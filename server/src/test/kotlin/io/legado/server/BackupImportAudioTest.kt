package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 方案 G2 · **G0：放开音频入架**（不可跳过的第一步）。
 *
 * 放开之前 `BackupImporter` 把音频书当 `skippedAudio` 主动丢弃 —— 后面所有环节都没有输入。
 * 这里用与真实数据**同分布**的合成备份（锚点：434 条 = 389 在线 + 31 音频 + 14 本地图书，
 * 见 `ShelfKindTest` 与 AGENTS.md）把三件事钉死：
 *
 * 1. 音频**不再被过滤**，且如实落库 `book_kind='audio'`（前端据此进有声书播放器）；
 * 2. 本地图书**仍然被过滤**（`bookUrl` 指向手机本机，服务端真的读不到）；
 * 3. 音频的 `durChapterPos`（毫秒）被导入成 `position_ms`，而**文本的字符偏移量不导入**
 *    （AGENTS.md 明文：不得把 `durChapterPos` 强塞进百分比 `scroll_position`）。
 */
class BackupImportAudioTest {

    private companion object {
        /** 真实数据的分布锚点（`te/分类判别方法.md` / SESSION-031）。 */
        const val ONLINE_COUNT = 389
        const val AUDIO_COUNT = 31
        const val LOCAL_COUNT = 14
        const val SOURCE_URL = "https://audiobook.example.com"
    }

