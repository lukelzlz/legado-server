package io.legado.server

import com.jayway.jsonpath.JsonPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * 一本书在**规则执行期**的上下文 —— 即开源阅读里 JS 能看到的那个 `book` 对象。
 *
 * 为什么必须显式传：`sourceContext()` 原本只装书源信息（sourceId/jsLib），于是书源 JS 里
 * `book.name` / `book.author` / `book.bookUrl` / `book.getVariable('custom')` 全是**空值**。
 * 实测代价最大的一处就是听书音色：`🍅大灰狼聚合` 源用 `book.getVariable('custom')` 取音色编号，
 * 空值 ⇒ 永远只能用默认音色，而用户在书籍变量里填什么都没用。
 *
 * [variables] 刻意是**可变**的：规则里可能 `book.putVariable(...)`（Legado 里这会随
 * `saveBook` 落库），调用方在规则跑完后比对并写回。
 */
data class BookContext(
    val sourceId: String,
    val bookUrl: String,
    val name: String? = null,
    val author: String? = null,
    val tocUrl: String? = null,
    /** `novel` / `comic` / `audio` / `local`（[BookKind] 的 wire 值）。 */
    val kind: String? = null,
    /** 书籍变量表（Legado `Book.variableMap`）。 */
    val variables: MutableMap<String, String> = mutableMapOf(),
) {
    /** Legado 的 `Book.type`：0=文本 1=音频 2=图像（书源里 `book.type` 会按它分派）。 */
    val numericType: Int
        get() = when (kind?.lowercase()) {
            "audio" -> 1
            "comic" -> 2
            else -> 0
        }
}

/**
 * 从库里装配一本书的规则执行上下文（含书籍变量）。
 *
 * 书架里没有这本书（例如从搜索页直接进详情）时**依然返回上下文**：书名/作者取不到，
 * 但变量表可以（`getBookVariables` 会返回空表），这样行为与 Legado 一致而不会抛错。
 */
internal fun Database.bookContext(sourceId: String, bookUrl: String): BookContext {
    val shelf = getShelfBookByUrl(bookUrl)?.takeIf { it.sourceId == sourceId }
    return BookContext(
        sourceId = sourceId,
        bookUrl = bookUrl,
        name = shelf?.name,
        author = shelf?.author,
        tocUrl = shelf?.tocUrl,
        kind = shelf?.bookKind,
        variables = getBookVariables(sourceId, bookUrl).toMutableMap(),
    )
}

/**
 * 把规则执行期间对书籍变量的修改落库（Legado 里 `book.putVariable(...)` 会随 `saveBook` 落库）。
 *
 * **只在真的变了才写**：正文/目录每次都要跑规则，无脑写库会让高频路径多出无谓的写事务。
 */
internal fun Database.persistBookVariables(book: BookContext) {
    if (getBookVariables(book.sourceId, book.bookUrl) != book.variables) {
        saveBookVariables(book.sourceId, book.bookUrl, book.variables)
    }
}

/**
 * A deliberately small, server-safe subset of Legado's declarative source protocol.
 * It keeps execution away from Android APIs and rejects private-network targets.
 */
