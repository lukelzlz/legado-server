package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 服务内的预取任务（同一本书后来的请求会**顶掉**前一个的待办区间，而不是新起一条并行任务）。 */
data class MangaPrefetchTask(
    val sourceId: String,
    val bookUrl: String,
    val fromChapterIndex: Int,
    /** 从起始章起**再往后**多少章（含起始章共 ahead+1 章）。 */
    val ahead: Int,
) {
    /** 这个任务的区间是否覆盖第 [index] 章（用于判断"更窄的自动预取"要不要顶掉它）。 */
    fun covers(index: Int): Boolean = index >= fromChapterIndex && index <= fromChapterIndex + ahead
}

/**
 * 一本书的预取进度（`GET /api/books/images/prefetch` 的响应元素）。
 *
 * ## 单位契约（**别再把图片张数当"章"显示**）
 * 用户反馈「进度明明写的是章节，实际上是每张图片的进度」：一本书 51 章显示成 `83/136`。
 * 根因就是这里的 `done/planned` 曾经是**图片**口径，而前端文案写的是"章"。
 * 现在：
 * - [done] / [planned] = **章**（`done` 只统计"整章图片全部落盘"的章节）；
 * - [doneImages] / [plannedImages] = 图片张数（细节口径，要展示就单独说明单位）。
 *
 * 只**新增**字段、不复用旧字段表达新含义：字段名不变、语义变成"章"，
 * 因为 `done/planned` 的消费端（目录进度、书架列表）本来就要的是章数。
 */
@kotlinx.serialization.Serializable
data class MangaPrefetchStatus(
    val sourceId: String,
    val bookUrl: String,
    val running: Boolean = true,
    /** **已完成章节数**（整章图片全部落盘才算完成；本次任务开始前就已就绪的章也计入）。 */
    val done: Int = 0,
    /** 本次任务**规划**的章节数（目录还没抓到时为 0，抓完立刻回填）。 */
    val planned: Int = 0,
    val currentChapterIndex: Int = 0,
    /** 已落盘**图片**张数（细节口径，不是"章"）。 */
    val doneImages: Int = 0,
    /** 本次任务已解析出的**图片**张数（逐章增长，细节口径，不是"章"）。 */
    val plannedImages: Int = 0,
    /** 本次任务内失败的图片张数（>0 时用 [lastError] 说明首条原因，绝不静默）。 */
    val failed: Int = 0,
    /** 首条失败原因（供 UI 如实报出）。 */
    val lastError: String? = null,
)

/**
 * 方案 E 环节 E8：漫画图片的后台预取。
 *
 * ## 为什么要有它
 * 阅读器里的 `<img>` 只按需抓**可视区**那几张（前台快），但用户往下滚时仍会顿一下。
 * 预取把「整章」和「下一章」提前抓满，做到**连点下一章无等待**。
 *
 * ## 结构上与既有 `BookCacheService` 同构（不新造一套并发模型）
 * - 全局限流**不在这里**，在 `MangaImageFetcher` 的 `Semaphore`（抓取收口）——
 *   这里只负责"错峰投放"，绝不再加第二道闸门（两道闸门会互相等待）。
 * - 批量错峰：每 [BATCH_SIZE] 张让出 [BATCH_DELAY_MS]，避免预取把 IO/网络吃满
 *   （历史事故 SESSION-025：后台任务把 `/healthz` 拖到 4 分钟超时）。
 * - **断点续做**：[requests] 里始终保留"最新意图"，正在跑的循环每章结束后重新读取，
 *   于是用户连点三章不会产生三条并行任务，而是同一条循环改目标。
 * - **逐章解析**：规划章节的清单**用一章解析一章**，绝不先把 50 章的目录页都解析完
 *   （那会在用户点"后 50 章"的瞬间对上游发起 50 个请求，属于自伤式突发）。
 * - **失败静默**：任何一层失败只记日志。`LEGADO_MANGA_PREFETCH=false` 是"整块关掉"的回滚开关。
 */
