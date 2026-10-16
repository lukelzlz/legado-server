package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * P2 路由层契约：预取、免死金牌、以及**绿点按 kind 换源**。
 *
 * 这条链路特意做成**完全离线**端到端：先把 L1 字节喂进去（模拟"用户已经看过这几张"），
 * 于是预取不需要任何上游请求就能把整章推到 `ready`，绿点随即亮起。
 * 这样测的是 P2 真正的交付物（状态机 + 契约），而不是网络。
 */
class MangaPrefetchRouteTest {

    private val comicSource = """{
        "bookSourceUrl": "https://manga.example.com",
        "bookSourceName": "测试漫画源",
        "bookSourceType": 2
    }"""

    private val sourceId = "https://manga.example.com"
    private val bookUrl = "https://manga.example.com/book/1"
    private val imageUrls = (0..2).map { "https://img.example/$it.jpg" }

    @Test
    fun `prefetch route auth validation accepted pin and green dot`() = testApplication {
        val dbPath = Files.createTempFile("legado-manga-prefetch-route", ".sqlite").toString()
        val dataDir = Files.createTempDirectory("legado-manga-prefetch-route")

        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            seed.importSources(listOf(comicSource), applyGroups = false)
            seed.saveBookshelf(
                BookshelfWriteRequest(sourceId = sourceId, bookUrl = bookUrl, name = "测试漫画", tocUrl = bookUrl),
                cover = null,
            )
            seed.saveTocCache(sourceId, bookUrl, listOf(Chapter(0, "第 1 话", "ch-0")))
            seed.saveMangaChapter(
                MangaChapterRecord(
                    sourceId = sourceId, bookUrl = bookUrl, chapterUrl = "ch-0", chapterIndex = 0,
                    imagesJson = encodeMangaImageUrls(imageUrls), total = imageUrls.size, state = "pending",
                ),
            )
            // 字节先喂进 L1（等价于"这几张用户刚才已经看过"）⇒ 预取不需要上游请求；
            // 但**不刷新就绪度**：整章 state 仍是 pending，ready 必须由预取推上去。
            val cache = ImageCache(dataDir.resolve("images"), seed, ServerConfig.DEFAULT_IMAGE_CACHE_QUOTA_BYTES)
            imageUrls.forEachIndexed { index, url ->
                cache.put(sourceId, bookUrl, "ch-0", url, "image/jpeg", jpeg(seed = index), chapterIndex = 0, segmentIndex = index)
            }
        } finally {
            seed.close()
        }

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"), webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                imageCacheDirectory = dataDir.resolve("images"),
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val body = MangaPrefetchRequest(sourceId = sourceId, bookUrl = bookUrl, fromChapterIndex = 0, ahead = 0)

            // 1. 未登录 ⇒ 401（写接口，且预取会真去抓图，绝不能被匿名触发）
            val anonymous = client.post("/api/books/images/prefetch") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

            val csrf = login(client, "test-password-1234")

            // 2. 缺 sourceId ⇒ 400
            val noSource = client.post("/api/books/images/prefetch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body.copy(sourceId = ""))
            }
            assertEquals(HttpStatusCode.BadRequest, noSource.status)

            // 3. 缺 bookUrl ⇒ 400（没有书就不知道去哪找"后 N 章"）
            val noBook = client.post("/api/books/images/prefetch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body.copy(bookUrl = null))
            }
            assertEquals(HttpStatusCode.BadRequest, noBook.status)

            // 4. 正常受理 ⇒ 202 + queued=true + 规划 1 章
            val accepted = client.post("/api/books/images/prefetch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            val acceptedBody = accepted.body<MangaPrefetchResponse>()
            assertTrue("受理即返回 202（它的语义是「已受理」而不是「已完成」）", acceptedBody.queued)
            assertEquals(1, acceptedBody.chapters)

            // 5. 绿点：`/bookshelf/cached-chapters` 契约不变，但漫画必须换源到 manga_chapter_cache
            val ready = awaitGreenDot(client, "ch-0")
            assertTrue("整章就绪后绿点必须亮（data source 换源不能只做一半）", ready)

            // 6. 免死金牌
            val pinned = client.put("/api/books/images/cache/pin?sourceId=$sourceId&bookUrl=$bookUrl&pinned=true") {
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.OK, pinned.status)
            assertEquals(true, pinned.body<MangaPinResponse>().pinned)

            // 7. 预取状态接口（同一本书已经跑完 ⇒ 空列表，不是 500）
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

    @Test
    fun `prefetch route reports disabled instead of failing`() = testApplication {
        val dbPath = Files.createTempFile("legado-manga-prefetch-off", ".sqlite").toString()
        val dataDir = Files.createTempDirectory("legado-manga-prefetch-off")

        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            seed.importSources(listOf(comicSource), applyGroups = false)
            seed.saveTocCache(sourceId, bookUrl, listOf(Chapter(0, "第 1 话", "ch-0")))
        } finally {
            seed.close()
        }

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"), webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                imageCacheDirectory = dataDir.resolve("images"),
                mangaPrefetchEnabled = false,
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val csrf = login(client, "test-password-1234")

            val response = client.post("/api/books/images/prefetch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(MangaPrefetchRequest(sourceId = sourceId, bookUrl = bookUrl, fromChapterIndex = 0, ahead = 1))
            }

            // 关闭 ≠ 报错：阅读器对它的唯一正确反应是"什么都不做"
            assertEquals(HttpStatusCode.Accepted, response.status)
            val body = response.body<MangaPrefetchResponse>()
            assertFalse(body.queued)
            assertEquals(0, body.chapters)
            assertEquals("prefetch_disabled", body.reason)
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    private suspend fun awaitGreenDot(client: io.ktor.client.HttpClient, chapterUrl: String): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val response = client.get("/api/bookshelf/cached-chapters?sourceId=$sourceId&bookUrl=$bookUrl")
            if (response.status == HttpStatusCode.OK) {
                val body = response.body<CachedChaptersResponse>()
                if (chapterUrl in body.cachedChapterUrls) return@runBlocking true
            }
            delay(50)
        }
        false
    }

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