class RuleRunner(private val responseFetcher: ((String) -> String)? = null, internal var database: Database? = null) {
    constructor(database: Database) : this(null, database)
    constructor(responseFetcher: (String) -> String) : this(responseFetcher, null)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }
    internal val jsSandbox = JsSandbox(this)

    fun search(sourceJson: String, keyword: String): List<SearchResult> {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).search(keyword) }
        val rawSearchUrl = source.string("searchUrl") ?: throw RuleExecutionException("该书源未配置 searchUrl")
        val sourceUrl = source.string("bookSourceUrl") ?: throw RuleExecutionException("书源缺少 bookSourceUrl")
        // 整个搜索流程都在书源上下文内执行：规则里的 <js> 依赖 source.getVariable() 与 jsLib 工具函数
        val execContext = sourceContext(source, sourceUrl)
        return jsSandbox.withSourceContext(execContext) {
            val searchUrl = if (rawSearchUrl.trimStart().startsWith("@js:") || rawSearchUrl.trimStart().startsWith("js:") || rawSearchUrl.contains("<js>")) {
                val jsCode = if (rawSearchUrl.contains("<js>")) rawSearchUrl.substringAfter("<js>").substringBefore("</js>") else rawSearchUrl.removePrefix("@js:").removePrefix("js:")
                jsSandbox.eval(jsCode, mapOf("key" to keyword, "keyword" to keyword, "page" to 1, "baseUrl" to sourceUrl, "sourceId" to sourceUrl), execContext)
                    ?: throw RuleExecutionException("searchUrl JS 计算未返回有效地址：${execContext.lastError ?: "脚本无返回值"}")
            } else {
                rawSearchUrl
            }
            val (urlTemplate, options) = splitUrlOptions(searchUrl)
            val mergedOptions = mergeOptions(parseSourceHeaders(source, sourceUrl), options)
            val rendered = renderUrl(urlTemplate, keyword, sourceUrl).absolute(sourceUrl)
            val body = fetchUrl(rendered, mergedOptions, keyword, sourceUrl, database)
            val rule = source.objectValue("ruleSearch") ?: throw RuleExecutionException("该书源未配置 ruleSearch")
            val items = nodes(body, rule.string("bookList") ?: throw RuleExecutionException("缺少 ruleSearch.bookList"))
            items.mapNotNull { item ->
                val url = item.value(rule.string("bookUrl"), jsSandbox, body, sourceUrl)?.absolute(sourceUrl) ?: return@mapNotNull null
                SearchResult(
                    sourceId = sourceUrl,
                    name = item.value(rule.string("name"), jsSandbox, body, sourceUrl) ?: return@mapNotNull null,
                    author = item.value(rule.string("author"), jsSandbox, body, sourceUrl),
                    bookUrl = url,
                    coverUrl = item.value(rule.string("coverUrl"), jsSandbox, body, sourceUrl)?.absolute(sourceUrl),
                    intro = item.value(rule.string("intro"), jsSandbox, body, sourceUrl),
                )
            }
        }
    }

    /**
     * 解析书源的发现分类树结构。
     *
     * 支持三种生态形式：
     * 1. 包含 <js> 或 @js: 的沙箱脚本求值；
     * 2. JSON 数组/树形结构；
     * 3. 经典多行纯文本（以 :: 或 && 分隔标题与 URL，支持层级缩进或平铺）。
     */
    fun exploreCategories(sourceJson: String): List<ExploreCategory> {
        val source = sourceJson.objectValue()
        val rawExploreUrl = source.string("exploreUrl")?.trim() ?: return emptyList()
        if (rawExploreUrl.isBlank()) return emptyList()
        val sourceUrl = source.string("bookSourceUrl") ?: ""
        val execContext = sourceContext(source, sourceUrl)

        val evaluated = jsSandbox.withSourceContext(execContext) {
            if (rawExploreUrl.trimStart().startsWith("@js:") || rawExploreUrl.trimStart().startsWith("js:") || rawExploreUrl.contains("<js>")) {
                val jsCode = if (rawExploreUrl.contains("<js>")) {
                    rawExploreUrl.substringAfter("<js>").substringBefore("</js>")
                } else {
                    rawExploreUrl.trimStart().removePrefix("@js:").removePrefix("js:")
                }
                jsSandbox.eval(jsCode, mapOf("baseUrl" to sourceUrl, "sourceId" to sourceUrl), execContext) ?: ""
            } else {
                rawExploreUrl
            }
        }.trim()

        if (evaluated.isBlank()) return emptyList()
        return parseExploreCategoriesText(evaluated, sourceUrl)
    }

    private fun parseExploreCategoriesText(text: String, baseUrl: String): List<ExploreCategory> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val jsonCategories = runCatching {
                val element = json.parseToJsonElement(trimmed)
                if (element is JsonArray) {
                    parseJsonCategories(element, baseUrl)
                } else null
            }.getOrNull()
            if (!jsonCategories.isNullOrEmpty()) {
                return jsonCategories
            }
        }
        return parseLineBasedCategories(text, baseUrl)
    }

    private fun parseJsonCategories(array: JsonArray, baseUrl: String): List<ExploreCategory> {
        return array.mapNotNull { elem ->
            if (elem !is JsonObject) return@mapNotNull null
            val title = elem.string("title") ?: elem.string("name") ?: return@mapNotNull null
            val urlElem = elem["url"]
            if (urlElem is JsonArray) {
                val sub = parseJsonCategories(urlElem, baseUrl)
                ExploreCategory(title = title, url = null, subCategories = sub)
            } else {
                val rawUrl = (urlElem as? JsonPrimitive)?.contentOrNull
                val subArray = (elem["subCategories"] ?: elem["children"] ?: elem["list"] ?: elem["items"]) as? JsonArray
                val sub = if (subArray != null) parseJsonCategories(subArray, baseUrl) else emptyList()
                ExploreCategory(
                    title = title,
                    url = rawUrl?.takeIf { it.isNotBlank() },
                    subCategories = sub,
                )
            }
        }
    }

    private fun parseLineBasedCategories(text: String, baseUrl: String): List<ExploreCategory> {
        val lines = text.lines()
        val result = mutableListOf<ExploreCategory>()
        var currentGroup: String? = null
        val currentGroupItems = mutableListOf<ExploreCategory>()

        fun flushGroup() {
            if (currentGroup != null) {
                if (currentGroupItems.isNotEmpty()) {
                    result.add(ExploreCategory(title = currentGroup!!, url = null, subCategories = currentGroupItems.toList()))
                    currentGroupItems.clear()
                } else {
                    result.add(ExploreCategory(title = currentGroup!!, url = null))
                }
                currentGroup = null
            }
        }

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isBlank()) continue

            val separator = when {
                line.contains("::") -> "::"
                line.contains("&&") -> "&&"
                else -> null
            }

            if (separator != null) {
                val parts = line.split(separator)
                val title = parts[0].trim()
                val url = if (parts.size > 1) {
                    val rawUrlPart = parts[1].trim()
                    rawUrlPart.takeIf { it.isNotBlank() }
                } else null

                val hasIndent = line.startsWith(" ") || line.startsWith("\t") || line.startsWith("　")
                val item = ExploreCategory(title = title, url = url)

                if (hasIndent && currentGroup != null) {
                    currentGroupItems.add(item)
                } else if (currentGroup != null) {
                    currentGroupItems.add(item)
                } else {
                    result.add(item)
                }
            } else {
                flushGroup()
                currentGroup = trimmedLine
            }
        }
        flushGroup()
        return result
    }

    /**
     * 根据分类 URL 获取发现书籍列表。
     *
     * 遵循 Legado 生态规范：优先使用 ruleExplore，若为空自动回退至 ruleSearch。
     */
    fun exploreBooks(sourceJson: String, exploreUrl: String, page: Int = 1): List<SearchResult> {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl") ?: throw RuleExecutionException("书源缺少 bookSourceUrl")
        val execContext = sourceContext(source, sourceUrl)

        return jsSandbox.withSourceContext(execContext) {
            val evaluatedUrl = if (exploreUrl.trimStart().startsWith("@js:") || exploreUrl.trimStart().startsWith("js:") || exploreUrl.contains("<js>")) {
                val jsCode = if (exploreUrl.contains("<js>")) {
                    exploreUrl.substringAfter("<js>").substringBefore("</js>")
                } else {
                    exploreUrl.trimStart().removePrefix("@js:").removePrefix("js:")
                }
                jsSandbox.eval(jsCode, mapOf("page" to page, "baseUrl" to sourceUrl, "sourceId" to sourceUrl), execContext)
                    ?: throw RuleExecutionException("exploreUrl JS 计算未返回有效地址：${execContext.lastError ?: "脚本无返回值"}")
            } else {
                exploreUrl
            }

            val pStr = page.coerceAtLeast(1).toString()
            val renderedUrl = evaluatedUrl
                .replace("{{page}}", pStr)
                .replace("{{page+1}}", (page + 1).toString())
                .replace("{{page-1}}", (page - 1).coerceAtLeast(1).toString())

            val (urlTemplate, options) = splitUrlOptions(renderedUrl)
            val mergedOptions = mergeOptions(parseSourceHeaders(source, sourceUrl), options)
            val rendered = urlTemplate.absolute(sourceUrl)
            val body = fetchUrl(rendered, mergedOptions, null, sourceUrl, database)

            val ruleExplore = source.objectValue("ruleExplore")
            val ruleSearch = source.objectValue("ruleSearch")
            val rule = if (ruleExplore != null && !ruleExplore.string("bookList").isNullOrBlank()) {
                ruleExplore
            } else if (ruleSearch != null && !ruleSearch.string("bookList").isNullOrBlank()) {
                ruleSearch
            } else {
                throw RuleExecutionException("书源未配置 ruleExplore 且未配置有效 ruleSearch")
            }

            val listRule = rule.string("bookList") ?: throw RuleExecutionException("缺少 bookList 规则")
            val items = nodes(body, listRule)
            items.mapNotNull { item ->
                val url = item.value(rule.string("bookUrl"), jsSandbox, body, sourceUrl)?.absolute(sourceUrl) ?: return@mapNotNull null
                val name = item.value(rule.string("name"), jsSandbox, body, sourceUrl) ?: return@mapNotNull null
                SearchResult(
                    sourceId = sourceUrl,
                    name = name,
                    author = item.value(rule.string("author"), jsSandbox, body, sourceUrl),
                    bookUrl = url,
                    coverUrl = item.value(rule.string("coverUrl"), jsSandbox, body, sourceUrl)?.absolute(sourceUrl),
                    intro = item.value(rule.string("intro"), jsSandbox, body, sourceUrl),
                )
            }
        }
    }

    /**
     * 构造当前书源的 JS 执行上下文（含 jsLib，规则 JS 因此可以调用书源工具函数）。
     *
     * 注意：Legado 约定里 `loginUrl` 可以不是网址，而是**整段登录用 JS**（聚合源普遍如此），
     * 其中的工具函数同样会被正文/目录规则调用，因此也要并入脚本库。
     */
    private fun sourceContext(source: JsonObject, sourceUrl: String?, book: BookContext? = null): JsExecutionContext {
        val library = buildString {
            source.string("jsLib")?.takeIf { it.isNotBlank() }?.let { append(it) }
            val loginScript = source.string("loginUrl")?.takeIf { it.isNotBlank() && !it.trim().startsWith("http") }
            if (loginScript != null) {
                if (isNotEmpty()) append("\n")
                append(loginScript)
            }
        }.takeIf { it.isNotBlank() }
        // 书籍上下文一并注入：书源 JS 里的 `book.getVariable('custom')`（听书音色）就靠它。
        // 传 null 时保持原行为（各字段为空串），搜索等"还没有具体书"的场景走这条。
        return JsExecutionContext(
            sourceId = sourceUrl,
            database = database,
            jsLib = library,
            bookUrl = book?.bookUrl,
            bookName = book?.name,
            bookAuthor = book?.author,
            bookTocUrl = book?.tocUrl,
            bookType = book?.numericType,
            bookKind = book?.kind,
            bookVariables = book?.variables ?: mutableMapOf(),
        )
    }

    fun details(sourceJson: String, bookUrl: String, book: BookContext? = null): BookDetails {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).details(bookUrl, book) }
        return jsSandbox.withSourceContext(sourceContext(source, source.string("bookSourceUrl"), book)) {
            declarativeDetails(source, bookUrl)
        }
    }

    /**
     * 解析 `data:;base64,<载荷>,<类型标记>` 形式的数据地址，返回解码后的载荷；非数据地址返回 null。
     *
     * 聚合类书源用它把 book_id/来源/分栏等参数从搜索结果一路带到详情、目录和正文。
     */
    private fun dataUrlPayload(url: String): String? {
        val value = url.trim()
        if (!value.startsWith("data:", ignoreCase = true)) return null
        val comma = value.indexOf(',')
        if (comma < 0) return null
        val meta = value.substring(5, comma)
        var payload = value.substring(comma + 1)
        // 末尾可能跟一个类型标记（如 ,{"type":"qingtian2"}），取最后一个逗号之前的部分
        val typeIndex = payload.lastIndexOf(',')
        if (typeIndex > 0 && payload.substring(typeIndex + 1).trimStart().startsWith("{")) {
            payload = payload.substring(0, typeIndex)
        }
        return if (meta.contains("base64", ignoreCase = true)) {
            runCatching { String(java.util.Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8) }
                .getOrElse { payload }
        } else {
            runCatching { java.net.URLDecoder.decode(payload, Charsets.UTF_8) }.getOrDefault(payload)
        }
    }

    private fun declarativeDetails(source: JsonObject, bookUrl: String): BookDetails {
        val sourceUrl = source.string("bookSourceUrl")
        val rule = source.objectValue("ruleBookInfo") ?: throw RuleExecutionException("该书源未配置 ruleBookInfo")
        val initRule = rule.string("init")

        // 聚合源把 bookUrl 当作参数载体（data:;base64,<载荷>,{"type":"qingtian"}），
        // 这类地址无法直接 HTTP 请求，需要先取出解码后的载荷交给规则。
        val dataPayload = dataUrlPayload(bookUrl)
        val body: String
        val baseDoc: NodeValue
        if (dataPayload != null) {
            body = dataPayload
            baseDoc = NodeValue.json(dataPayload)
        } else {
            val (url, options) = splitUrlOptions(bookUrl)
            val mergedOptions = mergeOptions(parseSourceHeaders(source, sourceUrl), options)
            body = fetchUrl(url, mergedOptions, null, sourceUrl, database)
            baseDoc = NodeValue.document(body)
        }

        // init 常写成「JS + 后置取值路径」的规则链（如 <js>…</js>$.data）：必须求值，不能当路径用。
        // 纯路径（如 $.data）仍走原有 at() 语义，避免把 JSON 对象退化成字符串。
        val root = when {
            initRule.isNullOrBlank() -> baseDoc
            initRule.contains("<js>") || initRule.trimStart().startsWith("@js:") || initRule.trimStart().startsWith("js:") ->
                runCatching { baseDoc.value(initRule, jsSandbox, body, bookUrl) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { NodeValue.document(it) } ?: baseDoc
            else -> baseDoc.at(initRule)
        }
        fun value(key: String): String? = runCatching { root.value(rule.string(key), jsSandbox, body, bookUrl) }.getOrNull()
        val name = sanitizeBookName(value("name"))
        return BookDetails(
            sourceId = source.string("bookSourceUrl")!!,
            name = name.ifBlank { "未命名书籍" },
            author = value("author"), intro = value("intro"),
            coverUrl = value("coverUrl")?.absolute(bookUrl),
            tocUrl = value("tocUrl")?.absolute(bookUrl) ?: bookUrl,
        )
    }

    fun chapters(sourceJson: String, tocUrl: String, book: BookContext? = null): List<Chapter> {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).chapters(tocUrl, book) }
        val sourceUrl = source.string("bookSourceUrl")
        return jsSandbox.withSourceContext(sourceContext(source, sourceUrl, book)) {
            val rule = source.objectValue("ruleToc") ?: throw RuleExecutionException("该书源未配置 ruleToc")
            val listRule = rule.string("chapterList") ?: throw RuleExecutionException("缺少 ruleToc.chapterList")
            val dataPayload = dataUrlPayload(tocUrl)
            val body: String
            val chapterNodes: List<NodeValue>
            if (dataPayload != null) {
                // 聚合源的目录地址同样是参数载体，由 chapterList 的 JS 自行取回目录
                body = dataPayload
                chapterNodes = jsListNodes(NodeValue.json(dataPayload), listRule, body, tocUrl)
            } else {
                val (url, options) = splitUrlOptions(tocUrl)
                val mergedOptions = mergeOptions(parseSourceHeaders(source, sourceUrl), options)
                body = fetchUrl(url, mergedOptions, null, sourceUrl, database)
                val baseDoc = NodeValue.document(body)
                chapterNodes = if (isJsRule(listRule)) jsListNodes(baseDoc, listRule, body, tocUrl) else nodes(body, listRule)
            }
            chapterNodes.mapIndexedNotNull { index, node ->
                val chUrl = node.value(rule.string("chapterUrl"), jsSandbox, body, tocUrl)?.absolute(tocUrl) ?: return@mapIndexedNotNull null
                Chapter(index, node.value(rule.string("chapterName"), jsSandbox, body, tocUrl) ?: "第 ${index + 1} 章", chUrl)
            }
        }
    }

    private fun isJsRule(rule: String): Boolean {
        val trimmed = rule.trimStart()
        return trimmed.startsWith("@js:") || trimmed.startsWith("js:") || (rule.contains("<js>") && rule.contains("</js>"))
    }

    /** `<js>` 形式的列表规则：求值得到 JSON 数组后逐项包装成可继续取值的节点。 */
    private fun jsListNodes(base: NodeValue, listRule: String, body: String, baseUrl: String): List<NodeValue> {
        val json = base.value(listRule, jsSandbox, body, baseUrl) ?: return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
        return parsed.map { NodeValue.json(it.toPlainJava()) }
    }

    /** 把 kotlinx JsonElement 转成普通 Java 结构，使 JsonPath 取值与 JS 属性访问都能正常工作。 */
    private fun JsonElement.toPlainJava(): Any? = when (this) {
        is JsonObject -> LinkedHashMap<String, Any?>().also { map -> entries.forEach { (key, value) -> map[key] = value.toPlainJava() } }
        is JsonArray -> map { it.toPlainJava() }
        is JsonPrimitive -> if (isString) content
        else content.toBooleanStrictOrNull() ?: content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
        JsonNull -> null
    }

    fun content(sourceJson: String, chapterUrl: String, bookName: String? = null, book: BookContext? = null): ChapterContent {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl")
        val sourceName = source.string("bookSourceName")
        val replaceRules = database?.getEnabledReplaceRulesForScope(bookName, sourceUrl, sourceName).orEmpty()

        if (source.string("mainJs")?.isNotBlank() == true) {
            val result = JsSourceRunner(this, source).content(chapterUrl, book)
            val rawTitle = result.title
            val rawContent = result.content
            if (replaceRules.isEmpty()) return ChapterContent(rawTitle, rawContent, rawTitle, rawContent)
            val cleanedTitle = rawTitle?.let { ContentProcessor.processTitle(it, replaceRules, jsSandbox, bookName) }
            val cleanedText = ContentProcessor.processContent(rawContent, replaceRules, jsSandbox, bookName, rawTitle)
            return ChapterContent(cleanedTitle, cleanedText, rawTitle, rawContent)
        }

        return jsSandbox.withSourceContext(
            sourceContext(source, sourceUrl, book).let { it.copy(chapterUrl = chapterUrl) },
        ) {
            val rule = source.objectValue("ruleContent") ?: throw RuleExecutionException("该书源未配置 ruleContent")
            // 聚合源的章节地址同样是 data: 参数载体，正文由内容规则自行取回
            val dataPayload = dataUrlPayload(chapterUrl)
            val body: String
            val root: NodeValue
            if (dataPayload != null) {
                body = dataPayload
                root = NodeValue.json(dataPayload)
            } else {
                val (url, options) = splitUrlOptions(chapterUrl)
                val mergedOptions = mergeOptions(parseSourceHeaders(source, sourceUrl), options)
                body = fetchUrl(url, mergedOptions, null, sourceUrl, database)
                root = NodeValue.document(body)
            }
            // 插图：清洗时保留安全的 <img>（相对地址按章节页补全）——见 cleanContent 的说明
            var text = root.value(rule.string("content"), jsSandbox, body, chapterUrl)?.cleanContent(chapterUrl).orEmpty()
            if (text.length < 500) {
                val paragraphs = Regex("(?i)<p[^>]*>([\\s\\S]*?)</p>").findAll(body)
                    .map { it.groupValues[1].cleanContent(chapterUrl) }
                    .filter { it.length > 2 && !it.contains("按←键返回") && !it.contains("加入书签") && !it.contains("仅放置最近浏览") }
                    .toList()
                if (paragraphs.size >= 5) {
                    val candidate = paragraphs.joinToString("\n\n")
                    if (candidate.length > text.length) text = candidate
                }
            }
            val replaceRegex = rule.string("replaceRegex")
            if (!replaceRegex.isNullOrBlank()) {
                val patterns = replaceRegex.split(Regex("[\r\n]+|&&")).map { it.trim() }.filter { it.isNotBlank() }
                for (pattern in patterns) {
                    text = runCatching { text.replace(Regex(pattern), "") }.getOrDefault(text)
                }
            }
            if (text.isBlank()) {
                val detail = jsSandbox.lastError?.let { "，脚本异常：$it" } ?: ""
                throw RuleExecutionException("正文规则未提取到内容$detail")
            }

            val rawTitle = root.value(rule.string("title"), jsSandbox, body, chapterUrl)
            val rawText = text
            var cleanedTitle = rawTitle
            var cleanedText = rawText
            if (replaceRules.isNotEmpty()) {
                cleanedText = ContentProcessor.processContent(rawText, replaceRules, jsSandbox, bookName, rawTitle)
                if (rawTitle != null) {
                    cleanedTitle = ContentProcessor.processTitle(rawTitle, replaceRules, jsSandbox, bookName)
                }
            }
            ChapterContent(cleanedTitle, cleanedText, rawTitle, rawText)
        }
    }

    /**
     * 解析一章漫画的**有序图片 URL 清单**——方案 E · 方案 A 的解析层复用。
     *
     * 与 [content] 共享 fetch / 规则链 / JS 沙箱，**唯一差异是最后一步**：
     * `content()` 做文本净化 → 单行文本；
     * `mangaImages()` 做 URL 拆分 → 有序 URL 列表。
     *
     * @return 有序去重后的全量上游图片 URL（空列表意味着规则未提取到图片）。
     */
    fun mangaImages(sourceJson: String, chapterUrl: String, bookName: String? = null, book: BookContext? = null): List<String> {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl")

        // JS 书源（mainJs）：走 JsSourceRunner 取回文本，再尝试从文本中提取图片 URL
        if (source.string("mainJs")?.isNotBlank() == true) {
            val result = JsSourceRunner(this, source).content(chapterUrl)
            return splitImageUrls(result.content)
        }

        return jsSandbox.withSourceContext(
            sourceContext(source, sourceUrl, book).let { it.copy(chapterUrl = chapterUrl) },
        ) {
            val rule = source.objectValue("ruleContent") ?: throw RuleExecutionException("该书源未配置 ruleContent")
            val dataPayload = dataUrlPayload(chapterUrl)
            val body: String
            val root: NodeValue
            if (dataPayload != null) {
                body = dataPayload
                root = NodeValue.json(dataPayload)
            } else {
                val (url, options) = splitUrlOptions(chapterUrl)
                val mergedOptions = mergeOptions(parseSourceHeaders(source, sourceUrl), options)
                body = fetchUrl(url, mergedOptions, null, sourceUrl, database)
                root = NodeValue.document(body)
            }
            val raw = root.value(rule.string("content"), jsSandbox, body, chapterUrl)?.trim() ?: ""
            val urls = splitImageUrls(raw).map { it.absolute(chapterUrl) }
            // 保序去重（同一张图重现时保留首次位置）
            val seen = mutableSetOf<String>()
            urls.filter { it.isNotBlank() && seen.add(it) }
        }
    }

    /**
     * 从规则求值所得的字符串中提取有序图片 URL。
     *
     * 三种来源（按优先级）：
     * 1. HTML 片段里的 `<img>` 标签：优先用 `data-src` / `data-original`（懒加载兼容），否则用 `src`；
     * 2. JSON 数组字符串：`["url1","url2"]`；
     * 3. 换行分隔的纯文本 URL 列表（Legado 多值选择器默认 `\n` 连接）。
     */
    internal fun splitImageUrls(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()

        fun cleanImageUrl(url: String): String =
            url.substringBefore(",{").substringBefore(",%7B").trim().trimEnd(',', ' ')

        // 1. 按 <img> 标签提取 src / data-src / data-original
        val doc = Jsoup.parse(raw)
        val imgs = doc.select("img")
        if (imgs.isNotEmpty()) {
            return imgs.mapNotNull { el ->
                (el.attr("data-src").takeIf { it.isNotBlank() }
                    ?: el.attr("data-original").takeIf { it.isNotBlank() }
                    ?: el.attr("src").takeIf { it.isNotBlank() })
                    ?.let(::cleanImageUrl)
            }.filter { it.isNotBlank() }
        }

        // 2. JSON 数组字符串
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonArray
            if (parsed != null) {
                return parsed.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull?.let(::cleanImageUrl) }.filter { it.isNotBlank() }
            }
        }

        // 3. 换行分隔（Legado 多元素 CSS / JSON 多值连接默认 "\n"）
        return raw.lines().map(::cleanImageUrl).filter { it.isNotBlank() }
    }

    /**
     * 抓取**媒体资源**（漫画图片 / 有声书音频）时该用的请求头：书源 `header` + 登录头 + Cookie。
     *
     * 为什么要开这个 public 口子：媒体抓取发生在 `RuleRunner` 之外（`MangaImageFetcher`），
     * 但它**必须与书源取数用同一份请求头**——漫画站/音频站的防盗链判的就是这套头，
     * 少一个 `Referer` 或 Cookie 就是整章裂图，而且不会有任何报错。
     *
     * 解析失败一律返回空表（静默降级），绝不让头解析把抓取流程打断。
     */
    fun mediaHeaders(sourceJson: String, sourceId: String? = null): Map<String, String> =
        runCatching { parseSourceHeaders(sourceJson.objectValue(), sourceId) }.getOrDefault(emptyMap())

    // ------------------------------------------------------------------
    // 有声书（方案 G2）：章节音频地址解析
    // ------------------------------------------------------------------

    /**
     * 解析一章的**有序音频分段清单**——与 [mangaImages] 完全同构，只换最后一步的取值语义。
     *
     * 三种形态（G1）：
     * ① **纯文本 URL**（一行一个，Legado 多值规则默认 `\n` 连接）；
     * ② **JSON 数组**（多分段，**保序**；元素可以是字符串，也可以是 `{url, headers}` 对象）；
     * ③ **`url,{"headers":{...}}` 扩展头**（每段可带独立请求头，必须随分段一起交给 Fetcher，否则重拉 403）。
     *
     * 另额外兼容 HTML 片段里的 `<audio src>` / `<source src>`（部分源的音频规则直接给标签）。
     *
     * @return 有序分段规格；**不含上游 URL 的下发**，URL 只留在服务端（见 `AudioCache` 的 key 语义）。
     */
    fun audioTracks(sourceJson: String, chapterUrl: String, bookName: String? = null, book: BookContext? = null): List<AudioTrackSpec> {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl")

        // JS 书源（mainJs）：走 JsSourceRunner 取回文本，再从中提取音频地址
        if (source.string("mainJs")?.isNotBlank() == true) {
            val result = JsSourceRunner(this, source).content(chapterUrl, book)
            return normalizeTracks(splitAudioTracks(result.content), chapterUrl)
        }

        return jsSandbox.withSourceContext(
            // ⚠️ 必须带上书籍上下文：听书音色就靠源里 `book.getVariable('custom')` 读出来拼进音频地址。
            // 漏了它 ⇒ 变量永远读成空串 ⇒ 用户改了音色也**毫无变化且不报错**（实测踩过）。
            sourceContext(source, sourceUrl, book).let { it.copy(chapterUrl = chapterUrl) },
        ) {
            val rule = source.objectValue("ruleContent") ?: throw RuleExecutionException("该书源未配置 ruleContent")
            val dataPayload = dataUrlPayload(chapterUrl)
            val body: String
            val root: NodeValue
            if (dataPayload != null) {
                body = dataPayload
                root = NodeValue.json(dataPayload)
            } else {
                val (url, options) = splitUrlOptions(chapterUrl)
                val mergedOptions = mergeOptions(parseSourceHeaders(source, sourceUrl), options)
                body = fetchUrl(url, mergedOptions, null, sourceUrl, database)
                root = NodeValue.document(body)
            }
            val raw = root.value(rule.string("content"), jsSandbox, body, chapterUrl)?.trim() ?: ""
            normalizeTracks(splitAudioTracks(raw), chapterUrl)
        }
    }

    /**
     * 把相对地址补全到章节页，并**保序去重**。
     *
     * 为什么去重：对外 key = `sha256(sourceId + '\n' + url)` 是 `audio_cache` 的主键，
     * 同一章里出现两条完全相同的地址只会互相覆盖（`segment_index` 丢失），
     * 因此与漫画清单保持同一口径（同一地址只保留首次出现的位置）。
     */
    private fun normalizeTracks(tracks: List<AudioTrackSpec>, chapterUrl: String): List<AudioTrackSpec> {
        val seen = mutableSetOf<String>()
        return tracks.mapNotNull { spec ->
            val url = spec.url.absolute(chapterUrl).trim()
            if (url.isBlank()) return@mapNotNull null
            if (!seen.add("$url\u0000${spec.headersJson.orEmpty()}")) return@mapNotNull null
            spec.copy(url = url)
        }
    }

    /**
     * 从规则求值所得的字符串里提取有序音频分段。
     *
     * 拆分顺序：JSON 数组 → HTML `<audio>/<source>` → 换行纯文本。
     * 元素级的 `url,{"headers":…}` / `{"url":…,"headers":…}` 拆解见 [parseAudioTrackItem]。
     */
    internal fun splitAudioTracks(raw: String?): List<AudioTrackSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        val items = mutableListOf<String>()

        // ① JSON 数组：既可能是 ["url1","url2"]，也可能是 [{"url":…,"headers":…}]
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonArray
            if (parsed != null) {
                parsed.forEach { element ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull?.let { items += it }
                        // 对象形态：**整条对象**交给 parseAudioTrackItem
                        //（它才知道怎么取 `headers`；只取 url 会把每段专属头静默丢掉 ⇒ 重拉 403）
                        is JsonObject -> items += element.toString()
                        else -> Unit
                    }
                }
            }
        }

        // ② HTML 片段：<audio src> / <source src>（含 <audio><source src=…></audio> 的嵌套形态）
        if (items.isEmpty()) {
            val doc = Jsoup.parse(raw)
            val selected = doc.select("audio[src], source[src]")
            if (selected.isNotEmpty()) selected.forEach { items += it.attr("src") }
        }

        // ③ 换行分隔的纯文本（Legado 多元素规则的默认连接符）
        if (items.isEmpty()) items += raw.lines()

        return items.mapNotNull(::parseAudioTrackItem)
    }

    /**
     * 拆解单条音频项，**同时支持两种扩展头写法**：
     * - `https://a/b.mp3,{"headers":{"Referer":"…"}}`（Legado 的 `url,{options}` 形态）
     * - `{"url":"https://a/b.mp3","headers":{"Referer":"…"}}`（对象形态）
     *
     * 认不出来就当作**裸 URL**（绝不因为"选项解析失败"而丢掉整条音频——
     * 静默丢数据比报错危险得多，而裸 URL 至少还能播）。
     */
    internal fun parseAudioTrackItem(rawItem: String): AudioTrackSpec? {
        val item = rawItem.trim().trim(',', ' ').removeSurrounding("\"").trim()
        if (item.isEmpty()) return null

        if (item.startsWith("{")) {
            val obj = runCatching { Json.parseToJsonElement(item).jsonObject }.getOrNull()
                ?: return AudioTrackSpec(item, null)
            val url = obj.string("url") ?: obj.string("src") ?: return null
            return AudioTrackSpec(cleanAudioUrl(url), obj["headers"]?.toString())
        }

        // `,{` 与 URL 编码后的 `,%7B` 都出现过（上游/书源会自行编码）
        val plainIndex = item.indexOf(",{").takeIf { it >= 0 } ?: Int.MAX_VALUE
        val encodedIndex = item.indexOf(",%7B", ignoreCase = true).takeIf { it >= 0 } ?: Int.MAX_VALUE
        val index = minOf(plainIndex, encodedIndex)
        if (index == Int.MAX_VALUE) return AudioTrackSpec(cleanAudioUrl(item), null)

        val url = cleanAudioUrl(item.substring(0, index))
        val optionsText = item.substring(index + 1).trim()
        val headers = runCatching {
            Json.parseToJsonElement(optionsText).jsonObject["headers"]?.toString()
        }.getOrNull()
        return AudioTrackSpec(url, headers)
    }

    private fun cleanAudioUrl(url: String): String = url.trim().trimEnd(',', ' ')

    fun parseLoginUi(sourceJson: String): List<SourceLoginUiItem> {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl") ?: ""
        val loginUiStr = source.string("loginUi")?.trim()
        val loginUrl = source.string("loginUrl")?.trim()
        val jsLib = source.string("jsLib")?.trim()

        if (loginUiStr.isNullOrBlank()) {
            if (!loginUrl.isNullOrBlank() && (loginUrl.startsWith("http://") || loginUrl.startsWith("https://"))) {
                return listOf(
                    SourceLoginUiItem(
                        name = "打开登录网页",
                        type = "button",
                        action = loginUrl,
                        style = FlexChildStyle(layout_flexBasisPercent = 1.0f),
                    ),
                )
            }
            return emptyList()
        }

        val rawJson = if (loginUiStr.startsWith("@js:") || loginUiStr.startsWith("js:") || loginUiStr.contains("<js>")) {
            val jsCode = if (loginUiStr.contains("<js>")) loginUiStr.substringAfter("<js>").substringBefore("</js>") else loginUiStr.removePrefix("@js:").removePrefix("js:")
            val execContext = sourceContext(source, sourceUrl)
                .copy(sourceName = source.string("bookSourceName") ?: sourceUrl)
            jsSandbox.eval(jsCode, mapOf("sourceId" to sourceUrl, "baseUrl" to sourceUrl), execContext) ?: "[]"
        } else {
            loginUiStr
        }

        return runCatching {
            json.decodeFromString<List<SourceLoginUiItem>>(rawJson)
        }.getOrElse { emptyList() }
    }

    fun executeLoginAction(
        sourceJson: String,
        actionCode: String,
        loginData: Map<String, String>,
        isLongClick: Boolean = false,
    ): SourceLoginActionResult {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl") ?: ""
        val sourceName = source.string("bookSourceName") ?: sourceUrl

        // 脚本库（jsLib + loginUrl 形式的 JS）由执行上下文统一注入，这里只拼动作本身
        val execContext = sourceContext(source, sourceUrl).copy(
            sourceName = sourceName,
            initialLoginInfo = loginData.toMutableMap(),
            isLongClick = isLongClick,
        )

        val trimmedAction = actionCode.trim()
        if (trimmedAction.startsWith("http://") || trimmedAction.startsWith("https://")) {
            return SourceLoginActionResult(
                success = true,
                openUrl = trimmedAction,
            )
        }

        val fullScript = if (trimmedAction.equals("login", ignoreCase = true) || trimmedAction.startsWith("login(")) {
            "if (typeof login === 'function') { login.apply(this); } else { $trimmedAction; }"
        } else {
            trimmedAction
        }

        val bindings = mapOf(
            "result" to loginData,
            "baseUrl" to sourceUrl,
            "sourceId" to sourceUrl,
            "bookSourceUrl" to sourceUrl,
            "isLongClick" to isLongClick,
        )

        jsSandbox.eval(fullScript, bindings, execContext)
        val state = database?.getSourceLoginState(sourceUrl)
        val failure = execContext.lastError
        val openUrl = execContext.openUrl

        // 无头端 `startBrowserAwait` 是立即返回的 stub（body 恒为空），因此书源脚本在「已请求打开
        // 内置页面」之后必然读不到设置结果，会吐一句「未读取到设置结果，已保留原配置」的**误报**——
        // 真实结果由内置浏览器回传通道（POST /browser/result）落库，这里把误报替换成如实提示。
        val inlineBrowser = openUrl != null && !openUrl.startsWith("http://") && !openUrl.startsWith("https://")
        val suppressedStaleResult = inlineBrowser && execContext.toastMessages.any { it.contains(NO_SETTINGS_RESULT_MARKER) }
        val toastMessages = if (inlineBrowser) {
            execContext.toastMessages.filterNot { it.contains(NO_SETTINGS_RESULT_MARKER) }
        } else {
            execContext.toastMessages
        }

        return SourceLoginActionResult(
            success = failure == null,
            error = failure,
            toastMessages = if (suppressedStaleResult) toastMessages + INLINE_BROWSER_SAVE_HINT else toastMessages,
            openUrl = execContext.openUrl,
            copyText = execContext.copyText,
            updatedLoginInfo = execContext.initialLoginInfo.ifEmpty { state?.loginInfo ?: emptyMap() },
            updatedLoginHeader = state?.loginHeader,
            updatedVariable = state?.sourceVariable,
            reRenderUi = execContext.reRenderUi,
        )
    }

    fun checkLoginStatus(sourceJson: String): SourceLoginCheckResult {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl") ?: ""
        val loginCheckJs = source.string("loginCheckJs")?.trim()
        val loginUrl = source.string("loginUrl") ?: ""
        val jsLib = source.string("jsLib") ?: ""

        if (loginCheckJs.isNullOrBlank()) {
            val jar = database?.getSourceCookieJar(sourceUrl)
            val state = database?.getSourceLoginState(sourceUrl)
            val hasCookie = jar?.isNotEmpty() == true
            val hasInfo = state?.loginInfo?.isNotEmpty() == true
            return SourceLoginCheckResult(
                loggedIn = hasCookie || hasInfo,
                message = if (hasCookie || hasInfo) "已配置登录凭据" else "未登录",
            )
        }

        val execContext = sourceContext(source, sourceUrl)
        val result = jsSandbox.eval(loginCheckJs, mapOf("sourceId" to sourceUrl, "baseUrl" to sourceUrl), execContext)?.trim()
        val isSuccess = result == "true" || (result != null && result.isNotBlank() && result != "false" && result != "0" && !result.contains("未登录"))
        return SourceLoginCheckResult(
            loggedIn = isSuccess,
            message = if (isSuccess) "登录态有效" else (result ?: "未登录"),
        )
    }

    internal fun fetch(url: String, sourceId: String? = null, database: Database? = null): String {
        val (cleanUrl, options) = splitUrlOptions(url)
        val db = database ?: this.database
        val mergedOptions = if (db != null && !sourceId.isNullOrBlank()) {
            val headers = mutableMapOf<String, String>()
            val state = db.getSourceLoginState(sourceId)
            state?.loginHeader?.takeIf { it.isNotBlank() }?.let { h ->
                runCatching {
                    val elem = Json.parseToJsonElement(h)
                    if (elem is JsonObject) {
                        elem.entries.filter { it.value is JsonPrimitive }.forEach {
                            headers[it.key] = (it.value as JsonPrimitive).contentOrNull ?: ""
                        }
                    }
                }
            }
            val cookie = db.getSourceCookie(sourceId, cleanUrl)
            if (!cookie.isNullOrBlank()) {
                headers["Cookie"] = cookie
            }
            mergeOptions(headers, options)
        } else {
            options
        }
        return fetchUrl(cleanUrl, mergedOptions, null, sourceId, db)
    }

    private fun parseSourceHeaders(source: JsonObject, sourceUrl: String? = null): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val headerStr = source.string("header")?.trim()
        if (!headerStr.isNullOrBlank()) {
            headers.putAll(parseHeaderMap(headerStr))
        }
        val sUrl = sourceUrl ?: source.string("bookSourceUrl")
        val db = database
        if (db != null && !sUrl.isNullOrBlank()) {
            val state = db.getSourceLoginState(sUrl)
            state?.loginHeader?.takeIf { it.isNotBlank() }?.let { h ->
                headers.putAll(parseHeaderMap(h))
            }
            val cookie = db.getSourceCookie(sUrl, sUrl)
            if (!cookie.isNullOrBlank()) {
                headers["Cookie"] = cookie
            }
        }
        return headers
    }

    /**
     * 宽容解析书源 `header` / 登录头。
     *
     * 先按标准 JSON 解析；失败则回退到**宽容模式**。
     *
     * 为什么必须宽容（实测，见 SESSION-026）：Legado 生态里大量书源的 `header`
     * 用的是**单引号**的伪 JSON，例如：
     * ```
     * { 'User-Agent': 'okhttp/4.9.2', 'client-device': '0cde...', 'Authorization': 'bearer...' }
     * ```
     * 这**不是合法 JSON**，`Json.parseToJsonElement` 会抛异常。旧实现把异常
     * `runCatching` 吞掉 ⇒ **所有请求头被静默丢弃** ⇒ 需要鉴权的上游一律返回
     * 403 / `code:4004 device 不能为空`，表现为「搜索能出结果，但打开书正文全空」。
     *
     * 这类静默丢失极难排查，因此宽容模式是必需的，而不是「顺手兼容一下」。
     */
    internal fun parseHeaderMap(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyMap()
        // 1) 标准 JSON
        runCatching {
            val elem = Json.parseToJsonElement(text)
            if (elem is JsonObject) {
                return elem.entries.filter { it.value is JsonPrimitive }
                    .associate { it.key to ((it.value as JsonPrimitive).contentOrNull ?: "") }
            }
        }
        // 2) 宽容模式：单引号 / 无引号键 / Python dict 风格
        return parseLooseHeaderMap(text)
    }

    /**
     * 宽容模式的键值扫描器。
     *
     * 不用正则硬拆（值里可能含逗号、冒号、引号），而是**逐字符扫描**：
     * 先读键（可带单/双引号或裸标识符），再读冒号后的值（带引号则读到配对引号，
     * 否则读到下一个逗号或结尾），从而正确处理 `Authorization: 'bearer eyJ...'` 这类长值。
     */
    private fun parseLooseHeaderMap(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var i = text.indexOfFirst { it == '{' }
        i = if (i < 0) 0 else i + 1
        val end = text.lastIndexOf('}').let { if (it < 0) text.length else it }
        while (i < end) {
            // 跳过分隔与空行
            while (i < end && (text[i].isWhitespace() || text[i] == ',' || text[i] == ';')) i++
            if (i >= end) break

            // --- 读键 ---
            val key: String
            val afterKey: Int
            when (text[i]) {
                '\'', '"' -> {
                    val quote = text[i]
                    val close = text.indexOf(quote, i + 1)
                    if (close < 0 || close > end) break
                    key = text.substring(i + 1, close)
                    afterKey = close + 1
                }
                else -> {
                    var j = i
                    while (j < end && text[j] != ':' && text[j] != ',' && text[j] != '\n') j++
                    key = text.substring(i, j).trim()
                    afterKey = j
                }
            }
            i = afterKey
            while (i < end && text[i].isWhitespace()) i++
            if (i >= end || text[i] != ':') {
                // 不是 k:v（例如残留的 `}`），跳到下一个逗号
                while (i < end && text[i] != ',') i++
                continue
            }
            i++ // 跳过 ':'
            while (i < end && text[i].isWhitespace()) i++

            // --- 读值 ---
            val value: String
            if (i < end && (text[i] == '\'' || text[i] == '"')) {
                val quote = text[i]
                val sb = StringBuilder()
                var j = i + 1
                while (j < end) {
                    val ch = text[j]
                    if (ch == '\\' && j + 1 < end) { sb.append(text[j + 1]); j += 2; continue }
                    if (ch == quote) break
                    sb.append(ch); j++
                }
                value = sb.toString()
                i = if (j < end) j + 1 else end
            } else {
                var j = i
                while (j < end && text[j] != ',' && text[j] != '\n') j++
                value = text.substring(i, j).trim()
                i = j
            }
            val cleanKey = key.trim().trim('\'', '"')
            if (cleanKey.isNotEmpty()) result[cleanKey] = value
        }
        return result
    }

    private fun mergeOptions(sourceHeaders: Map<String, String>, options: UrlOptions?): UrlOptions {
        if (sourceHeaders.isEmpty()) return options ?: UrlOptions()
        val combined = sourceHeaders.toMutableMap()
        options?.headers?.let { combined.putAll(it) }
        return (options ?: UrlOptions()).copy(headers = combined)
    }

    private data class UrlOptions(
        val method: String = "GET",
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val charset: Charset = Charsets.UTF_8,
    )

    private fun splitUrlOptions(value: String): Pair<String, UrlOptions?> {
        val jsonStart = value.indexOf(",{")
        if (jsonStart < 0) return value to null
        val optionsText = value.substring(jsonStart + 1).trim()
        val optionsObject = runCatching { Json.parseToJsonElement(optionsText).jsonObject }.getOrNull() ?: return value to null
        return value.substring(0, jsonStart) to UrlOptions(
            method = optionsObject.string("method")?.uppercase() ?: "GET",
            body = optionsObject.string("body"),
            headers = optionsObject.objectValue("headers")?.entries?.filter { it.value is JsonPrimitive }?.associate { it.key to ((it.value as JsonPrimitive).contentOrNull ?: "") } ?: emptyMap(),
            charset = runCatching { Charset.forName(optionsObject.string("charset") ?: "UTF-8") }.getOrDefault(Charsets.UTF_8),
        )
    }

    private fun parseUri(url: String): URI {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            throw RuleExecutionException("URL 格式无效或包含未解析变量: $url")
        }
        // Legado 书源普遍把未转义的 JSON（含 { } " 等）直接拼进 query，Android 端 OkHttp 会自动编码，
        // 而 java.net.URI 严格按 RFC 3986 拒绝。这里先按原样解析，失败再对非法字符做百分号编码。
        runCatching { URI(trimmed) }.getOrNull()?.let { return it }
        val encoded = encodeIllegalUrlChars(trimmed)
        return runCatching { URI(encoded) }.getOrElse {
            throw RuleExecutionException("URL 解析失败: $url (${it.message})")
        }
    }

    private fun fetchUrl(
        url: String,
        options: UrlOptions?,
        keyword: String?,
        sourceId: String? = null,
        database: Database? = null,
    ): String {
        responseFetcher?.let { return it(url) }
        val db = database ?: this.database
        var request = buildRequest(parseUri(url), options, keyword)
        // 首次连接偶发失败（连接复用/握手抖动）时重试一次：书源 JS 常在内部 try/catch 吞掉 ajax 异常，
        // 不重试会表现为「规则执行成功但内容为空」这类极难排查的现象。
        var transientAttempt = 0
        repeat(4) {
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (error: java.io.IOException) {
                if (transientAttempt == 0) {
                    transientAttempt++
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                } else {
                    throw error
                }
            }
            val setCookies = response.headers().allValues("set-cookie")
            if (setCookies.isNotEmpty() && db != null && !sourceId.isNullOrBlank()) {
                setCookies.forEach { cookieStr ->
                    db.setSourceCookieFromSetCookie(sourceId, url, cookieStr)
                }
            }
            if (response.statusCode() !in 300..399) {
                if (response.statusCode() !in 200..299) throw RuleExecutionException("上游返回 HTTP ${response.statusCode()}")
                val bytes = readLimited(response.body())
                if (bytes.size > MAX_BODY_BYTES) throw RuleExecutionException("上游响应超过 2 MiB 限制")
                val charset = (options?.charset) ?: Charsets.UTF_8
                return decodeBody(bytes, response.headers().firstValue("content-encoding").orElse(""), charset)
            }
            val location = response.headers().firstValue("location").orElseThrow { RuleExecutionException("重定向缺少 Location") }
            val redirect = request.uri().resolve(location); validateTarget(redirect)
            request = buildRequest(redirect, options, keyword)
        }
        throw RuleExecutionException("重定向次数超过限制")
    }

    private fun buildRequest(initial: URI, options: UrlOptions?, keyword: String?): HttpRequest {
        validateTarget(initial)
        val builder = HttpRequest.newBuilder(initial)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "LegadoServer/0.1")
            .header("Accept-Encoding", "gzip, deflate")
        options?.headers?.forEach { (name, value) -> builder.header(name, value) }
        val method = options?.method?.uppercase() ?: "GET"
        val body = options?.body
        if (method != "GET" && body != null) {
            val rendered = keyword?.let { renderUrl(body, it, initial.toString()) } ?: body
            builder.method(method, HttpRequest.BodyPublishers.ofString(rendered, options?.charset ?: Charsets.UTF_8))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return builder.build()
    }

    private fun decodeBody(bytes: ByteArray, contentEncoding: String, charset: Charset): String {
        val encoding = contentEncoding.lowercase()
        val decoded = when {
            encoding.contains("gzip") -> GZIPInputStream(bytes.inputStream()).use(::readAll)
            encoding.contains("deflate") -> InflaterInputStream(bytes.inputStream()).use(::readAll)
            else -> bytes
        }
        return decoded.toString(charset)
    }

    private fun readAll(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= MAX_BODY_BYTES) { "上游响应超过 2 MiB 限制" }
            output.write(buffer, 0, count)
        }
    }

    private fun validateTarget(uri: URI) {
        try {
            NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "书源")
        } catch (e: IllegalArgumentException) {
            throw RuleExecutionException(e.message ?: "拒绝访问内网或本机地址")
        }
    }

    private fun renderUrl(template: String, keyword: String, sourceUrl: String): String = template
        .replace("{{key}}", URLEncoder.encode(keyword, Charsets.UTF_8))
        .replace("{{keyword}}", URLEncoder.encode(keyword, Charsets.UTF_8))
        .replace("{{page}}", "1")
        .replace("<key>", URLEncoder.encode(keyword, Charsets.UTF_8))
        .replace("{{source.key}}", URLEncoder.encode(keyword, Charsets.UTF_8))
        .replace("{{source.bookSourceUrl}}", sourceUrl)
        .replace("{{cookie.removeCookie(source.key)}}", "")

    private fun nodes(body: String, rule: String): List<NodeValue> = when {
        rule.startsWith("$") -> (JsonPath.read<Any>(body, rule) as? List<*>)?.map { NodeValue.json(it) } ?: emptyList()
        else -> Jsoup.parse(body).select(rule.css()).map(NodeValue::html)
    }

    /**
     * 把 Legado 的 `@` 链式选择器翻译成 CSS 选择器。
     *
     * 必须翻译的三类语法（缺一类就会**静默匹配 0 个元素**，表现为「目录 0 章」）：
     * 1. `id.xxx`   → `#xxx`      （旧实现直接透传，而 `id.list` 不是合法 CSS ⇒ 0 匹配）
     * 2. `tag.xxx`  → `xxx`       （旧实现只处理了后接 `.数字` 的形式，`tag.p` 会残留前缀）
     * 3. `xxx!0:1:2` → 排除多个下标（旧实现只认单个数字，多下标直接丢失排除语义）
     *
     * 实测（SESSION-026）：`id.list@dd!0:1:2:3:4:5:6:7:8` 旧实现产出 `id.list dd`，
     * Jsoup 匹配 **0** 个；正确应为 `#list dd`，匹配 **89** 个。
     */
    private fun String.css(): String {
        val clean = removePrefix("@css:").trim()
        val parts = clean.split("@")
        val cssParts = mutableListOf<String>()
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed in setOf("text", "href", "src", "content", "html", "textNodes", "textNode")) break
            if (trimmed.startsWith("attr(") || trimmed.startsWith("text(") || trimmed.startsWith("all")) break
            if ("!" in trimmed) {
                val (rawTag, notIndex) = trimmed.split("!", limit = 2)
                val tag = translateLegadoSelector(rawTag.trim())
                // `!0:1:2` —— 排除多个下标（Legado 用冒号分隔）；单个 `!0` 也要支持
                val excluded = notIndex.split(":").mapNotNull { it.trim().toIntOrNull() }.distinct()
                cssParts.add(
                    if (excluded.isEmpty()) tag
                    else excluded.joinToString("") { "$tag:not(:nth-child(${it + 1}))" }
                )
            } else if (trimmed.startsWith("class.")) {
                val cls = trimmed.removePrefix("class.")
                if ("." in cls) {
                    val (cName, idxStr) = cls.split(".", limit = 2)
                    val idx = idxStr.toIntOrNull()
                    if (idx != null) cssParts.add(".$cName:nth-of-type(${idx + 1})") else cssParts.add(".$cls")
                } else {
                    cssParts.add(".$cls")
                }
            } else if (trimmed.matches(Regex("^[a-zA-Z0-9-]+\\.\\d+$"))) {
                val (tag, idxStr) = trimmed.split(".", limit = 2)
                val idx = idxStr.toIntOrNull()
                if (idx != null) cssParts.add("$tag:nth-of-type(${idx + 1})") else cssParts.add(trimmed)
            } else {
                cssParts.add(translateLegadoSelector(trimmed))
            }
        }
        return if (cssParts.isEmpty()) clean else cssParts.joinToString(" ")
    }

    private fun translateLegadoSelector(selector: String): String = translateSelector(selector)
    /**
     * 正文清洗：优先走「结构性删除」（去掉 script/style/div 容器），
     * 但聚合源的正文**整体包在一个 `<div>` 里**（如大灰狼的 `<div rs-native>…</div>`），
     * 直接删 div 会把正文一起删光 ⇒ 表现为「正文规则未提取到内容」。
     * 因此当结构性删除后内容所剩无几时，退化为「只剥标签、保留文本」。
     *
     * ## 正文插图（`<img>`）为什么必须先"摘走"再放回
     * 旧实现把所有标签一并剥掉 ⇒ **正文里的插图彻底消失**（用户报「添加书籍正文插图功能」）。
     * 但直接把 `<img …>` 留在待清洗文本里也不行：结构性删除、`<[^>]+>` 剥离、
     * 实体反转义都会把它撕坏（属性里的 `onerror=…` 甚至会变成可见正文）。
     * 所以：先按 [IllustrationMarkup] 的判据把**安全的插图**换成独占一行的占位标记，
     * 文本清洗照旧跑，最后按行把占位标记换回**我们自己重建**的 `<img src="…" alt="…">`。
     *
     * 安全性由「重建」保证：只保留 `src`/`alt`（自转义），上游的 `on*`/`style`/`srcset`
     * 一个都进不来；`data:`/`javascript:` 之类非 http(s) 地址整条丢弃（见 ADR-033）。
     *
     * @param baseUrl 章节页地址（相对/协议相对插图地址的补全基准）；聚合源 `data:` 载荷传 null
     */
    private fun String.cleanContent(baseUrl: String? = null): String {
        val illustrations = mutableListOf<String>()
        val masked = IllustrationMarkup.IMG_TAG.replace(this) { match ->
            val safe = IllustrationMarkup.safeTag(match.value, baseUrl)
            if (safe == null) {
                ""
            } else {
                illustrations += safe
                "\n${IllustrationMarkup.slotMarker(illustrations.size - 1)}\n"
            }
        }
        // 占位行独占一行 ⇒ 清洗完成后按行换回插图标签（占位标记里没有任何标签字符，不会被清洗吃掉）
        fun String.restoreIllustrations(): String {
            if (illustrations.isEmpty()) return this
            return this.lines()
                .map { line -> IllustrationMarkup.slotIndex(line)?.let { illustrations.getOrNull(it) } ?: line.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        }
        val structural = masked
            .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("(?i)<div[\\s\\S]*?</div>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?p[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            .lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
        // 结构性删除把内容吃光了 ⇒ 说明正文被外层 div 包着，改走「只剥标签」
        if (structural.isNotBlank()) return structural.restoreIllustrations()
        val fallback = masked
            .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(p|div)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            .lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
        return fallback.restoreIllustrations()
    }
    private fun String.absolute(base: String): String = try {
        val cleanBase = base.substringBefore("##").substringBefore("#").trim()
        if (cleanBase.startsWith("http://", ignoreCase = true) || cleanBase.startsWith("https://", ignoreCase = true)) {
            URI(cleanBase).resolve(this).toString()
        } else {
            this
        }
    } catch (_: Exception) { this }
    private fun String.objectValue(): JsonObject = json.parseToJsonElement(this).jsonObject
    private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.jsonObject
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024

        /** 书源脚本在 startBrowserAwait 之后自报「读不到设置结果」的文案标记（无头端必然触发，属误报）。 */
        const val NO_SETTINGS_RESULT_MARKER = "未读取到设置结果"

        /** 替代误报的如实提示：改动由内置浏览器回传通道自动保存。 */
        const val INLINE_BROWSER_SAVE_HINT = "设置中心已在内置浏览器中打开，改动会自动保存"
    }
}

