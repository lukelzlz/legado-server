package io.legado.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * P1 的两个公共件：`ImageBytes`（字节判读唯一收口）与 `AtomicFileStore`（原子落盘）。
 *
 * 这些用例锁定的都是**静默失败**：gzip 假图片（接口 200 却裂图）、半截文件、
 * 非法 key 拼路径抛异常。它们不会报错，只会让用户看到裂图或播放中途断流。
 */
class MediaCachePrimitivesTest {

    // ------------------------------------------------------------------
    // ImageBytes · gzip 兜底解压（SESSION-027 的回归锚点）
    // ------------------------------------------------------------------

    private fun gzipOf(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun `decompresses gzip wrapped image bytes by magic number`() {
        val jpeg = jpegBytes()
        val wrapped = gzipOf(jpeg)

        // 上游谎报 Content-Type: image/jpeg 且内容其实是 gzip —— 必须按魔数兜底解压
        assertEquals(0x1f, wrapped[0].toInt() and 0xff)
        assertArrayEquals(jpeg, ImageBytes.decompressIfNeeded(wrapped))
        assertTrue("解压后必须能通过图片魔数校验", ImageBytes.looksLikeImage(ImageBytes.decompressIfNeeded(wrapped)))
    }

    @Test
    fun `leaves real image bytes untouched`() {
        val jpeg = jpegBytes()
        assertArrayEquals(jpeg, ImageBytes.decompressIfNeeded(jpeg))
    }

    @Test
    fun `does not touch short or empty input`() {
        assertArrayEquals(ByteArray(0), ImageBytes.decompressIfNeeded(ByteArray(0)))
        assertArrayEquals(byteArrayOf(0x1f), ImageBytes.decompressIfNeeded(byteArrayOf(0x1f)))
    }

    // ------------------------------------------------------------------
    // ImageBytes · 魔数与内容类型
    // ------------------------------------------------------------------

    @Test
    fun `detects content types from magic numbers`() {
        assertEquals("image/jpeg", ImageBytes.detectImageContentType(jpegBytes()))
        assertEquals("image/png", ImageBytes.detectImageContentType(pngBytes(800, 1200)))
        assertEquals("image/gif", ImageBytes.detectImageContentType(gifBytes(320, 480)))
        assertEquals("image/webp", ImageBytes.detectImageContentType(webpVp8xBytes(799, 1199)))
        assertNull("HTML 错误页绝不能当成图片", ImageBytes.detectImageContentType("<html>403</html>".toByteArray()))
    }

    // ------------------------------------------------------------------
    // ImageBytes · 宽高探测（只读文件头，不整图解码）
    // ------------------------------------------------------------------

    @Test
    fun `probes png size from ihdr`() {
        assertEquals(800 to 1200, ImageBytes.probeSize(pngBytes(800, 1200)))
    }

    @Test
    fun `probes gif size from logical screen descriptor`() {
        assertEquals(320 to 480, ImageBytes.probeSize(gifBytes(320, 480)))
    }

    @Test
    fun `probes jpeg size by scanning sof marker`() {
        // 先放一个 APP0 段（跳过），再放 SOF0 —— 必须能跳过前置段找到 SOF
        assertEquals(640 to 960, ImageBytes.probeSize(jpegBytes(width = 640, height = 960)))
    }

    @Test
    fun `probes webp vp8x canvas size`() {
        assertEquals(800 to 1200, ImageBytes.probeSize(webpVp8xBytes(799, 1199)))
    }

    @Test
    fun `returns null instead of throwing on garbage`() {
        assertNull(ImageBytes.probeSize("not an image at all".toByteArray()))
        assertNull(ImageBytes.probeSize(ByteArray(4)))
    }

    // ------------------------------------------------------------------
    // AtomicFileStore · 原子落盘
    // ------------------------------------------------------------------

    private fun tempStore(): Pair<AtomicFileStore, Path> {
        val root = Files.createTempDirectory("legado-atomic-store")
        return AtomicFileStore(root) to root
    }

    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)

