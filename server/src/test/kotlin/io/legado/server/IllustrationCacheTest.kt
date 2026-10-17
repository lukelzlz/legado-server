package io.legado.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * 小说正文插图的**存储层 + 离线缓存链路**验证。
 *
 * 三组要点：
 * 1. 落盘命名严格沿用 v3（`images/<书名 (书哈希)>/<章节名 (章节哈希)>/<8位序号>.<真实扩展名>`）；
 * 2. 读取只认「两个短哈希 + 序号」—— `file_path` 丢了也能定位（绝不因为中文目录被改名就判缺失）；
 * 3. **章节缓存时插图一起落盘**，且**单张失败绝不影响正文缓存**。
 */
class IllustrationCacheTest {

    @Test
    fun `url hash matches the project-wide convention and layout follows v3`() {
        withTempStore { dataDir, database, cache ->
            val sourceId = "https://s1.test"
            val bookUrl = "https://book.example/1"
            val chapterUrl = "https://book.example/c/1"
            val upstream = "https://cdn.example.com/a.jpg"

            assertEquals(
                "urlHash 必须是 sha256(sourceId + '\\n' + url)（与漫画/音频同一口径）",
                ImageBytes.sha256("$sourceId\n$upstream"),
                cache.urlHash(sourceId, upstream),
            )

            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, "十日终焉", tocUrl = "$bookUrl/toc"), null)
            // 章节标题进 TOC 缓存 ⇒ 目录名带上可读中文（用户要求"方便本地查看"）
            database.saveTocCache(
                sourceId, "$bookUrl/toc",
                listOf(Chapter(0, "第一章 生路", chapterUrl)),
            )

            val record = cache.put(
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                upstreamUrl = upstream,
                contentType = "image/jpeg",
                bytes = jpegBytes(800, 1200),
                chapterIndex = 0,
                segmentIndex = 3,
            )
            val path = cache.fileFor(record)
            assertNotNull("落盘后必须能定位到文件", path)
            assertTrue("必须落在 <data>/images 下", path!!.startsWith(dataDir.resolve("images")))
            val relative = dataDir.resolve("images").relativize(path).toString().replace('\\', '/')
            assertTrue("书目录名格式错误：$relative", relative.startsWith("十日终焉 (${bookCacheHash(sourceId, bookUrl)})/"))
            assertTrue("章节目录名格式错误：$relative", relative.contains("/第一章 生路 (${chapterCacheHash(sourceId, bookUrl, chapterUrl)})/"))
            assertTrue("分段文件名必须是 8 位序号 + 真实扩展名：$relative", relative.endsWith("/00000003.jpg"))

            // 命中检查：ready + 文件在
            val hit = cache.getByKey(cache.urlHash(sourceId, upstream))
            assertNotNull("命中必须返回行", hit)
            assertEquals("image/jpeg", hit!!.contentType)
            assertEquals(record.byteSize, hit.byteSize)
            assertFalse("cache_key（字节哈希）必须落库：ETag 靠它", hit.cacheKey.isBlank())

