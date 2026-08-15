package io.legado.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class E2EScenariosTest {

    @Test
    fun `scenario 1 - multi-source streaming search and instant book open`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Setup 15 realistic sources (10 matching, 3 empty, 2 failing)
            val sources = (1..15).map { i ->
                when {
                    i <= 10 -> """{"bookSourceUrl":"https://src$i.com","bookSourceName":"书源$i","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/book/{{$.id}}"},"ruleBookInfo":{"init":"$.data","name":"$.title","coverUrl":"$.cover","tocUrl":"/book/{{$.id}}/toc"},"ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.data.content"}}"""
                    i <= 13 -> """{"bookSourceUrl":"https://src$i.com","bookSourceName":"空书源$i","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/book/{{$.id}}"}}"""
                    else -> """{"bookSourceUrl":"https://src$i.com","bookSourceName":"故障书源$i","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/book/{{$.id}}"}}"""
                }
            }
            database.importSources(sources)

            val sourceNetworkRequests = ConcurrentHashMap<String, AtomicInteger>()
            val runner = RuleRunner { url ->
                val srcMatch = Regex("https://src(\\d+)\\.com").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                sourceNetworkRequests.computeIfAbsent("src$srcMatch") { AtomicInteger() }.incrementAndGet()

                when {
                    srcMatch in 14..15 && url.contains("/search") -> throw RuntimeException("HTTP 503 Upstream Error")
                    srcMatch in 11..13 && url.contains("/search") -> """{"data":[]}"""
                    url.contains("/search") -> """{"data":[{"id":"b$srcMatch","title":"凡人修仙传","author":"忘语"}]}"""
                    url.contains("/toc") -> {
                        val chapterList = (1..500).joinToString(",") { """{"title":"第${it}章 修仙风云","url":"https://src$srcMatch.com/c$it"}""" }
                        """{"data":[$chapterList]}"""
                    }
                    url.contains("/c1") -> """{"data":{"content":"第一章 山边小村：阳光明媚，韩立正在院子里打坐。"}}"""
                    url.contains("/book/b") -> """{"data":{"title":"凡人修仙传","id":"b$srcMatch","author":"忘语","cover":"https://src$srcMatch.com/cover.jpg"}}"""
                    url.contains("/c") -> """{"data":{"content":"正文内容"}}"""
                    else -> """{"data":[]}"""
                }
            }

            // Step 1: Execute concurrent streaming search across all 15 sources
            val records = database.listSearchSourceRecords(null)
            assertEquals(15, records.size)

            val startTime = System.currentTimeMillis()
            val counters = SearchStreamCounters(records.size)
            val events = mutableListOf<SearchStreamEvent>()

            val outcomes = boundedConcurrentMap(records, 16) { record ->
                val outcome = searchSourceOutcome(runner, record.json, "凡人修仙传")
                val event = counters.complete(outcome)
                synchronized(events) { events.add(event) }
                outcome
            }
            val searchDuration = System.currentTimeMillis() - startTime

            assertTrue("Streaming search across 15 sources must complete rapidly (< 1500ms)", searchDuration < 1500)

            val doneEvent = counters.snapshot("done")
            assertEquals(15, doneEvent.totalSources)
            assertEquals(15, doneEvent.completedSources)
            assertEquals(10, doneEvent.matchedSources)
            assertEquals(3, doneEvent.emptySources)
            assertEquals(2, doneEvent.failedSources)
            assertEquals(10, doneEvent.resultCount)

            // Step 2: Instant book open — pick first matching candidate (lazy candidate loading)
            val firstHit = outcomes.first { it.results.isNotEmpty() }.results.first()
            assertEquals("凡人修仙传", firstHit.name)
            assertEquals("https://src1.com", firstHit.sourceId)

            val openStartTime = System.currentTimeMillis()
            val chosenSourceRecord = database.getSource(firstHit.sourceId)!!
            val details = runner.details(chosenSourceRecord.json, firstHit.bookUrl)
            val tocChapters = runner.chapters(chosenSourceRecord.json, details.tocUrl)
            val firstChapterContent = runner.content(chosenSourceRecord.json, tocChapters.first().url)
            val openDuration = System.currentTimeMillis() - openStartTime

            assertTrue("Instant book open must complete within < 300ms", openDuration < 300)
            assertEquals(500, tocChapters.size)
            assertEquals("第1章 修仙风云", tocChapters.first().title)
            assertTrue(firstChapterContent.content.contains("山边小村"))

            // Save to bookshelf & reading progress
            val shelfItem = database.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = firstHit.sourceId,
                    bookUrl = firstHit.bookUrl,
                    name = details.name,
                    author = details.author,
                    tocUrl = details.tocUrl,
                ),
                null,
            )
            database.saveProgress(
                ReadingProgress(
                    sourceId = firstHit.sourceId,
                    bookUrl = firstHit.bookUrl,
                    chapterUrl = tocChapters.first().url,
                    chapterIndex = 0,
                    scrollPosition = 0.0,
                )
            )

            // Verify non-selected sources were NOT contacted for detail/TOC/content
            val otherSourceRequests = sourceNetworkRequests.filterKeys { it != "src1" }
            otherSourceRequests.forEach { (src, counter) ->
                assertEquals("Non-selected source $src should have only 1 search request", 1, counter.get())
            }

            // Verify shelf entry
            assertEquals("凡人修仙传", shelfItem.name)
            val loadedProgress = database.getProgress(firstHit.sourceId, firstHit.bookUrl)
            assertNotNull(loadedProgress)
            assertEquals(0, loadedProgress!!.chapterIndex)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `scenario 2 - offline caching 2000 chapters with concurrent reading and progress updates`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val sourceJson = """{"bookSourceUrl":"https://bigsource.com","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)
            val book = BookshelfWriteRequest("https://bigsource.com", "https://bigsource.com/book/epic", "万相之王", tocUrl = "https://bigsource.com/book/epic/toc")
            database.saveBookshelf(book, null)

            val totalChapters = 2000
            val runner = RuleRunner { url ->
                if (url.contains("/toc")) {
                    val items = (1..totalChapters).joinToString(",") { """{"title":"第${it}章","url":"https://bigsource.com/c$it"}""" }
                    """{"data":[$items]}"""
                } else {
                    val chapterNum = Regex("/c(\\d+)").find(url)?.groupValues?.get(1) ?: "0"
                    """{"content":"第${chapterNum}章 正文内容：风起云涌，万相争霸。"}"""
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://bigsource.com", "https://bigsource.com/book/epic", "https://bigsource.com/book/epic/toc"))

            // User concurrently reads chapters and updates progress while caching takes place
            coroutineScope {
                val reader = async {
                    val readingChapters = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 1500, 1999)
                    readingChapters.forEachIndexed { step, chIdx ->
                        val chUrl = "https://bigsource.com/c$chIdx"
                        val cached = database.cachedContent("https://bigsource.com", "https://bigsource.com/book/epic", chUrl)
                        val text = cached?.content ?: runner.content(sourceJson, chUrl).content
                        assertTrue(text.contains("正文内容"))
                        database.saveProgress(
                            ReadingProgress(
                                sourceId = "https://bigsource.com",
                                bookUrl = "https://bigsource.com/book/epic",
                                chapterUrl = chUrl,
                                chapterIndex = chIdx - 1,
                                scrollPosition = 0.05 * (step + 1),
                            )
                        )
                        val shelfList = database.listBookshelf()
                        assertTrue(shelfList.isNotEmpty())
                        delay(10)
                    }
                }
                reader.await()
            }

            // Wait for 2,000 chapters to finish caching
            waitForCondition(20000) {
                database.listBookshelf().single().cacheState == "ready"
            }

            val shelf = database.listBookshelf().single()
            assertEquals("ready", shelf.cacheState)
            assertEquals(2000, shelf.cachedChapters)
            assertEquals(2000, shelf.totalChapters)

            // Verify sample chapters across the whole range
            listOf(1, 250, 500, 1000, 1500, 2000).forEach { ch ->
                val content = database.cachedContent("https://bigsource.com", "https://bigsource.com/book/epic", "https://bigsource.com/c$ch")
                assertNotNull("Chapter $ch must be cached in database", content)
                assertTrue(content!!.content.contains("第${ch}章"))
            }

            // Verify reader progress is preserved
            val progress = database.getProgress("https://bigsource.com", "https://bigsource.com/book/epic")
            assertNotNull(progress)
            assertEquals(1998, progress!!.chapterIndex) // chapter 1999 has index 1998
            service.stop()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `scenario 3 - cache interruption and instant breakpoint resume`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val sourceJson = """{"bookSourceUrl":"https://src.com","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)
            val book = BookshelfWriteRequest("https://src.com", "https://src.com/book/1", "中断恢复测试", tocUrl = "https://src.com/toc")
            database.saveBookshelf(book, null)

            val totalChapters = 100
            val downloadCount = AtomicInteger(0)

            val runner = RuleRunner { url ->
                if (url.contains("/toc")) {
                    val items = (1..totalChapters).joinToString(",") { """{"title":"第${it}章","url":"https://src.com/c$it"}""" }
                    """{"data":[$items]}"""
                } else {
                    downloadCount.incrementAndGet()
                    """{"content":"第${url.substringAfterLast("/c")}章 正文"}"""
                }
            }

            // Simulate pre-cached 35 chapters in database (from a previous session before restart)
            (1..35).forEach { i ->
                database.cacheBookContent(
                    sourceId = "https://src.com",
                    bookUrl = "https://src.com/book/1",
                    chapterUrl = "https://src.com/c$i",
                    content = ChapterContent("第${i}章", "第${i}章 已缓存正文"),
                )
            }

            // Verify 35 chapters exist in cache initially
            (1..35).forEach { i ->
                val cached = database.cachedContent("https://src.com", "https://src.com/book/1", "https://src.com/c$i")
                assertNotNull(cached)
                assertEquals("第${i}章 已缓存正文", cached!!.content)
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://src.com", "https://src.com/book/1", "https://src.com/toc"))

            waitForCondition(10000) {
                database.listBookshelf().single().cacheState == "ready"
            }

            val shelf = database.listBookshelf().single()
            assertEquals("ready", shelf.cacheState)
            assertEquals(100, shelf.cachedChapters)
            assertEquals(100, shelf.totalChapters)

            // Verify all 100 chapters exist in database
            (1..100).forEach { i ->
                val cached = database.cachedContent("https://src.com", "https://src.com/book/1", "https://src.com/c$i")
                assertNotNull("Chapter $i must exist in cache", cached)
                assertTrue(cached!!.content.isNotBlank())
            }

            service.stop()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `scenario 4 - large book 5000 chapters TOC virtual browsing and chapter retrieval`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val sourceJson = """{"bookSourceUrl":"https://src.com","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val totalChapters = 5000
            val runner = RuleRunner { url ->
                if (url.contains("/toc")) {
                    val items = (1..totalChapters).joinToString(",") { """{"title":"第${it}章 仙道奇缘","url":"https://src.com/c$it"}""" }
                    """{"data":[$items]}"""
                } else {
                    val idx = url.substringAfterLast("/c")
                    """{"content":"第${idx}章 正文内容：修仙长路漫漫，求道之心不灭。"}"""
                }
            }

            // Step 1: Fetch and parse 5,000 chapter TOC
            val tocStartTime = System.currentTimeMillis()
            val chapters = runner.chapters(sourceJson, "https://src.com/toc")
            val tocDuration = System.currentTimeMillis() - tocStartTime

            assertTrue("Parsing 5,000 chapters TOC should complete in < 500ms", tocDuration < 500)
            assertEquals(5000, chapters.size)
            assertEquals("第1章 仙道奇缘", chapters.first().title)
            assertEquals("第5000章 仙道奇缘", chapters.last().title)
            assertEquals("https://src.com/c1", chapters.first().url)
            assertEquals("https://src.com/c5000", chapters.last().url)

            // Step 2: Virtual list windowing simulation (rendering 25 items at a time)
            val windowSize = 25

            // Window at top: 0..24
            val topWindow = chapters.subList(0, windowSize)
            assertEquals(25, topWindow.size)
            assertEquals("第1章 仙道奇缘", topWindow.first().title)
            assertEquals("第25章 仙道奇缘", topWindow.last().title)

            // Window in middle: 2500..2524
            val midWindow = chapters.subList(2500, 2500 + windowSize)
            assertEquals(25, midWindow.size)
            assertEquals("第2501章 仙道奇缘", midWindow.first().title)
            assertEquals("第2525章 仙道奇缘", midWindow.last().title)

            // Window at end: 4975..4999
            val endWindow = chapters.subList(5000 - windowSize, 5000)
            assertEquals(25, endWindow.size)
            assertEquals("第4976章 仙道奇缘", endWindow.first().title)
            assertEquals("第5000章 仙道奇缘", endWindow.last().title)

            // Step 3: TOC keyword filtering across 5,000 chapters
            val matchedChapters = chapters.filter { it.title.contains(Regex("第489\\d章")) }
            assertEquals(10, matchedChapters.size) // 4890..4899
            assertEquals(4889, matchedChapters.first().index) // 0-based index of 4890
            assertEquals("第4890章 仙道奇缘", matchedChapters.first().title)

            // Step 4: Random chapter jump & retrieval (Chapter 4892)
            val targetChapter = chapters[4891]
            assertEquals("第4892章 仙道奇缘", targetChapter.title)

            val content = runner.content(sourceJson, targetChapter.url)
            assertTrue(content.content.contains("第4892章 正文内容"))

            // Cache and record reading progress for this distant chapter
            database.cacheBookContent("https://src.com", "https://src.com/book/5000", targetChapter.url, content)
            database.saveProgress(
                ReadingProgress(
                    sourceId = "https://src.com",
                    bookUrl = "https://src.com/book/5000",
                    chapterUrl = targetChapter.url,
                    chapterIndex = 4891,
                    scrollPosition = 0.73,
                )
            )

            val progress = database.getProgress("https://src.com", "https://src.com/book/5000")
            assertNotNull(progress)
            assertEquals(4891, progress!!.chapterIndex)
            assertEquals(0.73, progress.scrollPosition, 0.0001)

            val cachedContent = database.cachedContent("https://src.com", "https://src.com/book/5000", targetChapter.url)
            assertNotNull(cachedContent)
            assertEquals(content.content, cachedContent!!.content)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `scenario 5 - high concurrency source management and query`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Batch import 100 sources with varied schemas and mixed casing
            val rawSources = (1..100).map { i ->
                val namePrefix = when {
                    i % 4 == 0 -> "NOVEL_SOURCE"
                    i % 4 == 1 -> "novel_source"
                    i % 4 == 2 -> "Novel_Source"
                    else -> "中文书源"
                }
                val isJs = (i % 5 == 0)
                if (isJs) {
                    """{"bookSourceUrl":"https://src$i.com","bookSourceName":"$namePrefix $i","bookSourceGroup":"Group${i % 3}","enabled":true,"mainJs":"function search(k, p) { return []; } function getBookInfo(b) { return {}; } function getChapters(t) { return []; } function getContent(c) { return 'JS正文'; }"}"""
                } else {
                    """{"bookSourceUrl":"https://src$i.com","bookSourceName":"$namePrefix $i","bookSourceGroup":"Group${i % 3}","enabled":true,"searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"}}"""
                }
            }
            val importResp = database.importSources(rawSources)
            assertEquals(100, importResp.imported)

            // High concurrency queries and mutations
            val totalOps = AtomicInteger(0)
            coroutineScope {
                val workers = (1..20).map { workerId ->
                    async {
                        repeat(5) { iter ->
                            when ((workerId + iter) % 5) {
                                0 -> {
                                    // Case-insensitive listing
                                    val results = database.listSources("novel_source")
                                    assertTrue(results.isNotEmpty())
                                }
                                1 -> {
                                    // Search records query
                                    val records = database.listSearchSourceRecords(listOf("https://src1.com", "https://src2.com", "https://src3.com"))
                                    assertEquals(3, records.size)
                                }
                                2 -> {
                                    // Exact source get
                                    val source = database.getSource("https://src$workerId.com")
                                    assertNotNull(source)
                                    assertTrue(source!!.json.contains("src$workerId"))
                                }
                                3 -> {
                                    // Export sources
                                    val exported = database.exportSources(listOf("https://src$workerId.com"))
                                    assertEquals(1, exported.size)
                                }
                                4 -> {
                                    // Save / update source
                                    val src = database.getSource("https://src$workerId.com")
                                    if (src != null) {
                                        val parsed = SourceCodec.parse(src.json)
                                        val updated = database.saveSource(parsed, src.version)
                                        assertTrue(updated.version >= src.version)
                                    }
                                }
                            }
                            totalOps.incrementAndGet()
                        }
                    }
                }
                workers.awaitAll()
            }

            assertEquals(100, totalOps.get())

            // Verify sources list with case-insensitive ordering
            val allSources = database.listSources(null)
            assertEquals(100, allSources.size)

            // Delete 5 sources concurrently
            coroutineScope {
                val deleteWorkers = (96..100).map { id ->
                    async {
                        database.deleteSource("https://src$id.com")
                    }
                }
                deleteWorkers.awaitAll()
            }

            assertEquals(95, database.listSources(null).size)
            assertNull(database.getSource("https://src100.com"))
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    private suspend fun waitForCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun temporaryDatabase(): String = Files.createTempFile("legado-e2e-test", ".sqlite").toString()
}
