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
import java.util.concurrent.atomic.AtomicLong

class BookCacheService(private val database: Database, private val runner: RuleRunner, private val log: (String) -> Unit) {
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

    fun enqueue(book: CachedBookRequest) {
        val key = "${book.sourceId}\u0000${book.bookUrl}"
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch {
            try { cache(book) } finally { jobs.remove(key) }
        }
    }

    fun cancel(sourceId: String, bookUrl: String) {
        val job = jobs.remove("$sourceId\u0000$bookUrl")
        job?.cancel()
        database.finishBookCache(sourceId, bookUrl, "已取消缓存")
    }

    private suspend fun cache(book: CachedBookRequest) {
        try {
            val source = database.getSource(book.sourceId) ?: throw IllegalArgumentException("书源不存在")
            val chapters = withContext(Dispatchers.IO) { runner.chapters(source.json, book.tocUrl) }
            if (chapters.isEmpty()) throw RuleExecutionException("目录规则未提取到章节")
            database.saveTocCache(book.sourceId, book.tocUrl, chapters)
            if (book.bookUrl != book.tocUrl) {
                database.saveTocCache(book.sourceId, book.bookUrl, chapters)
            }

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
            val cachedCount = AtomicInteger(alreadyCached.size)
            val lastProgressUpdate = AtomicLong(0L)
            val semaphore = Semaphore(CACHE_CONCURRENCY)

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
                                    val content = withContext(Dispatchers.IO) {
                                        globalGate.withPermit { runner.content(source.json, chapter.url, bookName) }
                                    }
                                    if (content.content.toByteArray().size <= MAX_CHAPTER_BYTES) {
                                        database.cacheBookContent(
                                            book.sourceId, book.bookUrl, chapter.url,
                                            content.copy(title = content.title ?: chapter.title)
                                        )
                                        reportProgress(book, cachedCount.incrementAndGet(), lastProgressUpdate)
                                    } else {
                                        failures.incrementAndGet()
                                    }
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
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
            val error = if (totalFailures == 0) null else "$totalFailures 章未缓存"
            database.finishBookCache(book.sourceId, book.bookUrl, error)
            log("book cache completed: ${book.bookUrl}, chapters=${chapters.size}, skipped=${alreadyCached.size}, failures=$totalFailures")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            database.finishBookCache(book.sourceId, book.bookUrl, error.message ?: "缓存失败")
            log("book cache failed: ${book.bookUrl}, error=${error.message}")
        }
    }

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
