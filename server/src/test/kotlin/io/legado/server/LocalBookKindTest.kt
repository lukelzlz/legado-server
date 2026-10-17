package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * 本地导入书的**类型**（issue #66）。
 *
 * 用户原话：「**本地书导入时右上角标签添加"本地"，数据库里也要有，漫画有声小说同理**」
 * 「**本地书根本不要有清除缓存的按钮**」「**缓存按钮也不该有**」。
 *
 * 因此这里锁死四件事：
 * 1. `book_shelf.book_kind` 的取值表是**小写四类** `comic / audio / novel / local`，
 *    本地导入书落 `local`（`loc_book` 只是 `source_id`，**不是** `book_kind` 的取值）；
 * 2. 老数据（`null` / 空 / 早年误写成 `novel`）由**幂等回填**纠正成 `local`，且不碰已有四类值；
 * 3. `book_kind='local'` 之后，所有按书型分发的分支（`cached-chapters`、`bookshelfWithMediaCache`、
 *    缓存路由、备份导出 `type=264`）**不退化、不抛异常**；
 * 4. 数据层守卫：**本地书恒不删正文**（防 UI 绕过；`clearBookCacheContent` 直接返回 0）。
 */
class LocalBookKindTest {

    private companion object {
        const val LOC = LocalBookParser.LOC_BOOK_SOURCE_ID
        const val LOCAL_URL = "local://book01"
    }

    // ------------------------------------------------------------------
    // ① 导入即落库 'local'
    // ------------------------------------------------------------------

    @Test
    fun `importing a local book writes book_kind local`() = withDatabase("legado-local-kind-import") { db ->
        val item = db.importLocalBook("book01", parsedBook("本地书"), null)

        assertEquals("接口返回的类型必须是小写 local", "local", item.bookKind)
        assertEquals("book_shelf.book_kind 必须落库 local", "local", db.rawBookKind(LOC, LOCAL_URL))
        assertEquals("读取口径同样必须是 local", "local", db.getBookKind(LOC, LOCAL_URL))
        assertEquals("BookKind 常量必须还原成 LOCAL", BookKind.LOCAL, BookKind.from(item.bookKind))
        // `loc_book` 是**书源 id**，绝不能拿它当 book_kind 的取值
        assertNotEquals("loc_book", db.rawBookKind(LOC, LOCAL_URL))
    }

    @Test
    fun `re-importing a local book keeps and corrects book_kind local`() = withDatabase("legado-local-kind-reimport") { db ->
        db.importLocalBook("book01", parsedBook("本地书"), null)

        // 模拟历史误判：本地书曾被写成 novel（老逻辑只认 tab/源声明）
        db.setBookKindSilently(LOC, LOCAL_URL, "novel")
        assertEquals("novel", db.rawBookKind(LOC, LOCAL_URL))

        // 重新导入（同一 bookId ⇒ upsert 命中已有行）后必须纠正回 local
        db.importLocalBook("book01", parsedBook("本地书"), null)
        assertEquals("local", db.rawBookKind(LOC, LOCAL_URL))
    }

    // ------------------------------------------------------------------
    // ② 幂等回填：老数据自动变好，且不污染另外三类
    // ------------------------------------------------------------------

