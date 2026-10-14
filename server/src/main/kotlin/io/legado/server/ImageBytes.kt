package io.legado.server

import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 二进制媒体字节的**唯一收口**工具：哈希、gzip 兜底解压、图片魔数校验、内容类型与宽高探测。
 *
 * ## 为什么必须抽出来（复杂度惩罚 + 唯一收口）
 * `CoverCache`（封面）、`ImageCache`（漫画图片）、`AudioCache`（有声书）三条链路都会做
 * 「落盘前判一下这堆字节到底是什么」。若各自复制一份，**「gzip 假图片」那类缺陷就要各修一遍**
 * （历史事故见 SESSION-027：接口 200 + 字节数正常，浏览器全裂图）。
 *
 * ## 两条铁律（来自 AGENTS.md 部落知识）
 * 1. **按魔数判断，不信任 `Content-Encoding` 响应头**：上游会谎报，强行解压反而把好数据弄坏。
 * 2. **解压对所有路径生效**（对真实图片是无操作），但**强魔数校验只加在网络信任边界上**
 *    ——进程内注入的接缝（测试/内部调用方）不该承受网络侧的强校验，否则会拒掉合法用例。
 */
object ImageBytes {

    /** 单张图片/封面字节上限（与既有 `CoverCache` 行为保持一致，勿随意改动）。 */
    const val MAX_BYTES = 5 * 1024 * 1024

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 按**字节魔数**兜底解压，不依赖 `Content-Encoding`。
     *
     * `HttpResponse.BodyHandlers.ofByteArray()` **不会自动解压** gzip，而上游图床对 `.jpg`
     * 也会返回 `Content-Encoding: gzip`。若直接落盘，磁盘上就是「Content-Type 是 image/jpeg、
     * 内容却是 gzip」的坏文件：接口 HTTP 200、字节数正常，`<img>` 却空白/裂图。
     */
    fun decompressIfNeeded(raw: ByteArray): ByteArray {
        if (raw.size < 2) return raw
        val gzipMagic = (raw[0].toInt() and 0xff) == 0x1f && (raw[1].toInt() and 0xff) == 0x8b
        if (!gzipMagic) return raw
        return runCatching { GZIPInputStream(raw.inputStream()).use { it.readAllBytes() } }.getOrDefault(raw)
    }

    /** 图片魔数校验（JPEG / PNG / GIF / WebP / BMP）。 */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(i: Int) = bytes[i].toInt() and 0xff
        return when {
            at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> true                       // JPEG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4e && at(3) == 0x47 -> true      // PNG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true                       // GIF
            at(0) == 0x42 && at(1) == 0x4d -> true                                        // BMP
            // WebP: "RIFF"...."WEBP"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> true
            else -> false
        }
    }

    fun detectImageContentType(bytes: ByteArray): String? {
        if (!looksLikeImage(bytes)) return null
        fun at(i: Int) = bytes[i].toInt() and 0xff
        return when {
            at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4e && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x42 && at(1) == 0x4d -> "image/bmp"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * 只读**文件头**探测宽高，**绝不整图解码**。
     *
     * 用途：manifest 回给前端真实宽高 ⇒ 首屏占位不跳动（用 `ImageIO.read` 会带来 CPU 抖动，
     * 且把 `java.desktop` 拖进依赖）。探测不出来就返回 null，前端退回默认比例即可 —— 这不是错误。
     */
    fun probeSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 16) return null
        fun at(i: Int) = bytes[i].toInt() and 0xff

        // PNG：IHDR 的宽高固定在第 16..23 字节（大端）
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4e && at(3) == 0x47) {
            if (bytes.size < 24) return null
            return be32(bytes, 16) to be32(bytes, 20)
        }
        // GIF：小端 16 位
        if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) {
            if (bytes.size < 10) return null
            return le16(bytes, 6) to le16(bytes, 8)
        }
        // BMP：小端 32 位（高度可能为负表示自上而下，取绝对值）
        if (at(0) == 0x42 && at(1) == 0x4d) {
            if (bytes.size < 26) return null
            val w = le32(bytes, 18)
            val h = kotlin.math.abs(le32(bytes, 22))
            return w to h
        }
        // WebP：RIFF....WEBP，再按 chunk 类型取
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        ) {
            if (bytes.size < 30) return null
            return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
                // 扩展格式：画布宽高各 3 字节（小端，存的是 width-1 / height-1）
                "VP8X" -> (le24(bytes, 24) + 1) to (le24(bytes, 27) + 1)
                // 无损：1 字节签名后 14+14 位
                "VP8L" -> {
                    val bits = (at(21)) or (at(22) shl 8) or (at(23) shl 16) or (at(24) shl 24)
                    ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
                }
                // 有损：宽高各 2 字节（小端，低 14 位有效）
                "VP8 " -> (le16(bytes, 26) and 0x3FFF) to (le16(bytes, 28) and 0x3FFF)
                else -> null
            }
        }
        // JPEG：扫段找 SOFn（0xC0~0xCF，排除 DHT/JPG/DAC）
        if (at(0) == 0xff && at(1) == 0xd8) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (at(i) != 0xff) { i++; continue }
                val marker = at(i + 1)
                if (marker == 0xff) { i++; continue }
                if (marker == 0x01 || marker in 0xd0..0xd9) { i += 2; continue }
                val len = (at(i + 2) shl 8) or at(i + 3)
                if (len < 2) return null
                val isSof = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
                if (isSof) {
                    val h = (at(i + 5) shl 8) or at(i + 6)
                    val w = (at(i + 7) shl 8) or at(i + 8)
                    return w to h
                }
                i += len + 2
            }
        }
        return null
    }

    private fun be32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
            ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)

    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun le24(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or ((b[i + 2].toInt() and 0xff) shl 16)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
}
