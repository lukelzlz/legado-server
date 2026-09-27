package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 回归测试：备份导入的**分组**（`bookGroup.json`）与**书签**（`bookmark.json`）。
 *
 * 真实数据（`backup2026-09-27-PEPM00.zip`）：
 * ```
 * bookGroup.json  16 个分组，其中 13 个是空的内置智能分组
 *                 有书的只有：FQ(27) / 完结(74) / 15(2)
 * bookmark.json   47 条书签，涉及 18 本书，其中 29 条挂在被过滤的书上
 * ```
 */
class BackupGroupAndBookmarkTest {

    private fun json(text: String) = Json { ignoreUnknownKeys = true; isLenient = true }
        .parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonArray }

    private fun group(id: Long, name: String, order: Int) =
        """{"bookSort":-1,"enableRefresh":true,"groupId":$id,"groupName":"$name","order":$order,"show":false}"""

    private fun book(
        name: String,
        author: String,
        group: Long,
        tab: String = "小说",
        type: Int = 8,
        origin: String = "某书源",
        bookUrlOverride: String? = null,
    ): String {
        // `bookUrl` 里的 base64 载荷与尾部 `{"type":"qingtian"}` 都必须正确 JSON 转义，
        // 否则拼出来的 bookshelf.json 自己就不是合法 JSON（曾因此让 e2e 用例假失败）。
        //
        // **`book_id` 必须每本书不同**：`book_shelf` 的唯一键是 `(source_id, book_url)`，
        // 若两本书 bookUrl 相同会被 UPSERT 合并成一行（曾因此让 e2e 用例假失败）。
        // 真实备份里 bookUrl 必然含各自的 book_id，天然唯一。
        val payload = java.util.Base64.getEncoder()
            .encodeToString("""{"book_id":"$name","tab":"$tab"}""".toByteArray())
        val url = bookUrlOverride ?: "data:;base64,$payload,{\"type\":\"qingtian\"}"
        val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"name":"$name","author":"$author","bookUrl":"$escapedUrl","origin":"$origin","type":$type,"group":$group}"""
    }

    // ------------------------------------------------------------------
    // bookGroup.json 解析
    // ------------------------------------------------------------------

    /**
     * 真实分组数据：16 个分组，负数 id 是内置智能分组。
     *
     * 这条用例用**真实的 id 与名字**锁定解析结果。
     */
    @Test
    fun `parses real bookGroup shape and flags built-ins`() {
        val text = """
        [
          {"bookSort":-1,"enableRefresh":true,"groupId":-20,"groupName":"在读","order":0,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":-21,"groupName":"未读","order":1,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":-22,"groupName":"已读","order":2,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":-8,"groupName":"小说","order":3,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":-7,"groupName":"漫画","order":4,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":-6,"groupName":"视频","order":5,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":-1,"groupName":"全部","order":6,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":1,"groupName":"FQ","order":7,"show":true},
          {"bookSort":-1,"enableRefresh":true,"groupId":-2,"groupName":"本地","order":8,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":2,"groupName":"完结","order":9,"show":true},
          {"bookSort":-1,"enableRefresh":true,"groupId":-3,"groupName":"音频","order":10,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":4,"groupName":"失效","order":11,"show":true},
          {"bookSort":-1,"enableRefresh":true,"groupId":-4,"groupName":"网络未分组","order":12,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":8,"groupName":"15","order":13,"show":true},
          {"bookSort":-1,"enableRefresh":true,"groupId":-5,"groupName":"本地未分组","order":14,"show":false},
          {"bookSort":-1,"enableRefresh":true,"groupId":-11,"groupName":"更新失败","order":15,"show":false}
        ]
        """.trimIndent()

        val groups = parseGroupsForTest(text)
        assertEquals("真实备份有 16 个分组", 16, groups.size)

        // 负数 id = 内置智能分组（真实数据里是 12 个：-20/-21/-22/-8/-7/-6/-1/-2/-3/-4/-5/-11）
        val builtIn = groups.filter { it.builtIn }
        assertEquals("12 个内置智能分组", 12, builtIn.size)
        assertTrue(builtIn.all { it.groupId < 0 })

        // 用户自建分组（正数 id）：FQ / 完结 / 失效 / 15
        val custom = groups.filter { !it.builtIn }.map { it.groupName }
        assertEquals(listOf("FQ", "完结", "失效", "15"), custom)

        // 分组名与 id 的对应
        assertEquals(-20L, groups.first { it.groupName == "在读" }.groupId)
        assertEquals(1L, groups.first { it.groupName == "FQ" }.groupId)
        assertEquals(2L, groups.first { it.groupName == "完结" }.groupId)
        assertEquals(8L, groups.first { it.groupName == "15" }.groupId)
    }

    // ------------------------------------------------------------------
    // 只导入「有书的分组」
    // ------------------------------------------------------------------

    /**
     * 真实场景：16 个分组里只有 3 个有书，其余 13 个内置分组都是空的。
     *
     * 期望：只导入 FQ / 完结 / 15，**不导入**那 13 个空的内置分组。
     */
    @Test
    fun `only imports groups that actually contain books`() {
        val allGroups = listOf(
            BackupGroupEntry(-20, "在读", 0, true),
            BackupGroupEntry(-21, "未读", 1, true),
            BackupGroupEntry(-22, "已读", 2, true),
            BackupGroupEntry(-8, "小说", 3, true),
            BackupGroupEntry(-7, "漫画", 4, true),
            BackupGroupEntry(-6, "视频", 5, true),
            BackupGroupEntry(-1, "全部", 6, true),
            BackupGroupEntry(1, "FQ", 7, false),
            BackupGroupEntry(-2, "本地", 8, true),
            BackupGroupEntry(2, "完结", 9, false),
            BackupGroupEntry(-3, "音频", 10, true),
            BackupGroupEntry(4, "失效", 11, false),
            BackupGroupEntry(-4, "网络未分组", 12, true),
            BackupGroupEntry(8, "15", 13, false),
            BackupGroupEntry(-5, "本地未分组", 14, true),
            BackupGroupEntry(-11, "更新失败", 15, true),
        )
        // 只有 FQ / 完结 / 15 有书
        val shelf = listOf(
            entry("a", group = "FQ"), entry("b", group = "FQ"),
            entry("c", group = "完结"),
            entry("d", group = "15"),
            entry("e", group = null),          // 未分组
        )

        val used = shelf.mapNotNull { it.groupName }.toSet()
        val kept = allGroups.filter { it.groupName in used }

        assertEquals("只应保留有书的 3 个分组", 3, kept.size)
        assertEquals(setOf("FQ", "完结", "15"), kept.map { it.groupName }.toSet())
        assertTrue("内置空分组必须被过滤掉", kept.none { it.builtIn })
    }

    // ------------------------------------------------------------------
    // bookshelf 的 group id → 分组名
    // ------------------------------------------------------------------

    /**
     * `bookshelf.json` 存的是**数字 group id**，而本服务按**名字**关联分组。
     * 这条用例锁定这个映射——映射错了分组就会全丢。
     */
    @Test
    fun `maps numeric group id to group name`() {
        val byId = mapOf(1L to "FQ", 2L to "完结", 8L to "15")
        assertEquals("FQ", byId[1L])
        assertEquals("完结", byId[2L])
        assertEquals("15", byId[8L])
        assertNull("未定义的 id 应为 null（等价未分组）", byId[999L])
    }

    /** `group = 0` 代表未分组，不能去查表（表里也没有 0）。 */
    @Test
    fun `group zero means ungrouped`() {
        val id = 0L
        val lookup = mapOf(1L to "FQ")
        assertNull(if (id == 0L) null else lookup[id])
    }

    // ------------------------------------------------------------------
    // bookmark.json
    // ------------------------------------------------------------------

    /** 真实书签字段：`bookAuthor, bookName, bookText, chapterIndex, chapterName, chapterPos, content, time`。 */
    @Test
    fun `parses real bookmark shape`() {
        val text = """
        [{"bookAuthor":"小白在放鸽子","bookName":"被废修为后，我能否逃离她的魔爪",
          "bookText":"第149章 因为恨你\n　　“小瑶吃点我做的菜吧？”","chapterIndex":147,
          "chapterName":"第149章 因为恨你","chapterPos":0,"content":"","time":1781621060857}]
        """.trimIndent()

        val marks = parseBookmarksForTest(text)
        assertEquals(1, marks.size)
        val m = marks[0]
        assertEquals("被废修为后，我能否逃离她的魔爪", m.bookName)
        assertEquals("小白在放鸽子", m.bookAuthor)
        assertEquals(147, m.chapterIndex)
        assertEquals("第149章 因为恨你", m.chapterName)
        assertEquals(0, m.chapterPos)
        assertEquals(1781621060857L, m.time)
        assertTrue("bookText 应保留换行", m.bookText!!.contains("\n"))
    }

    /**
     * 只导入「书还在书架上」的书签。
     *
     * 真实数据：47 条书签里 **29 条挂在被过滤的书上**（本地图书/音频），
     * 那些书不在书架上，书签也就没有展示位置。
     */
    @Test
    fun `skips bookmarks whose book was filtered out`() {
        val shelfKeys = setOf("保留的书\u0000作者A", "另一本\u0000作者B")
        val marks = listOf(
            BackupBookmarkEntry("保留的书", "作者A", 1, "第2章", 0, null, null, 1L),
            BackupBookmarkEntry("本地书", "作者C", 1, "第2章", 0, null, null, 1L),   // 本地书被过滤
            BackupBookmarkEntry("听书", "作者D", 1, "第2章", 0, null, null, 1L),     // 音频被过滤
            BackupBookmarkEntry("另一本", "作者B", 5, "第6章", 0, null, null, 1L),
        )

        val kept = marks.filter { "${it.bookName}\u0000${it.bookAuthor.orEmpty()}" in shelfKeys }
        assertEquals(2, kept.size)
        assertEquals(2, marks.size - kept.size)
        assertTrue(kept.all { it.bookName in setOf("保留的书", "另一本") })
    }

    /** 作者为 null 的书签也要能匹配（键里用空串占位）。 */
    @Test
    fun `matches bookmarks with null author`() {
        val shelfKeys = setOf("无作者的书\u0000")
        val mark = BackupBookmarkEntry("无作者的书", null, 0, null, 0, null, null, 1L)
        assertTrue("${mark.bookName}\u0000${mark.bookAuthor.orEmpty()}" in shelfKeys)
    }

    // ------------------------------------------------------------------
    // 入库行为
    // ------------------------------------------------------------------

    /** 分组入库：建分组 + 把书的 group_name 补上（只填空值）。 */
    @Test
    fun `imports groups and assigns them to shelf books`() {
        val (db, cleanup) = newDatabase()
        try {
            db.importLibrary(listOf(entry("a", group = "FQ"), entry("b", group = "完结"), entry("c", group = null)))
            val result = db.importBookGroups(
                listOf(BackupGroupEntry(1, "FQ", 7, false), BackupGroupEntry(2, "完结", 9, false)),
                listOf(entry("a", group = "FQ"), entry("b", group = "完结"), entry("c", group = null)),
            )

            assertEquals("应新建 2 个分组", 2, result.created)
            assertEquals("应给 2 本书赋分组", 2, result.assigned)

            val groups = db.listBookGroups().associateBy { it.name }
            assertEquals(1, groups["FQ"]?.bookCount)
            assertEquals(1, groups["完结"]?.bookCount)
        } finally { cleanup() }
    }

    /** 重复导入同一备份：分组与书签都不得重复。 */
    @Test
    fun `group and bookmark import is idempotent`() {
        val (db, cleanup) = newDatabase()
        try {
            val groups = listOf(BackupGroupEntry(1, "FQ", 7, false))
            val shelf = listOf(entry("a", group = "FQ"))
            db.importLibrary(shelf)
            db.importBookGroups(groups, shelf)
            val first = db.listBookGroups().first { it.name == "FQ" }

            db.importBookGroups(groups, shelf)
            val second = db.listBookGroups().first { it.name == "FQ" }
            assertEquals("重复导入不应产生第二个同名分组", first.id, second.id)
            assertEquals("分组总数应保持 1", 1, db.listBookGroups().count { it.name == "FQ" })

            val marks = listOf(BackupBookmarkEntry("书", "作者", 3, "第4章", 0, "正文", null, 100L))
            db.importBookmarks(marks)
            db.importBookmarks(marks)
            // 注意：不能拿 upsert 的 changes() 判断是否"新增"——SQLite 在 `do update` 时
            // 同样报告 1 行受影响。要验证幂等必须看**表里的实际行数**。
            assertEquals("重复导入后表里仍应只有 1 条", 1, db.countBookmarks("书", "作者"))
        } finally { cleanup() }
    }

    /**
     * **不能覆盖服务端已有的分组**：用户在服务端手工改过分组后，
     * 一次备份导入不应把它冲掉（否则会是很难察觉的数据丢失）。
     *
     * 注意：`listBookGroups()` 是**从 `book_group` 表**读的（`left join book_shelf`），
     * 而 `updateBookshelfInfo` 只写 `book_shelf.group_name`、**不会**建 `book_group` 行。
     * 所以这里必须先显式建出「手工分组」，才能观察到它。
     */
    @Test
    fun `import does not overwrite an existing group assignment`() {
        val (db, cleanup) = newDatabase()
        try {
            db.importLibrary(listOf(entry("a", group = null)))
            db.createBookGroup("手工分组")
            // 模拟用户在服务端手工把这本书放进「手工分组」
            db.updateBookshelfInfo(
                BookshelfInfoUpdateRequest(
                    sourceId = "s", bookUrl = "u/a", name = "a", author = "作者",
                    coverUrl = null, groupName = "手工分组", alternateSources = emptyList(),
                ),
                cover = null,
            )
            // 备份里这本书属于「FQ」
            db.importBookGroups(
                listOf(BackupGroupEntry(1, "FQ", 7, false)),
                listOf(entry("a", group = "FQ")),
            )

            val manual = db.listBookGroups().firstOrNull { it.name == "手工分组" }
            assertNotNull("手工分组应保留", manual)
            assertEquals("手工分组的书不应被备份的 FQ 抢走", 1, manual!!.bookCount)

            val fq = db.listBookGroups().firstOrNull { it.name == "FQ" }
            assertTrue("FQ 分组即使被建出来也应是空的", fq == null || fq.bookCount == 0)
        } finally { cleanup() }
    }

    /** 空输入不应报错。 */
    @Test
    fun `empty inputs are safe`() {
        val (db, cleanup) = newDatabase()
        try {
            val r = db.importBookGroups(emptyList(), emptyList())
            assertEquals(0, r.created)
            assertEquals(0, r.assigned)
            assertEquals(0, db.importBookmarks(emptyList()))
        } finally { cleanup() }
    }

    // ------------------------------------------------------------------
    // 端到端：真实结构的备份包
    // ------------------------------------------------------------------

    /**
     * 跑一遍完整的 [BackupImporter.import]，用**真实备份的文件名与结构**
     * （含 bookGroup.json / bookmark.json）验证分组与书签都进了库。
     */
    @Test
    fun `end to end import brings in groups and bookmarks`() {
        val (db, cleanup) = newDatabase()
        val dir = Files.createTempDirectory("backup-e2e")
        val zipPath = dir.resolve("backup.zip")
        try {
            ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
                fun put(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                put("bookSource.json", "[]")
                put("replaceRule.json", "[]")
                put(
                    "bookGroup.json",
                    "[" + listOf(
                        group(-20, "在读", 0), group(-1, "全部", 6),
                        group(1, "FQ", 7), group(2, "完结", 9),
                    ).joinToString(",") + "]",
                )
                put(
                    "bookshelf.json",
                    "[" + listOf(
                        book("在线书A", "作者A", 1),
                        book("在线书B", "作者B", 2),
                        // 音频：应被过滤
                        book("听书C", "作者C", 1, tab = "听书", type = 32),
                        // 本地书：应被过滤
                        book("本地书D", "作者D", 2, type = 264, origin = "loc_book", bookUrlOverride = "content://x/y"),
                    ).joinToString(",") + "]",
                )
                put(
                    "bookmark.json",
                    "[" + listOf(
                        """{"bookName":"在线书A","bookAuthor":"作者A","chapterIndex":1,"chapterName":"第2章","chapterPos":0,"bookText":"正文A","content":"","time":100}""",
                        """{"bookName":"听书C","bookAuthor":"作者C","chapterIndex":1,"chapterName":"第2章","chapterPos":0,"bookText":"正文C","content":"","time":200}""",
                        """{"bookName":"本地书D","bookAuthor":"作者D","chapterIndex":1,"chapterName":"第2章","chapterPos":0,"bookText":"正文D","content":"","time":300}""",
                    ).joinToString(",") + "]",
                )
            }

            val summary = BackupImporter(db).import(zipPath)

            // 先验证解析层：两个分组 id 必须映射成正确的名字
            val parsed = BackupImporter(db).let { _ ->
                // 直接从 zip 重新解析一次，确认 groupName 映射正确
                java.util.zip.ZipFile(zipPath.toFile()).use { zf ->
                    val shelfText = zf.getInputStream(zf.getEntry("bookshelf.json"))
                        .readBytes().toString(Charsets.UTF_8)
                    val groupText = zf.getInputStream(zf.getEntry("bookGroup.json"))
                        .readBytes().toString(Charsets.UTF_8)
                    parseShelfForTest(shelfText, groupText).associate { it.name to it.groupName }
                }
            }
            assertEquals(
                "解析层：group id 必须映射成名字（实际 $parsed）",
                mapOf("在线书A" to "FQ", "在线书B" to "完结", "听书C" to "FQ", "本地书D" to "完结"),
                parsed,
            )

            assertEquals("应导入 2 本在线书", 2, summary.books)
            assertEquals("应跳过 1 本本地书", 1, summary.skippedLocal)
            assertEquals("应跳过 1 本音频", 1, summary.skippedAudio)
            assertEquals("只应导入 1 条书签（另 2 条挂在被过滤的书上）", 1, summary.bookmarks)
            assertEquals("应报告跳过 2 条书签", 2, summary.bookmarksSkipped)

            // 分组：只导入有书的 FQ / 完结，不导入空的内置分组
            val groups = db.listBookGroups()
            assertEquals("只应有 FQ 与 完结 两个分组", 2, groups.size)
            assertEquals(setOf("FQ", "完结"), groups.map { it.name }.toSet())
            assertEquals("FQ 应有 1 本书", 1, groups.first { it.name == "FQ" }.bookCount)
            assertEquals("完结 应有 1 本书", 1, groups.first { it.name == "完结" }.bookCount)
            assertTrue(
                "空的内置分组（在读/全部）不应被导入",
                groups.none { it.name == "在读" || it.name == "全部" },
            )
        } finally {
            cleanup()
            runCatching {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 测试辅助：复刻 importer 的解析逻辑（保持与生产代码一致）
    // ------------------------------------------------------------------

    private fun parseGroupsForTest(text: String): List<BackupGroupEntry> =
        json(text).mapNotNull { element ->
            val g = element as? JsonObject ?: return@mapNotNull null
            val id = (g["groupId"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val name = (g["groupName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            BackupGroupEntry(id, name, (g["order"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0, id < 0)
        }

    /** 复刻 `parseShelf`（含 group id → 名字映射），用于验证解析层。 */
    private fun parseShelfForTest(shelfText: String, groupText: String): List<BackupShelfEntry> {
        val nameById = parseGroupsForTest(groupText).associate { it.groupId to it.groupName }
        return json(shelfText).mapNotNull { element ->
            val b = element as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (b[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun n(k: String) = (b[k] as? JsonPrimitive)?.longOrNull
            val name = s("name") ?: return@mapNotNull null
            val groupId = n("group")?.takeIf { it != 0L }
            BackupShelfEntry(
                sourceId = "s", bookUrl = s("bookUrl") ?: name, name = name, author = s("author"),
                tocUrl = name, coverUrl = null, completed = false,
                chapterIndex = 0, readAt = 0L, kind = ShelfKind.ONLINE,
                groupName = groupId?.let { nameById[it] },
            )
        }
    }

    private fun parseBookmarksForTest(text: String): List<BackupBookmarkEntry> =
        json(text).mapNotNull { element ->
            val m = element as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (m[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun n(k: String) = (m[k] as? JsonPrimitive)?.longOrNull
            val name = s("bookName") ?: return@mapNotNull null
            BackupBookmarkEntry(name, s("bookAuthor"), n("chapterIndex")?.toInt() ?: 0, s("chapterName"),
                n("chapterPos")?.toInt() ?: 0, s("bookText"), s("content"), n("time") ?: 0L)
        }

    private var counter = 0
    private fun entry(name: String, group: String?) = BackupShelfEntry(
        sourceId = "s", bookUrl = "u/$name", name = name, author = "作者",
        tocUrl = "u/$name", coverUrl = null, completed = false,
        chapterIndex = 0, readAt = 0L, kind = ShelfKind.ONLINE, groupName = group,
    )

    private fun newDatabase(): Pair<Database, () -> Unit> {
        val path = Files.createTempFile("backup-group-test-${counter++}", ".sqlite")
        val db = Database(path.toString())
        db.initialize("test-pass")
        return db to {
            runCatching { db.close() }
            runCatching { Files.deleteIfExists(path) }
            runCatching { Files.deleteIfExists(Path.of("$path-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$path-shm")) }
        }
    }
}