    @Test
    fun `backfill marks legacy local rows as local and never rewrites the other three kinds`() =
        withDatabase("legado-local-kind-backfill") { db ->
            // 老本地书：book_kind 为空 / 被误写成 novel
            db.saveBookshelf(
                BookshelfWriteRequest(LOC, "local://legacy-a", "老本地书 A", tocUrl = "local://legacy-a/toc"),
                null,
            )
            db.saveBookshelf(
                BookshelfWriteRequest(LOC, "local://legacy-b", "老本地书 B", tocUrl = "local://legacy-b/toc", bookKind = "novel"),
                null,
            )
            // 对照组一：聚合源里的漫画（书自己 tab=漫画，源不声明类型）
            val comicUrl = bookUrlWithTab("漫画", "c1")
            db.saveBookshelf(
                BookshelfWriteRequest("https://aggregate.example", comicUrl, "聚合漫画", tocUrl = "https://aggregate.example/toc"),
                null,
            )
            // 对照组二：在线小说（已经是 novel，回填不该无谓改它）
            val novelUrl = "https://novel.example/book/1"
            db.saveBookshelf(
                BookshelfWriteRequest("https://novel.example", novelUrl, "在线小说", tocUrl = "https://novel.example/toc", bookKind = "novel"),
                null,
            )

            assertNull("未回填前本地书是「未分类」", db.rawBookKind(LOC, "local://legacy-a"))
            assertEquals("本地书被误写成了 novel", "novel", db.rawBookKind(LOC, "local://legacy-b"))

            db.backfillBookKindFromSources()

            assertEquals("local", db.rawBookKind(LOC, "local://legacy-a"))
            assertEquals("早年误写成 novel 的本地书也必须纠正", "local", db.rawBookKind(LOC, "local://legacy-b"))
            assertEquals("回填不得破坏 tab 推导出的 comic", "comic", db.rawBookKind("https://aggregate.example", comicUrl))
            assertEquals("在线小说保持 novel", "novel", db.rawBookKind("https://novel.example", novelUrl))

            // 幂等：反复回填结果不变（不抛、不换值）
            db.backfillBookKindFromSources()
            db.backfillBookKindFromSources()
            assertEquals("local", db.rawBookKind(LOC, "local://legacy-a"))
            assertEquals("local", db.rawBookKind(LOC, "local://legacy-b"))
            assertEquals("comic", db.rawBookKind("https://aggregate.example", comicUrl))
            assertEquals("novel", db.rawBookKind("https://novel.example", novelUrl))
        }

    // ------------------------------------------------------------------
    // ③ 取值表与身份判据（小写四类，LOCAL 不是 NOVEL 的特例）
    // ------------------------------------------------------------------

    @Test
    fun `book kind wire values are exactly the four lowercase words`() {
        assertEquals("novel", BookKind.NOVEL.wire)
        assertEquals("comic", BookKind.COMIC.wire)
        assertEquals("audio", BookKind.AUDIO.wire)
        assertEquals("local", BookKind.LOCAL.wire)

        // `from()` 的大小写处理与既有三类完全一致（trim + lowercase）
        assertEquals(BookKind.LOCAL, BookKind.from("local"))
        assertEquals(BookKind.LOCAL, BookKind.from("  LOCAL "))
        assertEquals(BookKind.COMIC, BookKind.from("Comic"))
        assertEquals(BookKind.AUDIO, BookKind.from("AUDIO"))
        assertNotEquals("local 绝不能被当成 novel 的特例", BookKind.NOVEL, BookKind.from("local"))

        // 身份判据：loc_book 虚拟书源 / local:// 路径
        assertTrue(BookKind.isLocalRef(LOC, "whatever"))
        assertTrue(BookKind.isLocalRef("legacy-id", "local://x/chapter_1"))
        assertFalse(BookKind.isLocalRef("https://novel.example", "https://novel.example/book/1"))

        // 本地性是**身份**，不从载荷/显式值推导（写入由导入 / 回填 / 加入书架路由显式负责）
        assertNull(BookKind.resolveOrNull(null, "local://x", null))
        assertNull(BookKind.resolveOrNull(null, "content://x/book.epub", null))
    }

    // ------------------------------------------------------------------
    // ④ 数据层守卫：本地书恒不删正文（防 UI 绕过）
    // ------------------------------------------------------------------

    @Test
    fun `clearing server cache never deletes a local book body but still clears network books`() =
        withDatabase("legado-local-kind-clear") { db ->
            db.importLocalBook("book02", parsedBook("本地书"), null)
            val localUrl = "local://book02"
            assertEquals("导入即写满正文", 2, db.cachedChapterUrls(LOC, localUrl).size)

            val deleted = db.clearBookCacheContent(LOC, localUrl)

            assertEquals("本地书正文恒不删（删了线上无处重新下载）", 0, deleted)
            assertEquals("正文必须原样保留", 2, db.cachedChapterUrls(LOC, localUrl).size)
            assertEquals("缓存状态仍是 ready", "ready", db.listBookshelf().single().cacheState)

            // 回归保护：网络书照旧可清（守卫只针对本地书）
            val onlineUrl = "https://novel.example/book/1"
            db.saveBookshelf(
                BookshelfWriteRequest("https://novel.example", onlineUrl, "网络书", tocUrl = "https://novel.example/toc"),
                null,
            )
            db.cacheBookContent("https://novel.example", onlineUrl, "https://novel.example/c/1", ChapterContent("第1章", "正文"))
            assertEquals(1, db.clearBookCacheContent("https://novel.example", onlineUrl))
            assertEquals(0, db.cachedChapterUrls("https://novel.example", onlineUrl).size)
        }

