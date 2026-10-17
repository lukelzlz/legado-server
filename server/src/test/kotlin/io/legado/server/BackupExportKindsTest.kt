package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * 备份**导出侧**的类别保真：`type` 必须按 `book_kind` 写。
 *
 * ## 为什么这是缺陷而不是"有损字段"
 * Legado 的 `Book.type` 是位标志（`text=8` / `audio=32` / `image=64`，见
 * `legado-with-MD3` 的 `constant/BookType.kt`），手机端**按位**决定用哪个阅读器。
 * 导出器曾把**所有**书一律写成 `type = 8`，后果：
 * - 听书导出后在手机端落进文字阅读器；
 * - 漫画导出后在手机端落进文字阅读器（"点不开"）；
 * - 再导回本服务时，只能靠 `bookUrl` 里的 `tab` 才勉强救回类型（源声明兜底的书救不回来）。
 *
 * 这里把「`type` 跟着 kind 走」+「音频的 `durChapterPos` 是毫秒」逐条锁死，并用
 * 「导出 → 再导入」的往返断言证明**类别不会在往返中丢失**（这才是用户真正的使用路径）。
 */
class BackupExportKindsTest {

    private val sourcePayload = """{"bookSourceUrl":"https://kinds.example.com","bookSourceName":"类别源"}"""

    private fun bookUrlWithTab(tab: String, id: String): String {
        val payload = java.util.Base64.getEncoder()
            .encodeToString("""{"book_id":"$id","sources":"测试","tab":"$tab","url":""}""".toByteArray(Charsets.UTF_8))
        return "data:;base64,$payload"
    }

    /** 铺三本书（小说 / 漫画 / 听书），听书还带毫秒进度。 */
    private fun seed(db: Database) {
        db.importSources(listOf(sourcePayload), applyGroups = false)
        listOf(
            Triple("小说书", "小说", "novel"),
            Triple("漫画书", "漫画", "comic"),
            Triple("听书书", "听书", "audio"),
        ).forEach { (name, tab, kind) ->
            db.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://kinds.example.com",
                    bookUrl = bookUrlWithTab(tab, name),
                    name = name,
                    tocUrl = "https://kinds.example.com/book/$name",
                    bookKind = kind,
                ),
                cover = null,
            )
        }
        db.saveProgress(
            ReadingProgress(
                sourceId = "https://kinds.example.com",
                bookUrl = bookUrlWithTab("听书", "听书书"),
                chapterUrl = "https://kinds.example.com/chapter/3",
                chapterIndex = 3,
                scrollPosition = 0.0,
                positionMs = 1_834_521,
            ),
        )
    }

    private fun bookshelfOf(bytes: ByteArray): List<JsonObject> {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "bookshelf.json") {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    return Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
                }
                zip.readBytes()
            }
        }
        error("导出包里没有 bookshelf.json")
    }

    @Test
    fun `export writes Legado type bit flags per book kind`() {
        withDatabase { db ->
            seed(db)
            val rows = bookshelfOf(BackupExporter(db).export("", LocalDate.of(2026, 10, 17)).bytes).associateBy { it["name"]!!.jsonPrimitive.content }

            assertEquals("文本 = 8", 8, rows.getValue("小说书")["type"]!!.jsonPrimitive.int)
            assertEquals("漫画 = image(64)——写成 8 会让手机端用文字阅读器打开漫画", 64, rows.getValue("漫画书")["type"]!!.jsonPrimitive.int)
            assertEquals("听书 = audio(32)——写成 8 会让手机端用文字阅读器打开听书", 32, rows.getValue("听书书")["type"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `export keeps audio millisecond position but never fakes a text offset`() {
        withDatabase { db ->
            seed(db)
            val rows = bookshelfOf(BackupExporter(db).export("", LocalDate.of(2026, 10, 17)).bytes).associateBy { it["name"]!!.jsonPrimitive.content }

            assertEquals(
                "听书的 durChapterPos 就是毫秒，必须直传（否则换设备后从头播）",
                1_834_521L,
                rows.getValue("听书书")["durChapterPos"]!!.jsonPrimitive.long,
            )
            assertEquals(
                "文本没有字符偏移可写 ⇒ 写 0，绝不把 0..1 的比例冒充成偏移",
                0L,
                rows.getValue("小说书")["durChapterPos"]!!.jsonPrimitive.long,
            )
        }
    }

    /**
     * 往返：导出 → 再导入，三本书的 `book_kind` 必须**原样回来**。
     *
     * 这条用例能对着"导出写死 type=8"的旧代码变红吗？——只有漫画/听书的 `bookUrl` 里
     * 带 `tab` 时才会绿（`tab` 兜底救了类型）。因此下面还单独断言 `type` 本身，
     * 二者合起来才真正锁住"类别不丢失"。
     */
    @Test
    fun `export then import keeps all three kinds`() {
        withDatabase { db ->
            seed(db)
            val archive = db.exportToTempZip()

            withDatabase { target ->
                BackupImporter(target).import(archive)

                assertEquals(
                    mapOf("小说书" to "novel", "漫画书" to "comic", "听书书" to "audio"),
                    target.listBookshelf().associate { it.name to it.bookKind },
                )
                val audio = target.listBookshelf().first { it.name == "听书书" }
                assertEquals("听书的毫秒进度必须一并往返", 1_834_521L, audio.positionMs)
            }
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun Database.exportToTempZip(): Path {
        val file = Files.createTempFile("legado-export-kinds", ".zip")
        Files.write(file, BackupExporter(this).export("", LocalDate.of(2026, 10, 17)).bytes)
        return file
    }

    private fun withDatabase(block: (Database) -> Unit) {
        val tempDb = Files.createTempFile("legado-export-kinds", ".sqlite").toString()
        var db: Database? = null
        try {
            db = Database(tempDb)
            db.initialize("test-password-1234")
            block(db)
        } finally {
            db?.close()
            listOf(tempDb, "$tempDb-wal", "$tempDb-shm").forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
        }
    }
}