private class JsSourceRunner(private val runner: RuleRunner, private val source: JsonObject) {
    private val script = source.string("mainJs") ?: error("mainJs 缺失")

    fun search(keyword: String): List<SearchResult> = call("search", arrayOf(keyword, 1)).jsonArray().mapNotNull { value ->
        val book = Json.parseToJsonElement(value).jsonObject
        val url = book.string("bookUrl") ?: return@mapNotNull null
        SearchResult(source.string("bookSourceUrl")!!, book.string("name") ?: return@mapNotNull null, book.string("author"), url, book.string("coverUrl"), book.string("intro"))
    }
    fun details(bookUrl: String, book: BookContext? = null): BookDetails {
        val value = callOptional("getBookInfo", arrayOf(bookObject(bookUrl, book)))?.jsonObjectOrEmpty() ?: JsonObject(emptyMap())
        val name = sanitizeBookName(value.string("name"))
        return BookDetails(source.string("bookSourceUrl")!!, name.ifBlank { "未命名书籍" }, value.string("author"), value.string("intro"), value.string("coverUrl"), value.string("tocUrl") ?: bookUrl)
    }
    fun chapters(tocUrl: String, book: BookContext? = null): List<Chapter> = call("getChapters", arrayOf(bookObject(tocUrl, book))).jsonArray().mapIndexedNotNull { index, value ->
        val chapter = Json.parseToJsonElement(value).jsonObject; val url = chapter.string("url") ?: return@mapIndexedNotNull null; Chapter(index, chapter.string("title") ?: "第 ${index + 1} 章", url)
    }
    fun content(chapterUrl: String, book: BookContext? = null): ChapterContent = ChapterContent(content = call("getContent", arrayOf(chapterObject(chapterUrl), bookObject("", book), null)).trim().also { if (it.isBlank()) throw RuleExecutionException("JS书源正文为空") })

