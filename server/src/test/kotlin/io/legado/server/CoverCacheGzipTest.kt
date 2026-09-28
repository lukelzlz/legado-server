package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * 回归测试：封面缓存必须落盘**可解码的图片字节**。
 *
 * 实测根因（SESSION-027）：部分图床对 `.jpg` 也返回 `Content-Encoding: gzip`，
 * 而 `HttpResponse.BodyHandlers.ofByteArray()` **不会自动解压**。
 * 结果磁盘上存的是 gzip 字节、`Content-Type` 却仍是 `image/jpeg`：
 * 接口 HTTP 200、字节数正常、`<img>` 却渲染为**空白/裂图**
 * （Chrome 里 `complete=true` 但 `naturalWidth=0`）。
 *
 * 对照事实：jmlldsc(gzip)→坏 / wensang(不 gzip)→好 / lfdapengu(gzip)→坏。
 */
class CoverCacheGzipTest {

    private fun minimalJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46,
        0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun minimalPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun cache(fetcher: (String) -> Pair<String, ByteArray>): CoverCache {
        val dir = Files.createTempDirectory("cover-gzip-test")
        return CoverCache(dir) { url -> fetcher(url) }
    }

    /** 核心用例：上游 gzip + content-type 谎报 image/jpeg 时，落盘必须是真 JPEG。 */
    @Test
    fun `gzip encoded cover is decompressed before storing`() {
        val jpeg = minimalJpeg()
        val cache = cache { "image/jpeg" to gzip(jpeg) }

        val stored = cache.cache("https://img.example/a.jpg")
        val onDisk = Files.readAllBytes(cache.file(stored.key)!!)

        assertTrue("落盘字节必须是 JPEG 魔数，实际：${onDisk.take(4).joinToString(" ") { "%02x".format(it) }}",
            onDisk[0] == 0xFF.toByte() && onDisk[1] == 0xD8.toByte())
        assertEquals("应等于解压后的原始 JPEG", jpeg.size, onDisk.size)
    }

    /** 不压缩的上游必须原样保留（不能把好数据弄坏）。 */
    @Test
    fun `plain cover is stored unchanged`() {
        val jpeg = minimalJpeg()
        val cache = cache { "image/jpeg" to jpeg }
        val stored = cache.cache("https://img.example/plain.jpg")
        val onDisk = Files.readAllBytes(cache.file(stored.key)!!)
        assertEquals(jpeg.size, onDisk.size)
        assertTrue(onDisk.contentEquals(jpeg))
    }

    /** 上游谎报 Content-Encoding: gzip 但字节其实是明文图片时，不能破坏数据。 */
    @Test
    fun `lying content-encoding does not corrupt plain bytes`() {
        val jpeg = minimalJpeg()
        val cache = cache { "image/jpeg" to jpeg }
        val stored = cache.cache("https://img.example/liar.jpg")
        val onDisk = Files.readAllBytes(cache.file(stored.key)!!)
        assertTrue("明文 JPEG 必须原样保留", onDisk.contentEquals(jpeg))
    }

    /** PNG 同样要能落盘。 */
    @Test
    fun `png cover is accepted`() {
        val png = minimalPng()
        val cache = cache { "image/png" to png }
        val stored = cache.cache("https://img.example/a.png")
        assertTrue(Files.exists(cache.file(stored.key)!!))
    }

    /**
     * 关键防线：内置下载路径（fetcher == null，即真实网络）拿到**非图片字节**时必须拒绝，
     * 而不是存成一个"看起来是 JPEG 的坏文件"。
     *
     * 说明：魔数强校验只作用于内置下载路径（网络信任边界）；注入 fetcher 的接缝不做强校验，
     * 否则会拒掉合法的测试/内部注入用例（实测回归 6 例）。
     * 但**解压对所有路径生效**。
     */
    @Test
    fun `saveCoverBytes decompresses gzip so callers cannot store compressed bytes`() {
        val cache = cache { "image/jpeg" to minimalJpeg() }
        // 传入未解压的 gzip：必须被解出来，且落盘的是真 JPEG
        val key = cache.saveCoverBytes(gzip(minimalJpeg()), "image/jpeg")
        val onDisk = Files.readAllBytes(cache.file(key)!!)
        assertTrue(
            "必须存成解压后的 JPEG，实际魔数：${onDisk.take(4).joinToString(" ") { "%02x".format(it) }}",
            onDisk[0] == 0xFF.toByte() && onDisk[1] == 0xD8.toByte(),
        )
    }

    /** 注入 fetcher 的路径同样要解压（这是导入器补抓封面的真实路径）。 */
    @Test
    fun `injected fetcher path also decompresses gzip`() {
        val jpeg = minimalJpeg()
        val cache = cache { "image/jpeg" to gzip(jpeg) }
        val stored = cache.cache("https://img.example/injected.jpg")
        val onDisk = Files.readAllBytes(cache.file(stored.key)!!)
        assertTrue("注入路径也必须落盘解压后的图片", onDisk.contentEquals(jpeg))
    }

    /** 完全不是图片的内容：通过 saveCoverBytes 也要能解压兜底（若本来就是明文则原样存）。 */
    @Test
    fun `html error page bytes pass through saveCoverBytes unchanged`() {
        val cache = cache { "image/jpeg" to minimalJpeg() }
        val html = "<html>403 Forbidden</html>".toByteArray()
        val key = cache.saveCoverBytes(html)
        val onDisk = Files.readAllBytes(cache.file(key)!!)
        assertTrue("非 gzip 内容不应被改写", onDisk.contentEquals(html))
    }

    /** 已有缓存命中时不应重复下载（保持原有短路语义）。 */
    @Test
    fun `existing cache short circuits download`() {
        var calls = 0
        val cache = cache { calls++; "image/jpeg" to minimalJpeg() }
        cache.cache("https://img.example/once.jpg")
        cache.cache("https://img.example/once.jpg")
        assertEquals("第二次应命中缓存，不应再次下载", 1, calls)
    }
}
