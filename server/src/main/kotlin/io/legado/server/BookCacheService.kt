package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BookCacheService(private val database: Database, private val runner: RuleRunner, private val log: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun start() { database.cacheRequests().forEach(::enqueue) }
    fun stop() { scope.cancel() }

    fun enqueue(book: CachedBookRequest) {
        val key = "${book.sourceId}\u0000${book.bookUrl}"
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch {
            try { cache(book) } finally { jobs.remove(key) }
        }
    }

    fun cancel(sourceId: String, bookUrl: String) { jobs.remove("$sourceId\u0000$bookUrl")?.cancel() }

    private fun cache(book: CachedBookRequest) {
        try {
            val source = database.getSource(book.sourceId) ?: throw IllegalArgumentException("书源不存在")
            val chapters = runner.chapters(source.json, book.tocUrl)
            database.beginBookCache(book.sourceId, book.bookUrl, chapters.size)
            var failures = 0
            chapters.forEach { chapter ->
                try {
                    val content = runner.content(source.json, chapter.url)
                    if (content.content.toByteArray().size <= MAX_CHAPTER_BYTES) database.cacheBookContent(book.sourceId, book.bookUrl, chapter.url, content.copy(title = content.title ?: chapter.title))
                    else failures++
                } catch (_: Throwable) { failures++ }
            }
            val error = if (failures == 0) null else "$failures 章未缓存"
            database.finishBookCache(book.sourceId, book.bookUrl, error)
            log("book cache completed: ${book.bookUrl}, chapters=${chapters.size}, failures=$failures")
        } catch (error: Throwable) {
            database.finishBookCache(book.sourceId, book.bookUrl, error.message ?: "缓存失败")
            log("book cache failed: ${book.bookUrl}, error=${error.message}")
        }
    }

    private companion object { const val MAX_CHAPTER_BYTES = 2 * 1024 * 1024 }
}
