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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * 书源设置中心结果回传（`POST /api/sources/{id}/browser/result`）的路由契约测试。
 *
 * 历史缺口：无头端 `startBrowserAwait` 是 stub（body 恒为空）＋内置浏览器 iframe 无
 * `allow-same-origin`（`document.cookie` 抛 SecurityError）⇒ 设置页的几条出口全部到不了
 * 书源 JS，用户表现为「设置完点确定，提示未读取到设置结果，已保留原配置」。
 * 本测试锁定替代通道：页面结果 → postMessage → 宿主回传 → 服务端落库源变量。
 */
class SourceBrowserResultRouteTest {

    // 书源生态允许 bookSourceUrl 使用自定义标识（如真实的大灰狼融合VIP5.0），
    // 这类书源的入口只能来自书源 JS 生成的内联页
    private val sourceId = "aggregate-source-1"
    private val sourceJson =
        """{"bookSourceUrl":"$sourceId","bookSourceName":"大灰狼融合VIP5.0（测试源）","loginUi":"[]"}"""

    private suspend fun HttpClient.login(password: String): String {
        val resp = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<LoginResponse>().csrfToken
    }

    /** 直接读库自证落盘结果（接口只回 boolean，落库内容必须从持久层看）。 */
    private fun storedVariable(config: ServerConfig): String? {
        val db = Database(config.databasePath)
        return try {
            db.getSourceVariable(sourceId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `browser result saves normalized source variable and rejects bad input`() = testApplication {
        val dbPath = Files.createTempFile("legado-browser-result", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-browser-result-covers")
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
            val csrf = client.login("test-password-1234")
            val path = "/api/sources/$sourceId"

            val importResp = client.post("/api/sources/import") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(ImportRequest(listOf(sourceJson)))
            }
            assertEquals(HttpStatusCode.OK, importResp.status)

            // 1) 书源脚本生成的内联页 → 拿到一张浏览器会话票据
            val inlineHtml =
                """<html><body><script id="source-settings-final-result" type="application/json"></script></body></html>"""
            val dataUrl = "data:text/html;base64," +
                Base64.getEncoder().encodeToString(inlineHtml.toByteArray(Charsets.UTF_8))
            val sessionResp = client.post("$path/browser/session") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SourceBrowserSessionRequest(dataUrl))
            }
            assertEquals(HttpStatusCode.OK, sessionResp.status)
            val session = sessionResp.body<SourceBrowserSessionResponse>()
            assertTrue("内联 data 地址必须走 inlineOnly 托管", session.inlineOnly)

            val settings = buildJsonObject {
                put("tab", "听书")
                put("sources", "书旗")
                put("server", "https://v5.langge.uk")
                put("shuqi_tone_id", "默认音色")
                put("pstyle", 0)
                put("_settings_nonce", "1790742645198_843225")
            }

            // 2) 未鉴权 / 票据无效 / 载荷无关，一律拒绝且不落库
            val unauthenticated = client.post("$path/browser/result") {
                contentType(ContentType.Application.Json)
                setBody(SourceBrowserResultRequest(session.token, settings, "source-settings-final-result"))
            }
            assertTrue(
                "未鉴权必须被拒",
                unauthenticated.status == HttpStatusCode.Unauthorized ||
                    unauthenticated.status == HttpStatusCode.Forbidden,
            )

            val badToken = client.post("$path/browser/result") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SourceBrowserResultRequest("deadbeef", settings, "source-settings-final-result"))
            }
            assertEquals(HttpStatusCode.BadRequest, badToken.status)

            val unrelated = client.post("$path/browser/result") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    SourceBrowserResultRequest(
                        session.token,
                        buildJsonObject { put("foo", 1) },
                        "source-settings-result",
                    ),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, unrelated.status)
            assertNull("被拒的请求不得写库", storedVariable(config))

            // 3) 合法结果 → 落库并按书源 JS 的口径归一化
            val saved = client.post("$path/browser/result") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SourceBrowserResultRequest(session.token, settings, "source-settings-final-result"))
            }
            assertEquals(HttpStatusCode.OK, saved.status)
            val savedBody = saved.body<SourceBrowserResultResponse>()
            assertTrue(savedBody.saved)
            assertEquals(5, savedBody.keys)

            val stored = storedVariable(config)
            assertNotNull("源变量未落库", stored)
            assertTrue(stored!!.contains("\"tab\":\"听书\""))
            assertTrue(stored.contains("\"sources\":\"书旗\""))
            assertTrue(stored.contains("\"shuqi_tone_id\":\"multi_role\""))
            assertTrue(stored.contains("\"pstyle\":\"0\""))
            assertFalse("一次性 nonce 不能落库", stored.contains("_settings_nonce"))

            // 4) 复用同一票据的后续保存（页面每次改动都会回传）覆盖为最新设置
            val again = client.post("$path/browser/result") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    SourceBrowserResultRequest(
                        session.token,
                        buildJsonObject {
                            put("tab", "漫画")
                            put("sources", "全部")
                            put("pstyle", "7")
                        },
                        "source-settings-final-result",
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, again.status)
            val latest = storedVariable(config)!!
            assertTrue(latest.contains("\"tab\":\"漫画\""))
            assertTrue(latest.contains("\"pstyle\":\"7\""))
            assertFalse("旧值必须被最新设置覆盖", latest.contains("书旗"))
        } finally {
            // Windows 不允许删除仍被服务端 WAL 连接占用的 sqlite（与仓库既有 52 个失败同源），
            // 清理失败不得影响断言结论
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `login action replaces the stale settings readback toast when opening an inline page`() {
        val dbPath = Files.createTempFile("legado-settings-toast", ".sqlite").toString()
        val db = Database(dbPath)
        db.initialize("admin123")
        try {
            val runner = RuleRunner(database = db)
            val dataUrl = "data:text/html;base64," +
                Base64.getEncoder().encodeToString("<b>设置中心</b>".toByteArray(Charsets.UTF_8))
            val action = """
                java.toast('未读取到设置结果，已保留原配置。');
                java.startBrowserAwait('$dataUrl', '书源设置', false);
            """.trimIndent()

            val outcome = runner.executeLoginAction("""{"bookSourceUrl":"aggregate-source-1"}""", action, emptyMap())

            assertTrue("内联地址必须回传给前端打开内置浏览器", outcome.openUrl!!.startsWith("data:text/html"))
            assertFalse(
                "无头端必然出现的误报必须被替换，用户不应看到「未读取到设置结果」",
                outcome.toastMessages.any { it.contains("未读取到设置结果") },
            )
            assertTrue(
                "应改为如实提示：" + outcome.toastMessages,
                outcome.toastMessages.any { it.contains("改动会自动保存") },
            )

            // 不涉及内置浏览器的提示必须原样保留（严禁误伤普通书源的提示）
            val plain = runner.executeLoginAction("""{"bookSourceUrl":"aggregate-source-2"}""", "java.toast('未读取到设置结果');", emptyMap())
            assertNull(plain.openUrl)
            assertTrue(
                "普通提示不得被过滤：" + plain.toastMessages,
                plain.toastMessages.any { it.contains("未读取到设置结果") },
            )
        } finally {
            runCatching { db.close() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
        }
    }
}
