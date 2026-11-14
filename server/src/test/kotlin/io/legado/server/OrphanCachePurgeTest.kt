package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 孤儿缓存清理：书已不在书架的正文 / 目录 / 章节索引要被真正删除。
 *
 * 这条能力的存在理由：查询侧可以用 INNER JOIN 把孤儿「藏起来」（避免出现「未知书名」），
 * 但那是遮丑，不是清理 —— 换源/删书残留的缓存会一直占空间。
 *
 * 关键约束是**宁可漏删、绝不误删**：聚合源的 `book_url` 与 `toc_url` 只差尾部
 * `{"type":"x"}` 字段（实测 qingtian / qingtian2），所以判活必须做后缀归一化 + 双向包含。
 */
class OrphanCachePurgeTest {

    private val password = "pw-123456789"

    /** 与其它测试一致：Windows 上删被占用的 sqlite 会抛异常，清理必须吞掉。 */
    private fun deleteSqlite(dbPath: String) {
        runCatching { Files.deleteIfExists(Path.of(dbPath)) }
        runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
        runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
    }

    @Test
    fun `孤儿正文缓存被删除，尾部类型不同的合法缓存保留`() {
        val dbPath = Files.createTempFile("legado-purge-orphan", ".sqlite").toString()
        try {
            Database(dbPath).use { db ->
                db.initialize(password)
                // 真实形态：book_url 尾部 qingtian、toc_url 尾部 qingtian2，缓存分别挂在两种 URL 上
                val bookUrl = """data:;base64,AAA,{"type":"qingtian"}"""
                val tocUrl = """data:;base64,AAA,{"type":"qingtian2"}"""
                db.saveBookshelf(
                    BookshelfWriteRequest(
                        sourceId = "src-a",
                        bookUrl = bookUrl,
                        name = "十日终焉",
                        author = "杀虫队队员",
                        tocUrl = tocUrl,
                    ),
                    null,
                )
                // 合法：挂在 book_url 上
                db.cacheBookContent("src-a", bookUrl, "$bookUrl/c/1", ChapterContent("第一章", "剑光如雪"))
                // 合法：挂在 toc_url 上（尾部不同，但归一化后同一个 URL）
                db.cacheBookContent("src-a", tocUrl, "$tocUrl/c/1", ChapterContent("第一章", "风雪很大"))
                // 孤儿：这本书已经不在书架
                db.cacheBookContent("src-a", "https://gone.test/book/9", "https://gone.test/book/9/c/1", ChapterContent("旧章", "早已离开"))

                val result = db.purgeOrphanTextCaches()
                assertEquals("只该删掉孤儿那一本", 1, result.contentRows)
                assertNotNull("book_url 形态的合法缓存必须保留", db.cachedContent("src-a", bookUrl, "$bookUrl/c/1"))
                assertNotNull("toc_url 形态的合法缓存必须保留", db.cachedContent("src-a", tocUrl, "$tocUrl/c/1"))
                assertNull("孤儿缓存必须被删掉", db.cachedContent("src-a", "https://gone.test/book/9", "https://gone.test/book/9/c/1"))

                // 幂等：再跑一次什么都不该删
                assertEquals(0, db.purgeOrphanTextCaches().total)
            }
        } finally {
            deleteSqlite(dbPath)
        }
    }

    @Test
    fun `孤儿目录与章节索引同样被清掉`() {
        val dbPath = Files.createTempFile("legado-purge-orphan-toc", ".sqlite").toString()
        try {
            Database(dbPath).use { db ->
                db.initialize(password)
                db.saveBookshelf(
                    BookshelfWriteRequest(
                        sourceId = "src-a",
                        bookUrl = "https://a.test/book/1",
                        name = "十日终焉",
                        author = "杀虫队队员",
                        tocUrl = "https://a.test/book/1/toc",
                    ),
                    null,
                )
                db.saveTocCache("src-a", "https://a.test/book/1/toc", listOf(Chapter(0, "第一章", "https://a.test/book/1/c/1")))
                // 孤儿目录（书不在架 ⇒ 不建索引，但缓存行是手工造的）
                db.saveTocCache("src-a", "https://gone.test/book/9/toc", listOf(Chapter(0, "旧章", "https://gone.test/book/9/c/1")))

                val result = db.purgeOrphanTextCaches()
                assertEquals("该删掉孤儿目录", 1, result.tocRows)
                assertNotNull("合法目录必须保留", db.getTocCache("src-a", "https://a.test/book/1/toc"))
                assertNull(db.getTocCache("src-a", "https://gone.test/book/9/toc"))
            }
        } finally {
            deleteSqlite(dbPath)
        }
    }
}
