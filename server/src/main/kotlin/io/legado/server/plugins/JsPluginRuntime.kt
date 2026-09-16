package io.legado.server.plugins

import io.legado.plugin.api.PluginApiException
import io.legado.plugin.api.PluginEvent
import io.legado.plugin.api.PluginEventListener
import io.legado.plugin.api.PluginRequest
import io.legado.plugin.api.PluginResponse
import io.legado.plugin.api.PluginRouteHandler
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Callable
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.ConsString
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.Base64
import java.util.concurrent.Callable as JavaCallable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one plugin's `server.js` inside a Rhino sandbox.
 *
 * Design notes:
 * - **One thread per plugin.** A Rhino scope is not thread-safe, so every script entry point is
 *   marshalled onto a single dedicated thread. That also serialises plugin code, which keeps
 *   plugin-authored state (module-level variables) consistent without asking authors to think
 *   about concurrency.
 * - **One dispatch entry point.** JavaScript calls `__legadoCall(name, args)`; [dispatch] maps the
 *   operation name onto a permission-checked [PluginHostContext] method. Adding a capability means
 *   adding a branch here plus a one-line binding in `prelude.js`.
 * - **No Java reflection.** `ClassShutter` denies every Java class, and the standard objects are the
 *   safe ones, so plugin scripts cannot reach the JVM.
 */
