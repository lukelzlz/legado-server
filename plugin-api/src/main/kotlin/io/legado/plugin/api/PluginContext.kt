package io.legado.plugin.api

import java.nio.file.Path

/**
 * Everything a plugin is allowed to touch, handed to [LegadoPlugin.activate].
 *
 * The context is the plugin's only door into the server: it deliberately exposes curated
 * capabilities (books, sources, subscriptions, covers, HTTP, storage, events) instead of the raw
 * `Database` / `RuleRunner` internals. That keeps the plugin contract stable across server
 * refactors and lets the host enforce permissions on every call.
 *
 * All methods are blocking. Read paths are cheap in-memory or SQLite lookups; the rule-execution
 * and subscription APIs do network I/O and should be called from a background task rather than
 * from inside a hot request handler.
 */
interface PluginContext {
    val apiVersion: Int

    val pluginId: String
    val pluginName: String
    val pluginVersion: String

    /** The plugin's own folder, e.g. `<dataDir>/plugins/webdav`. */
    val pluginDirectory: Path

    /** Writable scratch folder created for the plugin: `<pluginDirectory>/data`. */
    val dataDirectory: Path

    val permissions: Set<String>

    fun hasPermission(permission: String): Boolean

    val logger: PluginLogger
    val storage: PluginStorage
    val settings: PluginSettings
    val events: PluginEventBus

    val books: PluginBookApi
    val sources: PluginSourceApi
    val subscriptions: PluginSubscriptionApi
    val covers: PluginCoverApi
    val http: PluginHttpApi
    val auth: PluginAuthApi

    /**
     * Registers a handler mounted at `/api/plugins/<pluginId>/r/<path>`.
     *
     * `path` may contain `{name}` segments (e.g. `/books/{id}/file`); captured values arrive in
     * [PluginRequest.query] under the segment name. Sessions are enforced by default.
     */
    fun route(method: String, path: String, handler: PluginRouteHandler)

    /**
     * Registers a handler that may be invoked without an admin session.
     *
     * Requires [PluginPermissions.ROUTES_PUBLIC]. Use it for protocol endpoints (WebDAV, OPDS, ...)
     * served to third-party clients; the plugin must authenticate callers itself.
     */
    fun routePublic(method: String, path: String, handler: PluginRouteHandler)

    /** Subscribes to a host [PluginEvents] type or to a custom `plugin.<name>` event. */
    fun on(eventType: String, handler: PluginEventListener)

    /**
     * Runs [task] on a background thread every [periodMs] milliseconds, first after [initialDelayMs].
     * The task is cancelled automatically when the plugin is unloaded or disabled.
     */
    fun schedule(initialDelayMs: Long, periodMs: Long, task: Runnable): PluginTask
}

interface PluginLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, error: Throwable? = null)
}

/** A plugin-private persistent key-value store, backed by the server database. */
interface PluginStorage {
    fun get(key: String): String?
    fun set(key: String, value: String?)
    fun remove(key: String)
    fun keys(): List<String>
    fun clear()

    fun getJson(key: String): Map<String, Any?>?
    fun setJson(key: String, value: Any?)
}

/**
 * The plugin's settings object.
 *
 * The UI renders the fields declared in `plugin.json` → `settings` and writes the resulting object
 * back here, so plugins get a configurable surface without shipping any admin code.
 */
interface PluginSettings {
    fun all(): Map<String, Any?>

    /** Merges [values] into the stored settings and returns the effective result. */
    fun update(values: Map<String, Any?>): Map<String, Any?>

    fun replace(values: Map<String, Any?>): Map<String, Any?>

    fun string(key: String, fallback: String? = null): String?

    fun bool(key: String, fallback: Boolean = false): Boolean

    fun int(key: String, fallback: Int = 0): Int
}

interface PluginEventBus {
    /**
     * Publishes an event to every subscriber, including the emitting plugin.
     *
     * Custom types must be prefixed with [PluginEvents.CUSTOM_PREFIX] so host event namespaces
     * cannot be spoofed. Requires [PluginPermissions.EVENTS].
     */
    fun emit(type: String, payload: Map<String, Any?> = emptyMap())
}

