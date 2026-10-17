package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份导入**不做类别分类**（用户的明确要求，2026-10-14）。
 *
 * ## 规则
 * 1. **不按备份字段猜类型**：`bookshelf.json` 的 `tab` / `type` 是手机端的展示分类，
 *    拿它猜"是不是漫画"属于自动分类 —— 猜错的形态是**漫画被送进文字阅读器**
 *    （表现为"打不开"，且用户看不出是谁改的）。
 * 2. **类型只认书源自身声明**（`bookSourceType == 2`），导入结束时立即回填，不必等重启。
 * 3. **分组完全按备份文件里的分组**：文件里怎么写就怎么落；没有分组的一律未分组
 *    （在"全部"里能看到），绝不自动塞进"小说/漫画/有声"这类分类分组。
 * 4. **已经一样就跳过**：类型为默认（小说/未指定）时不写库；已有行保留原值。
 * 5. **漫画绝不能被跳过**：导入条数必须把它算进去（自 G0 起音频也一样不再被跳过，
 *    导入侧只过滤本地图书这一种"服务端真的读不到"的类别）。
 */
class BackupImportNoClassificationTest {

    /**
     * 一本 `tab == "漫画"` 的书（书源内部载荷就长这样）。
     *
     * 刻意**不**拼上真实数据里的 `,{"type":"x"}` 尾巴：那会让这个值带上引号，
     * 嵌进测试用的 JSON 里就得再转义一层（踩过一次）。判 `tab` 只需要 base64 段。
     */
    private val comicTab: String = run {
        val payload = java.util.Base64.getEncoder()
            .encodeToString("""{"book_id":"1","sources":"测试","tab":"漫画","url":""}""".toByteArray(Charsets.UTF_8))
        "data:;base64,$payload"
    }

    @Test
    fun `comic tab on the book itself decides the kind, not the source declaration`() {
        withImport(
            // 聚合源：**没有** bookSourceType 字段（真实数据就是这样）
            sources = """[{"bookSourceUrl":"https://manga.example.com","bookSourceName":"聚合源","enabled":true}]""",
            shelf = """[{"name":"某漫画","author":"作者","bookUrl":"$comicTab","origin":"https://manga.example.com","tocUrl":"https://manga.example.com/book/1","group":0,"durChapterIndex":0,"durChapterTime":0}]""",
        ) { database, summary ->
            assertEquals("漫画书必须被导入，绝不能因为分类而被跳过", 1, summary.books)
            assertEquals(0, summary.skippedLocal)
            assertEquals(
                "书自己的 tab=漫画 ⇒ 必须是 comic：只认源声明会把聚合源里的漫画判成小说（用户实测反馈）",
                "comic",
                database.rawBookKind("https://manga.example.com", comicTab),
            )
            assertEquals("comic", database.getShelfBookByUrl(comicTab)?.bookKind)
        }
    }

    @Test
    fun `leaves the kind empty when neither the book nor the source says anything`() {
        withImport(
            sources = """[{"bookSourceUrl":"https://novel.example.com","bookSourceName":"文本书源","enabled":true}]""",
            shelf = """[{"name":"某小说","author":"作者","bookUrl":"https://novel.example.com/book/1","origin":"https://novel.example.com","tocUrl":"https://novel.example.com/book/1","group":0,"durChapterIndex":0,"durChapterTime":0}]""",
        ) { database, _ ->
            assertNull(
                "谁都没说类型 ⇒ 不写库（按小说处理 = 已经和小说一样就跳过）",
                database.rawBookKind("https://novel.example.com", "https://novel.example.com/book/1"),
            )
            assertEquals("novel", database.getShelfBookByUrl("https://novel.example.com/book/1")?.bookKind)
        }
    }

