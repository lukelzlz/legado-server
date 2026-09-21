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
class RuleRunner(private val responseFetcher: ((String) -> String)? = null, internal var database: Database? = null) {
    constructor(database: Database) : this(null, database)
    constructor(responseFetcher: (String) -> String) : this(responseFetcher, null)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsSandbox = JsSandbox(this)

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
     * 构造当前书源的 JS 执行上下文（含 jsLib，规则 JS 因此可以调用书源工具函数）。
     *
     * 注意：Legado 约定里 `loginUrl` 可以不是网址，而是**整段登录用 JS**（聚合源普遍如此），
     * 其中的工具函数同样会被正文/目录规则调用，因此也要并入脚本库。
     */
    private fun sourceContext(source: JsonObject, sourceUrl: String?): JsExecutionContext {
        val library = buildString {
            source.string("jsLib")?.takeIf { it.isNotBlank() }?.let { append(it) }
            val loginScript = source.string("loginUrl")?.takeIf { it.isNotBlank() && !it.trim().startsWith("http") }
            if (loginScript != null) {
                if (isNotEmpty()) append("\n")
                append(loginScript)
            }
        }.takeIf { it.isNotBlank() }
        return JsExecutionContext(sourceId = sourceUrl, database = database, jsLib = library)
    }

    fun details(sourceJson: String, bookUrl: String): BookDetails {
        val source = sourceJson.objectValue()
        source.string("mainJs")?.takeIf { it.isNotBlank() }?.let { return JsSourceRunner(this, source).details(bookUrl) }
        return jsSandbox.withSourceContext(sourceContext(source, source.string("bookSourceUrl"))) {
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
        val sourceUrl = source.string("bookSourceUrl")
        return jsSandbox.withSourceContext(sourceContext(source, sourceUrl)) {
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

    fun content(sourceJson: String, chapterUrl: String, bookName: String? = null): ChapterContent {
        val source = sourceJson.objectValue()
        val sourceUrl = source.string("bookSourceUrl")
        val sourceName = source.string("bookSourceName")
        val replaceRules = database?.getEnabledReplaceRulesForScope(bookName, sourceUrl, sourceName).orEmpty()

        if (source.string("mainJs")?.isNotBlank() == true) {
            val result = JsSourceRunner(this, source).content(chapterUrl)
            if (replaceRules.isEmpty()) return result
            val cleanedTitle = result.title?.let { ContentProcessor.processTitle(it, replaceRules, jsSandbox, bookName) }
            val cleanedText = ContentProcessor.processContent(result.content, replaceRules, jsSandbox, bookName, result.title)
            return ChapterContent(cleanedTitle, cleanedText)
        }

        return jsSandbox.withSourceContext(
            sourceContext(source, sourceUrl).let { it.copy(chapterUrl = chapterUrl) },
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
            if (text.isBlank()) {
                val detail = jsSandbox.lastError?.let { "，脚本异常：$it" } ?: ""
                throw RuleExecutionException("正文规则未提取到内容$detail")
            }

            var title = root.value(rule.string("title"), jsSandbox, body, chapterUrl)
            if (replaceRules.isNotEmpty()) {
                text = ContentProcessor.processContent(text, replaceRules, jsSandbox, bookName, title)
                if (title != null) {
                    title = ContentProcessor.processTitle(title, replaceRules, jsSandbox, bookName)
                }
            }
            ChapterContent(title, text)
        }
    }

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

        return SourceLoginActionResult(
            success = failure == null,
            error = failure,
            toastMessages = execContext.toastMessages,
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
            runCatching {
                val elem = Json.parseToJsonElement(headerStr)
                if (elem is JsonObject) {
                    elem.entries.filter { it.value is JsonPrimitive }.forEach {
                        headers[it.key] = (it.value as JsonPrimitive).contentOrNull ?: ""
                    }
                }
            }
        }
        val sUrl = sourceUrl ?: source.string("bookSourceUrl")
        val db = database
        if (db != null && !sUrl.isNullOrBlank()) {
            val state = db.getSourceLoginState(sUrl)
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
            val cookie = db.getSourceCookie(sUrl, sUrl)
            if (!cookie.isNullOrBlank()) {
                headers["Cookie"] = cookie
            }
        }
        return headers
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

    /** 对 URL 中 RFC 不允许的字面字符做百分号编码。 */
    private fun encodeIllegalUrlChars(url: String): String {
        val builder = StringBuilder(url.length + 16)
        for (ch in url) {
            when {
                ch == ' ' -> builder.append("%20")
                ch.code > 127 || ch in "\"<>{}|\\^`" -> builder.append(URLEncoder.encode(ch.toString(), Charsets.UTF_8))
                else -> builder.append(ch)
            }
        }
        return builder.toString()
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
    /**
     * 正文清洗：优先走「结构性删除」（去掉 script/style/div 容器），
     * 但聚合源的正文**整体包在一个 `<div>` 里**（如大灰狼的 `<div rs-native>…</div>`），
     * 直接删 div 会把正文一起删光 ⇒ 表现为「正文规则未提取到内容」。
     * 因此当结构性删除后内容所剩无几时，退化为「只剥标签、保留文本」。
     */
    private fun String.cleanContent(): String {
        val structural = this
            .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("(?i)<div[\\s\\S]*?</div>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?p[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            .lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
        // 结构性删除把内容吃光了 ⇒ 说明正文被外层 div 包着，改走「只剥标签」
        if (structural.isNotBlank()) return structural
        return this
            .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(p|div)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            .lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
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
        element.select(trimmed)
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
