package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 回归测试：手机端进度文件夹（bookProgress）双向同步。
 *
 * 用例全部基于**真实数据形态**（SESSION-028）：
 * ```json
 * { "author": "梁灼安", "durChapterIndex": 4, "durChapterPos": 0,
 *   "durChapterTime": 1790465002215,
 *   "durChapterTitle": "第5 章 ：再考心智我甘心受辱",
 *   "name": "十日终焉我成魔\n第八十章 星尘归寂，余念长存" }
 * ```
 */
class BookProgressSyncTest {

    private fun tempRoot(): Path = Files.createTempDirectory("progress-sync-test")

    private fun newDb(): Database {
        val db = Database(Files.createTempFile("progress-sync-db", ".sqlite").toString())
        db.initialize("test-pass")
        return db
    }

    private fun sync(root: Path, db: Database = newDb()) = BookProgressSync(root, db)

    // ------------------------------------------------------------------
    // 路径安全
    // ------------------------------------------------------------------

    /**
     * 文件夹名允许多级（手机端备份自带 `legado` 层级），
     * 但任何试图逃出 WebDAV 根的输入都要被拒绝并回退默认值。
     */
    @Test
    fun `directory name rejects path traversal attempts`() {
        val s = sync(tempRoot())
        val hostile = listOf(
            "../..",
            "../../etc",
            "..",
            "a/../../b",
            "legado/../../etc",
            "C:/Windows",
            "legado/bookProgress\u0000evil",
            "legado/\u0000",
            "a/b/c/d/e/f",
        )
        for (bad in hostile) {
            assertEquals("恶意输入应回退到默认值: $bad", BookProgressSync.DEFAULT_DIRECTORY, s.sanitizeDirectoryName(bad))
        }
    }

    /**
     * 最关键的安全断言：**不论输入什么**，解析出的目录都必须落在 WebDAV 根之内。
     * 这是防路径穿越的最终防线（即便清洗逻辑将来被改坏，这条也必须成立）。
     */
    @Test
    fun `resolved directory never escapes webdav root for any input`() {
        val root = tempRoot().toAbsolutePath().normalize()
        val db = newDb()
        val s = BookProgressSync(root, db)
        val inputs = listOf(
            "../../etc", "..", "a/../../b", "/etc/passwd", "C:/Windows",
            "legado/bookProgress", "bookProgress", "\u0000", "nn/../../..",
        )
        for (input in inputs) {
            db.setSetting(BookProgressSync.SETTING_KEY, input)
            val name = s.directoryName()
            val resolved = root.resolve(name).normalize()
            assertTrue("输入 '$input' 解析成了根之外的路径: $resolved", resolved.startsWith(root))
        }
    }

    /** 正常名字要保留（含多级、中文与常见符号）。 */
    @Test
    fun `directory name keeps legitimate values`() {
        val s = sync(tempRoot())
        assertEquals("legado/bookProgress", s.sanitizeDirectoryName("legado/bookProgress"))
        assertEquals("bookProgress", s.sanitizeDirectoryName("bookProgress"))
        assertEquals("进度", s.sanitizeDirectoryName("进度"))
        assertEquals("my-progress_2", s.sanitizeDirectoryName("my-progress_2"))
        assertEquals("legado/bookProgress", s.sanitizeDirectoryName("  legado/bookProgress  "))
        // 反斜杠写法归一成正斜杠（Windows 用户会这么填）
        assertEquals("legado/bookProgress", s.sanitizeDirectoryName("legado\\bookProgress"))
        assertEquals(BookProgressSync.DEFAULT_DIRECTORY, s.sanitizeDirectoryName(""))
        // 超长名字回退默认，避免超出文件系统限制
        assertEquals(BookProgressSync.DEFAULT_DIRECTORY, s.sanitizeDirectoryName("x".repeat(200)))
    }

    /** 解析结果必须始终落在 WebDAV 根目录之内。 */
    @Test
    fun `resolved directory always stays inside webdav root`() {
        val root = tempRoot()
        val db = newDb()
        val s = BookProgressSync(root, db)
        db.setSetting(BookProgressSync.SETTING_KEY, "../../escape")
        val dir = s.directory()
        if (dir != null) {
            assertTrue("解析结果必须在根目录内: $dir", dir.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
        }
    }

    // ------------------------------------------------------------------
    // 读取真实文件
    // ------------------------------------------------------------------

    /** 读取真实格式的进度文件。 */
    @Test
    fun `reads real progress file`() {
        val root = tempRoot()
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("长征十日_安南十八子.json"),
            """
            {
              "author": "安南十八子",
              "durChapterIndex": 0,
              "durChapterPos": 0,
              "durChapterTime": 1790465067186,
              "durChapterTitle": "第一章：飞筑城坐镇指挥 谋贵州一箭双雕(下）",
              "name": "长征十日"
            }
            """.trimIndent(),
        )
        val s = sync(root)
        val p = s.read("长征十日", "安南十八子")
        assertNotNull("应读取到进度文件", p)
        assertEquals(0, p!!.chapterIndex)
        assertEquals("第一章：飞筑城坐镇指挥 谋贵州一箭双雕(下）", p.chapterTitle)
        assertEquals(1790465067186L, p.updatedAt)
    }