class MangaPrefetchService(
    private val database: Database,
    private val runner: RuleRunner,
    private val imageCache: ImageCache,
    private val fetcher: MangaImageFetcher,
    private val scope: CoroutineScope,
    private val gc: ImageCacheGc? = null,
    /** `LEGADO_MANGA_PREFETCH=false` ⇒ 硬关闭。路由层也读它，好把 `prefetch_disabled` 如实报出来。 */
    val enabled: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    /** "最新意图"：`bookKey -> task`。跑批的循环每次取走它。 */
    private val requests = ConcurrentHashMap<String, MangaPrefetchTask>()

    /**
     * 正在跑的意图：`bookKey -> task`。
     *
     * 为什么需要：`requests` 被 `drain` 取走之后，"当前任务覆盖哪些章"就没人记得了，
     * 于是**阅读器每翻一章投递的"下一章"（ahead=0）会把"全本 / 后 100 章"顶成 1 章**，
     * 批量任务被切成一条条单章、表现为"点了缓存全本但进度不动"。
     * 见 [enqueue] 里的"窄意图不得顶掉宽意图"守卫。
     */
    private val active = ConcurrentHashMap<String, MangaPrefetchTask>()

    /** 正在跑的任务：`bookKey -> Job`（用于 `cancel` 与"是不是已经在跑"）。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    private val progress = ConcurrentHashMap<String, MangaPrefetchStatus>()

    /**
     * 投递一次预取。
     *
     * @return 本次**已受理**的章节数（≥1 表示已受理；`0` 只表示"没做"：开关关闭 / 目录与意图都空）。
     *         目录还没缓存时**也受理**：真实章数要抓完目录才知道（详见 [drain]），
     *         这里先按"1 章"如实回报"已受理"——**绝不能返回 0**，否则调用方会把
     *         "目录还没缓存"误判成"没事可做"，预取就永远不会启动（用户反馈的
     *         "只有打开书了进度才动"正是这条旧行为）。
     */
    fun enqueue(sourceId: String, bookUrl: String, fromChapterIndex: Int, ahead: Int = 1, pin: Boolean = false): Int {
        if (!enabled) return 0
        if (pin) {
            // 「整本离线」的免死金牌越早打越好：中途重启也不至于被 GC 从半本开始删。
            // （路由层另外也会打一次，两处都是幂等的 upsert。）
            // **放在目录解析之前**：目录抓不到时，用户"整本离线"的意图也必须算数。
            runCatching { database.setMangaBookPinned(sourceId, bookUrl, true) }
                .onFailure { log("manga prefetch pin failed: ${it.message}") }
        }

        // 只读库、不触网：抓目录是 suspend 的事，必须放到 drain 的协程里。
        val chapters = readCachedToc(database, sourceId, bookUrl)
        val key = bookKey(sourceId, bookUrl)

        // 「窄意图不得顶掉宽意图」：自动单章预取（ahead=0 且没打 pin）若落在正在跑的区间里，
        // 直接忽略——那条循环本来就会顺序走到它，顶窄了只会让批量任务变成一条条单章。
        val running = active[key]
        if (running != null && !pin && ahead <= 0 && running.covers(fromChapterIndex)) return 1

        val start = if (chapters == null) fromChapterIndex.coerceAtLeast(0)
        else fromChapterIndex.coerceIn(0, chapters.size - 1)
        val requestedAhead = ahead.coerceAtLeast(0).coerceAtMost(MAX_AHEAD)
        val span = if (chapters == null) requestedAhead
        else ((start + requestedAhead).coerceAtMost(chapters.size - 1)) - start

        requests[key] = MangaPrefetchTask(sourceId, bookUrl, start, span)
        launchLoop(key)
        // 目录未知 ⇒ 只回报"已受理"（1）：真实章数要抓完目录才知道，`planned` 由 drain 回填。
        // **绝不能返回 0**——上层会把 0 当成"没事可做"（`nothing_to_prefetch`），
        // 于是从书架点"缓存全本"什么都不会发生，只有打开书（目录被阅读器落库）之后才动。
        return if (chapters == null) 1 else span + 1
    }

    /**
     * 保证"同一本书同时只可能有一条循环"。
     *
     * `compute` 是原子的：两个并发请求不会各自起一条循环。
     */
    private fun launchLoop(key: String) {
        jobs.compute(key) { _, current ->
            if (current?.isActive == true) {
                current
            } else {
                scope.launch(prefetchDispatcher) {
                    try {
                        drain(key)
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        log("manga prefetch aborted: $key (${error.message})")
                    } finally {
                        // ⚠️ 顺序很重要：先摘 job 再清 active，最后再看有没有"刚好赶进来"的意图。
                        // 若在 drain 返回与摘 job 之间进来一个新意图，[launchLoop] 里的 compute
                        // 会看到 isActive=true 而不再起循环 ⇒ 那个意图将永远没人执行（静默不动）。
                        jobs.remove(key)
                        progress.remove(key)
                        active.remove(key)
                        if (requests.containsKey(key)) launchLoop(key)
                    }
                }
            }
        }
    }

    /** 取走最新意图并跑完。每章结束后重新读意图 ⇒ 用户改目标时**在同一条循环里**转向。 */
    private suspend fun drain(key: String) {
        while (true) {
            val task = requests.remove(key) ?: return
            active[key] = task
            // 目录缺失时在这里**自己抓**（阅读路径同款的 chapters ⇒ details ⇒ chapters 回退链）。
            // 这是"不打开书也能缓存"的关键：抓取必须发生在协程里，enqueue 是非 suspend 的。
            val chapters = ensurePrefetchChapters(database, runner, task.sourceId, task.bookUrl, log)
            if (chapters.isNullOrEmpty()) {
                log("manga prefetch skipped: 目录不可用 ${task.bookUrl}")
                return
            }
            val start = task.fromChapterIndex.coerceIn(0, chapters.size - 1)
            val end = (start + task.ahead).coerceAtMost(chapters.size - 1)
            val planned = chapters.subList(start, end + 1)

            // 章口径（done）与图片口径（doneImages）分开记：
            // done 只加"整章图片全部落盘"的章，doneImages 加本次真正落盘的张数。
            var doneChapters = 0
            var doneImages = 0
            var plannedImages = 0
            var failedImages = 0
            var lastError: String? = null

            for ((offset, chapter) in planned.withIndex()) {
                val chapterIndex = start + offset
                // 逐章解析：一章的清单解析完就抓它，不做"先把全部目录页解析一遍"的突发
                plannedImages += ensureManifest(task.sourceId, task.bookUrl, chapter)
                progress[key] = status(task, chapterIndex, doneChapters, planned.size, doneImages, plannedImages, failedImages, lastError)
                val outcome = fetchChapter(task.sourceId, task.bookUrl, chapter)
                doneImages += outcome.fetched
                failedImages += outcome.failed
                if (outcome.firstError != null) lastError = outcome.firstError
                // 整章就绪 ⇒ 这才算"完成一章"（章节口径的唯一判据，与目录绿点同源）
                if (outcome.ready) doneChapters++
                progress[key] = status(task, chapterIndex, doneChapters, planned.size, doneImages, plannedImages, failedImages, lastError)
                // 每章收口后顺手看一眼配额：预取正是把磁盘吃满的元凶，这里是最自然的触发点。
                // "没超阈值"的检查只有一条 sum，代价可忽略。
                runCatching { gc?.maybeRun() }
                // 用户又点了别的章 ⇒ 立刻转向（断点续做）
                if (requests.containsKey(key)) break
            }
            if (!requests.containsKey(key)) return
        }
    }

    private fun status(
        task: MangaPrefetchTask,
        chapterIndex: Int,
        doneChapters: Int,
        plannedChapters: Int,
        doneImages: Int,
        plannedImages: Int,
        failedImages: Int,
        lastError: String?,
    ): MangaPrefetchStatus = MangaPrefetchStatus(
        sourceId = task.sourceId,
        bookUrl = task.bookUrl,
        running = true,
        done = doneChapters,
        planned = plannedChapters,
        currentChapterIndex = chapterIndex,
        doneImages = doneImages,
        plannedImages = plannedImages,
        failed = failedImages,
        lastError = lastError,
    )

    /**
     * 确保某章有清单（L2）。返回本章规划抓取的张数。
     *
     * 清单已在本地 ⇒ **完全不回源抓章节页**（与 manifest 路由同一口径，二次打开零上游请求）。
     */
    private suspend fun ensureManifest(sourceId: String, bookUrl: String, chapter: Chapter): Int {
        val existing = database.getMangaChapter(sourceId, bookUrl, chapter.url)
        val cachedUrls = existing?.let { decodeMangaImageUrls(it.imagesJson) }
        if (!cachedUrls.isNullOrEmpty()) {
            imageCache.registerChapter(sourceId, bookUrl, chapter.url, chapter.index, cachedUrls)
            imageCache.refreshChapterReadiness(sourceId, bookUrl, chapter.url)
            return cachedUrls.size
        }
        val source = database.getSource(sourceId) ?: return 0
        // 预取与在线阅读走**同一份**规则上下文（含书籍变量）；否则"手动点开能看、后台预取却取到别的结果"。
        val book = database.bookContext(sourceId, bookUrl)
        val parsed = runCatching { runner.mangaImages(source.json, chapter.url, bookName = null, book = book) }
            .onFailure { log("manga prefetch manifest failed: ${chapter.url} (${it.message})") }
            .getOrNull() ?: return 0
        database.persistBookVariables(book)
        if (parsed.isEmpty()) return 0
        runCatching {
            database.saveMangaChapter(
                MangaChapterRecord(
                    sourceId = sourceId,
                    bookUrl = bookUrl,
                    chapterUrl = chapter.url,
                    chapterIndex = chapter.index,
                    imagesJson = encodeMangaImageUrls(parsed),
                    total = parsed.size,
                    state = "pending",
                ),
            )
        }.onFailure { log("manga prefetch save manifest failed: ${chapter.url} (${it.message})") }
        imageCache.registerChapter(sourceId, bookUrl, chapter.url, chapter.index, parsed)
        imageCache.refreshChapterReadiness(sourceId, bookUrl, chapter.url)
        return parsed.size
    }

    /** 一章的抓取结果（章口径 + 图片口径 + 失败原因）。 */
    private data class ChapterFetchOutcome(
        val fetched: Int,
        val failed: Int,
        val firstError: String?,
        /** 整章就绪（`ready == total`）——章口径 `done` 的唯一判据。 */
        val ready: Boolean,
    )

    /** 抓满一章（已就绪的跳过）。返回本次新抓的张数与失败情况。 */
    private suspend fun fetchChapter(sourceId: String, bookUrl: String, chapter: Chapter): ChapterFetchOutcome {
        val sourceJson = database.getSource(sourceId)?.json ?: return ChapterFetchOutcome(
            fetched = 0,
            failed = 0,
            firstError = null,
            ready = chapterReady(sourceId, bookUrl, chapter.url),
        )
        val rows = imageCache.chapterRows(sourceId, bookUrl, chapter.url)
        var fetched = 0
        var failed = 0
        var firstError: String? = null
        var inBatch = 0
        for (row in rows) {
            // ready 且文件在 ⇒ 跳过；failed 的**不跳过**（上游抖动是常态，
            // 而"重试"是阅读器显式提供的动作，预取没有理由比它更悲观）
            if (imageCache.getByKey(row.urlHash) != null) continue
            val bytes = fetcher.fetch(
                upstreamUrl = row.upstreamUrl,
                mediaHeaders = runner.mediaHeaders(sourceJson, sourceId),
                referer = chapter.url.takeIf { it.startsWith("http") },
            )
            if (bytes == null) {
                failed++
                // `MangaImageFetcher` 失败只返回 null（不像音频那样带原因），所以把地址截短带上：
                // 这是排查"某几张图永远抓不到"的唯一线索，同时不能把 URL 整串塞进 UI。
                if (firstError == null) firstError = "图片抓取失败（${row.upstreamUrl.take(60)}）"
                runCatching { imageCache.markFailed(row.urlHash, "预取失败") }
                log("manga prefetch image failed: ${row.urlHash} (${row.upstreamUrl.take(120)})")
                continue
            }
            val stored = runCatching {
                imageCache.put(
                    sourceId = sourceId,
                    bookUrl = bookUrl,
                    chapterUrl = chapter.url,
                    upstreamUrl = row.upstreamUrl,
                    contentType = bytes.contentType,
                    bytes = bytes.bytes,
                    chapterIndex = row.chapterIndex,
                    segmentIndex = row.segmentIndex,
                )
            }
            if (stored.isFailure) {
                // 落盘失败也是失败：必须把行标 failed，否则它会永远停在 pending，
                // 整章就绪度永远上不去（绿点永远不亮，用户只会看到"缓存不动"）。
                failed++
                val reason = "图片落盘失败（${stored.exceptionOrNull()?.message ?: "未知错误"}）"
                if (firstError == null) firstError = reason
                runCatching { imageCache.markFailed(row.urlHash, reason.take(200)) }
                log("manga prefetch put failed: ${row.urlHash} (${stored.exceptionOrNull()?.message})")
                continue
            }
            fetched++
            inBatch++
            // 错峰：每 3 张让出 300ms，别把 IO/网络吃满（前台接口必须仍然可用）
            if (inBatch >= BATCH_SIZE) {
                inBatch = 0
                delay(BATCH_DELAY_MS)
            }
        }
        val readyCount = imageCache.refreshChapterReadiness(sourceId, bookUrl, chapter.url)
        return ChapterFetchOutcome(fetched, failed, firstError, ready = rows.isNotEmpty() && readyCount == rows.size)
    }

    /** 整章是否已就绪（`ready == total`，与目录绿点同一口径）。 */
    private fun chapterReady(sourceId: String, bookUrl: String, chapterUrl: String): Boolean =
        database.getMangaChapter(sourceId, bookUrl, chapterUrl)?.state == "ready"

    /** 取消某本书的预取（清理缓存时调用；残留意图也要清掉，否则循环会又跑起来）。 */
    fun cancel(sourceId: String, bookUrl: String) {
        val key = bookKey(sourceId, bookUrl)
        requests.remove(key)
        active.remove(key)
        jobs.remove(key)?.cancel()
        progress.remove(key)
    }

    fun cancelAll() {
        requests.clear()
        active.clear()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        progress.clear()
    }

    fun status(): List<MangaPrefetchStatus> {
        val running = jobs.filterValues { it.isActive }.keys
        return progress.entries.filter { it.key in running }.map { it.value.copy(running = true) }
    }

    private fun bookKey(sourceId: String, bookUrl: String) = "$sourceId\n$bookUrl"

    private companion object {
        /** 每抓这么多张就让出一次（与既有 `BookCacheService` 的 `RESUME_BATCH_SIZE` 同思想）。 */
        const val BATCH_SIZE = 3
        const val BATCH_DELAY_MS = 300L

        /** 单次请求最多预取多少章（防"全本"类请求把队列撑爆；前端目前最多传 100）。 */
        const val MAX_AHEAD = 2000

        /**
         * 预取是**阻塞式**网络 IO（`MangaImageFetcher` / `RuleRunner` 内部都是阻塞调用），
         * 必须显式落到 IO 调度器：跑在 Ktor 应用调度器上会把它占住，表现为整站变慢、
         * 预取自己也很慢（历史上后台任务拖垮前台接口是踩过的坑，见 SESSION-025）。
         */
        val prefetchDispatcher = Dispatchers.IO
    }
}
