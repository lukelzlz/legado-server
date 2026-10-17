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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 「取消缓存」按钮的路由级契约：`POST /api/books/audio/prefetch/cancel`
 * ——**只取消，不删缓存**。
 *
 * ## 这条测试防的是什么
 * 目录底栏的「取消缓存」如果图省事直接复用 `DELETE /audio/cache`
 * （那是"清缓存顺带停任务"），用户点一下就会把**已经下好的整本音频连同清单**删光，
 * 而且不可恢复。所以必须单开一条只调 `cancel()` 的路由，并用测试把它钉死：
 * 取消之后 **缓存行仍在、字节仍可直出（可 seek）、绿点仍在**，只有"正在跑的任务"没了。
 *
 * 刻意**不触网**：先把一段 ready 的音频缓存预置进 `AudioCache`（等价于"之前已经下好"），
 * 于是取消前后都不需要任何上游请求。
 */
class AudioPrefetchCancelRouteTest {

    private val sourceId = "https://audio.example.com"
    private val bookUrl = "$sourceId/book/1"
    private val chapterUrl = "$sourceId/chapter/1"
    private val upstream = "https://cdn.example.com/media/1.mp3"

    @Test
    fun `cancel route requires auth, validates input and never deletes cached audio`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-audio-prefetch-cancel")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        val trackBytes = mp3Bytes(4096)

        // ---- 预置：一个书源 + 一段已就绪的音频缓存（等价于"之前已经抓过一次"）
        var key: String
        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            seed.importSources(listOf("""{"bookSourceUrl":"$sourceId","bookSourceName":"测试有声书","enabled":true}"""))
            seed.saveTocCache(sourceId, bookUrl, listOf(Chapter(0, "第 1 集", chapterUrl)))
            val cache = AudioCache(dataDir.resolve("audio"), seed)
            val rows = cache.registerChapter(sourceId, bookUrl, chapterUrl, 0, listOf(AudioTrackSpec(upstream)))
            key = rows.first().cacheKey
            cache.store.writeStream(key) { it.write(trackBytes) }
            cache.markReady(rows.first(), "audio/mpeg", trackBytes.size.toLong())
            assertNotNull("预置缓存必须落盘成功", cache.fileFor(key))
        } finally {
            seed.close()
        }

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"), webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                audioCacheDirectory = dataDir.resolve("audio"),
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val body = AudioPrefetchRequest(sourceId = sourceId, bookUrl = bookUrl)

            // 1. 未登录 ⇒ 401（写接口：它会改服务端的任务状态，与缓存路由同款鉴权）
            val anonymous = client.post("/api/books/audio/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

            val csrf = login(client, "test-password-1234")

            // 2. 缺 sourceId / bookUrl ⇒ 400（与缓存路由同款错误口径）
            val noSource = client.post("/api/books/audio/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body.copy(sourceId = ""))
            }
            assertEquals(HttpStatusCode.BadRequest, noSource.status)

            val noBook = client.post("/api/books/audio/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body.copy(bookUrl = null))
            }
            assertEquals(HttpStatusCode.BadRequest, noBook.status)

            // 取消之前：统计里有这一行、字节可直出、绿点已亮
            val before = client.get("/api/audio/cache/stats").body<AudioCacheStats>()
            assertEquals(1, before.trackCount)
            assertEquals(trackBytes.size.toLong(), before.totalBytes)
            assertEquals(HttpStatusCode.OK, client.get("/api/audio/$key").status)
            assertTrue("前置条件：整章就绪后绿点必须已亮", greenDot(client))

            // 3. 取消 ⇒ 204（没有正在跑的任务也照样成功：幂等）
            val cancelled = client.post("/api/books/audio/prefetch/cancel") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, cancelled.status)

            // 4. **只取消、不删缓存**：缓存行 / 字节 / 直出 / 绿点**全部原样**
            val after = client.get("/api/audio/cache/stats").body<AudioCacheStats>()
            assertEquals("取消缓存绝不能删掉已下好的音频行", before.trackCount, after.trackCount)
            assertEquals("取消缓存绝不能删掉已下好的字节", before.totalBytes, after.totalBytes)
            assertEquals(HttpStatusCode.OK, client.get("/api/audio/$key").status)
            assertTrue("取消缓存绝不能把绿点弄灭（清单必须留着）", greenDot(client))

            // 5. 取消之后不再有"正在跑"的任务（状态接口给空列表，而不是 500）
            val status = client.get("/api/books/audio/prefetch")
            assertEquals(HttpStatusCode.OK, status.status)
            assertTrue(status.body<List<AudioPrefetchStatus>>().isEmpty())
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    private suspend fun greenDot(client: io.ktor.client.HttpClient): Boolean {
        val res = client.get("/api/books/audio/cached-chapters?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}")
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

    /** 一段**恰好 [size] 字节**的假 MP3（ID3v2 头 + 帧同步字节填充）。 */
    private fun mp3Bytes(size: Int): ByteArray {
        val header = ByteArray(10).also {
            "ID3".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
            it[3] = 0x03
        }
        if (size <= header.size) return header.copyOf(size)
        val bytes = header.copyOf(size)
        var offset = header.size
        while (offset + 4 <= size) {
            bytes[offset] = 0xff.toByte(); bytes[offset + 1] = 0xfb.toByte()
            bytes[offset + 2] = 0x90.toByte(); bytes[offset + 3] = 0x00
            offset += 4
        }
        return bytes
    }
}
