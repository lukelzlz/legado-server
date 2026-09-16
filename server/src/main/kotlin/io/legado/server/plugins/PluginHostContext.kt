package io.legado.server.plugins

import io.legado.plugin.api.PluginApiException
import io.legado.plugin.api.PluginAuthApi
import io.legado.plugin.api.PluginBookApi
import io.legado.plugin.api.PluginContext
import io.legado.plugin.api.PluginCoverApi
import io.legado.plugin.api.PluginEvent
import io.legado.plugin.api.PluginEventBus
import io.legado.plugin.api.PluginEventListener
import io.legado.plugin.api.PluginEvents
import io.legado.plugin.api.PluginHttpApi
import io.legado.plugin.api.PluginHttpResponse
import io.legado.plugin.api.PluginJson
import io.legado.plugin.api.PluginLogger
import io.legado.plugin.api.PluginPermissionException
import io.legado.plugin.api.PluginPermissions
import io.legado.plugin.api.PluginRouteHandler
import io.legado.plugin.api.PluginSettings
import io.legado.plugin.api.PluginSourceApi
import io.legado.plugin.api.PluginStorage
import io.legado.plugin.api.PluginSubscriptionApi
import io.legado.plugin.api.PluginTask
import io.legado.server.BookCacheService
import io.legado.server.BookshelfWriteRequest
import io.legado.server.CachedBookRequest
import io.legado.server.CoverCache
import io.legado.server.Database
import io.legado.server.NetworkSecurity
import io.legado.server.ReadingProgress
import io.legado.server.RuleRunner
import io.legado.server.SourceCodec
import io.legado.server.SubscriptionService
import io.legado.server.SubscriptionWriteRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Shared, plugin-independent services a plugin context delegates to. */
class PluginServices(
    val database: Database,
    val runner: RuleRunner,
    val coverCache: CoverCache,
    val bookCache: BookCacheService,
    val subscriptions: SubscriptionService,
    val scheduler: PluginScheduler,
    val events: PluginEventPublisher,
    val serverVersion: String,
    val httpClient: HttpClient,
    /** Writes a line into the server log, tagged with the plugin id. */
    val log: (String, Throwable?) -> Unit,
)

/**
 * The [PluginContext] handed to one plugin.
 *
 * Every capability is permission-gated here rather than at the call sites: a plugin either declared
 * a permission in `plugin.json` or the host refuses the call, which keeps the enforcement in a
 * single auditable place.
 */
