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

/**
 * 组装整个应用。
 *
 * @param responseFetcher 仅用于**测试**：非空时 [RuleRunner] 完全绕过网络，改用它取数
 *   （与书源侧既有的测试接缝同源）。生产环境传 null，走真实 HTTP。
 */
fun Application.legadoApplication(
    config: ServerConfig = ServerConfig.fromEnvironment(),
    responseFetcher: ((String) -> String)? = null,
) {
    val database = Database(config.databasePath)
    database.initialize(config.initialAdminPassword)
    val auth = AuthService(database, config.secureCookies)
    val subscriptions = SubscriptionService(database) { message -> log.info(message) }
    val runner = if (responseFetcher != null) RuleRunner(responseFetcher, database) else RuleRunner(database = database)
    val bookCache = BookCacheService(database, runner) { message -> log.info(message) }
    val edgeTts = EdgeTtsService()
    val httpTtsService = HttpTtsService(database)
    val ttsSessions = TtsSessionService(edgeTts, httpTtsService)
    // WebDAV 存储区在多个地方用到（WebDAV 服务端 + 「从 WebDAV 导入书籍」+ 定时备份导出），
    // 因此在这里先建好，保证几边看到的是同一个根目录。
    val webDavStorage = WebDavStorage(config.webDavDirectory)
    val backupExportScheduler = BackupExportScheduler(
        database,
        webDavStorage,
        BackupExporter(database),
    ) { message -> log.info(message) }
    subscriptions.start()
    bookCache.start()
    backupExportScheduler.start()
    val stopServices = {
        subscriptions.stop(); bookCache.stop(); backupExportScheduler.stop()
        ttsSessions.close(); database.close()
    }
    environment.monitor.subscribe(ApplicationStopping) { stopServices() }
    environment.monitor.subscribe(ApplicationStopped) { stopServices() }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
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
        val coverCache = CoverCache(config.coverCacheDirectory)
        authRoutes(auth)
        apiRoutes(
            database, auth, runner, coverCache, subscriptions, bookCache, edgeTts, ttsSessions,
            config.localBooksDirectory,
            // 进度文件落在 WebDAV 根目录下的 bookProgress（与手机端备份结构一致）
            BookProgressSync(config.webDavDirectory, database),
            webDavStorage,
            httpTtsService,
        )
        webDavRoutes(auth, webDavStorage, database, coverCache)
        staticWeb()
    }
}