    // ------------------------------------------------------------------
    // ⑤ 按书型分发的分支不退化：书架列表 / 备份导出 type=264
    // ------------------------------------------------------------------

    @Test
    fun `bookshelfWithMediaCache keeps local items untouched instead of throwing`() = withDatabase("legado-local-kind-media") { db ->
        db.importLocalBook("book03", parsedBook("本地书", chapters = 3), null)

        val item = bookshelfWithMediaCache(db, null, null).single()

        assertEquals("local", item.bookKind)
        assertEquals("本地书的缓存进度就是已落库正文行数", 3, item.cachedChapters)
        assertEquals(3, item.totalChapters)
        assertEquals("ready", item.cacheState)
        assertNull(item.cacheError)
    }

    @Test
    fun `backup export writes type 264 for local books`() = withDatabase("legado-local-kind-export") { db ->
        db.importLocalBook("book04", parsedBook("本地导出的书"), null)
        db.saveBookshelf(
            BookshelfWriteRequest("https://novel.example", "https://novel.example/book/1", "网络书", tocUrl = "https://novel.example/toc"),
            null,
        )

        val rows = bookshelfRows(BackupExporter(db).export("", LocalDate.of(2026, 10, 17)).bytes)
            .associateBy { it["name"]!!.jsonPrimitive.content }

        assertEquals("本地书 = local(256) | text(8) = 264", 264, rows.getValue("本地导出的书")["type"]!!.jsonPrimitive.int)
        assertEquals("在线小说仍是 8（回归保护）", 8, rows.getValue("网络书")["type"]!!.jsonPrimitive.int)
    }

    // ------------------------------------------------------------------
    // ⑥ 路由端到端：导入 ⇒ 接口拿到 local ⇒ cached-chapters 正常 ⇒ 清缓存仍不删正文
    // ------------------------------------------------------------------

    @Test
    fun `local book routes keep local kind and never lose the body on clear cache`() {
        val tempDir = Files.createTempDirectory("legado-local-kind-routes")
        val dbPath = tempDir.resolve("legado.sqlite").toString()
        try {
            // 注意：清理必须在 testApplication 之外 —— 块内删 SQLite 时应用还开着，
            // Windows 会报「另一个程序正在使用此文件」。
            testApplication {
                val config = ServerConfig(
                    host = "0.0.0.0", port = 8080, databasePath = dbPath,
                    coverCacheDirectory = tempDir.resolve("covers"),
                    webDavDirectory = tempDir.resolve("webdav"),
                    localBooksDirectory = tempDir.resolve("local_books"),
                    initialAdminPassword = "test-password-1234", secureCookies = false,
                )
                application { legadoApplication(config) }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    install(HttpCookies)
                }

                val login = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("test-password-1234"))
                }
                assertEquals(HttpStatusCode.OK, login.status)
                val csrf = login.body<LoginResponse>().csrfToken

