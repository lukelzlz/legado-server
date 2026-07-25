package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.util.concurrent.ConcurrentHashMap

class AuthService(val database: Database, private val secureCookies: Boolean) {
    private val attempts = ConcurrentHashMap<String, Attempt>()

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

    fun canAttempt(remoteHost: String): Boolean = attempts[remoteHost]?.let { it.until > System.currentTimeMillis() } != true
    fun failure(remoteHost: String) {
        attempts.compute(remoteHost) { _, old ->
            val count = (old?.count ?: 0) + 1
            Attempt(count, if (count >= 5) System.currentTimeMillis() + 60_000 else 0)
        }
    }
    fun success(remoteHost: String) { attempts.remove(remoteHost) }
    private data class Attempt(val count: Int, val until: Long)

    companion object { const val COOKIE_NAME = "legado_session"; const val CSRF_HEADER = "X-CSRF-Token" }
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
