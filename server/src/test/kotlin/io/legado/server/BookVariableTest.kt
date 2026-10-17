package io.legado.server

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * 书籍变量（Legado `Book.variable` / `book.getVariable()`）。
 *
 * 这组用例锁定的事实是**这项能力一直是缺的**（不是在发明新东西）：
 * `JsExecutionContext.bookVariables` 早已存在、沙箱也早已导出 `book.getVariable()`，
 * 但**从来没有任何存储或路由往里填** ⇒ 永远返回空值。代价最大的一处正是听书音色：
 * `🍅大灰狼聚合` 源用 `book.getVariable('custom')` 取音色编号，它自带的说明写着
 * 「详情页会显示本书可用音色及数字编号，**右上角书籍变量填写编号**即可切换」——
 * 也就是说"切换朗读者"在服务端一直**静默失效**（填了没用，且不报错）。
 *
 * 语义逐条对齐开源阅读 `legado-with-MD3`：
 * - `Book.variable` = 整张变量表的 JSON 字符串（`BaseBook.variable = GSON.toJson(variableMap)`）；
 * - `RuleDataInterface.getVariable(key)` = **键不存在返回空串**（不是 `"null"`）；
 * - `Book.putCustomVariable(value)` = 只写 `custom` 一个键，**其余键原样保留**。
 */
class BookVariableTest {

    private var fetchedUrl: String = ""
    private val html = "<html><body>正文正文正文正文正文</body></html>"

    /** 注入式接缝：书源 JS 的 `java.ajax(...)` 与正文抓取都走它，绝不触网。 */
    private fun runner(database: Database? = null): RuleRunner {
        fetchedUrl = ""
        return RuleRunner(responseFetcher = { url -> fetchedUrl = url; html }, database = database)
    }

    private fun withDatabase(block: (Database) -> Unit) {
        val path = Files.createTempFile("legado-book-variable", ".sqlite").toString()
        try {
            val database = Database(path)
            database.initialize("password-for-test")
            block(database)
            database.close()
        } finally {
            // Windows 上 SQLite 的 WAL/句柄偶尔仍被占用（本机既有的 52 个同类失败同源），
            // 清理失败**不能**改变用例结论 —— 这里只清理临时文件，不承载任何断言。
            listOf(path, "$path-wal", "$path-shm").forEach { candidate ->
                runCatching { Files.deleteIfExists(java.nio.file.Path.of(candidate)) }
            }
        }
    }

    private fun seedBook(database: Database) {
        database.saveBookshelf(
            BookshelfWriteRequest(
                sourceId = SOURCE,
                bookUrl = BOOK,
                name = "十日终焉",
                author = "杀虫队队员",
                tocUrl = "$BOOK/toc",
            ),
            cover = null,
        )
    }

    // ------------------------------------------------------------------
    // 1. 存储层：JSON 表读写 + 容错
    // ------------------------------------------------------------------

    @Test
    fun `stores and reads the whole variable table as json`() {
        withDatabase { database ->
            seedBook(database)

            assertTrue(database.saveBookVariables(SOURCE, BOOK, mapOf("custom" to "3", "线路" to "B")))

            assertEquals("""{"custom":"3","线路":"B"}""", database.getBookVariable(SOURCE, BOOK))
            assertEquals(mapOf("custom" to "3", "线路" to "B"), database.getBookVariables(SOURCE, BOOK))
        }
    }

    @Test
    fun `an empty table is stored as null so book variable stays falsy in js`() {
        withDatabase { database ->
            seedBook(database)
            database.saveBookVariables(SOURCE, BOOK, mapOf("custom" to "3"))

            database.saveBookVariables(SOURCE, BOOK, emptyMap())

            // 书源里有 `if (!book.variable)` 这类判空（大灰狼源判"缺少必要参数"就靠它），
            // 所以"没有变量"必须是 null/空串，而不是 "{}"。
            assertNull(database.getBookVariable(SOURCE, BOOK))
            assertTrue(database.getBookVariables(SOURCE, BOOK).isEmpty())
        }
    }

    @Test
    fun `corrupted json degrades to an empty table instead of breaking the shelf`() {
        withDatabase { database ->
            seedBook(database)
            // 模拟手改库 / 旧版本写坏的数据
            database.saveBookVariable(SOURCE, BOOK, "{not json at all")
            assertTrue(database.getBookVariables(SOURCE, BOOK).isEmpty())

            // 非字符串值：**数字按字符串读入** —— Gson 把 JSON number 读进 `Map<String,String>`
            // 时同样会走 nextString()，所以 `3` → `"3"` 才是与 Legado 一致的行为；
            // `null` 值丢弃（Legado 的表里不该有 null 值）。
            database.saveBookVariable(SOURCE, BOOK, """{"custom":3,"ok":"yes","gone":null}""")
            assertEquals(mapOf("custom" to "3", "ok" to "yes"), database.getBookVariables(SOURCE, BOOK))

            // 顶层不是对象（数组/字符串）同样退化为空表，绝不抛
            database.saveBookVariable(SOURCE, BOOK, "[1,2,3]")
            assertTrue(database.getBookVariables(SOURCE, BOOK).isEmpty())

            // 脏值不能把书架查询带崩（toShelf 走的是同一列）
            assertEquals(1, database.listBookshelf().size)
        }
    }

