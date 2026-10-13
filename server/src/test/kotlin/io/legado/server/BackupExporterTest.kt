package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * 备份导出器：**格式契约**测试。
 *
 * 参照物是真实备份 `backup2026-09-28-CD_Watch_A.zip` 与 `backup2026-09-30-PEPM00.zip`
 * （两者结构实测一致）。这里把「4 个文件齐备」「字段顺序」「2 空格排版」「不转义 HTML 字符」
 * 「空的**可选字段**省略但**文件**不省」这些契约逐条锁死 —— 它们一旦漂移，导出的包在
 * Legado App 侧就会缺数据或解析异常，而这类问题**看代码看不出来**。
 *
 * ⚠️ `bookmark.json` 是**阅读进度**的载体（`bookshelf.json` 里的 `durChapter*` 只记到章节号），
 * 因此它必须始终存在，不能因为「本次没书签」就省略。
 */
class BackupExporterTest {

    /** 每个用例一个独立临时库；**必须 close 后再删**，否则 Windows 下 WAL 占用会抛 FileSystemException。 */
    private fun withDatabase(block: (Database) -> Unit) {
        val tempDb = Files.createTempFile("legado-export", ".sqlite").toString()
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

    private val sourcePayload = """{"bookSourceUrl":"https://a.example.com","bookSourceName":"A 源"}"""

    /** 铺一份最小但各部件齐全的数据：书源 + 分组 + 书架（含进度）+ 书签。 */
    private fun seed(db: Database) {
        db.importSources(listOf(sourcePayload), applyGroups = false)
        val shelf = listOf(
            BackupShelfEntry(
                sourceId = "https://a.example.com",
                bookUrl = "https://a.example.com/book/1",
                name = "测试书",
                author = "作者",
                tocUrl = "https://a.example.com/book/1",
                coverUrl = null,
                completed = false,
                chapterIndex = 12,
                readAt = 1_700_000_000_000L,
                groupName = "分组A",
            ),
        )
        db.importLibrary(shelf)
        db.importBookGroups(listOf(BackupGroupEntry(groupId = 7, groupName = "分组A", order = 0, builtIn = false)), shelf)
        db.importBookmarks(
            listOf(
                BackupBookmarkEntry(
                    bookName = "测试书",
                    bookAuthor = "作者",
                    chapterIndex = 12,
                    chapterName = "第13章",
                    chapterPos = 42,
                    bookText = "正文片段",
                    content = null,
                    time = 1_700_000_000_500L,
                ),
            ),
        )
    }

    private fun entriesOf(bytes: ByteArray): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun `export always writes the six required entries in name order`() = withDatabase { db ->
        seed(db)
        val result = BackupExporter(db).export("CD_Watch_A", LocalDate.of(2026, 9, 30))

        // 4 个文件一个都不能少，且顺序按名称升序（与真实备份一致）
        assertEquals(
            listOf("bookGroup.json", "bookmark.json", "bookshelf.json", "bookSource.json", "httpTTS.json", "replaceRule.json"),
            entriesOf(result.bytes).keys.toList(),
        )
        assertEquals("backup2026-09-30-CD_Watch_A.zip", result.fileName)
    }

    /**
     * 空库也必须产出 4 个文件 —— 这是「文件齐备」契约的关键：
     * 文件可以在数据为空时是 `[]`，但不能消失。
     */
    @Test
    fun `empty database still produces all six files with empty arrays`() = withDatabase { db ->
        val entries = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes)

        assertEquals(
            listOf("bookGroup.json", "bookmark.json", "bookshelf.json", "bookSource.json", "httpTTS.json", "replaceRule.json"),
            entries.keys.toList(),
        )
        entries.forEach { (name, text) -> assertEquals("$name 应为空数组", "[]", text) }
    }

    @Test
    fun `bookmark entry keeps the eight Legado fields and the empty content placeholder`() = withDatabase { db ->
        seed(db)
        val bookmark = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes).getValue("bookmark.json")

