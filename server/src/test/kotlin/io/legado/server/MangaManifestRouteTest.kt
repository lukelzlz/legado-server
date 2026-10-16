package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 方案 E · P0：`POST /api/books/images/manifest` 的输入校验与鉴权，
 * 外加 `book_kind` 的**老数据回填**端到端验证。
 *
 * 刻意不在这里测「成功返回清单」：那需要真实上游图片站，属于 P0 的手工验收项
 * （见方案 E 的 P0 验收：用真实漫画源跑一章并记录是否需要 Referer/Cookie）。
 */
class MangaManifestRouteTest {

    private val comicSource = """{
        "bookSourceUrl": "https://manga.example.com",
        "bookSourceName": "测试漫画源",
        "bookSourceType": 2,
        "ruleContent": { "content": "@css:.comic@html" }
    }""".trimIndent()

    @Test
    fun `manifest route validates input, auth and backfills book kind`() = testApplication {
        val dbPath = Files.createTempFile("legado-manga-manifest", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-manga-manifest")

        // 预置「老数据」：书源是图片书源，但书架行的 book_kind 为空 ——
        // 期望应用启动时的幂等回填把它修成 comic。
        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            seed.importSources(listOf(comicSource), applyGroups = true)
            seed.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://manga.example.com",
                    bookUrl = "https://manga.example.com/book/1",
                    name = "测试漫画",
                    tocUrl = "https://manga.example.com/book/1",
                ),
                cover = null,
            )
        } finally {
            seed.close()
        }

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            // 1. 未登录 ⇒ 401
            val anonymous = client.post("/api/books/images/manifest") {
                contentType(ContentType.Application.Json)
                setBody(ContentRequest("https://manga.example.com", "https://manga.example.com/c/1"))
            }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

            val csrf = login(client, "test-password-1234")

            // 2. 缺 sourceId ⇒ 400
            val noSource = client.post("/api/books/images/manifest") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(ContentRequest("", "https://manga.example.com/c/1"))
            }
            assertEquals(HttpStatusCode.BadRequest, noSource.status)

            // 3. 缺 chapterUrl ⇒ 400（读接口不需要 CSRF，这里带上也无妨）
            val noChapter = client.post("/api/books/images/manifest") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(ContentRequest("https://manga.example.com", ""))
            }
            assertEquals(HttpStatusCode.BadRequest, noChapter.status)

            // 4. 书源不存在 ⇒ 404
            val unknown = client.post("/api/books/images/manifest") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(ContentRequest("https://unknown.example", "https://unknown.example/c/1"))
            }
            assertEquals(HttpStatusCode.NotFound, unknown.status)

            // 5. 老数据回填：book_kind 从 NULL 被修成 comic
            val shelf = client.get("/api/bookshelf").body<List<BookshelfItem>>()
            assertEquals(1, shelf.size)
            assertEquals(
                "initialize() 的幂等回填必须把 bookSourceType=2 的老书架行修成 comic",
                "comic",
                shelf[0].bookKind,
            )
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    private suspend fun login(client: io.ktor.client.HttpClient, pass: String): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = pass))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return res.body<LoginResponse>().csrfToken
    }
}