class PluginHostContext(
    private val plugin: LoadedPlugin,
    private val services: PluginServices,
) : PluginContext {

    override val apiVersion: Int = PluginManifests.CURRENT_API_VERSION
    override val pluginId: String = plugin.id
    override val pluginName: String = plugin.manifest.name
    override val pluginVersion: String = plugin.manifest.version
    override val pluginDirectory: Path = plugin.directory
    override val dataDirectory: Path = plugin.dataDirectory
    override val permissions: Set<String> = plugin.manifest.permissions.toSet()

    override fun hasPermission(permission: String): Boolean = plugin.hasPermission(permission)

    private fun require(permission: String) {
        if (!plugin.hasPermission(permission)) throw PluginPermissionException(permission)
    }

    /** Called once before the plugin activates so `data/` is always writable. */
    fun prepare() {
        Files.createDirectories(dataDirectory)
    }

    override val logger: PluginLogger = object : PluginLogger {
        override fun debug(message: String) = services.log("[$pluginId] $message", null)
        override fun info(message: String) = services.log("[$pluginId] $message", null)
        override fun warn(message: String) = services.log("[$pluginId] WARN $message", null)
        override fun error(message: String, error: Throwable?) = services.log("[$pluginId] ERROR $message", error)
    }

    override val storage: PluginStorage = DatabaseStorage(plugin.id, services.database)

    override val settings: PluginSettings = DatabaseSettings(plugin, services.database)

    override val events: PluginEventBus = object : PluginEventBus {
        override fun emit(type: String, payload: Map<String, Any?>) {
            require(PluginPermissions.EVENTS)
            if (!type.startsWith(PluginEvents.CUSTOM_PREFIX)) {
                throw PluginApiException("插件只能发布以 '${PluginEvents.CUSTOM_PREFIX}' 开头的事件，收到 '$type'")
            }
            services.events.publish(type, payload, pluginId)
        }
    }

    override val books: PluginBookApi = BookApi()
    override val sources: PluginSourceApi = SourceApi()
    override val subscriptions: PluginSubscriptionApi = SubscriptionApi()
    override val covers: PluginCoverApi = CoverApi()
    override val http: PluginHttpApi = HttpApi()
    override val auth: PluginAuthApi = object : PluginAuthApi {
        override fun verifyAdminPassword(password: String): Boolean {
            require(PluginPermissions.AUTH)
            if (password.isBlank()) return false
            return services.database.verifyPassword(password)
        }
    }

    override fun route(method: String, path: String, handler: PluginRouteHandler) =
        registerRoute(method, path, isPublic = false, handler)

    override fun routePublic(method: String, path: String, handler: PluginRouteHandler) {
        require(PluginPermissions.ROUTES_PUBLIC)
        registerRoute(method, path, isPublic = true, handler)
    }

    private fun registerRoute(method: String, path: String, isPublic: Boolean, handler: PluginRouteHandler) {
        val normalizedMethod = method.trim().uppercase()
        if (normalizedMethod != "*" && normalizedMethod !in HTTP_METHODS) {
            throw PluginApiException("不支持的 HTTP 方法: $method")
        }
        val normalizedPath = path.trim()
        if (!normalizedPath.startsWith("/") || normalizedPath.contains("..") || normalizedPath.length > 256) {
            throw PluginApiException("路由路径非法: $path（必须以 / 开头，且不含 ..）")
        }
        if (!ROUTE_PATH_PATTERN.matches(normalizedPath)) {
            throw PluginApiException("路由路径只能包含字母、数字、- _ . / 与 {name} 占位符: $path")
        }
        val duplicate = plugin.routes.any {
            it.method == normalizedMethod && it.path == normalizedPath && it.isPublic == isPublic
        }
        if (duplicate) throw PluginApiException("路由重复注册: $normalizedMethod $normalizedPath")
        plugin.routes.add(RegisteredRoute(pluginId, normalizedMethod, normalizedPath, isPublic, handler))
    }
    override fun on(eventType: String, handler: PluginEventListener) {
        require(PluginPermissions.EVENTS)
        if (eventType.isBlank()) throw PluginApiException("事件类型不能为空")
        plugin.listeners.add(RegisteredListener(pluginId, eventType, handler))
    }

    override fun schedule(initialDelayMs: Long, periodMs: Long, task: Runnable): PluginTask {
        require(PluginPermissions.SCHEDULE)
        if (periodMs < MIN_PERIOD_MS) throw PluginApiException("定时任务周期不能小于 ${MIN_PERIOD_MS}ms")
        return services.scheduler.schedule(pluginId, initialDelayMs.coerceAtLeast(0), periodMs, task)
    }

    // --- 能力实现 ---------------------------------------------------------------------------

    private inner class BookApi : PluginBookApi {
        override fun listShelf(): List<Map<String, Any?>> {
            require(PluginPermissions.BOOKS_READ)
            return services.database.listBookshelf().map(PluginValues::shelfItem)
        }

        override fun getShelfItem(sourceId: String, bookUrl: String): Map<String, Any?>? {
            require(PluginPermissions.BOOKS_READ)
            return services.database.listBookshelf()
                .firstOrNull { it.sourceId == sourceId && it.bookUrl == bookUrl }
                ?.let(PluginValues::shelfItem)
        }

        override fun addToShelf(fields: Map<String, Any?>): Map<String, Any?>? {
            require(PluginPermissions.BOOKS_WRITE)
            val sourceId = PluginValues.stringField(fields, "sourceId")
                ?: throw PluginApiException("addToShelf 缺少 sourceId")
            val bookUrl = PluginValues.stringField(fields, "bookUrl")
                ?: throw PluginApiException("addToShelf 缺少 bookUrl")
            val name = PluginValues.stringField(fields, "name") ?: bookUrl
            val tocUrl = PluginValues.stringField(fields, "tocUrl") ?: bookUrl
            val coverUrl = PluginValues.stringField(fields, "coverUrl")
            val request = BookshelfWriteRequest(
                sourceId = sourceId,
                bookUrl = bookUrl,
                name = name,
                author = PluginValues.stringField(fields, "author"),
                tocUrl = tocUrl,
                coverUrl = coverUrl,
            )
            val cover = coverUrl?.let { url -> runCatching { services.coverCache.getIfCached(url) ?: services.coverCache.cache(url) }.getOrNull() }
            return PluginValues.shelfItem(services.database.saveBookshelf(request, cover))
        }

        override fun removeFromShelf(sourceId: String, bookUrl: String): Boolean {
            require(PluginPermissions.BOOKS_WRITE)
            return services.database.removeBookshelf(sourceId, bookUrl) != null
        }

        override fun setCompleted(sourceId: String, bookUrl: String, completed: Boolean): Map<String, Any?>? {
            require(PluginPermissions.BOOKS_WRITE)
            return services.database.setBookshelfCompleted(sourceId, bookUrl, completed)?.let(PluginValues::shelfItem)
        }

        override fun getProgress(sourceId: String, bookUrl: String): Map<String, Any?>? {
            require(PluginPermissions.BOOKS_READ)
            return services.database.getProgress(sourceId, bookUrl)?.let(PluginValues::progress)
        }

        override fun saveProgress(fields: Map<String, Any?>): Map<String, Any?> {
            require(PluginPermissions.BOOKS_WRITE)
            val sourceId = PluginValues.stringField(fields, "sourceId")
                ?: throw PluginApiException("saveProgress 缺少 sourceId")
            val bookUrl = PluginValues.stringField(fields, "bookUrl")
                ?: throw PluginApiException("saveProgress 缺少 bookUrl")
            val progress = ReadingProgress(
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = PluginValues.stringField(fields, "chapterUrl") ?: "",
                chapterIndex = PluginValues.intField(fields, "chapterIndex"),
                scrollPosition = PluginValues.doubleField(fields, "scrollPosition"),
                updatedAt = System.currentTimeMillis(),
            )
            return PluginValues.progress(services.database.saveProgress(progress))
        }

        override fun getToc(sourceId: String, tocUrl: String): List<Map<String, Any?>> {
            require(PluginPermissions.BOOKS_READ)
            return services.database.getTocCache(sourceId, tocUrl)?.map(PluginValues::chapter).orEmpty()
        }

        override fun getCachedChapters(sourceId: String, bookUrl: String): List<Map<String, Any?>> {
            require(PluginPermissions.BOOKS_READ)
            return services.database.getCachedChaptersFallback(sourceId, bookUrl).map(PluginValues::chapter)
        }

        override fun listCachedChapterUrls(sourceId: String, bookUrl: String): List<String> {
            require(PluginPermissions.BOOKS_READ)
            return services.database.cachedChapterUrls(sourceId, bookUrl).toList()
        }

        override fun getCachedContent(sourceId: String, bookUrl: String, chapterUrl: String): Map<String, Any?>? {
            require(PluginPermissions.BOOKS_READ)
            return services.database.cachedContent(sourceId, bookUrl, chapterUrl)?.let { PluginValues.chapterContent(it) }
        }

        override fun requestOfflineCache(sourceId: String, bookUrl: String): Boolean {
            require(PluginPermissions.BOOKS_WRITE)
            val item = services.database.listBookshelf().firstOrNull { it.sourceId == sourceId && it.bookUrl == bookUrl }
                ?: throw PluginApiException("书籍不在书架中，无法缓存: $bookUrl")
            services.bookCache.enqueue(CachedBookRequest(sourceId, bookUrl, item.tocUrl))
            return true
        }

        override fun cancelOfflineCache(sourceId: String, bookUrl: String): Boolean {
            require(PluginPermissions.BOOKS_WRITE)
            services.bookCache.cancel(sourceId, bookUrl)
            return true
        }

        override fun search(sourceId: String, keyword: String): List<Map<String, Any?>> {
            require(PluginPermissions.NETWORK_RULES)
            val source = services.database.getSource(sourceId) ?: throw PluginApiException("书源不存在: $sourceId")
            return services.runner.search(source.json, keyword).map(PluginValues::searchResult)
        }

        override fun fetchDetails(sourceId: String, bookUrl: String): Map<String, Any?> {
            require(PluginPermissions.NETWORK_RULES)
            val source = services.database.getSource(sourceId) ?: throw PluginApiException("书源不存在: $sourceId")
            return PluginValues.bookDetails(services.runner.details(source.json, bookUrl))
        }

        override fun fetchChapters(sourceId: String, tocUrl: String): List<Map<String, Any?>> {
            require(PluginPermissions.NETWORK_RULES)
            val source = services.database.getSource(sourceId) ?: throw PluginApiException("书源不存在: $sourceId")
            return services.runner.chapters(source.json, tocUrl).map(PluginValues::chapter)
        }

        override fun fetchContent(sourceId: String, chapterUrl: String, bookName: String?): Map<String, Any?> {
            require(PluginPermissions.NETWORK_RULES)
            val source = services.database.getSource(sourceId) ?: throw PluginApiException("书源不存在: $sourceId")
            return PluginValues.chapterContent(services.runner.content(source.json, chapterUrl, bookName))
        }
    }

    private inner class SourceApi : PluginSourceApi {
        override fun list(query: String?): List<Map<String, Any?>> {
            require(PluginPermissions.SOURCES_READ)
            return services.database.listSources(query).map(PluginValues::sourceSummary)
        }

        override fun get(id: String): Map<String, Any?>? {
            require(PluginPermissions.SOURCES_READ)
            return services.database.getSource(id)?.let(PluginValues::sourceRecord)
        }

        override fun save(json: String): Map<String, Any?> {
            require(PluginPermissions.SOURCES_WRITE)
            val parsed = SourceCodec.parse(json)
            return PluginValues.sourceRecord(services.database.saveSource(parsed, null))
        }

        override fun remove(id: String): Boolean {
            require(PluginPermissions.SOURCES_WRITE)
            return services.database.deleteSource(id)
        }

        override fun setEnabled(id: String, enabled: Boolean): Map<String, Any?>? {
            require(PluginPermissions.SOURCES_WRITE)
            val record = services.database.getSource(id) ?: return null
            val updated = withEnabledFlag(record.json, enabled)
            val parsed = SourceCodec.parse(updated)
            return PluginValues.sourceRecord(services.database.saveSource(parsed, record.version))
        }

        override fun export(ids: List<String>?): List<String> {
            require(PluginPermissions.SOURCES_READ)
            return services.database.exportSources(ids)
        }

        private fun withEnabledFlag(json: String, enabled: Boolean): String {
            val element = Json.parseToJsonElement(json).jsonObject
            val mutable = element.toMutableMap()
            mutable["enabled"] = JsonPrimitive(enabled)
            return Json.encodeToString(JsonElement.serializer(), JsonObject(mutable))
        }
    }

    private inner class SubscriptionApi : PluginSubscriptionApi {
        override fun list(enabledOnly: Boolean): List<Map<String, Any?>> {
            require(PluginPermissions.SUBSCRIPTIONS_READ)
            return services.database.listSubscriptions(enabledOnly).map(PluginValues::subscription)
        }

        override fun add(url: String): Map<String, Any?> {
            require(PluginPermissions.SUBSCRIPTIONS_WRITE)
            if (url.isBlank()) throw PluginApiException("订阅地址不能为空")
            return PluginValues.subscription(services.database.saveSubscription(SubscriptionWriteRequest(url, true)))
        }

        override fun remove(id: Long): Boolean {
            require(PluginPermissions.SUBSCRIPTIONS_WRITE)
            return services.database.deleteSubscription(id)
        }

        override fun refresh(id: Long): Map<String, Any?> {
            require(PluginPermissions.SUBSCRIPTIONS_WRITE)
            val response = runBlocking { services.subscriptions.updateOne(id) }
            return PluginValues.importResponse(response)
        }

        override fun refreshAll(): List<Map<String, Any?>> {
            require(PluginPermissions.SUBSCRIPTIONS_WRITE)
            return runBlocking { services.subscriptions.updateAll() }
                .map { (id, result) -> mapOf("id" to id, "ok" to result.isSuccess) + (result.getOrNull()?.let(PluginValues::importResponse) ?: mapOf("error" to result.exceptionOrNull()?.message)) }
        }
    }

    private inner class CoverApi : PluginCoverApi {
        override fun cacheUrl(url: String): Map<String, Any?>? {
            require(PluginPermissions.COVERS)
            if (url.isBlank()) return null
            return runCatching { PluginValues.cover(services.coverCache.cache(url)) }.getOrNull()
        }

        override fun fileFor(key: String): Path? {
            require(PluginPermissions.COVERS)
            return services.coverCache.file(key)
        }

        override fun contentType(key: String): String? {
            require(PluginPermissions.COVERS)
            return services.database.coverContentType(key)
        }

        override fun delete(key: String) {
            require(PluginPermissions.COVERS)
            services.coverCache.delete(key)
        }
    }

    private inner class HttpApi : PluginHttpApi {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMs: Long,
        ): PluginHttpResponse {
            require(PluginPermissions.HTTP)
            val uri = try {
                URI(url)
            } catch (error: Exception) {
                throw PluginApiException("插件 HTTP 请求地址非法: $url")
            }
            if (plugin.hasPermission(PluginPermissions.HTTP_PRIVATE)) {
                if (uri.scheme !in setOf("http", "https")) throw PluginApiException("仅支持 http(s) 地址: $url")
            } else {
                NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "插件请求")
            }
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs.coerceIn(1_000, 120_000)))
            headers.forEach { (name, value) ->
                // java.net.http refuses to set connection-level headers itself; surface that as a
                // plugin-level error instead of an obscure IllegalArgumentException.
                runCatching { builder.header(name, value) }.onFailure {
                    throw PluginApiException("不允许设置请求头 '$name'")
                }
            }
            when (method.uppercase()) {
                "GET" -> builder.GET()
                "DELETE" -> builder.DELETE()
                "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
                else -> builder.method(
                    method.uppercase(),
                    body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody(),
                )
            }
            val response = try {
                services.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            } catch (error: Exception) {
                throw PluginApiException("插件 HTTP 请求失败: ${error.message}")
            }
            return PluginHttpResponse(
                status = response.statusCode(),
                headers = response.headers().map().mapValues { (_, values) -> values.joinToString(", ") },
                body = response.body() ?: ByteArray(0),
            )
        }

        override fun get(url: String, headers: Map<String, String>): PluginHttpResponse = request("GET", url, headers)

        override fun post(url: String, body: ByteArray?, contentType: String?): PluginHttpResponse =
            request("POST", url, contentType?.let { mapOf("Content-Type" to it) } ?: emptyMap(), body)
    }

    private companion object {
        val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        val ROUTE_PATH_PATTERN = Regex("""^/[A-Za-z0-9\-._~/{}*]*$""")
        const val MIN_PERIOD_MS = 1_000L
    }
}

