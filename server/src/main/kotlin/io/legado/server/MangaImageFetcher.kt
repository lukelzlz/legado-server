package io.legado.server

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 一次成功的图片抓取结果（**已经过解压与魔数校验**，调用方可直接落盘）。 */
data class FetchedImageBytes(val contentType: String, val bytes: ByteArray, val finalUrl: String)

/**
 * 漫画图片的**唯一抓取收口**。
 *
 * ## 为什么必须只有一个收口
 * 防盗链（`Referer`）、书源登录态（Cookie）、SSRF 防护、gzip 假数据、体积上限、全局限流
 * —— 这六件事只要有一条在某个调用点上漏掉，症状都是「部分图裂、且不报错」。
 * 因此所有取图路径（前台按需、后台预取、章节缓存）都必须走这里。
 *
 * ## 限流为什么挂在类里而不是调用方
 * AGENTS.md 的教训（SESSION-025）：限流加在**单个任务内部**，当任务数量本身无界时等于没限流。
 * 这里的 `Semaphore` 是**整机**级别，`config.maxGlobalImageFetches` 默认 6。
 */
class MangaImageFetcher(
    private val config: ServerConfig,
    private val log: (String) -> Unit = {},
    /**
     * 测试注入接缝：`(url, headers, referer) -> (contentType, bytes)`。
     *
     * ⚠️ 与 `CoverCache` 的注入约定**不同**：那里的注入来自进程内可信生产者
     * （导入器/解析器给出的字节），故免于网络侧强校验；而这里的注入代表的是
     * **上游返回的原始字节**，因此与真实网络路径走**完全相同**的校验
     * （解压 → 体积上限 → 图片魔数）。否则测试会在「上游返回 HTML 错误页」
     * 这类最需要覆盖的场景上给出**假绿**。
     */
    private val injectedFetcher: ((String, Map<String, String>, String?) -> Pair<String, ByteArray>)? = null,
) {
    private val gate = Semaphore(config.maxGlobalImageFetches.coerceAtLeast(1))
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * 抓一张图。**任何失败都返回 null**（调用方渲染占位破图），绝不抛到阅读主流程。
     *
     * @param mediaHeaders 书源 header + 登录头 + Cookie（由 `RuleRunner.mediaHeaders()` 产出）
     * @param referer      章节页地址 —— 漫画站防盗链的主判据
     */
    suspend fun fetch(upstreamUrl: String, mediaHeaders: Map<String, String>, referer: String?): FetchedImageBytes? =
        gate.withPermit {
            runCatching { fetchOnce(upstreamUrl, mediaHeaders, referer) }
                .onFailure { log("manga image fetch failed: $upstreamUrl (${it.message})") }
                .getOrNull()
        }

    private fun fetchOnce(url: String, mediaHeaders: Map<String, String>, referer: String?): FetchedImageBytes {
        injectedFetcher?.let { injected ->
            val (contentType, raw) = injected(url, mediaHeaders, referer)
            return validate(raw, contentType, url)
        }

        var uri = lenientUri(url)
        repeat(MAX_REDIRECTS) {
            val response = sendWithRetry(buildRequest(uri, mediaHeaders, referer))
            if (response.statusCode() in 300..399) {
                val location = response.headers().firstValue("location")
                    .orElseThrow { IllegalArgumentException("图片重定向缺少地址") }
                uri = lenientUri(uri.resolve(location).toString())
                return@repeat
            }
            require(response.statusCode() in 200..299) { "图片上游返回 HTTP ${response.statusCode()}" }
            val declaredType = response.headers().firstValue("content-type").orElse("image/jpeg")
            return validate(response.body(), declaredType, uri.toString())
        }
        throw IllegalArgumentException("图片重定向次数超过限制")
    }

    /**
     * 唯一校验收口（真实网络路径与注入路径**共用**）：
     * 按魔数兜底解压 → 体积上限 → **图片魔数校验** → 内容类型以真实字节为准。
     *
     * 最后一步是网络信任边界上的防御：上游既会返回 HTML 错误页，也会谎报 `Content-Type`。
     */
    private fun validate(raw: ByteArray, declaredContentType: String, finalUrl: String): FetchedImageBytes {
        val body = ImageBytes.decompressIfNeeded(raw)
        require(body.size <= ImageBytes.MAX_BYTES) { "图片超过 5 MiB 限制" }
        require(ImageBytes.looksLikeImage(body)) { "图片内容不是可解码的图片（可能是错误页或未解压的压缩数据）" }
        return FetchedImageBytes(
            contentType = ImageBytes.detectImageContentType(body) ?: declaredContentType.substringBefore(';'),
            bytes = body,
            finalUrl = finalUrl,
        )
    }

    private fun buildRequest(uri: URI, mediaHeaders: Map<String, String>, referer: String?): HttpRequest {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET()
        var hasReferer = false
        var hasUserAgent = false
        mediaHeaders.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            if (name.lowercase() in RESTRICTED_HEADERS) return@forEach
            if (name.equals("Referer", ignoreCase = true)) hasReferer = true
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            runCatching { builder.header(name, value) }
        }
        // 书源自己声明的 Referer 优先；没声明才用章节页地址兜底
        if (!hasReferer && !referer.isNullOrBlank()) builder.header("Referer", referer)
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)
        return builder.build()
    }

    private fun sendWithRetry(request: HttpRequest): HttpResponse<ByteArray> = try {
        client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    } catch (error: java.io.IOException) {
        // 首次连接偶发失败（连接复用/握手抖动）重试一次：漫画站经常瞬时掐掉连接，
        // 不重试会表现为「随机几张图裂」这种极难归因的现象。
        client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    /**
     * 宽容解析 URL：漫画源的图片地址常带未编码的 `{}"` （Legado 生态常态），
     * `java.net.URI` 严格遵循 RFC 3986 会直接抛异常 ⇒ 先走全项目共用的宽容编码。
     */
    private fun lenientUri(url: String): URI {
        val encoded = encodeIllegalUrlChars(url.trim())
        val uri = runCatching { URI(encoded) }
            .getOrElse { throw IllegalArgumentException("图片 URL 解析失败: ${it.message}") }
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "漫画图片")
        return uri
    }

    private companion object {
        const val MAX_REDIRECTS = 4
        const val DEFAULT_USER_AGENT = "LegadoServer/0.1"
        /** `java.net.http` 禁止应用层设置的受限头，设置会直接抛异常，必须过滤。 */
        val RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade")
    }
}
