package io.legado.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchConcurrencyTest {
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
}