    @Test
    fun `writes and reads back bytes without leaving part files`() {
        val (store, root) = tempStore()
        try {
            val payload = "图片字节".toByteArray()
            store.write(key, payload)

            assertTrue(store.exists(key))
            assertArrayEquals(payload, store.read(key))
            assertEquals(payload.size.toLong(), store.size(key))

            // 两级散列目录
            assertTrue("必须落在 <root>/<key[0:2]>/<key>", store.pathFor(key).toString().replace('\\', '/').endsWith("/aa/$key"))
            // 绝不能留下 .part 临时文件
            val leftovers = Files.walk(root).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".part") }.count()
            }
            assertEquals("原子落盘不得残留 .part", 0L, leftovers)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `streaming write counts bytes and commits atomically`() {
        val (store, root) = tempStore()
        try {
            val written = store.writeStream(key) { out ->
                repeat(1000) { out.write(byteArrayOf(1, 2, 3, 4)) }
            }
            assertEquals(4000L, written)
            assertEquals(4000L, store.size(key))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects non hex keys instead of building a bogus path`() {
        val (store, root) = tempStore()
        try {
            // AGENTS.md：用户数据拼路径会抛 InvalidPathException，必须提前拒绝
            assertFalse(store.exists("书\n名.jpg"))
            assertNull(store.read("../../etc/passwd"))
            assertEquals(false, store.delete("zz"))
            var threw = false
            try { store.pathFor("../escape") } catch (e: IllegalArgumentException) { threw = true }
            assertTrue("非法 key 必须显式抛错，而不是静默拼出路径", threw)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `lists keys and totals bytes skipping part files`() {
        val (store, root) = tempStore()
        try {
            store.write(key, ByteArray(10))
            store.write(otherKey, ByteArray(5))
            // 手工塞一个 .part：统计与列举都必须跳过它
            Files.write(store.pathFor(key).resolveSibling("tmp_x.part"), ByteArray(999))

            assertEquals(setOf(key, otherKey), store.listKeys().toSet())
            assertEquals(15L, store.totalBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delete removes the file`() {
        val (store, root) = tempStore()
        try {
            store.write(key, ByteArray(3))
            assertTrue(store.delete(key))
            assertFalse(store.exists(key))
            assertNull(store.read(key))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 最小合法样本构造
    // ------------------------------------------------------------------

    private fun jpegBytes(width: Int = 800, height: Int = 1200): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))                 // SOI
        // APP0：marker + 长度(16) + 14 字节负载
        out.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0x00, 0x10))
        out.write(ByteArray(14))
        // SOF0：marker + 长度(17) + 精度 + 高 + 宽 + 分量数(3)
        out.write(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x00, 0x11, 0x08))
        out.write(byteArrayOf((height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf(0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01))
        return out.toByteArray()
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val png = ByteArray(33)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(png, 0)
        byteArrayOf(0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52).copyInto(png, 8)   // len=13 + "IHDR"
        byteArrayOf((width shr 24).toByte(), (width shr 16).toByte(), (width shr 8).toByte(), width.toByte()).copyInto(png, 16)
        byteArrayOf((height shr 24).toByte(), (height shr 16).toByte(), (height shr 8).toByte(), height.toByte()).copyInto(png, 20)
        return png
    }

    private fun gifBytes(width: Int, height: Int): ByteArray {
        val gif = ByteArray(16)
        "GIF89a".toByteArray(Charsets.US_ASCII).copyInto(gif, 0)
        gif[6] = (width and 0xff).toByte(); gif[7] = ((width shr 8) and 0xff).toByte()
        gif[8] = (height and 0xff).toByte(); gif[9] = ((height shr 8) and 0xff).toByte()
        return gif
    }

    /** VP8X 的宽高存的是 canvas 尺寸 - 1。 */
    private fun webpVp8xBytes(widthMinus1: Int, heightMinus1: Int): ByteArray {
        val webp = ByteArray(30)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(webp, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(webp, 8)
        "VP8X".toByteArray(Charsets.US_ASCII).copyInto(webp, 12)
        webp[24] = (widthMinus1 and 0xff).toByte()
        webp[25] = ((widthMinus1 shr 8) and 0xff).toByte()
        webp[26] = ((widthMinus1 shr 16) and 0xff).toByte()
        webp[27] = (heightMinus1 and 0xff).toByte()
        webp[28] = ((heightMinus1 shr 8) and 0xff).toByte()
        webp[29] = ((heightMinus1 shr 16) and 0xff).toByte()
        return webp
    }
}
