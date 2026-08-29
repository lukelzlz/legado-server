package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

class DatabaseTest {
    @Test
    fun `saves and reads chapter scroll position`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")

            database.saveProgress(ReadingProgress("source", "book", "chapter", 3, 0.42))

            val progress = database.getProgress("source", "book")!!
            assertEquals(3, progress.chapterIndex)
            assertEquals(0.42, progress.scrollPosition, 0.0001)
            database.close()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `migrates existing reading progress with a zero scroll position`() {
        val path = temporaryDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                database.createStatement().use { statement ->
                    statement.executeUpdate("create table reading_progress (source_id text not null, book_url text not null, chapter_url text not null, chapter_index integer not null, updated_at integer not null, primary key (source_id, book_url))")
                    statement.executeUpdate("insert into reading_progress values ('source', 'book', 'chapter', 2, 1)")
                }
            }

            val database = Database(path)
            database.initialize("password-for-test")

            assertEquals(0.0, database.getProgress("source", "book")!!.scrollPosition, 0.0)
            database.close()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `removing shelf item clears its progress and returns an orphaned cover`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("password-for-test")
            val book = BookshelfWriteRequest("source", "book", "书名", "作者", "toc", "cover")
            database.saveBookshelf(book, CachedCover("a".repeat(64), "image/jpeg"))
            database.saveProgress(ReadingProgress("source", "book", "chapter", 3, .42))
            assertEquals(1, database.listBookshelf().size)
            assertEquals("a".repeat(64), database.removeBookshelf("source", "book"))
            assertEquals(0, database.listBookshelf().size)
            assertEquals(null, database.getProgress("source", "book"))
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `alternate sources persist on save and update on switch`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("password-for-test")
            val alt1 = SearchResult("alt-source-1", "测试书", "测试作者", "https://alt1.com/book")
            val alt2 = SearchResult("alt-source-2", "测试书", "测试作者", "https://alt2.com/book")
            val book = BookshelfWriteRequest("main-source", "https://main.com/book", "测试书", "测试作者", "toc", alternateSources = listOf(alt1, alt2))
            database.saveBookshelf(book, null)

            val shelf = database.listBookshelf().single()
            assertEquals(2, shelf.alternateSources.size)
            assertEquals("alt-source-1", shelf.alternateSources[0].sourceId)
            assertEquals("alt-source-2", shelf.alternateSources[1].sourceId)

            // Switch source to alt1
            val switched = database.switchBookshelf("main-source", "https://main.com/book", BookshelfWriteRequest("alt-source-1", "https://alt1.com/book", "测试书", "测试作者", "toc"), null).first
            assertEquals("alt-source-1", switched.sourceId)
            val altSourceIds = switched.alternateSources.map { it.sourceId }
            assertTrue(altSourceIds.contains("main-source"))
            assertTrue(altSourceIds.contains("alt-source-2"))
            org.junit.Assert.assertFalse(altSourceIds.contains("alt-source-1"))
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `shelf completion state persists and old shelves migrate as unread`() {
        val path = temporaryDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                database.createStatement().use { statement ->
                    statement.executeUpdate("create table book_shelf (source_id text not null, book_url text not null, name text not null, author text, toc_url text not null, cover_url text, cover_key text, last_read_at integer not null, primary key (source_id, book_url))")
                    statement.executeUpdate("insert into book_shelf values ('old', 'book', '旧书', null, 'toc', null, null, 1)")
                }
            }
            val database = Database(path); database.initialize("password-for-test")
            assertEquals(false, database.listBookshelf().single().completed)

            database.saveBookshelf(BookshelfWriteRequest("source", "book", "新书", tocUrl = "toc"), null)
            assertEquals(true, database.setBookshelfCompleted("source", "book", true)!!.completed)
            assertEquals(true, database.listBookshelf().first { it.sourceId == "source" }.completed)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `batch import deduplicates source URLs and overwrites existing sources`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("password-for-test")
            val first = """{"bookSourceUrl":"https://source.example","bookSourceName":"初版"}"""
            val replacement = """{"bookSourceUrl":"https://source.example","bookSourceName":"更新版"}"""

            val initial = database.importSources(listOf(first, replacement))
            assertEquals(1, initial.imported)
            assertEquals(1, initial.skipped)
            assertEquals("更新版", database.listSources(null).single().name)

            val update = database.importSources(listOf(first))
            assertEquals(0, update.imported)
            assertEquals(1, update.updated)
            assertEquals("初版", database.listSources(null).single().name)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `search source records load enabled sources in one filtered query`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("password-for-test")
            database.importSources(listOf(
                """{"bookSourceUrl":"https://enabled.example","bookSourceName":"可用"}""",
                """{"bookSourceUrl":"https://disabled.example","bookSourceName":"停用","enabled":false}""",
            ))

