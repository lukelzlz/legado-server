package io.legado.server

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AuthAndSessionTest {

    @Test
    fun `auth service rate limiting and lockout on repeated failures`() {
        val path = Files.createTempFile("legado-auth-test", ".sqlite").toString()
        try {
            val db = Database(path)
            db.initialize("correct-password-123")
            val auth = AuthService(db, secureCookies = false)

            val ip = "192.168.1.100"
            assertTrue(auth.canAttempt(ip))

            // 4 failures should still allow attempt
            repeat(4) {
                auth.failure(ip)
                assertTrue(auth.canAttempt(ip))
            }

            // 5th failure should trigger lockout
            auth.failure(ip)
            assertFalse(auth.canAttempt(ip))

            // success from that ip should clear lockout
            auth.success(ip)
            assertTrue(auth.canAttempt(ip))
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `auth service web socket csrf validation`() {
        val path = Files.createTempFile("legado-auth-ws-test", ".sqlite").toString()
        try {
            val db = Database(path)
            db.initialize("correct-password-123")
            val auth = AuthService(db, secureCookies = false)

            val session = db.createSession()
            val csrf = auth.csrf(session)
            assertNotNull(csrf)

            // Test csrf validation helpers
            assertEquals(csrf, db.csrfFor(session))
            assertNull(auth.csrf(UserSession("invalid-token")))
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `full http authentication lifecycle - session, login, rate limit, logout`() = testApplication {
        val dbPath = Files.createTempFile("legado-http-auth", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-http-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0",
                port = 8080,
                databasePath = dbPath,
                coverCacheDirectory = tempDir,
                initialAdminPassword = "super-secret-password",
                secureCookies = false,
            )
            application {
                legadoApplication(config)
            }

            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
                install(HttpCookies)
            }

            // 1. Initial session check -> unauthenticated
            val initialSessionResp = client.get("/api/auth/session")
            assertEquals(HttpStatusCode.OK, initialSessionResp.status)
            val initialSession = initialSessionResp.body<SessionResponse>()
            assertFalse(initialSession.authenticated)
            assertNull(initialSession.csrfToken)

            // 2. Failed login with wrong password
            val failedLoginResp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("wrong-password"))
            }
            assertEquals(HttpStatusCode.Unauthorized, failedLoginResp.status)
            val failError = failedLoginResp.body<ApiError>()
            assertEquals("invalid_credentials", failError.code)

            // 3. Successful login
            val loginResp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("super-secret-password"))
            }
            assertEquals(HttpStatusCode.OK, loginResp.status)
            val loginData = loginResp.body<LoginResponse>()
            assertNotNull(loginData.csrfToken)
            assertTrue(loginData.csrfToken.isNotBlank())

            // 4. Session check after login -> authenticated with matching csrf token
            val activeSessionResp = client.get("/api/auth/session")
            assertEquals(HttpStatusCode.OK, activeSessionResp.status)
            val activeSession = activeSessionResp.body<SessionResponse>()
            assertTrue(activeSession.authenticated)
            assertEquals(loginData.csrfToken, activeSession.csrfToken)

            // 5. Logout without CSRF header -> 403 Forbidden
            val logoutNoCsrf = client.post("/api/auth/logout")
            assertEquals(HttpStatusCode.Forbidden, logoutNoCsrf.status)

            // 6. Logout with invalid CSRF header -> 403 Forbidden
            val logoutBadCsrf = client.post("/api/auth/logout") {
                header(AuthService.CSRF_HEADER, "bogus-csrf-token")
            }
            assertEquals(HttpStatusCode.Forbidden, logoutBadCsrf.status)

            // 7. Logout with valid CSRF header -> 204 NoContent
            val logoutSuccess = client.post("/api/auth/logout") {
                header(AuthService.CSRF_HEADER, loginData.csrfToken)
            }
            assertEquals(HttpStatusCode.NoContent, logoutSuccess.status)

            // 8. Session check after logout -> unauthenticated
            val postLogoutSessionResp = client.get("/api/auth/session")
            val postLogoutSession = postLogoutSessionResp.body<SessionResponse>()
            assertFalse(postLogoutSession.authenticated)
            assertNull(postLogoutSession.csrfToken)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }
}
