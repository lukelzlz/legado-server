package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份导入的**类别放行规则**：只有本地图书会被过滤，听书/漫画必须以正确的 `book_kind` 入架。
 *
 * ## 为什么这条规则曾经是"对的"，现在成了"过时"
 * 当年服务端只能做文本阅读，因此有意过滤掉本地图书与音频（导入摘要里报"已跳过 N 条"）。
 * 现在漫画（图片缓存 `comic/` + 漫画阅读器）与听书（音频缓存 `audio/` + `Range` 直出）都是
 * 一等公民，继续过滤听书就是误伤；漫画若按"小说"入库则会进文字阅读器 ⇒ 用户看到"打不开"。
 *
 * 这些用例锁死三件事：
 * 1. 听书 ⇒ `book_kind='audio'`，**不再被跳过**；
 * 2. 漫画 ⇒ `book_kind='comic'`，不被跳过、也不被当成小说；
 * 3. 本地图书（`content://` 等手机本机路径）**仍然被跳过且计数正确** —— 服务端确实读不到；
 * 4. 判定顺序：**先判本地书、再判 kind**（本地书带 `tab` 时不得被 kind 抢走）。
 */
class BackupImportKindsTest {

    private companion object {
        const val AGGREGATE_SOURCE = "https://aggregate.example.com"
    }

    // ------------------------------------------------------------------
    // 放行：听书 / 漫画
    // ------------------------------------------------------------------

    @Test
    fun `audio book is imported as book_kind audio`() {
        withImport(shelfOf(entry("听书书", bookUrlWithTab("听书", "a1"), type = 32))) { database, summary ->
            assertEquals("听书必须计入导入", 1, summary.books)
            assertEquals("听书不是本地图书，不该被跳过", 0, summary.skippedLocal)
            assertEquals(
                "听书必须以 book_kind='audio' 入库（前端据此进有声书播放器）",
                "audio",
                database.getBookKind(AGGREGATE_SOURCE, bookUrlWithTab("听书", "a1")),
            )
        }
    }

    @Test
    fun `comic book is imported as book_kind comic`() {
        withImport(shelfOf(entry("漫画书", bookUrlWithTab("漫画", "c1"), type = 8))) { database, summary ->
            assertEquals("漫画必须计入导入", 1, summary.books)
            assertEquals("漫画不是本地图书，不该被跳过", 0, summary.skippedLocal)
            assertEquals(
                "漫画必须以 book_kind='comic' 入库（否则进文字阅读器 ⇒ 表现为打不开）",
                "comic",
                database.getBookKind(AGGREGATE_SOURCE, bookUrlWithTab("漫画", "c1")),
            )
        }
    }

    /** 漫画**绝不能被当成小说**：`book_kind` 为空（NULL）只是"未分类"，而这里必须是 `comic`。 */
    @Test
    fun `comic is never imported as a novel`() {
        withImport(shelfOf(entry("漫画书", bookUrlWithTab("漫画", "c1"), type = 8))) { database, _ ->
            assertEquals(
                "漫画书的 book_kind 必须是 comic（null 表示「未分类」、会被当小说打开）",
                "comic",
                database.rawBookKind(AGGREGATE_SOURCE, bookUrlWithTab("漫画", "c1")),
            )
            assertEquals("comic", database.getShelfBookByUrl(bookUrlWithTab("漫画", "c1"))?.bookKind)
        }
    }

    // ------------------------------------------------------------------
    // 过滤：只剩本地图书
    // ------------------------------------------------------------------

    @Test
    fun `local books are still skipped and counted`() {
        val localUrl = "content://com.android.documents/tree/primary/document/本地书.epub"
        withImport(shelfOf(entry("本地书", localUrl, type = 264, origin = "loc_book"))) { database, summary ->
            assertEquals("本地图书必须仍被过滤", 1, summary.skippedLocal)
            assertEquals("被过滤的本地图书不得计入导入", 0, summary.books)
            assertTrue("本地书绝不能进书架（点了必然打不开）", database.listBookshelf().isEmpty())
        }
    }

