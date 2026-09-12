package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** 反向代理失败时抛出，由路由层转换为 4xx 响应。 */
class WebViewException(message: String) : RuntimeException(message)

/** 代理结果：状态码 + 内容类型 + 响应体。 */
data class ProxiedPayload(val status: Int, val contentType: String, val body: ByteArray)

/**
 * 内置 WebView 登录引擎。
 *
 * 浏览器出于同源策略无法跨站 iframe 目标站点、也无法读取其 Cookie，因此这里把目标站点
 * **整站反向代理**到本服务路径下，使登录页可以安全地嵌入前端 iframe：
 * - HTML 会被重写，所有链接/表单/子资源都指回代理路径；
 * - 目标站点的 Set-Cookie 被吸收进书源自己的 Cookie jar，登录成功即刻对书源生效；
 * - 代理访问使用一次性短时效令牌鉴权，iframe 因此可以开启 sandbox 而无需携带管理会话。
 */
class WebViewProxy(private val database: Database) {

    private data class Ticket(
        val sourceId: String,
        val domains: Set<String>,
        val expiresAt: Long,
        /** 书源 JS 用数据地址生成的自包含页面（如「使用教程」「书源更新」「设置中心」） */
        val inline: MutableMap<String, String> = ConcurrentHashMap(),
    )

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val random = SecureRandom()
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // ---------------------------------------------------------------- 令牌

    fun issueTicket(sourceId: String, sourceJson: String, startUrl: String? = null): String {
        pruneTickets()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        // 允许域名来自书源自身配置；书源 JS 指定的入口地址也一并纳入，
        // 这样即便 bookSourceUrl 是「大灰狼融合VIP5.0」这类自定义标识也能正常代理。
        val domains = allowedDomains(sourceJson).toMutableSet()
        startUrl?.let { url -> hostOf(url)?.let { domains.add(registrableDomain(it)) } }
        tickets[token] = Ticket(
            sourceId = sourceId,
            domains = domains,
            expiresAt = System.currentTimeMillis() + TICKET_TTL_MS,
        )
        return token
    }

    fun revokeTicket(token: String) {
        tickets.remove(token)
    }

    private fun resolveTicket(token: String, sourceId: String): Ticket {
        val ticket = tickets[token] ?: throw WebViewException("登录会话已失效，请重新打开内置浏览器")
        if (ticket.expiresAt < System.currentTimeMillis()) {
            tickets.remove(token)
            throw WebViewException("登录会话已过期，请重新打开内置浏览器")
        }
        if (ticket.sourceId != sourceId) throw WebViewException("登录会话与书源不匹配")
        return ticket
    }

