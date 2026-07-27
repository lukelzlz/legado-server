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
    fun `source search concurrency is capped by CPU and eight`() {
        assertEquals(1, sourceSearchConcurrency(0))
        assertEquals(4, sourceSearchConcurrency(4))
        assertEquals(8, sourceSearchConcurrency(32))
    }

    @Test
    fun `candidate validation stays within the source concurrency cap and isolates failures`() = runBlocking {
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val results = boundedConcurrentMap((1..6).toList(), 2) { source ->
            val current = running.incrementAndGet()
            maximum.getAndUpdate { previous -> maxOf(previous, current) }
            try {
                // A source validates each of its search hits before returning its visible hits.
                (1..3).filter { candidate ->
                    delay(5)
                    source != 3 || candidate != 2
                }.map { "$source-$it" }
            } finally { running.decrementAndGet() }
        }.flatten()

        assertTrue(maximum.get() <= 2)
        assertEquals(17, results.size)
        assertTrue("3-2" !in results)
    }
}
