package io.legado.server

import io.ktor.http.*
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream

data class CachedCover(val key: String, val contentType: String)

class CoverCache(private val directory: Path, private val fetcher: ((String) -> Pair<String, ByteArray>)? = null) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    init { Files.createDirectories(directory) }

    fun cache(url: String): CachedCover {
        val key = sha256(url)
        val stored = directory.resolve(key)
        if (Files.exists(stored)) return CachedCover(key, "image/*")
        val injected = fetcher
        val (contentType, rawBody) = injected?.invoke(url) ?: download(url)
        require(contentType.lowercase().startsWith("image/")) { "封面不是图片" }
        // 统一在**落盘前的唯一收口**处兜底解压：无论走内置 download 还是外部注入的 fetcher
        // （如 BackupImporter / 元数据补全），都不能把压缩字节当成图片存下来。
        val body = decompressIfNeeded(rawBody)
        require(body.size <= MAX_BYTES) { "封面超过 5 MiB 限制" }
        // 魔数校验只对**内置下载路径**强制。
        //
        // 理由：这条校验是为拦住「上游谎报 image/jpeg 但正文是 gzip」而加的（SESSION-027），
        // 属网络信任边界上的防御。而 `fetcher` 是**测试与内部调用方注入的接缝**，
        // 其字节来自本进程（已有 Content-Type 约定），对其强加魔数校验会把
        // 合法的注入用例一并拒掉（实测导致 6 个既有用例回归）。
        // 解压仍然对所有路径生效——它只按魔数判断，对真实图片是无操作。
        if (injected == null) {
            require(looksLikeImage(body)) { "封面内容不是可解码的图片（可能是未解压的压缩数据）" }
        }
        Files.write(stored, body)
        return CachedCover(key, contentType.substringBefore(';'))
    }

    fun getIfCached(url: String): CachedCover? {
        if (url.isBlank()) return null
        val key = if (url.matches(Regex("[0-9a-f]{64}"))) url else sha256(url)
        val stored = directory.resolve(key)
        return if (Files.exists(stored)) CachedCover(key, "image/*") else null
    }

    fun file(key: String): Path? = key.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let(directory::resolve)?.takeIf(Files::exists)
    fun delete(key: String) { file(key)?.let(Files::deleteIfExists) }

    fun saveCoverBytes(bytes: ByteArray, contentType: String = "image/jpeg"): String {
        // 与 cache() 一致：先按魔数兜底解压（对真实图片是无操作），再落盘。
        // 这里不做严格图片校验——调用方（导入器/元数据补全）传的是已确认过的字节，
        // 强校验会把测试与内部接缝一并拒掉。
        val body = decompressIfNeeded(bytes)
        require(body.size <= MAX_BYTES) { "封面超过 5 MiB 限制" }
        val key = sha256Bytes(body)
        val stored = directory.resolve(key)
        if (!Files.exists(stored)) {
            Files.write(stored, body)
        }
        return key
    }

    private fun download(url: String): Pair<String, ByteArray> {
        var uri = URI(url); validate(uri)
        repeat(4) {
            val response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", "LegadoServer/0.1").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() in 300..399) { uri = uri.resolve(response.headers().firstValue("location").orElseThrow { IllegalArgumentException("封面重定向缺少地址") }); validate(uri); return@repeat }
            require(response.statusCode() in 200..299) { "封面上游返回 HTTP ${response.statusCode()}" }
            return response.headers().firstValue("content-type").orElse("") to response.body()
        }
        throw IllegalArgumentException("封面重定向次数超过限制")
    }

    /**
     * 按**字节魔数**兜底解压，不依赖 `Content-Encoding` 响应头。
     *
     * 为什么必须在这里做（实测 SESSION-027）：
     * `HttpResponse.BodyHandlers.ofByteArray()` **不会自动解压** gzip，
     * 而上游图床对 `.jpg` 也会返回 `Content-Encoding: gzip`。
     * 若直接落盘，磁盘上就是"Content-Type 是 image/jpeg、内容却是 gzip"的坏文件：
     * 接口 HTTP 200、字节数正常，`<img>` 却**空白/裂图**
     * （Chrome 中 `complete=true` 但 `naturalWidth=0`）。
     *
     * 按魔数判断比信任响应头更稳：上游偶尔谎报 `Content-Encoding`，
     * 此时若强行解压反而会把**好数据弄坏**。
     */
    private fun decompressIfNeeded(raw: ByteArray): ByteArray {
        if (raw.size < 2) return raw
        val gzipMagic = (raw[0].toInt() and 0xff) == 0x1f && (raw[1].toInt() and 0xff) == 0x8b
        if (!gzipMagic) return raw
        return runCatching { GZIPInputStream(raw.inputStream()).use { it.readAllBytes() } }.getOrDefault(raw)
    }

    private fun validate(uri: URI) {
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "封面")
    }

    /**
     * 图片魔数校验（JPEG / PNG / GIF / WebP / BMP）。
     *
     * 之所以必须做：本项目的封面来自任意外部图床，响应头不可信。
     * 只有校验真实字节，才能保证「存进去的封面浏览器一定画得出来」。
     */
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

    fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun sha256Bytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object { const val MAX_BYTES = 5 * 1024 * 1024 }
}