    @Test
    fun `custom variable merge keeps every other key`() {
        withDatabase { database ->
            seedBook(database)
            database.saveBookVariables(SOURCE, BOOK, mapOf("custom" to "1", "线路" to "B"))

            database.saveCustomVariable(SOURCE, BOOK, "7")

            // 这是「编辑音色」最怕的回归：顺手把用户别的变量清掉
            assertEquals(mapOf("custom" to "7", "线路" to "B"), database.getBookVariables(SOURCE, BOOK))
        }
    }

    @Test
    fun `shelf queries expose the variable column`() {
        withDatabase { database ->
            seedBook(database)
            database.saveBookVariables(SOURCE, BOOK, mapOf("custom" to "5"))

            // 「表里有」不等于「接口能拿到」——三处 SELECT 都必须带上这一列
            assertEquals("""{"custom":"5"}""", database.listBookshelf().single().variable)
            assertEquals("""{"custom":"5"}""", database.getShelfBookByUrl(BOOK)!!.variable)
        }
    }

    // ------------------------------------------------------------------
    // 2. 编辑书籍信息：customVariable 的三种语义
    // ------------------------------------------------------------------

    @Test
    fun `info update writes custom variable only when explicitly provided`() {
        withDatabase { database ->
            seedBook(database)
            database.saveBookVariables(SOURCE, BOOK, mapOf("custom" to "3", "线路" to "B"))

            // ① 不传 ⇒ 绝不动它（"只改书名点保存"不能清掉音色）
            database.updateBookshelfInfo(infoRequest(name = "改个名"), cover = null)
            assertEquals(mapOf("custom" to "3", "线路" to "B"), database.getBookVariables(SOURCE, BOOK))

            // ② 传值 ⇒ 写 custom，其余键保留
            database.updateBookshelfInfo(infoRequest(name = "改个名", customVariable = "9"), cover = null)
            assertEquals(mapOf("custom" to "9", "线路" to "B"), database.getBookVariables(SOURCE, BOOK))

            // ③ 传空串 ⇒ 清空 custom（读出来与"没有这个键"等价）
            database.updateBookshelfInfo(infoRequest(name = "改个名", customVariable = ""), cover = null)
            assertEquals("", database.getBookVariables(SOURCE, BOOK)["custom"])
        }
    }

    private fun infoRequest(name: String, customVariable: String? = null) = BookshelfInfoUpdateRequest(
        sourceId = SOURCE,
        bookUrl = BOOK,
        name = name,
        customVariable = customVariable,
    )

    // ------------------------------------------------------------------
    // 3. 沙箱：书源 JS 真正读到值（端到端，注入响应不触网）
    // ------------------------------------------------------------------

    @Test
    fun `source js reads the stored custom variable through book getVariable`() {
        withDatabase { database ->
            seedBook(database)
            database.saveBookVariables(SOURCE, BOOK, mapOf("custom" to "6"))

            // 规则把音色编号拼进请求 URL —— 真实书源就是这么用它来决定朗读者/线路的
            val content = runner(database).content(
                audioSource("""<js>java.ajax("https://api.example.com/audio?tone=" + book.getVariable('custom'))</js>"""),
                chapterUrl = "$SOURCE/chapter/1",
                bookName = "十日终焉",
                book = database.bookContext(SOURCE, BOOK),
            )

            assertEquals("沙箱必须把落库的 custom 交给书源 JS", "https://api.example.com/audio?tone=6", fetchedUrl)
            assertTrue(content.content.isNotEmpty())
        }
    }

    @Test
    fun `missing variable reads as empty string not the literal null`() {
        runner().content(
            audioSource(
                """<js>java.ajax("https://api.example.com/x?t=" + book.getVariable('nope') + "&v=" + (book.variable === '' ? 'empty' : 'nonempty') + "&m=" + (typeof book.variableMap))</js>""",
            ),
            chapterUrl = "$SOURCE/chapter/1",
            bookName = null,
            book = BookContext(SOURCE, BOOK, variables = mutableMapOf()),
        )

        // 对齐 `RuleDataInterface.getVariable`：`variableMap[key] ?: ""`
        assertEquals(
            "缺失键必须给空串；空变量表时 book.variable 也必须是空串（书源用 if(!book.variable) 判空）",
            "https://api.example.com/x?t=&v=empty&m=object",
            fetchedUrl,
        )
    }

