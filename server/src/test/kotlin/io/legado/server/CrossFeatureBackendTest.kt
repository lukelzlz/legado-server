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

class CrossFeatureBackendTest {

    @Test
    fun `concurrent search and background chapter caching execute without SQLite lock contention`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Seed 10 sources
            val sources = (1..10).map { i ->
                """{"bookSourceUrl":"https://src$i.com","bookSourceName":"源$i","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"},"ruleBookInfo":{"init":"$.data","name":"$.title","coverUrl":"$.cover","tocUrl":"$.toc"},"ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            }
            database.importSources(sources)

            // Seed bookshelf item for caching
            val book = BookshelfWriteRequest(
                sourceId = "https://src1.com",
                bookUrl = "https://src1.com/b/1",
                name = "正在缓存的书",
                tocUrl = "https://src1.com/b/1/toc",
            )
            database.saveBookshelf(book, null)

            val runner = RuleRunner { url ->
                when {
                    url.contains("/search") -> """{"data":[{"id":"1","title":"搜索结果"}]}"""
                    url.contains("/b/1/toc") -> {
                        val items = (1..20).joinToString(",") { """{"title":"第${it}章","url":"https://src1.com/b/1/c$it"}""" }
                        """{"data":[$items]}"""
                    }
                    url.contains("/b/1/c") -> """{"content":"正文内容"}"""
                    url.contains("/b/1") -> """{"data":{"id":"1","title":"搜索结果","cover":"https://src1.com/cover.jpg","toc":"https://src1.com/b/1/toc"}}"""
                    else -> """{"data":[]}"""
                }

            }

            val cacheService = BookCacheService(database, runner) {}
            cacheService.enqueue(CachedBookRequest("https://src1.com", "https://src1.com/b/1", "https://src1.com/b/1/toc"))

            // Concurrently perform 5 parallel search batches across 10 sources while reading shelf
            val searchSuccesses = AtomicInteger(0)
            coroutineScope {
                val searchJobs = (1..5).map {
                    async {
                        val records = database.listSearchSourceRecords(null)
                        val outcomes = boundedConcurrentMap(records, 16) { record ->
                            searchSourceOutcome(runner, record.json, "关键词")
                        }
                        val shelfList = database.listBookshelf()
                        assertTrue("Shelf should contain the active book", shelfList.isNotEmpty())
                        if (outcomes.isNotEmpty() && outcomes.all { it.results.isNotEmpty() }) {
                            searchSuccesses.incrementAndGet()
                        }
                    }
                }
                searchJobs.awaitAll()
            }

            assertEquals(5, searchSuccesses.get())

            // Wait for cache completion
            waitForCondition(10000) {
                database.listBookshelf().firstOrNull { it.bookUrl == "https://src1.com/b/1" }?.cacheState == "ready"
            }
            val shelf = database.listBookshelf().first { it.bookUrl == "https://src1.com/b/1" }
            assertEquals(20, shelf.cachedChapters)
            assertEquals("ready", shelf.cacheState)
            assertNotNull(database.cachedContent("https://src1.com", "https://src1.com/b/1", "https://src1.com/b/1/c1"))
            assertNotNull(database.cachedContent("https://src1.com", "https://src1.com/b/1", "https://src1.com/b/1/c20"))
            cacheService.stop()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `multi-source switching preserves reading progress across sources and purges old cache`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val oldBook = BookshelfWriteRequest("source-old", "https://old/book/1", "修真聊天群", tocUrl = "https://old/toc")
            database.saveBookshelf(oldBook, null)
            database.saveProgress(ReadingProgress("source-old", "https://old/book/1", "https://old/c5", chapterIndex = 4, scrollPosition = 0.42))
            database.cacheBookContent("source-old", "https://old/book/1", "https://old/c1", ChapterContent("第1章", "旧书正文1"))
            database.cacheBookContent("source-old", "https://old/book/1", "https://old/c2", ChapterContent("第2章", "旧书正文2"))

            val newBook = BookshelfWriteRequest("source-new", "https://new/book/1", "修真聊天群", tocUrl = "https://new/toc")
            val (switchedItem, _) = database.switchBookshelf("source-old", "https://old/book/1", newBook, null)

            assertEquals("source-new", switchedItem.sourceId)
            assertEquals("https://new/book/1", switchedItem.bookUrl)
            assertNull("Old progress must be cleared", database.getProgress("source-old", "https://old/book/1"))
            assertNull("Old content cache must be cleared", database.cachedContent("source-old", "https://old/book/1", "https://old/c1"))
            assertNull("Old content cache must be cleared", database.cachedContent("source-old", "https://old/book/1", "https://old/c2"))

            // Reader continues on new source and persists progress
            val newProgress = database.saveProgress(ReadingProgress("source-new", "https://new/book/1", "https://new/c5", chapterIndex = 4, scrollPosition = 0.42))
            val loadedProgress = database.getProgress("source-new", "https://new/book/1")
            assertNotNull(loadedProgress)
            assertEquals(4, loadedProgress!!.chapterIndex)
            assertEquals(0.42, loadedProgress.scrollPosition, 0.0001)

            // Cache new source content
            database.cacheBookContent("source-new", "https://new/book/1", "https://new/c1", ChapterContent("第1章", "新书正文1"))
            assertEquals("新书正文1", database.cachedContent("source-new", "https://new/book/1", "https://new/c1")?.content)

            val shelfList = database.listBookshelf()
            assertEquals(1, shelfList.size)
            assertEquals("source-new", shelfList.first().sourceId)
            assertEquals(4, shelfList.first().chapterIndex)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `sequential multi-source switching across three sources maintains bookshelf integrity`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Initial Source A
            database.saveBookshelf(BookshelfWriteRequest("src-a", "https://a.com/b1", "大奉打更人", tocUrl = "https://a.com/toc"), null)
            database.saveProgress(ReadingProgress("src-a", "https://a.com/b1", "https://a.com/c2", chapterIndex = 2, scrollPosition = 0.1))
            database.cacheBookContent("src-a", "https://a.com/b1", "https://a.com/c1", ChapterContent("第1章", "A正文"))

            // Switch to Source B
            val (shelfB, _) = database.switchBookshelf("src-a", "https://a.com/b1", BookshelfWriteRequest("src-b", "https://b.com/b1", "大奉打更人", tocUrl = "https://b.com/toc"), null)
            assertEquals("src-b", shelfB.sourceId)
            database.saveProgress(ReadingProgress("src-b", "https://b.com/b1", "https://b.com/c15", chapterIndex = 15, scrollPosition = 0.5))
            database.cacheBookContent("src-b", "https://b.com/b1", "https://b.com/c15", ChapterContent("第15章", "B正文"))

            // Verify A is purged
            assertNull(database.getProgress("src-a", "https://a.com/b1"))
            assertNull(database.cachedContent("src-a", "https://a.com/b1", "https://a.com/c1"))

            // Switch to Source C
            val (shelfC, _) = database.switchBookshelf("src-b", "https://b.com/b1", BookshelfWriteRequest("src-c", "https://c.com/b1", "大奉打更人", tocUrl = "https://c.com/toc"), null)
            assertEquals("src-c", shelfC.sourceId)
            database.saveProgress(ReadingProgress("src-c", "https://c.com/b1", "https://c.com/c30", chapterIndex = 30, scrollPosition = 0.9))

            // Verify B is purged
            assertNull(database.getProgress("src-b", "https://b.com/b1"))
            assertNull(database.cachedContent("src-b", "https://b.com/b1", "https://b.com/c15"))

            // Verify C is active and consistent
            val progressC = database.getProgress("src-c", "https://c.com/b1")
            assertNotNull(progressC)
            assertEquals(30, progressC!!.chapterIndex)
            assertEquals(0.9, progressC.scrollPosition, 0.0001)

            val allShelf = database.listBookshelf()
            assertEquals(1, allShelf.size)
            assertEquals("src-c", allShelf.first().sourceId)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `high concurrency SQLite connection pool stress under mixed read write operations`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Seed sources
            val sources = (1..50).map { """{"bookSourceUrl":"https://src$it.com","bookSourceName":"书源$it"}""" }
            database.importSources(sources)

            val totalOps = AtomicInteger(0)
            coroutineScope {
                val workers = (1..50).map { id ->
                    async {
                        repeat(10) { iter ->
                            when (iter % 5) {
                                0 -> {
                                    val list = database.listSources(null)
                                    assertTrue(list.isNotEmpty())
                                }
                                1 -> {
                                    val searchList = database.listSearchSourceRecords(null)
                                    assertTrue(searchList.isNotEmpty())
                                }
                                2 -> {
                                    database.saveProgress(
                                        ReadingProgress(
                                            sourceId = "https://src$id.com",
                                            bookUrl = "https://book/$id",
                                            chapterUrl = "https://c/$iter",
                                            chapterIndex = iter,
                                            scrollPosition = 0.1 * iter,
                                        )
                                    )
                                    val loaded = database.getProgress("https://src$id.com", "https://book/$id")
                                    assertNotNull(loaded)
                                    assertEquals(iter, loaded!!.chapterIndex)
                                }
                                3 -> {
                                    database.cacheBookContent(
                                        sourceId = "https://src$id.com",
                                        bookUrl = "https://book/$id",
                                        chapterUrl = "https://c/$iter",
                                        content = ChapterContent("第${iter}章", "正文内容 $id $iter"),
                                    )
                                    val content = database.cachedContent("https://src$id.com", "https://book/$id", "https://c/$iter")
                                    assertNotNull(content)
                                    assertEquals("第${iter}章", content!!.title)
                                }
                                4 -> {
                                    val shelf = database.listBookshelf()
                                    assertNotNull(shelf)
                                }
                            }
                            totalOps.incrementAndGet()
                        }
                    }
                }
                workers.awaitAll()
            }

            assertEquals(500, totalOps.get())
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `source import and update during active concurrent search queries`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Seed initial 10 sources
            val initial = (1..10).map { """{"bookSourceUrl":"https://src$it.com","bookSourceName":"初版书源$it","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"}}""" }
            database.importSources(initial)

            val runner = RuleRunner { _ -> """{"data":[{"id":"1","title":"书"}]}""" }

            coroutineScope {
                // Background search workers
                val searchWorkers = (1..5).map {
                    async {
                        repeat(5) {
                            val records = database.listSearchSourceRecords(null)
                            assertTrue(records.isNotEmpty())
                            val outcomes = boundedConcurrentMap(records, 8) { record ->
                                searchSourceOutcome(runner, record.json, "测试")
                            }
                            assertTrue(outcomes.isNotEmpty())
                        }
                    }
                }

                // Concurrent source import worker
                val importWorker = async {
                    val newSources = (11..25).map { """{"bookSourceUrl":"https://src$it.com","bookSourceName":"导入书源$it","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"}}""" }
                    val resp = database.importSources(newSources)
                    assertEquals(15, resp.imported)
                }

                // Concurrent source update worker
                val updateWorker = async {
                    val updateSource = SourceCodec.parse("""{"bookSourceUrl":"https://src1.com","bookSourceName":"更新版书源1","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"}}""")
                    val record = database.saveSource(updateSource, null)
                    assertTrue(record.version >= 2)
                }

                searchWorkers.awaitAll()
                importWorker.await()
                updateWorker.await()
            }

            assertEquals(25, database.listSources(null).size)
            assertEquals("更新版书源1", database.getSource("https://src1.com")?.let { SourceCodec.parse(it.json).name })
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `cancelling active cache job during concurrent reading does not corrupt database`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val sourceJson = """{"bookSourceUrl":"https://src.com","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)
            val book = BookshelfWriteRequest("https://src.com", "https://src.com/book/1", "取消测试书", tocUrl = "https://src.com/toc")
            database.saveBookshelf(book, null)

            val runner = RuleRunner { url ->
                when {
                    url.contains("/toc") -> {
                        val list = (1..30).joinToString(",") { """{"title":"第${it}章","url":"https://src.com/c$it"}""" }
                        """{"data":[$list]}"""
                    }
                    else -> """{"content":"正文内容"}"""
                }
            }

            val cacheService = BookCacheService(database, runner) {}
            cacheService.enqueue(CachedBookRequest("https://src.com", "https://src.com/book/1", "https://src.com/toc"))

            // Reader concurrently reads and writes progress
            coroutineScope {
                val reader = async {
                    repeat(10) { idx ->
                        database.saveProgress(ReadingProgress("https://src.com", "https://src.com/book/1", "https://src.com/c$idx", chapterIndex = idx, scrollPosition = 0.2))
                        val progress = database.getProgress("https://src.com", "https://src.com/book/1")
                        assertNotNull(progress)
                    }
                }
                val canceller = async {
                    delay(50)
                    cacheService.cancel("https://src.com", "https://src.com/book/1")
                }
                reader.await()
                canceller.await()
            }

            // Database remains fully operational
            val shelfList = database.listBookshelf()
            assertEquals(1, shelfList.size)
            assertEquals("https://src.com/book/1", shelfList.first().bookUrl)
            cacheService.stop()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `removing book from shelf during active cache cleanly purges records without leaking locks`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val sourceJson = """{"bookSourceUrl":"https://src.com","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)
            val book = BookshelfWriteRequest("https://src.com", "https://src.com/book/del", "删除测试书", tocUrl = "https://src.com/toc")
            database.saveBookshelf(book, null)

            val runner = RuleRunner { url ->
                when {
                    url.contains("/toc") -> {
                        val list = (1..30).joinToString(",") { """{"title":"第${it}章","url":"https://src.com/c$it"}""" }
                        """{"data":[$list]}"""
                    }
                    else -> """{"content":"正文内容"}"""
                }
            }

            val cacheService = BookCacheService(database, runner) {}
            cacheService.enqueue(CachedBookRequest("https://src.com", "https://src.com/book/del", "https://src.com/toc"))

            // Remove bookshelf item while caching
            cacheService.cancel("https://src.com", "https://src.com/book/del")
            database.removeBookshelf("https://src.com", "https://src.com/book/del")

            assertEquals(0, database.listBookshelf().size)
            assertNull(database.getProgress("https://src.com", "https://src.com/book/del"))
            assertNull(database.cachedContent("https://src.com", "https://src.com/book/del", "https://src.com/c1"))

            // Ensure subsequent shelf saves on other books work immediately
            val anotherBook = BookshelfWriteRequest("https://src.com", "https://src.com/book/another", "另一本书", tocUrl = "https://src.com/toc2")
            val saved = database.saveBookshelf(anotherBook, null)
            assertEquals("另一本书", saved.name)
            assertEquals(1, database.listBookshelf().size)
            cacheService.stop()
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

    private fun temporaryDatabase(): String = Files.createTempFile("legado-cross-test", ".sqlite").toString()
}
