package io.legado.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * `GET /api/books/images/illustration` 的**契约级**验证。
 *
 * 全部走**注入的上游字节**（`MangaImageFetcher` 的测试接缝），因此不触网也能覆盖
 * 真实世界最要命的四种上游形态：正常图、**gzip 假图片**、返回 HTML 错误页、超大文件。
 *
 * 修复前的行为：该路由根本不存在（正文清洗把 `<img>` 删光，前端连图都没有），
 * 所以这一组断言对"改前"代码必然全红。
 */
class IllustrationRouteTest {

    @Test
    fun `serves cached illustration bytes and never bypasses the proxy`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-illustration-route")
        val config = testConfig(dataDir)
        val bookUrl = "https://book.example/1"
        val chapterUrl = "https://book.example/c/1"
        val sourceId = "https://s1.test"
        val upstream = "https://cdn.example.com/a.jpg"

        val calls = AtomicInteger(0)
        val seenHeaders = mutableListOf<Map<String, String>>()
        val seenReferers = mutableListOf<String?>()
        val fetcher = MangaImageFetcher(config, log = {}) { url, headers, referer ->
            calls.incrementAndGet()
            seenHeaders += headers
            seenReferers += referer
            when {
                url.contains("gzipped") -> "image/jpeg" to gzip(jpegBytes(640, 960))
                url.contains("errorpage") -> "image/jpeg" to "<html>请先登录</html>".toByteArray()
                url.contains("oversized") -> "image/jpeg" to ByteArray(ImageBytes.MAX_BYTES + 1)
                else -> "image/jpeg" to jpegBytes(640, 960)
            }
        }

        try {
            seed(dataDir, sourceId, bookUrl)
            application { legadoApplication(config, injectedImageFetcher = fetcher) }
            val client = apiClient()

            val path = "/api/books/images/illustration?sourceId=${enc(sourceId)}" +
                "&bookUrl=${enc(bookUrl)}&chapterUrl=${enc(chapterUrl)}&url=${enc(upstream)}"

            // 1. 未登录 ⇒ 401（插图是私有数据：图床地址与字节都不该匿名可取）
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)

            login(client)

