package io.legado.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/**
 * issue #64：本地书执行「清除缓存（服务器/两者）」后正文完全丢失且无法自动恢复。
 *
 * 根因（已复核）：本地书的目录/正文解析后**只存在于** `book_toc_cache` / `book_content_cache`
 * （原始文件在 `<data>/local_books/<id>.<ext>`），而 `DELETE /api/bookshelf/cache?...&clearData=true`
 * 直接 `DELETE FROM book_content_cache` ⇒ 没有网络书源可重抓 ⇒ 再读全是空白。
 *
 * 定稿方案：**本地书的正文绝不清**（no-op），只清可重建的插图缓存，并回 200 +
 * [CacheClearResponse]（`cleared=false` / `preserved=true`）让前端如实提示；
 * 网络书行为**完全不变**（204 无 body）——本文件即这条契约的回归锁。
 */
class LocalBookClearCacheTest {

    private val localSourceId = LocalBookParser.LOC_BOOK_SOURCE_ID
    private val localBookId = "cache64"
    private val localBookUrl = "local://$localBookId"
    private val localChapterUrl = "local://$localBookId/chapter_1"

    private val netSourceId = "https://net.example.com"
    private val netBookUrl = "$netSourceId/book/1"
    private val netTocUrl = "$netSourceId/toc/1"
    private val netChapterUrl = "$netSourceId/chapter/1"

    private val txt = """
        第一章 开端
        本地正文段落一。
        本地正文段落二。

        第二章 转折
        本地正文段落三。
    """.trimIndent()

    // -----------------------------------------------------------------------
    // 1. 路由级：本地书 clearData=true 后正文仍可读；网络书照常清空（204 契约不变）
    // -----------------------------------------------------------------------

    @Test
    fun `clearing cache keeps local book text readable while network book is still cleared`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-local-clear-cache")
        val dbPath = dataDir.resolve("legado.sqlite").toString()

