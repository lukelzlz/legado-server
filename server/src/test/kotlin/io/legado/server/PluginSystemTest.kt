package io.legado.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.legado.plugin.api.LegadoPlugin
import io.legado.plugin.api.PluginJson
import io.legado.server.plugins.PluginManifests
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * End-to-end coverage of the plugin system: discovery, the JS sandbox bridge, permission
 * enforcement, settings persistence, enable/disable, and the JVM jar path (exercised through a jar
 * this suite assembles at runtime — see [JarFixturePlugin]).
 */
class PluginSystemTest {

    private val adminPassword = "test-password-1234"

    @Test
    fun `js plugin registers routes, persists storage and honours permissions`() = testApplication {
        val (dbPath, coverDir, pluginDir) = tempPaths()
        try {
            writeFixturePlugin(pluginDir)
            application { legadoApplication(config(dbPath, coverDir, pluginDir)) }
            val client = jsonClient()
            val csrf = login(client)

            val plugins = client.get("/api/plugins").bodyAsText()
            assertTrue("插件列表应包含 fixture: $plugins", plugins.contains("\"id\":\"fixture\""))

            // A JS route returns an explicit response descriptor and reads its own settings.
            val hello = client.get("/api/plugins/fixture/r/hello")
            assertEquals(HttpStatusCode.OK, hello.status)
            assertTrue(hello.bodyAsText(), hello.bodyAsText().contains("default-greeting"))

            // Settings written through the admin API become visible to the running script.
            val updated = client.put("/api/plugins/fixture/settings") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"greeting":"你好插件"}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertTrue(client.get("/api/plugins/fixture/r/hello").bodyAsText().contains("你好插件"))

            // The settings object is arbitrary JSON, so reading it back must work too — this is the
            // first call the demo plugin page makes, and a failure here surfaces as a bare
            // "服务器内部错误" in the UI.
            //
            // The fixture deliberately declares a string, a boolean and a number: a homogeneous
            // settings object still serialises fine, which is exactly why an earlier version of this
            // test missed the bug (`Serializing collections of different element types is not yet supported`).
            val readBack = client.get("/api/plugins/fixture/settings")
            assertEquals(HttpStatusCode.OK, readBack.status)
            val readBackBody = readBack.bodyAsText()
            assertTrue(readBackBody, readBackBody.contains("你好插件"))
            assertTrue(readBackBody, readBackBody.contains("\"showBadge\":true"))
            assertTrue(readBackBody, readBackBody.contains("\"limit\":10"))

            // Plugin storage survives across requests inside the same process.
            val echo = client.post("/api/plugins/fixture/r/echo") { setBody("payload-42") }
            assertEquals(HttpStatusCode.OK, echo.status)
            assertTrue(client.get("/api/plugins/fixture/r/stored").bodyAsText().contains("payload-42"))

            // A capability that was never declared is refused at the bridge, not silently ignored.
            val denied = client.get("/api/plugins/fixture/r/denied")
            assertEquals(HttpStatusCode.InternalServerError, denied.status)
            assertTrue(denied.bodyAsText(), denied.bodyAsText().contains("sources.read"))

            // Routed parameters arrive under their template name.
            val withParam = client.get("/api/plugins/fixture/r/echo/plainvalue")
            assertEquals(HttpStatusCode.OK, withParam.status)
            assertTrue(withParam.bodyAsText(), withParam.bodyAsText().contains("plainvalue"))

            // A JS plugin that omits the charset must still get a UTF-8 declaration, otherwise
            // clients fall back to Latin-1 and turn CJK text into mojibake.
            val charset = client.get("/api/plugins/fixture/r/charset")
            assertEquals(HttpStatusCode.OK, charset.status)
            assertTrue(
                charset.headers.toString(),
                charset.headers["Content-Type"]?.contains("charset=utf-8", ignoreCase = true) == true,
            )
            assertTrue(charset.bodyAsText().contains("中文正文"))
        } finally {
            cleanup(dbPath, coverDir, pluginDir)
        }
    }

    @Test
    fun `disabling a plugin unmounts its routes and re-enabling restores them`() = testApplication {
        val (dbPath, coverDir, pluginDir) = tempPaths()
        try {
            writeFixturePlugin(pluginDir)
            application { legadoApplication(config(dbPath, coverDir, pluginDir)) }
            val client = jsonClient()
            val csrf = login(client)

            assertEquals(HttpStatusCode.OK, client.get("/api/plugins/fixture/r/hello").status)

            val disabled = client.post("/api/plugins/fixture/disable") { header("X-CSRF-Token", csrf) }
            assertEquals(HttpStatusCode.OK, disabled.status)
            assertTrue(disabled.bodyAsText().contains("\"enabled\":false"))
            // A stopped plugin explains why it is unreachable instead of pretending the route is gone.
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/plugins/fixture/r/hello").status)

            val enabled = client.post("/api/plugins/fixture/enable") { header("X-CSRF-Token", csrf) }
            assertEquals(HttpStatusCode.OK, enabled.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/plugins/fixture/r/hello").status)
        } finally {
            cleanup(dbPath, coverDir, pluginDir)
        }
    }

    @Test
    fun `jvm jar plugin is discovered through ServiceLoader and serves its routes`() = testApplication {
        val (dbPath, coverDir, pluginDir) = tempPaths()
        try {
            JarFixturePlugin.reset()
            installJarPlugin(pluginDir)
            application { legadoApplication(config(dbPath, coverDir, pluginDir)) }
            val client = jsonClient()
            val csrf = login(client)

            val plugins = client.get("/api/plugins").bodyAsText()
            assertTrue(plugins, plugins.contains("\"id\":\"jarfix\""))
            assertTrue(plugins, plugins.contains("\"runtime\":\"jar\""))

            // The handler proves the jar was actually loaded and activated (not merely listed).
            val route = client.get("/api/plugins/jarfix/r/hello")
            assertEquals(HttpStatusCode.OK, route.status)
            assertTrue(route.bodyAsText(), route.bodyAsText().contains("\"from\":\"jar\""))

            // A jar plugin declaring a public route must be reachable without a session.
            assertEquals(HttpStatusCode.OK, client.get("/api/plugins/jarfix/r/open").status)

            // Disabling must run deactivate() on the plugin instance.
            assertEquals(HttpStatusCode.OK, client.post("/api/plugins/jarfix/disable") { header("X-CSRF-Token", csrf) }.status)
            assertTrue("停用后应调用 deactivate()", JarFixturePlugin.deactivated)
        } finally {
            cleanup(dbPath, coverDir, pluginDir)
        }
    }

    @Test
    fun `manifest validation rejects unusable plugins`() {
        val directory = Files.createTempDirectory("legado-plugin-invalid")
        try {
            Files.writeString(directory.resolve(PluginManifests.FILE_NAME), """{"id":"Bad Id","name":"x","server":"server.js"}""")
            Files.writeString(directory.resolve("server.js"), "")
            val error = assertThrows(IllegalArgumentException::class.java) { PluginManifests.read(directory) }
            assertTrue(error.message!!, error.message!!.contains("非法"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `host api version guard rejects plugins built for a newer server`() {
        val directory = Files.createTempDirectory("legado-plugin-future")
        try {
            Files.writeString(
                directory.resolve(PluginManifests.FILE_NAME),
                """{"id":"future","name":"future","apiVersion":99,"server":"server.js"}""",
            )
            Files.writeString(directory.resolve("server.js"), "")
            val error = assertThrows(IllegalArgumentException::class.java) { PluginManifests.read(directory) }
            assertTrue(error.message!!, error.message!!.contains("API 版本"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `manifest and scripts tolerate a utf8 byte order mark`() {
        val directory = Files.createTempDirectory("legado-plugin-bom")
        try {
            // Windows 编辑器（旧版记事本、PowerShell 的 -Encoding UTF8）会给 UTF-8 文件加 BOM。
            // 带 BOM 的 JSON 解析失败时的报错完全看不出是编码问题，所以这一层必须被容忍。
            val bom = "\uFEFF"
            Files.writeString(
                directory.resolve(PluginManifests.FILE_NAME),
                bom + """{"id":"bom","name":"带 BOM 的插件","server":"server.js"}""",
            )
            Files.writeString(directory.resolve("server.js"), bom + "legado.log.info('ok');")
            val descriptor = PluginManifests.read(directory)

            assertEquals("bom", descriptor.manifest.id)
            assertEquals("带 BOM 的插件", descriptor.manifest.name)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `dependency free json codec round-trips plugin payloads`() {
        val payload = mapOf(
            "name" to "阅读/reader",
            "count" to 42L,
            "ratio" to 0.5,
            "enabled" to true,
            "missing" to null,
            "tags" to listOf("a", "b\"c"),
            "nested" to mapOf("deep" to listOf(1L, 2L)),
        )
        val encoded = PluginJson.write(payload)
        assertEquals(payload, PluginJson.parse(encoded))

        // Escapes and astral-plane characters survive a round trip, and malformed input fails loudly.
        assertEquals("line\nbreak", PluginJson.parse(PluginJson.write("line\nbreak")))
        assertEquals("中文 😀", PluginJson.parse(PluginJson.write("中文 😀")))
        assertThrows(IllegalArgumentException::class.java) { PluginJson.parse("""{"a":}""") }
    }

    // --- fixtures --------------------------------------------------------------------------

    /**
     * Client wired for this suite: JSON body support plus a cookie jar.
     *
     * Centralised because forgetting `ContentNegotiation` makes [login] fail with a confusing
     * "Fail to prepare request body for sending" instead of anything about the test at hand.
     */
    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        install(HttpCookies)
    }

    private fun tempPaths(): Triple<String, Path, Path> = Triple(
        Files.createTempFile("legado-plugin-test", ".sqlite").toString(),
        Files.createTempDirectory("legado-plugin-covers"),
        Files.createTempDirectory("legado-plugin-dir"),
    )

    private fun config(dbPath: String, coverDir: Path, pluginDir: Path) = ServerConfig(
        host = "0.0.0.0",
        port = 8080,
        databasePath = dbPath,
        coverCacheDirectory = coverDir,
        pluginsDirectory = pluginDir,
        initialAdminPassword = adminPassword,
        secureCookies = false,
    )

    private suspend fun login(client: HttpClient): String {        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(adminPassword))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body<LoginResponse>().csrfToken
    }

    private fun writeFixturePlugin(directory: Path) {
        val pluginDir = directory.resolve("fixture")
        Files.createDirectories(pluginDir)
        Files.writeString(
            pluginDir.resolve(PluginManifests.FILE_NAME),
            """
            {
              "id": "fixture",
              "name": "测试插件",
              "version": "1.0.0",
              "apiVersion": 1,
              "server": "server.js",
              "permissions": ["storage", "settings", "books.read", "events"],
              "settings": [
                { "key": "greeting", "label": "问候语", "type": "text", "default": "default-greeting" },
                { "key": "showBadge", "label": "显示徽标", "type": "boolean", "default": true },
                { "key": "limit", "label": "条数", "type": "number", "default": 10 }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            pluginDir.resolve("server.js"),
            """
            legado.route('GET', '/hello', function (req) {
              return { status: 200, contentType: 'application/json',
                       body: JSON.stringify({ greeting: legado.settings.get('greeting', 'default-greeting'), plugin: legado.pluginId }) };
            });
            legado.route('POST', '/echo', function (req) {
              legado.storage.set('last', req.body);
              return { echo: req.body };
            });
            legado.route('GET', '/stored', function () {
              return { last: legado.storage.get('last') };
            });
            legado.route('GET', '/echo/{value}', function (req) {
              return { value: req.query.value };
            });
            legado.route('GET', '/denied', function () {
              return legado.sources.list(null);
            });
            legado.route('GET', '/charset', function () {
              return { status: 200, contentType: 'application/json', body: '{"text":"中文正文"}' };
            });
            """.trimIndent(),
        )
    }

    /**
     * Builds a plugin folder whose backend is a *real* jar, so the ServiceLoader path is exercised
     * rather than simulated: the jar carries the compiled plugin class plus the
     * `META-INF/services` declaration the host looks for.
     */
    private fun installJarPlugin(pluginDirectory: Path) {
        val target = pluginDirectory.resolve("jarfix")
        Files.createDirectories(target.resolve("lib"))
        Files.writeString(
            target.resolve(PluginManifests.FILE_NAME),
            """
            {
              "id": "jarfix",
              "name": "JAR 插件",
              "version": "1.0.0",
              "apiVersion": 1,
              "jars": ["lib/jarfix-plugin.jar"],
              "permissions": ["routes.public"]
            }
            """.trimIndent(),
        )

        val jarPath = target.resolve("lib/jarfix-plugin.jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            val className = JarFixturePlugin::class.java.name
            jar.putNextEntry(JarEntry("META-INF/services/${LegadoPlugin::class.java.name}"))
            jar.write("$className\n".toByteArray(Charsets.UTF_8))
            jar.closeEntry()

            val classBytes = javaClass.getResourceAsStream("/${className.replace('.', '/')}.class")
                ?: error("找不到已编译的测试插件类: $className")
            classBytes.use { input ->
                jar.putNextEntry(JarEntry("${className.replace('.', '/')}.class"))
                jar.write(input.readBytes())
                jar.closeEntry()
            }
        }
    }

    /**
     * Best-effort cleanup.
     *
     * Windows refuses to delete a SQLite file while the driver still holds a handle, and the whole
     * suite's teardown inherits that quirk (the pre-existing tests hit it on this platform too).
     * Cleanup therefore must not turn into the assertion: a leftover temp file is noise, not a
     * plugin-system failure.
     */
    private fun cleanup(dbPath: String, coverDir: Path, pluginDir: Path) {
        runCatching { Files.deleteIfExists(Path.of(dbPath)) }
        runCatching { coverDir.toFile().deleteRecursively() }
        runCatching { pluginDir.toFile().deleteRecursively() }
    }
}
