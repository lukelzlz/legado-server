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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class I18nLocaleAndErrorTest {

    @Test
    fun `test locale matching and message resolution logic`() {
        assertEquals("zh-CN", ServerMessages.matchLocale(null))
        assertEquals("zh-CN", ServerMessages.matchLocale(""))
        assertEquals("zh-CN", ServerMessages.matchLocale("zh-CN,zh;q=0.9"))
        assertEquals("zh-TW", ServerMessages.matchLocale("zh-TW,zh;q=0.9"))
        assertEquals("zh-TW", ServerMessages.matchLocale("zh-HK"))
        assertEquals("zh-TW", ServerMessages.matchLocale("zh-Hant-TW"))
        assertEquals("en-US", ServerMessages.matchLocale("en-US,en;q=0.9"))
        assertEquals("en-US", ServerMessages.matchLocale("en"))
        assertEquals("ja-JP", ServerMessages.matchLocale("ja-JP,ja;q=0.9"))
        assertEquals("ja-JP", ServerMessages.matchLocale("ja"))
        assertEquals("zh-CN", ServerMessages.matchLocale("fr-FR,fr;q=0.8"))

        assertEquals("Authentication required", ServerMessages.resolve("unauthenticated", "请先登录", "en-US"))
        assertEquals("ログインが必要です", ServerMessages.resolve("unauthenticated", "请先登录", "ja-JP"))
        assertEquals("請先登入", ServerMessages.resolve("unauthenticated", "请先登录", "zh-TW"))
        assertEquals("请先登录", ServerMessages.resolve("unauthenticated", "请先登录", "zh-CN"))

        // Fallback for unknown code
        assertEquals("自定义回退", ServerMessages.resolve("custom_unknown_code", "自定义回退", "en-US"))
    }

    @Test
    fun `test locale setting routes and accept-language error localization`() = testApplication {
        val dbPath = Files.createTempFile("legado-i18n-routes", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-i18n-routes")
        val password = "admin-secret-password-123"

        try {
            val config = ServerConfig(
                host = "0.0.0.0",
                port = 8080,
                databasePath = dbPath,
                coverCacheDirectory = tempDir,
                webDavDirectory = tempDir.resolve("webdav"),
                initialAdminPassword = password,
                secureCookies = false,
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            // 1. Unauthenticated Accept-Language error translation
            val respEn = client.get("/api/sources") {
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
            assertEquals(HttpStatusCode.Unauthorized, respEn.status)
            val errEn = respEn.body<ApiError>()
            assertEquals("unauthenticated", errEn.code)
            assertEquals("Authentication required", errEn.message)

            val respJa = client.get("/api/sources") {
                header(HttpHeaders.AcceptLanguage, "ja-JP,ja;q=0.9")
            }
            assertEquals(HttpStatusCode.Unauthorized, respJa.status)
            val errJa = respJa.body<ApiError>()
            assertEquals("unauthenticated", errJa.code)
            assertEquals("ログインが必要です", errJa.message)

            val respTw = client.get("/api/sources") {
                header(HttpHeaders.AcceptLanguage, "zh-TW")
            }
            assertEquals(HttpStatusCode.Unauthorized, respTw.status)
            val errTw = respTw.body<ApiError>()
            assertEquals("unauthenticated", errTw.code)
            assertEquals("請先登入", errTw.message)

            // 2. Initial locale query without session
            val initLocaleResp = client.get("/api/settings/locale")
            assertEquals(HttpStatusCode.OK, initLocaleResp.status)
            val initLocaleBody = initLocaleResp.body<LocaleSettingResponse>()
            assertNull(initLocaleBody.locale)

            // 3. PUT without auth should fail
            val unauthPut = client.put("/api/settings/locale") {
                contentType(ContentType.Application.Json)
                setBody(LocaleSettingRequest("en-US"))
            }
            assertEquals(HttpStatusCode.Unauthorized, unauthPut.status)

            // 4. Login
            val loginResp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(password))
            }
            assertEquals(HttpStatusCode.OK, loginResp.status)
            val csrf = loginResp.body<LoginResponse>().csrfToken

            // 5. Invalid locale PUT
            val invalidPut = client.put("/api/settings/locale") {
                header(AuthService.CSRF_HEADER, csrf)
                header(HttpHeaders.AcceptLanguage, "en-US")
                contentType(ContentType.Application.Json)
                setBody(LocaleSettingRequest("xyz-invalid"))
            }
            assertEquals(HttpStatusCode.BadRequest, invalidPut.status)
            val invalidErr = invalidPut.body<ApiError>()
            assertEquals("invalid_locale", invalidErr.code)
            assertEquals("Unsupported language code", invalidErr.message)

            // 6. Valid locale PUT -> en-US
            val validPut = client.put("/api/settings/locale") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(LocaleSettingRequest("en-US"))
            }
            assertEquals(HttpStatusCode.OK, validPut.status)
            assertEquals("en-US", validPut.body<LocaleSettingResponse>().locale)

            // 7. GET should now return "en-US"
            val updatedGet = client.get("/api/settings/locale")
            assertEquals(HttpStatusCode.OK, updatedGet.status)
            assertEquals("en-US", updatedGet.body<LocaleSettingResponse>().locale)

            // 8. Update to ja-JP
            val jaPut = client.put("/api/settings/locale") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(LocaleSettingRequest("ja"))
            }
            assertEquals(HttpStatusCode.OK, jaPut.status)
            assertEquals("ja-JP", jaPut.body<LocaleSettingResponse>().locale)

            val jaGet = client.get("/api/settings/locale")
            assertEquals("ja-JP", jaGet.body<LocaleSettingResponse>().locale)

        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}