            assertEquals(listOf("https://enabled.example"), database.listSearchSourceRecords(null).map { it.id })
            assertEquals(listOf("https://enabled.example"), database.listSearchSourceRecords(listOf("https://enabled.example")).map { it.id })
            assertEquals(0, database.listSearchSourceRecords(listOf("https://disabled.example")).size)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `subscription records preserve failure state and can be removed`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("password-for-test")
            val subscription = database.saveSubscription(SubscriptionWriteRequest("https://example.com/sources.json"))
            database.recordSubscriptionFailure(subscription.id, "网络超时")
            assertEquals("网络超时", database.listSubscriptions().single().lastError)
            assertEquals(true, database.deleteSubscription(subscription.id))
            assertEquals(0, database.listSubscriptions().size)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `initialization enables WAL mode`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")
            DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
                val mode = conn.createStatement().use { statement -> statement.executeQuery("pragma journal_mode").use { result -> result.next(); result.getString(1) } }
                assertEquals("wal", mode.lowercase())
            }
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `book content cache is served and removed with its shelf item`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("password-for-test")
            val book = BookshelfWriteRequest("source", "book", "书名", "作者", "toc")
            database.saveBookshelf(book, null)
            database.beginBookCache("source", "book", 1)
            val cacheResult: Unit = database.cacheBookContent("source", "book", "chapter", ChapterContent("第一章", "已缓存正文"))
            assertEquals(Unit, cacheResult)
            database.finishBookCache("source", "book")

            assertEquals("已缓存正文", database.cachedContent("source", "book", "chapter")!!.content)
            assertEquals("ready", database.listBookshelf().single().cacheState)
            assertEquals(1, database.listBookshelf().single().cachedChapters)
            database.removeBookshelf("source", "book")
            assertEquals(null, database.cachedContent("source", "book", "chapter"))
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `switching a shelf source clears old cached data`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path); database.initialize("password-for-test")
            database.saveBookshelf(BookshelfWriteRequest("old", "old-book", "书名", "作者", "old-toc"), null)
            database.saveProgress(ReadingProgress("old", "old-book", "chapter", 2))
            database.cacheBookContent("old", "old-book", "chapter", ChapterContent(content = "旧缓存"))

            val result = database.switchBookshelf("old", "old-book", BookshelfWriteRequest("new", "new-book", "书名", "作者", "new-toc"), null).first

            assertEquals("new", result.sourceId)
            assertEquals("new-book", result.bookUrl)
            assertEquals(null, database.getProgress("old", "old-book"))
            assertEquals(null, database.cachedContent("old", "old-book", "chapter"))
            assertEquals(1, database.listBookshelf().size)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `listSources projects summary fields without loading large payload`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")

            val largePayload = "{\"bookSourceUrl\":\"https://source.large\",\"bookSourceName\":\"大型书源\",\"bookSourceGroup\":\"精品\",\"customData\":\"" + "X".repeat(20000) + "\"}"
            database.importSources(listOf(largePayload))

            val summaries = database.listSources(null)
            assertEquals(1, summaries.size)
            val summary = summaries.first()
            assertEquals("https://source.large", summary.id)
            assertEquals("大型书源", summary.name)
            assertEquals("https://source.large", summary.url)
            assertEquals("精品", summary.group)
            assertEquals(true, summary.enabled)
            assertEquals(false, summary.isJsSource)
            assertTrue(summary.updatedAt > 0)
            assertEquals(1L, summary.version)

            val filtered = database.listSources("大型")
            assertEquals(1, filtered.size)
            assertEquals("大型书源", filtered.first().name)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `idx_source_name_nocase index exists and supports case-insensitive ordering`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")

            database.importSources(listOf(
                "{\"bookSourceUrl\":\"https://b.com\",\"bookSourceName\":\"b_book\"}",
                "{\"bookSourceUrl\":\"https://a.com\",\"bookSourceName\":\"A_book\"}",
                "{\"bookSourceUrl\":\"https://c.com\",\"bookSourceName\":\"c_book\"}",
            ))

            val names = database.listSources(null).map { it.name }
            assertEquals(listOf("A_book", "b_book", "c_book"), names)

            DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
                val indexExists = conn.createStatement().use { st ->
                    st.executeQuery("select 1 from sqlite_master where type='index' and name='idx_source_name_nocase'").use { rs ->
                        rs.next()
                    }
                }
                assertTrue("Index idx_source_name_nocase must exist in schema", indexExists)
            }
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `cachedChapterUrls returns complete set of cached chapter URLs`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")

            database.cacheBookContent("src-1", "book-1", "https://src.com/c1", ChapterContent("第1章", "内容1"))
            database.cacheBookContent("src-1", "book-1", "https://src.com/c2", ChapterContent("第2章", "内容2"))
            database.cacheBookContent("src-1", "book-1", "https://src.com/c3", ChapterContent("第3章", "内容3"))
            database.cacheBookContent("src-1", "book-2", "https://src.com/other-c1", ChapterContent("第1章", "内容A"))

            val cached = database.cachedChapterUrls("src-1", "book-1")
            assertEquals(setOf("https://src.com/c1", "https://src.com/c2", "https://src.com/c3"), cached)

            val empty = database.cachedChapterUrls("src-1", "book-non-existent")
            assertEquals(emptySet<String>(), empty)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `updateBookCacheProgress updates cached count without modifying other state`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")

            val book = BookshelfWriteRequest("src-1", "book-1", "书名", "作者", "toc")
            database.saveBookshelf(book, null)
            database.beginBookCache("src-1", "book-1", 100)

            val initialShelf = database.listBookshelf().single()
            assertEquals("caching", initialShelf.cacheState)
            assertEquals(0, initialShelf.cachedChapters)
            assertEquals(100, initialShelf.totalChapters)

            database.updateBookCacheProgress("src-1", "book-1", 45)

            val updatedShelf = database.listBookshelf().single()
            assertEquals("caching", updatedShelf.cacheState)
            assertEquals(45, updatedShelf.cachedChapters)
            assertEquals(100, updatedShelf.totalChapters)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `finishBookCache preserves previously known chapter total`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")

            val book = BookshelfWriteRequest("src-1", "book-1", "书名", "作者", "toc")
            database.saveBookshelf(book, null)
            database.beginBookCache("src-1", "book-1", 144)
            database.cacheBookContent("src-1", "book-1", "c1", ChapterContent("第1章", "内容"))
            database.finishBookCache("src-1", "book-1", "1 章未缓存")

            val failed = database.listBookshelf().single()
            assertEquals("failed", failed.cacheState)
            assertEquals(1, failed.cachedChapters)
            // Total must not be clobbered by the partially cached count.
            assertEquals(144, failed.totalChapters)

            database.finishBookCache("src-1", "book-1")
            val ready = database.listBookshelf().single()
            assertEquals("ready", ready.cacheState)
            assertEquals(144, ready.totalChapters)
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `concurrent multi-threaded read and write connection pooling`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")

            database.importSources((1..20).map { "{\"bookSourceUrl\":\"https://src$it.com\",\"bookSourceName\":\"书源$it\"}" })
            database.saveBookshelf(BookshelfWriteRequest("https://src1.com", "https://book1.com", "并发测试", "作者", "toc"), null)

            val executor = Executors.newFixedThreadPool(16)
            try {
                val tasks = mutableListOf<Future<Boolean>>()
                repeat(100) { i ->
                    tasks.add(executor.submit(Callable {
                        if (i % 3 == 0) {
                            database.saveProgress(ReadingProgress("https://src1.com", "https://book1.com", "https://c/$i", i, 0.1 * (i % 10)))
                        } else if (i % 3 == 1) {
                            val list = database.listSources(null)
                            assertEquals(20, list.size)
                        } else {
                            val shelf = database.listBookshelf()
                            assertEquals(1, shelf.size)
                        }
                        true
                    }))
                }

                for (task in tasks) {
                    assertTrue(task.get())
                }
            } finally {
                executor.shutdown()
            }
            database.close()
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `database close releases write connection and pooled read connections`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")
            database.listSources(null)
            database.saveBookshelf(BookshelfWriteRequest("s", "b", "n", "a", "t"), null)

            database.close()

            val error = runCatching { database.listSources(null) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message?.contains("closed") == true)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `updateBookshelfInfo updates name author and cover`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")
            database.saveBookshelf(BookshelfWriteRequest("src-1", "https://b1", "旧书名", "旧作者", "https://b1/toc"), CachedCover("cover-old", "image/jpeg"))

            val updated = database.updateBookshelfInfo(
                BookshelfInfoUpdateRequest("src-1", "https://b1", "新书名", "新作者", "https://example.com/new.jpg"),
                CachedCover("cover-new", "image/png")
            )

            assertNotNull(updated)
            assertEquals("新书名", updated!!.name)
            assertEquals("新作者", updated.author)
            assertEquals("cover-new", updated.coverKey)

            val shelfItem = database.listBookshelf().single()
            assertEquals("新书名", shelfItem.name)
            assertEquals("新作者", shelfItem.author)
            assertEquals("cover-new", shelfItem.coverKey)

            database.close()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `updateBookshelfInfo updates book with null initial cover`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("password-for-test")
            database.saveBookshelf(BookshelfWriteRequest("src-1", "https://b1", "未命名书籍", "会说话的肘子", "https://b1/toc"), null)

            val updated = database.updateBookshelfInfo(
                BookshelfInfoUpdateRequest("src-1", "https://b1", "我是大玩家", "会说话的肘子", "https://example.com/new.jpg"),
                CachedCover("cover-new", "image/png")
            )

            assertNotNull(updated)
            assertEquals("我是大玩家", updated!!.name)
            assertEquals("会说话的肘子", updated.author)
            assertEquals("cover-new", updated.coverKey)

            val shelfItem = database.listBookshelf().single()
            assertEquals("我是大玩家", shelfItem.name)
            assertEquals("cover-new", shelfItem.coverKey)

            database.close()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    private fun temporaryDatabase(): String = Files.createTempFile("legado-server-test", ".sqlite").toString()
}