    private fun call(name: String, args: Array<Any?>): String = callOptional(name, args) ?: throw RuleExecutionException("JS书源缺少函数 $name")
    private fun callOptional(name: String, args: Array<Any?>): String? {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1; context.setClassShutter(ClassShutter { false })
            val scope = context.initSafeStandardObjects()
            ScriptableObject.putProperty(scope, "java", ajaxFunction(scope))
            context.evaluateString(scope, script, "source.js", 1, null)
            val function = ScriptableObject.getProperty(scope, name) as? Function ?: return null
            val raw = function.call(context, scope, scope, args.map { toJsValue(it, scope) }.toTypedArray())
            if (raw == null || raw == Context.getUndefinedValue()) return null
            if (raw is CharSequence) return raw.toString()
            return NativeJSON.stringify(context, scope, raw, null, null).toString()
        } catch (error: RuleExecutionException) { throw error }
        catch (error: Exception) { throw RuleExecutionException("JS书源 $name 执行失败: ${error.message}") }
        finally { Context.exit() }
    }
    private fun ajaxFunction(scope: Scriptable): NativeObject = NativeObject().also { api ->
        api.parentScope = scope
        ScriptableObject.putProperty(api, "ajax", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.firstOrNull()?.toString() ?: throw RuleExecutionException("ajax 缺少 URL")
                // 同 JsSandbox.jsBridge：受检异常直接冒泡会触发 Rhino 的 (Error) 强转 CCE，
                // 掩盖真实原因并击穿书源自身的 try/catch 容错。
                return try {
                    runner.fetch(url)
                } catch (error: Throwable) {
                    throw normalizeScriptThrowable(error)
                }
            }
        })
    }
    private fun toJsValue(value: Any?, scope: Scriptable): Any? = when (value) {
        is Map<*, *> -> NativeObject().also { objectValue ->
            objectValue.parentScope = scope
            value.forEach { (key, item) -> if (key is String) ScriptableObject.putProperty(objectValue, key, toJsValue(item, scope)) }
        }
        is List<*> -> Context.getCurrentContext().newArray(scope, value.map { toJsValue(it, scope) }.toTypedArray())
        else -> Context.javaToJS(value, scope)
    }
    /**
     * JS 书源（`mainJs`）看到的 `book` 对象。
     *
     * 与声明式书源的沙箱 `book` 桥接保持**同一份字段**（含 `variable` 变量表 JSON 与 `name/author/kind`），
     * 否则同一本书在两种书源形态下行为不一致 —— 这类"只在某一条路径生效"的差异最难排查。
     */
    private fun bookObject(url: String, book: BookContext? = null): Map<String, Any?> = mapOf(
        "bookUrl" to url,
        "tocUrl" to url,
        "name" to (book?.name ?: ""),
        "author" to (book?.author ?: ""),
        "kind" to (book?.kind ?: ""),
        "type" to (book?.numericType ?: 0),
        "variable" to (book?.variables?.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) } ?: ""),
        "variableMap" to (book?.variables ?: emptyMap<String, String>()),
    )
    private fun chapterObject(url: String) = mapOf("url" to url)
    private fun String.jsonArray(): List<String> = (Json.parseToJsonElement(this) as? JsonArray)?.map { it.toString() } ?: throw RuleExecutionException("JS书源返回值必须是数组")
    private fun String.jsonObjectOrEmpty(): JsonObject = runCatching { Json.parseToJsonElement(this).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}

