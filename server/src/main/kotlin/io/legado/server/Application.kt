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

fun Application.legadoApplication(
    config: ServerConfig = ServerConfig.fromEnvironment(),
    /**
     * 测试注入接缝：替换**图片抓取收口**（漫画与小说插图共用同一个实例）。
     *
     * 默认 null ⇒ 真实网络抓取。契约级测试要覆盖「gzip 假图片 / 超大 / 上游 404」
     * 这些只在网络边界上才成立的校验，必须能注入上游字节，否则只能造假绿。
     */
    injectedImageFetcher: MangaImageFetcher? = null,
) {
    val database = Database(config.databasePath)
    database.initialize(config.initialAdminPassword)
    // 缓存目录结构升级到 v3 的唯一入口（同一套 `app_setting.cache_layout` 版本判断）：
    // - v2 → v3：漫画根 `images` 改名成 `comic`，动作是**移动**（布局没变 ⇒ 用户零数据损失），
    //   顺带把封面文件名从 `covers/<key>` 改成 `covers/<书名 (书哈希).扩展名>`；
    // - 更旧（v1 散列布局）→ 沿用既有裁决"清掉重下"（与新语义不兼容）；
    // - **幂等**：第二次启动读到 cache_layout=v3 直接 no-op，不做任何磁盘扫描。
    // 无论哪个版本，都会确保「小说插图」根目录存在（空目录，本次不实现写入）。
    migrateCacheLayout(
        database = database,
        comicRoot = config.comicCacheDirectory,
        illustrationRoot = config.illustrationCacheDirectory,
        audioRoot = config.audioCacheDirectory,
        coverRoot = config.coverCacheDirectory,
        log = { message -> log.info(message) },
    )
    val auth = AuthService(database, config.secureCookies)
    val subscriptions = SubscriptionService(database) { message -> log.info(message) }
    val runner = RuleRunner(database = database)
    // 图片抓取的**唯一收口**（漫画与小说正文插图共用）：书源 header + 章节页 Referer + Cookie、
    // 全局限流、gzip 魔数解压、单图体积上限、SSRF 防护都在这里（见 MangaImageFetcher 的说明）。
    // ⚠️ 必须用具名参数：`log` 不是最后一个参数（后面还有测试注入接缝），
    // 尾随 lambda 会绑到 `injectedFetcher` 上，导致类型不匹配。
    val imageFetcher = injectedImageFetcher ?: MangaImageFetcher(config, log = { message -> log.info(message) })
    // 小说正文插图的落盘缓存（`<data>/images/`）。离线缓存章节时顺手把插图抓下来。
    val illustrationCache = IllustrationCache(config.illustrationCacheDirectory, database) { message -> log.info(message) }
    val bookCache = BookCacheService(
        database,
        runner,
        illustrationCache = illustrationCache,
        illustrationFetcher = imageFetcher,
    ) { message -> log.info(message) }
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
        // 封面缓存带库：文件名是 `书名 (书哈希).扩展名`，`file_path` 就是索引里那一列
        // （拿不到库时只能按 key 命名/定位，那是上传接口等"还不知道属于哪本书"的场景）。
        val coverCache = CoverCache(config.coverCacheDirectory, database = database)
        // 漫画 P1：L1 字节缓存 + 抓取收口（配额默认 10 GiB，见 ADR-028 的滑窗 GC 裁决）
        val imageCache = ImageCache(config.comicCacheDirectory, database, config.imageCacheQuotaBytes) { message -> log.info(message) }
        val mangaImageFetcher = imageFetcher
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
        // 有声书 P1/P2：L1 字节缓存 + 抓取收口 + 滑窗 GC + 后台批量缓存（方案 G2）
        val audioCache = AudioCache(config.audioCacheDirectory, database, config.audioCacheQuotaBytes) { message -> log.info(message) }
        val audioFetcher = AudioFetcher(config, log = { message -> log.info(message) })
        val audioGc = AudioCacheGc(database, audioCache, config.audioCacheQuotaBytes, config.audioGcEnabled) { message -> log.info(message) }
        val audioPrefetch = AudioPrefetchService(
            database = database,
            runner = runner,
            audioCache = audioCache,
            fetcher = audioFetcher,
            scope = this@legadoApplication,
            gc = audioGc,
            enabled = config.audioPrefetchEnabled,
        ) { message -> log.info(message) }
        if (config.audioGcEnabled) {
            // 定时巡检：不抓音频的时候也要能收敛（例如用户手动改小了 LEGADO_AUDIO_CACHE_MAX_GB）
            val audioGcJob = launch {
                while (isActive) {
                    delay(ServerConfig.AUDIO_GC_INTERVAL_MS)
                    audioGc.maybeRun()
                }
            }
            environment.monitor.subscribe(ApplicationStopping) { audioGcJob.cancel() }
            environment.monitor.subscribe(ApplicationStopped) { audioGcJob.cancel() }
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
            audioCache,
            audioFetcher,
            audioPrefetch,
            illustrationCache,
        )
        webDavRoutes(auth, webDavStorage, database, coverCache)
        staticWeb()
    }
}
