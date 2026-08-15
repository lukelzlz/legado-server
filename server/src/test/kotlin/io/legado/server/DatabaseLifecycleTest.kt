package io.legado.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

class DatabaseLifecycleTest {

    // --- Tier 1: Connection Lifecycle & Configuration Tests ---

    @Test
    fun `database initializes with WAL mode and creates required tables and indices`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-admin-password")

            DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
                // Check WAL mode
                val journalMode = conn.createStatement().use { stmt ->
                    stmt.executeQuery("pragma journal_mode").use { rs -> rs.next(); rs.getString(1) }
                }
                assertEquals("wal", journalMode.lowercase())

                // Check tables exist
                val tables = conn.createStatement().use { stmt ->
                    stmt.executeQuery("select name from sqlite_master where type='table'").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString(1)) }
                    }
                }
                val expectedTables = setOf(
                    "app_user", "session", "source", "reading_progress",
                    "cover_cache", "book_shelf", "book_content_cache",
                    "book_cache_status", "source_subscription"
                )
                for (table in expectedTables) {
                    assertTrue("Table $table must exist", tables.contains(table))
                }

                // Check indices exist
                val indices = conn.createStatement().use { stmt ->
                    stmt.executeQuery("select name from sqlite_master where type='index'").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString(1)) }
                    }
                }
                assertTrue("source_name_idx must exist", indices.contains("source_name_idx"))
                assertTrue("book_shelf_last_read_idx must exist", indices.contains("book_shelf_last_read_idx"))
                assertTrue("book_content_cache_book_idx must exist", indices.contains("book_content_cache_book_idx"))
                assertTrue("source_subscription_enabled_idx must exist", indices.contains("source_subscription_enabled_idx"))
            }
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `listSources projection query returns summaries without loading JSON payloads into memory`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Insert a source with a large payload (e.g. 50KB)
            val largeRule = "{\"heavyData\":\"" + "x".repeat(50_000) + "\"}"
            val sourceJson = """{"bookSourceUrl":"https://heavy.example","bookSourceName":"重载书源","bookSourceGroup":"精选","enabled":true,"ruleSearch":$largeRule}"""
            database.saveSource(SourceCodec.parse(sourceJson), null)

            val sources = database.listSources(null)
            assertEquals(1, sources.size)
            val summary = sources.single()
            assertEquals("https://heavy.example", summary.id)
            assertEquals("重载书源", summary.name)
            assertEquals("https://heavy.example", summary.url)
            assertEquals("精选", summary.group)
            assertTrue(summary.enabled)
            assertFalse(summary.isJsSource)
            assertEquals(1L, summary.version)
            assertTrue(summary.updatedAt > 0)

            // Verify getSource loads the full payload only when explicitly requested
            val record = database.getSource("https://heavy.example")
            assertNotNull(record)
            assertTrue(record!!.json.contains("heavyData"))
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `listSources filters sources by name and url with case-insensitive ordering`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            database.importSources(listOf(
                """{"bookSourceUrl":"https://alpha.example","bookSourceName":"Alpha源"}""",
                """{"bookSourceUrl":"https://beta.example","bookSourceName":"Beta源"}""",
                """{"bookSourceUrl":"https://charlie.example/book","bookSourceName":"Charlie源"}""",
            ))

            // Query by name
            val alphaResults = database.listSources("Alpha")
            assertEquals(1, alphaResults.size)
            assertEquals("Alpha源", alphaResults.single().name)

            // Query by url keyword
            val charlieResults = database.listSources("charlie")
            assertEquals(1, charlieResults.size)
            assertEquals("Charlie源", charlieResults.single().name)

            // Query matching nothing
            val emptyResults = database.listSources("NonExistent")
            assertEquals(0, emptyResults.size)

            // Null or blank query returns all
            val allResults = database.listSources(null)
            assertEquals(3, allResults.size)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `case-insensitive NOCASE sorting orders mixed-case source names predictably`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            database.importSources(listOf(
                """{"bookSourceUrl":"https://s1.example","bookSourceName":"banana"}""",
                """{"bookSourceUrl":"https://s2.example","bookSourceName":"Apple"}""",
                """{"bookSourceUrl":"https://s3.example","bookSourceName":"CHERRY"}""",
                """{"bookSourceUrl":"https://s4.example","bookSourceName":"date"}""",
            ))

            val sources = database.listSources(null)
            val names = sources.map { it.name }
            // NOCASE collation should sort: Apple, banana, CHERRY, date
            assertEquals(listOf("Apple", "banana", "CHERRY", "date"), names)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `session lifecycle manages creation CSRF lookup and expiration`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val now = System.currentTimeMillis()
            val session = database.createSession(now)
            assertNotNull(session.id)
            assertTrue(session.id.isNotEmpty())

            // Valid CSRF token retrieval
            val csrf = database.csrfFor(session, now)
            assertNotNull(csrf)
            assertTrue(csrf!!.isNotEmpty())

            // Expired session check
            val futureTime = now + (31L * 24 * 60 * 60 * 1000) // 31 days later
            val expiredCsrf = database.csrfFor(session, futureTime)
            assertNull("Expired session must return null CSRF token", expiredCsrf)

            // Delete session
            database.deleteSession(session)
            assertNull("Deleted session must return null CSRF token", database.csrfFor(session, now))
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `admin password verification and reset cycle clears existing sessions`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("initial-admin-password")

            assertTrue(database.verifyPassword("initial-admin-password"))
            assertFalse(database.verifyPassword("wrong-password"))

            val session = database.createSession()
            assertNotNull(database.csrfFor(session))

            // Reset password
            database.resetPassword("new-secure-password")

            assertTrue(database.verifyPassword("new-secure-password"))
            assertFalse(database.verifyPassword("initial-admin-password"))
            // Verify sessions were wiped
            assertNull(database.csrfFor(session))
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    // --- Tier 2: Concurrency, Contention & Migration Tests ---

    @Test
    fun `concurrent multi-threaded reads and writes execute without lock contention`() = runBlocking {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            // Seed initial data
            val initialSources = (1..20).map { i ->
                """{"bookSourceUrl":"https://src$i.example","bookSourceName":"书源$i"}"""
            }
            database.importSources(initialSources)

            val totalReadOps = AtomicInteger(0)
            val totalWriteOps = AtomicInteger(0)

            coroutineScope {
                // 10 concurrent readers
                val readers = (1..10).map {
                    async {
                        repeat(15) {
                            val list = database.listSources(null)
                            assertTrue(list.isNotEmpty())
                            totalReadOps.incrementAndGet()
                        }
                    }
                }
                // 5 concurrent writers
                val writers = (1..5).map { writerId ->
                    async {
                        repeat(10) { iter ->
                            database.saveProgress(ReadingProgress(
                                sourceId = "https://src$writerId.example",
                                bookUrl = "https://book/$writerId",
                                chapterUrl = "https://c/$iter",
                                chapterIndex = iter,
                                scrollPosition = 0.1 * iter
                            ))
                            totalWriteOps.incrementAndGet()
                        }
                    }
                }

                readers.awaitAll()
                writers.awaitAll()
            }

            assertEquals(150, totalReadOps.get())
            assertEquals(50, totalWriteOps.get())
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `subscription lifecycle tracks successes failures and deletions`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val sub = database.saveSubscription(SubscriptionWriteRequest("https://example.com/subs.json", enabled = true))
            assertEquals("https://example.com/subs.json", sub.url)
            assertTrue(sub.enabled)

            // Record success
            val importResp = ImportResponse(imported = 5, updated = 2, skipped = 0, errors = emptyList())
            database.recordSubscriptionSuccess(sub.id, importResp, "hash123")

            val updatedSub = database.getSubscription(sub.id)!!
            assertEquals("hash123", updatedSub.contentHash)
            assertEquals(7, updatedSub.lastImported)
            assertNotNull(updatedSub.lastSuccessAt)
            assertNull(updatedSub.lastError)

            // Record failure
            database.recordSubscriptionFailure(sub.id, "502 Bad Gateway")
            val failedSub = database.getSubscription(sub.id)!!
            assertEquals("502 Bad Gateway", failedSub.lastError)
            assertNotNull(failedSub.lastAttemptAt)

            // Delete subscription
            assertTrue(database.deleteSubscription(sub.id))
            assertNull(database.getSubscription(sub.id))
            assertEquals(0, database.listSubscriptions().size)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `bookshelf switch cleans orphan covers and cache entries`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val book1 = BookshelfWriteRequest("source-1", "https://book/1", "书名1", "作者1", "https://book/1/toc")
            database.saveBookshelf(book1, CachedCover("cover-key-1", "image/jpeg"))
            database.saveProgress(ReadingProgress("source-1", "https://book/1", "https://book/1/c1", 1, 0.5))
            database.cacheBookContent("source-1", "https://book/1", "https://book/1/c1", ChapterContent("第1章", "内容1"))

            // Switch to source-2
            val book2 = BookshelfWriteRequest("source-2", "https://book/2", "书名1", "作者1", "https://book/2/toc")
            val (newItem, orphanCover) = database.switchBookshelf("source-1", "https://book/1", book2, CachedCover("cover-key-2", "image/png"))

            assertEquals("source-2", newItem.sourceId)
            assertEquals("https://book/2", newItem.bookUrl)
            assertEquals("cover-key-1", orphanCover) // Old cover key orphaned

            // Old progress and content cache should be cleared
            assertNull(database.getProgress("source-1", "https://book/1"))
            assertNull(database.cachedContent("source-1", "https://book/1", "https://book/1/c1"))
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `saveSource prevents version conflicts and increments version`() {
        val path = temporaryDatabase()
        try {
            val database = Database(path)
            database.initialize("test-pass")

            val sourceJson1 = """{"bookSourceUrl":"https://src.example","bookSourceName":"版本1"}"""
            val record1 = database.saveSource(SourceCodec.parse(sourceJson1), null)
            assertEquals(1L, record1.version)

            val sourceJson2 = """{"bookSourceUrl":"https://src.example","bookSourceName":"版本2"}"""
            val record2 = database.saveSource(SourceCodec.parse(sourceJson2), 1L)
            assertEquals(2L, record2.version)

            // Save with outdated expected version throws VersionConflict
            val sourceJson3 = """{"bookSourceUrl":"https://src.example","bookSourceName":"版本3"}"""
            val conflict = runCatching {
                database.saveSource(SourceCodec.parse(sourceJson3), 1L)
            }.exceptionOrNull()

            assertTrue("Should throw VersionConflict on stale version", conflict is VersionConflict)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    private fun temporaryDatabase(): String = Files.createTempFile("legado-db-lifecycle-test", ".sqlite").toString()
}
