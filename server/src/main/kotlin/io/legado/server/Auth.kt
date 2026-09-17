package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class AuthService(val database: Database, private val secureCookies: Boolean) {
    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val basicAuthorizations = ConcurrentHashMap<String, Long>()

    suspend fun requireSession(call: ApplicationCall, csrfRequired: Boolean = false): UserSession? {
        val session = call.sessions.get<UserSession>()
        val csrf = session?.let(database::csrfFor)
        if (session == null || csrf == null) {
            call.sessions.clear<UserSession>(); call.respond(HttpStatusCode.Unauthorized, ApiError("unauthenticated", "请先登录")); return null
        }
        if (csrfRequired && call.request.headers[CSRF_HEADER] != csrf) {
            call.respond(HttpStatusCode.Forbidden, ApiError("csrf_invalid", "请求验证失败")); return null
        }
        return session
    }

    fun csrf(session: UserSession): String? = database.csrfFor(session)
    fun hasWebSocketSession(call: ApplicationCall, csrfToken: String?): Boolean {
        if (csrfToken.isNullOrBlank()) return false
        val session = call.sessions.get<UserSession>()
        if (session != null && csrf(session) == csrfToken) return true
        return database.hasValidCsrfToken(csrfToken)
    }

    /**
     * WebDAV 等非浏览器客户端只能走 HTTP Basic：用户名任意（便于各类客户端填写），密码为管理员密码。
     * 校验通过的结果按请求头短时缓存，避免客户端每个文件操作都触发一次 PBKDF2 计算。
     */
    fun verifyBasicAuthorization(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val cachedUntil = basicAuthorizations[header]
        if (cachedUntil != null && cachedUntil > System.currentTimeMillis()) return true
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX_LENGTH).trim()), Charsets.UTF_8) }.getOrNull()
        val password = decoded?.substringAfter(':', "")
        if (password.isNullOrEmpty() || !database.verifyPassword(password)) {
            basicAuthorizations.remove(header)
            return false
        }
        if (basicAuthorizations.size >= MAX_CACHED_AUTHORIZATIONS) basicAuthorizations.clear()
        basicAuthorizations[header] = System.currentTimeMillis() + BASIC_AUTHORIZATION_TTL_MS
        return true
    }

    fun canAttempt(remoteHost: String): Boolean = attempts[remoteHost]?.let { it.until > System.currentTimeMillis() } != true
    fun failure(remoteHost: String) {
        attempts.compute(remoteHost) { _, old ->
            val count = (old?.count ?: 0) + 1
            Attempt(count, if (count >= 5) System.currentTimeMillis() + 60_000 else 0)
        }
    }
    fun success(remoteHost: String) { attempts.remove(remoteHost) }
    private data class Attempt(val count: Int, val until: Long)

    companion object {
        const val COOKIE_NAME = "legado_session"
        const val CSRF_HEADER = "X-CSRF-Token"
        private const val BASIC_PREFIX_LENGTH = 6
        private const val BASIC_AUTHORIZATION_TTL_MS = 5 * 60 * 1000L
        private const val MAX_CACHED_AUTHORIZATIONS = 32
    }
}

fun Route.authRoutes(auth: AuthService) {
    route("/api/auth") {
        get("/session") {
            val session = call.sessions.get<UserSession>()
            val csrf = session?.let(auth::csrf)
            call.respond(SessionResponse(csrf != null, csrf))
        }
        post("/login") {
            val remote = call.request.origin.remoteHost
            if (!auth.canAttempt(remote)) { call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "请稍后再试")); return@post }
            val request = call.receive<LoginRequest>()
            if (!auth.database.verifyPassword(request.password)) {
                auth.failure(remote); call.application.log.warn("authentication failed from {}", remote); call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_credentials", "密码不正确")); return@post
            }
            auth.success(remote)
            val session = auth.database.createSession()
            call.application.log.info("authentication succeeded from {}", remote)
            call.sessions.set(session)
            call.respond(LoginResponse(auth.csrf(session)!!))
        }
        post("/logout") {
            val session = auth.requireSession(call, csrfRequired = true) ?: return@post
            auth.database.deleteSession(session); call.sessions.clear<UserSession>(); call.respond(HttpStatusCode.NoContent)
            call.application.log.info("session logged out")
        }
    }
}
