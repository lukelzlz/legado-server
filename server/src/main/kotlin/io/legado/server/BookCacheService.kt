package io.legado.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

class BookCacheService(
    private val database: Database,
    private val runner: RuleRunner,
    /**
     * 小说正文插图的落盘缓存（`<data>/images/`）。
     *
     * 传 null ⇒ 只缓存正文、不预热插图（老测试与"只要文字"的调用方不受影响）。
     * 给了它就必须同时给 [illustrationFetcher]：抓图必须有书源 header/Referer/Cookie 与限流收口，
     * 那条链路只有 `MangaImageFetcher` 有。
     */
    private val illustrationCache: IllustrationCache? = null,
    private val illustrationFetcher: MangaImageFetcher? = null,
    /** ⚠️ 必须**留在最后**：既有调用点大量使用尾随 lambda 传 `log`。 */
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * 全局并发闸门：限制**整机**同时在飞的章节抓取数。
     *
     * [CACHE_CONCURRENCY] 是**单本书内部**的并发；一旦同时缓存多本书，
     * 实际并发 = 书数 × CACHE_CONCURRENCY。实测 418 本续做时这就把
     * Dispatchers.IO 占满，服务虽显示 `Application started` 却持续超时数分钟，
     * 数据库还从 13MB 膨胀到 343MB。
     * 有了这道全局闸门，无论排队多少本书，同时进行的网络抓取都恒定有界，
     * 前台请求始终能拿到线程与连接。
     */
    private val globalGate = Semaphore(MAX_GLOBAL_CHAPTER_FETCHES)

    fun start() {
        // 启动续做必须限流：整架书的缓存任务若一次性全部 launch，
        // 会把 Dispatchers.IO 线程池占满，导致服务无法响应任何请求。
        val pending = database.cacheRequests()
        if (pending.isEmpty()) return
        log("book cache resume: ${pending.size} book(s) pending (global concurrency=${MAX_GLOBAL_CHAPTER_FETCHES})")
        scope.launch {
            pending.chunked(RESUME_BATCH_SIZE).forEach { batch ->
                batch.forEach(::enqueue)
                delay(RESUME_BATCH_DELAY_MS)
            }
        }
    }
    fun stop() { scope.cancel() }

    private fun isLocalBook(sourceId: String, bookUrl: String): Boolean {
        return sourceId == LocalBookParser.LOC_BOOK_SOURCE_ID ||
            bookUrl.startsWith("local://") ||
            bookUrl.startsWith("content://") ||
            bookUrl.startsWith("file://")
    }

    fun enqueue(book: CachedBookRequest) {
        if (isLocalBook(book.sourceId, book.bookUrl)) {
            database.ensureLocalBookStatusReady(book.sourceId, book.bookUrl)
            return
        }
        val key = "${book.sourceId}\u0000${book.bookUrl}"
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch {
            try { cache(book) } finally { jobs.remove(key) }
        }
    }

    fun cancel(sourceId: String, bookUrl: String) {
        val job = jobs.remove("$sourceId\u0000$bookUrl")
        job?.cancel()
        if (!isLocalBook(sourceId, bookUrl)) {
            database.finishBookCache(sourceId, bookUrl, "已取消缓存")
        }
    }

    private suspend fun cache(book: CachedBookRequest) {
        if (isLocalBook(book.sourceId, book.bookUrl)) {
            database.ensureLocalBookStatusReady(book.sourceId, book.bookUrl)
            return
        }
        try {
            val source = database.getSource(book.sourceId) ?: throw IllegalArgumentException("书源不存在")
            // 离线缓存也必须带书籍上下文：缓存的正文/目录要跟在线读**一模一样**，
            // 否则按音色/线路参数分派的源会缓存出错版本的正文（Legado 的 CacheBook 同样带着 Book）。
            val bookContext = database.bookContext(book.sourceId, book.bookUrl)
            // 取目录与阅读路径**共用同一实现**（含 details 回退）：聚合源书架里存的 toc_url
            // 可能是一次性错误载荷，过去这里只试一次 ⇒ 出现"能读不能缓存"（实测 462 章的书缓存 0 章）。
            val chapters = withContext(Dispatchers.IO) {
                resolveBookChapters(
                    database, runner, book.sourceId, book.bookUrl, source.json, bookContext,
                    tocUrlHint = book.tocUrl,
                )
            }
            database.persistBookVariables(bookContext)
            if (chapters.isEmpty()) throw RuleExecutionException("目录规则未提取到章节")

            // Slice chapters by range if specified
            val targetChapters = if (book.startIndex != null || book.endIndex != null || book.count != null) {
                val start = (book.startIndex ?: 0).coerceIn(0, chapters.size)
                val end = if (book.endIndex != null) {
                    (book.endIndex + 1).coerceIn(start, chapters.size)
                } else if (book.count != null) {
                    (start + book.count).coerceIn(start, chapters.size)
                } else {
                    chapters.size
                }
                if (start < end) chapters.subList(start, end) else emptyList()
            } else {
                chapters
            }

            // Breakpoint resume: skip already-cached chapter URLs
            val alreadyCached = database.cachedChapterUrls(book.sourceId, book.bookUrl)
            val remaining = targetChapters.filter { it.url !in alreadyCached }

            database.beginBookCache(book.sourceId, book.bookUrl, chapters.size)

            val failures = AtomicInteger(0)
            // 只记**第一条**失败原因：过去这里静默只计数，用户只看到"N 章未缓存"，
            // 完全无从判断是上游登录态、网络不通还是规则问题。
            val firstFailure = AtomicReference<String?>(null)
            val cachedCount = AtomicInteger(alreadyCached.size)
            val lastProgressUpdate = AtomicLong(0L)
            val semaphore = Semaphore(CACHE_CONCURRENCY)
            // 插图预热的战果：**单张失败绝不影响正文缓存**（离线读文字优先），只计数 + 记首条原因
            val illustrationsCached = AtomicInteger(0)
            val illustrationFailures = AtomicInteger(0)
            val firstIllustrationFailure = AtomicReference<String?>(null)

            val bookName = database.listBookshelf().firstOrNull { it.sourceId == book.sourceId && it.bookUrl == book.bookUrl }?.name
            coroutineScope {
                val batches = remaining.chunked(CACHE_CONCURRENCY)
                for ((index, batch) in batches.withIndex()) {
                    if (!isActive) break
                    batch.map { chapter ->
                        async {
                            semaphore.withPermit {
                                try {
                                    // 全局闸门：把「整机同时在飞的章节抓取」限制住，
                                    // 避免多本书并行时把线程池与连接池吃光（详见 globalGate 注释）。
                                    // ⚠️ 每个章节抓取拿**自己的变量表副本**：下面这些抓取是并发的，
                                    // 共享同一个可变的 MutableMap 会在规则写变量时产生竞态。
                                    // 副本只用于"读到当前的变量"（这才是刚需）；此路径不回写变量，
                                    // 因为并发合并语义复杂，而离线缓存本身不需要产生变量。
                                    val contentBook = bookContext.copy(variables = bookContext.variables.toMutableMap())
                                    val content = withContext(Dispatchers.IO) {
                                        globalGate.withPermit { runner.content(source.json, chapter.url, bookName, contentBook) }
                                    }
                                    if (content.content.toByteArray().size <= MAX_CHAPTER_BYTES) {
                                        database.cacheBookContent(
                                            book.sourceId, book.bookUrl, chapter.url,
                                            content.copy(title = content.title ?: chapter.title)
                                        )
                                        // 正文里的插图随章节一起落盘 ⇒ 断网后正文与插图都在本地。
                                        // ⚠️ 这段的一切失败都**只计数**：插图缺失不该让"这一章没缓存上"。
                                        prefetchIllustrations(source.json, book, chapter.index, chapter.url, content.content)
                                            ?.let { outcome ->
                                                illustrationsCached.addAndGet(outcome.cached)
                                                if (outcome.failed > 0) {
                                                    illustrationFailures.addAndGet(outcome.failed)
                                                    outcome.firstError?.let { firstIllustrationFailure.compareAndSet(null, it) }
                                                }
                                            }
                                        reportProgress(book, cachedCount.incrementAndGet(), lastProgressUpdate)
                                    } else {
                                        failures.incrementAndGet()
                                    }
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
                                    if (firstFailure.compareAndSet(null, "${chapter.url}: ${error.message}")) {
                                        log("chapter cache failed: ${chapter.url}, error=${error.message}")
                                    }
                                    failures.incrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
                    if (index < batches.size - 1 && BATCH_THROTTLE_DELAY_MS > 0 && isActive) {
                        delay(BATCH_THROTTLE_DELAY_MS)
                    }
                }
            }

            val totalFailures = failures.get()
            val error = if (totalFailures == 0) null else {
                val reason = firstFailure.get()
                if (reason == null) "$totalFailures 章未缓存" else "$totalFailures 章未缓存（首条原因：$reason）"
            }
            database.finishBookCache(book.sourceId, book.bookUrl, error)
            val illustrationNote = if (illustrationFailures.get() == 0) {
                "插图=$illustrationsCached"
            } else {
                "插图=$illustrationsCached（失败 ${illustrationFailures.get()}，首条原因：${firstIllustrationFailure.get()}）"
            }
            log(
                "book cache completed: ${book.bookUrl}, chapters=${chapters.size}, skipped=${alreadyCached.size}, " +
                    "failures=$totalFailures, $illustrationNote",
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            database.finishBookCache(book.sourceId, book.bookUrl, error.message ?: "缓存失败")
            log("book cache failed: ${book.bookUrl}, error=${error.message}")
        }
    }

    /**
     * 把一章正文里的插图抓下来落盘（离线阅读要用）。
     *
     * 三条硬约束（都来自历史事故，改了就会复发）：
     * 1. **任何失败都只返回计数与首条原因**，绝不抛出 —— 插图是正文的附属物，
     *    "图挂了"不该让整章正文被判为缓存失败；
     * 2. 抓取**必须**走 [MangaImageFetcher]（书源 `header` + 章节页 `Referer` + Cookie、
     *    全局限流、gzip 魔数解压、单图体积上限、图片魔数校验都在那里）——缺 Referer 必裂图；
     * 3. 单章图片数封顶 [MAX_ILLUSTRATIONS_PER_CHAPTER]，防止一页几十张图的章把队列占满。
     *
     * @return null 表示当前实例没接插图缓存（功能未启用）
     */
    private suspend fun prefetchIllustrations(
        sourceJson: String,
        book: CachedBookRequest,
        chapterIndex: Int,
        chapterUrl: String,
        content: String,
    ): IllustrationPrefetchOutcome? {
        val cache = illustrationCache ?: return null
        val fetcher = illustrationFetcher ?: return null
        val urls = runCatching { IllustrationMarkup.urlsIn(content) }.getOrDefault(emptyList())
        if (urls.isEmpty()) return IllustrationPrefetchOutcome(0, 0, null)
        // 正文顺序决定章节内序号（登记是幂等的：已登记的号不会被改写）
        val rows = runCatching {
            cache.registerChapter(book.sourceId, book.bookUrl, chapterUrl, chapterIndex, urls)
        }.getOrElse { error ->
            return IllustrationPrefetchOutcome(0, 1, "$chapterUrl 插图登记失败：${error.message}")
        }
        var cached = 0
        var failed = 0
        var firstError: String? = null
        // 书源请求头解析失败只会返回空表（静默降级），Referer 才是防盗链的主判据
        val headers by lazy { runner.mediaHeaders(sourceJson, book.sourceId) }
        val referer = chapterUrl.takeIf { it.startsWith("http") }
        for (row in rows.take(MAX_ILLUSTRATIONS_PER_CHAPTER)) {
            if (cache.getByKey(row.urlHash) != null) {
                cached++
                continue
            }
            val fetched = fetcher.fetch(row.upstreamUrl, headers, referer)
            if (fetched == null) {
                failed++
                if (firstError == null) firstError = "${row.upstreamUrl}（上游抓取失败或内容不是图片）"
                continue
            }
            runCatching {
                cache.put(
                    sourceId = book.sourceId,
                    bookUrl = book.bookUrl,
                    chapterUrl = chapterUrl,
                    upstreamUrl = row.upstreamUrl,
                    contentType = fetched.contentType,
                    bytes = fetched.bytes,
                    chapterIndex = chapterIndex,
                    segmentIndex = row.segmentIndex,
                )
                cached++
            }.onFailure { error ->
                failed++
                if (firstError == null) firstError = "${row.upstreamUrl}（落盘失败：${error.message}）"
            }
        }
        return IllustrationPrefetchOutcome(cached, failed, firstError)
    }

    /** 一章插图预热的战果（`cached` 张落盘、`failed` 张失败、首条失败原因）。 */
    private data class IllustrationPrefetchOutcome(val cached: Int, val failed: Int, val firstError: String?)

    /**
     * Writes fine-grained cache progress to the database at most once per
     * [PROGRESS_UPDATE_INTERVAL_MS]. The final `finishBookCache` call remains the
     * authoritative sync point, so a throttled update failure never corrupts progress.
     */
    private fun reportProgress(book: CachedBookRequest, cachedCount: Int, lastProgressUpdate: AtomicLong) {
        val now = System.currentTimeMillis()
        while (true) {
            val last = lastProgressUpdate.get()
            if (now - last < PROGRESS_UPDATE_INTERVAL_MS) return
            if (!lastProgressUpdate.compareAndSet(last, now)) continue
            runCatching {
                database.updateBookCacheProgress(book.sourceId, book.bookUrl, cachedCount)
            }
            return
        }
    }

    private companion object {
        const val MAX_CHAPTER_BYTES = 2 * 1024 * 1024
        const val CACHE_CONCURRENCY = 4
        /**
         * 单章插图预热的张数上限。
         *
         * 图片书 / 图文混排的"章节"动辄几十张图，无上限时一本书的后台缓存会退化成
         * 「章节队列被图片占满」；超出部分仍在**在线阅读时按需下载并落盘**（不丢功能）。
         */
        const val MAX_ILLUSTRATIONS_PER_CHAPTER = 20
        const val BATCH_THROTTLE_DELAY_MS = 25L
        const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        /** 启动续做时每批投放的书数，避免一次性占满线程池。 */
        const val RESUME_BATCH_SIZE = 3
        /** 批次之间的让出间隔，给前台请求留出调度机会。 */
        const val RESUME_BATCH_DELAY_MS = 300L
        /**
         * 整机同时在飞的章节抓取上限。
         *
         * 留出足够裕量给前台请求（阅读正文、搜索、封面），
         * 缓存只是后台任务，永远不该饿死交互。
         */
        const val MAX_GLOBAL_CHAPTER_FETCHES = 8
    }
}
