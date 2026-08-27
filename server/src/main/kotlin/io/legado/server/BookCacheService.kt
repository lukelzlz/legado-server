package io.legado.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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

    fun start() { database.cacheRequests().forEach(::enqueue) }
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

            // Breakpoint resume: skip already-cached chapter URLs
            val alreadyCached = database.cachedChapterUrls(book.sourceId, book.bookUrl)
            val remaining = chapters.filter { it.url !in alreadyCached }

            database.beginBookCache(book.sourceId, book.bookUrl, chapters.size)

            val failures = AtomicInteger(0)
            val cachedCount = AtomicInteger(alreadyCached.size)
            val lastProgressUpdate = AtomicLong(0L)
            val semaphore = Semaphore(CACHE_CONCURRENCY)

            coroutineScope {
                remaining.map { chapter ->
                    async {
                        semaphore.withPermit {
                            try {
                                val content = withContext(Dispatchers.IO) { runner.content(source.json, chapter.url) }
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
        const val CACHE_CONCURRENCY = 8
        const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
    }
}