internal class NodeValue private constructor(private val html: Element?, private val json: Any?) {
    fun value(rule: String?, jsSandbox: JsSandbox? = null, rawBody: String? = null, baseUrl: String? = null): String? {
        if (rule.isNullOrBlank()) return null
        var trimmedRule = rule.trim()

        if (trimmedRule.contains("{{") && trimmedRule.contains("}}")) {
            trimmedRule = Regex("\\{\\{(.*?)}}").replace(trimmedRule) { match ->
                val inner = match.groupValues[1].trim()
                if (inner.isBlank()) return@replace ""
                if (json != null && (inner.startsWith("$") || inner.startsWith(".."))) {
                    runCatching { unwrapJsonValue(readJson(inner)) ?: "" }.getOrDefault("")
                } else if (html != null) {
                    runCatching { valuePlain(inner) ?: "" }.getOrDefault("")
                } else {
                    ""
                }
            }
        }

        if (trimmedRule.startsWith("@js:") || trimmedRule.startsWith("js:")) {
            val jsCode = trimmedRule.removePrefix("@js:").removePrefix("js:").trim()
            val contextVal = html?.html() ?: json?.toString() ?: rawBody ?: ""
            return jsSandbox?.eval(jsCode, mapOf("result" to contextVal, "src" to (rawBody ?: contextVal), "baseUrl" to (baseUrl ?: ""))) ?: contextVal
        }

        if (trimmedRule.contains("<js>") && trimmedRule.contains("</js>")) {
            val preRule = trimmedRule.substringBefore("<js>").trim()
            val jsCode = trimmedRule.substringAfter("<js>").substringBefore("</js>").trim()
            val postRule = trimmedRule.substringAfter("</js>").trim()
            // 无前缀时 result 就是当前条目本身。JSON 条目必须按**对象**传入（沙箱会把 Map 转成 JS 对象），
            // 若按 toString 传，书源里的 result.book_id 之类属性访问全部取不到值，条目会被整条丢弃。
            val intermediate: Any? = if (preRule.isNotBlank()) valuePlain(preRule) ?: ""
            else html?.html() ?: json ?: rawBody ?: ""
            val evaluated = jsSandbox?.eval(
                jsCode,
                mapOf("result" to intermediate, "src" to (rawBody ?: intermediate), "baseUrl" to (baseUrl ?: "")),
            ) ?: (intermediate as? String ?: intermediate?.toString() ?: "")
            if (postRule.isBlank()) return evaluated
            // Legado 规则链：`<js>…</js>` 之后可以再接取值路径（如 `$.data` 或 CSS 选择器），
            // 把 JS 的返回值当作新文档继续取值。聚合源大量使用这种写法。
            return NodeValue.document(evaluated).value(postRule, jsSandbox, evaluated, baseUrl)
        }

        // ------------------------------------------------------------------
        // `取值路径@js:代码` —— Legado 的**后置 JS 处理**写法。
        //
        // 实测（SESSION-026）：书源 api.jmlldsc.com 的 chapterUrl 规则是
        //   $.path@js:java.aesBase64DecodeToString(result,"f041c49714d39908",...)
        // 旧实现没有这个分支：整串（含 `@js:`）被丢给 JsonPath，解析必然失败 ⇒
        // 返回 null ⇒ chapters() 里 `mapIndexedNotNull` 把**每一章**都丢掉
        // ⇒ 目录 0 章、正文取不到，且**没有任何报错**。
        //
        // 语义：先按左边的路径/CSS 取出值，把它作为 `result` 交给右边的 JS，
        // 用 JS 的返回值作为最终结果。
        // ------------------------------------------------------------------
        val jsSplitIndex = findJsPostProcessor(trimmedRule)
        if (jsSplitIndex > 0) {
            val preRule = trimmedRule.substring(0, jsSplitIndex).trim()
            val jsCode = trimmedRule.substring(jsSplitIndex)
                .removePrefix("@js:").removePrefix("js:").trim()
            if (jsCode.isNotBlank()) {
                val intermediate = valuePlain(preRule)
                val evaluated = jsSandbox?.eval(
                    jsCode,
                    mapOf(
                        "result" to (intermediate ?: ""),
                        "src" to (rawBody ?: intermediate ?: ""),
                        "baseUrl" to (baseUrl ?: ""),
                    ),
                )
                return evaluated ?: intermediate
            }
        }

        return valuePlain(trimmedRule)
    }

