package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * P1 的核心两件：`ImageCache`（L1 唯一副本 + 两级 key）与 `MangaImageFetcher`（抓取收口）。
 *
 * 这些用例锁定的都是**静默失败**：跨源脏命中、引用计数算错导致误删别人的字节、
 * 表里有行但盘上没文件（前端裂图且数据库看起来正常）、gzip 假图片、错误页被当图片存下来。
 */
class MangaImageCacheTest {

    // ------------------------------------------------------------------
    // ImageCache
    // ------------------------------------------------------------------

    @Test
    fun `stores bytes once, indexes by url and probes real size`() {
        withCache { cache, _ ->
            val bytes = jpegBytes(800, 1200)
            val stored = cache.put("src-a", "book-1", "ch-1", "https://img.example/1.jpg", "image/jpeg", bytes)

            assertFalse(stored.fromCache)
            assertEquals("image/jpeg", stored.contentType)
            assertEquals(800, stored.width)
            assertEquals(1200, stored.height)

            val hit = cache.get("src-a", "https://img.example/1.jpg")
            assertNotNull("落盘后必须命中", hit)
            assertTrue(hit!!.fromCache)
            assertEquals(stored.cacheKey, hit.cacheKey)
            assertEquals(bytes.size.toLong(), hit.byteSize)
            assertArrayEquals(bytes, cache.read(hit.cacheKey))
        }
    }

    @Test
    fun `deduplicates identical bytes across different urls`() {
        withCache { cache, database ->
            val bytes = jpegBytes(640, 480)
            val a = cache.put("src-a", "book-1", "ch-1", "https://img.example/1.jpg", "image/jpeg", bytes)
            val b = cache.put("src-a", "book-1", "ch-2", "https://img.example/2.jpg", "image/jpeg", bytes)

            assertEquals("相同字节必须落到同一个 cacheKey（内容寻址去重）", a.cacheKey, b.cacheKey)
            assertEquals("两个 URL 引用同一份字节", 2, database.countMangaImageRefs(a.cacheKey))

            val files = Files.list(cache.fileFor(a.cacheKey)!!.parent).use { it.count() }
            assertEquals("盘上只能有一份物理文件", 1L, files)
        }
    }

    @Test
    fun `never mixes bytes across sources for the same url`() {
        withCache { cache, database ->
            val bytes = jpegBytes(600, 900)
            val fromA = cache.put("src-a", "book-1", "ch-1", "https://img.example/same.jpg", "image/jpeg", bytes)
            val fromB = cache.put("src-b", "book-2", "ch-1", "https://img.example/same.jpg", "image/jpeg", bytes)

            // 同一上游地址、不同书源 ⇒ 必须是两条 URL 索引（否则跨源脏命中，图错/水印不对极难排查）
            assertTrue(fromA.urlHash != fromB.urlHash)
            assertNotNull(cache.get("src-a", "https://img.example/same.jpg"))
            assertNotNull(cache.get("src-b", "https://img.example/same.jpg"))
            assertEquals("URL 索引两条，字节仍只有一份", 2, database.countMangaImageRefs(fromA.cacheKey))
        }
    }

    @Test
    fun `drops the row when the file disappeared instead of pretending a hit`() {
        withCache { cache, database ->
            val stored = cache.put("src-a", "book-1", "ch-1", "https://img.example/gone.jpg", "image/jpeg", jpegBytes())
            Files.deleteIfExists(cache.fileFor(stored.cacheKey)!!)

            assertNull("盘上没文件就必须回源，绝不能假装命中", cache.get("src-a", "https://img.example/gone.jpg"))
            assertNull("失效的行必须被清掉", database.findMangaImageByUrlHash(stored.urlHash))
        }
    }

    @Test
    fun `deletes book cache but keeps bytes still referenced elsewhere`() {
        withCache { cache, database ->
            val shared = jpegBytes(500, 700)
            // 同一份字节同时属于两本书
            val a = cache.put("src-a", "book-1", "ch-1", "https://img.example/shared.jpg", "image/jpeg", shared)
            cache.put("src-a", "book-2", "ch-1", "https://img.example/shared2.jpg", "image/jpeg", shared)

            val removed = cache.deleteBook("src-a", "book-1")
            assertEquals(1, removed)
            assertNull(database.findMangaImageByUrlHash(a.urlHash))
            assertNotNull("还被 book-2 引用 ⇒ 物理文件绝不能删", cache.fileFor(a.cacheKey))

            cache.deleteBook("src-a", "book-2")
            assertNull("最后一个引用消失后才回收文件", cache.fileFor(a.cacheKey))
        }
    }

