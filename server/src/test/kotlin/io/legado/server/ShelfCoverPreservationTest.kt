package io.legado.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * 书架封面的「获取时机」契约。
 *
 * 用户明确要求的规则：**封面只在①刚导入 ②加入书架 ③手动修改这三种时机获取**；
 * 除此之外（尤其是「打开书阅读后返回书架」）一律不许刷新封面。
 *
 * 这条规则曾经被两处破坏，且都表现为**不可逆的数据损失**（实测 2026-10-03 弄丢 2 本书的封面）：
 *
 * 1. 前端每次开书都调一次「加入书架」（`main.tsx` 的 `openReader`），而它携带的 `coverUrl`
 *    对「只靠外链、没有本地副本」的书是 `undefined`；服务端 upsert 又是
 *    `cover_url=excluded.cover_url` 的**无条件覆盖**（`cover_key` 反而有 coalesce 保护）
 *    ⇒ `cover_url` 被抹成 NULL ⇒ 该书封面引用彻底消失。
 * 2. `PUT /api/bookshelf/info`（编辑弹窗）同样把「字段缺省」当成「清空」，
 *    于是「只改书名点保存」也会抹掉 `cover_url`。
 *
 * 本测试锁定修好后的语义：**缺省 = 保留；显式空串 = 清除**（`cover_key` 与 `cover_url` 一视同仁）。
 */
class ShelfCoverPreservationTest {

    private fun withDatabase(block: (Database) -> Unit) {
        val path = Files.createTempFile("legado-cover-preserve", ".sqlite").toString()
        try {
            val database = Database(path)
            database.initialize("password-for-test")
            block(database)
            database.close()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
            Files.deleteIfExists(java.nio.file.Path.of("$path-wal"))
            Files.deleteIfExists(java.nio.file.Path.of("$path-shm"))
        }
    }

    private fun cover(seed: Char) = CachedCover(seed.toString().repeat(64), "image/jpeg")

    private fun write(
        coverUrl: String? = null,
        name: String = "书名",
        alternateSources: List<SearchResult>? = null,
    ) = BookshelfWriteRequest(
        sourceId = "source",
        bookUrl = "book",
        name = name,
        author = "作者",
        tocUrl = "toc",
        coverUrl = coverUrl,
        alternateSources = alternateSources,
    )

    @Test
    fun `re-saving a shelf item without a cover url keeps the stored url`() {
        withDatabase { database ->
            database.saveBookshelf(write(coverUrl = "https://img.example/a.jpg"), null)

            // 前端「打开书」时发出的就是这种请求：只有书目字段，没有 coverUrl
            val afterOpen = database.saveBookshelf(write(), null)

            assertEquals("https://img.example/a.jpg", afterOpen.coverUrl)
            assertEquals("https://img.example/a.jpg", database.getShelfBookByUrl("book")!!.coverUrl)
        }
    }

    @Test
    fun `re-saving a shelf item keeps the existing local cover copy`() {
        withDatabase { database ->
            database.saveBookshelf(write(coverUrl = "https://img.example/a.jpg"), cover('a'))

            // 重复「加入书架」时带来了另一个封面（例如从书源搜索页打开）：
            // 已有本地副本必须优先，不能被顶掉（手动选好的封面同理）。
            val afterReAdd = database.saveBookshelf(write(coverUrl = "https://img.example/b.jpg"), cover('b'))

            assertEquals("a".repeat(64), afterReAdd.coverKey)
            assertEquals("a".repeat(64), database.getShelfBookByUrl("book")!!.coverKey)
        }
    }

    @Test
    fun `a book without a cover still receives one when added to the shelf`() {
        withDatabase { database ->
            // 首次加入书架：允许获取封面（这是规则里明确允许的时机之一）
            val first = database.saveBookshelf(write(coverUrl = "https://img.example/a.jpg"), cover('a'))
            assertEquals("a".repeat(64), first.coverKey)

            // 换一本书，先建成「无封面」，再加入时应补上
            database.saveBookshelf(BookshelfWriteRequest("source", "book-2", "书2", "作者", "toc", null), null)
            assertNull(database.getShelfBookByUrl("book-2")!!.coverKey)
            val filled = database.saveBookshelf(BookshelfWriteRequest("source", "book-2", "书2", "作者", "toc", null), cover('c'))
            assertEquals("c".repeat(64), filled.coverKey)
        }
    }

