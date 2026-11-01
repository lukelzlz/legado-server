package io.legado.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * 书签在 **HTTP 契约层**的闭环：加 → 列 → 改 → 删，外加「同一位置重复添加只更新不新增」。
 *
 * 为什么必须有这一层：`Database.saveBookmark` 的单测过了，**路由没接上照样全绿**，
 * 用户看到的就是「点了添加书签、列表里没有」。本仓库历史上「字段没进 SELECT」这类
 * 静默故障（封面的 `cover_url`）正是这样漏过去的。
 *
 * 第二个用例守住**备份导出**这一条：书签单独一张表，最容易在改动中掉出
 * `bookmark.json`（用户明确要求「备份的导入导出也要有」）。
 */
class BookmarkRoutesTest {

    @Test
    fun `add list update delete a bookmark over http`() {
        withApp { client, cookie, csrf ->
            // 0) 未登录不允许读书签
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/bookmarks?bookName=十日终焉").status)

            // 1) 添加：位置锚点与原文摘要必须原样落库
            val added = client.post("/api/bookmarks") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"bookName":"十日终焉","bookAuthor":"杀虫队队员","chapterIndex":3,
                     "chapterName":"第二章 开始","chapterPos":1234,"bookText":"书签处的原文片段","content":""}
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, added.status)
            val created = Json { ignoreUnknownKeys = true; explicitNulls = false }
                .decodeFromString<Bookmark>(added.bodyAsText())
            assertTrue("必须返回落库后的 id", created.id > 0)
            assertEquals(1234, created.chapterPos)
            assertEquals("第二章 开始", created.chapterName)
            assertEquals("书签处的原文片段", created.bookText)
            assertTrue("createdAt 由服务端补", created.createdAt > 0)

            // 2) 同一位置再点一次：唯一键去重，只更新摘要，不新增
            val again = client.post("/api/bookmarks") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"bookName":"十日终焉","bookAuthor":"杀虫队队员","chapterIndex":3,
                        "chapterName":"第二章 开始","chapterPos":1234,"bookText":"更新后的摘要"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(created.id, Json.decodeFromString<Bookmark>(again.bodyAsText()).id)

