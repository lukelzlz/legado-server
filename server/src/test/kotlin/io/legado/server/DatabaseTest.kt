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

    private fun temporaryDatabase(): String = Files.createTempFile("legado-server-test", ".sqlite").toString()
}