    @Test
    fun `blank cover url on add is treated as unknown and keeps the stored url`() {
        withDatabase { database ->
            database.saveBookshelf(write(coverUrl = "https://img.example/a.jpg"), null)
            // 空串在「加入书架」这条路径上表示「这次没拿到地址」，不是「清空」
            val afterBlank = database.saveBookshelf(write(coverUrl = ""), null)

            assertEquals("https://img.example/a.jpg", afterBlank.coverUrl)
        }
    }

    @Test
    fun `updating book info without a cover url keeps the stored url`() {
        withDatabase { database ->
            database.saveBookshelf(write(coverUrl = "https://img.example/a.jpg"), cover('a'))

            // 编辑弹窗「只改书名、不动封面」点保存 —— 原实现会把 cover_url 抹成 NULL
            val updated = database.updateBookshelfInfo(
                BookshelfInfoUpdateRequest(sourceId = "source", bookUrl = "book", name = "新书名"),
                null,
            )!!

            assertEquals("新书名", updated.name)
            assertEquals("https://img.example/a.jpg", updated.coverUrl)
            assertEquals("a".repeat(64), updated.coverKey)
        }
    }

    @Test
    fun `updating book info with a blank cover url clears the cover explicitly`() {
        withDatabase { database ->
            database.saveBookshelf(write(coverUrl = "https://img.example/a.jpg"), cover('a'))

            // 「清除封面」按钮：显式空串必须真的清掉（含本地副本）
            val cleared = database.updateBookshelfInfo(
                BookshelfInfoUpdateRequest(sourceId = "source", bookUrl = "book", name = "书名", coverUrl = ""),
                null,
            )!!

            assertNull(cleared.coverUrl)
            assertNull(cleared.coverKey)
        }
    }

    @Test
    fun `submitting a self cover reference keeps the real external url`() {
        withDatabase { database ->
            database.saveBookshelf(write(coverUrl = "https://img.example/a.jpg"), cover('a'))

            // 编辑弹窗在没有外部 URL 时会回退成 /api/covers/<自己的 key>（自引用）。
            // 这种值绝不能入库，但也**不能顺手把真实外链抹掉** —— 退回原值。
            val updated = database.updateBookshelfInfo(
                BookshelfInfoUpdateRequest(
                    sourceId = "source",
                    bookUrl = "book",
                    name = "书名",
                    coverUrl = "/api/covers/${"a".repeat(64)}",
                ),
                null,
            )!!

            assertEquals("https://img.example/a.jpg", updated.coverUrl)
        }
    }

    @Test
    fun `opening a book over http does not touch its cover fields`() {
        val dbPath = Files.createTempFile("legado-cover-route", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-cover-route-covers")
        try {
            // 注意：清理必须在 testApplication 之外 —— 在块内删 SQLite 时应用还开着，
            // Windows 会报「另一个程序正在使用此文件」（这正是本仓库 52 个既有失败的根因）。
            testApplication {
                val config = ServerConfig(
                    host = "0.0.0.0", port = 8080, databasePath = dbPath,
                    coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
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

                fun HttpRequestBuilder.withCsrf() = header("X-CSRF-Token", csrf)

                // 加入书架，带一个真实存在但抓不到的外部地址（抓取失败是常态，不该影响入库）
                val added = client.post("/api/bookshelf") {
                    contentType(ContentType.Application.Json)
                    withCsrf()
                    setBody(BookshelfWriteRequest("source", "book", "书名", "作者", "toc", "https://img.example/a.jpg"))
                }
                assertEquals(HttpStatusCode.OK, added.status)
                val addedItem = added.body<BookshelfItem>()
                assertEquals("https://img.example/a.jpg", addedItem.coverUrl)

                // 前端「打开书」曾经发的请求：同一本书，只有书目字段、没有 coverUrl
                val reopened = client.post("/api/bookshelf") {
                    contentType(ContentType.Application.Json)
                    withCsrf()
                    setBody(BookshelfWriteRequest("source", "book", "书名", "作者", "toc"))
                }
                assertEquals(HttpStatusCode.OK, reopened.status)
                assertEquals("https://img.example/a.jpg", reopened.body<BookshelfItem>().coverUrl)

                // 手动编辑但不碰封面
                val edited = client.put("/api/bookshelf/info") {
                    contentType(ContentType.Application.Json)
                    withCsrf()
                    setBody(BookshelfInfoUpdateRequest(sourceId = "source", bookUrl = "book", name = "改过的书名"))
                }
                assertEquals(HttpStatusCode.OK, edited.status)
                val editedItem = edited.body<BookshelfItem>()
                assertEquals("改过的书名", editedItem.name)
                assertEquals("https://img.example/a.jpg", editedItem.coverUrl)
            }
        } finally {
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(java.nio.file.Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(java.nio.file.Path.of("$dbPath-shm")) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}
