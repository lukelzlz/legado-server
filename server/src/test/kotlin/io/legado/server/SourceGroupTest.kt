package io.legado.server

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 书源分组：聚合、按分组取搜索范围、改名、删除，以及**两条导入路径的分组语义**。
 *
 * 分组语义（用户明确要求）：
 * - **导入书源文件 / 订阅更新** → 不自动分组，新源一律「未分组」（`applyGroups = false`）；
 * - **导入手机备份** → 按备份里的 `bookSourceGroup` 落库（`applyGroups = true`，见 [BackupImporter]）。
 *
 * 全部落在 [Database] 层（不经 HTTP）：这些断言关心的是分组语义本身，
 * 而 HTTP 用例在本机受 Windows SQLite 文件占用问题干扰（见 SESSION-HIST-008），
 * 路由契约另有 [SourceGroupRoutesTest] 覆盖。
 */
class SourceGroupTest {

    @Test
    fun `source groups aggregate trimmed names with enabled counts`() {
        val path = temporaryDatabase()
        val database = Database(path)
        try {
            database.initialize("password-for-test")
            val response = importGrouped(
                database,
                listOf(
                    sourceJson("https://a1.example", "启用A1", "组A"),
                    sourceJson("https://a2.example", "停用A2", "组A", enabled = false),
                    // 首尾带空格的分组名必须聚合成一组，否则导入数据会把一个分组拆成两个
                    sourceJson("https://b1.example", "启用B1", "  组B  "),
                    sourceJson("https://n1.example", "未分组N1", null),
                    sourceJson("https://n2.example", "空串分组N2", ""),
                ),
            )

            assertEquals("两个书源携带一个分组，5 个里只有 2 个分组名", 2, response.sourceGroups)

            val groups = database.listSourceGroups()
            assertEquals(listOf("组A", "组B"), groups.map { it.name })
            assertEquals("组A 共 2 个书源", 2, groups[0].sourceCount)
            assertEquals("其中只有 1 个是启用的（搜索实际会用到的）", 1, groups[0].enabledCount)
            assertEquals("组B 的空白被 trim 掉", 1, groups[1].sourceCount)
        } finally {
            database.close()
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `group scoped search only returns that group's enabled sources`() {
        val path = temporaryDatabase()
        val database = Database(path)
        try {
            database.initialize("password-for-test")
            importGrouped(
                database,
                listOf(
                    sourceJson("https://a1.example", "启用A1", "News"),
                    sourceJson("https://a2.example", "停用A2", "News", enabled = false),
                    sourceJson("https://b1.example", "启用B1", "漫画"),
                    sourceJson("https://n1.example", "未分组N1", null),
                    sourceJson("https://n2.example", "空串分组N2", ""),
                ),
            )

            assertEquals(
                "分组过滤必须只留本组且已启用（停用的不参与搜索）",
                listOf("https://a1.example"),
                database.listSearchSourceRecords(null, "News").map { it.id },
            )
            assertEquals(
                "分组名大小写不敏感（collate nocase），否则「news」会搜出 0 个源",
                listOf("https://a1.example"),
                database.listSearchSourceRecords(null, "news").map { it.id },
            )
            // 首尾空白来自用户输入（下拉框值通常干净，但 API 是公开契约），必须 trim 后再匹配
            assertEquals(listOf("https://a1.example"), database.listSearchSourceRecords(null, "  News ").map { it.id })
            assertEquals(listOf("https://b1.example"), database.listSearchSourceRecords(null, "漫画").map { it.id })

            assertEquals(
                "未分组哨兵要同时覆盖 null 与空串两种落库形态",
                listOf("https://n1.example", "https://n2.example"),
                database.listSearchSourceRecords(null, SourceGroupFilter.UNGROUPED).map { it.id }.sorted(),
            )
            assertEquals("不存在的分组应当搜出 0 个源，而不是退化成「全部源」", emptyList<String>(), database.listSearchSourceRecords(null, "不存在").map { it.id })
            assertEquals("空白分组名 = 不过滤", 4, database.listSearchSourceRecords(null, "   ").size)
            assertEquals("null 分组名 = 不过滤（保持既有语义）", 4, database.listSearchSourceRecords(null, null).size)

            // 分组与书源 id 同时给出时取交集（POST /api/search 的两种范围字段都兼容）
            assertEquals(
                listOf("https://a1.example"),
                database.listSearchSourceRecords(listOf("https://a1.example", "https://b1.example"), "News").map { it.id },
            )
            assertEquals(
                emptyList<String>(),
                database.listSearchSourceRecords(listOf("https://b1.example"), "News").map { it.id },
            )
        } finally {
            database.close()
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `renaming a source group moves all its sources and merges into an existing group`() {
        val path = temporaryDatabase()
        val database = Database(path)
        try {
            database.initialize("password-for-test")
            importGrouped(
                database,
                listOf(
                    sourceJson("https://a1.example", "A1", "组A"),
                    sourceJson("https://b1.example", "B1", "组B"),
                    sourceJson("https://b2.example", "B2", "组B"),
                ),
            )

            assertEquals(2, database.renameSourceGroup("组B", "组A"))
            val merged = database.listSourceGroups()
            assertEquals("组B 应当消失、其书源并入组A，而不是并存两个分组", listOf("组A"), merged.map { it.name })
            assertEquals(3, merged.single().sourceCount)

            assertEquals("改名不存在的分组应当如实返回 0（路由据此回 404）", 0, database.renameSourceGroup("不存在", "组C"))
        } finally {
            database.close()
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `clearing a source group only unfiles sources and never deletes them`() {
        val path = temporaryDatabase()
        val database = Database(path)
        try {
            database.initialize("password-for-test")
            importGrouped(
                database,
                listOf(
                    sourceJson("https://a1.example", "A1", "组A"),
                    sourceJson("https://a2.example", "A2", "组A"),
                    sourceJson("https://b1.example", "B1", "组B"),
                ),
            )

            assertEquals(2, database.clearSourceGroup("组A"))

            assertEquals("删掉的只能是分组本身", listOf("组B"), database.listSourceGroups().map { it.name })
            val sources = database.listSources(null)
            assertEquals("书源一个都不能少", 3, sources.size)
            assertNull("被删分组的书源退化为未分组", sources.first { it.id == "https://a1.example" }.group)
            assertEquals(
                "删组后这些书源仍然可被「未分组」范围搜到（否则它们会变成搜不到的死源）",
                listOf("https://a1.example", "https://a2.example"),
                database.listSearchSourceRecords(null, SourceGroupFilter.UNGROUPED).map { it.id }.sorted(),
            )
            assertEquals(0, database.clearSourceGroup("组A"))
        } finally {
            database.close()
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `backup style import updates the group of an already imported source`() {
        val path = temporaryDatabase()
        val database = Database(path)
        try {
            database.initialize("password-for-test")
            // 备份导入走 importSources(applyGroups = true)（upsert）：分组必须跟着一起更新，
            // 而不是只在首次插入时生效
            database.importSources(listOf(sourceJson("https://a1.example", "A1", "旧分组")), applyGroups = true)
            database.importSources(listOf(sourceJson("https://a1.example", "A1", "新分组")), applyGroups = true)

            assertEquals(listOf("新分组"), database.listSourceGroups().map { it.name })
            assertEquals("新分组", database.listSources(null).single().group)
        } finally {
            database.close()
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    /**
     * 用户明确要求：**单独导入书源时不自动分组**。
     *
     * 两个必须同时成立的点：
     * 1. 新书源落在「未分组」，且**源自身 JSON 里的 `bookSourceGroup` 被删净**
     *    （只清列不清 payload 会让编辑弹窗显示一个库里不存在的分组）；
     * 2. 已经在服务端手工分好组的书源，**再导入一次书源文件不会被清空分组**。
     */
    @Test
    fun `plain source import never auto groups and never wipes a manual group`() {
        val path = temporaryDatabase()
        val database = Database(path)
        try {
            database.initialize("password-for-test")

            val response = database.importSources(
                listOf(
                    sourceJson("https://a1.example", "A1", "源文件里的分组"),
                    sourceJson("https://a2.example", "A2", null),
                ),
            )
            assertEquals("导入书源文件不采用书源自带分组，提示里的分组数必须是 0", 0, response.sourceGroups)
            assertEquals("新源一律未分组", emptyList<String>(), database.listSourceGroups().map { it.name })
            assertNull(database.listSources(null).first { it.id == "https://a1.example" }.group)
            assertTrue(
                "payload 里也不能留下 bookSourceGroup，否则编辑弹窗会显示库里不存在的分组",
                !database.getSource("https://a1.example")!!.json.contains("bookSourceGroup"),
            )

            // 用户手工分组（= 「分组管理」面板调用的那个批量接口）后，再导入同一个书源文件
            // （内容里仍自带分组）：**不得把手工分组冲掉**。
            assertEquals(1, database.batchUpdateSources(listOf("https://a1.example"), "set_group", "手工分组"))
            assertEquals(listOf("手工分组"), database.listSourceGroups().map { it.name })

            database.importSources(listOf(sourceJson("https://a1.example", "A1", "源文件里的分组")))
            assertEquals(
                "普通导入（applyGroups = false）不得清空已有分组",
                listOf("手工分组"),
                database.listSourceGroups().map { it.name },
            )
        } finally {
            database.close()
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    @Test
    fun `import response reports distinct source groups for backup style imports`() {
        val path = temporaryDatabase()
        val database = Database(path)
        try {
            database.initialize("password-for-test")
            val response = importGrouped(
                database,
                listOf(
                    sourceJson("https://a1.example", "A1", "组A"),
                    sourceJson("https://a2.example", "A2", "组a"),
                    sourceJson("https://b1.example", "B1", "组B"),
                    sourceJson("https://n1.example", "N1", null),
                ),
            )
            // 大小写不同的同名分组按一个算（与 listSourceGroups / 范围过滤的 collate nocase 口径一致）
            assertEquals(2, response.sourceGroups)
            assertTrue(response.errors.isEmpty())

            val groups = database.listSourceGroups()
            assertEquals("组A / 组a 这类大小写变体不能显示成两个分组", 2, groups.size)
            assertEquals(2, groups.first { it.name.equals("组A", ignoreCase = true) }.sourceCount)
        } finally {
            database.close()
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    private fun temporaryDatabase(): String = Files.createTempFile("legado-source-group-test", ".sqlite").toString()

    /** 备份导入路径的语义：采用书源自带的 `bookSourceGroup`。 */
    private fun importGrouped(database: Database, sources: List<String>): ImportResponse =
        database.importSources(sources, applyGroups = true)

    private fun sourceJson(url: String, name: String, group: String?, enabled: Boolean = true): String {
        val groupField = if (group == null) "" else ""","bookSourceGroup":${JsonPrimitive(group)}"""
        return """{"bookSourceUrl":"$url","bookSourceName":"$name","enabled":$enabled$groupField}"""
    }
}
