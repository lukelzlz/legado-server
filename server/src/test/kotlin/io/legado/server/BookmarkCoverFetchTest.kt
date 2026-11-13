package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 「**只在书签里的书**也能补到封面」——审查指出的那个死路径的回归用例。
 *
 * 旧实现：搜到候选书目后走 `CoverBackfiller` → `materializeCover` → `updateBookshelfCover`，
 * 而书不在书架上时 `updateBookshelfCover` 恒 false ⇒ 结果恒 null ⇒ 每次都记 miss、
 * 满 5 次被标成"无封面"，下载下来的图还留在磁盘上没人引用。
 *
 * 现在：非书架书直接 `CoverCache.cache` + `recordCoverCache` + `saveBookCover`。
 * 本用例用 [BookmarkCoverService] 的 `searchBooks` / `CoverCache.fetcher` 两个接缝
 * **不触网**地验证整条链路能落库、能在概览里看到、能取到字节。
 */
class BookmarkCoverFetchTest {

    private lateinit var tempDir: Path
    private lateinit var database: Database
    private lateinit var coverCache: CoverCache

    /** 一张 1x1 PNG（魔数正确，CoverCache 的解压/落盘逻辑能过）。 */
    private val pngBytes = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==",
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("legado-bookmark-cover-fetch")
        database = Database(tempDir.resolve("test.sqlite").toString())
        database.initialize("test-pass")
        coverCache = CoverCache(tempDir.resolve("covers"), database) { _ ->
            "image/png" to pngBytes
        }
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    @Test
    fun `非书架书按书名搜到候选后能真正落盘并出现在概览里`() = runBlocking {
        database.saveBookmark(
            BookmarkSaveRequest(
                bookName = "只在书签里的书",
                bookAuthor = "作者甲",
                chapterIndex = 0,
                chapterName = "第 1 章",
                chapterPos = 10,
                bookText = "原文",
                content = "",
            ),
        )
        val service = BookmarkCoverService(
            database = database,
            coverCache = coverCache,
            runner = null,
            searchBooks = { keyword ->
                assertEquals("只在书签里的书 作者甲", keyword)
                listOf(
                    SearchResult(
                        sourceId = "src-x",
                        name = "只在书签里的书",
                        author = "作者甲",
                        bookUrl = "https://example.com/book/1",
                        coverUrl = "https://img.example.com/cover.png",
                    ),
                )
            },
        )

        val outcome = service.resolve("只在书签里的书", "作者甲")
        assertTrue("非书架书必须能补到封面（旧实现在这里恒失败）", outcome is BookmarkCoverService.Outcome.Fetched)
        val key = (outcome as BookmarkCoverService.Outcome.Fetched).coverKey
        assertTrue("封面文件必须真落盘", coverCache.file(key) != null)

        val book = database.listBookmarkBooks().single()
        assertEquals("概览里必须能看到这张封面", key, book.coverKey)
        assertEquals(0, book.coverAttempts)

        // 再抓一次应短路成功（已有封面 ⇒ 不该再加失败计数）
        val again = service.resolve("只在书签里的书", "作者甲")
        assertTrue(again is BookmarkCoverService.Outcome.Fetched)
    }

    @Test
    fun `搜不到候选时累计失败次数，满五次后不再自动重试`() = runBlocking {
        val service = BookmarkCoverService(
            database = database,
            coverCache = coverCache,
            runner = null,
            searchBooks = { emptyList() },
        )
        repeat(5) {
            assertTrue(service.resolve("抓不到的书", "作者乙") is BookmarkCoverService.Outcome.Failed)
        }
        assertNotNull(database.getBookCover("抓不到的书", "作者乙"))
        assertEquals(5, database.getBookCover("抓不到的书", "作者乙")!!.missingAttempts)
        // 满 5 次后：非 force 走 Skipped，force 才继续尝试
        assertTrue(service.resolve("抓不到的书", "作者乙") is BookmarkCoverService.Outcome.Skipped)
        assertTrue(service.resolve("抓不到的书", "作者乙", force = true) is BookmarkCoverService.Outcome.Failed)
    }

    @Test
    fun `换封面时旧图会被回收（没人引用后删文件）`() = runBlocking {
        val first = coverCache.cache("https://img.example.com/a.png")
        val second = coverCache.cache("https://img.example.com/b.png")
        database.saveBookmark(
            BookmarkSaveRequest(
                bookName = "换封面的书",
                bookAuthor = null,
                chapterIndex = 0,
                chapterName = "第 1 章",
                chapterPos = 0,
                bookText = "原文",
                content = "",
            ),
        )
        val service = BookmarkCoverService(database, coverCache, null)
        service.attach("换封面的书", null, first.key)
        assertNotNull(coverCache.file(first.key))

        service.attach("换封面的书", null, second.key)
        assertEquals("新封面必须登记上", second.key, database.getBookCover("换封面的书", null)?.coverKey)
        assertTrue("旧封面没人引用 ⇒ 文件应被回收", coverCache.file(first.key) == null)
        assertNotNull("新封面文件必须还在", coverCache.file(second.key))
    }
}
