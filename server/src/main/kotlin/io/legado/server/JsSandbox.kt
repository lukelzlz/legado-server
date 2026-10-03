package io.legado.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class JsExecutionContext(
    val sourceId: String? = null,
    val sourceName: String? = null,
    val sourceComment: String? = null,
    val database: Database? = null,
    val initialLoginInfo: MutableMap<String, String> = mutableMapOf(),
    val isLongClick: Boolean = false,
    val toastMessages: MutableList<String> = mutableListOf(),
    var openUrl: String? = null,
    var copyText: String? = null,
    var reRenderUi: Boolean = false,
    /** 脚本执行失败的原始原因，供登录动作回传给前端展示（否则按钮会「假成功」） */
    var lastError: String? = null,
    /** 当前书籍上下文，供 book 桥接对象读取 */
    val bookUrl: String? = null,
    val bookName: String? = null,
    val bookAuthor: String? = null,
    val bookTocUrl: String? = null,
    val bookType: Int? = null,
    val bookVariables: MutableMap<String, String> = mutableMapOf(),
    /** 当前章节上下文，供 chapter 桥接对象读取 */
    val chapterUrl: String? = null,
    val chapterIndex: Int? = null,
    val chapterTitle: String? = null,
    /** 该书源的 jsLib：所有规则 JS 都应能调用其中的工具函数 */
    val jsLib: String? = null,
)

class JsSandbox(private val runner: RuleRunner? = null) {
    private val sessionStore = ConcurrentHashMap<String, Any>()

    /**
     * `cache.putMemory`/`cache.getFromMemory` 的进程内后端（key 形如 `sourceId\u0000key`）。
     * 只在本沙箱实例内有效，不落库 —— 与 Legado `CacheManager` 的内存缓存语义一致。
     * 存的是**原始 JS 值**（见 [createCacheBridge] 的说明）。
     */
    private val memoryCache = ConcurrentHashMap<String, Any?>()

    /**
     * 当前线程正在求值的书源上下文。
     *
     * 规则的 `<js>` 片段（NodeValue）无法方便地把执行上下文逐层透传，但书源 JS 普遍依赖
     * `source.getVariable()` 与 jsLib 里的工具函数，因此由 RuleRunner 的入口方法设置本线程上下文，
     * eval 在未显式传入 execContext 时回退到它。
     */
    @PublishedApi
    internal val threadContext = ThreadLocal<JsExecutionContext?>()

    /** 在指定的书源上下文内执行 block（inline 以便调用方直接 return）。 */
    inline fun <T> withSourceContext(context: JsExecutionContext?, block: () -> T): T {
        val previous = threadContext.get()
        threadContext.set(context)
        try {
            return block()
        } finally {
            threadContext.set(previous)
        }
    }

