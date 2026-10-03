package io.legado.server

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/**
 * RSS 订阅源规则求值（对齐 Legado 手机版 `model/rss/RssParserByRule.kt` 与 `RssParserDefault.kt`）。
 *
 * ## 为什么单独一个文件、却复用 [RuleRunner]
 *
 * 订阅源的规则形态与**书源同源**：`<js>…</js>$.data[*]` 链式、`路径@js:代码` 后置处理、
 * `$.a.b` JsonPath、`##正则##替换`、CSS 选择器。这些书源侧都已踩平，因此这里只做「按手机版
 * 顺序把它们串起来」，**不复制任何求值实现**（见 [RuleRunner.evalNodeList] / [RuleRunner.evalNodeValue]）。
 *
 * ## 与手机版逐条对齐的语义（每一条都影响结果，且错了不会报错、只会「少几条」）
 *
 * | 语义 | 手机版依据 | 本实现 |
 * | :--- | :--- | :--- |
 * | JS 里的 `baseUrl` = **当前列表地址**（分类的 `sortUrl`），不是 `sourceUrl` | `RssParserByRule.kt:45-47` | [parse] 把 `url` 作为 `baseUrl` 传入 |
 * | `ruleArticles` 以 `-` 开头 ⇒ 求值后**整表反转** | `:50-53` | [parse] 处理 |
 * | `ruleLink` 的 base 是 **`sourceUrl`** | `:125` | 用 `sourceUrl` 绝对化 link |
 * | `ruleImage` 会**绝对化**，base 是列表地址 | `:122` | 用列表地址绝对化 image |
 * | `title` 为空 ⇒ **整条丢弃** | `:127-129` | `mapNotNull` 丢弃 |
 * | 无规则 ⇒ 走**默认 RSS/XML 解析** | `:40-42` | [parseDefault] |
 *
 * ⚠️ `baseUrl` 那条尤其反直觉：真实源「大灰狼书荒广场」的 `ruleArticles` 里写着
 * `if (baseUrl.includes('番茄'))`，而它的 `sortUrl` 是 `番茄::番茄` —— 只有把**列表地址**
 * 当 `baseUrl` 才会命中「番茄」分支。传成 `sourceUrl` 会静默走 else 分支（伪造一条「进入官网」）。
 */
object RssRuleParser {

    /** 一次解析的结果。 */
    data class Result(
        val articles: List<RssArticle>,
        /** `ruleNextPage` 求出的下一页地址（无则为 null）。 */
        val nextPageUrl: String? = null,
        /** 走的是默认 XML 解析而不是规则（用于如实回报「此源无文章规则」）。 */
        val fromDefaultXml: Boolean = false,
    )

    /**
     * 解析订阅源的文章列表。
     *
     * @param sourceJson 订阅源 JSON（`RssSource` 的序列化形态；也兼容备份包里的原始对象）。
     * @param url        本次要抓的**列表地址**（分类页 / `sortUrl`）。同时充当 JS 的 `baseUrl`。
     * @param page       页码，替换规则里的 `{{page}}`。
     * @param runner     复用书源的规则求值器（含 Rhino 沙箱与 `java.*` 桥）。
     */
    fun parse(sourceJson: String, url: String, page: Int = 1, runner: RuleRunner): Result {
        val json = RssSourceCodec.decode(sourceJson)
        val sourceUrl = json.sourceUrl
        val rawListUrl = url.takeIf { it.isNotBlank() } ?: json.defaultListUrl()
        // 与书源一致：整个求值过程都处在「该源」的上下文里，<js> 才能拿到 jsLib / source / cookie / cache
        val execContext = JsExecutionContext(
            sourceId = sourceUrl,
            sourceName = json.sourceName,
            sourceComment = json.sourceComment,
            database = runner.database,
            jsLib = json.jsLib?.takeIf { it.isNotBlank() },
        )
        return runner.jsSandbox.withSourceContext(execContext) {
            val listUrl = rawListUrl.replace("{{page}}", page.toString())
            val body = runner.evalFetchRssList(listUrl, json.header, sourceUrl, runner.database)

            val listRule = json.ruleArticles?.trim().orEmpty()
            // 无列表规则 ⇒ 默认 RSS/XML 解析（手机版 RssParserDefault）。
            // 真实备份里 8 条有 7 条是这种「只打开网页」的源，它们其实没有文章可抓，
            // 因此这里如果 XML 也解析不出条目，就如实返回空表而**不是**伪造数据。
            if (listRule.isBlank()) {
                return@withSourceContext Result(parseDefaultXml(body, sourceUrl), null, fromDefaultXml = true)
            }

            val reversed = listRule.startsWith("-")
            val effectiveRule = if (reversed) listRule.substring(1).trimStart() else listRule
            val nodes = runner.evalNodeList(body, effectiveRule, listUrl)
            val parsed = nodes.mapNotNull { node ->
                // 手机版：title 为空 ⇒ 丢弃整条（宁可少一条，也不要一条没有标题的空行）
                val title = runner.evalNodeValue(node, json.ruleTitle, body, listUrl)?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                RssArticle(
                    sourceUrl = sourceUrl,
                    link = absolute(sourceUrl, runner.evalNodeValue(node, json.ruleLink, body, listUrl).orEmpty()),
                    title = title,
                    image = runner.evalNodeValue(node, json.ruleImage, body, listUrl)?.let { absolute(listUrl, it) },
                    pubDate = runner.evalNodeValue(node, json.rulePubDate, body, listUrl),
                    origin = sourceUrl,
                )
            }
            val ordered = if (reversed) parsed.reversed() else parsed
            Result(ordered, nextPageUrl = null)
        }
    }

