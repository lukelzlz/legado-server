package io.legado.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchConcurrencyTest {
    @Test
    fun `stream counters distinguish matched empty and failed sources`() {
        val counters = SearchStreamCounters(3)
        val result = SearchResult("source-a", "测试书", bookUrl = "https://example.com/book")

        counters.complete(SearchSourceOutcome(listOf(result), failed = false))
        counters.complete(SearchSourceOutcome(emptyList(), failed = false))
        val done = counters.complete(SearchSourceOutcome(emptyList(), failed = true)).copy(type = "done")

        assertEquals(3, done.totalSources)
        assertEquals(3, done.completedSources)
        assertEquals(1, done.matchedSources)
        assertEquals(1, done.emptySources)
        assertEquals(1, done.failedSources)
        assertEquals(1, done.resultCount)
    }

    @Test
    fun `bounded search work respects its concurrency cap and isolates failures`() = runBlocking {
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val results = boundedConcurrentMap((1..8).toList(), 3) { value ->
            val current = running.incrementAndGet()
            maximum.getAndUpdate { previous -> maxOf(previous, current) }
            try {
                delay(10)
                runCatching { if (value == 4) error("source failed") else value }.getOrNull()
            } finally { running.decrementAndGet() }
        }

        assertTrue(maximum.get() <= 3)
        assertEquals(7, results.filterNotNull().size)
    }

    @Test
    fun `source search concurrency scales between 16 and 32 workers`() {
        assertEquals(16, sourceSearchConcurrency(0))
        assertEquals(16, sourceSearchConcurrency(4))
        assertEquals(16, sourceSearchConcurrency(16))
        assertEquals(24, sourceSearchConcurrency(24))
        assertEquals(32, sourceSearchConcurrency(32))
        assertEquals(32, sourceSearchConcurrency(64))
    }

    @Test
    fun `searchSourceOutcome returns candidates directly upon search completion and isolates failures`() = runBlocking {
        val source = """{"bookSourceUrl":"https://src.example","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","bookUrl":"/b/{{$.id}}","name":"$.title"}}"""
        val runner = RuleRunner { url ->
            if (url.contains("fail")) throw RuntimeException("network down")
            """{"data":[{"id":"1","title":"书1"},{"id":"2","title":"书2"}]}"""
        }

        val successOutcome = searchSourceOutcome(runner, source, "正常")
        assertEquals(false, successOutcome.failed)
        assertEquals(2, successOutcome.results.size)
        assertEquals("书1", successOutcome.results[0].name)
        assertEquals("书2", successOutcome.results[1].name)

        val failSource = """{"bookSourceUrl":"https://src.example","searchUrl":"/search?k={{key}}&fail=1","ruleSearch":{"bookList":"$.data","bookUrl":"/b/{{$.id}}","name":"$.title"}}"""
        val failOutcome = searchSourceOutcome(runner, failSource, "失败")
        assertEquals(true, failOutcome.failed)
        assertEquals(0, failOutcome.results.size)
    }
}