    /**
     * 找到 `@js:` / `js:` 后置处理器在规则中的位置（仅当它**不是**出现在开头时）。
     *
     * 返回 -1 表示没有后置 JS。必须做引号感知扫描：`@js:` 可能出现在 JS 字符串字面量里
     * （例如 `...@js:java.toast("@js:not-a-marker")`），朴素 indexOf 会切错位置。
     */
    private fun findJsPostProcessor(rule: String): Int {
        var i = 0
        var quote: Char? = null
        var escaped = false
        while (i < rule.length) {
            val ch = rule[i]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == quote -> quote = null
                }
                i++
                continue
            }
            if (ch == '\'' || ch == '"') { quote = ch; i++; continue }
            if (ch == '@' && rule.startsWith("@js:", i)) return i
            // 裸 `js:` 只在它前面是路径分隔上下文时才认，避免误伤 URL 里的 "js:"
            if (ch == 'j' && rule.startsWith("js:", i) && i > 0 && rule[i - 1] == '@') return i - 1
            i++
        }
        return -1
    }

    private fun valuePlain(rule: String): String? {
        if (rule.isBlank()) return null
        return if (json != null) valueJson(rule)
        else html?.let { element ->
            val selector = rule.removePrefix("@css:")
            val segments = selector.split('@')
            val lastSegment = segments.last()
            val rawMode = lastSegment.substringBefore("##").ifBlank { "text" }
            val selectorChain = segments.subList(0, segments.size - 1).filter { it.isNotBlank() }
            
            val elements = if (selectorChain.isEmpty()) listOf(element) else {
                var currentList = listOf(element)
                for (segment in selectorChain) {
                    val nextList = mutableListOf<Element>()
                    for (curr in currentList) {
                        nextList.addAll(selectAllLegado(curr, segment))
                    }
                    currentList = nextList
                    if (currentList.isEmpty()) break
                }
                currentList
            }
            if (elements.isEmpty()) return null
            val texts = elements.mapNotNull { el ->
                when (rawMode) {
                    "html" -> el.html().takeIf { it.isNotBlank() }
                    "text" -> el.text().takeIf { it.isNotBlank() }
                    "textNodes", "textNode" -> el.ownText().takeIf { it.isNotBlank() }
                    else -> el.attr(rawMode).takeIf { it.isNotBlank() }
                }
            }
            if (texts.isEmpty()) return null
            val resultText = applyRegexReplace(texts.joinToString("\n"), lastSegment)
            resultText.takeIf { it.isNotBlank() }
        }
    }

    private fun selectAllLegado(element: Element, selector: String): List<Element> = runCatching {
        val trimmed = selector.trim()
        if (trimmed == "children") return@runCatching element.children().toList()
        val idOnly = Regex("^id\\.([a-zA-Z0-9_-]+)$").find(trimmed)
        if (idOnly != null) {
            val el = element.getElementById(idOnly.groupValues[1])
            return@runCatching if (el != null) listOf(el) else emptyList()
        }
        val tagOnly = Regex("^tag\\.([a-zA-Z0-9_-]+)$").find(trimmed)
        if (tagOnly != null) {
            return@runCatching element.getElementsByTag(tagOnly.groupValues[1]).toList()
        }
        val classOnly = Regex("^class\\.([a-zA-Z][a-zA-Z0-9_-]*)$").find(trimmed)
        if (classOnly != null) {
            return@runCatching element.select(".${classOnly.groupValues[1]}")
        }
        val indexed = Regex("^([a-zA-Z][a-zA-Z0-9-]*)\\.(\\d+)$").find(trimmed)
        if (indexed != null) {
            val tag = indexed.groupValues[1]
            val ordinal = indexed.groupValues[2].toInt().coerceAtLeast(0)
            val matches = element.getElementsByTag(tag)
            return@runCatching matches.getOrNull(ordinal)?.let { listOf(it) } ?: emptyList()
        }
        val classIndexed = Regex("^class\\.([a-zA-Z][a-zA-Z0-9_-]*)\\.(\\d+)$").find(trimmed)
            ?: Regex("^\\.([a-zA-Z][a-zA-Z0-9_-]*)\\.(\\d+)$").find(trimmed)
        if (classIndexed != null) {
            val className = classIndexed.groupValues[1]
            val ordinal = classIndexed.groupValues[2].toInt().coerceAtLeast(0)
            val matches = element.select(".$className")
            return@runCatching matches.getOrNull(ordinal)?.let { listOf(it) } ?: emptyList()
        }
        // 兜底：先翻译 Legado 专有写法（`id.x`→`#x`、`tag.x`→`x`）再交给 Jsoup，
        // 否则 `id.list` 这类非法 CSS 会静默返回空集。
        element.select(translateSelector(trimmed))
    }.getOrDefault(emptyList())

    fun at(rule: String?): NodeValue {
        if (json == null || rule.isNullOrBlank() || !rule.trimStart().startsWith("$")) return this
        return runCatching { NodeValue.json(readJson(rule)) }.getOrDefault(this)
    }

    private fun unwrapJsonValue(value: Any?): String? {
        return when (value) {
            null -> null
            // JSON 对象必须序列化成合法 JSON：直接用 Java 的 {k=v} 形式会让后续规则链解析失败
            is Map<*, *> -> toJsonText(value)
            is List<*> -> {
                val nonNull = value.filterNotNull()
                // 元素是对象/嵌套结构时必须保留 JSON 数组形态，否则章节列表之类的规则链会被压成文本而无法再解析；
                // 纯标量列表仍按行拼接，保持原有取值语义。
                if (nonNull.any { it is Map<*, *> || it is List<*> }) toJsonText(nonNull)
                else if (nonNull.size == 1) unwrapJsonValue(nonNull[0])
                else nonNull.joinToString(separator = "\n") { unwrapJsonValue(it) ?: "" }.ifBlank { null }
            }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    /** 把 JsonPath 取出的 Java Map/List 递归序列化为合法 JSON 文本。 */
    private fun toJsonText(value: Any?): String = runCatching {
        Json.encodeToString(JsonElement.serializer(), toJsonElement(value))
    }.getOrElse { value?.toString() ?: "" }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun valueJson(rule: String): String? {
        // Legado 规则语法 `路径##正则##替换`：必须先剥离 ## 部分再交给 JsonPath，
        // 否则整串会当成 JSON 路径解析失败，条目会被 mapNotNull 全部丢弃（表现为搜索 0 结果）。
        val pathPart = rule.substringBefore("##").trim()
        val template = Regex("\\{\\{(.*?)}}").replace(pathPart) { match ->
            val inner = match.groupValues[1].trim()
            unwrapJsonValue(readJson(inner)) ?: ""
        }
        if (!template.startsWith("$")) return applyRegexReplace(template, rule).takeIf { it.isNotBlank() }
        val raw = unwrapJsonValue(readJson(template)) ?: return null
        return applyRegexReplace(raw, rule).takeIf { it.isNotBlank() }
    }

    /** Legado 的 `##正则##替换` 语法：成对出现，替换串缺省为空串。 */
    private fun applyRegexReplace(value: String, rule: String): String {
        if (!rule.contains("##")) return value
        val parts = rule.split("##")
        var result = value
        var index = 1
        while (index < parts.size) {
            val pattern = parts[index]
            val replacement = if (index + 1 < parts.size) parts[index + 1] else ""
            if (pattern.isNotBlank()) {
                result = runCatching { result.replace(Regex(pattern), replacement) }.getOrDefault(result)
            }
            index += 2
        }
        return result
    }

    private fun readJson(path: String): Any? = runCatching {
        val clean = path.trim()
        val normalized = if (clean.startsWith("$")) clean else if (clean.startsWith("..")) "$.$clean" else "$.$clean"
        JsonPath.read<Any>(json, normalized)
    }.getOrNull()

    companion object {
        fun html(element: Element) = NodeValue(element, null)
        fun document(body: String) = if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
            NodeValue(null, JsonPath.parse(body).json<Any>())
        } else {
            NodeValue(Jsoup.parse(body), null)
        }
        fun json(value: Any?) = NodeValue(null, value)
    }
}

