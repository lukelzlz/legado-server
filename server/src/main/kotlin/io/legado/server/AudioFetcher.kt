package io.legado.server

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.PushbackInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** 一次音频抓取的结果。**失败带原因**（不像漫画那样静默返回 null）——见 [Failure] 的说明。 */
sealed interface AudioFetchOutcome {
    data class Success(val contentType: String, val byteSize: Long) : AudioFetchOutcome

    /**
     * 失败。
     *
     * 与漫画的差异：漫画抓图失败只返回 `null`（调用方渲染破图占位），因为"这张图挂了"
     * 不需要向用户解释。音频**必须给一句能指导下一步动作的话**：整章听不了的时候，
     * 用户需要知道是"要登录"、"格式不支持"还是"上游 404"——否则只能干瞪眼。
     */
    data class Failure(val reason: String) : AudioFetchOutcome
}

/**
 * 有声书音频的**唯一抓取收口**。
 *
 * ## 为什么必须只有一个收口（与 [MangaImageFetcher] 同源）
 * 防盗链（`Referer`）、书源登录态（Cookie）、SSRF 防护、体积上限、全局限流、上游谎报
 * ——这六件事任何一条在某个调用点上漏掉，症状都是"部分章放不出、且不报错"。
 *
 * ## 三条与图片**刻意不同**的实现（都是音频独有）
 * 1. **流式落盘**：10~60 MiB/章，必须**边读边写** `.part`；`readAllBytes()` 会 OOM
 *    （AGENTS.md：60 MB × 并发 3 = 180 MB 堆，必炸）。
 * 2. **弱校验而不是强魔数**：音频容器/编码太杂，拿"必须是某种魔数"卡会误杀合法编码；
 *    这里只判「不是 HTML/JSON 错误页」+ **内容类型白名单**（判据收口在 [AudioBytes]）。
 * 3. **流式 gzip 兜底**：上游可能对媒体也回 gzip（SESSION-027 的教训），但音频不能先全量读进内存再解压；
 *    这里用 `PushbackInputStream` 窥探头两个字节，是 gzip 就**套一层 `GZIPInputStream` 边解边写**。
 *
 * ## 限流位置
 * **全局** `Semaphore(config.maxGlobalAudioFetches)`（默认 3）+ **单 host 1**。
 * AGENTS.md（SESSION-025）：限流加在单个任务内部，当任务数量本身无界时等于没限流。
 */
