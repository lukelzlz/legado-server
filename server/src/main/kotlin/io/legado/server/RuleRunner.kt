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
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A deliberately small, server-safe subset of Legado's declarative source protocol.
 * It keeps execution away from Android APIs and rejects private-network targets.
 */
class RuleRunner(private val responseFetcher: ((String) -> String)? = null) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun search(sourceJson: String, keyword: String): List<SearchResult> {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).search(keyword) }
        val searchUrl = source.string("searchUrl") ?: throw RuleExecutionException("该书源未配置 searchUrl")
        if (searchUrl.trimStart().startsWith("@js")) throw RuleExecutionException("该书源的 searchUrl JavaScript 尚不受服务器支持")
        val sourceUrl = source.string("bookSourceUrl") ?: throw RuleExecutionException("书源缺少 bookSourceUrl")
        val body = fetch(renderUrl(searchUrl, keyword).absolute(sourceUrl))
        val rule = source.objectValue("ruleSearch") ?: throw RuleExecutionException("该书源未配置 ruleSearch")
        val items = nodes(body, rule.string("bookList") ?: throw RuleExecutionException("缺少 ruleSearch.bookList"))
        return items.mapNotNull { item ->
            val url = item.value(rule.string("bookUrl"))?.absolute(sourceUrl) ?: return@mapNotNull null
            SearchResult(
                sourceId = sourceUrl,
                name = item.value(rule.string("name")) ?: return@mapNotNull null,
                author = item.value(rule.string("author")), bookUrl = url,
                coverUrl = item.value(rule.string("coverUrl"))?.absolute(sourceUrl),
                intro = item.value(rule.string("intro")),
            )
        }
    }

    fun details(sourceJson: String, bookUrl: String): BookDetails {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).details(bookUrl) }
        return declarativeDetails(source, bookUrl)
    }

    /**
     * Confirms that a search hit can enter the reader without presenting a broken source choice.
     * This deliberately reads only the first chapter and does not persist any result.
     */
    fun isReadableSearchResult(sourceJson: String, result: SearchResult): Boolean = runCatching {
        val source = sourceJson.objectValue()
        val details = source.string("mainJs")?.takeIf { it.isNotBlank() }
            ?.let { JsSourceRunner(this, source).details(result.bookUrl, requireName = true) }
            ?: declarativeDetails(source, result.bookUrl, requireName = true)
        requireHttpUrl(details.coverUrl, "封面规则未提取到 HTTP(S) 地址")
        val firstChapter = chapters(sourceJson, details.tocUrl).firstOrNull()
            ?: throw RuleExecutionException("目录规则未提取到章节")
        content(sourceJson, firstChapter.url)
        true
    }.getOrDefault(false)

    private fun declarativeDetails(source: JsonObject, bookUrl: String, requireName: Boolean = false): BookDetails {
        val body = fetch(bookUrl)
        val rule = source.objectValue("ruleBookInfo") ?: throw RuleExecutionException("该书源未配置 ruleBookInfo")
        val root = NodeValue.document(body).at(rule.string("init"))
        val name = root.value(rule.string("name"))?.trim().orEmpty()
        if (requireName && name.isBlank()) throw RuleExecutionException("详情标题规则未提取到书名")
        return BookDetails(
            sourceId = source.string("bookSourceUrl")!!,
            name = name.ifBlank { "未命名书籍" },
            author = root.value(rule.string("author")), intro = root.value(rule.string("intro")),
            coverUrl = root.value(rule.string("coverUrl"))?.absolute(bookUrl),
            tocUrl = root.value(rule.string("tocUrl"))?.absolute(bookUrl) ?: bookUrl,
        )
    }

    fun chapters(sourceJson: String, tocUrl: String): List<Chapter> {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).chapters(tocUrl) }
        val body = fetch(tocUrl)
        val rule = source.objectValue("ruleToc") ?: throw RuleExecutionException("该书源未配置 ruleToc")
        val listRule = rule.string("chapterList") ?: throw RuleExecutionException("缺少 ruleToc.chapterList")
        return nodes(body, listRule).mapIndexedNotNull { index, node ->
            val url = node.value(rule.string("chapterUrl"))?.absolute(tocUrl) ?: return@mapIndexedNotNull null
            Chapter(index, node.value(rule.string("chapterName")) ?: "第 ${index + 1} 章", url)
        }
    }

    fun content(sourceJson: String, chapterUrl: String): ChapterContent {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).content(chapterUrl) }
        val body = fetch(chapterUrl)
        val rule = source.objectValue("ruleContent") ?: throw RuleExecutionException("该书源未配置 ruleContent")
        val root = NodeValue.document(body)
        val text = root.value(rule.string("content"))?.cleanContent().orEmpty()
        if (text.isBlank()) throw RuleExecutionException("正文规则未提取到内容")
        return ChapterContent(root.value(rule.string("title")), text)
    }

    internal fun fetch(url: String): String {
        responseFetcher?.let { return it(url) }
        val initial = URI(url); validateTarget(initial)
        var request = HttpRequest.newBuilder(initial).timeout(Duration.ofSeconds(20)).header("User-Agent", "LegadoServer/0.1").GET().build()
        repeat(4) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 300..399) {
                if (response.statusCode() !in 200..299) throw RuleExecutionException("上游返回 HTTP ${response.statusCode()}")
                if (response.body().toByteArray().size > MAX_BODY_BYTES) throw RuleExecutionException("上游响应超过 2 MiB 限制")
                return response.body()
            }
            val location = response.headers().firstValue("location").orElseThrow { RuleExecutionException("重定向缺少 Location") }
            val redirect = request.uri().resolve(location); validateTarget(redirect)
            request = HttpRequest.newBuilder(redirect).timeout(Duration.ofSeconds(20)).header("User-Agent", "LegadoServer/0.1").GET().build()
        }
        throw RuleExecutionException("重定向次数超过限制")
    }

    private fun validateTarget(uri: URI) {
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw RuleExecutionException("仅允许 HTTP(S) 书源地址")
        InetAddress.getAllByName(uri.host).forEach { address ->
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.hostAddress == "169.254.169.254") {
                throw RuleExecutionException("拒绝访问内网或本机地址")
            }
        }
    }

    private fun requireHttpUrl(value: String?, message: String) {
        val uri = runCatching { value?.trim()?.let(::URI) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) throw RuleExecutionException(message)
    }

    private fun renderUrl(template: String, keyword: String): String = template
        .replace("{{key}}", URLEncoder.encode(keyword, Charsets.UTF_8))
        .replace("{{keyword}}", URLEncoder.encode(keyword, Charsets.UTF_8))
        .replace("{{page}}", "1")
        .replace("<key>", URLEncoder.encode(keyword, Charsets.UTF_8))

    private fun nodes(body: String, rule: String): List<NodeValue> = when {
        rule.startsWith("$") -> (JsonPath.read<Any>(body, rule) as? List<*>)?.map { NodeValue.json(it) } ?: emptyList()
        else -> Jsoup.parse(body).select(rule.css()).map(NodeValue::html)
    }

    private fun String.css(): String = removePrefix("@css:").substringBefore("@").trim()
    private fun String.cleanContent(): String = Jsoup.parseBodyFragment(this).text().replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\\n\\n").trim()
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
    fun details(bookUrl: String, requireName: Boolean = false): BookDetails {
        val value = callOptional("getBookInfo", arrayOf(bookObject(bookUrl)))?.jsonObjectOrEmpty() ?: JsonObject(emptyMap())
        val name = value.string("name")?.trim().orEmpty()
        if (requireName && name.isBlank()) throw RuleExecutionException("详情标题规则未提取到书名")
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

private class NodeValue private constructor(private val html: Element?, private val json: Any?) {
    fun value(rule: String?): String? {
        if (rule.isNullOrBlank()) return null
        return if (json != null) valueJson(rule)
        else html?.let { element ->
            val selector = rule.removePrefix("@css:"); val mode = selector.substringAfter("@", "text")
            val target = element.selectFirst(selector.substringBefore("@")) ?: return null
            when (mode) { "html" -> target.html(); "text" -> target.text(); else -> target.attr(mode) }
        }
    }
    fun at(rule: String?): NodeValue {
        if (json == null || rule.isNullOrBlank() || !rule.trimStart().startsWith("$")) return this
        return runCatching { NodeValue.json(readJson(rule)) }.getOrDefault(this)
    }

    private fun valueJson(rule: String): String? {
        val template = Regex("\\{\\{(.*?)}}").replace(rule) { match ->
            runCatching { readJson(match.groupValues[1]).toString() }.getOrDefault("")
        }
        if (!template.startsWith("$")) return template.takeIf { it.isNotBlank() }
        return runCatching { readJson(template).toString() }.getOrNull()
    }

    private fun readJson(path: String): Any = JsonPath.parse(json).read(path)

    companion object {
        fun html(element: Element) = NodeValue(element, null)
        fun document(body: String) = if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
            NodeValue(null, JsonPath.parse(body).json<Any>())
        } else {
            NodeValue(Jsoup.parse(body).body(), null)
        }
        fun json(value: Any?) = NodeValue(null, value)
    }
}

class RuleExecutionException(message: String) : RuntimeException(message)