    @Test
    fun `reports stats with table and disk bytes`() {
        withCache { cache, _ ->
            cache.put("src-a", "book-1", "ch-1", "https://img.example/1.jpg", "image/jpeg", jpegBytes(800, 1200))

            val stats = cache.stats(quotaBytes = 10L * 1024 * 1024 * 1024)
            assertEquals(1, stats.imageCount)
            assertTrue(stats.totalBytes > 0)
            assertEquals(stats.totalBytes, stats.diskBytes)
            assertEquals(10L * 1024 * 1024 * 1024, stats.quotaBytes)
            assertEquals(1, stats.books.size)
        }
    }

    @Test
    fun `rejects non image bytes`() {
        withCache { cache, _ ->
            var threw = false
            try {
                cache.put("src-a", "book-1", "ch-1", "https://img.example/x.jpg", "image/jpeg", "<html>403</html>".toByteArray())
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("错误页绝不能被当成图片存下来", threw)
        }
    }

    // ------------------------------------------------------------------
    // MangaImageFetcher（注入接缝，不触网）
    // ------------------------------------------------------------------

    private fun fetcherReturning(
        contentType: String,
        bytes: ByteArray,
    ): MangaImageFetcher = MangaImageFetcher(testConfig(), injectedFetcher = { _, _, _ -> contentType to bytes })

    @Test
    fun `decompresses gzip wrapped image from upstream`() {
        val jpeg = jpegBytes()
        val fetcher = fetcherReturning("image/jpeg", gzipOf(jpeg))

        val result = runBlocking { fetcher.fetch("https://img.example/1.jpg", emptyMap(), "https://manga.example/ch/1") }

        assertNotNull(result)
        assertArrayEquals(jpeg, result!!.bytes)
        assertEquals("image/jpeg", result.contentType)
    }

    @Test
    fun `returns null for html error page instead of storing garbage`() {
        val fetcher = fetcherReturning("image/jpeg", "<html>403 Forbidden</html>".toByteArray())
        assertNull(runBlocking { fetcher.fetch("https://img.example/1.jpg", emptyMap(), null) })
    }

    @Test
    fun `returns null for oversized image`() {
        val huge = ByteArray(ImageBytes.MAX_BYTES + 1)
        jpegBytes().copyInto(huge, 0)
        val fetcher = fetcherReturning("image/jpeg", huge)
        assertNull(runBlocking { fetcher.fetch("https://img.example/1.jpg", emptyMap(), null) })
    }

    @Test
    fun `swallows upstream failures without breaking the read flow`() {
        val fetcher = MangaImageFetcher(testConfig(), injectedFetcher = { _, _, _ -> throw java.io.IOException("connection reset") })
        assertNull("单张图失败必须返回 null，绝不抛到阅读主流程", runBlocking { fetcher.fetch("https://img.example/1.jpg", emptyMap(), null) })
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun withCache(block: (ImageCache, Database) -> Unit) {
        val dir = Files.createTempDirectory("legado-image-cache")
        val dbPath = Files.createTempFile("legado-image-cache", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            block(ImageCache(dir, database), database)
        } finally {
            // 必须先 close 再删：否则 Windows 上会因文件占用删不掉（本机 52 个既有失败的根因）
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    private fun testConfig(): ServerConfig {
        val dir = Files.createTempDirectory("legado-fetcher-config")
        return ServerConfig(
            host = "0.0.0.0", port = 8080, databasePath = "unused.sqlite",
            coverCacheDirectory = dir, webDavDirectory = dir.resolve("webdav"),
            initialAdminPassword = null, secureCookies = false,
        )
    }

    private fun gzipOf(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun jpegBytes(width: Int = 800, height: Int = 1200): ByteArray {
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