    @Test
    fun `audio books are imported instead of being skipped`() {
        val dir = Files.createTempDirectory("legado-audio-import")
        val dbPath = dir.resolve("legado.sqlite").toString()
        val zipPath = dir.resolve("backup2026-10-15-test.zip")
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            writeBackup(zipPath)

            val summary = BackupImporter(database).import(zipPath)

            assertEquals(
                "音频必须计入导入（$ONLINE_COUNT 在线 + $AUDIO_COUNT 音频）",
                ONLINE_COUNT + AUDIO_COUNT,
                summary.books,
            )
            assertEquals("只有本地图书被跳过", LOCAL_COUNT, summary.skippedLocal)
            assertEquals("G0 起音频**不再被跳过**（如实报 0，而不是静默吞掉）", 0, summary.skippedAudio)

            val shelf = database.listBookshelf()
            assertEquals(ONLINE_COUNT + AUDIO_COUNT, shelf.size)

            val audioBooks = shelf.filter { it.bookKind == "audio" }
            assertEquals("31 本音频书必须落成 book_kind=audio", AUDIO_COUNT, audioBooks.size)
            // 在线小说不得被误判成音频（否则会被送进有声书播放器 ⇒ "书打不开"）
            assertEquals(ONLINE_COUNT, shelf.count { it.bookKind == "novel" })
        } finally {
            database.close()
            cleanup(dir)
        }
    }

    @Test
    fun `imports millisecond position for audio books only`() {
        val dir = Files.createTempDirectory("legado-audio-import-pos")
        val dbPath = dir.resolve("legado.sqlite").toString()
        val zipPath = dir.resolve("backup2026-10-15-pos.zip")
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            writeBackup(zipPath, audioChapterPos = 1_834_521, novelChapterPos = 4096)

            BackupImporter(database).import(zipPath)

            val audioAnchor = database.listReadingAnchors().first { anchor -> database.getBookKind(anchor.sourceId, anchor.bookUrl) == "audio" }
            assertEquals(
                "音频的 durChapterPos 就是毫秒 ⇒ 直传成 position_ms",
                1_834_521L,
                audioAnchor.positionMs,
            )

            val novelAnchor = database.listReadingAnchors().first { anchor -> database.getBookKind(anchor.sourceId, anchor.bookUrl) == "novel" }
            assertNull(
                "文本的字符偏移量**绝不能**混进毫秒字段（AGENTS.md 明文禁止）",
                novelAnchor.positionMs,
            )
            assertEquals("文本进度仍走比例字段（导入时为 0）", 0.0, novelAnchor.scrollPosition, 0.0001)
        } finally {
            database.close()
            cleanup(dir)
        }
    }

    @Test
    fun `local books are still skipped because the server cannot read them`() {
        val dir = Files.createTempDirectory("legado-audio-import-local")
        val dbPath = dir.resolve("legado.sqlite").toString()
        val zipPath = dir.resolve("backup2026-10-15-local.zip")
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            writeBackup(zipPath)

            val summary = BackupImporter(database).import(zipPath)

            assertTrue("本地图书必须仍被过滤", summary.skippedLocal > 0)
            val local = database.listBookshelf().filter { it.bookUrl.startsWith("content://") }
            assertEquals("本地书绝不能进书架（点了必然打不开）", 0, local.size)
            val audio = database.listBookshelf().firstOrNull { it.bookKind == "audio" }
            assertNotNull("音频书必须在书架上（它的封面/进度/播放都依赖这一行）", audio)
        } finally {
            database.close()
            cleanup(dir)
        }
    }

    // ------------------------------------------------------------------
    // 合成备份（分布与真实数据一致）
    // ------------------------------------------------------------------

    /**
     * 写一份与真实备份同分布的 `bookshelf.json`。
     *
     * ⚠️ 每本书的 `bookUrl` 必须**唯一**：`book_shelf` 主键是 `(source_id, book_url)`，
     * 两本书用同一个载荷会被 UPSERT **静默合并成一行**（表现为"导入 N 本却只查到 N-1 本"）。
     * 因此 base64 载荷里带各自的 `book_id`。
     */
    private fun writeBackup(zipPath: Path, audioChapterPos: Int = 100, novelChapterPos: Int = 0) {
        val shelfEntries = mutableListOf<String>()

        repeat(ONLINE_COUNT) { index ->
            shelfEntries += shelfEntry(
                id = "novel-$index",
                name = "在线小说$index",
                tab = "小说",
                type = 8,
                origin = SOURCE_URL,
                bookUrl = bookUrlWithTab("小说", "novel-$index"),
                chapterIndex = 1,
                chapterPos = novelChapterPos,
            )
        }
        repeat(AUDIO_COUNT) { index ->
            shelfEntries += shelfEntry(
                id = "audio-$index",
                name = "听书$index",
                tab = "听书",
                type = 32,
                origin = SOURCE_URL,
                bookUrl = bookUrlWithTab("听书", "audio-$index"),
                chapterIndex = 2,
                chapterPos = audioChapterPos,
            )
        }
        repeat(LOCAL_COUNT) { index ->
            shelfEntries += shelfEntry(
                id = "local-$index",
                name = "本地书$index",
                tab = null,
                type = 264,
                origin = "loc_book",
                bookUrl = "content://com.android.documents/tree/primary/document/local-$index.epub",
                chapterIndex = 1,
                chapterPos = 0,
            )
        }

        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(ZipEntry("bookSource.json"))
            zip.write("""[{"bookSourceUrl":"$SOURCE_URL","bookSourceName":"测试有声书","enabled":true}]""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bookGroup.json"))
            zip.write("[]".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bookshelf.json"))
            zip.write(("[" + shelfEntries.joinToString(",") + "]").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun shelfEntry(
        id: String,
        name: String,
        tab: String?,
        type: Int,
        origin: String,
        bookUrl: String,
        chapterIndex: Int,
        chapterPos: Int,
    ): String = """
        {"name":"$name","author":"作者$id","bookUrl":"$bookUrl","origin":"$origin",
         "tocUrl":"$SOURCE_URL/book/$id","type":$type,"group":0,
         "durChapterIndex":$chapterIndex,"durChapterPos":$chapterPos,"durChapterTime":1790000000000}
    """.trimIndent().replace("\n", "")

    private fun bookUrlWithTab(tab: String, id: String): String {
        val payload = Base64.getEncoder()
            .encodeToString("""{"book_id":"$id","sources":"测试","tab":"$tab","url":""}""".toByteArray(Charsets.UTF_8))
        return "data:;base64,$payload"
    }

    private fun cleanup(dir: Path) {
        runCatching { dir.toFile().deleteRecursively() }
        val dbPath = dir.resolve("legado.sqlite").toString()
        runCatching { Files.deleteIfExists(Path.of(dbPath)) }
        runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
        runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
    }
}