        // 逐字节对齐：2 空格缩进、": " 分隔、字段顺序照抄真实备份、content 保留空串（Legado 用它存批注）
        assertEquals(
            """
            [
              {
                "bookAuthor": "作者",
                "bookName": "测试书",
                "bookText": "正文片段",
                "chapterIndex": 12,
                "chapterName": "第13章",
                "chapterPos": 42,
                "content": "",
                "time": 1700000000500
              }
            ]
            """.trimIndent(),
            bookmark,
        )
    }

    @Test
    fun `bookshelf entry keeps Legado field order and omits empty optional fields`() = withDatabase { db ->
        seed(db)
        val shelf = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes).getValue("bookshelf.json")
        // 服务端按**名字**关联分组，`book_group.id` 是它自己的自增主键（不是备份里的 groupId），
        // 因此导出写回的 `group` 必须是这个本地 id，否则导入端按 id 反查会落空。
        val groupId = db.listBookGroups().single().id

        // coverUrl / intro / variable / readConfig / wordCount 在真实备份里是「有值才出现」的，
        // 这里对应「无值就不写」；durChapterPos 语义不同（百分比 vs 字符偏移）刻意写 0。
        assertEquals(
            """
            [
              {
                "author": "作者",
                "bookUrl": "https://a.example.com/book/1",
                "canUpdate": true,
                "durChapterIndex": 12,
                "durChapterPos": 0,
                "durChapterTime": 1700000000000,
                "group": $groupId,
                "kind": "连载中",
                "lastCheckCount": 0,
                "lastCheckTime": 0,
                "latestChapterTime": 0,
                "name": "测试书",
                "order": -1,
                "origin": "https://a.example.com",
                "originName": "A 源",
                "originOrder": 0,
                "syncTime": 1700000000000,
                "tocUrl": "https://a.example.com/book/1",
                "totalChapterNum": 0,
                "type": 8
              }
            ]
            """.trimIndent(),
            shelf,
        )
    }

    @Test
    fun `book group entry keeps the six Legado fields`() = withDatabase { db ->
        seed(db)
        val groups = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes).getValue("bookGroup.json")
        val groupId = db.listBookGroups().single().id

        assertEquals(
            """
            [
              {
                "bookSort": -1,
                "enableRefresh": true,
                "groupId": $groupId,
                "groupName": "分组A",
                "order": 0,
                "show": false
              }
            ]
            """.trimIndent(),
            groups,
        )
    }

    /** 书源必须**原样回放**：它承载 26 个字段（含 jsLib / loginUrl 等大段文本），重建必失真。 */
    @Test
    fun `book source payload is replayed verbatim`() = withDatabase { db ->
        seed(db)
        val sources = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes).getValue("bookSource.json")

        assertTrue("应包含书源名", sources.contains("\"bookSourceName\": \"A 源\""))
        assertTrue("应包含书源 id", sources.contains("\"bookSourceUrl\": \"https://a.example.com\""))
    }

    /** HTML 字符不能转义：真实备份里 `"exploreUrl": "<js>…"` 就是原样尖括号。 */
    @Test
    fun `json output does not escape html characters`() = withDatabase { db ->
        db.importSources(listOf("""{"bookSourceUrl":"https://b.example.com","bookSourceName":"<js> & \"引号\"</js>"}"""))
        val sources = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes).getValue("bookSource.json")

        assertTrue("尖括号与 & 必须原样保留，不能被转义成 \\u003c", sources.contains("<js> & "))
        assertTrue("引号仍要正确转义", sources.contains("\\\"引号\\\""))
    }

    /** 设备名为空时文件名只有日期；带设备名时拼在日期之后。 */
    @Test
    fun `file name follows the backup date device pattern`() = withDatabase { db ->
        val exporter = BackupExporter(db)
        val today = LocalDate.of(2026, 9, 28)
        assertEquals("backup2026-09-28.zip", exporter.fileName("", today))
        assertEquals("backup2026-09-28-CD_Watch_A.zip", exporter.fileName("CD_Watch_A", today))
        assertEquals("backup2026-09-28-PEPM00.zip", exporter.fileName("  PEPM00  ", today))
    }

    /** 设备名会拼进**文件名**，因此路径分隔符等非法字符必须被挡掉。 */
    @Test
    fun `device name is sanitized against path separators`() {
        assertEquals("CD_Watch_A", sanitizeBackupDeviceName("CD_Watch_A"))
        // 路径分隔符被剥掉 ⇒ 得到的是一段**不含分隔符**的普通文件名，无法用于目录穿越
        assertEquals("....etcpasswd", sanitizeBackupDeviceName("../../etc/passwd"))
        assertEquals("a b", sanitizeBackupDeviceName("  a b  "))
        assertEquals("平板", sanitizeBackupDeviceName("平板"))
        assertTrue(sanitizeBackupDeviceName("x".repeat(100)).length <= 48)
    }

    /**
     * 往返：导出的包必须能被 [BackupImporter] 读回去，且各部分条数一致。
     *
     * 这是最强的一条断言 —— 它同时保证「4 个文件都在」「字段名对得上」「解析器能解析」。
     */
    @Test
    fun `exported package round trips through the importer`() = withDatabase { source ->
        seed(source)
        val exported = BackupExporter(source).export("CD_Watch_A", LocalDate.of(2026, 9, 30))
        val zipPath = Files.createTempFile("legado-export-roundtrip", ".zip")
        val target = Files.createTempFile("legado-export-target", ".sqlite").toString()
        var db: Database? = null
        try {
            Files.write(zipPath, exported.bytes)
            db = Database(target)
            db.initialize("test-password-1234")

            val summary = BackupImporter(db).import(zipPath)

            // 书源、书籍、书签都要回来（进度记在 bookshelf.json 的 durChapter* 上）
            assertEquals(1, summary.sources + summary.sourcesUpdated)
            assertEquals(1, summary.books + summary.booksUpdated)
            assertEquals(1, summary.bookmarks)
            assertEquals("测试书", db.listBookshelf().single().name)
            assertEquals(12, db.listBookshelf().single().chapterIndex)
        } finally {
            db?.close()
            listOf(zipPath, Path.of(target), Path.of("$target-wal"), Path.of("$target-shm"))
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