/** Key-value store persisted in the `plugin_kv` table. */
private class DatabaseStorage(private val pluginId: String, private val database: Database) : PluginStorage {
    override fun get(key: String): String? = database.pluginKvGet(pluginId, key)

    override fun set(key: String, value: String?) {
        requireKey(key)
        database.pluginKvSet(pluginId, key, value)
    }

    override fun remove(key: String) {
        requireKey(key)
        database.pluginKvSet(pluginId, key, null)
    }

    override fun keys(): List<String> = database.pluginKvKeys(pluginId)

    override fun clear() = database.pluginKvClear(pluginId)

    override fun getJson(key: String): Map<String, Any?>? = get(key)?.let { runCatching { PluginJson.parseObject(it) }.getOrNull() }

    override fun setJson(key: String, value: Any?) {
        requireKey(key)
        database.pluginKvSet(pluginId, key, PluginJson.write(value))
    }

    private fun requireKey(key: String) {
        if (key.isBlank() || key.length > 256) throw PluginApiException("存储 key 非法")
    }
}

/** Settings object persisted in the `plugin_state` table. */
private class DatabaseSettings(private val plugin: LoadedPlugin, private val database: Database) : PluginSettings {
    override fun all(): Map<String, Any?> = plugin.effectiveSettings()

    override fun update(values: Map<String, Any?>): Map<String, Any?> =
        persist(plugin.settings + values)

    override fun replace(values: Map<String, Any?>): Map<String, Any?> = persist(values)

    private fun persist(values: Map<String, Any?>): Map<String, Any?> {
        database.savePluginState(plugin.id, settings = PluginJson.write(values))
        plugin.settings = values
        return plugin.effectiveSettings()
    }

    override fun string(key: String, fallback: String?): String? = all()[key]?.toString() ?: fallback

    override fun bool(key: String, fallback: Boolean): Boolean = when (val value = all()[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> fallback
    }

    override fun int(key: String, fallback: Int): Int = when (val value = all()[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }
}

/** Convenience for the manager when it publishes host events. */
internal fun PluginEventPublisher.publishHost(type: String, payload: Map<String, Any?> = emptyMap()) =
    publish(type, payload, "server")

/** Re-exported for the JS bridge, which builds [PluginEvent] payloads from script objects. */
internal fun pluginEvent(type: String, payload: Map<String, Any?>, origin: String): PluginEvent =
    PluginEvent(type, payload, origin)