    fun eval(
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
        execContext: JsExecutionContext? = null,
    ): String? {
        val context = execContext ?: threadContext.get()
        val rawScript = script.trim().removePrefix("@js:").removePrefix("js:").trim()
        if (rawScript.isBlank()) return null
        // 书源的 jsLib 对所有规则 JS 可见（Legado 语义），集中在这里注入，
        // 避免每个调用点各自拼接、漏拼一处就整段脚本 ReferenceError。
        val library = context?.jsLib?.takeIf { it.isNotBlank() }

        // 关键：jsLib **不能**与规则脚本拼接后共同参与「是否包裹 IIFE」的判定。
        // 聚合源 jsLib 里每个工具函数都含 return，拼接判定会命真 ⇒ 整段被包进 IIFE，
        // 而 Rhino 在 IIFE 下的求值补全值会退化为 undefined，正文规则末尾的裸表达式
        // （如 `data;`）就拿不到值。改为：jsLib 先在同一 scope 内单独求值（只建立函数
        // 定义），规则脚本再按**自身的顶层 return** 决定是否包裹。
        // 实测背景见 docs/sessions/SESSION-019-dagou-content-root-cause.md。
        val executableScript =
            if (hasTopLevelReturn(rawScript)) "(function(){\n$rawScript\n})()" else rawScript



        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.setClassShutter(ClassShutter { false }) // Sandbox: block all Java reflection
            val scope = cx.initSafeStandardObjects()

            // Inject bindings (e.g. result, src, baseUrl, key, page, book, chapter, isLongClick)
            bindings.forEach { (key, value) ->
                ScriptableObject.putProperty(scope, key, toJsValue(value, scope))
            }

            // Inject standard Legado 'java' bridge object
            ScriptableObject.putProperty(scope, "java", createJavaBridge(scope, context))

            // Legado 书源的 jsLib 惯用 `new JavaImporter()` + `with(importer){ ... }` 组织工具函数库。
            // 沙箱禁用了真实 Java 反射，此处提供空实现的安全替身：只要不再抛 ReferenceError，
            // 整个 with 块就能正常完成函数定义，后续登录动作才可能执行。
            ScriptableObject.putProperty(scope, "JavaImporter", createJavaImporterStub(scope))

            // Inject 'source' and 'cookie' bridge objects if sourceId is available
            val sourceId = context?.sourceId ?: bindings["sourceId"]?.toString() ?: bindings["baseUrl"]?.toString() ?: bindings["bookSourceUrl"]?.toString()
            if (!sourceId.isNullOrBlank()) {
                val db = context?.database ?: runner?.database
                ScriptableObject.putProperty(scope, "source", createSourceBridge(scope, sourceId, db, context))
                ScriptableObject.putProperty(scope, "cookie", createCookieBridge(scope, sourceId, db))
                // cache：Legado CacheManager 语义（getFromMemory/putMemory 走内存，get/put 落库）。
                // 真实 RSS 源的 sourceUrl 与 header 规则都依赖它，缺了会静默失效。
                ScriptableObject.putProperty(scope, "cache", createCacheBridge(scope, sourceId, db))
            }

            // Legado 的 book 对象：书源 JS 会用 book.getVariable('custom') 读取书籍级变量
            ScriptableObject.putProperty(scope, "book", createBookBridge(scope, context))
            // chapter 对象：正文规则会引用 chapter.index / chapter.title
            ScriptableObject.putProperty(scope, "chapter", createChapterBridge(scope, context))

            // 先单独求值 jsLib：只建立函数定义，其结果不参与补全值。
            // 失败不中断（部分源的 jsLib 依赖未实现的 API），但记录原因便于排障。
            if (library != null) {
                runCatching { cx.evaluateString(scope, library, "jsLib.js", 1, null) }
                    .onFailure { e ->
                        lastError = "jsLib 求值失败：" + (e.message ?: e.javaClass.simpleName)
                    }
            }

            val result = cx.evaluateString(scope, executableScript, "rule.js", 1, null)
            lastError = null // 本次成功：清除旧值，避免调用方读到上一次失败的残留
            if (result == null || result == Context.getUndefinedValue()) {
                val globalResult = ScriptableObject.getProperty(scope, "result")
                if (globalResult != null && globalResult != Context.getUndefinedValue()) {
                    return jsValueToString(cx, scope, globalResult)
                }
                return null
            }
            return jsValueToString(cx, scope, result)
        } catch (e: Exception) {
            // 不能静默吞掉：否则登录按钮全部「执行成功」却毫无动作，用户无从判断原因。
            // 记录到执行上下文，由调用方决定是展示还是忽略。
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            context?.lastError = detail
            lastError = detail
            return null
        } finally {
            Context.exit()
        }
    }

    /** 最近一次 eval 的失败原因，供无法拿到执行上下文的调用方读取。 */
    @Volatile
    var lastError: String? = null
        private set

    companion object {
        /**
         * 把任意文本安全地嵌进 JS 字符串字面量。
         *
         * 只用于把 JSON 文本喂给沙箱内的 `JSON.parse`（见 [storageToJs]），
         * 因此必须处理引号/反斜杠/控制字符，以及 JS 特有的行终止符 U+2028/U+2029。
         */
        internal fun quoteForJavaScript(text: String): String {
            val out = StringBuilder(text.length + 2)
            out.append('"')
            for (ch in text) {
                when {
                    ch == '"' -> out.append("\\\"")
                    ch == '\\' -> out.append("\\\\")
                    ch == '\n' -> out.append("\\n")
                    ch == '\r' -> out.append("\\r")
                    ch == '\t' -> out.append("\\t")
                    ch == '\u2028' -> out.append("\\u2028")
                    ch == '\u2029' -> out.append("\\u2029")
                    ch < ' ' -> out.append("\\u%04x".format(ch.code))
                    else -> out.append(ch)
                }
            }
            out.append('"')
            return out.toString()
        }

        /**
         * 判断脚本是否存在**顶层** `return`。
         *
         * 只有顶层 `return` 才需要把脚本包进 `(function(){…})()`（否则 Rhino 会抛
         * `return not in function`，见部落知识 TK-06）。此前的判定用 `Regex("\\breturn\\b")`，
         * 会命中函数体、字符串、注释里的 `return` ⇒ 整段（含 jsLib）被包裹，
         * 导致 Rhino 的**求值补全值退化为 undefined**，聚合源正文因此取空。
         * （实测过程见 docs/sessions/SESSION-019-dagou-content-root-cause.md。）
         *
         * 本实现为引号/注释/模板字面量感知的括号深度扫描：仅在深度 0 且
         * 前后均非标识符字符时判定为顶层 return。
         */
        internal fun hasTopLevelReturn(script: String): Boolean {
            var i = 0
            var depth = 0
            val n = script.length
            while (i < n) {
                when (val c = script[i]) {
                    '\'', '"' -> {
                        val quote = c
                        i++
                        while (i < n && script[i] != quote) {
                            if (script[i] == '\\') i++
                            i++
                        }
                        i++
                    }
                    '`' -> {
                        i++
                        while (i < n && script[i] != '`') {
                            if (script[i] == '\\') i++
                            i++
                        }
                        i++
                    }
                    '/' -> {
                        val next = if (i + 1 < n) script[i + 1] else ' '
                        if (next == '/') {
                            i += 2
                            while (i < n && script[i] != '\n') i++
                        } else if (next == '*') {
                            i += 2
                            while (i + 1 < n && !(script[i] == '*' && script[i + 1] == '/')) i++
                            i += 2
                        } else {
                            i++
                        }
                    }
                    '(', '[', '{' -> { depth++; i++ }
                    ')', ']', '}' -> { depth--; i++ }
                    else -> {
                        if (depth == 0 && c == 'r' && script.startsWith("return", i)) {
                            val prev = script.substring(0, i).lastOrNull { !it.isWhitespace() }
                            val after = script.getOrNull(i + 6)
                            val prevIsWord = prev != null && (prev.isLetterOrDigit() || prev == '_' || prev == '$')
                            val afterIsWord = after != null && (after.isLetterOrDigit() || after == '_' || after == '$')
                            if (!prevIsWord && !afterIsWord) return true
                        }
                        i++
                    }
                }
            }
            return false
        }
    }

    /** `chapter` 桥接对象：正文规则会引用 chapter.index / chapter.title，缺失会让整段脚本中断。 */
    private fun createChapterBridge(scope: Scriptable, execContext: JsExecutionContext?): NativeObject {
        val chapter = NativeObject()
        chapter.parentScope = scope
        ScriptableObject.putProperty(chapter, "index", execContext?.chapterIndex ?: 0)
        ScriptableObject.putProperty(chapter, "title", execContext?.chapterTitle ?: "")
        ScriptableObject.putProperty(chapter, "url", execContext?.chapterUrl ?: "")
        ScriptableObject.putProperty(chapter, "start", 0)
        ScriptableObject.putProperty(chapter, "end", 0)
        return chapter
    }

    /**
     * `book` 桥接对象。     *
     * Legado 书源 JS 经常通过 `book.getVariable('custom')` 读取书籍级变量来决定请求参数；
     * 这里提供真实可存取的变量表，而不是让它抛错中断整段脚本。
     */
    private fun createBookBridge(scope: Scriptable, execContext: JsExecutionContext?): NativeObject {
        val book = NativeObject()
        book.parentScope = scope
        ScriptableObject.putProperty(book, "type", execContext?.bookType ?: 0)
        ScriptableObject.putProperty(book, "name", execContext?.bookName ?: "")
        ScriptableObject.putProperty(book, "author", execContext?.bookAuthor ?: "")
        ScriptableObject.putProperty(book, "bookUrl", execContext?.bookUrl ?: "")
        ScriptableObject.putProperty(book, "tocUrl", execContext?.bookTocUrl ?: "")
        ScriptableObject.putProperty(book, "durChapterIndex", execContext?.chapterIndex ?: 0)
        ScriptableObject.putProperty(book, "durChapterTitle", "")
        ScriptableObject.putProperty(book, "order", 0)
        val readConfig = NativeObject()
        readConfig.parentScope = scope
        ScriptableObject.putProperty(book, "readConfig", readConfig)

        ScriptableObject.putProperty(book, "getVariable", object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val key = args.firstOrNull()?.toString() ?: return "null"
                return execContext?.bookVariables?.get(key) ?: "null"
            }
        })
        ScriptableObject.putProperty(book, "setVariable", object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val key = args.firstOrNull()?.toString()
                if (key != null) {
                    execContext?.bookVariables?.put(key, args.getOrNull(1)?.toString() ?: "")
                }
                return Context.getUndefinedValue()
            }
        })
        val noop = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any =
                Context.getUndefinedValue()
        }
        ScriptableObject.putProperty(book, "setUseReplaceRule", noop)
        return book
    }

    /**
     * `JavaImporter` 的安全替身。     *
     * Legado 的 Rhino 环境提供 `JavaImporter`，大量书源的 jsLib 用它配合 `with` 语句组织工具函数库。
     * 沙箱出于安全禁用真实 Java 反射，因此这里只返回一个**空的**导入器：importClass/importPackage
     * 为空操作，不暴露任何 Java 能力，但足以让脚本不再抛 ReferenceError、正常完成函数定义。
     */
    private fun createJavaImporterStub(scope: Scriptable): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
            val importer = NativeObject()
            importer.parentScope = scope
            val noop = object : BaseFunction() {
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any =
                    Context.getUndefinedValue()
            }
            ScriptableObject.putProperty(importer, "importClass", noop)
            ScriptableObject.putProperty(importer, "importPackage", noop)
            return importer
        }

        override fun construct(cx: Context, s: Scriptable, args: Array<out Any?>): Scriptable =
            call(cx, s, s, args) as Scriptable
    }

    private fun jsValueToString(context: Context, scope: Scriptable, value: Any?): String? = when (value) {        null, Context.getUndefinedValue() -> null
        is CharSequence -> value.toString()
        is Number, is Boolean -> value.toString()
        else -> runCatching {
            NativeJSON.stringify(context, scope, value, null, null).toString()
        }.getOrNull() ?: value.toString()
    }

    private fun createSourceBridge(
        scope: Scriptable,
        sourceId: String,
        db: Database?,
        execContext: JsExecutionContext?,
    ): NativeObject = NativeObject().also { api ->
        api.parentScope = scope

        // source.bookSourceUrl / source.getKey() / source.key
        val keyFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any = sourceId
        }
        ScriptableObject.putProperty(api, "getKey", keyFn)
        ScriptableObject.putProperty(api, "bookSourceUrl", sourceId)
        ScriptableObject.putProperty(api, "key", sourceId)
        ScriptableObject.putProperty(api, "bookSourceName", execContext?.sourceName ?: sourceId)
        ScriptableObject.putProperty(api, "bookSourceComment", execContext?.sourceComment ?: "")

        // source.getLoginInfo() -> JSON string
        ScriptableObject.putProperty(api, "getLoginInfo", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val current = execContext?.initialLoginInfo?.takeIf { it.isNotEmpty() }
                    ?: db?.getSourceLoginState(sourceId)?.loginInfo
                    ?: emptyMap()
                return Json.encodeToString(current)
            }
        })

        // source.getLoginInfoMap() -> NativeObject
        ScriptableObject.putProperty(api, "getLoginInfoMap", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val current = execContext?.initialLoginInfo?.takeIf { it.isNotEmpty() }
                    ?: db?.getSourceLoginState(sourceId)?.loginInfo
                    ?: emptyMap()
                return toJsValue(current, scope) ?: NativeObject().also { it.parentScope = scope }
            }
        })

        // source.putLoginInfo(info)
        ScriptableObject.putProperty(api, "putLoginInfo", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val arg = args.firstOrNull() ?: return false
                val map = parseJsMapOrJson(arg)
                if (execContext != null) {
                    execContext.initialLoginInfo.putAll(map)
                }
                db?.saveSourceLoginInfo(sourceId, execContext?.initialLoginInfo ?: map)
                return true
            }
        })

        // source.removeLoginInfo()
        ScriptableObject.putProperty(api, "removeLoginInfo", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                execContext?.initialLoginInfo?.clear()
                db?.removeSourceLoginInfo(sourceId)
                return true
            }
        })

        // source.getLoginHeader()
        ScriptableObject.putProperty(api, "getLoginHeader", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                return db?.getSourceLoginState(sourceId)?.loginHeader ?: ""
            }
        })

        // source.getLoginHeaderMap()
        ScriptableObject.putProperty(api, "getLoginHeaderMap", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val header = db?.getSourceLoginState(sourceId)?.loginHeader ?: ""
                val map = runCatching { Json.decodeFromString<Map<String, String>>(header) }.getOrDefault(emptyMap())
                return toJsValue(map, scope) ?: NativeObject().also { it.parentScope = scope }
            }
        })

        // source.putLoginHeader(header)
        ScriptableObject.putProperty(api, "putLoginHeader", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val h = args.firstOrNull()?.toString() ?: ""
                db?.saveSourceLoginHeader(sourceId, h)
                return true
            }
        })

        // source.removeLoginHeader()
        ScriptableObject.putProperty(api, "removeLoginHeader", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                db?.removeSourceLoginHeader(sourceId)
                return true
            }
        })

        // source.getVariable()
        ScriptableObject.putProperty(api, "getVariable", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                return db?.getSourceVariable(sourceId) ?: ""
            }
        })

        // source.setVariable(val)
        ScriptableObject.putProperty(api, "setVariable", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val v = args.firstOrNull()?.toString() ?: ""
                db?.saveSourceVariable(sourceId, v)
                return v
            }
        })

        // source.put(key, val)
        ScriptableObject.putProperty(api, "put", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val k = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                val v = args.getOrNull(1)?.toString()
                db?.saveSourceKv(sourceId, k, v)
                return v ?: Context.getUndefinedValue()
            }
        })

        // source.get(key)
        ScriptableObject.putProperty(api, "get", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val k = args.firstOrNull()?.toString() ?: return Context.getUndefinedValue()
                return db?.getSourceKv(sourceId, k) ?: Context.getUndefinedValue()
            }
        })
    }

    private fun createCookieBridge(
        scope: Scriptable,
        sourceId: String,
        db: Database?,
    ): NativeObject = NativeObject().also { api ->
        api.parentScope = scope

        // cookie.getCookie(url)
        ScriptableObject.putProperty(api, "getCookie", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.firstOrNull()?.toString() ?: return ""
                return db?.getSourceCookie(sourceId, url) ?: ""
            }
        })

        // cookie.setCookie(url, cookie)
        val setFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.getOrNull(0)?.toString() ?: return ""
                val c = args.getOrNull(1)?.toString() ?: return ""
                db?.setSourceCookie(sourceId, url, c)
                return c
            }
        }
        ScriptableObject.putProperty(api, "setCookie", setFn)
        ScriptableObject.putProperty(api, "replaceCookie", setFn)
        ScriptableObject.putProperty(api, "setWebCookie", setFn)

        // cookie.removeCookie(url)
        ScriptableObject.putProperty(api, "removeCookie", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.firstOrNull()?.toString() ?: return true
                db?.removeSourceCookie(sourceId, url)
                return true
            }
        })

        // cookie.getKey(url, key)
        ScriptableObject.putProperty(api, "getKey", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.getOrNull(0)?.toString() ?: return ""
                val key = args.getOrNull(1)?.toString() ?: return ""
                val cookieStr = db?.getSourceCookie(sourceId, url) ?: ""
                val map = parseCookieString(cookieStr)
                return map[key] ?: ""
            }
        })

        // cookie.mapToCookie(map)
        ScriptableObject.putProperty(api, "mapToCookie", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val arg = args.firstOrNull() ?: return ""
                val map = parseJsMapOrJson(arg)
                return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        })
    }

    private fun createJavaBridge(
        scope: Scriptable,
        execContext: JsExecutionContext?,
    ): NativeObject = NativeObject().also { api ->
        api.parentScope = scope

        // java.ajax(url)
        ScriptableObject.putProperty(api, "ajax", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.firstOrNull()?.toString() ?: return ""
                if (url.startsWith("data:text/html;base64,")) {
                    val encoded = url.removePrefix("data:text/html;base64,")
                    return runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault("")
                }
                return try {
                    runner?.fetch(url, execContext?.sourceId, execContext?.database) ?: ""
                } catch (error: Throwable) {
                    throw normalizeScriptThrowable(error)
                }
            }
        })

        // java.post(url, body, headers)
        ScriptableObject.putProperty(api, "post", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.getOrNull(0)?.toString() ?: return ""
                val body = args.getOrNull(1)?.toString() ?: ""
                val headers = args.getOrNull(2)?.let { parseJsMapOrJson(it) } ?: emptyMap()
                // 严禁用 mapOf(...)+encodeToString：其静态类型是 Map<String, Any>，
                // kotlinx 会抛 "Serializer for class 'Any' is not found"，
                // 且该异常位于归一化 try/catch 之外 ⇒ 桥接静默返回 null。
                val fullUrl = "$url,${buildUrlOptionsJson("POST", body, headers)}"
                return try {
                    runner?.fetch(fullUrl, execContext?.sourceId, execContext?.database) ?: ""
                } catch (error: Throwable) {
                    throw normalizeScriptThrowable(error)
                }
            }
        })

        // java.get(url[, headers]) —— HTTP GET
        // 注意：此方法**不能**再被同名属性覆盖（历史上 748 行的 session store 曾把
        // 它整个顶掉，导致 java.get 静默失效、永远返回 session 值或 undefined）。
        // 两种语义改为在同一函数内按参数形态分派：http(s) 开头视为 URL 请求，
        // 否则视为读取 session store（保持 java.put 的既有配对语义）。
        ScriptableObject.putProperty(api, "get", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val first = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                val isHttp = first.startsWith("http://", ignoreCase = true) ||
                    first.startsWith("https://", ignoreCase = true)
                if (!isHttp) {
                    return sessionStore[first] ?: Context.getUndefinedValue()
                }
                val headers = args.getOrNull(1)?.let { parseJsMapOrJson(it) } ?: emptyMap()
                val fullUrl = "$first,${buildUrlOptionsJson("GET", null, headers)}"
                return try {
                    runner?.fetch(fullUrl, execContext?.sourceId, execContext?.database) ?: ""
                } catch (error: Throwable) {
                    throw normalizeScriptThrowable(error)
                }
            }
        })

        // java.toast(msg) / java.longToast(msg)
        val toastFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val msg = args.firstOrNull()?.toString()?.trim() ?: return Context.getUndefinedValue()
                if (msg.isNotBlank()) {
                    execContext?.toastMessages?.add(msg)
                }
                return Context.getUndefinedValue()
            }
        }
        ScriptableObject.putProperty(api, "toast", toastFn)
        ScriptableObject.putProperty(api, "longToast", toastFn)

        // java.log(msg) / java.log(tag, msg)
        //
        // ⚠️ 必须**回显入参**（与 Legado `help/JsExtensions.kt` 的 `fun log(msg: Any?): Any? { …; return msg }` 一致），
        // 不能返回 undefined：Legado 生态高频惯用法是 `java.ajax(java.log(url))` ——
        // 把 log 当成「打印并透传」的管道用。返回 undefined 会让 `java.ajax("undefined")` 走到
        // parseUri 抛异常，而该异常被 eval 的 catch 吞掉 ⇒ 规则求值整体返回 null ⇒ **静默 0 条**。
        // 真实 RSS 源「大灰狼书荒广场」的 ruleArticles 正是这样写的（实测定位）。
        ScriptableObject.putProperty(api, "log", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val first = args.firstOrNull() ?: return Context.getUndefinedValue()
                // 双参形态 log(tag, msg)：Legado 记录 "tag: msg" 后回显第二个参数
                return if (args.size >= 2) args[1] ?: Context.getUndefinedValue() else first
            }
        })

        // java.startBrowser(url, title) / java.startBrowserAwait(url, title) / java.openWeb(url)
        val browserFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.firstOrNull()?.toString() ?: ""
                execContext?.openUrl = url
                val resObj = NativeObject()
                resObj.parentScope = scope
                ScriptableObject.putProperty(resObj, "url", url)
                ScriptableObject.putProperty(resObj, "body", "")
                return resObj
            }
        }
        ScriptableObject.putProperty(api, "startBrowser", browserFn)
        ScriptableObject.putProperty(api, "startBrowserAwait", browserFn)
        ScriptableObject.putProperty(api, "openWeb", browserFn)
        // 聚合类书源常用的其它打开网页别名
        ScriptableObject.putProperty(api, "showBrowser", browserFn)
        ScriptableObject.putProperty(api, "showReadingBrowser", browserFn)
        ScriptableObject.putProperty(api, "startBrowserDp", browserFn)

        // java.getWebViewUA()：部分书源用它给站点请求伪装 WebView 的 UA
        ScriptableObject.putProperty(api, "getWebViewUA", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any =
                "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230805.001; wv) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36"
        })

        // java.copyText(text)
        ScriptableObject.putProperty(api, "copyText", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                execContext?.copyText = args.firstOrNull()?.toString() ?: ""
                return Context.getUndefinedValue()
            }
        })

        // java.upLoginData(data)
        ScriptableObject.putProperty(api, "upLoginData", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val arg = args.firstOrNull()
                if (arg != null) {
                    val map = parseJsMapOrJson(arg)
                    execContext?.initialLoginInfo?.putAll(map)
                }
                execContext?.reRenderUi = true
                return Context.getUndefinedValue()
            }
        })

        // java.reLoginView(deltaUp) / java.reUiView()
        val reUiFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                execContext?.reRenderUi = true
                return Context.getUndefinedValue()
            }
        }
        ScriptableObject.putProperty(api, "reLoginView", reUiFn)
        ScriptableObject.putProperty(api, "reUiView", reUiFn)
        ScriptableObject.putProperty(api, "refreshExplore", reUiFn)
        ScriptableObject.putProperty(api, "refreshBookInfo", reUiFn)
        ScriptableObject.putProperty(api, "refreshBookToc", reUiFn)
        ScriptableObject.putProperty(api, "refreshContent", reUiFn)

        // java.base64Decode(str)
        ScriptableObject.putProperty(api, "base64Decode", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                return runCatching {
                    val clean = input.trim()
                    val bytes = try {
                        Base64.getDecoder().decode(clean)
                    } catch (_: Exception) {
                        Base64.getUrlDecoder().decode(clean)
                    }
                    String(bytes, Charsets.UTF_8)
                }.getOrDefault("")
            }
        })

        // java.base64Encode(str)
        ScriptableObject.putProperty(api, "base64Encode", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                return Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
            }
        })

        // java.hexDecodeToString(hex) / java.hexEncodeToString(str)
        // 聚合类书源用 data:;hex,... 之类的载荷在搜索→详情→目录→正文之间传递参数
        ScriptableObject.putProperty(api, "hexDecodeToString", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                val clean = input.trim().removePrefix("0x")
                // 载荷可能是十六进制，也可能已经被上层解码成普通文本（如 JSON），
                // 非十六进制时原样返回，避免把正确的数据破坏成乱码。
                if (clean.isEmpty() || clean.length % 2 != 0 || !clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                    return input
                }
                return runCatching {
                    String(ByteArray(clean.length / 2) { index ->
                        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                    }, Charsets.UTF_8)
                }.getOrDefault(input)
            }
        })
        ScriptableObject.putProperty(api, "hexEncodeToString", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                return input.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            }
        })

        // java.md5Encode(str) / java.md5(str)
        val md5Fn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                return MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }
        }
        ScriptableObject.putProperty(api, "md5Encode", md5Fn)
        ScriptableObject.putProperty(api, "md5", md5Fn)

        // java.md5Encode16(str)
        ScriptableObject.putProperty(api, "md5Encode16", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                val full = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                return if (full.length >= 24) full.substring(8, 24) else full
            }
        })

        // java.randomUUID()
        ScriptableObject.putProperty(api, "randomUUID", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                return UUID.randomUUID().toString()
            }
        })

        // ------------------------------------------------------------------
        // 对称加解密族：Legado 书源用它们解密目录/正文地址
        //
        // 实测（SESSION-026）：书源 `api.jmlldsc.com` 的 chapterUrl 规则是
        //   $.path@js:java.aesBase64DecodeToString(result,"f041c49714d39908","AES/CBC/PKCS5Padding","0123456789abcdef")
        // 沙箱**没有**这个 API ⇒ JS 抛 ReferenceError ⇒ 每一章都被
        // `mapIndexedNotNull` 丢掉 ⇒ 目录 0 章、正文自然取不到。
        // 补上后同一密文可正确解出 `http://api.lemiyigou.com/697/697604/75510.json`。
        // ------------------------------------------------------------------

        /** Legado 语义：key/iv 按**原始字节**使用（不是 hex/base64 解码后的字节）。 */
        fun cryptoKeyBytes(raw: String?, algorithm: String): ByteArray {
            val text = raw ?: ""
            return when {
                text.isEmpty() -> ByteArray(0)
                // 32/48/64 位 hex 且长度匹配常见密钥长度时按 hex 解释
                text.matches(Regex("^[0-9a-fA-F]+$")) && algorithm.startsWith("AES") &&
                    text.length in setOf(32, 48, 64) -> text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                else -> text.toByteArray(Charsets.UTF_8)
            }
        }

        fun runCrypto(mode: Int, data: ByteArray, key: String?, transformation: String?, iv: String?): ByteArray {
            val t = transformation?.takeIf { it.isNotBlank() } ?: "AES/CBC/PKCS5Padding"
            val algorithm = t.substringBefore('/')
            val spec = Cipher.getInstance(t)
            val keySpec = SecretKeySpec(cryptoKeyBytes(key, algorithm), algorithm)
            val ivBytes = iv?.takeIf { it.isNotBlank() }?.let { cryptoKeyBytes(it, algorithm) }
            if (ivBytes != null && ivBytes.isNotEmpty()) {
                spec.init(mode, keySpec, IvParameterSpec(ivBytes))
            } else {
                spec.init(mode, keySpec)
            }
            return spec.doFinal(data)
        }

        // java.aesBase64DecodeToString(base64Data, key, transformation, iv)
        ScriptableObject.putProperty(api, "aesBase64DecodeToString", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val data = args.getOrNull(0)?.toString() ?: return ""
                val key = args.getOrNull(1)?.toString()
                val transformation = args.getOrNull(2)?.toString()
                val iv = args.getOrNull(3)?.toString()
                return runCatching {
                    val decoded = runCatching { Base64.getDecoder().decode(data) }
                        .getOrElse { Base64.getMimeDecoder().decode(data) }
                    String(runCrypto(Cipher.DECRYPT_MODE, decoded, key, transformation, iv), Charsets.UTF_8)
                }.getOrElse { error ->
                    throw RuntimeException("aesBase64DecodeToString 失败: ${error.message}")
                }
            }
        })

        // java.aesBase64EncodeToString(plainText, key, transformation, iv)
        ScriptableObject.putProperty(api, "aesBase64EncodeToString", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val text = args.getOrNull(0)?.toString() ?: return ""
                val key = args.getOrNull(1)?.toString()
                val transformation = args.getOrNull(2)?.toString()
                val iv = args.getOrNull(3)?.toString()
                return runCatching {
                    val encrypted = runCrypto(Cipher.ENCRYPT_MODE, text.toByteArray(Charsets.UTF_8), key, transformation, iv)
                    Base64.getEncoder().encodeToString(encrypted)
                }.getOrElse { error ->
                    throw RuntimeException("aesBase64EncodeToString 失败: ${error.message}")
                }
            }
        })

        // java.aesDecodeToString(base64Data, key, transformation, iv) —— 与上面同语义，Legado 两种命名都有
        val aesDecodeAlias = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val data = args.getOrNull(0)?.toString() ?: return ""
                val key = args.getOrNull(1)?.toString()
                val transformation = args.getOrNull(2)?.toString()
                val iv = args.getOrNull(3)?.toString()
                return runCatching {
                    val decoded = runCatching { Base64.getDecoder().decode(data) }
                        .getOrElse { Base64.getMimeDecoder().decode(data) }
                    String(runCrypto(Cipher.DECRYPT_MODE, decoded, key, transformation, iv), Charsets.UTF_8)
                }.getOrElse { error -> throw RuntimeException("aesDecodeToString 失败: ${error.message}") }
            }
        }
        ScriptableObject.putProperty(api, "aesDecodeToString", aesDecodeAlias)
        ScriptableObject.putProperty(api, "aesBase64Decode", aesDecodeAlias)

        // java.createSymmetricCrypto(transformation, key, iv) -> { encrypt, decrypt }
        ScriptableObject.putProperty(api, "createSymmetricCrypto", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val transformation = args.getOrNull(0)?.toString()
                val key = args.getOrNull(1)?.toString()
                val iv = args.getOrNull(2)?.toString()
                val obj = NativeObject()
                obj.parentScope = scope
                ScriptableObject.putProperty(obj, "encrypt", object : BaseFunction() {
                    override fun call(cx2: Context, s2: Scriptable, t2: Scriptable, a2: Array<out Any?>): Any {
                        val text = a2.firstOrNull()?.toString() ?: return ""
                        return Base64.getEncoder().encodeToString(
                            runCrypto(Cipher.ENCRYPT_MODE, text.toByteArray(Charsets.UTF_8), key, transformation, iv)
                        )
                    }
                })
                ScriptableObject.putProperty(obj, "decrypt", object : BaseFunction() {
                    override fun call(cx2: Context, s2: Scriptable, t2: Scriptable, a2: Array<out Any?>): Any {
                        val data = a2.firstOrNull()?.toString() ?: return ""
                        val decoded = runCatching { Base64.getDecoder().decode(data) }
                            .getOrElse { Base64.getMimeDecoder().decode(data) }
                        return String(runCrypto(Cipher.DECRYPT_MODE, decoded, key, transformation, iv), Charsets.UTF_8)
                    }
                })
                return obj
            }
        })

        // java.deviceID() / java.androidId()
        val deviceIdFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                return "legado-headless-client-001"
            }
        }
        ScriptableObject.putProperty(api, "deviceID", deviceIdFn)
        ScriptableObject.putProperty(api, "androidId", deviceIdFn)

        // java.timeFormat(timestamp)
        ScriptableObject.putProperty(api, "timeFormat", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val arg = args.firstOrNull() ?: return ""
                val millis = when (arg) {
                    is Number -> arg.toLong()
                    is Date -> arg.time
                    else -> arg.toString().toLongOrNull() ?: System.currentTimeMillis()
                }
                return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
            }
        })

        // java.getString(rule)
        ScriptableObject.putProperty(api, "getString", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val rule = args.firstOrNull()?.toString() ?: return ""
                val src = ScriptableObject.getProperty(scope, "src")?.toString() ?: ""
                return NodeValue.document(src).value(rule) ?: ""
            }
        })

        // java.put(key, val) / java.get(key)
        // 注意：`java.get` 已在上面统一定义（按参数形态分派 HTTP GET / session 读取），
        // 这里**严禁**再次 putProperty("get", ...) —— 那会静默覆盖 HTTP GET 能力。
        ScriptableObject.putProperty(api, "put", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val key = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                val value = args.getOrNull(1) ?: return Context.getUndefinedValue()
                sessionStore[key] = value
                return value
            }
        })

        // java.toNumChapter(s) —— 把「第123章 / 123 / 一百二十三」之类标题归一成数字。
        // 4 个书源用到（SESSION-026 扫描），缺失会让依赖它排序/对齐的规则抛 ReferenceError。
        ScriptableObject.putProperty(api, "toNumChapter", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val text = args.firstOrNull()?.toString()?.trim() ?: return 0
                Regex("\\d+").find(text)?.value?.toIntOrNull()?.let { return it }
                // 中文数字（仅支持常见的一~九百九十九，够覆盖章节标题）
                return chineseNumberToInt(text)
            }
        })

        // java.t2s(text) —— 繁体转简体（10 处使用）。服务端无完整词表，
        // 用一个可用的映射表覆盖常见字；未命中则原样返回，绝不抛错。
        ScriptableObject.putProperty(api, "t2s", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val text = args.firstOrNull()?.toString() ?: return ""
                return traditionalToSimplified(text)
            }
        })

        // java.encodeURI(text)
        ScriptableObject.putProperty(api, "encodeURI", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val text = args.firstOrNull()?.toString() ?: return ""
                return java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
            }
        })

        // java.connect(url) —— 仅建连探测，返回空串即视为可达；失败抛错（与 Legado 语义一致）。
        ScriptableObject.putProperty(api, "connect", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.firstOrNull()?.toString() ?: return ""
                return try {
                    runner?.fetch(url, execContext?.sourceId, execContext?.database) ?: ""
                } catch (error: Throwable) {
                    throw normalizeScriptThrowable(error)
                }
            }
        })

        // java.getElements(rule) / java.getElement(rule) —— 在当前正文上按规则取元素
        val getElementsFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val rule = args.firstOrNull()?.toString() ?: return Context.getUndefinedValue()
                val src = ScriptableObject.getProperty(scope, "src")?.toString()
                    ?: ScriptableObject.getProperty(scope, "result")?.toString() ?: ""
                val value = NodeValue.document(src).value(rule) ?: ""
                val arr = cx.newArray(scope, value.split("\n").filter { it.isNotBlank() }.toTypedArray())
                return arr
            }
        }
        ScriptableObject.putProperty(api, "getElements", getElementsFn)
        ScriptableObject.putProperty(api, "getElement", getElementsFn)
    }

    /** 中文数字转整数，覆盖「一」到「九百九十九」以及「十/十一/二十」等常见章节写法。 */
    private fun chineseNumberToInt(text: String): Int {
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        var result = 0
        var section = 0
        var seen = false
        for (ch in text) {
            when {
                ch in digits -> { section = digits.getValue(ch); seen = true }
                ch == '十' -> {
                    result += (if (section == 0) 1 else section) * 10
                    section = 0
                    seen = true
                }
                ch == '百' -> {
                    result += (if (section == 0) 1 else section) * 100
                    section = 0
                    seen = true
                }
                else -> if (seen) break
            }
        }
        return result + section
    }

    /**
     * 繁体 → 简体。
     *
     * 只做**字符级**映射（不含词组消歧，如「後」→「后」、「發」→「发」），
     * 这对目录/正文的显示足够；未收录的字原样保留，绝不因转换失败而中断规则。
     */
    private fun traditionalToSimplified(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(TRADITIONAL_TO_SIMPLIFIED[ch] ?: ch)
        }
        return sb.toString()
    }

        /**
         * 常用繁体字 → 简体字映射。
         *
         * 刻意保持精简：只收录书源实际会遇到的常见字，避免引入一个庞大的第三方词表
         * （复杂度惩罚）。未命中的字原样输出。
         */
        val TRADITIONAL_TO_SIMPLIFIED: Map<Char, Char> = buildMap {
            val pairs = "後后|發发|頭头|們们|個个|這这|說说|會会|時时|對对|開开|關关|來来|過过|為为|與与|長长|門门|問问|間间|實实|現现|點点|電电|話话|聽听|讀读|書书|筆笔|學学|習习|體体|見见|覺觉|場场|車车|馬马|鳥鸟|魚鱼|龍龙|風风|雲云|雨雨|飛飞|機机|氣气|萬万|億亿|兩两|買买|賣卖|錢钱|銀银|鐵铁|銅铜|東东|絲丝|紅红|綠绿|藍蓝|黃黄|兒儿|幾几|當当|經经|結结|給给|統统|維维|線线|練练|織织|總总|級级|紀纪|約约|純纯|紙纸|終终|組组|細细|紹绍|絶绝|統统|斷断|對对|將将|專专|尋寻|導导|層层|屬属|歲岁|歷历|殘残|殺杀|毎每|畢毕|異异|畫画|當当|疊叠|盡尽|監监|蓋盖|盤盘|眾众|著着|藍蓝|藝艺|藥药|處处|號号|蟲虫|術术|衝冲|裝装|複复|覺觉|觀观|計计|討讨|訓训|記记|講讲|許许|論论|設设|訪访|証证|評评|詞词|試试|詩诗|誠诚|誤误|調调|談谈|請请|論论|諾诺|謀谋|謝谢|議议|護护|讀读|變变|讓让|豐丰|豬猪|貝贝|貞贞|負负|財财|貢贡|貧贫|貨货|販贩|貪贪|購购|貫贯|責责|貴贵|賀贺|資资|賓宾|賞赏|賢贤|賤贱|賬账|賭赌|贊赞|贏赢|贛赣|趙赵|蹺跷|車车|軌轨|軍军|軒轩|軟软|軸轴|較较|載载|輔辅|輕轻|輛辆|輝辉|輩辈|輪轮|輯辑|輸输|轄辖|轉转|辦办|辭辞|辯辩|農农|遠远|適适|選选|遞递|遷迁|遺遗|鄉乡|鄭郑|醬酱|釋释|鐘钟|鋼钢|錄录|錢钱|鍋锅|鎮镇|鏈链|鎖锁|鏡镜|鐘钟|鐵铁|鑰钥|長长|閉闭|開开|閏闰|閑闲|閱阅|闆板|關关|陽阳|陰阴|陳陈|陸陆|隊队|階阶|隨随|險险|隱隐|雖虽|雙双|雜杂|雞鸡|離离|難难|雲云|電电|霧雾|靈灵|靜静|響响|頁页|頂顶|項项|順顺|須须|預预|頑顽|頓顿|頗颇|領领|頭头|頻频|顆颗|題题|顏颜|願愿|類类|顧顾|顯显|風风|飛飞|飯饭|飲饮|飾饰|飽饱|養养|餐餐|館馆|首首|香香|馬马|駕驾|騎骑|騰腾|驅驱|驗验|驚惊|骨骨|體体|高高|髮发|鬥斗|魚鱼|鮮鲜|鳥鸟|鳳凤|鴨鸭|鴻鸿|鵝鹅|鷹鹰|鹽盐|麗丽|麥麦|黃黄|點点|黨党|鼓鼓|鼠鼠|齒齿|龍龙|龜龟";
            for (entry in pairs.split('|')) {
                if (entry.length == 2) put(entry[0], entry[1])
            }
        }

    /**
     * 构造 Legado 的 URL 选项 JSON（拼接在 URL 之后的 `,{...}` 部分）。
     *
     * 必须显式构造 `JsonObject`，**严禁**写成
     * `Json.encodeToString(mapOf("method" to ..., "body" to ..., "headers" to ...))`：
     * 该 map 的静态类型是 `Map<String, Any>`，kotlinx 无法为多态 value 解析序列化器，
     * 会抛 `SerializationException: Serializer for class 'Any' is not found`。
     * 由于该语句位于归一化 try/catch 之外，异常会让桥接**静默返回 null**，
     * 表现为 `java.post` / `java.get` 毫无反应且无任何错误提示。
     */
    private fun buildUrlOptionsJson(
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): String = buildJsonObject {
        put("method", JsonPrimitive(method))
        if (body != null) put("body", JsonPrimitive(body))
        put(
            "headers",
            buildJsonObject { headers.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        )
    }.toString()

    private fun parseJsMapOrJson(value: Any?): Map<String, String> {
        if (value == null) return emptyMap()
        if (value is NativeObject) {
            val result = mutableMapOf<String, String>()
            value.ids.forEach { id ->
                val k = id.toString()
                val v = ScriptableObject.getProperty(value, k)
                if (v != null && v != Context.getUndefinedValue()) {
                    result[k] = v.toString()
                }
            }
            return result
        }
        if (value is Map<*, *>) {
            return value.entries.filter { it.key != null && it.value != null }
                .associate { it.key.toString() to it.value.toString() }
        }
        val str = value.toString().trim()
        if (str.startsWith("{")) {
            return runCatching { Json.decodeFromString<Map<String, String>>(str) }.getOrDefault(emptyMap())
        }
        return emptyMap()
    }

    private fun parseCookieString(cookieStr: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        cookieStr.split(';').forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun toJsValue(value: Any?, scope: Scriptable): Any? = when (value) {
        null -> null
        is Map<*, *> -> NativeObject().also { obj ->
            obj.parentScope = scope
            value.forEach { (k, v) -> if (k is String) ScriptableObject.putProperty(obj, k, toJsValue(v, scope)) }
        }
        is List<*> -> Context.getCurrentContext().newArray(scope, value.map { toJsValue(it, scope) }.toTypedArray())
        else -> Context.javaToJS(value, scope)
    }

    /**
     * `cache` 桥接对象（Legado `CacheManager` 语义）。
     *
     * 真实 RSS 源与书源都会用到：`cache.getFromMemory(key)` / `cache.putMemory(key, value)` /
     * `cache.get(key)` / `cache.put(key, value)`。
     * 实测参照数据里「大灰狼书荒广场」的 `sourceUrl` 就是
     * `http@js:eval(String(cache.getFromMemory('yckdm')))`，而「源仓库」的 `header` 规则
     * 用 `cache.putMemory('yckdm', …)` 预热它 —— 缺这一层桥会让这类源**静默**失效。
     *
     * 分层语义（对齐 Legado）：
     * - `getFromMemory`/`putMemory` 走本沙箱实例的进程内缓存，**存原始 JS 值**（不落库）；
     * - `get`/`put` 落到书源级 KV（`source_kv`），表达式求值间可跨进程存活。
     *
     * ⚠️ 内存层刻意**不做字符串往返**：把对象 `JSON.stringify` 再 `NativeJSON.parse` 回来后，
     * 拿到的 Rhino 映射在**属性访问**上不可靠（实测 `cache.getFromMemory('k').a` 得到
     * `undefined`，而 `String(...)` 却是 `{"a":1}`）。存原始值即可完全避免这个问题，
     * 也天然保留 `typeof` 语义。落库层（`get`/`put`）只能存文本，故按 JSON 还原并做兜底。
     */
    private fun createCacheBridge(
        scope: Scriptable,
        sourceId: String,
        db: Database?,
    ): NativeObject = NativeObject().also { api ->
        api.parentScope = scope

        // cache.putMemory(key, value)
        ScriptableObject.putProperty(api, "putMemory", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val k = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                val raw = args.getOrNull(1) ?: return Context.getUndefinedValue()
                memoryCache["$sourceId\u0000$k"] = raw
                return raw
            }
        })

        // cache.getFromMemory(key)
        ScriptableObject.putProperty(api, "getFromMemory", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val k = args.firstOrNull()?.toString() ?: return Context.getUndefinedValue()
                if (!memoryCache.containsKey("$sourceId\u0000$k")) return Context.getUndefinedValue()
                return memoryCache["$sourceId\u0000$k"] ?: Context.getUndefinedValue()
            }
        })

        // cache.get(key)
        ScriptableObject.putProperty(api, "get", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val k = args.firstOrNull()?.toString() ?: return Context.getUndefinedValue()
                val stored = db?.getSourceKv(sourceId, "cache:$k") ?: return Context.getUndefinedValue()
                return storageToJs(cx, scope, stored)
            }
        })

        // cache.put(key, value)
        ScriptableObject.putProperty(api, "put", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val k = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                val raw = args.getOrNull(1) ?: return Context.getUndefinedValue()
                db?.saveSourceKv(sourceId, "cache:$k", jsValueToStorage(scope, raw))
                return raw
            }
        })
    }

    /** 把 JS 值序列化成可持久化的字符串（Rhino 原生对象走 JSON，其余走 toString）。 */
    private fun jsValueToStorage(scope: Scriptable, value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> runCatching {
            Context.getCurrentContext()?.let { cx -> NativeJSON.stringify(cx, scope, value, null, null)?.toString() }
        }.getOrNull() ?: value.toString()
    }

    /**
     * 落库值回读：只有**结构化**文本（`{`/`[` 开头）才还原成 JS 原生值。
     *
     * 刻意**不**还原 `"42"` / `"true"` 这类标量：Legado 的 `cache.put('k','42')` 期望取回
     * 字符串 `'42'`，转成数字会让 `typeof` 与严格比较悄悄变味。还原失败一律原样返回字符串。
     *
     * 实现上刻意走「Java 容器 → [toJsValue] → `JSON.stringify` → 沙箱内 `JSON.parse`」
     * 这一圈，而不是直接返回 [toJsValue] 的产物，原因是实测踩到的两个坑：
     * 1. `NativeJSON.parse` 在当前 Rhino（1.8.0）+ 该调用形态下**直接返回原字符串**，
     *    于是「还原」静默失效，表现为 `cache.get('obj').a` 恒为 undefined；
     * 2. `toJsValue` 造出的 `NativeObject` 是**无原型**的壳：属性读写与 `JSON.stringify` 都正常，
     *    但 `String(obj)` 会抛 `TypeError: 未找到对象默认值`（真实源里 `String(cache.get(...))`
     *    与 `eval(String(...))` 都是常见写法）。
     * 在沙箱内用 `JSON.parse` 生成才是**真正的** JS 对象，上述两种语义都正确。
     */
    private fun storageToJs(cx: Context, scope: Scriptable, stored: String): Any {
        val trimmed = stored.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return stored
        val parsed = runCatching { com.jayway.jsonpath.JsonPath.parse(stored).json<Any>() }.getOrNull() ?: return stored
        val shell = runCatching { toJsValue(parsed, scope) }.getOrNull() ?: return stored
        val jsonText = runCatching { NativeJSON.stringify(cx, scope, shell, null, null)?.toString() }.getOrNull()
            ?: return stored
        // 沙箱内 JSON.parse 一定是原生对象；失败则退回文本，绝不抛错打断规则
        return runCatching { cx.evaluateString(scope, "JSON.parse(${quoteForJavaScript(jsonText)})", "cache.js", 1, null) }
            .getOrDefault(stored)
    }
}

