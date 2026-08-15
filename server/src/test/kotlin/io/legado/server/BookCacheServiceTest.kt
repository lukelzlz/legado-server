package io.legado.server

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

class BookCacheServiceTest {

    // --- Tier 1: Core Feature Tests ---

    @Test
    fun `caches all chapters and sets shelf cache state to ready`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "测试书", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val responses = ConcurrentHashMap<String, String>()
            responses["https://book/1/toc"] = """{"data":[{"title":"第1章","url":"https://book/1/c1"},{"title":"第2章","url":"https://book/1/c2"},{"title":"第3章","url":"https://book/1/c3"}]}"""
            responses["https://book/1/c1"] = """{"content":"第1章正文"}"""
            responses["https://book/1/c2"] = """{"content":"第2章正文"}"""
            responses["https://book/1/c3"] = """{"content":"第3章正文"}"""

            val runner = RuleRunner { url -> responses[url] ?: error("Unexpected URL: $url") }
            val service = BookCacheService(database, runner) {}

            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))
            waitForCondition(5000) { database.listBookshelf().single().cacheState == "ready" }

            val shelf = database.listBookshelf().single()
            assertEquals("ready", shelf.cacheState)
            assertEquals(3, shelf.cachedChapters)
            assertEquals(3, shelf.totalChapters)
            assertNull(shelf.cacheError)
            assertEquals("第1章正文", database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c1")?.content)
            assertEquals("第2章正文", database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c2")?.content)
            assertEquals("第3章正文", database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c3")?.content)
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `breakpoint resume preserves existing cached chapters and completes remaining chapters`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "断点测试", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            // Pre-cache chapter 1 and 2
            database.cacheBookContent("https://s1.test", "https://book/1", "https://book/1/c1", ChapterContent("第1章", "预缓存正文1"))
            database.cacheBookContent("https://s1.test", "https://book/1", "https://book/1/c2", ChapterContent("第2章", "预缓存正文2"))

            val runner = RuleRunner { url ->
                when (url) {
                    "https://book/1/toc" -> """{"data":[{"title":"第1章","url":"https://book/1/c1"},{"title":"第2章","url":"https://book/1/c2"},{"title":"第3章","url":"https://book/1/c3"}]}"""
                    "https://book/1/c1" -> """{"content":"新正文1"}"""
                    "https://book/1/c2" -> """{"content":"新正文2"}"""
                    "https://book/1/c3" -> """{"content":"第3章新正文"}"""
                    else -> error("Unexpected: $url")
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))
            waitForCondition(5000) { database.listBookshelf().single().cacheState == "ready" }

            val shelf = database.listBookshelf().single()
            assertEquals(3, shelf.cachedChapters)
            assertEquals("ready", shelf.cacheState)
            assertNotNull(database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c3"))
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `book with 100 percent pre-cached chapters immediately transitions to ready`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "已全缓", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            database.cacheBookContent("https://s1.test", "https://book/1", "https://book/1/c1", ChapterContent("第1章", "正文1"))
            database.cacheBookContent("https://s1.test", "https://book/1", "https://book/1/c2", ChapterContent("第2章", "正文2"))

            val runner = RuleRunner { url ->
                if (url == "https://book/1/toc") {
                    """{"data":[{"title":"第1章","url":"https://book/1/c1"},{"title":"第2章","url":"https://book/1/c2"}]}"""
                } else {
                    """{"content":"正文"}"""
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))
            waitForCondition(5000) { database.listBookshelf().single().cacheState == "ready" }

            val shelf = database.listBookshelf().single()
            assertEquals(2, shelf.cachedChapters)
            assertEquals(2, shelf.totalChapters)
            assertEquals("ready", shelf.cacheState)
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `start method automatically enqueues all shelf cache requests`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book1 = BookshelfWriteRequest("https://s1.test", "https://book/1", "书1", tocUrl = "https://book/1/toc")
            val book2 = BookshelfWriteRequest("https://s1.test", "https://book/2", "书2", tocUrl = "https://book/2/toc")
            database.saveBookshelf(book1, null)
            database.saveBookshelf(book2, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val runner = RuleRunner { url ->
                when {
                    url.endsWith("/toc") -> """{"data":[{"title":"第1章","url":"$url/c1"}]}"""
                    else -> """{"content":"自动缓存正文"}"""
                }
            }

            val service = BookCacheService(database, runner) {}
            service.start()
            waitForCondition(5000) {
                val list = database.listBookshelf()
                list.size == 2 && list.all { it.cacheState == "ready" }
            }

            val shelves = database.listBookshelf()
            assertEquals(2, shelves.size)
            assertTrue(shelves.all { it.cacheState == "ready" && it.cachedChapters == 1 })
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    // --- Tier 2: Boundary, Fault Tolerance & Cancellation Tests ---

    @Test
    fun `handles partial chapter download failures without aborting the batch`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "容错测试", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val runner = RuleRunner { url ->
                when (url) {
                    "https://book/1/toc" -> """{"data":[{"title":"第1章","url":"https://book/1/c1"},{"title":"第2章","url":"https://book/1/c2"},{"title":"第3章","url":"https://book/1/c3"}]}"""
                    "https://book/1/c1" -> """{"content":"正文1"}"""
                    "https://book/1/c2" -> throw RuntimeException("HTTP 500 error")
                    "https://book/1/c3" -> """{"content":"正文3"}"""
                    else -> error("Unexpected: $url")
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))
            waitForCondition(5000) { database.listBookshelf().single().cacheState == "failed" }

            val shelf = database.listBookshelf().single()
            assertEquals("failed", shelf.cacheState)
            assertEquals(2, shelf.cachedChapters)
            assertEquals(3, shelf.totalChapters)
            assertEquals("1 章未缓存", shelf.cacheError)
            assertNotNull(database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c1"))
            assertNotNull(database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c3"))
            assertNull(database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c2"))
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `rejects oversized chapters exceeding 2 MiB limit`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "超大章节测试", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val runner = RuleRunner { url ->
                when (url) {
                    "https://book/1/toc" -> """{"data":[{"title":"正常章","url":"https://book/1/c1"},{"title":"超大章","url":"https://book/1/c2"}]}"""
                    "https://book/1/c1" -> """{"content":"正常正文"}"""
                    "https://book/1/c2" -> """{"content":"${"x".repeat(3 * 1024 * 1024)}"}""" // 3 MiB content
                    else -> error("Unexpected: $url")
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))
            waitForCondition(5000) { database.listBookshelf().single().cacheState == "failed" }

            val shelf = database.listBookshelf().single()
            assertEquals("failed", shelf.cacheState)
            assertEquals(1, shelf.cachedChapters)
            assertEquals(2, shelf.totalChapters)
            assertEquals("1 章未缓存", shelf.cacheError)
            assertNotNull(database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c1"))
            assertNull(database.cachedContent("https://s1.test", "https://book/1", "https://book/1/c2"))
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `cancels active cache job cleanly`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "取消测试", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val runner = RuleRunner { url ->
                if (url == "https://book/1/toc") {
                    val list = (1..50).joinToString(",") { """{"title":"第${it}章","url":"https://book/1/c$it"}""" }
                    """{"data":[$list]}"""
                } else {
                    Thread.sleep(40)
                    """{"content":"正文"}"""
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))

            delay(100)
            service.cancel("https://s1.test", "https://book/1")

            val shelf = database.listBookshelf().single()
            assertTrue("Should stop before all 50 chapters download", shelf.cachedChapters < 50)
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `handles non-existent source gracefully with failed status`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("missing-source", "https://book/1", "无源书", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val runner = RuleRunner()
            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("missing-source", "https://book/1", "https://book/1/toc"))

            waitForCondition(5000) { database.listBookshelf().single().cacheState == "failed" }

            val shelf = database.listBookshelf().single()
            assertEquals("failed", shelf.cacheState)
            assertTrue(shelf.cacheError?.contains("书源不存在") == true)
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `deduplicates enqueue calls for already caching books`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "去重测试", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val fetchCount = AtomicInteger(0)
            val runner = RuleRunner { url ->
                fetchCount.incrementAndGet()
                if (url == "https://book/1/toc") {
                    """{"data":[{"title":"第1章","url":"https://book/1/c1"}]}"""
                } else {
                    Thread.sleep(50)
                    """{"content":"正文"}"""
                }
            }

            val service = BookCacheService(database, runner) {}
            // Enqueue twice rapidly
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))

            waitForCondition(5000) { database.listBookshelf().single().cacheState == "ready" }

            val shelf = database.listBookshelf().single()
            assertEquals(1, shelf.cachedChapters)
            assertEquals("ready", shelf.cacheState)
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `bounded chapter downloads run concurrently within the configured 4 to 8 worker window`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "并发窗口测试", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val runner = RuleRunner { url ->
                if (url == "https://book/1/toc") {
                    val chapters = (1..40).joinToString(",") { """{"title":"第${it}章","url":"https://book/1/c$it"}""" }
                    """{"data":[$chapters]}"""
                } else {
                    val current = active.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, current) }
                    try {
                        Thread.sleep(80)
                        """{"content":"并发正文"}"""
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))
            waitForCondition(10_000) { database.listBookshelf().single().cacheState == "ready" }

            val shelf = database.listBookshelf().single()
            assertEquals(40, shelf.cachedChapters)
            assertEquals("ready", shelf.cacheState)
            assertTrue("Expected 4-8 concurrent chapter downloads, observed ${maxActive.get()}", maxActive.get() in 4..8)
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `reports intermediate throttled cache progress while batch is still caching`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("test-pass")
            val book = BookshelfWriteRequest("https://s1.test", "https://book/1", "进度测试", tocUrl = "https://book/1/toc")
            database.saveBookshelf(book, null)

            val sourceJson = """{"bookSourceUrl":"https://s1.test","ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},"ruleContent":{"content":"$.content"}}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val runner = RuleRunner { url ->
                if (url == "https://book/1/toc") {
                    val chapters = (1..60).joinToString(",") { """{"title":"第${it}章","url":"https://book/1/c$it"}""" }
                    """{"data":[$chapters]}"""
                } else {
                    Thread.sleep(100)
                    """{"content":"进度正文"}"""
                }
            }

            val service = BookCacheService(database, runner) {}
            service.enqueue(CachedBookRequest("https://s1.test", "https://book/1", "https://book/1/toc"))

            val intermediateSeen = waitForCondition(5_000) {
                val shelf = database.listBookshelf().single()
                shelf.cacheState == "caching" && shelf.cachedChapters in 1..59
            }

            assertTrue("Cache progress should be written before the batch completes", intermediateSeen)
            waitForCondition(10_000) { database.listBookshelf().single().cacheState == "ready" }
            assertEquals(60, database.listBookshelf().single().cachedChapters)
            service.stop()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }


    private suspend fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(20)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun temporaryDatabase(): String = Files.createTempFile("legado-book-cache-test", ".sqlite").toString()
}