class RuleExecutionException(message: String) : RuntimeException(message)

/**
 * 把单个 Legado 选择器片段翻译成合法 CSS。
 *
 * Legado 专有写法（**不是** CSS，直接交给 Jsoup 会静默匹配 0 个元素）：
 * - `id.xxx`  → `#xxx`
 * - `tag.xxx` → `xxx`
 *
 * 抽成文件级函数是因为 `RuleRunner`（列表规则的 `css()`）与 `NodeValue`
 * （取值规则的 `selectAllLegado()`）两条路径都需要它，避免两处各写一份而漂移。
 */
internal fun translateSelector(selector: String): String {
    val s = selector.trim()
    if (s.isEmpty()) return s
    Regex("^id\\.([A-Za-z0-9_\\-]+)$").find(s)?.let { return "#${it.groupValues[1]}" }
    Regex("^tag\\.([A-Za-z0-9_\\-]+)$").find(s)?.let { return it.groupValues[1] }
    return s
}

/**
 * 清洗书名。
 *
 * 实测（SESSION-027）：某书源的书架条目名是
 * `"十日终焉我成魔\n第八十章 星尘归寂，余念长存"` ——
 * 目录页的规则把「最新章节标题」一并取进了书名（`id.info@h1@text` 命中了包含
 * 章节信息的标题块），导致书架显示成两行、且「书名」里混入章节名。
 *
 * 清洗策略（保守，避免误伤合法书名）：
 * 1. 仅取第一行（换行后的内容一律丢弃）；
 * 2. 折叠内部连续空白；
 * 3. 去掉首尾书名号/引号等包裹符。
 *
 * **刻意不做**「按"第X章"截断」——那会误伤本身就叫《第X章》之类的书名。
 */
