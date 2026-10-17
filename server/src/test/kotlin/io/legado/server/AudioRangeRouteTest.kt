package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * 方案 G2 · G4 的**直出路由**级验证。
 *
 * 刻意**不触网**：先在同一个数据目录里预置一份 `ready` 的缓存（文件 + 索引），
 * 再启动应用去读它 —— 这样验证的是「缓存命中 ⇒ 零上游请求 ⇒ 206/416/304/immutable」
 * 这条**离线可听 + 可 seek** 的核心链路，而不是网络。
 *
 * AGENTS.md 明文：**Range 写错 ⇒ 播放器 seek 卡死 / 416**，因此这里把边界逐条钉住。
 */
class AudioRangeRouteTest {

    private val sourceId = "https://audio.example.com"
    private val bookUrl = "$sourceId/book/1"
    private val chapterUrl = "$sourceId/chapter/1"
    private val upstream = "https://cdn.example.com/media/1.mp3"
    private val trackBytes = mp3Bytes(4096)

    @Test
    fun `serves cached audio with range support, etag, immutable and honest errors`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-audio-route")
        val dbPath = dataDir.resolve("legado.sqlite").toString()

        // ---- 预置：一个书源 + 一段已就绪的音频缓存（等价于"之前已经抓过一次"）
        var key: String
        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            seed.importSources(listOf("""{"bookSourceUrl":"$sourceId","bookSourceName":"测试有声书","enabled":true}"""))
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
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                audioCacheDirectory = dataDir.resolve("audio"),
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            // 1. 未登录 ⇒ 401（读也要 session，与漫画图片一致）
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/audio/$key").status)
            val csrf = login(client, "test-password-1234")

            // 2. 命中缓存 ⇒ 200 + 音频类型 + Accept-Ranges + ETag + immutable（**零上游请求**）
            val whole = client.get("/api/audio/$key")
            assertEquals(HttpStatusCode.OK, whole.status)
            assertTrue(
                "内容类型必须是音频（实际 ${whole.headers[HttpHeaders.ContentType]}）",
                whole.headers[HttpHeaders.ContentType]?.startsWith("audio/") == true,
            )
            assertEquals("必须声明支持 Range（否则播放器无法 seek）", "bytes", whole.headers[HttpHeaders.AcceptRanges])
            val etag = whole.headers[HttpHeaders.ETag]
            assertNotNull("必须带 ETag（= key）", etag)
            assertTrue(
                "immutable 长缓存是「二次播放零上游请求」的关键",
                whole.headers[HttpHeaders.CacheControl]?.contains("immutable") == true,
            )
            assertEquals(trackBytes.size, whole.body<ByteArray>().size)

            // 3. 单段 Range ⇒ 206 + 正确的 Content-Range 与长度
            val partial = client.get("/api/audio/$key") { header(HttpHeaders.Range, "bytes=0-1023") }
            assertEquals(HttpStatusCode.PartialContent, partial.status)
            assertEquals(
                "Content-Range 必须精确到字节",
                "bytes 0-1023/${trackBytes.size}",
                partial.headers[HttpHeaders.ContentRange],
            )
            assertEquals(1024, partial.body<ByteArray>().size)

            // 4. 尾部 Range（后缀式）⇒ 206
            val suffix = client.get("/api/audio/$key") { header(HttpHeaders.Range, "bytes=-512") }
            assertEquals(HttpStatusCode.PartialContent, suffix.status)
            assertEquals(
                "bytes=-512",
                "bytes ${trackBytes.size - 512}-${trackBytes.size - 1}/${trackBytes.size}",
                suffix.headers[HttpHeaders.ContentRange],
            )
            assertEquals(512, suffix.body<ByteArray>().size)

