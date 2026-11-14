package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalDateTime

/**
 * 小说 / 漫画 / 有声的**自动缓存**：设置 + 定时调度。
 *
 * 设置存在 `app_setting` 表（与 `backup_export_*` 同一机制、同一读写姿势）。
 * 三组**完全独立**（用户要求「三个分开，每个下面跟着隔多长时间下载几本书」）：
 *
 * | 组 | 键 | 额外项 |
 * | :--- | :--- | :--- |
 * | 小说 | [AUTO_CACHE_NOVEL_*] | —— |
 * | 漫画 | [AUTO_CACHE_COMIC_*] | `ignore_quota`（忽略内存大小限制） |
 * | 有声 | [AUTO_CACHE_AUDIO_*] | `ignore_quota`（忽略内存大小限制） |
 *
 * ## 入队一律走**既有服务**，不新造抓取实现
 *
 * - 小说：[BookCacheService.enqueue]（候选口径完全复用 `Database.cacheRequests()`：
 *   `book_cache_status.state in ('pending','caching')` + 排除本地/漫画/有声）；
 * - 漫画：[MangaPrefetchService.enqueue]；有声：[AudioPrefetchService.enqueue]。
 *
 * 三者都通过构造参数注入（[enqueueNovel] / [enqueueComic] / [enqueueAudio]），
 * 生产在 `Application.legadoApplication` 里接到真实服务，测试注入假服务断言 `pin` 透传。
 *
 * ## `ignore_quota` 的落地语义 = `pin = true`
 *
 * 既有的「pinned 免 GC」机制（`setMangaBookPinned` / `setAudioBookPinned`，per AGENTS.md
 * 「整本缓存要 pinned 免死金牌」）。**绝不另造配额旁路**：GC 只认 pinned 标记，
 * 多一条旁路就意味着「有的地方查配额、有的地方查旁路」，迟早出现算不清的账。
 */
internal const val AUTO_CACHE_NOVEL_ENABLED_KEY = "auto_cache_novel_enabled"
internal const val AUTO_CACHE_NOVEL_INTERVAL_KEY = "auto_cache_novel_interval_minutes"
internal const val AUTO_CACHE_NOVEL_BOOKS_KEY = "auto_cache_novel_books_per_round"
internal const val AUTO_CACHE_COMIC_ENABLED_KEY = "auto_cache_comic_enabled"
internal const val AUTO_CACHE_COMIC_INTERVAL_KEY = "auto_cache_comic_interval_minutes"
internal const val AUTO_CACHE_COMIC_BOOKS_KEY = "auto_cache_comic_books_per_round"
internal const val AUTO_CACHE_COMIC_IGNORE_QUOTA_KEY = "auto_cache_comic_ignore_quota"
internal const val AUTO_CACHE_AUDIO_ENABLED_KEY = "auto_cache_audio_enabled"
internal const val AUTO_CACHE_AUDIO_INTERVAL_KEY = "auto_cache_audio_interval_minutes"
internal const val AUTO_CACHE_AUDIO_BOOKS_KEY = "auto_cache_audio_books_per_round"
internal const val AUTO_CACHE_AUDIO_IGNORE_QUOTA_KEY = "auto_cache_audio_ignore_quota"

/** 默认间隔 60 分钟、每轮 1 本 —— 与用户描述「隔多长时间下载几本书」的最小可用值。 */
internal const val AUTO_CACHE_DEFAULT_INTERVAL_MINUTES = 60
internal const val AUTO_CACHE_DEFAULT_BOOKS_PER_ROUND = 1

internal const val AUTO_CACHE_MIN_INTERVAL_MINUTES = 1
internal const val AUTO_CACHE_MAX_INTERVAL_MINUTES = 1440
internal const val AUTO_CACHE_MIN_BOOKS_PER_ROUND = 1
internal const val AUTO_CACHE_MAX_BOOKS_PER_ROUND = 20

/**
 * 读设置：**全部默认关、间隔 60 分钟、每轮 1 本、ignore_quota 关**。
 *
 * 库里存了坏值（手工改库、旧版本残留）一律**回退到默认值**，绝不抛错 ——
 * 读接口因为一个脏字段 500，用户连页面都打不开，反而更容易把库改坏。
 */
