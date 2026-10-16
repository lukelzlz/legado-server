package io.legado.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 书籍变量在 **HTTP 契约层**的闭环：加入书架 → 编辑书籍信息（写 `custom`）→ 书架列表读回。
 *
 * 为什么必须有这一层：存储与沙箱的单测都过，**接线断掉照样全绿**。
 * 这一层的典型静默故障是「字段没进 SELECT」或「路由没把请求字段透传」——
 * 两者都不报错，只表现为"填了音色但没用"（本仓库历史上的封面 `cover_url` 就是这个形态）。
 */
class BookVariableRoutesTest {

    @Test
    fun `editing book info persists custom variable and the shelf returns it`() {
        val dbPath = Files.createTempFile("legado-book-variable-routes", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-book-variable-routes-dir")
        try {
            testApplication {
                val config = ServerConfig(
                    host = "0.0.0.0", port = 8080, databasePath = dbPath,
                    coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
                    initialAdminPassword = "test-password-1234", secureCookies = false,
                )
                application { legadoApplication(config) }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }

                val login = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("test-password-1234"))
                }
                assertEquals(HttpStatusCode.OK, login.status)
                val cookie = login.headers[io.ktor.http.HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.decodeFromString<LoginResponse>(login.bodyAsText()).csrfToken

                // 1) 加入书架
                val added = client.post("/api/bookshelf") {
                    header(io.ktor.http.HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"sourceId":"$SOURCE","bookUrl":"$BOOK","name":"十日终焉","author":"杀虫队队员","tocUrl":"$BOOK/toc"}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, added.status)

                // 2) 编辑书籍信息时写入书籍变量（听书音色编号）
                val updated = client.put("/api/bookshelf/info") {
                    header(io.ktor.http.HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"sourceId":"$SOURCE","bookUrl":"$BOOK","name":"十日终焉","customVariable":"6"}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, updated.status)
                assertTrue(
                    "编辑接口的响应必须带回 variable（前端据此回显）",
                    updated.bodyAsText().contains(""""variable":"{\"custom\":\"6\"}""""),
                )

                // 3) 书架列表必须能读到它
                val shelf = client.get("/api/bookshelf") { header(io.ktor.http.HttpHeaders.Cookie, cookie) }.bodyAsText()
                assertTrue("书架列表必须透出 variable 字段：$shelf", shelf.contains(""""variable":"{\"custom\":\"6\"}""""))

                // 4) 「只改书名」不得清掉它（服务端缺省语义 = 不动）
                val renameOnly = client.put("/api/bookshelf/info") {
                    header(io.ktor.http.HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"sourceId":"$SOURCE","bookUrl":"$BOOK","name":"十日终焉（改名）"}""")
                }
                assertEquals(HttpStatusCode.OK, renameOnly.status)
                assertTrue(
                    "改名不能顺手清掉书籍变量",
                    renameOnly.bodyAsText().contains(""""variable":"{\"custom\":\"6\"}""""),
                )
            }
        } finally {
            listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { candidate ->
                runCatching { Files.deleteIfExists(java.nio.file.Path.of(candidate)) }
            }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    private companion object {
        const val SOURCE = "大灰狼融合VIP5.0"
        const val BOOK = "https://api.example.com/book/1"
    }
}
