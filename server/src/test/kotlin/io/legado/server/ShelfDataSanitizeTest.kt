package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：书名为空/换行污染清洗，以及 `coverUrl` 自引用防护。
 *
 * 两者都是实测到的**脏数据**问题（SESSION-027）：
 * 1. 某书源的书名是 `"十日终焉我成魔\n第八十章 星尘归寂，余念长存"`
 *    （目录页规则把「最新章节标题」也取进了 name）；
 * 2. 编辑弹窗在无外部 URL 时会回退 `api.cover(coverKey)`，
 *    把 `/api/covers/<自己的 key>` 当成外部地址存回 `cover_url`，形成自引用。
 */
class ShelfDataSanitizeTest {

    // ------------------------------------------------------------------
    // 书名清洗
    // ------------------------------------------------------------------

    /** 真实脏数据：换行后的章节名必须被丢弃。 */
    @Test
    fun `book name with embedded newline keeps only the first line`() {
        val polluted = "十日终焉我成魔\n第八十章 星尘归寂，余念长存"
        assertEquals("十日终焉我成魔", sanitizeBookName(polluted))
    }

    /** CRLF 同样要处理。 */
    @Test
    fun `book name with crlf is cleaned`() {
        assertEquals("十日终焉", sanitizeBookName("十日终焉\r\n第十二章 终局"))
    }

    /** 前导空行的书名不能被清成空串。 */
    @Test
    fun `leading blank lines are skipped`() {
        assertEquals("真书名", sanitizeBookName("\n\n  真书名  \n第二章"))
    }

    /** 书名号 / 引号包裹要被剥离。 */
    @Test
    fun `surrounding quotes and book marks are trimmed`() {
        assertEquals("十日终焉", sanitizeBookName("《十日终焉》"))
        assertEquals("十日终焉", sanitizeBookName("\"十日终焉\""))
        assertEquals("十日终焉", sanitizeBookName("「十日终焉」"))
    }

    /** 内部连续空白折叠为单个空格。 */
    @Test
    fun `internal whitespace is collapsed`() {
        assertEquals("十日 终焉", sanitizeBookName("十日    终焉"))
    }

    /**
     * **刻意不做**「按`第X章`截断」：书名本身可能就叫《第X章》。
     * 这里锁定该设计决定，防止后人"顺手"加上截断逻辑而误伤。
     */
    @Test
    fun `chapter-like names are preserved when on the first line`() {
        assertEquals("第一章 起点", sanitizeBookName("第一章 起点"))
        assertEquals("第100章", sanitizeBookName("第100章"))
    }

    /** 空 / null 输入不得抛异常。 */
    @Test
    fun `null and blank names are safe`() {
        assertEquals("", sanitizeBookName(null))
        assertEquals("", sanitizeBookName(""))
        assertEquals("", sanitizeBookName("   \n  \n "))
    }

    /** 正常书名必须完全不变（避免过度清洗）。 */
    @Test
    fun `normal names are untouched`() {
        for (n in listOf("十日终焉", "三体", "The Three-Body Problem", "斗罗大陆 II 绝世唐门")) {
            assertEquals(n, sanitizeBookName(n))
        }
    }

    // ------------------------------------------------------------------
    // coverUrl 自引用
    // ------------------------------------------------------------------

    /** 真实脏数据形态：coverUrl 指向本服务自己的封面接口。 */
    @Test
    fun `self referencing cover url is detected`() {
        val key = "52fb9d95f73d9bc256cf6383157e1f0f033ed130d0606ddbf2e3f221af78615e"
        // 通过 Database 的写路径间接验证：这里直接断言真正的清洗结果
        val db = newDatabase()
        try {
            db.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://s.test",
                    bookUrl = "https://s.test/book/1",
                    name = "书名",
                    tocUrl = "https://s.test/book/1",
                    coverUrl = "/api/covers/$key",
                ),
                null,
            )
            val saved = db.getShelfBookByUrl("https://s.test/book/1")!!
            assertFalse(
                "自引用 coverUrl 不应被存库，实际：${saved.coverUrl}",
                saved.coverUrl?.contains("/api/covers/") == true,
            )
        } finally { closeQuietly(db) }
    }

    /** 真实外部 URL 必须原样保留。 */
    @Test
    fun `external cover url is preserved`() {
        val db = newDatabase()
        try {
            db.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://s.test",
                    bookUrl = "https://s.test/book/2",
                    name = "书名2",
                    tocUrl = "https://s.test/book/2",
                    coverUrl = "http://img.example/cover.jpg",
                ),
                null,
            )
            val saved = db.getShelfBookByUrl("https://s.test/book/2")!!
            assertEquals("http://img.example/cover.jpg", saved.coverUrl)
        } finally { closeQuietly(db) }
    }

    /** 带 origin 的自引用（`http://host/api/covers/<key>`）同样要识别。 */
    @Test
    fun `absolute self reference is also rejected`() {
        val db = newDatabase()
        try {
            db.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://s.test",
                    bookUrl = "https://s.test/book/3",
                    name = "书名3",
                    tocUrl = "https://s.test/book/3",
                    coverUrl = "http://127.0.0.1:8080/api/covers/${"a".repeat(64)}",
                ),
                null,
            )
            val saved = db.getShelfBookByUrl("https://s.test/book/3")!!
            assertFalse("绝对地址形式的自引用也要拒绝", saved.coverUrl?.contains("/api/covers/") == true)
        } finally { closeQuietly(db) }
    }

    /** 存库时书名也要被清洗。 */
    @Test
    fun `polluted name is sanitized on write`() {
        val db = newDatabase()
        try {
            db.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://s.test",
                    bookUrl = "https://s.test/book/4",
                    name = "十日终焉我成魔\n第八十章 星尘归寂，余念长存",
                    tocUrl = "https://s.test/book/4",
                ),
                null,
            )
            val saved = db.getShelfBookByUrl("https://s.test/book/4")!!
            assertEquals("十日终焉我成魔", saved.name)
        } finally { closeQuietly(db) }
    }

    private fun newDatabase(): Database {
        val path = java.nio.file.Files.createTempFile("shelf-sanitize-test", ".sqlite").toString()
        val db = Database(path)
        db.initialize("test-pass")
        return db
    }

    private fun closeQuietly(db: Database) {
        runCatching { db.close() }
    }
}