            val listed = client.get("/api/bookmarks?bookName=%E5%8D%81%E6%97%A5%E7%BB%88%E7%84%89&bookAuthor=%E6%9D%80%E8%99%AB%E9%98%9F%E9%98%9F%E5%91%98") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, listed.status)
            val items = Json { ignoreUnknownKeys = true; explicitNulls = false }
                .decodeFromString<BookmarkListResponse>(listed.bodyAsText()).items
            assertEquals("同一位置重复添加不能出现两条", 1, items.size)
            assertEquals("更新后的摘要", items.first().bookText)

            // 3) 编辑（只改备注，位置不动）
            val updated = client.put("/api/bookmarks/${created.id}") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"content":"我在这里看到的伏笔"}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedBookmark = Json.decodeFromString<Bookmark>(updated.bodyAsText())
            assertEquals("我在这里看到的伏笔", updatedBookmark.content)
            assertEquals("只传备注不能把位置改掉", 1234, updatedBookmark.chapterPos)

            // 4) 删除：先 204、再删 404
            val deleted = client.delete("/api/bookmarks/${created.id}") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            val deletedAgain = client.delete("/api/bookmarks/${created.id}") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NotFound, deletedAgain.status)
            val empty = client.get("/api/bookmarks?bookName=%E5%8D%81%E6%97%A5%E7%BB%88%E7%84%89") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
            }.bodyAsText()
            assertTrue("删除后列表应为空：$empty", empty.contains("\"items\":[]"))

            // 5) 参数校验
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/bookmarks") { header(io.ktor.http.HttpHeaders.Cookie, cookie) }.status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/api/bookmarks") {
                    header(io.ktor.http.HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookName":"书","chapterIndex":-1,"chapterPos":0}""")
                }.status,
            )
        }
    }

    /**
     * **无作者的书**（本地 TXT 就没有作者）：同一位置连点也不能插出重复行。
     *
     * 这条专门锁 SQLite 的坑：UNIQUE 索引把 NULL 视为互不相等，
     * `insert ... on conflict(book_name, book_author, ...)` 在 `book_author` 为 NULL 时**不会命中冲突**
     * ⇒ 实测连点 7 次得到 7 条一模一样、chapterPos 都是 0 的书签。
     */
    @Test
    fun `adding the same spot twice for a book without author keeps a single row`() {
        withApp { client, cookie, csrf ->
            val body = """{"bookName":"无作者的书","chapterIndex":0,"chapterName":"第一章","chapterPos":0,"bookText":"片段"}"""
            repeat(3) {
                val response = client.post("/api/bookmarks") {
                    header(io.ktor.http.HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.OK, response.status)
            }
            val listed = client.get("/api/bookmarks?bookName=%E6%97%A0%E4%BD%9C%E8%80%85%E7%9A%84%E4%B9%A6") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
            }.bodyAsText()
            val items = Json { ignoreUnknownKeys = true; explicitNulls = false }
                .decodeFromString<BookmarkListResponse>(listed).items
            assertEquals("无作者的书同一位置连点三次只能有一条：$listed", 1, items.size)
        }
    }

    /**
     * 「书签」页的两条接口：左栏聚合（含**不在书架上的书**）与批量删除。
     *
     * 这一层必须测，因为左栏是**服务端聚合**出来的：书签删光后那本书必须从聚合结果里消失，
     * 否则界面会出现「书签全删了、书还在列表里」。
     */
    @Test
    fun `overview groups bookmarks by book and batch delete removes whole books`() {
        withApp { client, cookie, csrf ->
            suspend fun addBookmark(bookName: String, author: String?, chapterPos: Int) {
                val response = client.post("/api/bookmarks") {
                    header(io.ktor.http.HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"bookName":"$bookName","bookAuthor":${if (author == null) "null" else "\"$author\""},
                            "chapterIndex":0,"chapterName":"第一章","chapterPos":$chapterPos,"bookText":"片段"}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, response.status)
            }

            // 书架上一本，另一本**不在书架**（「一股脑全存」的核心场景）
            client.post("/api/bookshelf") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"sourceId":"src-a","bookUrl":"https://a.example.com/1","name":"在架书","author":"作者A","tocUrl":"https://a.example.com/1"}""")
            }
            addBookmark("在架书", "作者A", 10)
            addBookmark("在架书", "作者A", 20)
            addBookmark("不在架书", null, 0)

            val overview = Json { ignoreUnknownKeys = true; explicitNulls = false }
                .decodeFromString<BookmarkOverviewResponse>(
                    client.get("/api/bookmarks/overview") { header(io.ktor.http.HttpHeaders.Cookie, cookie) }.bodyAsText(),
                ).items
            assertEquals("两本书都要出现在左栏（不论是否在书架）", 2, overview.size)
            val onShelf = overview.first { it.bookName == "在架书" }
            assertEquals("在架书应有 2 条", 2, onShelf.count)
            assertEquals("在架书应带作者", "作者A", onShelf.bookAuthor)
            val offShelf = overview.first { it.bookName == "不在架书" }
            assertEquals(1, offShelf.count)
            assertEquals("不在书架的书没有封面", null, offShelf.coverKey)

            // 批量按书删：删掉「在架书」→ 左栏不应再有它
            val deleted = client.post("/api/bookmarks/delete-batch") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"books":[{"bookName":"在架书","bookAuthor":"作者A"}]}""")
            }
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertEquals(2, Json.decodeFromString<BookmarkDeleteResult>(deleted.bodyAsText()).deleted)
            val afterBookDelete = Json { ignoreUnknownKeys = true; explicitNulls = false }
                .decodeFromString<BookmarkOverviewResponse>(
                    client.get("/api/bookmarks/overview") { header(io.ktor.http.HttpHeaders.Cookie, cookie) }.bodyAsText(),
                ).items
            assertEquals("书签删光后该书必须从左栏消失", listOf("不在架书"), afterBookDelete.map { it.bookName })

            // 批量按 id 删：最后一条也删掉 → 左栏清空；无参调用 400
            val remaining = client.get("/api/bookmarks?bookName=%E4%B8%8D%E5%9C%A8%E6%9E%B6%E4%B9%A6") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
            }.bodyAsText()
            val lastId = Json { ignoreUnknownKeys = true; explicitNulls = false }
                .decodeFromString<BookmarkListResponse>(remaining).items.first().id
            val deletedByIds = client.post("/api/bookmarks/delete-batch") {
                header(io.ktor.http.HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"ids":[$lastId]}""")
            }
            assertEquals(1, Json.decodeFromString<BookmarkDeleteResult>(deletedByIds.bodyAsText()).deleted)
            val empty = Json { ignoreUnknownKeys = true; explicitNulls = false }
                .decodeFromString<BookmarkOverviewResponse>(
                    client.get("/api/bookmarks/overview") { header(io.ktor.http.HttpHeaders.Cookie, cookie) }.bodyAsText(),
                ).items
            assertTrue("全部删完左栏必须为空", empty.isEmpty())
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/api/bookmarks/delete-batch") {
                    header(io.ktor.http.HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }.status,
            )
        }
    }

    @Test
    fun `a bookmark saved by the reader shows up in the backup bookmark json`() {
        val dbPath = Files.createTempFile("legado-bookmark-backup", ".sqlite").toString()
        var db: Database? = null
        try {
            db = Database(dbPath)
            db.initialize("test-password-1234")
            val saved = db.saveBookmark(
                BookmarkSaveRequest(
                    bookName = "测试书",
                    bookAuthor = "作者",
                    chapterIndex = 12,
                    chapterName = "第13章",
                    chapterPos = 42,
                    bookText = "正文片段",
                    content = "我的备注",
                ),
            )
            assertEquals(42, saved.chapterPos)

            val entries = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes)
            val bookmarkJson = entries.getValue("bookmark.json")
            // 八个字段的名与顺序照抄真实备份，注释（content）必须带上
            assertTrue("缺少书签条目：$bookmarkJson", bookmarkJson.contains("\"bookName\": \"测试书\""))
            assertTrue(bookmarkJson.contains("\"chapterPos\": 42"))
            assertTrue(bookmarkJson.contains("\"bookText\": \"正文片段\""))
            assertTrue(bookmarkJson.contains("\"content\": \"我的备注\""))
            assertTrue(bookmarkJson.contains("\"time\": ${saved.createdAt}"))

            // 删除后导出不应再有它（避免"删了还在备份里"）
            assertTrue(db.deleteBookmark(saved.id))
            val afterDelete = entriesOf(BackupExporter(db).export("", LocalDate.of(2026, 9, 30)).bytes)
            assertEquals("[]", afterDelete.getValue("bookmark.json"))
        } finally {
            db?.close()
            listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { candidate ->
                runCatching { Files.deleteIfExists(Path.of(candidate)) }
            }
        }
    }

    // ---------------------------------------------------------------------

    private fun withApp(block: suspend (io.ktor.client.HttpClient, String, String) -> Unit) {
        val dbPath = Files.createTempFile("legado-bookmark-routes", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-bookmark-routes-dir")
        try {
            testApplication {
                val config = ServerConfig(
                    host = "0.0.0.0", port = 8080, databasePath = dbPath,
                    coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
                    initialAdminPassword = "test-password-1234", secureCookies = false,
                )
                application { legadoApplication(config) }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }
                val login = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("test-password-1234"))
                }
                assertEquals(HttpStatusCode.OK, login.status)
                val cookie = login.headers[io.ktor.http.HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.decodeFromString<LoginResponse>(login.bodyAsText()).csrfToken
                block(client, cookie, csrf)
            }
        } finally {
            listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { candidate ->
                runCatching { Files.deleteIfExists(Path.of(candidate)) }
            }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    private fun entriesOf(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }
}