        // ---- 预置：一本本地书（导入即落库正文）+ 一本网络书（正文缓存已写好）
        val seed = Database(dbPath)
        try {
            seed.initialize(PASSWORD)
            val parsed = LocalBookParser.parseTxt("本地缓存测试.txt", txt.toByteArray(Charsets.UTF_8))
            assertEquals("前置条件：样例 TXT 必须解析出 2 章", 2, parsed.chapters.size)
            seed.importLocalBook(localBookId, parsed, null)
            seed.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = netSourceId,
                    bookUrl = netBookUrl,
                    name = "网络书",
                    author = "某人",
                    tocUrl = netTocUrl,
                ),
                null,
            )
            seed.saveTocCache(netSourceId, netTocUrl, listOf(Chapter(0, "第 1 章", netChapterUrl)))
            seed.cacheBookContent(netSourceId, netBookUrl, netChapterUrl, ChapterContent("第 1 章", "网络正文段落"))
            // 前置条件：本地书 2 章正文 / 网络书 1 章正文都已在缓存里
            assertEquals(2, seed.cachedChapterUrls(localSourceId, localBookUrl).size)
            assertEquals(setOf(netChapterUrl), seed.cachedChapterUrls(netSourceId, netBookUrl))
        } finally {
            seed.close()
        }

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = dataDir.resolve("webdav"),
                localBooksDirectory = dataDir.resolve("local_books"),
                initialAdminPassword = PASSWORD, secureCookies = false,
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val csrf = login(client, PASSWORD)

            // 0. 前置条件：本地书正文可读（旧实现下这里正常，清缓存后才变空白）
            assertTrue(
                "本地书正文必须可读，实际：${readLocalChapter(client, csrf, 0)}",
                readLocalChapter(client, csrf, 0).contains("本地正文段落一"),
            )

            // 1. 不带 clearData（= 取消缓存任务）本来就不删正文 ⇒ 仍可读
            val cancelOnly = client.delete("/api/bookshelf/cache?sourceId=$localSourceId&bookUrl=${enc(localBookUrl)}") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NoContent, cancelOnly.status)
            assertTrue("取消缓存任务绝不能动正文", readLocalChapter(client, csrf, 0).contains("本地正文段落一"))

            // 2. clearData=true（正是 issue 的复现路径）⇒ 200 + preserved 回执，正文**必须还在**
            val localClear = client.delete("/api/bookshelf/cache?sourceId=$localSourceId&bookUrl=${enc(localBookUrl)}&clearData=true") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals("本地书清缓存必须回 200 明确告知（而不是假装清完了）", HttpStatusCode.OK, localClear.status)
            val receipt = localClear.body<CacheClearResponse>()
            assertFalse("本地书正文不能被清", receipt.cleared)
            assertTrue("必须明确告知调用方正文被保留", receipt.preserved)
            assertEquals("local_book_content", receipt.reason)
            assertFalse("服务端提示不得再说'可重新下载'（本地书没有这条路径）", receipt.message.contains("重新下载"))

            // 核心断言（issue ①）：清缓存之后正文仍在，且两章都还在
            assertTrue("清缓存后本地书正文必须仍可读", readLocalChapter(client, csrf, 0).contains("本地正文段落一"))
            assertTrue("清缓存后本地书第 2 章也必须仍可读", readLocalChapter(client, csrf, 1).contains("本地正文段落三"))
            assertEquals("本地书的绿点（已缓存章数）不能被清零", 2, cachedChapterCount(client, localSourceId, localBookUrl))

            // 3. 网络书照常清空（不误伤），且 **204 契约完全没变**
            assertEquals(1, cachedChapterCount(client, netSourceId, netBookUrl))
            val netClear = client.delete("/api/bookshelf/cache?sourceId=${enc(netSourceId)}&bookUrl=${enc(netBookUrl)}&clearData=true") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals("网络书清缓存仍是 204（既有契约/ACCEPT-032 不变）", HttpStatusCode.NoContent, netClear.status)
            assertEquals("204 不得带 body", "", netClear.bodyAsText())
            assertEquals("网络书正文缓存必须照常被清空", 0, cachedChapterCount(client, netSourceId, netBookUrl))
            assertNull("网络书正文行必须真的没了", cachedContentInClient(client, csrf, netSourceId, netBookUrl, netChapterUrl))
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    // -----------------------------------------------------------------------
    // 2. 数据层不变量：clearBookCacheContent 对本地书任何形态都是 no-op
    //    （防未来新增调用点再次误删——issue 的根因就在这一层）
    // -----------------------------------------------------------------------

    @Test
    fun `database never deletes local book content but still clears network books`() {
        val dir = Files.createTempDirectory("legado-local-clear-db")
        val database = Database(dir.resolve("legado.sqlite").toString())
        try {
            database.initialize(PASSWORD) // 建表（表结构在 initialize 里落库）
            val parsed = LocalBookParser.parseTxt("数据层本地书.txt", txt.toByteArray(Charsets.UTF_8))
            database.importLocalBook("dbfix", parsed, null)

            // 两种本地书标识形态都必须被拒绝（sourceId 与 bookUrl 各判一次）
            assertEquals(0, database.clearBookCacheContent(localSourceId, "local://dbfix"))
            assertEquals(0, database.clearBookCacheContent("whatever", "local://dbfix"))
            assertNotNull(
                "本地书正文必须原样保留",
                database.cachedContent(localSourceId, "local://dbfix", "local://dbfix/chapter_1"),
            )

            // 网络书：同一函数照常清空、并把缓存状态复位为 idle
            database.saveBookshelf(
                BookshelfWriteRequest(netSourceId, netBookUrl, "网络书", null, netTocUrl),
                null,
            )
            database.cacheBookContent(netSourceId, netBookUrl, netChapterUrl, ChapterContent("第 1 章", "网络正文段落"))
            assertEquals(1, database.clearBookCacheContent(netSourceId, netBookUrl))
            assertNull(database.cachedContent(netSourceId, netBookUrl, netChapterUrl))
        } finally {
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** 直接打 `/api/books/content` 读本地书第 [chapterIndex] 章的正文（0 基）。 */
    private suspend fun readLocalChapter(client: HttpClient, csrf: String, chapterIndex: Int): String {
        val chapterUrl = "local://$localBookId/chapter_${chapterIndex + 1}"
        val response = client.post("/api/books/content") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(ContentRequest(sourceId = localSourceId, chapterUrl = chapterUrl, bookUrl = localBookUrl))
        }
        assertEquals("本地书章节能读到正文（HTTP ${response.status}）", HttpStatusCode.OK, response.status)
        return response.body<ChapterContent>().content
    }

    /** 服务端 `/api/books/content` 对网络书在无缓存时的回退结果是 404；这里只要"读不到缓存正文"即可。 */
    private suspend fun cachedContentInClient(
        client: HttpClient,
        csrf: String,
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
    ): String? {
        val response = client.post("/api/books/content") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(ContentRequest(sourceId = sourceId, chapterUrl = chapterUrl, bookUrl = bookUrl))
        }
        return if (response.status == HttpStatusCode.OK) response.body<ChapterContent>().content else null
    }

    /** 绿点口径：`/api/bookshelf/cached-chapters` 的已缓存章数。 */
    private suspend fun cachedChapterCount(client: HttpClient, sourceId: String, bookUrl: String): Int =
        client.get("/api/bookshelf/cached-chapters?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}")
            .body<CachedChaptersResponse>().cachedCount

    private fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun login(client: HttpClient, pass: String): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = pass))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return res.body<LoginResponse>().csrfToken
    }

    private companion object {
        const val PASSWORD = "test-password-1234"
    }
}
