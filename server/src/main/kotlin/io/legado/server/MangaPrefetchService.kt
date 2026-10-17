package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 服务内的预取任务（同一本书后来的请求会**顶掉**前一个的待办区间，而不是新起一条并行任务）。 */
data class MangaPrefetchTask(
    val sourceId: String,
    val bookUrl: String,
    val fromChapterIndex: Int,
    val ahead: Int,
)

/** 一本书的预取进度（`GET /api/books/images/prefetch` 的响应元素）。 */
@kotlinx.serialization.Serializable
data class MangaPrefetchStatus(
    val sourceId: String,
    val bookUrl: String,
    val running: Boolean = true,
    /** 已抓张数 / 已规划张数（跨已处理章节累计；规划数随逐章解析而增长）。 */
    val done: Int = 0,
    val planned: Int = 0,
    val currentChapterIndex: Int = 0,
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

    /** 正在跑的任务：`bookKey -> Job`（用于 `cancel` 与"是不是已经在跑"）。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    private val progress = ConcurrentHashMap<String, MangaPrefetchStatus>()

    /**
     * 投递一次预取。
     *
     * @return 规划的章节数；`0` 表示没做（被关闭 / 目录未缓存 / 区间为空）。
     *         **返回 0 不是错误**，调用方（阅读器）不该因此报错——预取只是增强。
     */
    fun enqueue(sourceId: String, bookUrl: String, fromChapterIndex: Int, ahead: Int = 1, pin: Boolean = false): Int {
        if (!enabled) return 0
        val chapters = resolveChapters(sourceId, bookUrl) ?: return 0
        if (chapters.isEmpty()) return 0
        if (pin) {
            // 「整本离线」的免死金牌越早打越好：中途重启也不至于被 GC 从半本开始删。
            // （路由层另外也会打一次，两处都是幂等的 upsert。）
            runCatching { database.setMangaBookPinned(sourceId, bookUrl, true) }
                .onFailure { log("manga prefetch pin failed: ${it.message}") }
        }
        val start = fromChapterIndex.coerceIn(0, chapters.size - 1)
        val end = (start + ahead.coerceAtLeast(0).coerceAtMost(MAX_AHEAD)).coerceAtMost(chapters.size - 1)
        val planned = end - start + 1
        if (planned <= 0) return 0

        val key = bookKey(sourceId, bookUrl)
        requests[key] = MangaPrefetchTask(sourceId, bookUrl, start, end - start)
        // ⚠️ **必须在返回前就把进度登账**（本函数是同步的，`drain` 之后才会第一次写 `progress`）：
        // `GET /books/images/prefetch` 与 `GET /bookshelf` 的 `cacheState='caching'` 都只认
        // `progress ∩ 活动 job`。以前这里不写 ⇒ 从入队到第一章清单解析完这段（抓目录时可达数秒）
        // 状态接口返回空 ⇒ 书架列表报 `idle/ready` ⇒ 前端那个 `if (!caching) return` 的轮询门
        // 直接**永久熄火**，用户看到的正是「退到书架后缓存就不动了」（实测：入队后 53ms 与 265ms
        // 两次采样都是 `ready`，直到 ~1s 才变 `caching`）。
        // `putIfAbsent`：已有在跑的进度绝不能被这次投递重置成 0。
        progress.putIfAbsent(key, seedStatus(sourceId, bookUrl, start, planned))
        // compute 保证"同一本书同时只可能有一条任务"：两个并发请求不会各自起一条循环
        jobs.compute(key) { _, current ->
            if (current?.isActive == true) current
            else scope.launch {
                try {
                    drain(key)
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    log("manga prefetch aborted: $key (${error.message})")
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
            var plannedImages = 0
            for ((offset, chapter) in planned.withIndex()) {
                val chapterIndex = start + offset
                // 逐章解析：一章的清单解析完就抓它，不做"先把全部目录页解析一遍"的突发
                plannedImages += ensureManifest(task.sourceId, task.bookUrl, chapter)
                progress[key] = MangaPrefetchStatus(task.sourceId, task.bookUrl, true, done, plannedImages, chapterIndex)
                done += fetchChapter(task.sourceId, task.bookUrl, chapter)
                progress[key] = MangaPrefetchStatus(task.sourceId, task.bookUrl, true, done, plannedImages, chapterIndex)
                // 每章收口后顺手看一眼配额：预取正是把磁盘吃满的元凶，这里是最自然的触发点。
                // "没超阈值"的检查只有一条 sum，代价可忽略。
                runCatching { gc?.maybeRun() }
                // 用户又点了别的章 ⇒ 立刻转向（断点续做）
                if (requests.containsKey(key)) break
            }
            if (!requests.containsKey(key)) return
        }
    }

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

    /** 抓满一章（已就绪的跳过）。返回本次新抓的张数。 */
    private suspend fun fetchChapter(sourceId: String, bookUrl: String, chapter: Chapter): Int {
        val sourceJson = database.getSource(sourceId)?.json ?: return 0
        val rows = imageCache.chapterRows(sourceId, bookUrl, chapter.url)
        var fetched = 0
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
                runCatching { imageCache.markFailed(row.urlHash, "预取失败") }
                continue
            }
            runCatching {
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
            }.onFailure { log("manga prefetch put failed: ${row.urlHash} (${it.message})") }
            fetched++
            inBatch++
            // 错峰：每 3 张让出 300ms，别把 IO/网络吃满（前台接口必须仍然可用）
            if (inBatch >= BATCH_SIZE) {
                inBatch = 0
                delay(BATCH_DELAY_MS)
            }
        }
        imageCache.refreshChapterReadiness(sourceId, bookUrl, chapter.url)
        return fetched
    }

    /** 取消某本书的预取（清理缓存时调用；残留意图也要清掉，否则循环会又跑起来）。 */
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
    ): MangaPrefetchStatus = MangaPrefetchStatus(
        sourceId = sourceId,
        bookUrl = bookUrl,
        running = true,
        done = 0,
        planned = planned,
        currentChapterIndex = currentChapterIndex,
    )

    fun status(): List<MangaPrefetchStatus> {
        val running = jobs.filterValues { it.isActive }.keys
        return progress.entries.filter { it.key in running }.map { it.value.copy(running = true) }
    }

    /** 目录：优先 `bookUrl`，再试 `bookUrl/toc` 变体（与 `/books/chapters` 的兜底口径一致）。 */
    private fun resolveChapters(sourceId: String, bookUrl: String): List<Chapter>? =
        database.getTocCache(sourceId, bookUrl)
            ?: database.getTocCache(sourceId, bookUrl.removeSuffix("/toc") + "/toc")

    private fun bookKey(sourceId: String, bookUrl: String) = "$sourceId\n$bookUrl"

    private companion object {
        /** 每抓这么多张就让出一次（与既有 `BookCacheService` 的 `RESUME_BATCH_SIZE` 同思想）。 */
        const val BATCH_SIZE = 3
        const val BATCH_DELAY_MS = 300L

        /** 单次请求最多预取多少章（防"全本"类请求把队列撑爆；前端目前最多传 100）。 */
        const val MAX_AHEAD = 2000
    }
}