                // 1) 走真实上传入口导入一本本地 TXT（与书架「导入本地 TXT / EPUB」同一条路径）
                val imported = client.post("/api/bookshelf/import-local") {
                    header("X-CSRF-Token", csrf)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "file", sampleTxt().toByteArray(StandardCharsets.UTF_8),
                                    Headers.build {
                                        append(HttpHeaders.ContentType, "text/plain")
                                        append(HttpHeaders.ContentDisposition, "filename=\"自动导入书.txt\"")
                                    },
                                )
                            },
                        ),
                    )
                }
                assertEquals(HttpStatusCode.OK, imported.status)
                val summary = imported.body<LocalBookImportResponse>()
                assertEquals("上传的本地书必须导入成功", 1, summary.imported)
                val bookUrl = summary.results.single().bookUrl!!
                assertTrue("本地书的 bookUrl 必须是 local://", bookUrl.startsWith("local://"))

                // 2) 书架接口必须把类型下发成 local（前端据此渲染「本地」角标）
                val shelf = client.get("/api/bookshelf").body<List<BookshelfItem>>()
                val localItem = shelf.single { it.bookUrl == bookUrl }
                assertEquals("local", localItem.bookKind)
                assertEquals(LOC, localItem.sourceId)

                // 3) cached-chapters 对 local 不得抛异常，且正文（=本地书的"已缓存"）全在
                val cachedRes = client.get("/api/bookshelf/cached-chapters") {
                    parameter("sourceId", LOC)
                    parameter("bookUrl", bookUrl)
                }
                assertEquals(HttpStatusCode.OK, cachedRes.status)
                val beforeClear = cachedRes.body<CachedChaptersResponse>()
                // TXT 里「前言 + 第一章 + 第二章」⇒ 3 章（前言也是一章，这是解析器既有语义）
                assertEquals("三章正文都应在缓存口径里", 3, beforeClear.cachedCount)
                assertEquals(3, beforeClear.totalChapters)

                // 4) 对本地书打「清除服务器缓存」（clearData=true）：契约不变（204），正文绝不能丢
                val cleared = client.delete("/api/bookshelf/cache") {
                    header("X-CSRF-Token", csrf)
                    parameter("sourceId", LOC)
                    parameter("bookUrl", bookUrl)
                    parameter("clearData", "true")
                }
                assertEquals(HttpStatusCode.NoContent, cleared.status)

                val afterClear = client.get("/api/bookshelf/cached-chapters") {
                    parameter("sourceId", LOC)
                    parameter("bookUrl", bookUrl)
                }.body<CachedChaptersResponse>()
                assertEquals("本地书正文是导入时解析落库的，清缓存绝不能删它", beforeClear.cachedCount, afterClear.cachedCount)

                // 5) 「缓存全本」对本地书也只回 ready，不排网络任务（前端已隐藏入口，这里是防绕过）
                val queued = client.post("/api/bookshelf/cache") {
                    contentType(ContentType.Application.Json)
                    header("X-CSRF-Token", csrf)
                    setBody(BookCacheRangeRequest(LOC, bookUrl))
                }
                assertEquals(HttpStatusCode.Accepted, queued.status)
            }
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun parsedBook(title: String, chapters: Int = 2) = ParsedBook(
        title = title,
        author = "作者",
        intro = null,
        coverBytes = null,
        coverContentType = null,
        chapters = (1..chapters).map { ParsedChapter("第${it}章 标题", "第${it}章的正文。") },
    )

    private fun sampleTxt(): String = buildString {
        appendLine("这是前言。")
        appendLine()
        appendLine("第一章 开端")
        appendLine("主角醒来了。")
        appendLine()
        appendLine("第二章 后续")
        appendLine("故事继续。")
    }

    private fun bookUrlWithTab(tab: String, id: String): String {
        val payload = Base64.getEncoder()
            .encodeToString("""{"book_id":"$id","sources":"测试","tab":"$tab","url":""}""".toByteArray(StandardCharsets.UTF_8))
        return "data:;base64,$payload"
    }

    /** 取导出包里的 `bookshelf.json` 行。 */
    private fun bookshelfRows(bytes: ByteArray): List<JsonObject> {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "bookshelf.json") {
                    val text = zip.readBytes().toString(StandardCharsets.UTF_8)
                    return Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
                }
                zip.readBytes()
            }
        }
        error("导出包里没有 bookshelf.json")
    }

    private fun withDatabase(prefix: String, block: (Database) -> Unit) {
        val tempDir = Files.createTempDirectory(prefix)
        val dbPath = tempDir.resolve("legado.sqlite").toString()
        var db: Database? = null
        try {
            db = Database(dbPath)
            db.initialize("test-password-1234")
            block(db)
        } finally {
            db?.close()
            listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}
