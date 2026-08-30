package io.legado.server

import com.jayway.jsonpath.JsonPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * A deliberately small, server-safe subset of Legado's declarative source protocol.
 * It keeps execution away from Android APIs and rejects private-network targets.
 */
class RuleRunner(private val responseFetcher: ((String) -> String)? = null) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsSandbox = JsSandbox(this)

    fun search(sourceJson: String, keyword: String): List<SearchResult> {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).search(keyword) }
        val rawSearchUrl = source.string("searchUrl") ?: throw RuleExecutionException("该书源未配置 searchUrl")
        val sourceUrl = source.string("bookSourceUrl") ?: throw RuleExecutionException("书源缺少 bookSourceUrl")
        val searchUrl = if (rawSearchUrl.trimStart().startsWith("@js:") || rawSearchUrl.trimStart().startsWith("js:") || rawSearchUrl.contains("<js>")) {
            val jsCode = if (rawSearchUrl.contains("<js>")) rawSearchUrl.substringAfter("<js>").substringBefore("</js>") else rawSearchUrl.removePrefix("@js:").removePrefix("js:")
            jsSandbox.eval(jsCode, mapOf("key" to keyword, "keyword" to keyword, "page" to 1, "baseUrl" to sourceUrl)) ?: throw RuleExecutionException("searchUrl JS 计算未返回有效地址")
        } else {
            rawSearchUrl
        }
        val (urlTemplate, options) = splitUrlOptions(searchUrl)
        val mergedOptions = mergeOptions(parseSourceHeaders(source), options)
        val rendered = renderUrl(urlTemplate, keyword, sourceUrl).absolute(sourceUrl)
        val body = fetchUrl(rendered, mergedOptions, keyword)
        val rule = source.objectValue("ruleSearch") ?: throw RuleExecutionException("该书源未配置 ruleSearch")
        val items = nodes(body, rule.string("bookList") ?: throw RuleExecutionException("缺少 ruleSearch.bookList"))
        return items.mapNotNull { item ->
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

    fun details(sourceJson: String, bookUrl: String): BookDetails {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).details(bookUrl) }
        return declarativeDetails(source, bookUrl)
    }

    private fun declarativeDetails(source: JsonObject, bookUrl: String): BookDetails {
        val (url, options) = splitUrlOptions(bookUrl)
        val mergedOptions = mergeOptions(parseSourceHeaders(source), options)
        val body = fetchUrl(url, mergedOptions, null)
        val rule = source.objectValue("ruleBookInfo") ?: throw RuleExecutionException("该书源未配置 ruleBookInfo")
        val root = NodeValue.document(body).at(rule.string("init"))
        fun value(key: String): String? = runCatching { root.value(rule.string(key), jsSandbox, body, bookUrl) }.getOrNull()
        val name = (value("name") ?: "").trim()
        return BookDetails(
            sourceId = source.string("bookSourceUrl")!!,
            name = name.ifBlank { "未命名书籍" },
            author = value("author"), intro = value("intro"),
            coverUrl = value("coverUrl")?.absolute(bookUrl),
            tocUrl = value("tocUrl")?.absolute(bookUrl) ?: bookUrl,
        )
    }

    fun chapters(sourceJson: String, tocUrl: String): List<Chapter> {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).chapters(tocUrl) }
        val (url, options) = splitUrlOptions(tocUrl)
        val mergedOptions = mergeOptions(parseSourceHeaders(source), options)
        val body = fetchUrl(url, mergedOptions, null)
        val rule = source.objectValue("ruleToc") ?: throw RuleExecutionException("该书源未配置 ruleToc")
        val listRule = rule.string("chapterList") ?: throw RuleExecutionException("缺少 ruleToc.chapterList")
        return nodes(body, listRule).mapIndexedNotNull { index, node ->
            val chUrl = node.value(rule.string("chapterUrl"), jsSandbox, body, tocUrl)?.absolute(tocUrl) ?: return@mapIndexedNotNull null
            Chapter(index, node.value(rule.string("chapterName"), jsSandbox, body, tocUrl) ?: "第 ${index + 1} 章", chUrl)
        }
    }

    fun content(sourceJson: String, chapterUrl: String): ChapterContent {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).content(chapterUrl) }
        val (url, options) = splitUrlOptions(chapterUrl)
        val mergedOptions = mergeOptions(parseSourceHeaders(source), options)
        val body = fetchUrl(url, mergedOptions, null)
        val rule = source.objectValue("ruleContent") ?: throw RuleExecutionException("该书源未配置 ruleContent")
        val root = NodeValue.document(body)
        var text = root.value(rule.string("content"), jsSandbox, body, chapterUrl)?.cleanContent().orEmpty()
        if (text.length < 500) {
            val paragraphs = Regex("(?i)<p[^>]*>([\\s\\S]*?)</p>").findAll(body)
                .map { it.groupValues[1].cleanContent() }
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
        if (text.isBlank()) throw RuleExecutionException("正文规则未提取到内容")
        return ChapterContent(root.value(rule.string("title"), jsSandbox, body, chapterUrl), text)
    }

    internal fun fetch(url: String): String = fetchUrl(url, null, null)

    private fun parseSourceHeaders(source: JsonObject): Map<String, String> {
        val headerStr = source.string("header")?.trim() ?: return emptyMap()
        return runCatching {
            val elem = Json.parseToJsonElement(headerStr)
            if (elem is JsonObject) {
                elem.entries.filter { it.value is JsonPrimitive }.associate { it.key to ((it.value as JsonPrimitive).contentOrNull ?: "") }
            } else emptyMap()
        }.getOrDefault(emptyMap())
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
        return try {
            URI(trimmed)
        } catch (e: Throwable) {
            throw RuleExecutionException("URL 解析失败: $url (${e.message})")
        }
    }

    private fun fetchUrl(url: String, options: UrlOptions?, keyword: String?): String {
        responseFetcher?.let { return it(url) }
        var request = buildRequest(parseUri(url), options, keyword)
        repeat(4) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
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
                val (tag, notIndex) = trimmed.split("!", limit = 2)
                val idx = notIndex.toIntOrNull()
                if (idx != null) {
                    cssParts.add("$tag:not(:nth-child(${idx + 1}))")
                } else {
                    cssParts.add(tag)
                }
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
                cssParts.add(trimmed)
            }
        }
        return if (cssParts.isEmpty()) clean else cssParts.joinToString(" ")
    }
    private fun String.cleanContent(): String = this
        .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
        .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
        .replace(Regex("(?i)<div[\\s\\S]*?</div>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</?p[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
    private fun String.absolute(base: String): String = try { URI(base).resolve(this).toString() } catch (_: Exception) { this }
    private fun String.objectValue(): JsonObject = json.parseToJsonElement(this).jsonObject
    private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.jsonObject
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private companion object { const val MAX_BODY_BYTES = 2 * 1024 * 1024 }
}

private class JsSourceRunner(private val runner: RuleRunner, private val source: JsonObject) {
    private val script = source.string("mainJs") ?: error("mainJs 缺失")

    fun search(keyword: String): List<SearchResult> = call("search", arrayOf(keyword, 1)).jsonArray().mapNotNull { value ->
        val book = Json.parseToJsonElement(value).jsonObject
        val url = book.string("bookUrl") ?: return@mapNotNull null
        SearchResult(source.string("bookSourceUrl")!!, book.string("name") ?: return@mapNotNull null, book.string("author"), url, book.string("coverUrl"), book.string("intro"))
    }
    fun details(bookUrl: String): BookDetails {
        val value = callOptional("getBookInfo", arrayOf(bookObject(bookUrl)))?.jsonObjectOrEmpty() ?: JsonObject(emptyMap())
        val name = value.string("name")?.trim().orEmpty()
        return BookDetails(source.string("bookSourceUrl")!!, name.ifBlank { "未命名书籍" }, value.string("author"), value.string("intro"), value.string("coverUrl"), value.string("tocUrl") ?: bookUrl)
    }
    fun chapters(tocUrl: String): List<Chapter> = call("getChapters", arrayOf(bookObject(tocUrl))).jsonArray().mapIndexedNotNull { index, value ->
        val chapter = Json.parseToJsonElement(value).jsonObject; val url = chapter.string("url") ?: return@mapIndexedNotNull null; Chapter(index, chapter.string("title") ?: "第 ${index + 1} 章", url)
    }
    fun content(chapterUrl: String): ChapterContent = ChapterContent(content = call("getContent", arrayOf(chapterObject(chapterUrl), bookObject(""), null)).trim().also { if (it.isBlank()) throw RuleExecutionException("JS书源正文为空") })

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
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any = runner.fetch(args.firstOrNull()?.toString() ?: throw RuleExecutionException("ajax 缺少 URL"))
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
    private fun bookObject(url: String) = mapOf("bookUrl" to url, "tocUrl" to url)
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
            val intermediate = if (preRule.isNotBlank()) valuePlain(preRule) ?: "" else (html?.html() ?: json?.toString() ?: rawBody ?: "")
            return jsSandbox?.eval(jsCode, mapOf("result" to intermediate, "src" to (rawBody ?: intermediate), "baseUrl" to (baseUrl ?: ""))) ?: intermediate
        }

        return valuePlain(trimmedRule)
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
            var resultText = texts.joinToString("\n")

            if (lastSegment.contains("##")) {
                val parts = lastSegment.split("##")
                var i = 1
                while (i < parts.size) {
                    val pattern = parts[i]
                    val replacement = if (i + 1 < parts.size) parts[i + 1] else ""
                    if (pattern.isNotBlank()) {
                        resultText = runCatching { resultText.replace(Regex(pattern), replacement) }.getOrDefault(resultText)
                    }
                    i += 2
                }
            }
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
        element.select(trimmed)
    }.getOrDefault(emptyList())

    fun at(rule: String?): NodeValue {
        if (json == null || rule.isNullOrBlank() || !rule.trimStart().startsWith("$")) return this
        return runCatching { NodeValue.json(readJson(rule)) }.getOrDefault(this)
    }

    private fun unwrapJsonValue(value: Any?): String? {
        return when (value) {
            null -> null
            is List<*> -> {
                val nonNull = value.filterNotNull()
                if (nonNull.size == 1) nonNull[0].toString().takeIf { it.isNotBlank() }
                else nonNull.joinToString(separator = "\n") { it.toString() }.ifBlank { null }
            }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun valueJson(rule: String): String? {
        val template = Regex("\\{\\{(.*?)}}").replace(rule) { match ->
            val inner = match.groupValues[1].trim()
            unwrapJsonValue(readJson(inner)) ?: ""
        }
        if (!template.startsWith("$")) return template.takeIf { it.isNotBlank() }
        return unwrapJsonValue(readJson(template))
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
