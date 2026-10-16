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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        // 漫画 P1：L1 字节缓存 + 抓取收口（配额默认 10 GiB，见 ADR-028 的滑窗 GC 裁决）
        val imageCache = ImageCache(config.imageCacheDirectory, database, config.imageCacheQuotaBytes) { message -> log.info(message) }
        // ⚠️ 必须用具名参数：`log` 不是最后一个参数（后面还有测试注入接缝），
        // 尾随 lambda 会绑到 `injectedFetcher` 上，导致类型不匹配。
        val mangaImageFetcher = MangaImageFetcher(config, log = { message -> log.info(message) })
        // 漫画 P2：滑窗 GC + 后台预取。
        // GC 的"每次落盘后检查"落在预取循环与章节收口处（每张图都查一次是没必要的写放大）。
        val imageGc = ImageCacheGc(database, imageCache, config.imageCacheQuotaBytes, config.mangaGcEnabled) { message -> log.info(message) }
        val mangaPrefetch = MangaPrefetchService(
            database = database,
            runner = runner,
            imageCache = imageCache,
            fetcher = mangaImageFetcher,
            scope = this@legadoApplication,
            gc = imageGc,
            enabled = config.mangaPrefetchEnabled,
        ) { message -> log.info(message) }
        if (config.mangaGcEnabled) {
            // 定时巡检：不抓图的时候也要能收敛（例如用户手动改小了 LEGADO_MANGA_CACHE_MAX_GB）
            val gcJob = launch {
                while (isActive) {
                    delay(ServerConfig.IMAGE_GC_INTERVAL_MS)
                    imageGc.maybeRun()
                }
            }
            environment.monitor.subscribe(ApplicationStopping) { gcJob.cancel() }
            environment.monitor.subscribe(ApplicationStopped) { gcJob.cancel() }
        }
        authRoutes(auth)
        apiRoutes(
            database, auth, runner, coverCache, subscriptions, bookCache, edgeTts, ttsSessions,
            config.localBooksDirectory,
            // 进度文件落在 WebDAV 根目录下的 bookProgress（与手机端备份结构一致）
            BookProgressSync(config.webDavDirectory, database),
            webDavStorage,
            httpTtsService,
            imageCache,
            mangaImageFetcher,
            mangaPrefetch,
        )
        webDavRoutes(auth, webDavStorage, database, coverCache)
        staticWeb()
    }
}