internal fun readAutoCacheSettings(database: Database): AutoCacheSettings = AutoCacheSettings(
    novelEnabled = database.getSetting(AUTO_CACHE_NOVEL_ENABLED_KEY) == "true",
    novelIntervalMinutes = readBoundedInt(
        database, AUTO_CACHE_NOVEL_INTERVAL_KEY,
        AUTO_CACHE_DEFAULT_INTERVAL_MINUTES, AUTO_CACHE_MIN_INTERVAL_MINUTES, AUTO_CACHE_MAX_INTERVAL_MINUTES,
    ),
    novelBooksPerRound = readBoundedInt(
        database, AUTO_CACHE_NOVEL_BOOKS_KEY,
        AUTO_CACHE_DEFAULT_BOOKS_PER_ROUND, AUTO_CACHE_MIN_BOOKS_PER_ROUND, AUTO_CACHE_MAX_BOOKS_PER_ROUND,
    ),
    comicEnabled = database.getSetting(AUTO_CACHE_COMIC_ENABLED_KEY) == "true",
    comicIntervalMinutes = readBoundedInt(
        database, AUTO_CACHE_COMIC_INTERVAL_KEY,
        AUTO_CACHE_DEFAULT_INTERVAL_MINUTES, AUTO_CACHE_MIN_INTERVAL_MINUTES, AUTO_CACHE_MAX_INTERVAL_MINUTES,
    ),
    comicBooksPerRound = readBoundedInt(
        database, AUTO_CACHE_COMIC_BOOKS_KEY,
        AUTO_CACHE_DEFAULT_BOOKS_PER_ROUND, AUTO_CACHE_MIN_BOOKS_PER_ROUND, AUTO_CACHE_MAX_BOOKS_PER_ROUND,
    ),
    comicIgnoreQuota = database.getSetting(AUTO_CACHE_COMIC_IGNORE_QUOTA_KEY) == "true",
    audioEnabled = database.getSetting(AUTO_CACHE_AUDIO_ENABLED_KEY) == "true",
    audioIntervalMinutes = readBoundedInt(
        database, AUTO_CACHE_AUDIO_INTERVAL_KEY,
        AUTO_CACHE_DEFAULT_INTERVAL_MINUTES, AUTO_CACHE_MIN_INTERVAL_MINUTES, AUTO_CACHE_MAX_INTERVAL_MINUTES,
    ),
    audioBooksPerRound = readBoundedInt(
        database, AUTO_CACHE_AUDIO_BOOKS_KEY,
        AUTO_CACHE_DEFAULT_BOOKS_PER_ROUND, AUTO_CACHE_MIN_BOOKS_PER_ROUND, AUTO_CACHE_MAX_BOOKS_PER_ROUND,
    ),
    audioIgnoreQuota = database.getSetting(AUTO_CACHE_AUDIO_IGNORE_QUOTA_KEY) == "true",
)

private fun readBoundedInt(database: Database, key: String, default: Int, min: Int, max: Int): Int =
    database.getSetting(key)?.trim()?.toIntOrNull()?.takeIf { it in min..max } ?: default

/**
 * 局部更新：**只写请求里出现过的字段**（与备份导出的 update 语义逐字一致）。
 *
 * ⚠️ 校验必须在**任何写库之前**跑完：否则 `enabled=true` 已落库、`interval=0` 才被拒，
 * 用户会得到一个「半保存」的状态 —— 页面上看是保存失败，实际开关已经打开了。
 *
 * @throws IllegalArgumentException 间隔不在 1..1440，或每轮本数不在 1..20
 */
