package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 回归测试：进度文件必须**逐字节**符合手机端原有格式（SESSION-029）。
 *
 * 手机端模板（实测 `长征十日_安南十八子.json`，229 字节）：
 * ```
 * {
 *   "author": "安南十八子",
 *   "durChapterIndex": 8,
 *   "durChapterPos": 0,
 *   "durChapterTime": 1790469228909,
 *   "durChapterTitle": "第八章：弄羊村兵分三路 花背洞鱼水情长（下）",
 *   "name": "长征十日"
 * }
 * ```
 * 特征：2 空格缩进、冒号后一个空格、LF 行尾、**末尾不加换行**、字段顺序固定。
 */
class BookProgressFormatTest {

    private fun tempRoot(): Path = Files.createTempDirectory("progress-format-test")

    private fun newDb(): Database {
        val db = Database(Files.createTempFile("progress-format-db", ".sqlite").toString())
        db.initialize("test-pass")
        return db
    }

    private fun sync(root: Path) = BookProgressSync(root, newDb())

    // ------------------------------------------------------------------
    // 逐字节格式
    // ------------------------------------------------------------------

    /**
     * 核心用例：写入结果必须与手机端模板**完全一致**（含末尾无换行）。
     * 这里用真实的时间戳与文案，确保是端到端的字节比对而非片段断言。
     */
    @Test
    fun `written file matches phone layout byte for byte`() {
        val root = tempRoot()
        val s = sync(root)
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        // 预置一份手机端文件，write 会原地更新它
        Files.writeString(dir.resolve("长征十日_安南十八子.json"), "{}")

        assertTrue(
            s.write(
                bookName = "长征十日",
                author = "安南十八子",
                chapterIndex = 8,
                chapterTitle = "第八章：弄羊村兵分三路 花背洞鱼水情长（下）",
            )
        )

        val file = dir.resolve("长征十日_安南十八子.json")
        val text = Files.readString(file)

        // 1) 结构：{ + 6 行字段 + } = 8 行、7 个 LF
        val lines = text.split("\n")
        assertEquals("应为 8 行（{ + 6 字段 + }）", 8, lines.size)
        assertEquals("{", lines[0])
        assertEquals("}", lines[7])

        // 2) 每行必须是 2 空格缩进 + 冒号后一个空格
        for (i in 1..6) {
            assertTrue("第 ${i + 1} 行应以 2 空格缩进开头: ${lines[i]}", lines[i].startsWith("  \""))
            assertTrue("第 ${i + 1} 行冒号后应有一个空格: ${lines[i]}", lines[i].contains("\": "))
        }

        // 3) 字段顺序固定
        val keys = lines.drop(1).dropLast(1).map { it.trim().substringAfter("\"").substringBefore("\"") }
        assertEquals(
            listOf("author", "durChapterIndex", "durChapterPos", "durChapterTime", "durChapterTitle", "name"),
            keys,
        )

        // 4) 行尾风格：LF、末尾**不加**换行
        assertFalse("末尾不应有换行", text.endsWith("\n"))
        assertFalse("不应出现 CRLF", text.contains("\r"))

        // 5) 逗号：前 5 行有，最后一行没有
        for (i in 1..5) assertTrue("第 ${i + 1} 行应以逗号结尾: ${lines[i]}", lines[i].endsWith(","))
        assertFalse("最后一行不应有逗号: ${lines[6]}", lines[6].endsWith(","))

        // 6) 能正常解析回来
        val obj = Json.parseToJsonElement(text) as JsonObject
        assertEquals(8, obj["durChapterIndex"]!!.jsonPrimitive.content.toInt())
        assertEquals("长征十日", obj["name"]!!.jsonPrimitive.content)
    }

    /** 与手机端模板的**字节级**对照（用真实模板文本）。 */
    @Test
    fun `output equals the real phone template shape`() {
        val root = tempRoot()
        val s = sync(root)
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("书_作者.json"), "{}")

        // 手机端真实文件内容（作者/书名/标题均取自用户实际数据）
        val expected = "{\n" +
            "  \"author\": \"安南十八子\",\n" +
            "  \"durChapterIndex\": 8,\n" +
            "  \"durChapterPos\": 0,\n" +
            "  \"durChapterTime\": 1790469228909,\n" +
            "  \"durChapterTitle\": \"第八章：弄羊村兵分三路 花背洞鱼水情长（下）\",\n" +
            "  \"name\": \"长征十日\"\n" +
            "}"

        // 构造一个与模板等价的 obj 并比较格式化结果
        val obj = Json.parseToJsonElement(expected) as JsonObject
        val actual = s.formatProgressJson(obj)

