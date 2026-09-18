package io.legado.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WebDavRoutesTest {

    private val password = "webdav-admin-password"

    private class Fixture(val dataDir: Path, val dbPath: String, val config: ServerConfig)

    private fun fixture(): Fixture {
        val dataDir = Files.createTempDirectory("legado-webdav-data")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        val config = ServerConfig(
            host = "0.0.0.0",
            port = 8080,
            databasePath = dbPath,
            coverCacheDirectory = dataDir.resolve("covers"),
            webDavDirectory = dataDir.resolve("webdav"),
            initialAdminPassword = password,
            secureCookies = false,
        )
        return Fixture(dataDir, dbPath, config)
    }

    private fun cleanup(fixture: Fixture) {
        fixture.dataDir.toFile().deleteRecursively()
    }

    private fun HttpRequestBuilder.dav(method: String) {
        this.method = HttpMethod(method)
        basicAuth("legado", password)
    }

    private fun HttpRequestBuilder.dav() {
        basicAuth("legado", password)
    }

    private suspend fun HttpClient.loginSession(): String {
        val response = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body<LoginResponse>().csrfToken
    }

    @Test
    fun `webdav options advertises class 1 and 2 without authentication`() = testApplication {
        val fixture = fixture()
        try {
            application { legadoApplication(fixture.config) }
            val client = createClient { }

            val response = client.options("/webdav")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("1, 2", response.headers[HttpHeaders.DAV])
            assertEquals("DAV", response.headers["MS-Author-Via"])
            val allow = response.headers[HttpHeaders.Allow].orEmpty()
            listOf("PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "PUT", "DELETE").forEach {
                assertTrue("Allow 缺少 $it", allow.contains(it))
            }
            assertTrue(Files.isDirectory(fixture.dataDir.resolve("webdav")))
        } finally {
            cleanup(fixture)
        }
    }

    @Test
    fun `webdav rejects unauthenticated and wrong credentials`() = testApplication {
        val fixture = fixture()
        try {
            application { legadoApplication(fixture.config) }
            val client = createClient { }

            val anonymous = client.request("/webdav") { method = HttpMethod("PROPFIND") }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
            assertTrue(anonymous.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Basic"))

            val wrong = client.request("/webdav") {
                method = HttpMethod("PROPFIND")
                basicAuth("legado", "not-the-password")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)

            val ok = client.request("/webdav") {
                dav("PROPFIND")
                header(HttpHeaders.Depth, "0")
            }
            assertEquals(HttpStatusCode.MultiStatus, ok.status)
        } finally {
            cleanup(fixture)
        }
    }

    @Test
    fun `webdav upload listing range download move copy and delete round trip`() = testApplication {
        val fixture = fixture()
        try {
            application { legadoApplication(fixture.config) }
            val client = createClient { }
            val root = fixture.dataDir.resolve("webdav")

            assertEquals(HttpStatusCode.Created, client.request("/webdav/books") { dav("MKCOL") }.status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.request("/webdav/books") { dav("MKCOL") }.status)
            assertEquals(HttpStatusCode.Conflict, client.request("/webdav/books/deep/dir") { dav("MKCOL") }.status)

            val upload = client.put("/webdav/books/a.txt") {
                dav()
                setBody("hello webdav")
            }
            assertEquals(HttpStatusCode.Created, upload.status)
            assertEquals("hello webdav", Files.readString(root.resolve("books/a.txt")))

            val overwrite = client.put("/webdav/books/a.txt") {
                dav()
                setBody("hello again")
            }
            assertEquals(HttpStatusCode.NoContent, overwrite.status)
            assertEquals("hello again", Files.readString(root.resolve("books/a.txt")))

            val propfind = client.request("/webdav/books") {
                dav("PROPFIND")
                header(HttpHeaders.Depth, "1")
            }
            assertEquals(HttpStatusCode.MultiStatus, propfind.status)
            val propfindBody = propfind.bodyAsText()
            assertTrue(propfindBody.contains("<D:href>/webdav/books/</D:href>"))
            assertTrue(propfindBody.contains("<D:href>/webdav/books/a.txt</D:href>"))
            assertTrue(propfindBody.contains("<D:getcontentlength>11</D:getcontentlength>"))
            assertTrue(propfindBody.contains("<D:collection/>"))

            val infiniteDepth = client.request("/webdav/books") {
                dav("PROPFIND")
                header(HttpHeaders.Depth, "infinity")
            }
            assertEquals(HttpStatusCode.Forbidden, infiniteDepth.status)
            assertTrue(infiniteDepth.bodyAsText().contains("propfind-finite-depth"))

            val full = client.get("/webdav/books/a.txt") { dav() }
            assertEquals(HttpStatusCode.OK, full.status)
            assertEquals("hello again", full.bodyAsText())
            assertEquals("bytes", full.headers[HttpHeaders.AcceptRanges])

            val partial = client.get("/webdav/books/a.txt") {
                dav()
                header(HttpHeaders.Range, "bytes=0-4")
            }
            assertEquals(HttpStatusCode.PartialContent, partial.status)
            assertEquals("hello", partial.bodyAsText())
            assertEquals("bytes 0-4/11", partial.headers[HttpHeaders.ContentRange])

            val suffixRange = client.get("/webdav/books/a.txt") {
                dav()
                header(HttpHeaders.Range, "bytes=-5")
            }
            assertEquals(HttpStatusCode.PartialContent, suffixRange.status)
            assertEquals("again", suffixRange.bodyAsText())

            val invalidRange = client.get("/webdav/books/a.txt") {
                dav()
                header(HttpHeaders.Range, "bytes=99-")
            }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, invalidRange.status)
            assertEquals("bytes */11", invalidRange.headers[HttpHeaders.ContentRange])

            val head = client.head("/webdav/books/a.txt") { dav() }
            assertEquals(HttpStatusCode.OK, head.status)
            assertEquals("11", head.headers[HttpHeaders.ContentLength])

            val listing = client.get("/webdav/books") { dav() }
            assertEquals(HttpStatusCode.OK, listing.status)
            assertTrue(listing.bodyAsText().contains("a.txt"))

            val rootListing = client.get("/webdav") { dav() }
            assertEquals(HttpStatusCode.OK, rootListing.status)
            assertTrue(rootListing.bodyAsText().contains("books/"))

            val moved = client.request("/webdav/books/a.txt") {
                dav("MOVE")
                header(HttpHeaders.Destination, "/webdav/books/b.txt")
            }
            assertEquals(HttpStatusCode.Created, moved.status)
            assertTrue(Files.exists(root.resolve("books/b.txt")))
            assertFalse(Files.exists(root.resolve("books/a.txt")))

            val copied = client.request("/webdav/books/b.txt") {
                dav("COPY")
                header(HttpHeaders.Destination, "http://localhost/webdav/books/c.txt")
            }
            assertEquals(HttpStatusCode.Created, copied.status)
            assertEquals("hello again", Files.readString(root.resolve("books/c.txt")))

            // 目标已存在且 Overwrite: F → 412；默认覆盖 → 204。
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                client.request("/webdav/books/b.txt") {
                    dav("COPY")
                    header(HttpHeaders.Destination, "/webdav/books/c.txt")
                    header(HttpHeaders.Overwrite, "F")
                }.status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client.request("/webdav/books/b.txt") {
                    dav("COPY")
                    header(HttpHeaders.Destination, "/webdav/books/c.txt")
                }.status,
            )
            // 源与目标相同按 RFC 4918 返回 403。
            assertEquals(
                HttpStatusCode.Forbidden,
                client.request("/webdav/books/b.txt") {
                    dav("MOVE")
                    header(HttpHeaders.Destination, "/webdav/books/b.txt")
                }.status,
            )
            // 目标位于源目录内部（自包含复制）同样拒绝。
            assertEquals(
                HttpStatusCode.Forbidden,
                client.request("/webdav/books") {
                    dav("COPY")
                    header(HttpHeaders.Destination, "/webdav/books/inner")
                }.status,
            )

            assertEquals(
                HttpStatusCode.BadGateway,
                client.request("/webdav/books/b.txt") {
                    dav("MOVE")
                    header(HttpHeaders.Destination, "https://example.com/elsewhere/b.txt")
                }.status,
            )

            assertEquals(
                HttpStatusCode.Forbidden,
                client.put("/webdav") {
                    dav()
                    setBody("nope")
                }.status,
            )

            assertEquals(HttpStatusCode.NoContent, client.delete("/webdav/books") { dav() }.status)
            assertFalse(Files.exists(root.resolve("books")))
            assertEquals(HttpStatusCode.NotFound, client.get("/webdav/books") { dav() }.status)
        } finally {
            cleanup(fixture)
        }
    }

    @Test
    fun `webdav lock lifecycle returns tokens and supports refresh`() = testApplication {
        val fixture = fixture()
        try {
            application { legadoApplication(fixture.config) }
            val client = createClient { }
            val lockInfo = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:lockinfo xmlns:D="DAV:">
                  <D:lockscope><D:exclusive/></D:lockscope>
                  <D:locktype><D:write/></D:locktype>
                  <D:owner><D:href>集成测试客户端</D:href></D:owner>
                </D:lockinfo>
            """.trimIndent()

            val lock = client.request("/webdav/locked.txt") {
                dav("LOCK")
                header(HttpHeaders.Timeout, "Second-600")
                setBody(lockInfo)
            }
            assertEquals(HttpStatusCode.Created, lock.status)
            val token = lock.headers[HttpHeaders.LockToken]?.trim('<', '>')
            assertNotNull(token)
            assertTrue(token!!.startsWith("opaquelocktoken:"))
            assertTrue(lock.bodyAsText().contains("<D:locktoken><D:href>$token</D:href></D:locktoken>"))
            assertTrue(lock.bodyAsText().contains("<D:timeout>Second-59"))
            assertTrue(Files.exists(fixture.dataDir.resolve("webdav/locked.txt")))

            val propfind = client.request("/webdav/locked.txt") {
                dav("PROPFIND")
                header(HttpHeaders.Depth, "0")
            }
            assertEquals(HttpStatusCode.MultiStatus, propfind.status)
            assertTrue(propfind.bodyAsText().contains("opaquelocktoken"))

            val refreshed = client.request("/webdav/locked.txt") {
                dav("LOCK")
                header(HttpHeaders.If, "(<$token>)")
            }
            assertEquals(HttpStatusCode.OK, refreshed.status)
            assertEquals(token, refreshed.headers[HttpHeaders.LockToken]?.trim('<', '>'))

            assertEquals(
                HttpStatusCode.Conflict,
                client.request("/webdav/locked.txt") {
                    dav("UNLOCK")
                    header(HttpHeaders.LockToken, "<opaquelocktoken:not-the-token>")
                }.status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client.request("/webdav/locked.txt") {
                    dav("UNLOCK")
                    header(HttpHeaders.LockToken, "<$token>")
                }.status,
            )
            assertEquals(
                HttpStatusCode.Conflict,
                client.request("/webdav/locked.txt") {
                    dav("UNLOCK")
                    header(HttpHeaders.LockToken, "<$token>")
                }.status,
            )
        } finally {
            cleanup(fixture)
        }
    }

    @Test
    fun `webdav proppatch refuses dead properties and traversal paths are rejected`() = testApplication {
        val fixture = fixture()
        try {
            application { legadoApplication(fixture.config) }
            val client = createClient { }

            assertEquals(
                HttpStatusCode.NotFound,
                client.request("/webdav/missing.txt") { dav("PROPPATCH") }.status,
            )

            val storage = WebDavStorage(fixture.dataDir.resolve("webdav"))
            assertEquals(storage.root.resolve("a/b"), storage.resolve("a/./b"))
            assertEquals(storage.root, storage.resolve(""))
            assertEquals(storage.root, storage.resolve("/"))
            assertNull(storage.resolve("../escape.txt"))
            assertNull(storage.resolve("a/../../escape.txt"))
            assertNull(storage.resolve("a\\b.txt"))
        } finally {
            cleanup(fixture)
        }
    }

    @Test
    fun `range header parsing follows http semantics`() {
        assertEquals(ByteRange.Satisfiable(0, 5), parseByteRange("bytes=0-4", 12))
        assertEquals(ByteRange.Satisfiable(5, 12), parseByteRange("bytes=5-", 12))
        assertEquals(ByteRange.Satisfiable(9, 12), parseByteRange("bytes=-3", 12))
        assertEquals(ByteRange.Satisfiable(0, 1), parseByteRange("bytes=0-99", 1))
        assertEquals(ByteRange.Unsatisfiable, parseByteRange("bytes=99-", 12))
        assertEquals(ByteRange.Unsatisfiable, parseByteRange("bytes=5-2", 12))
        assertEquals(ByteRange.Unsatisfiable, parseByteRange("bytes=0-5", 0))
        assertNull(parseByteRange(null, 12))
        assertNull(parseByteRange("bytes=0-4,6-7", 12))
    }

    @Test
    fun `webdav settings info reports storage usage and directory entries`() = testApplication {
        val fixture = fixture()
        try {
            application { legadoApplication(fixture.config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/webdav/info").status)

            assertEquals(HttpStatusCode.Created, client.request("/webdav/books") { dav("MKCOL") }.status)
            assertEquals(
                HttpStatusCode.Created,
                client.put("/webdav/books/book.txt") {
                    dav()
                    setBody("0123456789")
                }.status,
            )
            assertEquals(HttpStatusCode.Created, client.put("/webdav/root.txt") { dav(); setBody("abc") }.status)

            client.loginSession()

            val root = client.get("/api/webdav/info").body<WebDavInfoResponse>()
            assertEquals("/webdav", root.url)
            assertEquals(fixture.dataDir.resolve("webdav").toString(), root.directory)
            assertEquals("", root.path)
            assertNull(root.parent)
            assertEquals(2, root.fileCount)
            assertEquals(1, root.directoryCount)
            assertEquals(13L, root.totalBytes)
            assertTrue(root.entries.first().directory)
            assertEquals("books", root.entries.first().name)
            assertEquals(listOf("books", "root.txt"), root.entries.map { it.name })

            val nested = client.get("/api/webdav/info?path=books").body<WebDavInfoResponse>()
            assertEquals("books", nested.path)
            assertEquals("", nested.parent)
            assertEquals(listOf("book.txt"), nested.entries.map { it.name })
            assertEquals(10L, nested.entries.first().size)
            assertTrue(nested.entries.first().modifiedAt > 0)

            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/webdav/info?path=${"../escape"}").status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/webdav/info?path=missing").status,
            )
        } finally {
            cleanup(fixture)
        }
    }

    @Test
    fun `webdav browser session writes require csrf while reads accept the session cookie`() = testApplication {
        val fixture = fixture()
        try {
            application { legadoApplication(fixture.config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val csrf = client.loginSession()

            // 会话鉴权的读操作（设置页下载/浏览）无需 CSRF
            assertEquals(HttpStatusCode.Created, client.request("/webdav/notes") { dav("MKCOL") }.status)
            assertEquals(
                HttpStatusCode.Created,
                client.put("/webdav/notes/from-client.txt") { dav(); setBody("client") }.status,
            )
            assertEquals("client", client.get("/webdav/notes/from-client.txt").bodyAsText())

            // 会话鉴权的写操作必须携带 CSRF 头
            assertEquals(
                HttpStatusCode.Forbidden,
                client.put("/webdav/notes/from-browser.txt") { setBody("browser") }.status,
            )
            assertEquals(
                HttpStatusCode.Created,
                client.put("/webdav/notes/from-browser.txt") {
                    header(AuthService.CSRF_HEADER, csrf)
                    setBody("browser")
                }.status,
            )
            assertEquals("browser", Files.readString(fixture.dataDir.resolve("webdav/notes/from-browser.txt")))

            assertEquals(
                HttpStatusCode.Forbidden,
                client.delete("/webdav/notes/from-browser.txt").status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/webdav/notes/from-browser.txt") {
                    header(AuthService.CSRF_HEADER, csrf)
                }.status,
            )
            assertFalse(Files.exists(fixture.dataDir.resolve("webdav/notes/from-browser.txt")))
        } finally {
            cleanup(fixture)
        }
    }
}

