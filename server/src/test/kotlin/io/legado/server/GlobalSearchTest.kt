package io.legado.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 全局搜索（书架 / 目录 / 正文）的闭环测试，跑在 **SQLite** 上。
 *
 * 为什么要同时覆盖「数据层」与「HTTP 契约」：单测过了但路由没接上照样全绿
 * （本仓库历史上「字段没进 SELECT」这类静默故障就是这样漏过去的，见 AGENTS.md）。
 *
 * 另一个重点是**方言守卫**：`book_chapter_index` 的建表/查询必须只用 SQLite 与
 * PostgreSQL 都支持的写法。`order by rowid` 曾让 PG 秒 500，所以这里直接把
 * source-of-truth 的 DDL 与生成后的 PG 语句都断言一遍。
 */
class GlobalSearchTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ------------------------------------------------------------------ HTTP

    @Test
    fun `三种模式各自命中，且 sourceId + bookUrl 能收窄到单本书`() {
        val dbPath = Files.createTempFile("legado-search-routes", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-search-routes-dir")
        try {
            seed(dbPath)
            testApplication {
                val config = ServerConfig(
                    host = "0.0.0.0", port = 8080, databasePath = dbPath,
                    coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
                    initialAdminPassword = PASSWORD, secureCookies = false,
                )
                application { legadoApplication(config) }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }

                // 0) 未登录一律 401（搜索是只读接口，但仍然不能裸奔）
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.post("/api/search/local") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"mode":"shelf","query":"十日"}""")
                    }.status,
                )

                val login = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(PASSWORD))
                }
                assertEquals(HttpStatusCode.OK, login.status)
                val cookie = login.headers[io.ktor.http.HttpHeaders.SetCookie]!!.substringBefore(';')

                suspend fun search(body: String): SearchResponse {
                    val response = client.post("/api/search/local") {
                        header(io.ktor.http.HttpHeaders.Cookie, cookie)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    return json.decodeFromString<SearchResponse>(response.bodyAsText())
                }

                // 1) 书架：书名命中
                val byName = search("""{"mode":"shelf","query":"十日"}""")
                assertEquals(1, byName.items.size)
                assertEquals("shelf", byName.items.first().kind)
                assertEquals("十日终焉", byName.items.first().bookName)
                assertEquals("杀虫队队员", byName.items.first().bookAuthor)
                assertFalse(byName.truncated)

                // 2) 书架：作者命中（`lower(coalesce(author,''))` 这条分支）
                val byAuthor = search("""{"mode":"shelf","query":"烽火"}""")
                assertEquals(1, byAuthor.items.size)
                assertEquals("雪中悍刀行", byAuthor.items.first().bookName)

                // 3) 目录：按章名命中，必须带书名/章名/章序号（前端靠它跳章）
                val tocHits = search("""{"mode":"toc","query":"风暴"}""")
                assertEquals(1, tocHits.items.size)
                val tocHit = tocHits.items.first()
                assertEquals("toc", tocHit.kind)
                assertEquals("十日终焉", tocHit.bookName)
                assertEquals("第二章 风暴将至", tocHit.chapterTitle)
                assertEquals(1, tocHit.chapterIndex)
                assertEquals("https://a.test/book/1/c/2", tocHit.chapterUrl)

                // 4) 目录 + 单本过滤：同一关键词换成另一本书 ⇒ 0 条
                val tocOtherBook = search(
                    """{"mode":"toc","query":"风暴","sourceId":"src-b","bookUrl":"https://b.test/book/1"}""",
                )
                assertEquals("sourceId+bookUrl 过滤必须生效", 0, tocOtherBook.items.size)

                // 5) 正文：命中 + 服务端 snippet（含关键词、无换行、不超过 120 字）
                val contentHits = search("""{"mode":"content","query":"剑光如雪"}""")
                assertEquals(1, contentHits.items.size)
                val contentHit = contentHits.items.first()
                assertEquals("content", contentHit.kind)
                assertEquals("第二章 风暴将至", contentHit.chapterTitle)
                val snippet = contentHit.snippet
                assertNotNull("正文命中必须带 snippet", snippet)
                assertTrue("snippet 必须含关键词：$snippet", snippet!!.contains("剑光如雪"))
                assertFalse("snippet 不能留换行：$snippet", snippet.contains('\n'))
                assertTrue("snippet 不能超过 120 字：${snippet.length}", snippet.length <= 120)

                // 6) 正文 + 单本过滤
                val contentOtherBook = search(
                    """{"mode":"content","query":"剑光如雪","sourceId":"src-b","bookUrl":"https://b.test/book/1"}""",
                )
                assertEquals(0, contentOtherBook.items.size)

                // 7) 空查询：直接空结果，绝不打 `like '%%'`
                listOf("shelf", "toc", "content").forEach { mode ->
                    val empty = search("""{"mode":"$mode","query":"   "}""")
                    assertEquals(0, empty.items.size)
                    assertEquals(0, empty.total)
                    assertFalse(empty.truncated)
                }

                // 8) limit 夹在 1..100：limit=1 ⇒ 只回 1 条且 truncated=true
                val limited = search("""{"mode":"toc","query":"第","limit":1}""")
                assertEquals(1, limited.items.size)
                assertTrue("命中数超过 limit 时必须标记截断", limited.truncated)

                // 9) 未知模式 ⇒ 400（不能静默当 shelf 处理）
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.post("/api/search/local") {
                        header(io.ktor.http.HttpHeaders.Cookie, cookie)
                        contentType(ContentType.Application.Json)
                        setBody("""{"mode":"fulltext","query":"x"}""")
                    }.status,
                )
            }
        } finally {
            deleteSqlite(dbPath)
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    // -------------------------------------------------------------- 数据层

    @Test
    fun `目录索引随 TOC 缓存整体替换，且删书后不留残留`() {
        val dbPath = Files.createTempFile("legado-search-index", ".sqlite").toString()
        try {
            Database(dbPath).use { db ->
                db.initialize(PASSWORD)
                saveBook(db, sourceId = "src-a", bookUrl = "https://a.test/book/1", name = "十日终焉", author = "杀虫队队员")
                db.saveTocCache("src-a", "https://a.test/book/1", chaptersOf("src-a", 5, "第一章"))

                // 5 章全部进索引（按目录位置编号）
                assertEquals(5, db.searchToc("第一章", 50).size)
                assertEquals(5, db.searchToc("第一章", 50).mapNotNull { it.chapterIndex }.distinct().size)

                // 目录缩水到 2 章 ⇒ 旧行必须消失（否则会搜到不存在的章）
                db.saveTocCache("src-a", "https://a.test/book/1", chaptersOf("src-a", 2, "第一章"))
                assertEquals(2, db.searchToc("第一章", 50).size)

                // 重复 URL 不会让整批 insert 失败（书源「最新章节」区常重复列一遍）
                db.saveTocCache(
                    "src-a",
                    "https://a.test/book/1",
                    listOf(
                        Chapter(0, "重复章", "https://a.test/book/1/dup"),
                        Chapter(1, "重复章", "https://a.test/book/1/dup"),
                    ),
                )
                assertEquals(1, db.searchToc("重复章", 50).size)

                // 删书 ⇒ 索引与目录缓存一起走
                db.removeBookshelf("src-a", "https://a.test/book/1")
                assertEquals(0, db.searchToc("重复章", 50).size)
                assertEquals(0, db.searchShelf("十日", 50).size)
            }
        } finally {
            deleteSqlite(dbPath)
        }
    }

    @Test
    fun `tocUrl 变体通过书架反查落到同一个 book_url`() {
        val dbPath = Files.createTempFile("legado-search-toc-variant", ".sqlite").toString()
        try {
            Database(dbPath).use { db ->
                db.initialize(PASSWORD)
                saveBook(
                    db,
                    sourceId = "src-a",
                    bookUrl = "https://a.test/book/1",
                    tocUrl = "https://a.test/book/1/toc",
                    name = "十日终焉",
                    author = "杀虫队队员",
                )
                // 书源详情给的 tocUrl 是 `/toc` 变体：索引仍必须挂在书架的 book_url 上
                db.saveTocCache("src-a", "https://a.test/book/1/toc", chaptersOf("src-a", 3, "第"))
                val hits = db.searchToc("第", 50, sourceId = "src-a", bookUrl = "https://a.test/book/1")
                assertEquals(3, hits.size)
                assertTrue(hits.all { it.bookUrl == "https://a.test/book/1" })
                assertEquals("十日终焉", hits.first().bookName)

                // 不在书架上的书不落索引（目录搜索本来只针对书架里的书）
                db.saveTocCache("src-x", "https://x.test/book/9/toc", chaptersOf("src-x", 2, "第"))
                assertEquals(0, db.searchToc("第", 50, sourceId = "src-x").size)
            }
        } finally {
            deleteSqlite(dbPath)
        }
    }

    @Test
    fun `启动回填把存量 book_toc_cache 补进索引，且幂等`() {
        val dbPath = Files.createTempFile("legado-search-backfill", ".sqlite").toString()
        try {
            // 直接造「有目录缓存、但没有索引」的存量库：先写缓存，再把索引清空
            Database(dbPath).use { db ->
                db.initialize(PASSWORD)
                saveBook(db, sourceId = "src-a", bookUrl = "https://a.test/book/1", name = "十日终焉", author = "杀虫队队员")
                db.saveTocCache("src-a", "https://a.test/book/1/toc", chaptersOf("src-a", 4, "旧章"))
                clearChapterIndex(dbPath)
                assertEquals(0, db.searchToc("旧章", 50).size)

                assertEquals("第一轮应回填 1 本", 1, db.backfillChapterIndex())
                assertEquals(4, db.searchToc("旧章", 50).size)
                assertEquals("幂等：已索引的书不会被重复挑中", 0, db.backfillChapterIndex())
            }
        } finally {
            deleteSqlite(dbPath)
        }
    }

    // --------------------------------------------------------------- 方言守卫

    @Test
    fun `正文搜索只返回书架里的书（孤儿缓存不能变成未知书名）`() {
        val dbPath = Files.createTempFile("legado-search-orphan", ".sqlite").toString()
        try {
            Database(dbPath).use { db ->
                db.initialize(PASSWORD)
                saveBook(db, "src-a", "https://a.test/book/1", name = "十日终焉", author = "杀虫队队员")
                // ① 书在书架：能搜到，书名正常
                db.cacheBookContent(
                    "src-a", "https://a.test/book/1", "https://a.test/book/1/c/1",
                    ChapterContent(title = "第一章 开端", content = "苏尘拔剑，剑光如雪。"),
                )
                // ② 书不在书架（换源/删书后的孤儿缓存）：必须搜不到，否则前端只能显示「未知书名」
                db.cacheBookContent(
                    "src-a", "https://a.test/gone/9", "https://a.test/gone/9/c/1",
                    ChapterContent(title = "第一章 旧章", content = "苏尘早已离开此地，剑光不在。"),
                )

                val hits = db.searchContent("苏尘", 50)
                assertEquals(1, hits.size)
                assertEquals("十日终焉", hits.single().bookName)
                assertTrue("命中必须都带书名", hits.all { it.bookName.isNotBlank() })
            }
        } finally {
            deleteSqlite(dbPath)
        }
    }

    @Test
    fun `回填：书架 book_url 与 toc_url 只有尾部类型不同时也能索引（目录搜不到的真实修复）`() {
        val dbPath = Files.createTempFile("legado-search-backfill-tail", ".sqlite").toString()
        try {
            Database(dbPath).use { db ->
                db.initialize(PASSWORD)
                // 复刻聚合源真实形态：book_url 以 `{"type":"qingtian"}` 结尾，
                // toc_url 以 `{"type":"qingtian2"}` 结尾 —— 两者互不为子串。
                val bookUrl = "data:;base64,AAAA,{\"type\":\"qingtian\"}"
                val tocUrl = "data:;base64,AAAA,{\"type\":\"qingtian2\"}"
                saveBook(db, sourceId = "src-a", bookUrl = bookUrl, tocUrl = tocUrl, name = "被废修为后", author = "某作者")
                db.saveTocCache("src-a", tocUrl, chaptersOf("src-a", 6, "第"))
                clearChapterIndex(dbPath)
                assertEquals("先确认清空了索引", 0, db.searchToc("第", 50).size)

                assertEquals("按书架 toc_url 反查必须命中", 1, db.backfillChapterIndex())
                val hits = db.searchToc("第", 50, sourceId = "src-a", bookUrl = bookUrl)
                assertEquals(6, hits.size)
                assertTrue("索引行必须挂在书架的 book_url 上", hits.all { it.bookUrl == bookUrl })
            }
        } finally {
            deleteSqlite(dbPath)
        }
    }

    @Test
    fun `DDL 与查询里没有 SQLite 专有写法，PG 变体包含三张表达式索引`() {
        val sqlite = DatabaseSchema.SQLITE_DDL
        val section = sqlite.substringAfter("create table if not exists book_chapter_index")
        listOf("rowid", "insert or", "pragma", "strftime", "changes()").forEach { banned ->
            assertFalse("book_chapter_index 附近不能出现 `$banned`", section.contains(banned, ignoreCase = true))
        }
        assertTrue(sqlite.contains("create index if not exists book_chapter_index_title_idx on book_chapter_index(lower(title))"))
        assertTrue(sqlite.contains("create index if not exists book_shelf_name_ci_idx on book_shelf(lower(name))"))
        assertTrue(sqlite.contains("create index if not exists book_shelf_author_ci_idx on book_shelf(lower(coalesce(author, '')))"))

        val postgres = DatabaseSchema.statements(DatabaseEngine.POSTGRES)
        // 表达式索引必须原样下发（生成器只在遇到 collate nocase 时才改写）
        assertTrue(postgres.any { it.contains("book_chapter_index_title_idx") && it.contains("lower(title)") })
        assertTrue(postgres.any { it.contains("book_shelf_name_ci_idx") && it.contains("lower(name)") })
        assertTrue(postgres.any { it.contains("book_shelf_author_ci_idx") && it.contains("lower(coalesce") })
        // PG 里 `integer` 一律是 32 位，章序号虽小也走 bigint（与全库口径一致）
        val table = postgres.first { it.startsWith("create table if not exists book_chapter_index") }
        assertTrue("PG 变体：$table", table.contains("chapter_index bigint"))
        postgres.forEach { statement ->
            listOf("rowid", "insert or", "pragma", "strftime").forEach { banned ->
                assertFalse("PG DDL 不能出现 `$banned`：$statement", statement.contains(banned, ignoreCase = true))
            }
        }
    }

    @Test
    fun `snippet 生成规则：前后各 40 字符、压掉换行、最多 120 字`() {
        val body = "开头" + "啊".repeat(200) + "\n\n关键词\n" + "呀".repeat(200) + "结尾"
        val snippet = buildSearchSnippet(body, "关键词")
        assertTrue(snippet.contains("关键词"))
        assertFalse(snippet.contains('\n'))
        assertTrue("不能超过 120 字：${snippet.length}", snippet.length <= 120)
        assertTrue("命中位置前应有省略号", snippet.startsWith("…"))
        assertTrue("命中位置后应有省略号", snippet.endsWith("…"))

        // 命中在开头 ⇒ 不加前置省略号；正文比上限短 ⇒ 原样返回
        assertEquals("关键词在这里", buildSearchSnippet("关键词在这里", "关键词"))
        // 找不到命中也必须给出摘要，不能抛异常或返回空
        assertEquals("整段正文", buildSearchSnippet("整段正文", "不存在"))
        assertEquals("", buildSearchSnippet("", "关键词"))
    }

    // ------------------------------------------------------------------ 工具

    private fun seed(dbPath: String) {
        Database(dbPath).use { db ->
            db.initialize(PASSWORD)
            saveBook(db, "src-a", "https://a.test/book/1", name = "十日终焉", author = "杀虫队队员")
            saveBook(db, "src-b", "https://b.test/book/1", name = "雪中悍刀行", author = "烽火戏诸侯")

            val chaptersA = listOf(
                Chapter(0, "第一章 开端", "https://a.test/book/1/c/1"),
                Chapter(1, "第二章 风暴将至", "https://a.test/book/1/c/2"),
                Chapter(2, "第三章 剑来", "https://a.test/book/1/c/3"),
            )
            // 与真实链路一致：tocUrl 与 bookUrl 两个键都写（见 Routes.resolveBookChapters）
            db.saveTocCache("src-a", "https://a.test/book/1/toc", chaptersA)
            db.saveTocCache("src-a", "https://a.test/book/1", chaptersA)
            val chaptersB = listOf(
                Chapter(0, "第一章 白衣", "https://b.test/book/1/c/1"),
                Chapter(1, "第二章 江湖", "https://b.test/book/1/c/2"),
            )
            db.saveTocCache("src-b", "https://b.test/book/1/toc", chaptersB)
            db.saveTocCache("src-b", "https://b.test/book/1", chaptersB)

            db.cacheBookContent(
                "src-a",
                "https://a.test/book/1",
                "https://a.test/book/1/c/2",
                ChapterContent(title = "第二章 风暴将至", content = "他拔出长剑，剑光如雪。\n\n风雪很大，天地皆白。"),
            )
            db.cacheBookContent(
                "src-b",
                "https://b.test/book/1",
                "https://b.test/book/1/c/1",
                ChapterContent(title = "第一章 白衣", content = "北凉的风雪里，白衣少年缓步而来。"),
            )
        }
    }

    private fun saveBook(
        db: Database,
        sourceId: String,
        bookUrl: String,
        tocUrl: String = "$bookUrl/toc",
        name: String,
        author: String,
    ) {
        db.saveBookshelf(
            BookshelfWriteRequest(
                sourceId = sourceId,
                bookUrl = bookUrl,
                name = name,
                author = author,
                tocUrl = tocUrl,
            ),
            null,
        )
    }

    private fun chaptersOf(sourceId: String, count: Int, prefix: String): List<Chapter> =
        (0 until count).map { index -> Chapter(index, "$prefix ${index + 1}", "https://$sourceId.test/ch/$index") }

    /** 直接清空索引表（模拟"存量库还没回填"的老库状态）。 */
    private fun clearChapterIndex(dbPath: String) {
        // 用独立 JDBC 连接直删：要造的就是「表在、但一行数据都没有」这个外部状态，
        // 走 Database 的公开写入口反而造不出来（每次 saveTocCache 都会顺手补索引）。
        java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { it.executeUpdate("delete from book_chapter_index") }
        }
    }

    private fun deleteSqlite(dbPath: String) {
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { candidate ->
            runCatching { Files.deleteIfExists(Path.of(candidate)) }
        }
    }

    private companion object {
        const val PASSWORD = "test-password-1234"
    }
}

