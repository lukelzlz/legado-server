package io.legado.server

import org.jsoup.Jsoup
import java.net.URI

/**
 * 「小说正文插图」的 `<img>` 标签**唯一口径**（写入侧 `RuleRunner.cleanContent` 与读取侧
 * `IllustrationCache` 共用同一套判据）。
 *
 * ## 为什么必须收口成一处
 * 正文里的插图要经过三次角色转换：上游 HTML →（清洗）→ 缓存正文 →（前端重写）→ 代理地址。
 * 只要「什么算安全插图」在清洗与登记两处各写一份，就会出现
 * **清洗留下了 A、登记时按另一套规则漏掉 B** 的静默错位：前端渲染出图、服务端没有登记行，
 * 于是离线永远缺图、序号还可能和正文顺序不一致。因此这里只做两件事：
 * 1. **写入**：把上游 `<img>` 重建为安全标签（只保留 `src`/`alt`，绝不透传上游属性）；
 * 2. **读取**：按出现顺序取出插图地址，供 `IllustrationCache` 定死章节内序号。
 *
 * ## 安全判据（为什么只认 http(s)）
 * - `javascript:` / `vbscript:` ⇒ 直接是 XSS 载荷；
 * - `data:` ⇒ 会把整张图（甚至伪装成图片的 HTML/SVG）塞进正文文本，正文缓存与
 *   JSON 接口都会被它撑爆，而且**客户端无法核对字节**（这正是我们要在服务端统一
 *   校验魔数、压缩、体积的反面）；
 * - `file:` / `blob:` / 协议相对 `//` 之外的自定义协议 ⇒ 服务端取不到字节，
 *   浏览器侧又会绕过代理直连（缺 Referer/Cookie 必裂图 + 泄露用户 IP）。
 * 所以：**只保留可被服务端代理的绝对 http(s) 地址**；站内相对地址按章节地址补全
 * （补不出来就丢弃，绝不瞎猜一个主机）。
 */
object IllustrationMarkup {

    /** 正文里可能出现的 `<img …>` 开标签（HTML 语义里没有 `</img>`，不必处理闭标签）。 */
    val IMG_TAG = Regex("(?i)<img\\b[^>]*>")

    /**
     * 清洗期占位行标记。
     *
     * 文本清洗会剥掉一切标签，所以插图必须**先被"摘走"再放回**：否则 `<img>` 内的属性
     * 会被当成正文（`onerror=…` 变成可见文本），或者 `<div>` 结构性删除把它连根删掉。
     * 用一个不可能出现在正常正文里的 ASCII 标记独占一行，最后按行换回真实标签。
     */
    private const val SLOT_PREFIX = "@@LEGADO-ILLUSTRATION-"
    private const val SLOT_SUFFIX = "@@"

    /** 第 [index] 张插图的占位行内容（两侧带换行由调用方负责）。 */
    fun slotMarker(index: Int): String = "$SLOT_PREFIX$index$SLOT_SUFFIX"

    /** 占位行 → 插图下标；不是占位行返回 null。 */
    fun slotIndex(line: String): Int? {
        val trimmed = line.trim()
        if (trimmed.length <= SLOT_PREFIX.length + SLOT_SUFFIX.length) return null
        if (!trimmed.startsWith(SLOT_PREFIX) || !trimmed.endsWith(SLOT_SUFFIX)) return null
        return trimmed.substring(SLOT_PREFIX.length, trimmed.length - SLOT_SUFFIX.length).toIntOrNull()
    }