            // 2. 缺参 / 非 http(s) 地址 ⇒ 明确的 400（**绝不能是空 200**）
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/books/images/illustration?sourceId=${enc(sourceId)}&chapterUrl=${enc(chapterUrl)}").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get(
                    "/api/books/images/illustration?sourceId=${enc(sourceId)}&chapterUrl=${enc(chapterUrl)}" +
                        "&url=${enc("data:image/png;base64,AAAA")}",
                ).status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get(
                    "/api/books/images/illustration?sourceId=${enc(sourceId)}" +
                        "&url=${enc(upstream)}",
                ).status,
            )

            // 3. 未命中 ⇒ 用书源 header + 章节页 Referer 抓一次 → 落盘 → 直出真实字节
            val first = client.get(path)
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(1, calls.get())
            assertEquals("Referer 必须是章节页（防盗链的主判据）", chapterUrl, seenReferers.single())
            assertEquals("书源 header 必须带上", "test-ua", seenHeaders.single()["User-Agent"])
            val bytes = first.body<ByteArray>()
            assertTrue("必须直出真实图片字节（JPEG 魔数）", isJpeg(bytes))
            assertEquals("内容类型必须来自真实字节", "image/jpeg", first.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            val etag = first.headers[HttpHeaders.ETag]
            assertNotNull("必须带 ETag（= 字节哈希）", etag)
            assertTrue(
                "内容变了 ETag 必变 ⇒ 可以 immutable 长缓存",
                first.headers[HttpHeaders.CacheControl]?.contains("immutable") == true,
            )

            // 4. 落盘命名必须符合 v3 口径：images/<书名 (书哈希)>/<章节名 (章节哈希)>/00000000.jpg
            val stored = illustrationFiles(dataDir)
            assertEquals("一张图只应落一份盘，实际：$stored", 1, stored.size)
            val relative = dataDir.resolve("images").relativize(stored.single()).toString().replace('\\', '/')
            val bookHash = bookCacheHash(sourceId, bookUrl)
            val chapterHash = chapterCacheHash(sourceId, bookUrl, chapterUrl)
            assertTrue("书目录必须带 20 位书哈希 ($bookHash)，实际：$relative", relative.contains("($bookHash)"))
            assertTrue("章节目录必须带 20 位章节哈希 ($chapterHash)，实际：$relative", relative.contains("($chapterHash)"))
            assertTrue("文件名必须是 8 位序号 + 真实扩展名，实际：$relative", relative.endsWith("/00000000.jpg"))
            assertTrue("漫画根（comic/）绝不能被插图污染", comicFiles(dataDir).isEmpty())

            // 5. 二次请求命中本地文件 ⇒ **零上游请求** + 304（浏览器/SW 二次访问零字节传输）
            assertEquals(HttpStatusCode.OK, client.get(path).status)
            assertEquals("命中缓存后绝不能再打上游", 1, calls.get())
            assertEquals(
                HttpStatusCode.NotModified,
                client.get(path) { header(HttpHeaders.IfNoneMatch, etag!!) }.status,
            )

            // 6. gzip「假图片」：上游对 .jpg 也 gzip 是常态，必须按魔数解压后才落盘/直出
            val gzPath = "/api/books/images/illustration?sourceId=${enc(sourceId)}" +
                "&bookUrl=${enc(bookUrl)}&chapterUrl=${enc(chapterUrl)}&url=${enc("https://cdn.example.com/gzipped.jpg")}"
            val gz = client.get(gzPath)
            assertEquals(HttpStatusCode.OK, gz.status)
            assertTrue("gzip 必须被按魔数解压成真 JPEG（否则浏览器裂图）", isJpeg(gz.body<ByteArray>()))
            assertEquals("解压后的图必须落盘", 2, illustrationFiles(dataDir).size)

            // 7. 上游返回错误页（不是图片）⇒ 502 且**不落盘**（绝不把错误页当图片存下来）
            val htmlPath = "/api/books/images/illustration?sourceId=${enc(sourceId)}" +
                "&bookUrl=${enc(bookUrl)}&chapterUrl=${enc(chapterUrl)}&url=${enc("https://cdn.example.com/errorpage.jpg")}"
            assertEquals(HttpStatusCode.BadGateway, client.get(htmlPath).status)
            assertEquals(2, illustrationFiles(dataDir).size)

            // 8. 超过单图体积上限 ⇒ 502 且**不落盘**
            val hugePath = "/api/books/images/illustration?sourceId=${enc(sourceId)}" +
                "&bookUrl=${enc(bookUrl)}&chapterUrl=${enc(chapterUrl)}&url=${enc("https://cdn.example.com/oversized.jpg")}"
            assertEquals(HttpStatusCode.BadGateway, client.get(hugePath).status)
            assertEquals(2, illustrationFiles(dataDir).size)

            // 9. 书源不存在 ⇒ 404（不能 500，也不能去抓）
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(
                    "/api/books/images/illustration?sourceId=${enc("https://no-such-source.test")}" +
                        "&chapterUrl=${enc(chapterUrl)}&url=${enc(upstream)}",
                ).status,
            )
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    /**
     * 序号必须来自**正文里的出现顺序**：本章已缓存正文时，请求第 3 张图应落到 `00000002.*`。
     *
     * 这条同时锁定「缓存正文时登记插图」这条链路 —— 登记缺失时序号会退化成"追加到章末"，
     * 于是同一章的两张图可能抢到同一个号 ⇒ **读者看到别人的图**（比裂图难查得多）。
     */
    @Test
    fun `segment index follows the illustration order inside cached chapter content`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-illustration-order")
        val config = testConfig(dataDir)
        val sourceId = "https://s1.test"
        val bookUrl = "https://book.example/2"
        val chapterUrl = "https://book.example/c/9"
        val third = "https://cdn.example.com/3.jpg"

        val fetcher = MangaImageFetcher(config, log = {}) { _, _, _ -> "image/jpeg" to jpegBytes(320, 480) }

        try {
            seed(dataDir, sourceId, bookUrl)
            // 本章正文已缓存（含 3 张按序插图）—— 等价于"用户已经在网页上读过这一章"
            val cached = Database(config.databasePath)
            try {
                cached.cacheBookContent(
                    sourceId, bookUrl, chapterUrl,
                    ChapterContent(
                        "第9章",
                        "第1段\n\n<img src=\"https://cdn.example.com/1.jpg\">\n\n第2段\n\n" +
                            "<img src=\"https://cdn.example.com/2.jpg\">\n\n第3段\n\n" +
                            "<img src=\"$third\">\n\n第4段",
                    ),
                )
            } finally {
                cached.close()
            }

            application { legadoApplication(config, injectedImageFetcher = fetcher) }
            val client = apiClient()
            login(client)

            val response = client.get(
                "/api/books/images/illustration?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}" +
                    "&chapterUrl=${enc(chapterUrl)}&url=${enc(third)}",
            )
            assertEquals(HttpStatusCode.OK, response.status)

            val relative = dataDir.resolve("images").relativize(illustrationFiles(dataDir).single())
                .toString().replace('\\', '/')
            assertTrue("第 3 张图的序号必须是 00000002，实际：$relative", relative.endsWith("/00000002.jpg"))

            // 登记表也必须是"正文顺序"的口径（离线缓存与在线代理共用它）
            val verifier = Database(config.databasePath)
            try {
                val rows = verifier.listIllustrationsForChapter(sourceId, bookUrl, chapterUrl)
                assertEquals("三张图必须全部登记（含尚未抓取的两张）", 3, rows.size)
                assertEquals("序号必须等于正文出现顺序", listOf(0, 1, 2), rows.map { it.segmentIndex })
                assertTrue(
                    "被抓取的那张必须落成 ready 且带上落盘路径",
                    rows.single { it.upstreamUrl == third }.let { it.state == "ready" && it.filePath.isNotBlank() },
                )
            } finally {
                verifier.close()
            }
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------

    private fun io.ktor.server.testing.ApplicationTestBuilder.apiClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        install(HttpCookies)
    }

    private suspend fun login(client: HttpClient) {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = "test-password-1234"))
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    private fun testConfig(dataDir: Path): ServerConfig = ServerConfig(
        host = "0.0.0.0",
        port = 8080,
        databasePath = dataDir.resolve("legado.sqlite").toString(),
        coverCacheDirectory = dataDir.resolve("covers"),
        webDavDirectory = dataDir.resolve("webdav"),
        initialAdminPassword = "test-password-1234",
        secureCookies = false,
        comicCacheDirectory = dataDir.resolve("comic"),
        illustrationCacheDirectory = dataDir.resolve("images"),
    )

    /** 预置书源（带自定义 header）+ 书架条目，等价于"用户已经加过这本书"。 */
    private fun seed(dataDir: Path, sourceId: String, bookUrl: String) {
        val db = Database(dataDir.resolve("legado.sqlite").toString())
        try {
            db.initialize("test-password-1234")
            db.saveSource(
                SourceCodec.parse(
                    """{"bookSourceUrl":"$sourceId","bookSourceName":"测试源",
                       "header":"{\"User-Agent\":\"test-ua\"}",
                       "ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},
                       "ruleContent":{"content":"$.content","title":"$.title"}}""",
                ),
                null,
            )
            db.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, "插图测试书", tocUrl = "$bookUrl/toc"), null)
        } finally {
            db.close()
        }
    }

    private fun illustrationFiles(dataDir: Path): List<Path> {
        val root = dataDir.resolve("images")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".part") }.toList()
        }
    }

    private fun comicFiles(dataDir: Path): List<Path> {
        val root = dataDir.resolve("comic")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xff) == 0xd8

    /** 最小合法 JPEG 头（含 SOF0 宽高），与 `MangaImageRouteTest` 同一手法。 */
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
