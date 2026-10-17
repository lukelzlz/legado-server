package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 服务内的音频缓存任务（同一本书后来的请求会**顶掉**前一个的待办区间，而不是新起一条并行任务）。 */
data class AudioPrefetchTask(
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
 * 方案 G2 环节 G5：音频的**后台批量缓存 / 预取**。
 *
 * 与 [MangaPrefetchService] 同构（同一套并发模型，不新造一套）：
 * - 全局限流**不在这里**，在 [AudioFetcher] 的 `Semaphore`（抓取收口）——这里只错峰投放；
 * - 批量错峰：每 [BATCH_SIZE] 段让出 [BATCH_DELAY_MS]，避免把 IO/网络吃满；
 * - **断点续做**：`requests` 里始终保留"最新意图"，正在跑的循环每章结束后重新读取 ⇒
 *   用户连点三章不会产生三条并行任务，而是同一条循环改目标；
 * - **逐章解析**：规划章节的清单**用一章解析一章**，绝不先把 50 章的目录页一次解析完；
 * - **失败静默**：任何一层失败只记日志。`LEGADO_AUDIO_PREFETCH=false` 是整块回滚开关。
 *
 * ## 实测过的三个"能跑一点就停"的成因（本类针对它们各有一处防御）
 * 1. **目录没缓存就 return 0** ⇒ 从书架点"缓存全本"什么都不发生（只有打开过听书之后才动）。
 *    现在目录缺失由 [ensurePrefetchChapters] 自己抓（与阅读路径同一条回退链）。
 * 2. **播放器每切一章都投递 `ahead=0` 的自动预取**，会把"全本"意图顶成 1 章 ⇒ 批量任务被切成单章。
 *    见 [enqueue] 里的"窄意图不得顶掉宽意图"守卫。
 * 3. **上游连接抖动（实测 `HTTP connect timed out` / `Connection reset`，占 1/4 章节）**
 *    原来只重试一次、失败就永久 failed。现在每段做**带退避的有限重试**，
 *    并把首条失败原因放进 [AudioPrefetchStatus.lastError]，不再静默。
 */
class AudioPrefetchService(
    private val database: Database,
    private val runner: RuleRunner,
    private val audioCache: AudioCache,
    private val fetcher: AudioFetcher,
    private val scope: CoroutineScope,
    private val gc: AudioCacheGc? = null,
    /** `LEGADO_AUDIO_PREFETCH=false` ⇒ 硬关闭。路由层也读它，好把 `prefetch_disabled` 如实报出来。 */
    val enabled: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    private val requests = ConcurrentHashMap<String, AudioPrefetchTask>()

    /**
     * 正在跑的意图：`bookKey -> task`。
     *
     * 为什么需要：见类注释第 2 条。播放器"本章/下一章"的自动预取（`ahead=0`）不能把
     * "全本 / 后 N 章"顶窄，否则用户点了缓存全本、进度表面上一直在"缓存中"却几乎不动。
     */
    private val active = ConcurrentHashMap<String, AudioPrefetchTask>()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, AudioPrefetchStatus>()

    /**
     * 投递一次批量缓存。
     *
     * @return 本次**已受理**的章节数（≥1 表示已受理；`0` 只表示"没做"：开关关闭）。
     *         目录还没缓存时**也受理**（与漫画端同一裁决，理由见 [MangaPrefetchService.enqueue]）。
     */
    fun enqueue(sourceId: String, bookUrl: String, fromChapterIndex: Int, ahead: Int = 1, pin: Boolean = false): Int {
        if (!enabled) return 0
        if (pin) {
            // 「整本离线」的免死金牌越早打越好：中途重启也不至于被 GC 从半本开始删。
            // **放在目录解析之前**：目录抓不到时，用户"整本离线"的意图也必须算数。
            runCatching { database.setAudioBookPinned(sourceId, bookUrl, true) }
                .onFailure { log("audio prefetch pin failed: ${it.message}") }
        }

        // 只读库、不触网：抓目录是 suspend 的事，必须放到 drain 的协程里。
        val chapters = readCachedToc(database, sourceId, bookUrl)
        val key = bookKey(sourceId, bookUrl)

        // 「窄意图不得顶掉宽意图」：自动单章预取（ahead=0 且没打 pin）若落在正在跑的区间里就忽略。
        val running = active[key]
        if (running != null && !pin && ahead <= 0 && running.covers(fromChapterIndex)) return 1

        val start = if (chapters == null) fromChapterIndex.coerceAtLeast(0)
        else fromChapterIndex.coerceIn(0, chapters.size - 1)
        val requestedAhead = ahead.coerceAtLeast(0).coerceAtMost(MAX_AHEAD)
        val span = if (chapters == null) requestedAhead
        else ((start + requestedAhead).coerceAtMost(chapters.size - 1)) - start

        requests[key] = AudioPrefetchTask(sourceId, bookUrl, start, span)
        // ⚠️ 与漫画端同一条教训：`GET /books/audio/prefetch` 与书架 `cacheState` 都只认
        // `progress ∩ 活动 job`。入队时就把占位进度登账，否则"入队 → 第一章分段清单解析完"
        // 这段（抓目录时可达数秒）状态为空 ⇒ 书架列表报 idle/ready ⇒ 前端轮询门永久熄火，
        // 用户看到的就是「退回书架后缓存不动了」。`putIfAbsent`：不重置已在跑的进度。
        progress.putIfAbsent(key, seedStatus(sourceId, bookUrl, start, if (chapters == null) 0 else span + 1))
        launchLoop(key)
        // 目录未知 ⇒ 只回报"已受理"（1）；真实章数由 drain 抓完目录后回填到 `planned`。
        // **绝不能返回 0**：上层会把它当成 `nothing_to_cache`，于是书架的"缓存全本"什么都不会发生。
        return if (chapters == null) 1 else span + 1
    }

    /** 保证"同一本书同时只可能有一条循环"（`compute` 原子，两个并发请求不会各起一条）。 */
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
                        log("audio prefetch aborted: $key (${error.message})")
                    } finally {
                        // 先摘 job 再清 active，最后补一次"退出窗口里刚进来的意图"（详见漫画端同名注释）
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
            val chapters = ensurePrefetchChapters(database, runner, task.sourceId, task.bookUrl, log)
            if (chapters.isNullOrEmpty()) {
                log("audio prefetch skipped: 目录不可用 ${task.bookUrl}")
                return
            }
            val start = task.fromChapterIndex.coerceIn(0, chapters.size - 1)
            val end = (start + task.ahead).coerceAtMost(chapters.size - 1)
            val planned = chapters.subList(start, end + 1)

            // 章口径（done）与分段口径（doneTracks）分开记：
            // done 只加"整章分段全部落盘"的章，doneTracks 加本次真正落盘的分段数。
            var doneChapters = 0
            var doneTracks = 0
            var plannedTracks = 0
            var failedTracks = 0
            var lastError: String? = null

            for ((offset, chapter) in planned.withIndex()) {
                val chapterIndex = start + offset
                // 逐章解析：一章的清单解析完就抓它，不做"先把全部目录页解析一遍"的突发
                plannedTracks += ensureTracks(task.sourceId, task.bookUrl, chapter)
                progress[key] = status(task, chapterIndex, doneChapters, planned.size, doneTracks, plannedTracks, failedTracks, lastError)
                val outcome = fetchChapter(task.sourceId, task.bookUrl, chapter)
                doneTracks += outcome.fetched
                failedTracks += outcome.failed
                if (outcome.firstError != null) lastError = outcome.firstError
                // 整章就绪 ⇒ 这才算"完成一章"（章节口径的唯一判据，与目录绿点同源）
                if (outcome.ready) doneChapters++
                progress[key] = status(task, chapterIndex, doneChapters, planned.size, doneTracks, plannedTracks, failedTracks, lastError)
                // 每章收口后顺手看一眼配额：批量缓存正是把磁盘吃满的元凶，这里是最自然的触发点
                runCatching { gc?.maybeRun() }
                // 本章有失败 ⇒ 让上游喘一口再抓下一章。
                // 实测（2026-10-17 真实数据）：`fqnovelvod` 会成片地连不上（`HTTP connect timed out`），
                // 而每次连接超时本身要烧 10s×2（AudioFetcher 内部还有一次立即重试）⇒ 一个坏地址就吃掉 20s。
                // 紧接着立刻抓下一章只会更容易撞上同一波限制；这里退 2s 比"继续猛冲"更可能成功。
                if (outcome.failed > 0) delay(FAILURE_COOLDOWN_MS)
                // 用户又点了别的章 ⇒ 立刻转向（断点续做）
                if (requests.containsKey(key)) break
            }
            if (!requests.containsKey(key)) return
        }
    }

    private fun status(
        task: AudioPrefetchTask,
        chapterIndex: Int,
        doneChapters: Int,
        plannedChapters: Int,
        doneTracks: Int,
        plannedTracks: Int,
        failedTracks: Int,
        lastError: String?,
    ): AudioPrefetchStatus = AudioPrefetchStatus(
        sourceId = task.sourceId,
        bookUrl = task.bookUrl,
        running = true,
        done = doneChapters,
        planned = plannedChapters,
        currentChapterIndex = chapterIndex,
        doneTracks = doneTracks,
        plannedTracks = plannedTracks,
        failed = failedTracks,
        lastError = lastError,
    )

    /**
     * 入队瞬间的"占位进度"（见 [enqueue] 里的注释）。
     *
     * [planned] 为 0 表示"目录还没抓到、真实章数未知"——列表侧必须把 0 当未知而不是当 0 章。
     */
    private fun seedStatus(
        sourceId: String,
        bookUrl: String,
        currentChapterIndex: Int,
        planned: Int,
    ): AudioPrefetchStatus = AudioPrefetchStatus(
        sourceId = sourceId,
        bookUrl = bookUrl,
        running = true,
        done = 0,
        planned = planned,
        currentChapterIndex = currentChapterIndex,
    )
    /** 确保某章已登记分段（本地已有行就**完全不回源抓章节页**）。返回本章分段数。 */
    private suspend fun ensureTracks(sourceId: String, bookUrl: String, chapter: Chapter): Int {
        val existing = audioCache.chapterRows(sourceId, bookUrl, chapter.url)
        if (existing.isNotEmpty()) return existing.size
        val source = database.getSource(sourceId) ?: return 0
        val parsed = runCatching { runner.audioTracks(source.json, chapter.url) }
            .onFailure { log("audio prefetch manifest failed: ${chapter.url} (${it.message})") }
            .getOrNull() ?: return 0
        if (parsed.isEmpty()) return 0
        return audioCache.registerChapter(sourceId, bookUrl, chapter.url, chapter.index, parsed).size
    }

    /** 一章的抓取结果（章口径 + 分段口径 + 失败原因）。 */
    private data class ChapterFetchOutcome(
        val fetched: Int,
        val failed: Int,
        val firstError: String?,
        /** 整章就绪（每一段都 `ready`）——章口径 `done` 的唯一判据。 */
        val ready: Boolean,
    )

    /**
     * 抓满一章（已就绪的跳过）。返回本次新抓的分段数与失败情况。
     *
     * **每段带退避的有限重试**：实测上游音频站会成片地 `HTTP connect timed out` / `Connection reset`
     * （一本书 34 段里 8 段失败），一次性重试几乎总是撞在同一波抖动上。这里按
     * [RETRY_DELAYS_MS] 退避重试，仍失败才标 `failed` 并把原因带出去（状态 + 日志双份）。
     */
    private suspend fun fetchChapter(sourceId: String, bookUrl: String, chapter: Chapter): ChapterFetchOutcome {
        val sourceJson = database.getSource(sourceId)?.json
            ?: return ChapterFetchOutcome(0, 0, null, chapterReady(sourceId, bookUrl, chapter.url))
        val rows = audioCache.chapterRows(sourceId, bookUrl, chapter.url)
        val mediaHeaders = runner.mediaHeaders(sourceJson, sourceId)
        var fetched = 0
        var failed = 0
        var firstError: String? = null
        var inBatch = 0
        for (row in rows) {
            // ready 且文件在 ⇒ 跳过；failed 的**不跳过**（上游抖动是常态）
            if (audioCache.getByKey(row.cacheKey) != null) continue
            var outcome = fetchOnce(chapter, row, mediaHeaders)
            var retry = 0
            while (outcome is AudioFetchOutcome.Failure && retry < RETRY_DELAYS_MS.size) {
                val waitMs = RETRY_DELAYS_MS[retry]
                retry++
                log("audio prefetch retry in ${waitMs}ms (${retry}/${RETRY_DELAYS_MS.size}): ${row.cacheKey} (${outcome.reason})")
                delay(waitMs)
                outcome = fetchOnce(chapter, row, mediaHeaders)
            }
            if (outcome is AudioFetchOutcome.Failure) {
                failed++
                if (firstError == null) firstError = outcome.reason
                // 退避重试之后仍失败：必须既落库（下次任务会重试）又进日志与状态，
                // 否则用户只能看到"进度不动"，完全无从判断是上游 403、登录态还是超限。
                log("audio prefetch failed: ch=${chapter.index} key=${row.cacheKey} (${outcome.reason})")
                runCatching { audioCache.markFailed(row.cacheKey, outcome.reason) }
                continue
            }
            fetched++
            inBatch++
            // 错峰：每 2 段让出 400ms，别把 IO/网络吃满（前台播放必须仍然可用）
            if (inBatch >= BATCH_SIZE) {
                inBatch = 0
                delay(BATCH_DELAY_MS)
            }
        }
        return ChapterFetchOutcome(fetched, failed, firstError, ready = rows.isNotEmpty() && chapterReady(sourceId, bookUrl, chapter.url))
    }

    private suspend fun fetchOnce(
        chapter: Chapter,
        row: AudioCacheRecord,
        mediaHeaders: Map<String, String>,
    ): AudioFetchOutcome {
        val outcome = fetcher.fetchInto(
            upstreamUrl = row.upstreamUrl,
            mediaHeaders = mediaHeaders,
            referer = chapter.url.takeIf { it.startsWith("http") },
            trackHeadersJson = row.headersJson,
            store = audioCache.store,
            target = audioCache.targetFor(row),
        )
        if (outcome is AudioFetchOutcome.Success) {
            // 字节已在 `fetchInto` 里**流式落盘**（不是先读进内存），这里只做"先盘后表"的翻牌
            runCatching { audioCache.markReady(row, outcome.contentType, outcome.byteSize, outcome.filePath) }
                .onFailure { log("audio prefetch put failed: ${row.cacheKey} (${it.message})") }
        }
        return outcome
    }

    /** 整章是否已就绪（每一段都 `ready`，与目录绿点同一口径）。 */
    private fun chapterReady(sourceId: String, bookUrl: String, chapterUrl: String): Boolean {
        val rows = audioCache.chapterRows(sourceId, bookUrl, chapterUrl)
        return rows.isNotEmpty() && rows.all { it.state == "ready" }
    }

    /** 取消某本书的缓存任务（清理缓存时调用；残留意图也要清掉，否则循环会又跑起来）。 */
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

    fun status(): List<AudioPrefetchStatus> {
        val running = jobs.filterValues { it.isActive }.keys
        return progress.entries.filter { it.key in running }.map { it.value.copy(running = true) }
    }

    private fun bookKey(sourceId: String, bookUrl: String) = "$sourceId\n$bookUrl"

    private companion object {
        /** 每抓这么多段就让出一次。 */
        const val BATCH_SIZE = 2
        const val BATCH_DELAY_MS = 400L

        /** 单次请求最多缓存多少章（防"全本"类请求把队列撑爆）。 */
        const val MAX_AHEAD = 2000

        /**
         * 单段的退避重试间隔：**只重试一次**。
         *
         * 为什么不重试两次：`AudioFetcher` 内部已经对 IOException 立即重试一次，
         * 再叠加这里的多次退避 ⇒ 一个永久坏地址要烧 `3 × (2 × 10s)` ≈ 60s。
         * 实测一本 1497 章的书里 1/4 章节会失败，60s/章 会让整本缓存永远跑不完。
         */
        val RETRY_DELAYS_MS = longArrayOf(2_000L)

        /** 一章里有失败后的冷却时间（见 drain 里的实测说明）。 */
        const val FAILURE_COOLDOWN_MS = 2_000L

        /** 缓存任务是**阻塞式**网络 IO，必须显式落到 IO 调度器（同漫画端）。 */
        val prefetchDispatcher = Dispatchers.IO
    }
}