class JsPluginRuntime(
    private val plugin: LoadedPlugin,
    private val context: PluginHostContext,
    private val scriptSource: String,
) : AutoCloseable {

    private val threadName = "legado-plugin-${plugin.id}"
    private val handles = AtomicInteger()
    private val scheduled = ConcurrentHashMap<Int, io.legado.plugin.api.PluginTask>()

    @Volatile
    private var pluginThread: Thread? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply {
            isDaemon = true
            pluginThread = this
        }
    }

    fun start() {
        onPluginThread {
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                cx.setClassShutter(ClassShutter { false })
                val scope = cx.initSafeStandardObjects()

                val hostCall = object : BaseFunction() {
                    override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                        val operation = PluginJsCodec.fromJs(args.getOrNull(0))?.toString()
                            ?: throw PluginApiException("__legadoCall 缺少操作名")
                        val raw = (args.getOrNull(1) as? NativeArray)
                            ?.let { array -> Array<Any?>(array.length.toInt()) { array.get(it, array) } }
                            ?: emptyArray()
                        return try {
                            PluginJsCodec.toJs(cx, s, dispatch(operation, raw))
                        } catch (error: Throwable) {
                            // Rhino must see a script-visible exception, otherwise the failure would
                            // surface as an opaque Java error inside plugin code.
                            throw Context.throwAsScriptRuntimeEx(error)
                        }
                    }
                }
                hostCall.parentScope = scope
                hostCall.prototype = ScriptableObject.getFunctionPrototype(scope)
                ScriptableObject.putProperty(scope, "__legadoCall", hostCall)

                val meta = NativeObject().also { obj ->
                    obj.parentScope = scope
                    ScriptableObject.putProperty(obj, "pluginId", plugin.id)
                    ScriptableObject.putProperty(obj, "pluginName", plugin.manifest.name)
                    ScriptableObject.putProperty(obj, "pluginVersion", plugin.manifest.version)
                    ScriptableObject.putProperty(obj, "apiVersion", PluginManifests.CURRENT_API_VERSION)
                }
                ScriptableObject.putProperty(scope, "__legadoMeta", meta)

                cx.evaluateString(scope, readPrelude(), "legado-prelude.js", 1, null)
                cx.evaluateString(scope, scriptSource, "${plugin.id}/server.js", 1, null)

                scriptScope = scope
            } finally {
                Context.exit()
            }
        }
    }

    /** Invokes a route handler registered from JavaScript. */
    fun invokeRoute(handler: Any?, request: PluginRequest): PluginResponse = onPluginThread {
        withContext { cx, scope ->
            val jsRequest = PluginJsCodec.toJs(cx, scope, requestToMap(request))
            PluginJsCodec.toResponse(cx, callFunction(cx, scope, handler, arrayOf(jsRequest)))
        }
    }

    /** Invokes an event listener registered from JavaScript. */
    fun invokeListener(handler: Any?, event: PluginEvent) = onPluginThread {
        withContext { cx, scope ->
            val payload = mapOf(
                "type" to event.type,
                "payload" to event.payload,
                "origin" to event.origin,
            )
            callFunction(cx, scope, handler, arrayOf(PluginJsCodec.toJs(cx, scope, payload)))
            Unit
        }
    }

    /** Invokes a scheduled task registered from JavaScript. */
    fun invokeTask(handler: Any?) = onPluginThread {
        withContext { cx, scope -> callFunction(cx, scope, handler, emptyArray()); Unit }
    }

    override fun close() {
        runCatching { onPluginThread { invokeGlobal("deactivate") } }
        scheduled.values.forEach { runCatching { it.cancel() } }
        scheduled.clear()
        executor.shutdownNow()
    }

    // --- plumbing --------------------------------------------------------------------------

    @Volatile
    private var scriptScope: Scriptable? = null

    private inline fun <T> withContext(block: (Context, Scriptable) -> T): T {
        val scope = scriptScope ?: throw PluginApiException("插件脚本尚未初始化")
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.setClassShutter(ClassShutter { false })
            return block(cx, scope)
        } finally {
            Context.exit()
        }
    }

    private fun callFunction(cx: Context, scope: Scriptable, function: Any?, args: Array<Any?>): Any? = when (function) {
        is Function -> function.call(cx, scope, scope, args)
        is Callable -> function.call(cx, scope, scope, args)
        else -> throw PluginApiException("期望一个 JavaScript 函数，实际收到 ${function?.javaClass?.simpleName ?: "null"}")
    }

    private fun invokeGlobal(functionName: String) {
        val scope = scriptScope ?: return
        val candidate = ScriptableObject.getProperty(scope, functionName)
        if (candidate == Scriptable.NOT_FOUND || candidate is Undefined) return
        withContext { cx, s -> callFunction(cx, s, candidate, emptyArray()) }
    }

    private fun <T> onPluginThread(block: () -> T): T {
        // Re-entrancy matters: a JS listener may be triggered synchronously while the plugin thread
        // is already executing (e.g. a route handler emitting an event). Submitting again would
        // deadlock on a single-threaded executor, so run inline instead.
        if (Thread.currentThread() === pluginThread) return block()
        val future = executor.submit(JavaCallable { block() })
        return try {
            future.get(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw PluginApiException("插件脚本执行超时（${EXECUTION_TIMEOUT_SECONDS} 秒）")
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            throw if (cause is RuntimeException) cause else PluginApiException("插件脚本执行失败: ${cause.message}", cause)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PluginApiException("插件脚本执行被中断")
        }
    }

    // --- host operations -------------------------------------------------------------------

    private fun dispatch(operation: String, args: Array<out Any?>): Any? = when (operation) {
        "log.debug", "log.info" -> { context.logger.info(string(args, 0, operation)); null }
        "log.warn" -> { context.logger.warn(string(args, 0, operation)); null }
        "log.error" -> { context.logger.error(string(args, 0, operation), null); null }

        "storage.get" -> context.storage.get(string(args, 0, operation))
        "storage.set" -> { context.storage.set(string(args, 0, operation), optionalString(args, 1)); null }
        "storage.remove" -> { context.storage.remove(string(args, 0, operation)); null }
        "storage.keys" -> context.storage.keys()
        "storage.clear" -> { context.storage.clear(); null }
        "storage.getJson" -> context.storage.getJson(string(args, 0, operation))
        "storage.setJson" -> { context.storage.setJson(string(args, 0, operation), optionalPlain(args, 1)); null }

        "settings.all" -> context.settings.all()
        "settings.update" -> context.settings.update(map(args, 0))
        "settings.replace" -> context.settings.replace(map(args, 0))
        "settings.get" -> context.settings.all()[string(args, 0, operation)] ?: optionalPlain(args, 1)

        "books.listShelf" -> context.books.listShelf()
        "books.getShelfItem" -> context.books.getShelfItem(string(args, 0, operation), string(args, 1, operation))
        "books.addToShelf" -> context.books.addToShelf(map(args, 0))
        "books.removeFromShelf" -> context.books.removeFromShelf(string(args, 0, operation), string(args, 1, operation))
        "books.setCompleted" -> context.books.setCompleted(string(args, 0, operation), string(args, 1, operation), bool(args, 2))
        "books.getProgress" -> context.books.getProgress(string(args, 0, operation), string(args, 1, operation))
        "books.saveProgress" -> context.books.saveProgress(map(args, 0))
        "books.getToc" -> context.books.getToc(string(args, 0, operation), string(args, 1, operation))
        "books.getCachedChapters" -> context.books.getCachedChapters(string(args, 0, operation), string(args, 1, operation))
        "books.listCachedChapterUrls" -> context.books.listCachedChapterUrls(string(args, 0, operation), string(args, 1, operation))
        "books.getCachedContent" -> context.books.getCachedContent(string(args, 0, operation), string(args, 1, operation), string(args, 2, operation))
        "books.requestOfflineCache" -> context.books.requestOfflineCache(string(args, 0, operation), string(args, 1, operation))
        "books.cancelOfflineCache" -> context.books.cancelOfflineCache(string(args, 0, operation), string(args, 1, operation))
        "books.search" -> context.books.search(string(args, 0, operation), string(args, 1, operation))
        "books.fetchDetails" -> context.books.fetchDetails(string(args, 0, operation), string(args, 1, operation))
        "books.fetchChapters" -> context.books.fetchChapters(string(args, 0, operation), string(args, 1, operation))
        "books.fetchContent" -> context.books.fetchContent(string(args, 0, operation), string(args, 1, operation), optionalString(args, 2))

        "sources.list" -> context.sources.list(optionalString(args, 0))
        "sources.get" -> context.sources.get(string(args, 0, operation))
        "sources.save" -> context.sources.save(string(args, 0, operation))
        "sources.remove" -> context.sources.remove(string(args, 0, operation))
        "sources.setEnabled" -> context.sources.setEnabled(string(args, 0, operation), bool(args, 1))
        "sources.export" -> context.sources.export(stringList(args, 0))

        "subscriptions.list" -> context.subscriptions.list(bool(args, 0))
        "subscriptions.add" -> context.subscriptions.add(string(args, 0, operation))
        "subscriptions.remove" -> context.subscriptions.remove(long(args, 0, operation))
        "subscriptions.refresh" -> context.subscriptions.refresh(long(args, 0, operation))
        "subscriptions.refreshAll" -> context.subscriptions.refreshAll()

        "covers.cacheUrl" -> context.covers.cacheUrl(string(args, 0, operation))
        "covers.contentType" -> context.covers.contentType(string(args, 0, operation))
        "covers.delete" -> { context.covers.delete(string(args, 0, operation)); null }

        "http.request" -> context.http.request(
            method = string(args, 0, operation),
            url = string(args, 1, operation),
            headers = map(args, 2).mapValues { (_, value) -> value?.toString() ?: "" },
            body = optionalString(args, 3)?.toByteArray(Charsets.UTF_8),
            timeoutMs = long(args, 4, operation, 15_000L),
        ).let { mapOf("status" to it.status, "headers" to it.headers, "body" to it.bodyText) }

        "http.get" -> context.http.get(string(args, 0, operation), map(args, 1).mapValues { (_, v) -> v?.toString() ?: "" })
            .let { mapOf("status" to it.status, "headers" to it.headers, "body" to it.bodyText) }

        "http.post" -> context.http.post(string(args, 0, operation), optionalString(args, 1)?.toByteArray(Charsets.UTF_8), optionalString(args, 2))
            .let { mapOf("status" to it.status, "headers" to it.headers, "body" to it.bodyText) }

        "auth.verifyAdminPassword" -> context.auth.verifyAdminPassword(string(args, 0, operation))

        "route.register" -> {
            val method = string(args, 0, operation)
            val path = string(args, 1, operation)
            val isPublic = bool(args, 2)
            val handler = args.getOrNull(3)
                ?: throw PluginApiException("route.register 缺少处理函数")
            val routeHandler = PluginRouteHandler { request -> invokeRoute(handler, request) }
            if (isPublic) context.routePublic(method, path, routeHandler) else context.route(method, path, routeHandler)
            null
        }

        "events.on" -> {
            val eventType = string(args, 0, operation)
            val handler = args.getOrNull(1) ?: throw PluginApiException("events.on 缺少处理函数")
            context.on(eventType, PluginEventListener { event -> invokeListener(handler, event) })
            null
        }

        "events.emit" -> {
            context.events.emit(string(args, 0, operation), map(args, 1))
            null
        }

        "schedule.register" -> {
            val initialDelay = long(args, 0, operation, 0L)
            val period = long(args, 1, operation)
            val handler = args.getOrNull(2) ?: throw PluginApiException("schedule.register 缺少任务函数")
            val handle = handles.incrementAndGet()
            val task = context.schedule(initialDelay, period) { invokeTask(handler) }
            scheduled[handle] = task
            handle
        }

        "schedule.cancel" -> {
            val handle = long(args, 0, operation).toInt()
            scheduled.remove(handle)?.let { it.cancel(); true } ?: false
        }

        else -> throw PluginApiException("未知的宿主操作: $operation")
    }

    // --- argument helpers ------------------------------------------------------------------

    private fun string(args: Array<out Any?>, index: Int, operation: String): String =
        optionalString(args, index) ?: throw PluginApiException("$operation 缺少第 ${index + 1} 个参数")

    private fun optionalString(args: Array<out Any?>, index: Int): String? {
        val value = optionalPlain(args, index) ?: return null
        val text = value.toString()
        return text
    }

    private fun optionalPlain(args: Array<out Any?>, index: Int): Any? {
        val raw = args.getOrNull(index) ?: return null
        if (raw is Undefined || raw === Scriptable.NOT_FOUND) return null
        if (raw is Function || raw is Callable) return null
        return PluginJsCodec.fromJs(raw)
    }

    private fun bool(args: Array<out Any?>, index: Int, fallback: Boolean = false): Boolean =
        when (val value = optionalPlain(args, index)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", true) || value == "1"
            else -> fallback
        }

    private fun long(args: Array<out Any?>, index: Int, operation: String, fallback: Long? = null): Long {
        val value = optionalPlain(args, index)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            null -> fallback
            else -> null
        } ?: throw PluginApiException("$operation 的第 ${index + 1} 个参数必须是数字")
    }

    private fun map(args: Array<out Any?>, index: Int): Map<String, Any?> =
        (optionalPlain(args, index) as? Map<*, *>)?.entries
            ?.filter { it.key is String }
            ?.associate { (key, value) -> key as String to value }
            ?: emptyMap()

    private fun stringList(args: Array<out Any?>, index: Int): List<String>? =
        (optionalPlain(args, index) as? List<*>)?.map { it.toString() }

    private fun requestToMap(request: PluginRequest): Map<String, Any?> = mapOf(
        "method" to request.method,
        "path" to request.path,
        "query" to request.query,
        "headers" to request.headers,
        "body" to request.bodyText,
        "remoteHost" to request.remoteHost,
        "authenticated" to request.authenticated,
    )

    private fun readPrelude(): String =
        JsPluginRuntime::class.java.getResourceAsStream("/plugins/prelude.js")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("缺少 /plugins/prelude.js 资源")

    private companion object {
        const val EXECUTION_TIMEOUT_SECONDS = 60L
    }
}

