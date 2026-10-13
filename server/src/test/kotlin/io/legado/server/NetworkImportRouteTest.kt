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

/**
 * 网络书源导入两条路由的行为契约。
 *
 * 这里**只覆盖不需要真实网络的分支**（鉴权、CSRF、地址校验、票据校验与四语错误码）：
 * 拉取与解析逻辑由 [NetworkSourceImportTest] 用注入式 fetcher 覆盖，单测不该依赖外网。
 */
class NetworkImportRouteTest {

    private val previewPath = "/api/sources/import-url/preview"
    private val commitPath = "/api/sources/import-url/commit"

    private suspend fun HttpClient.login(password: String): String {
        val resp = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<LoginResponse>().csrfToken
    }

    /** 断言一次 preview 调用返回指定错误码（地址校验失败都发生在发起网络请求之前）。 */
    private suspend fun HttpClient.expectPreviewError(
        csrf: String,
        url: String,
        expectedCode: String,
    ) {
        val resp = post(previewPath) {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(NetworkImportPreviewRequest(url))
        }
        assertEquals("地址 $url 应被拒", HttpStatusCode.BadRequest, resp.status)
        assertEquals(expectedCode, resp.body<ApiError>().code)
    }

    @Test
    fun `network import requires session and csrf and maps error codes`() = testApplication {
        val dbPath = Files.createTempFile("legado-netimport-route", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-netimport-route-covers")
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

            // 1) 未登录一律拒绝
            val anonymous = client.post(previewPath) {
                contentType(ContentType.Application.Json)
                setBody(NetworkImportPreviewRequest("https://example.com/a.json"))
            }
            assertTrue(
                "未鉴权必须被拒",
                anonymous.status == HttpStatusCode.Unauthorized || anonymous.status == HttpStatusCode.Forbidden,
            )

            val csrf = client.login("test-password-1234")

            // 2) 有会话但缺 CSRF ⇒ 拒绝（这是写操作，必须带 CSRF）
            val noCsrf = client.post(commitPath) {
                contentType(ContentType.Application.Json)
                setBody(NetworkImportCommitRequest("token", listOf(0), null))
            }
            assertTrue(
                "缺 CSRF 必须被拒",
                noCsrf.status == HttpStatusCode.Forbidden || noCsrf.status == HttpStatusCode.Unauthorized,
            )

            // 3) 地址校验：全部在发起网络请求**之前**就拒绝，因此本用例不触网
            client.expectPreviewError(csrf, "   ", "import_url_required")
            client.expectPreviewError(csrf, "ftp://example.com/a.json", "import_url_scheme")
            client.expectPreviewError(csrf, "yuedu://booksource/importonline?src=x", "import_url_scheme")
            client.expectPreviewError(csrf, "http://127.0.0.1:8080/a.json", "import_target_blocked")
            client.expectPreviewError(csrf, "http://localhost/a.json", "import_target_blocked")
            client.expectPreviewError(csrf, "http://169.254.169.254/latest/meta-data", "import_target_blocked")

            // 4) 错误码稳定不变，但消息按 Accept-Language 走四语字典
            val localized = client.post(previewPath) {
                header(AuthService.CSRF_HEADER, csrf)
                header(HttpHeaders.AcceptLanguage, "en-US")
                contentType(ContentType.Application.Json)
                setBody(NetworkImportPreviewRequest("http://127.0.0.1/a.json"))
            }
            assertEquals(HttpStatusCode.BadRequest, localized.status)
            val localizedError = localized.body<ApiError>()
            assertEquals("import_target_blocked", localizedError.code)
            assertTrue(
                "en-US 下必须回英文文案，实际：${localizedError.message}",
                localizedError.message.contains("blocked"),
            )
            assertFalse(
                "en-US 下不应出现中文，实际：${localizedError.message}",
                localizedError.message.any { it.code in 0x4E00..0x9FFF },
            )

            // 5) 票据无效 / 已过期 ⇒ 明确提示重新拉取，而不是静默失败
            val staleTicket = client.post(commitPath) {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(NetworkImportCommitRequest("not-a-real-token", listOf(0), null))
            }
            assertEquals(HttpStatusCode.BadRequest, staleTicket.status)
            assertEquals("import_ticket_expired", staleTicket.body<ApiError>().code)
        } finally {
            // Windows 下服务端 WAL 仍占用 sqlite 时删除会失败（与仓库既有基线噪声同源）
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * **本地文件**预览路由。
     *
     * 关键契约是「与网络导入**同一条**预览/票据/落库管线」——因此这里不仅验证错误码，
     * 还要证明**本地预览签发的票据可以直接走 commit**（两条路共用一个落库实现）。
     */
    @Test
    fun `local json preview shares the ticket pipeline with the network import`() = testApplication {
        val dbPath = Files.createTempFile("legado-localimport-route", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-localimport-route-covers")
        val localPath = "/api/sources/import-json/preview"
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

            val one = """{"bookSourceUrl":"https://local1.example.com","bookSourceName":"本地源1"}"""
            val two = """{"bookSourceUrl":"https://local2.example.com","bookSourceName":"本地源2"}"""

            // 1) 未登录一律拒绝
            val anonymous = client.post(localPath) {
                contentType(ContentType.Application.Json)
                setBody(LocalSourcePreviewRequest("[$one]"))
            }
            assertTrue(
                "未鉴权必须被拒",
                anonymous.status == HttpStatusCode.Unauthorized || anonymous.status == HttpStatusCode.Forbidden,
            )

            val csrf = client.login("test-password-1234")

            // 2) 有会话但缺 CSRF ⇒ 拒绝（预览会签发票据，属写操作）
            val noCsrf = client.post(localPath) {
                contentType(ContentType.Application.Json)
                setBody(LocalSourcePreviewRequest("[$one]"))
            }
            assertTrue(
                "缺 CSRF 必须被拒",
                noCsrf.status == HttpStatusCode.Forbidden || noCsrf.status == HttpStatusCode.Unauthorized,
            )

            // 3) 正常内容 ⇒ 与网络导入同结构的预览
            val previewResp = client.post(localPath) {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(LocalSourcePreviewRequest("[$one,$two]", "我的书源.json"))
            }
            assertEquals(HttpStatusCode.OK, previewResp.status)
            val preview = previewResp.body<NetworkImportPreviewResponse>()
            assertEquals(2, preview.total)
            assertEquals(2, preview.newCount)
            assertEquals(0, preview.invalidCount)
            // 本地模式的 label 会作为来源回显（前端用它替代地址栏展示）
            assertEquals("我的书源.json", preview.url)

            // 4) **同一票据可直接 commit** —— 证明本地与网络共用同一条落库管线
            val commit = client.post(commitPath) {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(NetworkImportCommitRequest(preview.token, listOf(0), null))
            }
            assertEquals(HttpStatusCode.OK, commit.status)
            assertEquals(1, commit.body<ImportResponse>().imported)

            // 5) 一行一条 JSON（NDJSON）在服务端同样支持（容忍度已从旧前端实现上移）
            val ndjson = client.post(localPath) {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(LocalSourcePreviewRequest("$one\n$two"))
            }
            assertEquals(HttpStatusCode.OK, ndjson.status)
            assertEquals(2, ndjson.body<NetworkImportPreviewResponse>().total)

            // 6) 空内容 / 非书源内容 ⇒ 各自明确的错误码，且不是 5xx
            val empty = client.post(localPath) {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(LocalSourcePreviewRequest("   "))
            }
            assertEquals(HttpStatusCode.BadRequest, empty.status)
            assertEquals("import_content_empty", empty.body<ApiError>().code)

            val garbage = client.post(localPath) {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(LocalSourcePreviewRequest("这不是 JSON\n也不是"))
            }
            assertEquals(HttpStatusCode.BadRequest, garbage.status)
            assertEquals("import_content_invalid", garbage.body<ApiError>().code)
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}