internal fun applyAutoCacheSettingsUpdate(
    database: Database,
    update: AutoCacheSettingsUpdate,
): AutoCacheSettings {
    update.novelIntervalMinutes?.let { requireInterval(it, "小说") }
    update.comicIntervalMinutes?.let { requireInterval(it, "漫画") }
    update.audioIntervalMinutes?.let { requireInterval(it, "有声") }
    update.novelBooksPerRound?.let { requireBooksPerRound(it, "小说") }
    update.comicBooksPerRound?.let { requireBooksPerRound(it, "漫画") }
    update.audioBooksPerRound?.let { requireBooksPerRound(it, "有声") }

    update.novelEnabled?.let { database.setSetting(AUTO_CACHE_NOVEL_ENABLED_KEY, it.toString()) }
    update.novelIntervalMinutes?.let { database.setSetting(AUTO_CACHE_NOVEL_INTERVAL_KEY, it.toString()) }
    update.novelBooksPerRound?.let { database.setSetting(AUTO_CACHE_NOVEL_BOOKS_KEY, it.toString()) }
    update.comicEnabled?.let { database.setSetting(AUTO_CACHE_COMIC_ENABLED_KEY, it.toString()) }
    update.comicIntervalMinutes?.let { database.setSetting(AUTO_CACHE_COMIC_INTERVAL_KEY, it.toString()) }
    update.comicBooksPerRound?.let { database.setSetting(AUTO_CACHE_COMIC_BOOKS_KEY, it.toString()) }
    update.comicIgnoreQuota?.let { database.setSetting(AUTO_CACHE_COMIC_IGNORE_QUOTA_KEY, it.toString()) }
    update.audioEnabled?.let { database.setSetting(AUTO_CACHE_AUDIO_ENABLED_KEY, it.toString()) }
    update.audioIntervalMinutes?.let { database.setSetting(AUTO_CACHE_AUDIO_INTERVAL_KEY, it.toString()) }
    update.audioBooksPerRound?.let { database.setSetting(AUTO_CACHE_AUDIO_BOOKS_KEY, it.toString()) }
    update.audioIgnoreQuota?.let { database.setSetting(AUTO_CACHE_AUDIO_IGNORE_QUOTA_KEY, it.toString()) }
    return readAutoCacheSettings(database)
}

private fun requireInterval(value: Int, label: String): Int {
    require(value in AUTO_CACHE_MIN_INTERVAL_MINUTES..AUTO_CACHE_MAX_INTERVAL_MINUTES) {
        "${label}自动缓存的间隔需在 ${AUTO_CACHE_MIN_INTERVAL_MINUTES}~${AUTO_CACHE_MAX_INTERVAL_MINUTES} 分钟之间（当前 $value）"
    }
    return value
}

private fun requireBooksPerRound(value: Int, label: String): Int {
    require(value in AUTO_CACHE_MIN_BOOKS_PER_ROUND..AUTO_CACHE_MAX_BOOKS_PER_ROUND) {
        "${label}自动缓存每轮本数需在 ${AUTO_CACHE_MIN_BOOKS_PER_ROUND}~${AUTO_CACHE_MAX_BOOKS_PER_ROUND} 之间（当前 $value）"
    }
    return value
}

/** 一轮媒体书（漫画 / 有声）需要的全部参数，避免 `runMedia` 拖八个位置参数。 */
private class MediaRound(
    /** 日志里的名字（`[auto-cache] comic: ...`）。 */
    val label: String,
    val kind: BookKind,
    val enabled: Boolean,
    val intervalMinutes: Int,
    val booksPerRound: Int,
    val ignoreQuota: Boolean,
    val runningKeys: Set<String>,
    val enqueue: (AutoCacheMediaRequest) -> Int,
)

/**
 * 自动缓存调度器（仿 [BackupExportScheduler] 的协程结构：`start()` / `stop()` + `Mutex` + `SupervisorJob`）。
 *
 * 每 [TICK_MS] 醒一次；对每种书型：`enabled && (now - lastRun >= interval)` ⇒
 * 挑最多 `booksPerRound` 本「缓存未完成」的书，走**既有服务**入队。
 *
 * 取舍（如实说明）：
 * - 「缓存未完成」的**小说**口径直接复用 `Database.cacheRequests()`（`state in ('pending','caching')`），
 *   不另造一套判断 —— 那套口径已经踩过「无过滤=整架重下」的坑（SESSION-025）。
 * - 「缓存未完成」的**漫画/有声**口径：整章就绪数（`readyManga/readyAudioChapterCountsByBook`，
 *   与书架进度、目录绿点同一口径）< 目录章数即未完成；目录还没缓存时按**未完成**处理
 *   （首次自动化本来就该顺带把目录抓下来）。
 * - 正在跑的书**跳过**：否则 `booksPerRound=1` 时每轮都挑中同一本，其余书永远轮不到。
 * - 每轮每种书型都留一条 `[auto-cache] <kind>: queued=N candidates=M skipped=K` 日志；
 *   任何一层失败只记日志，**绝不影响主流程**（与备份导出同一条纪律）。
 */