    @Test
    fun `book exposes name author kind and json variable map`() {
        val book = BookContext(
            sourceId = SOURCE,
            bookUrl = BOOK,
            name = "十日终焉",
            author = "杀虫队队员",
            kind = "audio",
            variables = mutableMapOf("custom" to "3"),
        )
        runner().content(
            audioSource(
                """<js>java.ajax("https://api.example.com/x?n=" + book.name + "&a=" + book.author + "&k=" + book.kind + "&t=" + book.type + "&v=" + book.variable + "&c=" + book.variableMap['custom'])</js>""",
            ),
            chapterUrl = "$SOURCE/chapter/1",
            bookName = null,
            book = book,
        )

        assertEquals(
            "book 上下文（含 type=1 音频、variable JSON、variableMap 取值）必须完整可见",
            """https://api.example.com/x?n=十日终焉&a=杀虫队队员&k=audio&t=1&v={"custom":"3"}&c=3""",
            fetchedUrl,
        )
    }

    @Test
    fun `rules may write variables back and the caller persists them`() {
        withDatabase { database ->
            seedBook(database)
            val book = database.bookContext(SOURCE, BOOK)
            runner().content(
                audioSource("""<js>book.putVariable('custom', '42'); java.ajax("https://api.example.com/x")</js>"""),
                chapterUrl = "$SOURCE/chapter/1",
                bookName = null,
                book = book,
            )

            // Legado 里 `book.putVariable(...)` 会随 saveBook 落库；这里由调用方在规则跑完后比对写回
            database.persistBookVariables(book)
            assertEquals("42", database.getBookVariables(SOURCE, BOOK)["custom"])

            // 值没变时不该产生无谓写库（正文/目录是高频路径）
            database.persistBookVariables(book)
            assertEquals("42", database.getBookVariables(SOURCE, BOOK)["custom"])
        }
    }

    // ------------------------------------------------------------------
    // 4. 备份：variable 必须跨设备带得走
    // ------------------------------------------------------------------

    @Test
    fun `import keeps the variable field and export writes it back`() {
        withDatabase { database ->
            database.importLibrary(
                listOf(
                    BackupShelfEntry(
                        sourceId = SOURCE,
                        bookUrl = BOOK,
                        name = "十日终焉",
                        author = "杀虫队队员",
                        tocUrl = "$BOOK/toc",
                        coverUrl = null,
                        completed = false,
                        chapterIndex = 3,
                        readAt = 1_780_000_000_000L,
                        variable = """{"custom":"6"}""",
                    ),
                ),
            )

            assertEquals(
                "导入备份必须带走书籍变量（否则换设备就丢音色设置）",
                mapOf("custom" to "6"),
                database.getBookVariables(SOURCE, BOOK),
            )

            // 导出侧同样要带上，否则"导入有了、再导出又没了"
            val entries = LinkedHashMap<String, String>()
            ZipInputStream(BackupExporter(database).export("CD_Watch_A", java.time.LocalDate.of(2026, 10, 16)).bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            val shelfJson = entries.getValue("bookshelf.json")
            assertTrue(
                "导出的 bookshelf.json 必须含 variable 字段：$shelfJson",
                shelfJson.contains("\"variable\": \"{\\\"custom\\\":\\\"6\\\"}\""),
            )
        }
    }

    @Test
    fun `re-importing a backup never wipes locally edited variables`() {
        withDatabase { database ->
            seedBook(database)
            database.saveCustomVariable(SOURCE, BOOK, "9")

            // 备份里这本书**没有** variable（老备份 / 从没设置过）⇒ 必须保留本机刚改的值
            database.importLibrary(
                listOf(
                    BackupShelfEntry(
                        sourceId = SOURCE,
                        bookUrl = BOOK,
                        name = "十日终焉",
                        author = "杀虫队队员",
                        tocUrl = "$BOOK/toc",
                        coverUrl = null,
                        completed = false,
                        chapterIndex = 1,
                        readAt = 1_780_000_000_000L,
                        variable = null,
                    ),
                ),
            )

            assertEquals("""{"custom":"9"}""", database.getBookVariable(SOURCE, BOOK))
        }
    }

    // ------------------------------------------------------------------

    /** 一个"正文规则就是那段 JS"的极简听书书源：`java.ajax` 的返回值即正文。 */
    private fun audioSource(contentRule: String) = """
        {
          "bookSourceName": "测试听书",
          "bookSourceUrl": "$SOURCE",
          "bookSourceType": 1,
          "ruleContent": { "content": ${JsonPrimitive(contentRule)} }
        }
    """.trimIndent()

    private companion object {
        const val SOURCE = "大灰狼融合VIP5.0"
        const val BOOK = "https://api.example.com/book/1"
    }
}
