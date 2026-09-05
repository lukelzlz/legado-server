package io.legado.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    if (args.firstOrNull() == "reset-password") {
        resetPasswordMain(args.drop(1).toTypedArray())
        return
    }
    val config = ServerConfig.fromEnvironment()
    embeddedServer(CIO, host = config.host, port = config.port) {
        legadoApplication(config)
    }.start(wait = true)
}

fun Application.legadoApplication(config: ServerConfig = ServerConfig.fromEnvironment()) {
    val database = Database(config.databasePath)
    database.initialize(config.initialAdminPassword)
    val auth = AuthService(database, config.secureCookies)
    val subscriptions = SubscriptionService(database) { message -> log.info(message) }
    val runner = RuleRunner(database = database)
    val bookCache = BookCacheService(database, runner) { message -> log.info(message) }
    val edgeTts = EdgeTtsService()
    val ttsSessions = TtsSessionService(edgeTts)
    subscriptions.start()
    bookCache.start()
    environment.monitor.subscribe(ApplicationStopped) { subscriptions.stop(); bookCache.stop(); ttsSessions.close(); database.close() }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    install(Sessions) {
        cookie<UserSession>(AuthService.COOKIE_NAME) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = config.secureCookies
            cookie.extensions["SameSite"] = "Strict"
        }
    }
    install(WebSockets)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            this@legadoApplication.log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "服务器内部错误"))
        }
    }
    routing {
        get("/healthz") { call.respond(mapOf("status" to "ok")) }
        authRoutes(auth)
        apiRoutes(database, auth, runner, CoverCache(config.coverCacheDirectory), subscriptions, bookCache, edgeTts, ttsSessions)
        staticWeb()
    }
}