    /**
     * 书名含换行时，手机端会把它去掉再命名。
     * 直接用原始书名拼文件名会找不到 —— 这里锁定「归一化匹配」兜底有效。
     */
    @Test
    fun `finds file when book name contains newline`() {
        val root = tempRoot()
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        // 手机端去掉换行后的文件名
        Files.writeString(
            dir.resolve("十日终焉我成魔第八十章 星尘归寂，余念长存_梁灼安.json"),
            """{"author":"梁灼安","durChapterIndex":4,"durChapterTitle":"第5 章 ：再考心智我甘心受辱","durChapterTime":1790465002215,"name":"十日终焉我成魔\n第八十章 星尘归寂，余念长存"}""",
        )
        val s = sync(root)
        val p = s.read("十日终焉我成魔\n第八十章 星尘归寂，余念长存", "梁灼安")
        assertNotNull("书名含换行时也必须能找到文件", p)
        assertEquals(4, p!!.chapterIndex)
    }

    /** 文件不存在返回 null，不抛异常。 */
    @Test
    fun `missing file returns null`() {
        val s = sync(tempRoot())
        assertNull(s.read("不存在的书", "某人"))
    }

    /**
     * 书名含换行时，`Path.resolve(name)` 会抛 `InvalidPathException`（**不是**返回不存在的路径）。
     *
     * 这是本功能最隐蔽的一个坑：若不在候选循环里 try/catch，
     * **第一个未清洗的候选就会中断整个查找**，后面真正匹配的候选永远试不到，
     * 表现为「明明文件就在那儿却读不到」。
     */
    @Test
    fun `lookup survives invalid characters in book name`() {
        val root = tempRoot()
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("正常书名_作者.json"),
            """{"durChapterIndex":2,"durChapterTitle":"第3章","durChapterTime":9}""",
        )
        val s = sync(root)
        // 这些名字会让 resolve 抛异常或产生非法路径，但都不能让查找崩掉
        for (hostile in listOf("正常书名", "书\n名", "书\u0000名", "a\r\nb")) {
            val result = runCatching { s.read(hostile, "作者") }
            assertTrue("读取 '$hostile' 不应抛异常，实际：${result.exceptionOrNull()}", result.isSuccess)
        }
        // 正常那个必须仍然能读到
        assertEquals(2, s.read("正常书名", "作者")!!.chapterIndex)
    }

    /** 目录不存在（用户还没配）时返回 null 而不是崩溃。 */
    @Test
    fun `missing directory returns null`() {
        val s = sync(tempRoot())
        assertNull(s.directory())
        assertNull(s.read("任意", null))
    }

    /** 损坏的 JSON 不能抛异常（手机端可能写入半截文件）。 */
    @Test
    fun `corrupt json does not throw`() {
        val root = tempRoot()
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("坏书_作者.json"), "{ this is not json")
        val s = sync(root)
        assertNull(s.read("坏书", "作者"))
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 写回后必须能被重新读出，且时间戳更新。 */
    @Test
    fun `write then read round trips`() {
        val root = tempRoot()
        val s = sync(root)
        assertTrue(s.write("测试书", "测试作者", 7, "第8章 试炼"))

        val p = s.read("测试书", "测试作者")
        assertNotNull(p)
        assertEquals(7, p!!.chapterIndex)
        assertEquals("第8章 试炼", p.chapterTitle)
        assertTrue("写入时应刷新时间戳", p.updatedAt > 0)
    }

    /** 更新已有文件时不能覆盖手机端写入的 name/author。 */
    @Test
    fun `write preserves existing name and author from phone`() {
        val root = tempRoot()
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        val file = dir.resolve("十日终焉_杀虫队队员.json")
        Files.writeString(file, """{"author":"杀虫队队员","durChapterIndex":1,"durChapterPos":33,"durChapterTitle":"第2章 旧","durChapterTime":1,"name":"十日终焉"}""")

        val s = sync(root)
        assertTrue(s.write("十日终焉", "杀虫队队员", 5, "第6章 新"))

        val obj = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        assertEquals("手机端写入的 name 必须保留", "十日终焉", obj["name"]!!.jsonPrimitive.content)
        assertEquals("手机端写入的 author 必须保留", "杀虫队队员", obj["author"]!!.jsonPrimitive.content)
        assertEquals(5, obj["durChapterIndex"]!!.jsonPrimitive.content.toInt())
        assertEquals("第6章 新", obj["durChapterTitle"]!!.jsonPrimitive.content)
        assertEquals("durChapterPos 应沿用原值", "33", obj["durChapterPos"]!!.jsonPrimitive.content)
    }

    /** 目标目录不存在时应自动创建（用户刚配好还没同步过）。 */
    @Test
    fun `write creates directory when absent`() {
        val root = tempRoot()
        val s = sync(root)
        assertFalse(Files.exists(root.resolve("legado").resolve("bookProgress")))
        assertTrue(s.write("新书", "作者", 0, "第1章"))
        assertTrue(Files.exists(root.resolve("legado").resolve("bookProgress")))
    }

    /** 写入必须原子（临时文件不残留）。 */
    @Test
    fun `write leaves no temp files`() {
        val root = tempRoot()
        val s = sync(root)
        s.write("书", "作者", 1, "第2章")
        val leftovers = Files.list(root.resolve("legado").resolve("bookProgress")).use { st -> st.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        assertEquals(0, leftovers)
    }

    // ------------------------------------------------------------------
    // 章节对齐（核心逻辑）
    // ------------------------------------------------------------------

    private fun chapters(vararg titles: String) =
        titles.mapIndexed { i, t -> Chapter(i, t, "https://e.test/c/$i") }

    /** 下标与标题都吻合 → 直接采用该下标。 */
    @Test
    fun `align uses index when title matches`() {
        val s = sync(tempRoot())
        val list = chapters("第1章 起点", "第2章 试炼", "第3章 终局")
        assertEquals(1, s.alignChapter(list, 1, "第2章 试炼"))
    }

    /** 真实场景：手机端标题是「第5 章 ：…」，书源是「第5章：…」——空白/标点差异必须容忍。 */
    @Test
    fun `align tolerates whitespace and punctuation differences`() {
        val s = sync(tempRoot())
        val list = chapters("第1章 起点", "第2章 试炼", "第5章：再考心智我甘心受辱")
        val idx = s.alignChapter(list, 2, "第5 章 ：再考心智我甘心受辱")
        assertEquals("规范化后应能对上，不该被下标带偏", 2, idx)
    }

    /** 下标指向的标题不符时，应改按标题查找（换源后章节数不同）。 */
    @Test
    fun `align falls back to title when index points elsewhere`() {
        val s = sync(tempRoot())
        val list = chapters("甲", "乙", "丙", "第9章 目标")
        val idx = s.alignChapter(list, 0, "第9章 目标")
        assertEquals("应按标题找到真正的章节", 3, idx)
    }

    /** 全角数字/字母也要能对上。 */
    @Test
    fun `align normalizes fullwidth characters`() {
        val s = sync(tempRoot())
        val list = chapters("第１章 ＡＢＣ")
        assertEquals(0, s.alignChapter(list, 0, "第1章 ABC"))
    }

    /** 标题完全对不上时用下标兜底，并夹到合法范围。 */
    @Test
    fun `align clamps out of range index`() {
        val s = sync(tempRoot())
        val list = chapters("甲", "乙", "丙")
        assertEquals(2, s.alignChapter(list, 99, "完全不相干"))
        assertEquals(0, s.alignChapter(list, -5, "完全不相干"))
    }

    /** 空章节列表返回 null（调用方应回退数据库进度）。 */
    @Test
    fun `align returns null for empty chapters`() {
        val s = sync(tempRoot())
        assertNull(s.alignChapter(emptyList(), 3, "任意"))
    }

    /** 只有下标没有标题时，用下标。 */
    @Test
    fun `align works with index only`() {
        val s = sync(tempRoot())
        val list = chapters("甲", "乙", "丙")
        assertEquals(1, s.alignChapter(list, 1, null))
    }

    /** 包含关系应能对上（手机端标题可能带卷名）。 */
    @Test
    fun `align matches by containment`() {
        val s = sync(tempRoot())
        val list = chapters("第一卷 第3章 风起")
        assertEquals(0, s.alignChapter(list, null, "第3章 风起"))
    }

    // ------------------------------------------------------------------
    // 设置持久化
    // ------------------------------------------------------------------

    /** 目录名设置必须能持久化并即时生效。 */
    @Test
    fun `directory name setting persists`() {
        val root = tempRoot()
        val db = newDb()
        val s = BookProgressSync(root, db)
        assertEquals("默认应为 bookProgress", BookProgressSync.DEFAULT_DIRECTORY, s.directoryName())

        s.setDirectoryName("我的进度")
        assertEquals("我的进度", s.directoryName())

        // 重新构造（模拟重启）后仍应读到
        assertEquals("我的进度", BookProgressSync(root, db).directoryName())
    }

    /** 每次都写入恶意值也必须被清洗（不能因为存过一次就绕过校验）。 */
    @Test
    fun `hostile stored value is sanitized on read`() {
        val root = tempRoot()
        val db = newDb()
        db.setSetting(BookProgressSync.SETTING_KEY, "../../../etc")
        val s = BookProgressSync(root, db)
        assertEquals(BookProgressSync.DEFAULT_DIRECTORY, s.directoryName())
    }
}
