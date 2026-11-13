package io.legado.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 书签页封面链路的**数据层**闭环：
 *
 * 1. 「超过五次没找到就标记」——`missing_attempts` 累加到 5 之后 `coverMissing` 为真，
 *    之后的批量请求默认跳过；用户手动 `force` 仍可再试。
 * 2. 封面文件的生命周期——「只要书架或者书签任何一个或两者存在就保留」：
 *    - 移出书架但还有书签 ⇒ 封面文件**不能删**；
 *    - 书签也删光了 且 书架里没有 ⇒ 才允许删。
 */
class BookmarkCoverDbTest {

    private lateinit var tempDir: Path
    private lateinit var database: Database

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("legado-bookmark-cover")
        database = Database(tempDir.resolve("test.sqlite").toString())
        database.initialize("test-pass")
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private fun addShelfBook(name: String, author: String, coverKey: String?) {
        database.saveBookshelf(
            BookshelfWriteRequest(
                sourceId = "src-$name",
                bookUrl = "https://example.com/$name",
                name = name,
                author = author,
                tocUrl = "https://example.com/$name",
            ),
            cover = null,
        )
        if (coverKey != null) database.attachCoverToShelfRows(name, author, coverKey)
    }

    private fun addBookmark(name: String, author: String?, chapterIndex: Int = 0) {
        database.saveBookmark(
            BookmarkSaveRequest(
                bookName = name,
                bookAuthor = author,
                chapterIndex = chapterIndex,
                chapterName = "第 ${chapterIndex + 1} 章",
                chapterPos = 10,
                bookText = "书签原文",
                content = "",
            ),
        )
    }

    @Test
    fun `连续五次没抓到之后标记放弃，之后的批量请求默认跳过`() {
        val name = "抓不到封面的书"
        (1..COVER_MISSING_LIMIT).forEach { _ ->
            database.recordCoverMiss(name, "作者甲")
        }
        val row = database.getBookCover(name, "作者甲")
        assertNotNull("失败计数必须落库", row)
        assertEquals(COVER_MISSING_LIMIT, row!!.missingAttempts)
        assertTrue("满 5 次必须被标记为放弃", row.exhausted)

        // 抓到之后计数清零、标记消失
        database.saveBookCover(name, "作者甲", "cover-key-1")
        val cleared = database.getBookCover(name, "作者甲")!!
        assertEquals(0, cleared.missingAttempts)
        assertFalse(cleared.exhausted)
        assertEquals("cover-key-1", cleared.coverKey)
    }

    @Test
    fun `书签概览会把封面登记表的兜底封面与无封面标记一起返回`() {
        // 只在书签里存在的书（书架里没有）+ 一张 manual 封面
        addBookmark("只有书签的书", null)
        database.saveBookCover("只有书签的书", null, "manual-cover")
        // 另一本：抓了 5 次仍没封面
        addBookmark("无封面的书", "作者乙")
        repeat(5) { database.recordCoverMiss("无封面的书", "作者乙") }

        val overview = database.listBookmarkBooks()
        val withCover = overview.first { it.bookName == "只有书签的书" }
        assertEquals("书架里没有也要能拿到兜底封面", "manual-cover", withCover.coverKey)

        val missing = overview.first { it.bookName == "无封面的书" }
        assertNull(missing.coverKey)
        assertTrue("满 5 次要在概览里带标记", missing.coverMissing)
        assertEquals(5, missing.coverAttempts)
    }

    @Test
    fun `移出书架但还有书签时，封面文件必须保留`() {
        addShelfBook("保留封面的书", "作者丙", coverKey = "shared-cover")
        addBookmark("保留封面的书", "作者丙")

        val orphan = database.removeBookshelf("src-保留封面的书", "https://example.com/保留封面的书")

        assertNull("还有书签引用这本书 ⇒ 不算孤儿，不能删封面", orphan)
        assertEquals(0, database.listBookshelf().size)
        assertTrue("书签不跟着移出书架一起删", database.hasBookmarksFor("保留封面的书", "作者丙"))
        // 关键：key 必须**转存**进 book_cover —— 否则书签页看不到封面，且日后书签删光也回收不掉文件
        // （审查实测复现：orphan=null && overviewCover=null && bookCoverRow=null）
        assertEquals("shared-cover", database.getBookCover("保留封面的书", "作者丙")?.coverKey)
        val overview = database.listBookmarkBooks().single()
        assertEquals("shared-cover", overview.coverKey)
        assertEquals(1, overview.count)
    }

    @Test
    fun `同一本书挂多个书源时，书签条数不能被书架行放大`() {
        addShelfBook("多源书", "作者庚", coverKey = null)
        // 第二个书源上的同一本书（book_shelf 主键是 sourceId+bookUrl，所以这是合法的两行）
        database.saveBookshelf(
            BookshelfWriteRequest(
                sourceId = "src-other",
                bookUrl = "https://other.example.com/多源书",
                name = "多源书",
                author = "作者庚",
                tocUrl = "https://other.example.com/多源书",
            ),
            cover = null,
        )
        addBookmark("多源书", "作者庚")

        val overview = database.listBookmarkBooks().single()
        assertEquals("JOIN 会把书签行按书架行数放大，必须 count(distinct b.id)", 1, overview.count)
    }

    @Test
    fun `书架与书签都不在了，封面才成为孤儿`() {
        addShelfBook("彻底删除的书", "作者丁", coverKey = "orphan-cover")

        val orphan = database.removeBookshelf("src-彻底删除的书", "https://example.com/彻底删除的书")

        assertEquals("既没书架行也没书签 ⇒ 允许回收封面文件", "orphan-cover", orphan)
    }

    @Test
    fun `封面登记表里的同一张图被别的书引用时也不能当孤儿删`() {
        addShelfBook("甲书", "作者戊", coverKey = "shared-key")
        addBookmark("乙书", "作者己")
        database.saveBookCover("乙书", "作者己", "shared-key")

        val orphan = database.removeBookshelf("src-甲书", "https://example.com/甲书")

        assertNull("封面登记表还引用着同一张图 ⇒ 不能删", orphan)
    }
}
