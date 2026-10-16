package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * 方案 E · P1 的**直出路由**级验证。
 *
 * 刻意**不触网**：先在同一个数据目录里预置一份 `ready` 的缓存（文件 + 索引），
 * 再启动应用去读它 —— 这样验证的是「缓存命中 ⇒ 零上游请求 ⇒ 206/304/immutable」
 * 这条**离线能力的核心链路**，而不是网络。
 */
class MangaImageRouteTest {

    @Test
    fun `serves cached image with etag and immutable, honors if-none-match, and cleans up`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-manga-image-route")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        val sourceId = "https://manga.example.com"
        val bookUrl = "$sourceId/book/1"
        val chapterUrl = "$sourceId/chapter/1"
        val upstream = "https://img.example.com/1.jpg"

        // ---- 预置：一份已就绪的图片缓存（等价于"之前已经抓过一次"）
        val seed = Database(dbPath)
        var urlHash: String
        try {
            seed.initialize("test-password-1234")
            // 预置数据等价于"已经升级到 v2 布局的既有部署"：否则启动清理会按 v2 语义把预置缓存清掉
            seed.setSetting(CACHE_LAYOUT_KEY, CACHE_LAYOUT_VERSION)
            val cache = ImageCache(dataDir.resolve("images"), seed)
            val stored = cache.put(sourceId, bookUrl, chapterUrl, upstream, "image/jpeg", jpegBytes(800, 1200))
            urlHash = stored.urlHash
            assertNotNull("预置缓存必须落盘成功", cache.fileFor(stored.cacheKey))
        } finally {
            seed.close()
        }

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                imageCacheDirectory = dataDir.resolve("images"),
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            // 1. 未登录 ⇒ 401
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/images/$urlHash").status)

            val csrf = login(client, "test-password-1234")

            // 2. 命中缓存 ⇒ 200 + 正确类型 + ETag + immutable（**零上游请求**）
            val image = client.get("/api/images/$urlHash")
            assertEquals(HttpStatusCode.OK, image.status)
            assertTrue("内容类型必须是图片", image.headers[HttpHeaders.ContentType]?.startsWith("image/") == true)
            val etag = image.headers[HttpHeaders.ETag]
            assertNotNull("必须带 ETag（= 字节哈希）", etag)
            assertTrue(
                "内容寻址 ⇒ 可以 immutable 长缓存",
                image.headers[HttpHeaders.CacheControl]?.contains("immutable") == true,
            )

            // 3. If-None-Match 命中 ⇒ 304（浏览器与 SW 的二次访问零字节传输）
            val notModified = client.get("/api/images/$urlHash") { header(HttpHeaders.IfNoneMatch, etag!!) }
            assertEquals(HttpStatusCode.NotModified, notModified.status)

            // 4. 未登记的 key ⇒ 404（不能 500，也不能拿它去拼路径）
            assertEquals(HttpStatusCode.NotFound, client.get("/api/images/${"a".repeat(64)}").status)
            // 5. 非法 key（非 64 位 hex）⇒ 404
            assertEquals(HttpStatusCode.NotFound, client.get("/api/images/..%2F..%2Fetc%2Fpasswd").status)

            // 6. 统计接口
            val stats = client.get("/api/books/images/cache/stats")
            assertEquals(HttpStatusCode.OK, stats.status)
            val parsed = stats.body<MangaCacheStats>()
            assertEquals(1, parsed.imageCount)
            assertTrue("磁盘占用必须与表内字节一致", parsed.diskBytes == parsed.totalBytes)
            assertEquals(1, parsed.books.size)
            assertEquals(bookUrl, parsed.books.first().bookUrl)

            // 7. 清理单本 ⇒ 204，且图片随之 404（先表后盘 + 清单一起清）
            val cleared = client.delete("/api/books/images/cache?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}") {
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NoContent, cleared.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/images/$urlHash").status)
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
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

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        out.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0x00, 0x10))
        out.write(ByteArray(14))
        out.write(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x00, 0x11, 0x08))
        out.write(byteArrayOf((height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf(0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01))
        return out.toByteArray()
    }
}