    /** 混合一批：听书 + 漫画 + 本地书 —— 跳过计数必须只数本地书。 */
    @Test
    fun `only local books count as skipped in a mixed backup`() {
        val localUrl = "content://com.android.documents/tree/primary/document/x.txt"
        withImport(
            shelfOf(
                entry("听书书", bookUrlWithTab("听书", "a1"), type = 32),
                entry("漫画书", bookUrlWithTab("漫画", "c1"), type = 8),
                entry("小说书", bookUrlWithTab("小说", "n1"), type = 8),
                entry("本地书", localUrl, type = 264, origin = "loc_book"),
            ),
        ) { database, summary ->
            assertEquals("听书 + 漫画 + 小说 共 3 本必须导入", 3, summary.books)
            assertEquals("只有本地图书算跳过", 1, summary.skippedLocal)
            assertEquals(setOf("听书书", "漫画书", "小说书"), database.listBookshelf().map { it.name }.toSet())
            assertEquals(
                "三种类别各就各位",
                mapOf("听书书" to "audio", "漫画书" to "comic", "小说书" to "novel"),
                database.listBookshelf().associate { it.name to it.bookKind },
            )
        }
    }

    // ------------------------------------------------------------------
    // 判定顺序（关键陷阱）：本地书优先于 kind
    // ------------------------------------------------------------------

    /**
     * **先判本地图书，再判 `tab`**。
     *
     * 本地书没有 `tab` 字段；若先判 `tab`，带 `tab` 的本地记录就会落进兜底分支被当成在线书。
     * 这里构造「既是 `content://` 又恰好带 `tab=漫画`」的记录，确认本地优先 ⇒ 仍被过滤。
     */
    @Test
    fun `local book wins over comic tab`() {
        val hybrid = "content://x/" + bookUrlWithTab("漫画", "hybrid")
        withImport(shelfOf(entry("本地漫画", hybrid, type = 264, origin = "loc_book"))) { database, summary ->
            assertEquals("本地图书判定必须先于 tab/kind 判定", 1, summary.skippedLocal)
            assertEquals("不得因为带 comic tab 就把它当漫画导入", 0, summary.books)
            assertTrue("书架上不该出现这本书", database.listBookshelf().isEmpty())
        }
    }

    /** 同理，`听书` tab 也不能把本地书"救"进书架。 */
    @Test
    fun `local book wins over audio tab`() {
        val hybrid = "content://x/" + bookUrlWithTab("听书", "hybrid")
        withImport(shelfOf(entry("本地听书", hybrid, type = 264, origin = "loc_book"))) { database, summary ->
            assertEquals(1, summary.skippedLocal)
            assertEquals(0, summary.books)
            assertTrue(database.listBookshelf().isEmpty())
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun withImport(shelf: String, block: (Database, BackupImportSummary) -> Unit) {
        val directory = Files.createTempDirectory("legado-import-kinds")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val archive = directory.resolve("backup.zip")
            writeArchive(archive, shelf)
            block(database, BackupImporter(database).import(archive))
        } finally {
            database.close()
            Files.deleteIfExists(Path.of(directory.resolve("legado.sqlite").toString()))
            listOf("-wal", "-shm").forEach { suffix ->
                runCatching { Files.deleteIfExists(Path.of(directory.resolve("legado.sqlite").toString() + suffix)) }
            }
            runCatching { directory.toFile().deleteRecursively() }
        }
    }

    private fun shelfOf(vararg entries: String): String = "[" + entries.joinToString(",") + "]"

    private fun writeArchive(target: Path, shelf: String) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            // 聚合源：**没有** `bookSourceType`（真实数据就是这样），类型只能由书自己的 tab 决定
            zip.putNextEntry(ZipEntry("bookSource.json"))
            zip.write("""[{"bookSourceUrl":"$AGGREGATE_SOURCE","bookSourceName":"聚合源","enabled":true}]""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bookshelf.json"))
            zip.write(shelf.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun entry(name: String, bookUrl: String, type: Int, origin: String = AGGREGATE_SOURCE): String =
        """{"name":"$name","author":"作者","bookUrl":"$bookUrl","origin":"$origin","tocUrl":"$AGGREGATE_SOURCE/book/$name","type":$type,"group":0,"durChapterIndex":1,"durChapterTime":1790000000000}"""

    private fun bookUrlWithTab(tab: String, id: String): String {
        val payload = Base64.getEncoder()
            .encodeToString("""{"book_id":"$id","sources":"测试","tab":"$tab","url":""}""".toByteArray(Charsets.UTF_8))
        return "data:;base64,$payload"
    }
}