    // ------------------------------------------------------------------ 默认 RSS/XML 解析

    /**
     * 默认解析（源**没有配 `ruleArticles`** 时）。
     *
     * 支持生态里最常见的三种容器：
     * - RSS 2.0：`rss > channel > item`
     * - Atom：`feed > entry`
     * - RDF / RSS 1.0：`rdf:RDF > item`
     *
     * 用 Jsoup 的 **XML 解析器**而不是 `DocumentBuilderFactory`：Jsoup 在本项目已是既有依赖，
     * 其解析器**不解析外部实体**，从根上规避 XXE（默认 XML 解析面对的正是外部不可信内容）。
     * 命名空间前缀不写死：用 `getElementsByTag` 的宽松匹配兜住 `dc:date` / `content:encoded` 等。
     */
    private fun parseDefaultXml(body: String, sourceUrl: String): List<RssArticle> {
        val document = runCatching { Jsoup.parse(body, "", Parser.xmlParser()) }.getOrNull() ?: return emptyList()
        val items = document.select("item").ifEmpty { document.select("entry") }
        return items.mapNotNull { item ->
            val title = text(item, "title").orEmpty().trim()
            val rawLink = link(item)?.takeIf { it.isNotBlank() }
            // 没有链接的条目没法点开，丢弃（手机版同样要求 link 可用）
            if (title.isBlank() || rawLink == null) return@mapNotNull null
            RssArticle(
                sourceUrl = sourceUrl,
                link = absolute(sourceUrl, rawLink),
                title = title,
                image = firstNonBlank(
                    // 常见封面字段：RSS 的 enclosure / media:content / itunes:image、Atom 的 link[rel=enclosure]
                    item.selectFirst("enclosure[url]")?.attr("url"),
                    item.selectFirst("content[url]")?.attr("url"),
                    item.selectFirst("image")?.attr("href"),
                    item.selectFirst("image")?.attr("url"),
                    item.selectFirst("thumbnail")?.attr("url"),
                )?.let { absolute(sourceUrl, it) },
                pubDate = firstNonBlank(
                    text(item, "pubDate"),
                    text(item, "date"),
                    text(item, "published"),
                    text(item, "updated"),
                ),
                description = firstNonBlank(
                    text(item, "description"),
                    text(item, "summary"),
                    text(item, "encoded"),
                ),
                origin = sourceUrl,
            )
        }
    }

    /** 按标签名取文本，带命名空间前缀的（`dc:date`）也能命中。 */
    private fun text(item: Element, tag: String): String? =
        item.selectFirst(tag)?.text()?.takeIf { it.isNotBlank() }
            ?: item.getElementsByTag(tag).firstOrNull()?.text()?.takeIf { it.isNotBlank() }

    /** Atom 的 `<link href=…>` 与 RSS 的 `<link>文本</link>` 两种形态都要认。 */
    private fun link(item: Element): String? {
        item.selectFirst("link[href]")?.attr("href")?.takeIf { it.isNotBlank() }?.let { return it }
        item.selectFirst("link")?.text()?.takeIf { it.isNotBlank() }?.let { return it }
        item.selectFirst("guid")?.text()?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    /**
     * 相对地址绝对化（与书源 `String.absolute()` 同语义）。
     *
     * `base` 上的 `##`/`#` 先剥掉（Legado 规则串里 `@css:`、`##` 可能混进 base），
     * 且只有 `http(s)://` 的 base 才做 resolve，否则原样返回 —— 真实数据里
     * `sourceUrl` 可能是 `snssdk1128://…` 或中文自定义串。
     */
    private fun absolute(base: String, value: String): String {
        val link = value.trim()
        if (link.isEmpty()) return link
        if (link.startsWith("http://", true) || link.startsWith("https://", true)) return link
        val cleanBase = base.substringBefore("##").substringBefore("#").trim()
        if (!cleanBase.startsWith("http://", true) && !cleanBase.startsWith("https://", true)) return link
        return runCatching { URI(cleanBase).resolve(link).toString() }.getOrDefault(link)
    }
}