            // 上游对 .jpg 也 gzip 是常态：落盘前必须按魔数解压
            val gz = cache.put(
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                upstreamUrl = "https://cdn.example.com/gz.jpg",
                contentType = "image/jpeg",
                bytes = gzip(jpegBytes(200, 200)),
                chapterIndex = 0,
                segmentIndex = 4,
            )
            val gzBytes = Files.readAllBytes(cache.fileFor(gz)!!)
            assertTrue("gzip 假图片必须解压后落盘（否则浏览器一律裂图）", isJpeg(gzBytes))
        }
    }

    @Test
    fun `reads by short hashes when file path cache is lost`() {
        withTempStore { _, database, cache ->
            val sourceId = "https://s1.test"
            val bookUrl = "https://book.example/1"
            val chapterUrl = "https://book.example/c/1"
            val upstream = "https://cdn.example.com/a.jpg"
            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, "书", tocUrl = "$bookUrl/toc"), null)
            val record = cache.put(sourceId, bookUrl, chapterUrl, upstream, "image/png", pngBytes())
            assertNotNull(cache.fileFor(record))

            // 模拟 `file_path` 失效（历史数据 / 手工改库 / 目录被改名）：必须仍按哈希 + 序号定位
            database.updateIllustrationFilePath(record.urlHash, "")
            val reloaded = cache.rowByKey(record.urlHash)!!
            assertEquals("", reloaded.filePath)
            val located = cache.fileFor(reloaded)
            assertNotNull("file_path 丢了也必须靠「两个短哈希 + 8 位序号」找回来", located)
            assertTrue("找回后必须回填 file_path（下次走快路径）", cache.rowByKey(record.urlHash)!!.filePath.isNotBlank())
        }
    }

    @Test
    fun `registration is idempotent and never reassigns an existing segment index`() {
        withTempStore { _, database, cache ->
            val sourceId = "https://s1.test"
            val bookUrl = "https://book.example/1"
            val chapterUrl = "https://book.example/c/1"
            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, "书", tocUrl = "$bookUrl/toc"), null)
            val a = "https://cdn.example.com/a.jpg"
            val b = "https://cdn.example.com/b.jpg"

            val first = cache.registerChapter(sourceId, bookUrl, chapterUrl, 0, listOf(a, b))
            assertEquals(listOf(0, 1), first.sortedBy { it.segmentIndex }.map { it.segmentIndex })

            // 正文顺序变了（例如源站改版）：序号**绝不能**被重新分配，否则文件名与内容错位 ⇒ 错图
            val second = cache.registerChapter(sourceId, bookUrl, chapterUrl, 0, listOf(b, a))
            assertEquals(
                "已登记的序号必须保持不变",
                mapOf(a to 0, b to 1),
                second.associate { it.upstreamUrl to it.segmentIndex },
            )
            // 新出现的图追加到末尾
            val third = cache.registerChapter(sourceId, bookUrl, chapterUrl, 0, listOf(b, a, "https://cdn.example.com/c.jpg"))
            assertEquals(3, third.size)
            assertEquals(2, third.single { it.upstreamUrl == "https://cdn.example.com/c.jpg" }.segmentIndex)
        }
    }

    @Test
    fun `rejects non image bytes and deletes book cache with its files`() {
        withTempStore { dataDir, database, cache ->
            val sourceId = "https://s1.test"
            val bookUrl = "https://book.example/1"
            val chapterUrl = "https://book.example/c/1"
            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, "书", tocUrl = "$bookUrl/toc"), null)

            val rejected = runCatching {
                cache.put(sourceId, bookUrl, chapterUrl, "https://cdn.example.com/x.jpg", "image/jpeg", "<html>请先登录</html>".toByteArray())
            }.exceptionOrNull()
            assertNotNull("非图片字节必须被拒绝（错误页绝不能当图片落盘）", rejected)
            assertTrue("落盘目录不该产生任何文件", illustrationFiles(dataDir).isEmpty())

            val record = cache.put(sourceId, bookUrl, chapterUrl, "https://cdn.example.com/a.jpg", "image/jpeg", jpegBytes(10, 10))
            val file = cache.fileFor(record)!!
            assertTrue(Files.isRegularFile(file))

            // 清整本书的插图：先表后盘
            assertEquals(1, cache.deleteBook(sourceId, bookUrl))
            assertTrue("行必须清空", database.listIllustrationsByBook(sourceId, bookUrl).isEmpty())
            assertTrue("物理文件也必须回收", !Files.exists(file))
        }
    }

    /**
     * 离线缓存链路：`BookCacheService` 缓存章节时**顺手把插图抓下来落盘**，
     * 且单张图失败**不影响**正文章节缓存（只计数 + 记首条原因）。
     */
    @Test
    fun `chapter caching stores inline illustrations and survives single image failures`() = runBlocking {
        val dataDir = Files.createTempDirectory("legado-illustration-book-cache")
        val config = ServerConfig(
            host = "0.0.0.0",
            port = 8080,
            databasePath = dataDir.resolve("legado.sqlite").toString(),
            coverCacheDirectory = dataDir.resolve("covers"),
            webDavDirectory = dataDir.resolve("webdav"),
            initialAdminPassword = "test-pass",
            secureCookies = false,
            comicCacheDirectory = dataDir.resolve("comic"),
            illustrationCacheDirectory = dataDir.resolve("images"),
        )
        val database = Database(config.databasePath)
        try {
            database.initialize("test-pass")
            val sourceId = "https://s1.test"
            val bookUrl = "https://book.example/1"
            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, "图文书", tocUrl = "$bookUrl/toc"), null)
            database.saveSource(
                SourceCodec.parse(
                    """{"bookSourceUrl":"$sourceId","bookSourceName":"图文源",
                       "ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"$.url"},
                       "ruleContent":{"content":"$.content","title":"$.title"}}""",
                ),
                null,
            )

            val imageCalls = AtomicInteger(0)
            val runner = RuleRunner { url ->
                when (url) {
                    "$bookUrl/toc" -> """{"data":[{"title":"第1章","url":"$bookUrl/c1"},{"title":"第2章","url":"$bookUrl/c2"}]}"""
                    "$bookUrl/c1" -> """{"title":"第1章","content":"第1章正文<p><img src=\"https://cdn.example.com/ok.jpg\"></p>结束"}"""
                    // 第2章：一张好图 + 一个必然失败的上游（引擎里抛错 ⇒ fetch 返回 null）
                    "$bookUrl/c2" -> """{"title":"第2章","content":"正文<img src=\"https://cdn.example.com/boom.jpg\"><img src=\"https://cdn.example.com/ok2.jpg\">"}"""
                    else -> error("Unexpected URL: $url")
                }
            }
            val fetcher = MangaImageFetcher(config, log = {}) { url, _, _ ->
                imageCalls.incrementAndGet()
                if (url.contains("boom")) throw IllegalStateException("上游 403")
                "image/jpeg" to jpegBytes(64, 64)
            }

            val cache = IllustrationCache(config.illustrationCacheDirectory, database)
            val service = BookCacheService(
                database,
                runner,
                illustrationCache = cache,
                illustrationFetcher = fetcher,
            ) {}
            service.enqueue(CachedBookRequest(sourceId, bookUrl, "$bookUrl/toc"))
            waitForCondition(10_000) { database.listBookshelf().single().cacheState == "ready" }
            service.stop()

            val shelf = database.listBookshelf().single()
            assertEquals("单张插图失败**绝不能**把章节判成未缓存", "ready", shelf.cacheState)
            assertEquals(2, shelf.cachedChapters)
            assertNull(shelf.cacheError)

            // 正文里的插图标签必须还在（离线读也要有图，否则等于没缓存）
            val content = database.cachedContent(sourceId, bookUrl, "$bookUrl/c1")?.content.orEmpty()
            assertTrue("缓存正文必须保留插图：$content", content.contains("<img src=\"https://cdn.example.com/ok.jpg\">"))

            // 两张成功的图必须落盘，失败的那张不得留下任何文件
            val files = illustrationFiles(dataDir)
            assertEquals("成功两张：$files，实际抓取次数=${imageCalls.get()}", 2, files.size)
            val names = files.map { it.fileName.toString() }.sorted()
            assertEquals(listOf("00000000.jpg", "00000001.jpg"), names)

            // 离线可用性判据：文件是**真实可解码图片**，不是上游错误页
            files.forEach { assertTrue("落盘内容必须是真图片：$it", isJpeg(Files.readAllBytes(it))) }

            val rows = database.listIllustrationsForChapter(sourceId, bookUrl, "$bookUrl/c2")
            assertEquals("第2章的两张图都必须登记（失败的那张也要留着地址以便重试）", 2, rows.size)
            assertEquals("失败的行不能变成 ready", 1, rows.count { it.state == "ready" })
        } finally {
            runCatching { database.close() }
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------

    private fun withTempStore(block: (Path, Database, IllustrationCache) -> Unit) {
        val dataDir = Files.createTempDirectory("legado-illustration-cache")
        val database = Database(dataDir.resolve("legado.sqlite").toString())
        try {
            database.initialize("test-pass")
            block(dataDir, database, IllustrationCache(dataDir.resolve("images"), database))
        } finally {
            runCatching { database.close() }
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    private suspend fun waitForCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun illustrationFiles(dataDir: Path): List<Path> {
        val root = dataDir.resolve("images")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".part") }.toList()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xff) == 0xd8

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

    /** 8 字节 PNG 魔数 + 足够的字节长度即可通过魔数校验（本用例不解析尺寸）。 */
    private fun pngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(64)
}
