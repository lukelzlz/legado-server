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

data class CachedCover(val key: String, val contentType: String)

class CoverCache(private val directory: Path, private val fetcher: ((String) -> Pair<String, ByteArray>)? = null) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    init { Files.createDirectories(directory) }

    fun cache(url: String): CachedCover {
        val key = sha256(url)
        val stored = directory.resolve(key)
        if (Files.exists(stored)) return CachedCover(key, "image/*")
        val (contentType, body) = fetcher?.invoke(url) ?: download(url)
        require(contentType.lowercase().startsWith("image/")) { "封面不是图片" }
        require(body.size <= MAX_BYTES) { "封面超过 5 MiB 限制" }
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

    private fun validate(uri: URI) {
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "封面")
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private companion object { const val MAX_BYTES = 5 * 1024 * 1024 }
}
