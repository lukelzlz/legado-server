package io.legado.server

import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.Json

class HttpTtsTest {
    private val tempDir = Files.createTempDirectory("http-tts-test")
    private val dbPath = tempDir.resolve("test.sqlite").toString()
    private lateinit var db: Database
    private lateinit var httpTtsService: HttpTtsService

    @Before
    fun setup() {
        db = Database(dbPath)
        db.initialize("admin123")
        httpTtsService = HttpTtsService(db)
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testDatabaseCrud() {
        val tts = HttpTts(
            id = 1001L,
            name = "测试百度语音",
            url = "http://tts.baidu.com/text2audio",
            header = "{\"User-Agent\": \"TestAgent\"}",
            contentType = "audio/wav",
            concurrentRate = "2",
        )
        val saved = db.saveHttpTts(tts)
        assertEquals(1001L, saved.id)
        assertEquals("测试百度语音", saved.name)

        val fetched = db.getHttpTts(1001L)
        assertNotNull(fetched)
        assertEquals("http://tts.baidu.com/text2audio", fetched!!.url)
        assertEquals("audio/wav", fetched.contentType)

        val list = db.listHttpTts("百度")
        assertEquals(1, list.size)
        assertEquals("测试百度语音", list[0].name)

        val deleted = db.deleteHttpTts(1001L)
        assertTrue(deleted)
        assertNull(db.getHttpTts(1001L))
    }

    @Test
    fun testImportLegadoHttpTtsJson() {
        val sampleJson = """
        [
          {
            "concurrentRate": "0",
            "contentType": "audio/wav",
            "enabledCookieJar": false,
            "id": -100,
            "lastUpdateTime": 1759716268074,
            "name": "1.百度",
            "url": "http://tts.baidu.com/text2audio,{\n    \"method\": \"POST\",\n    \"body\": \"tex={{java.encodeURI(java.encodeURI(speakText))}}&spd={{(speakSpeed + 5) / 10 + 4}}\"\n}"
          },
          {
            "concurrentRate": "0",
            "contentType": "audio/mpeg",
            "enabledCookieJar": false,
            "id": -29,
            "lastUpdateTime": 1759716268087,
            "name": "2.阿里云语音",
            "url": "https://nls-gateway.cn-shanghai.aliyuncs.com/stream/v1/tts"
          }
        ]
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val list = json.decodeFromString<List<HttpTts>>(sampleJson)
        assertEquals(2, list.size)

        val resp = db.importHttpTts(list)
        assertEquals(2, resp.total)
        assertEquals(2, resp.imported)
        assertEquals(0, resp.failed)

        val all = db.listHttpTts()
        assertEquals(2, all.size)
        val baidu = db.getHttpTts(-100L)
        assertNotNull(baidu)
        assertEquals("1.百度", baidu!!.name)
        assertTrue(baidu.url.contains("POST"))
    }

    @Test
    fun testTemplateRenderingAndJsEvaluation() {
        val bindings = mapOf(
            "speakText" to "你好，世界",
            "speakSpeed" to 1.0,
            "speakVoice" to "zh-CN-Yunxi",
            "speakPitch" to 1.2,
        )

        // Basic variable replacement
        val t1 = "http://api.com/tts?text={{speakText}}&speed={{speakSpeed}}&voice={{speakVoice}}"
        val r1 = httpTtsService.renderTemplate(t1, bindings, urlEncodeRawVars = true)
        assertTrue(r1.contains("text=%E4%BD%A0%E5%A5%BD%EF%BC%8C%E4%B8%96%E7%95%8C"))
        assertTrue(r1.contains("speed=1.0"))
        assertTrue(r1.contains("voice=zh-CN-Yunxi"))

        // Math expression evaluation
        val t2 = "spd={{(speakSpeed + 5) / 10 + 4}}"
        val r2 = httpTtsService.renderTemplate(t2, bindings)
        assertEquals("spd=4.6", r2)

        // java.encodeURI evaluation
        val t3 = "tex={{java.encodeURI(speakText)}}"
        val r3 = httpTtsService.renderTemplate(t3, bindings)
        assertEquals("tex=%E4%BD%A0%E5%A5%BD%EF%BC%8C%E4%B8%96%E7%95%8C", r3)
    }

    @Test
    fun testSynthesizeAgainstMockHttpServer() {
        val mockMp3Bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte(), 0x00, 0x01)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var receivedMethod = ""
        var receivedBody = ""
        var receivedHeader = ""

        server.createContext("/text2audio") { exchange ->
            receivedMethod = exchange.requestMethod
            receivedBody = exchange.requestBody.bufferedReader().readText()
            receivedHeader = exchange.requestHeaders.getFirst("X-Custom-Header") ?: ""

            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, mockMp3Bytes.size.toLong())
            exchange.responseBody.use { it.write(mockMp3Bytes) }
        }
        server.start()

        try {
            val port = server.address.port
            val tts = HttpTts(
                id = 99L,
                name = "MockServerTTS",
                url = "http://127.0.0.1:$port/text2audio,{\n  \"method\": \"POST\",\n  \"headers\": {\"X-Custom-Header\": \"MyToken\"},\n  \"body\": \"text={{speakText}}&speed={{speakSpeed}}\"\n}",
                contentType = "audio/mpeg",
            )

            val (contentType, bytes) = httpTtsService.synthesize(tts, "春眠不觉晓", speed = 1.25)
            assertEquals("audio/mpeg", contentType)
            assertEquals(mockMp3Bytes.size, bytes.size)
            assertEquals("POST", receivedMethod)
            assertEquals("MyToken", receivedHeader)
            assertEquals("text=春眠不觉晓&speed=1.25", receivedBody)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testHttpTtsRoutes() = testApplication {
        val testDb = Files.createTempFile("http-tts-route-test", ".sqlite").toString()
        val coversDir = Files.createTempDirectory("http-tts-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0",
                port = 8080,
                databasePath = testDb,
                coverCacheDirectory = coversDir,
                webDavDirectory = coversDir.resolve("webdav"),
                initialAdminPassword = "admin123-tts",
                secureCookies = false,
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(HttpCookies)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }

            // Unauthenticated
            val unauth = client.get("/api/http-tts")
            assertEquals(HttpStatusCode.Unauthorized, unauth.status)

            // Login
            val login = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("admin123-tts"))
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val csrf = login.body<LoginResponse>().csrfToken

            // List empty
            val listResp = client.get("/api/http-tts")
            assertEquals(HttpStatusCode.OK, listResp.status)
            assertEquals(0, listResp.body<List<HttpTts>>().size)

            // Save new TTS
            val saveResp = client.post("/api/http-tts") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(HttpTts(name = "路由测试TTS", url = "http://example.com/tts"))
            }
            assertEquals(HttpStatusCode.OK, saveResp.status)
            val created = saveResp.body<HttpTts>()
            assertTrue(created.id > 0)
            assertEquals("路由测试TTS", created.name)

            // Get single
            val getResp = client.get("/api/http-tts/${created.id}")
            assertEquals(HttpStatusCode.OK, getResp.status)
            assertEquals("路由测试TTS", getResp.body<HttpTts>().name)

            // Import
            val importResp = client.post("/api/http-tts/import") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(listOf(
                    HttpTts(id = 201L, name = "导入TTS 1", url = "http://a.com/tts"),
                    HttpTts(id = 202L, name = "导入TTS 2", url = "http://b.com/tts"),
                ))
            }
            assertEquals(HttpStatusCode.OK, importResp.status)
            val importResult = importResp.body<HttpTtsImportResponse>()
            assertEquals(2, importResult.imported)

            // Delete
            val delResp = client.delete("/api/http-tts/${created.id}") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NoContent, delResp.status)
        } finally {
            testDb.let { runCatching { Files.deleteIfExists(Path.of(it)) } }
            coversDir.toFile().deleteRecursively()
        }
    }
}
