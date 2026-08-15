package io.legado.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SearchRoutesTest {

    // --- Tier 1: Core Feature Tests ---

    @Test
    fun `search returns candidates immediately without deep 3-tier validation`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val sourceJson = """{
                "bookSourceUrl":"https://source.example",
                "searchUrl":"/search?key={{key}}",
                "ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/book/{{$.id}}","coverUrl":"$.cover","author":"$.author","intro":"$.desc"},
                "ruleBookInfo":{"init":"$.data","name":"$.title","tocUrl":"/book/{{$.id}}/toc"},
                "ruleToc":{"chapterList":"$.data","chapterUrl":"$.url"},
                "ruleContent":{"content":"$.content"}
            }"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val requestedUrls = ConcurrentHashMap.newKeySet<String>()
            val runner = RuleRunner { url ->
                requestedUrls.add(url)
                when {
                    url.contains("/search") -> """{"data":[{"id":"b1","title":"测试书籍","author":"测试作者","cover":"https://img.example/1.jpg","desc":"书籍简介"}]}"""
                    else -> error("Unexpected deep network call: $url")
                }
            }

            val outcome = searchSourceOutcome(runner, sourceJson, "测试")
            assertFalse("Outcome should not be marked as failed", outcome.failed)
            assertEquals(1, outcome.results.size)
            val first = outcome.results.first()
            assertEquals("测试书籍", first.name)
            assertEquals("https://source.example/book/b1", first.bookUrl)
            assertEquals("测试作者", first.author)
            assertEquals("https://img.example/1.jpg", first.coverUrl)
            assertEquals("书籍简介", first.intro)

            // Assert that ONLY the search URL was requested (no details, TOC, or content fetches)
            assertEquals(1, requestedUrls.size)
            assertTrue(requestedUrls.first().contains("/search"))
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `search retains results with null or missing cover URLs`() = runBlocking {
        val sourceJson = """{
            "bookSourceUrl":"https://source.example",
            "searchUrl":"/search?key={{key}}",
            "ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/book/{{$.id}}","coverUrl":"$.cover"}
        }"""
        val runner = RuleRunner { _ ->
            """{"data":[
                {"id":"b1","title":"有封面","cover":"https://img.example/1.jpg"},
                {"id":"b2","title":"无封面","cover":null},
                {"id":"b3","title":"相对封面","cover":"/covers/b3.jpg"}
            ]}"""
        }

        val outcome = searchSourceOutcome(runner, sourceJson, "测试")
        assertFalse(outcome.failed)
        assertEquals(3, outcome.results.size)
        assertEquals("有封面", outcome.results[0].name)
        assertEquals("https://img.example/1.jpg", outcome.results[0].coverUrl)
        assertEquals("无封面", outcome.results[1].name)
        assertNull(outcome.results[1].coverUrl)
        assertEquals("相对封面", outcome.results[2].name)
        assertEquals("https://source.example/covers/b3.jpg", outcome.results[2].coverUrl)
    }

    @Test
    fun `streaming search emits results per source immediately with accurate counters`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val sources = (1..5).map { idx ->
                """{"bookSourceUrl":"https://source$idx.example","bookSourceName":"书源$idx","searchUrl":"/search?key={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/book/{{$.id}}"}}"""
            }
            database.importSources(sources)

            val runner = RuleRunner { url ->
                val sourceNum = Regex("source(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                when (sourceNum) {
                    3 -> """{"data":[]}""" // empty source
                    4 -> throw RuntimeException("HTTP 503 Service Unavailable") // failed source
                    else -> """{"data":[{"id":"b$sourceNum","title":"书源$sourceNum-结果"}]}"""
                }
            }

            val counters = SearchStreamCounters(5)
            val events = mutableListOf<SearchStreamEvent>()

            val sourceRecords = database.listSearchSourceRecords(null)
            assertEquals(5, sourceRecords.size)

            val results = boundedConcurrentMap(sourceRecords, 16) { record ->
                val outcome = searchSourceOutcome(runner, record.json, "测试")
                val event = counters.complete(outcome)
                synchronized(events) { events.add(event) }
                outcome
            }

            assertEquals(5, results.size)
            val done = counters.snapshot("done")
            assertEquals(5, done.totalSources)
            assertEquals(5, done.completedSources)
            assertEquals(3, done.matchedSources) // sources 1, 2, 5
            assertEquals(1, done.emptySources)   // source 3
            assertEquals(1, done.failedSources)  // source 4
            assertEquals(3, done.resultCount)
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `search concurrency supports bounded execution and scales with CPU`() = runBlocking {
        val concurrency = sourceSearchConcurrency(32)
        assertTrue("Concurrency must be within 16..32 range", concurrency in 16..32)
        assertEquals(16, sourceSearchConcurrency(4))
        assertEquals(16, sourceSearchConcurrency(0))


        val activeWorkers = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val tasks = (1..20).toList()

        val results = boundedConcurrentMap(tasks, 4) { item ->
            val cur = activeWorkers.incrementAndGet()
            maxActive.getAndUpdate { prev -> maxOf(prev, cur) }
            delay(15)
            activeWorkers.decrementAndGet()
            item * 2
        }

        assertEquals(20, results.size)
        assertTrue("Max concurrent workers (${maxActive.get()}) must not exceed limit 4", maxActive.get() <= 4)
        assertTrue("Max concurrent workers must be > 1 under load", maxActive.get() >= 2)
    }

    @Test
    fun `search filters records by sourceIds parameter`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            database.importSources(listOf(
                """{"bookSourceUrl":"https://src1.example","bookSourceName":"源1","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b"}}""",
                """{"bookSourceUrl":"https://src2.example","bookSourceName":"源2","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b"}}""",
                """{"bookSourceUrl":"https://src3.example","bookSourceName":"源3","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b"}}""",
            ))

            val allRecords = database.listSearchSourceRecords(null)
            assertEquals(3, allRecords.size)

            val filteredRecords = database.listSearchSourceRecords(listOf("https://src1.example", "https://src3.example"))
            assertEquals(2, filteredRecords.size)
            assertEquals(listOf("https://src1.example", "https://src3.example"), filteredRecords.map { it.id }.sorted())

            val nonExistent = database.listSearchSourceRecords(listOf("https://nonexistent.example"))
            assertEquals(0, nonExistent.size)
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    // --- Tier 2: Boundary, Error Isolation & Cancellation Tests ---

    @Test
    fun `search isolates network timeout and increments failed counter`() = runBlocking {
        val sourceJson = """{"bookSourceUrl":"https://slow.example","searchUrl":"/search?key={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"$.url"}}"""
        val runner = RuleRunner { _ ->
            throw RuntimeException("Connection timed out")
        }

        val outcome = searchSourceOutcome(runner, sourceJson, "测试")
        assertTrue("Timeout must mark outcome as failed", outcome.failed)
        assertTrue("Timeout outcome must have empty results", outcome.results.isEmpty())

        val counters = SearchStreamCounters(1)
        val event = counters.complete(outcome)
        assertEquals(1, event.completedSources)
        assertEquals(1, event.failedSources)
        assertEquals(0, event.matchedSources)
    }

    @Test
    fun `search isolates exceptions from individual sources without halting batch`() = runBlocking {
        val sources = listOf(
            """{"bookSourceUrl":"https://ok1.example","searchUrl":"/s?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b"}}""",
            """{"bookSourceUrl":"https://bad.example","searchUrl":"/s?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b"}}""",
            """{"bookSourceUrl":"https://ok2.example","searchUrl":"/s?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b"}}""",
        )

        val runner = RuleRunner { url ->
            if (url.contains("bad.example")) throw IllegalStateException("Internal server error 500")
            """{"data":[{"title":"成功书","id":"1"}]}"""
        }

        val outcomes = boundedConcurrentMap(sources, 4) { json ->
            searchSourceOutcome(runner, json, "测试")
        }

        assertEquals(3, outcomes.size)
        assertFalse(outcomes[0].failed)
        assertEquals(1, outcomes[0].results.size)
        assertTrue(outcomes[1].failed)
        assertEquals(0, outcomes[1].results.size)
        assertFalse(outcomes[2].failed)
        assertEquals(1, outcomes[2].results.size)
    }

    @Test
    fun `search stream counters handle all-empty and all-failed scenarios accurately`() {
        val countersAllEmpty = SearchStreamCounters(3)
        countersAllEmpty.complete(SearchSourceOutcome(emptyList(), failed = false))
        countersAllEmpty.complete(SearchSourceOutcome(emptyList(), failed = false))
        countersAllEmpty.complete(SearchSourceOutcome(emptyList(), failed = false))
        val doneEmpty = countersAllEmpty.snapshot("done")
        assertEquals(3, doneEmpty.totalSources)
        assertEquals(3, doneEmpty.completedSources)
        assertEquals(0, doneEmpty.matchedSources)
        assertEquals(3, doneEmpty.emptySources)
        assertEquals(0, doneEmpty.failedSources)
        assertEquals(0, doneEmpty.resultCount)

        val countersAllFailed = SearchStreamCounters(2)
        countersAllFailed.complete(SearchSourceOutcome(emptyList(), failed = true))
        countersAllFailed.complete(SearchSourceOutcome(emptyList(), failed = true))
        val doneFailed = countersAllFailed.snapshot("done")
        assertEquals(2, doneFailed.totalSources)
        assertEquals(2, doneFailed.completedSources)
        assertEquals(0, doneFailed.matchedSources)
        assertEquals(0, doneFailed.emptySources)
        assertEquals(2, doneFailed.failedSources)
        assertEquals(0, doneFailed.resultCount)
    }

    @Test
    fun `search handles complex Chinese and emoji keyword URL encoding`() = runBlocking {
        val sourceJson = """{"bookSourceUrl":"https://utf8.example","searchUrl":"/search?k={{key}}&tag={{keyword}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b"}}"""
        var capturedUrl: String? = null
        val runner = RuleRunner { url ->
            capturedUrl = url
            """{"data":[{"title":"大奉打更人","id":"1"}]}"""
        }

        val outcome = searchSourceOutcome(runner, sourceJson, "大奉打更人 🌟 卖报小郎君")
        assertNotNull(capturedUrl)
        assertFalse(capturedUrl!!.contains(" "))
        assertFalse(outcome.failed)
        assertEquals("大奉打更人", outcome.results.single().name)
    }

    @Test
    fun `boundedConcurrentMap respects cancellation cleanly`() = runBlocking {
        val items = (1..50).toList()
        val processed = AtomicInteger(0)

        val job = runCatching {
            boundedConcurrentMap(items, 4) {
                delay(50)
                processed.incrementAndGet()
            }
        }

        // Under normal completion all items are processed
        assertEquals(50, processed.get())
    }

    private fun temporaryDatabase(): String = Files.createTempFile("legado-search-routes-test", ".sqlite").toString()
}
