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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.file.Files

class ExploreRoutesTest {

    @Test
    fun `explore sources, categories and books endpoints`() = testApplication {
        val dbPath = Files.createTempFile("legado-explore-routes", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-explore-routes")
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

            val csrf = login(client, "test-password-1234")

            // 导入两个书源：一个带 exploreUrl，一个不带 exploreUrl
            val sources = listOf(
                """{
                    "bookSourceUrl": "https://explore.example",
                    "bookSourceName": "有发现页书源",
                    "bookSourceGroup": "精品",
                    "exploreUrl": "玄幻榜::/rank/xh_{{page}}\n都市榜::/rank/ds_{{page}}",
                    "ruleSearch": {
                        "bookList": ".item",
                        "name": ".title@text",
                        "bookUrl": ".title@href"
                    }
                }""",
                """{
                    "bookSourceUrl": "https://no-explore.example",
                    "bookSourceName": "无发现页书源",
                    "bookSourceGroup": "普通",
                    "searchUrl": "/search?k={{key}}"
                }"""
            )

            val importRes = client.post("/api/sources/import") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(ImportRequest(sources))
            }
            assertEquals(HttpStatusCode.OK, importRes.status)

            // 1. GET /api/explore/sources 只返回带 exploreUrl 的书源
            val sourcesRes = client.get("/api/explore/sources")
            assertEquals(HttpStatusCode.OK, sourcesRes.status)
            val exploreSources = sourcesRes.body<List<ExploreSourceItem>>()
            assertEquals(1, exploreSources.size)
            assertEquals("https://explore.example", exploreSources[0].id)
            assertEquals("有发现页书源", exploreSources[0].name)

            // 2. GET /api/explore/categories?sourceId=...
            val categoriesRes = client.get("/api/explore/categories?sourceId=" + URLEncoder.encode("https://explore.example", Charsets.UTF_8))
            assertEquals(HttpStatusCode.OK, categoriesRes.status)
            val categories = categoriesRes.body<List<ExploreCategory>>()
            assertEquals(2, categories.size)
            assertEquals("玄幻榜", categories[0].title)
            assertEquals("/rank/xh_{{page}}", categories[0].url)
            assertEquals("都市榜", categories[1].title)
            assertEquals("/rank/ds_{{page}}", categories[1].url)

            // 3. GET /api/explore/categories 缺少 sourceId 或不存在
            val missingParamRes = client.get("/api/explore/categories")
            assertEquals(HttpStatusCode.BadRequest, missingParamRes.status)

            val notFoundRes = client.get("/api/explore/categories?sourceId=https://nonexistent.example")
            assertEquals(HttpStatusCode.NotFound, notFoundRes.status)

        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(dbPath))
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