            // 5. 越界 ⇒ 416 + `bytes */size`（RFC 7233 要求，否则播放器无法自我纠正）
            val unsatisfiable = client.get("/api/audio/$key") { header(HttpHeaders.Range, "bytes=${trackBytes.size}-") }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, unsatisfiable.status)
            assertEquals("bytes */${trackBytes.size}", unsatisfiable.headers[HttpHeaders.ContentRange])

            // 6. If-None-Match 命中 ⇒ 304（seek 时的一批重复请求零字节传输）
            val notModified = client.get("/api/audio/$key") { header(HttpHeaders.IfNoneMatch, etag!!) }
            assertEquals(HttpStatusCode.NotModified, notModified.status)

            // 7. 非法 key / 未登记的 key ⇒ 404（绝不拿它去拼路径，也绝不 500）
            assertEquals(HttpStatusCode.NotFound, client.get("/api/audio/not-a-key").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/audio/${"a".repeat(64)}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/audio/..%2F..%2Fetc%2Fpasswd").status)

            // 8. 清单接口：本地已登记 ⇒ 完全回源被跳过，且**绝不下发上游 URL**
            val manifest = client.post("/api/books/audio") {
                contentType(ContentType.Application.Json)
                setBody(AudioManifestRequest(sourceId = sourceId, bookUrl = bookUrl, chapterUrl = chapterUrl, chapterIndex = 0))
            }
            assertEquals(HttpStatusCode.OK, manifest.status)
            val parsed = manifest.body<AudioChapterManifest>()
            assertEquals(1, parsed.total)
            assertEquals(1, parsed.ready)
            assertEquals("ready", parsed.state)
            assertEquals(key, parsed.tracks.first().key)
            assertFalse(
                "清单**绝不能**下发上游 URL（否则前端会绕过缓存直连，防盗链/离线全失效）",
                manifest.body<String>().contains("cdn.example.com"),
            )

            // 9. 时长回传（前端 loadedmetadata）⇒ 204，且只有正数才写库
            val duration = client.post("/api/audio/duration") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(AudioDurationRequest(key = key, durationMs = 183_452, positionMs = 0))
            }
            assertEquals(HttpStatusCode.NoContent, duration.status)

            // 10. 绿点口径：整章就绪才返回该章
            val cached = client.get("/api/books/audio/cached-chapters?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}")
            assertEquals(HttpStatusCode.OK, cached.status)
            assertEquals(listOf(chapterUrl), cached.body<CachedChaptersResponse>().cachedChapterUrls)

            // 11. 统计 + pin 免死金牌
            val stats = client.get("/api/audio/cache/stats")
            assertEquals(HttpStatusCode.OK, stats.status)
            val parsedStats = stats.body<AudioCacheStats>()
            assertEquals(1, parsedStats.trackCount)
            assertEquals(trackBytes.size.toLong(), parsedStats.totalBytes)
            assertEquals(parsedStats.totalBytes, parsedStats.diskBytes)
            assertEquals(20L * 1024 * 1024 * 1024, parsedStats.quotaBytes)

            val pin = client.put("/api/audio/cache/pin?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}&pinned=true") {
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.OK, pin.status)
            assertTrue("pin 必须写进统计（缓存管理 UI 要能看出这本书为什么删不掉）", client.get("/api/audio/cache/stats").body<AudioCacheStats>().books.first().pinned)

            // 12. 删除单段 ⇒ 204，且音频随之 404（先表后盘）
            val cleared = client.delete("/api/audio/cache?key=$key") { header("X-CSRF-Token", csrf) }
            assertEquals(HttpStatusCode.NoContent, cleared.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/audio/$key").status)
            assertEquals(0, client.get("/api/audio/cache/stats").body<AudioCacheStats>().trackCount)
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `rejects a malformed manifest request without touching the database`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-audio-route-bad")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                audioCacheDirectory = dataDir.resolve("audio"),
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            login(client, "test-password-1234")

            val missingSource = client.post("/api/books/audio") {
                contentType(ContentType.Application.Json)
                setBody(AudioManifestRequest(sourceId = "", chapterUrl = chapterUrl))
            }
            assertEquals(HttpStatusCode.BadRequest, missingSource.status)

            val missingChapter = client.post("/api/books/audio") {
                contentType(ContentType.Application.Json)
                setBody(AudioManifestRequest(sourceId = sourceId, chapterUrl = ""))
            }
            assertEquals(HttpStatusCode.BadRequest, missingChapter.status)

            val unknownSource = client.post("/api/books/audio") {
                contentType(ContentType.Application.Json)
                setBody(AudioManifestRequest(sourceId = sourceId, chapterUrl = chapterUrl))
            }
            assertEquals("书源不存在必须 404（而不是 500）", HttpStatusCode.NotFound, unknownSource.status)
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

    /** 一段**恰好 [size] 字节**的假 MP3（ID3v2 头 + 帧同步字节填充）—— 长度必须精确，否则 Content-Range 断言会失真。 */
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