/**
 * Converts between Kotlin values and Rhino values.
 *
 * Conversions are explicit rather than going through `Context.javaToJS`: the sandbox has Java
 * reflection disabled, and plugins must never receive a live reference to a server object.
 */
internal object PluginJsCodec {

    fun toJs(cx: Context, scope: Scriptable, value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is String -> value
        is ByteArray -> Base64.getEncoder().encodeToString(value)
        is Number -> value
        is Map<*, *> -> NativeObject().also { obj ->
            obj.parentScope = scope
            value.forEach { (key, item) -> if (key is String) ScriptableObject.putProperty(obj, key, toJs(cx, scope, item)) }
        }
        is Iterable<*> -> cx.newArray(scope, value.map { toJs(cx, scope, it) }.toTypedArray())
        is Array<*> -> cx.newArray(scope, value.map { toJs(cx, scope, it) }.toTypedArray())
        else -> value.toString()
    }

    fun fromJs(value: Any?): Any? = when (value) {
        null -> null
        is Undefined -> null
        is Boolean -> value
        is CharSequence -> value.toString()
        is ConsString -> value.toString()
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble.isFinite() && asDouble == Math.floor(asDouble) && Math.abs(asDouble) < 9.007199254740992E15) {
                asDouble.toLong()
            } else {
                asDouble
            }
        }
        is NativeArray -> {
            // Rhino reports array length as a long; every Scriptable.get overload takes an Int.
            val size = value.length.toInt()
            (0 until size).map { index -> fromJs(value.get(index, value)) }
        }
        is Scriptable -> buildMap {
            value.ids.forEach { id ->
                if (id is String) {
                    val item = ScriptableObject.getProperty(value, id)
                    if (item !== Scriptable.NOT_FOUND) put(id, fromJs(item))
                }
            }
        }
        else -> value.toString()
    }

    /**
     * Interprets whatever a JS route handler returned.
     *
     * A string is a plain text body, a plain object is serialised as JSON, and an object carrying a
     * numeric `status` is treated as an explicit response descriptor (optionally with `bodyBase64`
     * so plugins can serve binary payloads).
     */
    fun toResponse(cx: Context, value: Any?): PluginResponse {
        val plain = fromJs(value)
        if (plain == null) return PluginResponse(status = 200)
        if (plain is String) return PluginResponse.text(plain)
        if (plain is Map<*, *>) {
            val descriptor = plain as Map<String, Any?>
            val status = (descriptor["status"] as? Number)?.toInt()
            if (status == null) return PluginResponse.json(descriptor)
            val headers = (descriptor["headers"] as? Map<*, *>)
                ?.entries?.filter { it.key is String && it.value != null }
                ?.associate { (key, item) -> key as String to item.toString() }
                ?: emptyMap()
            val contentType = descriptor["contentType"]?.toString()
            val bodyBase64 = descriptor["bodyBase64"]?.toString()
            val body = when {
                bodyBase64 != null -> runCatching { Base64.getDecoder().decode(bodyBase64) }
                    .getOrElse { throw PluginApiException("bodyBase64 不是合法的 Base64 数据") }
                descriptor["body"] != null -> descriptor["body"].toString().toByteArray(Charsets.UTF_8)
                else -> null
            }
            val withContentType = if (contentType != null && headers.keys.none { it.equals("Content-Type", true) }) {
                headers + ("Content-Type" to withUtf8Charset(contentType))
            } else {
                headers
            }
            return PluginResponse(status, withContentType, body)
        }
        if (plain is Number || plain is Boolean) return PluginResponse.text(plain.toString())
        return PluginResponse.json(plain)
    }

    /**
     * JavaScript strings are UTF-8 by construction, so a textual content type without a charset
     * lets clients fall back to Latin-1 and render CJK text as mojibake — PowerShell's web client
     * does exactly that. Binary types are left untouched.
     */
    private fun withUtf8Charset(contentType: String): String {
        if (contentType.contains("charset", ignoreCase = true)) return contentType
        val lower = contentType.lowercase()
        val textual = lower.startsWith("text/") ||
            lower.startsWith("application/json") ||
            lower.startsWith("application/xml") ||
            lower.contains("javascript") ||
            lower.contains("+json") ||
            lower.contains("+xml")
        return if (textual) "$contentType; charset=utf-8" else contentType
    }

    /** Exposed for the manager's diagnostic messages: Rhino error text without the stack noise. */
    fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Undefined -> "undefined"
        else -> ScriptRuntime.toString(value)
    }
}