class AutoCacheScheduler(
    private val database: Database,
    /** 小说入队（生产：`BookCacheService::enqueue`）。 */
    private val enqueueNovel: (CachedBookRequest) -> Unit,
    /** 漫画入队（生产：`MangaPrefetchService::enqueue` 的适配）。 */
    private val enqueueComic: (AutoCacheMediaRequest) -> Int,
    /** 有声入队（生产：`AudioPrefetchService::enqueue` 的适配）。 */
    private val enqueueAudio: (AutoCacheMediaRequest) -> Int,
    /** 媒体书「正在跑」的 key 集合（`sourceId\u0000bookUrl`）。 */
    private val comicRunning: () -> Set<String> = { emptySet() },
    private val audioRunning: () -> Set<String> = { emptySet() },
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null

    /** 每种书型上一次真正跑过的时刻；null = 从未跑过（刚打开开关就立刻跑一轮）。 */
    private val lastRun = mutableMapOf<String, LocalDateTime>()

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { log("[auto-cache] tick failed: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    fun stop() { scope.cancel() }

    /**
     * 手动触发一轮（`POST /api/auto-cache/run-now`，便于验收）：忽略间隔，但**仍尊重启用开关**
     * —— 关掉的功能不该因为点了个按钮就偷偷跑起来。
     */
    suspend fun runNow(now: LocalDateTime = LocalDateTime.now()): AutoCacheRunResult = tick(now, force = true)

    /**
     * 判断「现在是否该跑」，该跑就跑。
     *
     * @param force 忽略间隔（`run-now` 用）；启用开关照样生效
     */
    internal suspend fun tick(
        now: LocalDateTime = LocalDateTime.now(),
        force: Boolean = false,
    ): AutoCacheRunResult = mutex.withLock {
        val settings = readAutoCacheSettings(database)
        val novel = runNovel(settings, now, force)
        val comic = runMedia(
            MediaRound(
                label = "comic",
                kind = BookKind.COMIC,
                enabled = settings.comicEnabled,
                intervalMinutes = settings.comicIntervalMinutes,
                booksPerRound = settings.comicBooksPerRound,
                ignoreQuota = settings.comicIgnoreQuota,
                runningKeys = safeSet(comicRunning),
                enqueue = enqueueComic,
            ),
            now,
            force,
        )
        val audio = runMedia(
            MediaRound(
                label = "audio",
                kind = BookKind.AUDIO,
                enabled = settings.audioEnabled,
                intervalMinutes = settings.audioIntervalMinutes,
                booksPerRound = settings.audioBooksPerRound,
                ignoreQuota = settings.audioIgnoreQuota,
                runningKeys = safeSet(audioRunning),
                enqueue = enqueueAudio,
            ),
            now,
            force,
        )
        AutoCacheRunResult(novelQueued = novel, comicQueued = comic, audioQueued = audio)
    }

    private fun runNovel(settings: AutoCacheSettings, now: LocalDateTime, force: Boolean): Int {
        val label = "novel"
        if (!settings.novelEnabled) return 0
        if (!isDue(now, lastRun[label], settings.novelIntervalMinutes, force)) return 0

        // 候选口径 = 既有 `cacheRequests()`（pending/caching、排除本地与漫画/有声），一行业务判断都不新写
        val candidates = runCatching { database.cacheRequests() }.getOrElse { error ->
            log("[auto-cache] novel: candidate query failed (${error.message})")
            emptyList()
        }
        val batch = candidates.take(settings.novelBooksPerRound)
        var queued = 0
        for (request in batch) {
            runCatching { enqueueNovel(request) }
                .onSuccess { queued++ }
                .onFailure { log("[auto-cache] novel: enqueue failed ${request.bookUrl} (${it.message})") }
        }
        lastRun[label] = now
        logRound(label, queued, candidates.size)
        return queued
    }

    private fun runMedia(plan: MediaRound, now: LocalDateTime, force: Boolean): Int {
        if (!plan.enabled) return 0
        if (!isDue(now, lastRun[plan.label], plan.intervalMinutes, force)) return 0

        val (candidates, picked) = pickMedia(plan)
        var queued = 0
        for (request in picked) {
            runCatching { plan.enqueue(request) }
                .onSuccess { queued++ }
                .onFailure { log("[auto-cache] ${plan.label}: enqueue failed ${request.bookUrl} (${it.message})") }
        }
        lastRun[plan.label] = now
        logRound(plan.label, queued, candidates)
        return queued
    }

    /**
     * 挑出这一轮要入队的媒体书。
     *
     * @return 「缓存未完成」的书总数（含正在跑与被本数上限挡住的）与该轮实际挑中的任务
     */
    private fun pickMedia(plan: MediaRound): Pair<Int, List<AutoCacheMediaRequest>> {
        val readyCounts = runCatching {
            when (plan.kind) {
                BookKind.COMIC -> database.readyMangaChapterCountsByBook()
                else -> database.readyAudioChapterCountsByBook()
            }
        }.getOrElse { error ->
            log("[auto-cache] ${plan.label}: ready-count query failed (${error.message})")
            return 0 to emptyList()
        }
        val shelf = runCatching { database.listBookshelf() }.getOrElse { error ->
            log("[auto-cache] ${plan.label}: shelf query failed (${error.message})")
            return 0 to emptyList()
        }

        var candidates = 0
        val picked = mutableListOf<AutoCacheMediaRequest>()
        for (book in shelf) {
            if (BookKind.from(book.bookKind) != plan.kind) continue
            // 本地书没有可抓的网络地址（漫画/有声的来源必然是书源），统一走既有身份判据
            if (BookKind.isLocalRef(book.sourceId, book.bookUrl)) continue
            val key = "${book.sourceId}\u0000${book.bookUrl}"
            val done = readyCounts[key] ?: 0
            // 目录章数：只读库、不触网（`readCachedToc` 是纯读）；没缓存过就是 0
            val total = runCatching { readCachedToc(database, book.sourceId, book.bookUrl)?.size ?: 0 }
                .getOrDefault(0)
            // dt>0 且整章就绪数已达标 ⇒ 这本已经缓存完了，不再占额度
            if (total > 0 && done >= total) continue
            candidates++
            if (key in plan.runningKeys) continue
            if (picked.size >= plan.booksPerRound) continue
            // ahead = 「章节数 - 1」（含起始章共 ahead+1 章）⇒ 整本；
            // 目录还没缓存时不知道章节数，交给服务侧的 MAX_AHEAD 截断，语义同样是「整本」
            picked += AutoCacheMediaRequest(
                sourceId = book.sourceId,
                bookUrl = book.bookUrl,
                fromChapterIndex = 0,
                ahead = if (total > 0) total - 1 else Int.MAX_VALUE,
                // 忽略内存大小限制 ⇒ pinned 免 GC（既有机制，不新造配额旁路）
                pin = plan.ignoreQuota,
            )
        }
        return candidates to picked
    }

    private fun isDue(now: LocalDateTime, last: LocalDateTime?, intervalMinutes: Int, force: Boolean): Boolean {
        if (force) return true
        if (last == null) return true
        return Duration.between(last, now).toMinutes() >= intervalMinutes
    }

    private fun logRound(label: String, queued: Int, candidates: Int) {
        val skipped = (candidates - queued).coerceAtLeast(0)
        log("[auto-cache] $label: queued=$queued candidates=$candidates skipped=$skipped")
    }

    private fun safeSet(provider: () -> Set<String>): Set<String> =
        runCatching(provider).getOrDefault(emptySet())

    private companion object {
        /** 30 秒一跳：远小于最小间隔 1 分钟，因此不会漏掉任何一轮。 */
        const val TICK_MS = 30_000L
    }
}
