package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * 「取消缓存」按钮的路由级契约：`POST /api/books/images/prefetch/cancel`
 * ——**只取消，不删缓存**。
 *
 * ## 这条测试防的是什么
 * 目录底栏的「取消缓存」如果图省事直接复用 `DELETE /books/images/cache`
 * （那是"清缓存顺带停任务"），用户点一下就会把**已经下好的整本图片连同清单**删光，
 * 而且不可恢复。所以必须单开一条只调 `cancel()` 的路由，并用测试把它钉死：
 * 取消之后 **缓存行仍在、字节仍可直出、绿点仍在**，只有"正在跑的任务"没了。
 *
 * 刻意**不触网**：先把 L1 字节喂进 `ImageCache`（等价于"之前已经下好"），
 * 于是取消前后都不需要任何上游请求。
 */
class MangaPrefetchCancelRouteTest {

    private val sourceId = "https://manga.example.com"
    private val bookUrl = "$sourceId/book/1"
    private val chapterUrl = "ch-0"
    private val upstream = "https://img.example.com/1.jpg"

    @Test
    fun `cancel route requires auth, validates input and never deletes cached bytes`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-manga-prefetch-cancel")
        val dbPath = dataDir.resolve("legado.sqlite").toString()

        var urlHash: String
        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            // 预置缓存等价于"已经升级到 v3 布局的既有部署"：否则启动迁移会把预置缓存按 v1 清掉
            // （cache_layout 未设置 ⇒ migrateCacheLayout 走"清掉重下"分支，实测就是这里 0 行/0 字节）
            seed.setSetting(CACHE_LAYOUT_KEY, CACHE_LAYOUT_VERSION)
            seed.importSources(listOf("""{"bookSourceUrl":"$sourceId","bookSourceName":"测试漫画源","bookSourceType":2}"""), applyGroups = false)
            seed.saveBookshelf(
                BookshelfWriteRequest(sourceId = sourceId, bookUrl = bookUrl, name = "测试漫画", tocUrl = bookUrl),
                cover = null,
            )
            seed.saveTocCache(sourceId, bookUrl, listOf(Chapter(0, "第 1 话", chapterUrl)))
            // 章节清单也要登记：绿点（`/bookshelf/cached-chapters`）读的是 manga_chapter_cache 的 state，
            // 只喂 L1 字节而不登记清单的话，`refreshChapterReadiness` 无处可写 ⇒ 绿点永远不亮（实测踩过）
            seed.saveMangaChapter(
                MangaChapterRecord(
                    sourceId = sourceId, bookUrl = bookUrl, chapterUrl = chapterUrl, chapterIndex = 0,
                    imagesJson = encodeMangaImageUrls(listOf(upstream)), total = 1, state = "pending",
                ),
            )
            val cache = ImageCache(dataDir.resolve("images"), seed, ServerConfig.DEFAULT_IMAGE_CACHE_QUOTA_BYTES)
            val stored = cache.put(sourceId, bookUrl, chapterUrl, upstream, "image/jpeg", jpeg(seed = 7), chapterIndex = 0, segmentIndex = 0)
            urlHash = stored.urlHash
            // 整章 ready ⇒ 绿点亮（取消**不得**把它弄灭）
            cache.refreshChapterReadiness(sourceId, bookUrl, chapterUrl)
        } finally {
            seed.close()
        }

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"), webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                comicCacheDirectory = dataDir.resolve("images"),
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val body = MangaPrefetchRequest(sourceId = sourceId, bookUrl = bookUrl)

            // 1. 未登录 ⇒ 401（写接口：它会改服务端的任务状态，与预取路由同款鉴权）
            val anonymous = client.post("/api/books/images/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

            val csrf = login(client, "test-password-1234")

            // 2. 缺 sourceId / bookUrl ⇒ 400（与预取路由同款错误口径）
            val noSource = client.post("/api/books/images/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body.copy(sourceId = ""))
            }
            assertEquals(HttpStatusCode.BadRequest, noSource.status)

            val noBook = client.post("/api/books/images/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body.copy(bookUrl = null))
            }
            assertEquals(HttpStatusCode.BadRequest, noBook.status)

            // 取消之前：绿点已亮、统计里有这一行
            val before = client.get("/api/books/images/cache/stats").body<MangaCacheStats>()
            assertEquals(1, before.imageCount)
            assertTrue(before.totalBytes > 0)
            assertTrue(
                "前置条件：整章就绪后绿点必须已亮",
                greenDot(client),
            )

            // 3. 取消 ⇒ 204（没有正在跑的任务也照样成功：幂等）
            val cancelled = client.post("/api/books/images/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, cancelled.status)

            // 4. **只取消、不删缓存**：缓存行 / 字节 / 绿点 / 直出**全部原样**
            val after = client.get("/api/books/images/cache/stats").body<MangaCacheStats>()
            assertEquals("取消缓存绝不能删掉已下好的图片行", before.imageCount, after.imageCount)
            assertEquals("取消缓存绝不能删掉已下好的字节", before.totalBytes, after.totalBytes)
            assertEquals(HttpStatusCode.OK, client.get("/api/images/$urlHash").status)
            assertTrue("取消缓存绝不能把绿点弄灭（清单必须留着）", greenDot(client))

            // 5. 取消之后不再有"正在跑"的任务（状态接口给空列表，而不是 500）
            val status = client.get("/api/books/images/prefetch")
            assertEquals(HttpStatusCode.OK, status.status)
            assertTrue(status.body<List<MangaPrefetchStatus>>().isEmpty())
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    private suspend fun greenDot(client: io.ktor.client.HttpClient): Boolean {
        val res = client.get("/api/bookshelf/cached-chapters?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}")
        if (res.status != HttpStatusCode.OK) return false
        return chapterUrl in res.body<CachedChaptersResponse>().cachedChapterUrls
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun login(client: io.ktor.client.HttpClient, pass: String): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = pass))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return res.body<LoginResponse>().csrfToken
    }

    private fun jpeg(seed: Int = 0, width: Int = 800, height: Int = 1200): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        out.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0x00, 0x10))
        out.write(ByteArray(14))
        out.write(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x00, 0x11, 0x08))
        out.write(byteArrayOf((height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf(0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01))
        out.write(ByteArray(64))
        val bytes = out.toByteArray()
        bytes[bytes.size - 1] = seed.toByte()
        return bytes
    }
}