internal fun sanitizeBookName(raw: String?): String {
    val text = raw ?: return ""
    // 取第一行
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
    return firstLine
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('《', '》', '"', '"', '\'', '「', '」')
        .trim()
}

/**
 * 把 Java 侧抛出的异常规范化为**脚本可见、可被 `catch(e)` 捕获**的 JS 错误。
 *
 * ## 背景
 * Rhino 在处理「逃逸出脚本的 Java 异常」时假定它是 `java.lang.Error` 系，
 * 会对**受检异常**执行 `(Error)` 强制转换。Kotlin 没有受检异常，桥接函数
 * 会把 `SSLHandshakeException` 这类受检异常原样放行，于是 Rhino 抛出：
 *
 * ```
 * class javax.net.ssl.SSLHandshakeException cannot be cast to class java.lang.Error
 * ```
 *
 * ## 危害（三层，均非「报错难看」）
 * 1. 真实原因（证书/握手/超时）被 `ClassCastException` 完全掩盖；
 * 2. 该 CCE 在 JS 引擎内部抛出，**书源自己的 `try{...}catch(e){}` 抓不到**，
 *    聚合源赖以容错的多节点轮询与主备线路回退被整体击穿；
 * 3. 前端只能显示这行天书，用户无从判断真实原因。
 *
 * ## 实测结论（Rhino 1.8.0，生产沙箱设置 `initSafeStandardObjects` + `ClassShutter{false}`）
 * | 抛出形式 | 可被 JS catch | `e.name` | 错误消息 |
 * | :--- | :--- | :--- | :--- |
 * | 受检异常直接冒泡 | ❌ 抛 CCE | — | — |
 * | `RuntimeException` / `Error` 包装 | ❌ 仍漏过 | — | — |
 * | `Context.throwAsScriptRuntimeEx` | ✅ | `InternalError` | **丢失真实类型名** |
 * | **`ScriptRuntime.constructError`** | ✅ | `Error` | ✅ 保留真实类型名 |
 *
 * 因此采用 `constructError`：既恢复书源容错，又让消息自解释。
 *
 * ## 为什么不包裹 `RuleExecutionException`
 * 该类是**业务语义异常**（如「未配置 searchUrl」「上游返回 HTTP 404」），
 * 必须中断整条规则执行并冒泡到路由层转成明确的用户提示；若被归一化成
 * JS 错误，书源的 `catch` 会把它当成「线路故障」而静默吞掉，反而掩盖真实问题。
 * 实测确认：不包裹时它会如实逃逸出 JS catch，正是期望行为。
 */
internal fun normalizeScriptThrowable(error: Throwable): Nothing {
    if (error is RuleExecutionException) throw error
    val detail = "${error.javaClass.name}: ${error.message ?: "无详细信息"}"
    throw ScriptRuntime.constructError("Error", detail) as Throwable
}