    /**
     * 把一个上游 `<img>` 标签重建为**安全标签**；不安全返回 null（调用方直接丢弃整个标签）。
     *
     * 注意这里是**重建**而不是"过滤属性"：`src`/`alt` 由我们自己转义后拼装 ⇒
     * 上游的 `onerror`、`onload`、`style`、`srcset` 一个都不可能漏进返回给前端的 HTML。
     *
     * @param baseUrl 章节页地址（相对地址的补全基准）；非 http(s)（如聚合源的 `data:` 载荷）时传 null
     */
    fun safeTag(tag: String, baseUrl: String?): String? {
        val element = runCatching { Jsoup.parseBodyFragment(tag).selectFirst("img") }.getOrNull() ?: return null
        // 懒加载兼容：与 RuleRunner.splitImageUrls 同一口径（data-src > data-original > src）
        val candidate = element.attr("data-src").trim()
            .ifBlank { element.attr("data-original").trim() }
            .ifBlank { element.attr("src").trim() }
        val url = safeHttpUrl(candidate, baseUrl) ?: return null
        val alt = element.attr("alt").trim()
        val altAttribute = if (alt.isEmpty()) "" else " alt=\"${escapeAttribute(alt)}\""
        return "<img src=\"${escapeAttribute(url)}\"$altAttribute>"
    }

    /**
     * 规范化并校验一个插图地址：只接受绝对 http(s)。
     *
     * @return 可交给服务端代理抓取的地址；不合法返回 null
     */
    fun safeHttpUrl(candidate: String, baseUrl: String?): String? {
        val raw = candidate.trim().trim('"', '\'').trim()
        if (raw.isEmpty()) return null
        // 明确禁止的伪协议（大小写变体一并挡；`java\nscript:` 这类夹空白的写法直接不匹配 http(s) 前缀，
        // 会落进下面的"相对地址补全"分支，补出来仍是 data:/javascript: 时被 isHttpUrl 再次挡掉）
        val lowered = raw.lowercase().filterNot { it.isWhitespace() || it.isISOControl() }
        if (FORBIDDEN_SCHEMES.any { lowered.startsWith(it) }) return null
        if (isHttpUrl(raw)) return raw
        val base = baseUrl?.trim()?.takeIf { isHttpUrl(it) }
        if (base != null) {
            val resolved = runCatching {
                URI(encodeIllegalUrlChars(base)).resolve(encodeIllegalUrlChars(raw)).toString()
            }.getOrNull()
            if (resolved != null && isHttpUrl(resolved)) return resolved
        }
        // 协议相对地址：没有基准时按 https 补（绝不补成明文 http，也不猜其它协议）
        if (raw.startsWith("//")) {
            val https = "https:$raw"
            if (isHttpUrl(https)) return https
        }
        return null
    }

    /** 正文里**按出现顺序**的插图地址（读取侧登记序号用；只认 http(s)，与写入侧同源）。 */
    fun urlsIn(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        return IMG_TAG.findAll(content).mapNotNull { match ->
            val element = runCatching { Jsoup.parseBodyFragment(match.value).selectFirst("img") }.getOrNull()
            val raw = element?.attr("src")?.trim().orEmpty()
            // 正文缓存里的标签是我们重建过的（属性只有 src/alt），这里不再做补全：拿不到绝对地址就跳过
            raw.takeIf { isHttpUrl(it) }
        }.toList()
    }

    /** 只做「协议 + 主机非空」的形态判定；真正的 SSRF/解析校验在抓取收口里做（不重复造轮子）。 */
    fun isHttpUrl(value: String): Boolean {
        val trimmed = value.trim()
        val scheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> 7
            trimmed.startsWith("https://", ignoreCase = true) -> 8
            else -> return false
        }
        val host = trimmed.substring(scheme).substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotBlank() && host.any { it == '.' || it == ':' || it.isLetterOrDigit() }
    }

    /** HTML 属性值转义（正文缓存是纯文本字段，属性里的 `&`/`"` 必须自己转，绝不依赖下游）。 */
    fun escapeAttribute(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    /** `escapeAttribute` 的逆运算（前端重写代理地址时必须还原，否则 `?a=1&amp;b=2` 会被当成一个参数）。 */
    fun unescapeAttribute(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private val FORBIDDEN_SCHEMES = listOf("javascript:", "data:", "vbscript:", "blob:", "file:", "about:", "chrome:")
}
