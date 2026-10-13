package io.legado.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 内置浏览器代理的**解压**行为。
 *
 * 历史缺陷：只有 html/css 走 `decodeBody` 解压，其它资源（JS / JSON / SVG / 字体）直接透传
 * `response.body()`；而请求头声明了 `Accept-Encoding: gzip`，JDK HttpClient **不会自动解压**
 * ⇒ 实测 `jquery.min.js` 交给浏览器的前 4 字节是 `1F 8B 08 00`（gzip）、体积 30462 B 全是乱码，
 * 浏览器又拿不到 `Content-Encoding`（代理不转发该头）⇒ **脚本根本无法执行**，页面表现为
 * 一直转圈 / 按钮无反应 / 更新页卡在「正在检查更新…」。
 *
 * 同时必须**按魔数**判断而不是只信响应头（仓库 SESSION-027 的教训：上游会谎报，
 * 强行解压反而会弄坏已经解压过的好数据）。
 */
class WebViewProxyDecompressTest {

    private val plain = "var answer = 42;\nconsole.log('hello');\n".toByteArray(Charsets.UTF_8)

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    @Test
    fun `gzip body is decompressed by magic bytes even without a header`() {
        // 真实场景：上游回 Content-Encoding: gzip，而代理此前把字节原样吐给浏览器
        val decoded = WebViewProxy.decompress(gzip(plain), "gzip")
        assertTrue("解压后不应再是 gzip 魔数", !isGzip(decoded))
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun `magic bytes alone are enough to trigger decompression`() {
        // 上游漏报/谎报 Content-Encoding 时，也要靠魔数兜底
        assertArrayEquals(plain, WebViewProxy.decompress(gzip(plain), ""))
        assertArrayEquals(plain, WebViewProxy.decompress(gzip(plain), "identity"))
    }

    @Test
    fun `already decompressed body is left untouched even if the header claims gzip`() {
        // ⚠️ 这是「只信响应头」会踩的坑：头部说 gzip、字节其实已经是明文，
        // 强行解压会抛异常并弄坏好数据。魔数优先必须保证原样返回。
        assertArrayEquals(plain, WebViewProxy.decompress(plain, "gzip"))
        assertArrayEquals(plain, WebViewProxy.decompress(plain, "deflate"))
    }

    @Test
    fun `plain body without encoding passes through`() {
        assertArrayEquals(plain, WebViewProxy.decompress(plain, ""))
    }

    @Test
    fun `deflate body is inflated when declared`() {
        assertArrayEquals(plain, WebViewProxy.decompress(deflate(plain), "deflate"))
    }

    @Test
    fun `broken gzip payload falls back to the original bytes`() {
        // 魔数对但内容损坏：不能让整个代理请求失败，退化为原样返回
        val broken = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0x01, 0x02, 0x03)
        assertArrayEquals(broken, WebViewProxy.decompress(broken, "gzip"))
    }

    @Test
    fun `binary payload that merely starts like gzip is not required to be valid gzip`() {
        // 真实图片/字体不以 1f 8b 开头，必须原样透传（不能因为嗅探而破坏二进制）
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertArrayEquals(png, WebViewProxy.decompress(png, ""))
        assertEquals(8, WebViewProxy.decompress(png, "gzip").size)
    }
}