    @Test
    fun `classifies comic from the book source declaration at import time`() {
        withImport(
            // 书源自己声明是图片书源（bookSourceType=2）⇒ 权威判据
            sources = """[{"bookSourceUrl":"https://manga.example.com","bookSourceName":"测试漫画源","bookSourceType":2,"enabled":true}]""",
            shelf = """[{"name":"某漫画","author":"作者","bookUrl":"$comicTab","origin":"https://manga.example.com","tocUrl":"https://manga.example.com/book/1","group":0,"durChapterIndex":0,"durChapterTime":0}]""",
        ) { database, _ ->
            assertEquals(
                "类型必须由书源声明决定，且导入完立刻生效（不必等重启）",
                "comic",
                database.getShelfBookByUrl(comicTab)?.bookKind,
            )
        }
    }

    @Test
    fun `keeps the existing kind when the import says nothing about it`() {
        val directory = Files.createTempDirectory("legado-backup-no-classify-keep")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            // 用户（或解析链路）已经把这本标成漫画：导入不得把它打回小说
            database.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://manga.example.com",
                    bookUrl = comicTab,
                    name = "某漫画",
                    tocUrl = "https://manga.example.com/book/1",
                    bookKind = "comic",
                ),
                cover = null,
            )

            val archive = directory.resolve("keep.zip")
            writeArchive(
                archive,
                mapOf(
                    "bookSource.json" to """[{"bookSourceUrl":"https://manga.example.com","bookSourceName":"测试源","enabled":true}]""",
                    "bookshelf.json" to """[{"name":"某漫画","author":"作者","bookUrl":"$comicTab","origin":"https://manga.example.com","tocUrl":"https://manga.example.com/book/1","group":0,"durChapterIndex":0,"durChapterTime":0}]""",
                ),
            )
            BackupImporter(database).import(archive)

            assertEquals(
                "已经标好的类型必须原样保留（「不指定」≠「写成默认值」）",
                "comic",
                database.getShelfBookByUrl(comicTab)?.bookKind,
            )
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `groups come from the backup file and ungrouped books stay ungrouped`() {
        withImport(
            sources = """[{"bookSourceUrl":"https://manga.example.com","bookSourceName":"测试源","enabled":true}]""",
            groups = """[{"groupId":3,"groupName":"我的收藏","order":0,"show":true},{"groupId":-7,"groupName":"漫画","order":0,"show":true}]""",
            shelf = """
                [
                  {"name":"有分组的","author":"作者","bookUrl":"https://manga.example.com/book/1","origin":"https://manga.example.com","tocUrl":"https://manga.example.com/book/1","group":3,"durChapterIndex":0,"durChapterTime":0},
                  {"name":"未分组的","author":"作者","bookUrl":"https://manga.example.com/book/2","origin":"https://manga.example.com","tocUrl":"https://manga.example.com/book/2","group":0,"durChapterIndex":0,"durChapterTime":0}
                ]
            """,
        ) { database, _ ->
            val shelf = database.listBookshelf().associateBy { it.name }
            assertEquals("分组必须按备份文件里的分组名落地", "我的收藏", shelf["有分组的"]?.groupName)
            assertNull("没有分组的就是未分组（落在「全部」里），绝不自动塞进分类分组", shelf["未分组的"]?.groupName)
            assertEquals(
                "内置智能分组（groupId 为负，如「漫画」）是虚拟分组，不该被建出来",
                listOf("我的收藏"),
                database.listBookGroups().map { it.name },
            )
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun withImport(
        sources: String,
        shelf: String,
        groups: String? = null,
        block: (Database, BackupImportSummary) -> Unit,
    ) {
        val directory = Files.createTempDirectory("legado-backup-no-classify")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val archive = directory.resolve("backup.zip")
            val sections = linkedMapOf("bookSource.json" to sources, "bookshelf.json" to shelf)
            if (groups != null) sections["bookGroup.json"] = groups
            writeArchive(archive, sections)
            block(database, BackupImporter(database).import(archive))
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun writeArchive(target: Path, sections: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            sections.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