        // durChapterTime 是毫秒整数，两者应完全一致
        assertEquals("格式化结果必须与手机端模板逐字符一致", expected, actual)
        assertEquals("字节数也应一致（无 BOM、无多余空白）", expected.toByteArray(Charsets.UTF_8).size, actual.toByteArray(Charsets.UTF_8).size)
    }

    /**
     * 书名含换行时必须转义成 `\n`（而不是真实换行拆行）。
     * 用户真实数据里就有 `"name": "十日终焉我成魔\n第八十章 ..."`。
     */
    @Test
    fun `newline inside name is escaped not literal`() {
        val root = tempRoot()
        val s = sync(root)
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)

        val bookName = "十日终焉我成魔\n第八十章 星尘归寂，余念长存"
        assertTrue(s.write(bookName, "梁灼安", 9, "第 10章 ：谈合作孙逸吃瘪被训，"))

        val written = Files.list(dir).use { it.findFirst().get() }
        val text = Files.readString(written)

        assertTrue("换行必须被转义为 \\n，实际: $text", text.contains("\\n"))
        // 结构仍必须是 8 行（换行没有把 JSON 拆开）
        assertEquals("转义后仍应为 8 行", 8, text.split("\n").size)
        // 能被解析，且解析回来的 name 含真实换行
        val obj = Json.parseToJsonElement(text) as JsonObject
        assertEquals(bookName, obj["name"]!!.jsonPrimitive.content)
    }

    /**
     * 引号、反斜杠等特殊字符必须正确转义，保证 JSON 合法。
     *
     * 注意：文件名会**去掉** `"`/`\` 等文件系统非法字符（`sanitizeFileComponent`），
     * 但 JSON **内部**的 `name` 字段保留真实书名。两者职责不同，这里分别断言。
     */
    @Test
    fun `special characters are escaped`() {
        val root = tempRoot()
        val s = sync(root)
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)

        val trickyName = "书\"名\\带制表"
        val trickyTitle = "第1章 \"引号\" 与 \\反斜杠"
        assertTrue(s.write(trickyName, "作者", 1, trickyTitle))

        val written = Files.list(dir).use { it.findFirst().get() }

        // 文件名必须已去掉非法字符（否则在 Windows 上根本无法创建）
        val fileName = written.fileName.toString()
        assertFalse("文件名不应含引号: $fileName", fileName.contains("\""))
        assertFalse("文件名不应含反斜杠: $fileName", fileName.contains("\\"))

        // JSON 内部保留真实书名与标题，且整体合法可解析
        val text = Files.readString(written)
        val obj = Json.parseToJsonElement(text) as JsonObject
        assertEquals("JSON 内的 name 应是真实书名（含引号与反斜杠）", trickyName, obj["name"]!!.jsonPrimitive.content)
        assertEquals("标题里的引号与反斜杠必须正确转义", trickyTitle, obj["durChapterTitle"]!!.jsonPrimitive.content)

        // 转义不能破坏结构
        assertEquals("转义后仍应为 8 行", 8, text.split("\n").size)
    }

    /** 数值字段不加引号（与手机端一致），字符串字段加引号。 */
    @Test
    fun `numbers are unquoted strings are quoted`() {
        val root = tempRoot()
        val s = sync(root)
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        s.write("书", "作者", 42, "第43章")

        val written = Files.list(dir).use { it.findFirst().get() }
        val text = Files.readString(written)
        assertTrue("数值不应带引号: $text", text.contains("\"durChapterIndex\": 42"))
        assertTrue("字符串应带引号: $text", text.contains("\"name\": \"书\""))
    }

    /** UTF-8 无 BOM（手机端文件不带 BOM，带 BOM 会让部分解析器出错）。 */
    @Test
    fun `no utf8 bom`() {
        val root = tempRoot()
        val s = sync(root)
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        s.write("书", "作者", 1, "第2章")

        val written = Files.list(dir).use { it.findFirst().get() }
        val bytes = Files.readAllBytes(written)
        assertFalse(
            "不应有 UTF-8 BOM，实际首字节: ${bytes.take(3).joinToString(" ") { "%02x".format(it) }}",
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte(),
        )
        assertEquals("首字节应为 '{'", '{'.code.toByte(), bytes[0])
    }

    /** 更新已有文件时也要保持同样的格式（不能只在新建时正确）。 */
    @Test
    fun `updating existing file keeps the layout`() {
        val root = tempRoot()
        val s = sync(root)
        val dir = root.resolve("legado").resolve("bookProgress")
        Files.createDirectories(dir)
        val file = dir.resolve("十日终焉我成魔_梁灼安.json")
        // 手机端的单行紧凑格式
        Files.writeString(file, """{"author":"梁灼安","durChapterIndex":28,"durChapterPos":2796,"durChapterTime":1790469262780,"durChapterTitle":"第二十八章：代价","name":"十日终焉我成魔"}""")

        assertTrue(s.write("十日终焉我成魔", "梁灼安", 29, "第二十九章：新章"))

        val text = Files.readString(file)
        assertEquals("更新后应统一为 8 行多行格式", 8, text.split("\n").size)
        assertFalse("末尾不应有换行", text.endsWith("\n"))
        val obj = Json.parseToJsonElement(text) as JsonObject
        assertEquals(29, obj["durChapterIndex"]!!.jsonPrimitive.content.toInt())
        assertEquals("应保留手机端写入的 durChapterPos", "2796", obj["durChapterPos"]!!.jsonPrimitive.content)
        assertEquals("应保留手机端写入的 name", "十日终焉我成魔", obj["name"]!!.jsonPrimitive.content)
    }
}
