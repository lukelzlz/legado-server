package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 服务内的音频缓存任务（同一本书后来的请求会**顶掉**前一个的待办区间，而不是新起一条并行任务）。 */
data class AudioPrefetchTask(
    val sourceId: String,
    val bookUrl: String,
    val fromChapterIndex: Int,
    val ahead: Int,
)

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
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, AudioPrefetchStatus>()

    /**
     * 投递一次批量缓存。
     *
     * @return 规划的章节数；`0` 表示没做（被关闭 / 目录未缓存 / 区间为空）。
     *         **返回 0 不是错误**，调用方不该因此报错——缓存只是增强。
     */
    fun enqueue(sourceId: String, bookUrl: String, fromChapterIndex: Int, ahead: Int = 1, pin: Boolean = false): Int {
        if (!enabled) return 0
        val chapters = resolveChapters(sourceId, bookUrl) ?: return 0
        if (chapters.isEmpty()) return 0
        if (pin) {
            // 「整本离线」的免死金牌越早打越好：中途重启也不至于被 GC 从半本开始删
            runCatching { database.setAudioBookPinned(sourceId, bookUrl, true) }
                .onFailure { log("audio prefetch pin failed: ${it.message}") }
        }
        val start = fromChapterIndex.coerceIn(0, chapters.size - 1)
        val end = (start + ahead.coerceAtLeast(0).coerceAtMost(MAX_AHEAD)).coerceAtMost(chapters.size - 1)
        val planned = end - start + 1
        if (planned <= 0) return 0

        val key = bookKey(sourceId, bookUrl)
        requests[key] = AudioPrefetchTask(sourceId, bookUrl, start, end - start)
        // compute 保证"同一本书同时只可能有一条任务"
        jobs.compute(key) { _, current ->
            if (current?.isActive == true) current
            else scope.launch {
                try {
                    drain(key)
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    log("audio prefetch aborted: $key (${error.message})")
                } finally {
                    jobs.remove(key)
                    progress.remove(key)
                }
            }
        }
        return planned
    }

    /** 取走最新意图并跑完。每章结束后重新读意图 ⇒ 用户改目标时**在同一条循环里**转向。 */
    private suspend fun drain(key: String) {
        while (true) {
            val task = requests.remove(key) ?: return
            val chapters = resolveChapters(task.sourceId, task.bookUrl) ?: return
            if (chapters.isEmpty()) return
            val start = task.fromChapterIndex.coerceIn(0, chapters.size - 1)
            val end = (start + task.ahead).coerceAtMost(chapters.size - 1)
            val planned = chapters.subList(start, end + 1)

            var done = 0
            var plannedTracks = 0
            for ((offset, chapter) in planned.withIndex()) {
                val chapterIndex = start + offset
                // 逐章解析：一章的清单解析完就抓它，不做"先把全部目录页解析一遍"的突发
                plannedTracks += ensureTracks(task.sourceId, task.bookUrl, chapter)
                progress[key] = AudioPrefetchStatus(task.sourceId, task.bookUrl, true, done, plannedTracks, chapterIndex)
                done += fetchChapter(task.sourceId, task.bookUrl, chapter)
                progress[key] = AudioPrefetchStatus(task.sourceId, task.bookUrl, true, done, plannedTracks, chapterIndex)
                // 每章收口后顺手看一眼配额：批量缓存正是把磁盘吃满的元凶，这里是最自然的触发点
                runCatching { gc?.maybeRun() }
                // 用户又点了别的章 ⇒ 立刻转向（断点续做）
                if (requests.containsKey(key)) break
            }
            if (!requests.containsKey(key)) return
        }
    }

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

    /** 抓满一章（已就绪的跳过）。返回本次新抓的分段数。 */
    private suspend fun fetchChapter(sourceId: String, bookUrl: String, chapter: Chapter): Int {
        val sourceJson = database.getSource(sourceId)?.json ?: return 0
        val rows = audioCache.chapterRows(sourceId, bookUrl, chapter.url)
        var fetched = 0
        var inBatch = 0
        for (row in rows) {
            // ready 且文件在 ⇒ 跳过；failed 的**不跳过**（上游抖动是常态）
            if (audioCache.getByKey(row.cacheKey) != null) continue
            val outcome = fetcher.fetchInto(
                upstreamUrl = row.upstreamUrl,
                mediaHeaders = runner.mediaHeaders(sourceJson, sourceId),
                referer = chapter.url.takeIf { it.startsWith("http") },
                trackHeadersJson = row.headersJson,
                store = audioCache.store,
                target = audioCache.targetFor(row),
            )
            when (outcome) {
                is AudioFetchOutcome.Failure -> {
                    runCatching { audioCache.markFailed(row.cacheKey, outcome.reason) }
                    continue
                }
                is AudioFetchOutcome.Success -> {
                    // 字节已在 `fetchInto` 里**流式落盘**（不是先读进内存），这里只做"先盘后表"的翻牌
                    runCatching { audioCache.markReady(row, outcome.contentType, outcome.byteSize, outcome.filePath) }
                        .onFailure { log("audio prefetch put failed: ${row.cacheKey} (${it.message})") }
                    fetched++
                }
            }
            inBatch++
            // 错峰：每 2 段让出 400ms，别把 IO/网络吃满（前台播放必须仍然可用）
            if (inBatch >= BATCH_SIZE) {
                inBatch = 0
                delay(BATCH_DELAY_MS)
            }
        }
        return fetched
    }

    /** 取消某本书的缓存任务（清理缓存时调用；残留意图也要清掉，否则循环会又跑起来）。 */
    fun cancel(sourceId: String, bookUrl: String) {
        val key = bookKey(sourceId, bookUrl)
        requests.remove(key)
        jobs.remove(key)?.cancel()
        progress.remove(key)
    }

    fun cancelAll() {
        requests.clear()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        progress.clear()
    }

    fun status(): List<AudioPrefetchStatus> {
        val running = jobs.filterValues { it.isActive }.keys
        return progress.entries.filter { it.key in running }.map { it.value.copy(running = true) }
    }

    /** 目录：优先 `bookUrl`，再试 `bookUrl/toc` 变体（与 `/books/chapters` 的兜底口径一致）。 */
    private fun resolveChapters(sourceId: String, bookUrl: String): List<Chapter>? =
        database.getTocCache(sourceId, bookUrl)
            ?: database.getTocCache(sourceId, bookUrl.removeSuffix("/toc") + "/toc")

    private fun bookKey(sourceId: String, bookUrl: String) = "$sourceId\n$bookUrl"

    private companion object {
        /** 每抓这么多段就让出一次。 */
        const val BATCH_SIZE = 2
        const val BATCH_DELAY_MS = 400L

        /** 单次请求最多缓存多少章（防"全本"类请求把队列撑爆）。 */
        const val MAX_AHEAD = 2000
    }
}