    private fun pruneTickets() {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value.expiresAt < now }
    }

    // ---------------------------------------------------------------- 入口

    /** 打开一个页面（HTML 会被重写后返回）。 */
    fun openPage(sourceId: String, token: String, target: String): ProxiedPayload {
        val ticket = resolveTicket(token, sourceId)
        val uri = validateTarget(ticket, target, enforceDomain = true)
        val response = requestFollowing(uri, "GET", null, null, sourceId, referer = null)
        val contentType = response.headers().firstValue("content-type").orElse("")
        val raw = response.body()
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime.isNotEmpty() && !mime.contains("html")) {
            // 目标其实是个文件（如 PDF/图片），直接透传
            return ProxiedPayload(response.statusCode(), sanitizeContentType(contentType), raw)
        }
        val charset = detectCharset(contentType, raw)
        val html = decodeBody(raw, response.headers().firstValue("content-encoding").orElse(""), charset)
        val rewritten = rewriteHtml(html, uri, sourceId, token)
        return ProxiedPayload(response.statusCode(), "text/html; charset=utf-8", rewritten.toByteArray(Charsets.UTF_8))
    }

    /** 子资源（CSS/JS/图片/字体）二进制透传。 */
    fun openResource(sourceId: String, token: String, target: String): ProxiedPayload {
        val ticket = resolveTicket(token, sourceId)
        val uri = validateTarget(ticket, target, enforceDomain = false)
        val response = requestFollowing(uri, "GET", null, null, sourceId, referer = refererFor(uri))
        val contentType = response.headers().firstValue("content-type").orElse("application/octet-stream")
        val raw = response.body()
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime.contains("html")) {
            // 某些资源实际返回了 HTML（例如防盗链跳转页），同样重写以便站内跳转可用
            val charset = detectCharset(contentType, raw)
            val html = decodeBody(raw, response.headers().firstValue("content-encoding").orElse(""), charset)
            return ProxiedPayload(response.statusCode(), "text/html; charset=utf-8", rewriteHtml(html, uri, sourceId, token).toByteArray(Charsets.UTF_8))
        }
        if (mime.contains("css")) {
            val charset = detectCharset(contentType, raw)
            val css = decodeBody(raw, response.headers().firstValue("content-encoding").orElse(""), charset)
            val rewritten = rewriteCss(css, uri, sourceId, token)
            return ProxiedPayload(response.statusCode(), "text/css; charset=utf-8", rewritten.toByteArray(Charsets.UTF_8))
        }
        return ProxiedPayload(response.statusCode(), sanitizeContentType(contentType), raw)
    }

    /** 表单提交转发（GET / POST 均可）。 */
    fun submitForm(
        sourceId: String,
        token: String,
        action: String,
        method: String,
        contentType: String?,
        body: ByteArray,
    ): ProxiedPayload {
        val ticket = resolveTicket(token, sourceId)
        val uri = validateTarget(ticket, action, enforceDomain = true)
        val response = if (method.equals("POST", ignoreCase = true)) {
            requestFollowing(uri, "POST", body, contentType, sourceId, referer = refererFor(uri))
        } else {
            requestFollowing(uri, "GET", null, null, sourceId, referer = refererFor(uri))
        }
        val responseContentType = response.headers().firstValue("content-type").orElse("")
        val raw = response.body()
        val mime = responseContentType.substringBefore(';').trim().lowercase()
        if (mime.isNotEmpty() && !mime.contains("html")) {
            return ProxiedPayload(response.statusCode(), sanitizeContentType(responseContentType), raw)
        }
        val charset = detectCharset(responseContentType, raw)
        val html = decodeBody(raw, response.headers().firstValue("content-encoding").orElse(""), charset)
        return ProxiedPayload(response.statusCode(), "text/html; charset=utf-8", rewriteHtml(html, uri, sourceId, token).toByteArray(Charsets.UTF_8))
    }

    /** 当前书源已捕获到的 Cookie（按域名分组），用于前端展示登录结果。 */
    fun jarSnapshot(sourceId: String): Map<String, String> = database.getSourceCookieJar(sourceId)

    // ------------------------------------------------- 书源自生成的内置页面

    /**
     * 托管书源 JS 生成的数据地址页面，返回一个短 key。
     *
     * 书源常用 `java.startBrowserAwait('data:text/html;base64,' + ...)` 弹出教程/更新/设置页，
     * 这种地址动辄上万字符；若直接塞进 iframe 的 URL，会被 Ktor 的请求行 8192 字符上限拒绝
     * （表现为 “Line exceeds limit of 8192 characters”）。改由服务端解码托管、前端只传短 key。
     */
    fun registerInlinePage(sourceId: String, token: String, dataUrl: String): String {
        val ticket = resolveTicket(token, sourceId)
        val html = decodeDataUrl(dataUrl)
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        val key = bytes.joinToString("") { "%02x".format(it) }
        if (ticket.inline.size > MAX_INLINE_PAGES) ticket.inline.clear()
        ticket.inline[key] = html
        return key
    }

    fun openInlinePage(sourceId: String, token: String, key: String): ProxiedPayload {
        val ticket = resolveTicket(token, sourceId)
        val html = ticket.inline[key] ?: throw WebViewException("内置页面已失效，请重新打开")
        // 数据地址没有来源站点，用书源域名兜底解析其中的绝对地址
        val base = ticket.domains.firstOrNull()
            ?.let { runCatching { URI("https://$it/") }.getOrNull() }
            ?: URI("about:blank")
        val rewritten = rewriteHtml(html, base, sourceId, token)
        return ProxiedPayload(200, "text/html; charset=utf-8", rewritten.toByteArray(Charsets.UTF_8))
    }

    /** 数据地址是否为可托管的 HTML。 */
    fun isInlineDataUrl(raw: String): Boolean = raw.trim().startsWith("data:text/html", ignoreCase = true)

    private fun decodeDataUrl(raw: String): String {
        val value = raw.trim()
        val comma = value.indexOf(',')
        if (comma < 0) throw WebViewException("无效的数据地址")
        val meta = value.substring(5, comma)
        val payload = value.substring(comma + 1)
        val bytes = if (meta.contains("base64", ignoreCase = true)) {
            runCatching { java.util.Base64.getDecoder().decode(payload.trim()) }
                .getOrElse { throw WebViewException("数据地址 base64 解码失败") }
        } else {
            runCatching { java.net.URLDecoder.decode(payload, Charsets.UTF_8).toByteArray(Charsets.UTF_8) }
                .getOrElse { payload.toByteArray(Charsets.UTF_8) }
        }
        if (bytes.size > MAX_INLINE_BYTES) throw WebViewException("内置页面超过 4MB 上限")
        return String(bytes, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- 请求

    private fun requestFollowing(
        uri: URI,
        method: String,
        body: ByteArray?,
        contentType: String?,
        sourceId: String,
        referer: String?,
    ): HttpResponse<ByteArray> {
        var current = uri
        var currentMethod = method
        var currentBody = body
        var currentContentType = contentType
        var currentReferer = referer
        repeat(MAX_REDIRECTS) {
            val response = send(current, currentMethod, currentBody, currentContentType, sourceId, currentReferer)
            harvestCookies(sourceId, current, response)
            val status = response.statusCode()
            if (status in 300..399) {
                val location = response.headers().firstValue("location").orElse(null) ?: return response
                val next = runCatching { current.resolve(location) }.getOrNull() ?: return response
                currentReferer = current.toString()
                current = next
                // 302/303 之后按浏览器语义退化为 GET
                if (status == 303 || (status in setOf(301, 302) && currentMethod == "POST")) {
                    currentMethod = "GET"
                    currentBody = null
                    currentContentType = null
                }
                return@repeat
            }
            return response
        }
        throw WebViewException("目标站点重定向次数过多")
    }

    private fun send(
        uri: URI,
        method: String,
        body: ByteArray?,
        contentType: String?,
        sourceId: String,
        referer: String?,
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(25))
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept-Encoding", "gzip, deflate")
        referer?.let { builder.header("Referer", it) }
        database.getSourceCookie(sourceId, uri.toString())?.takeIf { it.isNotBlank() }?.let {
            builder.header("Cookie", it)
        }
        if (body != null) {
            builder.header("Content-Type", contentType ?: "application/x-www-form-urlencoded")
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.body().size > MAX_BODY_BYTES) throw WebViewException("目标内容超过 8MB 上限")
            response
        } catch (error: WebViewException) {
            throw error
        } catch (error: Exception) {
            throw WebViewException("无法访问目标站点：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** 把目标站点的 Set-Cookie 吸收进书源 Cookie jar，登录后书源立即可用。 */
    private fun harvestCookies(sourceId: String, uri: URI, response: HttpResponse<*>) {
        val setCookies = response.headers().allValues("set-cookie")
        if (setCookies.isEmpty()) return
        setCookies.forEach { header ->
            runCatching { database.setSourceCookieFromSetCookie(sourceId, uri.toString(), header) }
        }
    }

    // ---------------------------------------------------------------- 校验

    private fun validateTarget(ticket: Ticket, raw: String, enforceDomain: Boolean): URI {
        val value = raw.trim()
        if (value.isEmpty()) throw WebViewException("缺少目标地址")
        val uri = runCatching { URI(value) }.getOrNull() ?: throw WebViewException("目标地址无效")
        if (uri.scheme?.lowercase() !in setOf("http", "https")) throw WebViewException("仅支持 HTTP(S) 地址")
        val host = uri.host ?: throw WebViewException("目标地址缺少主机名")
        // NetworkSecurity 抛的是 IllegalArgumentException，需转成 WebViewException，
        // 否则会被 StatusPages 兜成 500「服务器内部错误」，用户看不到真实原因。
        try {
            NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "登录页")
        } catch (error: IllegalArgumentException) {
            throw WebViewException(error.message ?: "该地址不被允许访问")
        }
        // 只对「导航」做域名白名单；子资源放行任意公网域名，否则站点 CDN 会被拦死
        if (enforceDomain && ticket.domains.isNotEmpty()) {
            val domain = registrableDomain(host)
            if (ticket.domains.none { it == domain }) {
                throw WebViewException("该地址不属于此书源的站点域名（$domain），已拒绝代理")
            }
        }
        return uri
    }

    companion object {
        private const val TICKET_TTL_MS = 30 * 60 * 1000L
        private const val MAX_REDIRECTS = 6
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val MAX_INLINE_BYTES = 4 * 1024 * 1024
        private const val MAX_INLINE_PAGES = 16
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val MULTI_PART_TLDS = setOf(
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
            "com.tw", "org.tw", "com.hk", "co.jp", "ne.jp", "co.uk", "com.au", "com.sg", "com.my", "com.br",
        )

        private val URL_PATTERN = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        /** 直接抓主机名，避免书源模板（如 {{key}}）或中文路径导致 URI 解析失败 */
        private val HOST_PATTERN = Regex("""https?://([^/\s"'<>\\:?#]+)""", RegexOption.IGNORE_CASE)
        private val CSS_URL_PATTERN = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""", RegexOption.IGNORE_CASE)
        private val CSS_IMPORT_PATTERN = Regex("""@import\s+(['"])([^'"]+)\1""", RegexOption.IGNORE_CASE)

        /** 宽容地取出主机名：先试标准 URI，失败则退回正则（书源 URL 常含 {{}} 模板）。 */
        private fun hostOf(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            runCatching { URI(value) }.getOrNull()?.host?.takeIf { it.isNotBlank() }?.let { return it }
            return HOST_PATTERN.find(value)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        }

        /** 取可注册域名（eTLD+1 近似），用于把同站子域一并纳入白名单。 */
        fun registrableDomain(host: String): String {
            val normalized = host.lowercase().trim().trim('.')
            val parts = normalized.split('.').filter { it.isNotEmpty() }
            if (parts.size <= 2) return normalized
            val lastTwo = parts.takeLast(2).joinToString(".")
            if (lastTwo in MULTI_PART_TLDS && parts.size >= 3) return parts.takeLast(3).joinToString(".")
            return lastTwo
        }

        /**
         * 书源未配置 loginUrl、bookSourceUrl 又是自定义标识时的兜底入口：
         * 取书源自身 JSON 中出现的第一个 http(s) 地址，保证「内置浏览器」按钮不会直接死路。
         */
        fun defaultStartUrl(sourceJson: String): String? = URL_PATTERN.find(sourceJson)?.value

        /** 书源允许代理的域名集合，来源于书源自身配置中的全部 http(s) 地址。 */
        fun allowedDomains(sourceJson: String): Set<String> {
            val domains = linkedSetOf<String>()
            val obj = runCatching { Json.parseToJsonElement(sourceJson).jsonObject }.getOrNull()
            if (obj != null) {
                listOf("bookSourceUrl", "loginUrl").forEach { key ->
                    (obj[key] as? JsonPrimitive)?.contentOrNull?.let { value ->
                        hostOf(value)?.let { domains.add(registrableDomain(it)) }
                    }
                }
            }
            URL_PATTERN.findAll(sourceJson).forEach { match ->
                hostOf(match.value)?.let { domains.add(registrableDomain(it)) }
            }
            return domains.filter { it.isNotBlank() && it.contains('.') }.toSet()
        }

        private fun refererFor(uri: URI): String = "${uri.scheme}://${uri.host}/"

        private fun sanitizeContentType(contentType: String): String {
            val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = Regex("charset=\"?([A-Za-z0-9_\\-]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)
            return if (charset != null) "$mime; charset=${charset.lowercase()}" else mime
        }

        private fun detectCharset(contentType: String, body: ByteArray): Charset {
            val declared = Regex("charset=\"?([A-Za-z0-9_\\-]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)
            if (!declared.isNullOrBlank()) {
                runCatching { Charset.forName(declared) }.getOrNull()?.let { return it }
            }
            // 中文小说站大量使用 GBK/GB2312，Content-Type 常常不带 charset，需从 meta 嗅探
            val head = String(body, 0, minOf(body.size, 4096), Charsets.ISO_8859_1)
            val sniffed = Regex("""charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.get(1)
            if (!sniffed.isNullOrBlank()) {
                runCatching { Charset.forName(sniffed) }.getOrNull()?.let { return it }
            }
            return Charsets.UTF_8
        }

        private fun decodeBody(bytes: ByteArray, contentEncoding: String, charset: Charset): String {
            val decoded = when (contentEncoding.lowercase()) {
                "gzip" -> runCatching { GZIPInputStream(bytes.inputStream()).readAllBytes() }.getOrDefault(bytes)
                "deflate" -> runCatching { InflaterInputStream(bytes.inputStream()).readAllBytes() }.getOrDefault(bytes)
                else -> bytes
            }
            return String(decoded, charset)
        }

        private fun absolutize(raw: String, base: URI): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            val lower = value.lowercase()
            if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("mailto:") ||
                lower.startsWith("tel:") || lower.startsWith("blob:") || lower.startsWith("about:") ||
                lower.startsWith("#") || lower.startsWith("sms:")
            ) return null
            val resolved = runCatching { base.resolve(percentEncodeIllegal(value)) }.getOrNull() ?: return null
            return resolved.takeIf { it.scheme?.lowercase() in setOf("http", "https") }?.toString()
        }

        /** 对 URL 中的非法字符做百分号编码（中文、空格、模板花括号等），否则 URI 解析会直接失败。 */
        private fun percentEncodeIllegal(value: String): String {
            val builder = StringBuilder(value.length)
            for (ch in value) {
                when {
                    ch == ' ' -> builder.append("%20")
                    ch.code > 127 || ch in "\"<>{}|\\^`" -> builder.append(URLEncoder.encode(ch.toString(), Charsets.UTF_8))
                    else -> builder.append(ch)
                }
            }
            return builder.toString()
        }

        fun pageUrl(sourceId: String, token: String, target: String): String =
            "${proxyPrefix(sourceId)}/page?t=${enc(token)}&u=${enc(target)}"

        fun resourceUrl(sourceId: String, token: String, target: String): String =
            "${proxyPrefix(sourceId)}/res?t=${enc(token)}&u=${enc(target)}"

        fun submitUrl(sourceId: String, token: String, target: String): String =
            "${proxyPrefix(sourceId)}/submit?t=${enc(token)}&u=${enc(target)}"

        private fun proxyPrefix(sourceId: String): String = "/api/sources/${enc(sourceId)}/browser"

        private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        /** 重写 HTML，使全部导航与子资源都指回代理路径。 */
        fun rewriteHtml(html: String, base: URI, sourceId: String, token: String): String {
            val document: Document = Jsoup.parse(html, base.toString())
            document.select("base").remove()
            document.select("meta[http-equiv]").forEach { meta ->
                val equiv = meta.attr("http-equiv").lowercase()
                if (equiv == "content-security-policy") {
                    meta.remove()
                } else if (equiv == "refresh") {
                    val content = meta.attr("content")
                    val match = Regex("""url\s*=\s*['"]?([^'";]+)""", RegexOption.IGNORE_CASE).find(content)
                    val target = match?.groupValues?.get(1)?.let { absolutize(it, base) }
                    if (target != null) {
                        meta.attr("content", content.replace(match.value, "url=${pageUrl(sourceId, token, target)}"))
                    }
                }
            }

            // 导航类
            listOf("a[href]", "area[href]").forEach { selector ->
                document.select(selector).forEach { element ->
                    rewriteAttr(element, "href", base) { pageUrl(sourceId, token, it) }
                    element.removeAttr("target")
                }
            }
            listOf("iframe[src]", "frame[src]").forEach { selector ->
                document.select(selector).forEach { element ->
                    rewriteAttr(element, "src", base) { pageUrl(sourceId, token, it) }
                    element.removeAttr("sandbox")
                    element.removeAttr("srcdoc")
                }
            }

            // 表单
            document.select("form").forEach { form ->
                form.removeAttr("target")
                val action = form.attr("action").takeIf { it.isNotBlank() } ?: base.toString()
                val target = absolutize(action, base)
                if (target != null) form.attr("action", submitUrl(sourceId, token, target))
            }

            // 子资源
            val resourceSelectors = listOf(
                "img[src]", "script[src]", "source[src]", "video[src]", "audio[src]",
                "embed[src]", "track[src]", "input[src]", "link[href]", "object[data]",
            )
            resourceSelectors.forEach { selector ->
                document.select(selector).forEach { element ->
                    val attr = if (element.hasAttr("href")) "href" else if (element.hasAttr("data")) "data" else "src"
                    rewriteAttr(element, attr, base) { resourceUrl(sourceId, token, it) }
                }
            }
            document.select("[srcset]").forEach { element ->
                element.attr("srcset", rewriteSrcset(element.attr("srcset"), base, sourceId, token))
            }

            // 去掉子资源完整性校验（代理后会重排内容），否则浏览器会拒绝执行
            document.select("[integrity]").forEach { it.removeAttr("integrity") }
            document.select("[crossorigin]").forEach { it.removeAttr("crossorigin") }

            // 内联样式与 <style> 中的 url()
            document.select("[style]").forEach { element ->
                element.attr("style", rewriteCss(element.attr("style"), base, sourceId, token))
            }
            document.select("style").forEach { element ->
                val original = element.data().ifBlank { element.html() }
                replaceRawContent(element, rewriteCss(original, base, sourceId, token))
            }

            injectBootstrap(document, base, sourceId, token)
            return document.outerHtml()
        }

        private fun rewriteAttr(element: Element, attr: String, base: URI, map: (String) -> String) {
            val raw = element.attr(attr)
            if (raw.isBlank()) return
            val absolute = absolutize(raw, base) ?: return
            element.attr(attr, map(absolute))
        }

        private fun rewriteSrcset(value: String, base: URI, sourceId: String, token: String): String =
            value.split(',').mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val separator = trimmed.indexOfFirst { it == ' ' || it == '\t' }
                if (separator < 0) {
                    absolutize(trimmed, base)?.let { resourceUrl(sourceId, token, it) } ?: trimmed
                } else {
                    val url = trimmed.substring(0, separator)
                    val descriptor = trimmed.substring(separator)
                    (absolutize(url, base)?.let { resourceUrl(sourceId, token, it) } ?: url) + descriptor
                }
            }.joinToString(", ")

        fun rewriteCss(css: String, base: URI, sourceId: String, token: String): String {
            if (css.isBlank()) return css
            val withUrls = CSS_URL_PATTERN.replace(css) { match ->
                val raw = match.groupValues[2]
                val absolute = absolutize(raw, base)
                if (absolute == null) match.value else "url(\"${resourceUrl(sourceId, token, absolute)}\")"
            }
            return CSS_IMPORT_PATTERN.replace(withUrls) { match ->
                val raw = match.groupValues[2]
                val absolute = absolutize(raw, base)
                if (absolute == null) match.value else "@import \"${resourceUrl(sourceId, token, absolute)}\""
            }
        }

        /**
         * 注入引导脚本：
         * 1. 屏蔽 frame-busting（把 top/parent/frameElement 指向自身）；
         * 2. 把页面内 JS 发起的 fetch / XHR / window.open 也改写到代理路径上。
         */
        private fun injectBootstrap(document: Document, base: URI, sourceId: String, token: String) {
            // Jsoup 的 head() 在缺失时会自动补建，因此必定非空
            val host = document.head()
            val script = document.createElement("script")
            script.attr("data-legado-proxy", "bootstrap")
            replaceRawContent(
                script,
                """
                (function(){
                  try{Object.defineProperty(window,'top',{get:function(){return window},configurable:true})}catch(e){}
                  try{Object.defineProperty(window,'parent',{get:function(){return window},configurable:true})}catch(e){}
                  try{Object.defineProperty(window,'frameElement',{get:function(){return null},configurable:true})}catch(e){}
                  var BASE=${jsString(base.toString())};
                  var PREFIX=${jsString(proxyPrefix(sourceId))};
                  var TOKEN=${jsString(token)};
                  function abs(u){try{return new URL(String(u),BASE).href}catch(e){return null}}
                  function build(kind,u){var a=abs(u);if(!a||!/^https?:/i.test(a))return u;return PREFIX+'/'+kind+'?t='+encodeURIComponent(TOKEN)+'&u='+encodeURIComponent(a)}
                  var toRes=function(u){return build('res',u)}, toPage=function(u){return build('page',u)};
                  window.__legadoProxy={toRes:toRes,toPage:toPage};
                  try{
                    if(window.parent&&window.parent!==window){
                      window.parent.postMessage({source:'legado-webview',type:'navigated',url:BASE},'*')
                    }
                  }catch(e){}
                  var nativeFetch=window.fetch;
                  if(nativeFetch){
                    window.fetch=function(input,init){
                      try{
                        if(typeof input==='string'){ input=toRes(input) }
                        else if(input&&input.url){ input=new Request(toRes(input.url),input) }
                      }catch(e){}
                      return nativeFetch.call(this,input,init)
                    }
                  }
                  var nativeOpen=XMLHttpRequest.prototype.open;
                  XMLHttpRequest.prototype.open=function(method,url){
                    try{ arguments[1]=toRes(url) }catch(e){}
                    return nativeOpen.apply(this,arguments)
                  };
                  window.open=function(url){ if(url){ window.location.href=toPage(url) } return window };
                })();
                """.trimIndent(),
            )
            host.prependChild(script)
        }

        private fun jsString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        /**
         * 用 DataNode 替换 style / script 的原始内容。
         *
         * 这两个标签的内容是 raw text，浏览器不会解码其中的 HTML 实体；
         * 若走 html() 写入，Jsoup 会把代理 URL 里的 `&` 转义成 `&amp;`，导致地址全部失效。
         */
        private fun replaceRawContent(element: Element, raw: String) {
            element.empty()
            element.appendChild(DataNode(raw))
        }
    }
}