/** Bookshelf, reading progress, cached content and live book-source rule execution. */
interface PluginBookApi {
    fun listShelf(): List<Map<String, Any?>>
    fun getShelfItem(sourceId: String, bookUrl: String): Map<String, Any?>?

    /** Adds or updates a shelf entry. Recognised keys: `sourceId`, `bookUrl`, `name`, `author`, `tocUrl`, `coverUrl`. */
    fun addToShelf(fields: Map<String, Any?>): Map<String, Any?>?

    fun removeFromShelf(sourceId: String, bookUrl: String): Boolean
    fun setCompleted(sourceId: String, bookUrl: String, completed: Boolean): Map<String, Any?>?

    fun getProgress(sourceId: String, bookUrl: String): Map<String, Any?>?

    /** Persists reading progress. Recognised keys: `sourceId`, `bookUrl`, `chapterUrl`, `chapterIndex`, `scrollPosition`. */
    fun saveProgress(fields: Map<String, Any?>): Map<String, Any?>

    /** Table of contents, preferring the local cache; `null` when the book was never opened. */
    fun getToc(sourceId: String, tocUrl: String): List<Map<String, Any?>>

    fun getCachedChapters(sourceId: String, bookUrl: String): List<Map<String, Any?>>
    fun listCachedChapterUrls(sourceId: String, bookUrl: String): List<String>

    /** Offline-cached chapter body: `{title, content, cachedAt}` or `null` when not cached. */
    fun getCachedContent(sourceId: String, bookUrl: String, chapterUrl: String): Map<String, Any?>?

    /** Queues the whole book for offline caching. Requires [PluginPermissions.BOOKS_WRITE]. */
    fun requestOfflineCache(sourceId: String, bookUrl: String): Boolean
    fun cancelOfflineCache(sourceId: String, bookUrl: String): Boolean

    // --- Live rule execution (network) -----------------------------------------------------
    // Requires [PluginPermissions.NETWORK_RULES]; each call hits the remote book source.

    fun search(sourceId: String, keyword: String): List<Map<String, Any?>>
    fun fetchDetails(sourceId: String, bookUrl: String): Map<String, Any?>
    fun fetchChapters(sourceId: String, tocUrl: String): List<Map<String, Any?>>
    fun fetchContent(sourceId: String, chapterUrl: String, bookName: String? = null): Map<String, Any?>
}

interface PluginSourceApi {
    fun list(query: String? = null): List<Map<String, Any?>>

    /** The stored source, including its raw `json` payload. */
    fun get(id: String): Map<String, Any?>?

    /** Imports or updates one source from its Legado JSON text. */
    fun save(json: String): Map<String, Any?>

    fun remove(id: String): Boolean
    fun setEnabled(id: String, enabled: Boolean): Map<String, Any?>?

    /** Exports sources as Legado JSON texts; `null` exports every source. */
    fun export(ids: List<String>? = null): List<String>
}

interface PluginSubscriptionApi {
    fun list(enabledOnly: Boolean = false): List<Map<String, Any?>>
    fun add(url: String): Map<String, Any?>
    fun remove(id: Long): Boolean

    /** Fetches the subscription now and imports its sources. Blocking network call. */
    fun refresh(id: Long): Map<String, Any?>
    fun refreshAll(): List<Map<String, Any?>>
}

interface PluginCoverApi {
    /** Downloads and caches a remote cover, returning `{key, contentType}`; `null` on failure. */
    fun cacheUrl(url: String): Map<String, Any?>?

    /** Local file for a cache key, or `null` when the cover is not cached. */
    fun fileFor(key: String): Path?
    fun contentType(key: String): String?
    fun delete(key: String)
}

interface PluginHttpApi {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Long = 15000,
    ): PluginHttpResponse

    fun get(url: String, headers: Map<String, String> = emptyMap()): PluginHttpResponse
    fun post(url: String, body: ByteArray?, contentType: String? = null): PluginHttpResponse
}

interface PluginAuthApi {
    /**
     * Verifies the administrator password.
     *
     * Lets protocol plugins (WebDAV, OPDS) reuse the server credential instead of inventing a
     * second one. Requires [PluginPermissions.AUTH].
     */
    fun verifyAdminPassword(password: String): Boolean
}