class AudioFetcher(
    private val config: ServerConfig,
    private val log: (String) -> Unit = {},
    /**
     * 测试注入接缝：`(url, headers, referer, 分段专属头JSON) -> (contentType, bytes)`。
     *
     * ⚠️ 与 `MangaImageFetcher` 的约定一致：注入代表的是**上游返回的原始字节**，
     * 因此与真实网络路径走**完全相同**的校验。否则测试会在"上游返回 HTML 错误页"
     * 这类最需要覆盖的场景上给出**假绿**。
     */
    private val injectedFetcher: ((String, Map<String, String>, String?, String?) -> Pair<String, ByteArray>)? = null,
) {
    private val gate = Semaphore(config.maxGlobalAudioFetches.coerceAtLeast(1))
    private val hostGates = ConcurrentHashMap<String, Semaphore>()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * 抓一段音频并**流式写进 [AudioCache] 的存储**（成功时磁盘上就是完整文件，失败时不留 `.part`）。
     *
     * @param mediaHeaders 书源 header + 登录头 + Cookie（`RuleRunner.mediaHeaders()` 产出）
     * @param referer      章节页地址 —— 音频站防盗链的主判据
     * @param trackHeadersJson 该段专属请求头（`url,{"headers":{…}}` 的 `headers` 部分），优先级最高
     */
    suspend fun fetchInto(
        upstreamUrl: String,
        mediaHeaders: Map<String, String>,
        referer: String?,
        trackHeadersJson: String?,
        store: AtomicFileStore,
        cacheKey: String,
    ): AudioFetchOutcome = gate.withPermit {
        val host = runCatching { URI(upstreamUrl.trim()).host.orEmpty() }.getOrDefault("")
        val hostGate = hostGates.computeIfAbsent(host) { Semaphore(1) }
        hostGate.withPermit {
            runCatching { fetchOnce(upstreamUrl, mediaHeaders, referer, trackHeadersJson, store, cacheKey) }
                .getOrElse { error ->
                    log("audio fetch failed: $upstreamUrl (${error.message})")
                    AudioFetchOutcome.Failure(error.message ?: "上游抓取失败")
                }
        }
    }

    private fun fetchOnce(
        url: String,
        mediaHeaders: Map<String, String>,
        referer: String?,
        trackHeadersJson: String?,
        store: AtomicFileStore,
        cacheKey: String,
    ): AudioFetchOutcome {
        val headers = mergeHeaders(mediaHeaders, trackHeadersJson)
        injectedFetcher?.let { injected ->
            val (declaredType, bytes) = injected(url, headers, referer, trackHeadersJson)
            return persist(store, cacheKey, bytes.inputStream(), declaredType, url)
        }
        val cleanUrl = url.trim()
        var uri = lenientUri(cleanUrl)
        var lastStatus = 0
        repeat(MAX_REDIRECTS) {
            val response = sendWithRetry(buildRequest(uri, headers, referer))
            lastStatus = response.statusCode()
            if (lastStatus in 300..399) {
                val location = response.headers().firstValue("location").orElse(null)
                    ?: throw IllegalArgumentException("音频重定向缺少地址")
                uri = lenientUri(uri.resolve(location).toString())
                return@repeat
            }
            if (lastStatus !in 200..299) {
                // 4xx/5xx 如实上报：403 ⇒ 多半要登录/防盗链；404 ⇒ 链接失效
                throw IllegalArgumentException("上游返回 HTTP $lastStatus")
            }
            val declaredType = response.headers().firstValue("content-type").orElse("")
            // 先按 Content-Length 早退：省下一次 200 MiB 的白下载
            val declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
            require(declaredLength <= AudioBytes.MAX_SEGMENT_BYTES) {
                "音频分段声明大小 ${declaredLength / 1024 / 1024} MiB 超过 ${AudioBytes.MAX_SEGMENT_BYTES / 1024 / 1024} MiB 上限"
            }
            response.body().use { body ->
                return persist(store, cacheKey, body, declaredType, uri.toString())
            }
        }
        throw IllegalArgumentException("音频重定向次数超过限制（最后状态 $lastStatus）")
    }

    /**
     * 流式落盘 + 弱校验。**校验发生在"原子移动之前"**：不合格就抛错，
     * `AtomicFileStore.writeStream` 会删掉临时文件 ⇒ 绝不会留下半截或坏内容的"成品"。
     */
    private fun persist(
        store: AtomicFileStore,
        cacheKey: String,
        raw: java.io.InputStream,
        declaredType: String,
        finalUrl: String,
    ): AudioFetchOutcome {
        val head = ByteArray(AudioBytes.HEAD_BYTES)
        var headLength = 0
        val written = store.writeStream(cacheKey) { output ->
            val source = decompressStreamIfNeeded(raw)
            source.use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    require(total <= AudioBytes.MAX_SEGMENT_BYTES) {
                        "音频分段超过 ${AudioBytes.MAX_SEGMENT_BYTES / 1024 / 1024} MiB 上限（上游返回了异常内容？）"
                    }
                    if (headLength < head.size) {
                        val copy = minOf(read, head.size - headLength)
                        System.arraycopy(buffer, 0, head, headLength, copy)
                        headLength += copy
                    }
                    output.write(buffer, 0, read)
                }
            }
            validate(head, headLength, declaredType, finalUrl)
        }
        val contentType = AudioBytes.resolveContentType(head.copyOf(headLength), declaredType)
        return AudioFetchOutcome.Success(AudioBytes.normalizeContentType(contentType), written)
    }

    /** 唯一校验收口（真实网络路径与注入路径共用）。 */
    private fun validate(head: ByteArray, headLength: Int, declaredType: String, finalUrl: String) {
        val actual = head.copyOf(headLength)
        require(actual.isNotEmpty()) { "上游返回了 0 字节（$finalUrl）" }
        val resolved = AudioBytes.resolveContentType(actual, declaredType)
        require(AudioBytes.isWhitelisted(resolved)) { AudioBytes.rejectionReason(actual, declaredType) }
    }

    /** 按魔数窥探前两字节：是 gzip 就套 `GZIPInputStream`（**边解边写**，不进内存）。 */
    private fun decompressStreamIfNeeded(raw: java.io.InputStream): java.io.InputStream {
        val pushback = PushbackInputStream(raw, 2)
        val first = pushback.read()
        val second = pushback.read()
        if (second >= 0) pushback.unread(second)
        if (first >= 0) pushback.unread(first)
        val isGzip = first == 0x1f && second == 0x8b
        return if (isGzip) GZIPInputStream(pushback) else pushback
    }

    /**
     * 合并请求头：书源 header/Cookie ← 分段专属头（后者覆盖前者）。
     *
     * 分段专属头解析失败**退回源级头**（静默）：一个头写法不认识，不该让整段音频放不出。
     */
    private fun mergeHeaders(mediaHeaders: Map<String, String>, trackHeadersJson: String?): Map<String, String> {
        if (trackHeadersJson.isNullOrBlank()) return mediaHeaders
        val extra = runCatching {
            val obj = Json.parseToJsonElement(trackHeadersJson) as? JsonObject ?: return@runCatching emptyMap()
            obj.entries.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
            }.toMap()
        }.getOrDefault(emptyMap())
        return if (extra.isEmpty()) mediaHeaders else mediaHeaders + extra
    }

    private fun buildRequest(uri: URI, headers: Map<String, String>, referer: String?): HttpRequest {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET()
        var hasReferer = false
        var hasUserAgent = false
        headers.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            if (name.lowercase() in RESTRICTED_HEADERS) return@forEach
            if (name.equals("Referer", ignoreCase = true)) hasReferer = true
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            runCatching { builder.header(name, value) }
        }
        // 书源自己声明的 Referer 优先；没声明才用章节页地址兜底
        if (!hasReferer && !referer.isNullOrBlank()) builder.header("Referer", referer)
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)
        // ⚠️ 显式声明 identity：`java.net.http` 不解压，而上游对媒体回 gzip 是真实存在的
        //（SESSION-027）。这里要的是"别压"，真被压了也还有流式 gunzip 兜底。
        builder.header("Accept-Encoding", "identity")
        return builder.build()
    }

    private fun sendWithRetry(request: HttpRequest): HttpResponse<java.io.InputStream> = try {
        client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    } catch (error: java.io.IOException) {
        // 首次连接偶发失败（连接复用/握手抖动）重试一次：音频站经常瞬时掐掉连接
        client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    /** 宽容解析 URL：书源里的地址常带未编码的 `{}"`（Legado 生态常态），并必须过 SSRF 校验。 */
    private fun lenientUri(url: String): URI {
        val encoded = encodeIllegalUrlChars(url.trim())
        val uri = runCatching { URI(encoded) }
            .getOrElse { throw IllegalArgumentException("音频 URL 解析失败: ${it.message}") }
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "有声书音频")
        return uri
    }

    private companion object {
        const val MAX_REDIRECTS = 4
        const val DEFAULT_USER_AGENT = "LegadoServer/0.1"
        val RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade", "accept-encoding")
    }
}
