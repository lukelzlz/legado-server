package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager

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
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    @Test
    fun `initialization enables WAL mode`() {
        val path = temporaryDatabase()
        try {
            Database(path).initialize("password-for-test")
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                val mode = database.createStatement().use { statement -> statement.executeQuery("pragma journal_mode").use { result -> result.next(); result.getString(1) } }
                assertEquals("wal", mode.lowercase())
            }
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
            database.cacheBookContent("source", "book", "chapter", ChapterContent("第一章", "已缓存正文"))
            database.finishBookCache("source", "book")

            assertEquals("已缓存正文", database.cachedContent("source", "book", "chapter")!!.content)
            assertEquals("ready", database.listBookshelf().single().cacheState)
            assertEquals(1, database.listBookshelf().single().cachedChapters)
            database.removeBookshelf("source", "book")
            assertEquals(null, database.cachedContent("source", "book", "chapter"))
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
        } finally { Files.deleteIfExists(java.nio.file.Path.of(path)) }
    }

    private fun temporaryDatabase(): String = Files.createTempFile("legado-server-test", ".sqlite").toString()
}
