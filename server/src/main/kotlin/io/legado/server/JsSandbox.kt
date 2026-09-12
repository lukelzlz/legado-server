package io.legado.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        val cleanScript = if (library == null) rawScript else "$library\n$rawScript"

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
            }

            // Legado 的 book 对象：书源 JS 会用 book.getVariable('custom') 读取书籍级变量
            ScriptableObject.putProperty(scope, "book", createBookBridge(scope, context))
            // chapter 对象：正文规则会引用 chapter.index / chapter.title
            ScriptableObject.putProperty(scope, "chapter", createChapterBridge(scope, context))

            val result = cx.evaluateString(scope, cleanScript, "rule.js", 1, null)
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
                return runner?.fetch(url, execContext?.sourceId, execContext?.database) ?: ""
            }
        })

        // java.post(url, body, headers)
        ScriptableObject.putProperty(api, "post", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.getOrNull(0)?.toString() ?: return ""
                val body = args.getOrNull(1)?.toString() ?: ""
                val headers = args.getOrNull(2)?.let { parseJsMapOrJson(it) } ?: emptyMap()
                val opt = mapOf("method" to "POST", "body" to body, "headers" to headers)
                val fullUrl = "$url,${Json.encodeToString(opt)}"
                return runner?.fetch(fullUrl, execContext?.sourceId, execContext?.database) ?: ""
            }
        })

        // java.get(url, headers)
        ScriptableObject.putProperty(api, "get", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.getOrNull(0)?.toString() ?: return ""
                val headers = args.getOrNull(1)?.let { parseJsMapOrJson(it) } ?: emptyMap()
                val opt = mapOf("method" to "GET", "headers" to headers)
                val fullUrl = "$url,${Json.encodeToString(opt)}"
                return runner?.fetch(fullUrl, execContext?.sourceId, execContext?.database) ?: ""
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

        // java.log(msg)
        ScriptableObject.putProperty(api, "log", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                return Context.getUndefinedValue()
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
        ScriptableObject.putProperty(api, "put", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val key = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                val value = args.getOrNull(1) ?: return Context.getUndefinedValue()
                sessionStore[key] = value
                return value
            }
        })
        ScriptableObject.putProperty(api, "get", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val key = args.firstOrNull()?.toString() ?: return Context.getUndefinedValue()
                return sessionStore[key] ?: Context.getUndefinedValue()
            }
        })
    }

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
}

