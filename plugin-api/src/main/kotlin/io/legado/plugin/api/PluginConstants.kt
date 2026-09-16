package io.legado.plugin.api

/** Version of the host API described by this module. Bumped only on breaking changes. */
object PluginApiVersion {
    const val CURRENT = 1
}

/**
 * Permissions a plugin may request in its `plugin.json`.
 *
 * The host refuses every capability whose permission is missing: an undeclared call throws
 * [PluginPermissionException] rather than silently returning empty data, so plugin authors notice
 * misconfiguration immediately instead of debugging phantom bugs.
 */
object PluginPermissions {
    /** Read/write the plugin's private key-value store. */
    const val STORAGE = "storage"

    /** Read/write the plugin's settings object. */
    const val SETTINGS = "settings"

    /** Read the bookshelf, reading progress and cached content. */
    const val BOOKS_READ = "books.read"

    /** Mutate the bookshelf (add / remove / mark finished) and trigger offline caching. */
    const val BOOKS_WRITE = "books.write"

    /** Execute book-source rules over the network (search / details / chapters / content). */
    const val NETWORK_RULES = "books.network"

    /** List and read book sources. */
    const val SOURCES_READ = "sources.read"

    /** Import, enable, disable or delete book sources. */
    const val SOURCES_WRITE = "sources.write"

    /** List and read source subscriptions. */
    const val SUBSCRIPTIONS_READ = "subscriptions.read"

    /** Add, remove or refresh source subscriptions. */
    const val SUBSCRIPTIONS_WRITE = "subscriptions.write"

    /** Fetch and read cached book covers. */
    const val COVERS = "covers"

    /** Perform outbound HTTP requests through the host client (SSRF-guarded). */
    const val HTTP = "http"

    /** Perform outbound HTTP requests to private/loopback addresses. */
    const val HTTP_PRIVATE = "http.private"

    /** Verify the administrator password (needed by plugins exposing their own auth). */
    const val AUTH = "auth"

    /** Publish and subscribe to events on the host event bus. */
    const val EVENTS = "events"

    /** Register recurring background tasks. */
    const val SCHEDULE = "schedule"

    /**
     * Declare routes that skip session authentication. Required for protocol endpoints such as
     * WebDAV that are consumed by external clients which cannot present a session cookie.
     * The plugin is then fully responsible for authenticating its own callers.
     */
    const val ROUTES_PUBLIC = "routes.public"

    val ALL: Set<String> = setOf(
        STORAGE, SETTINGS, BOOKS_READ, BOOKS_WRITE, NETWORK_RULES, SOURCES_READ, SOURCES_WRITE,
        SUBSCRIPTIONS_READ, SUBSCRIPTIONS_WRITE, COVERS, HTTP, HTTP_PRIVATE, AUTH, EVENTS,
        SCHEDULE, ROUTES_PUBLIC,
    )
}

/** Built-in event types published on the host event bus. Plugins may also emit custom types. */
object PluginEvents {
    const val SERVER_START = "server.start"
    const val SERVER_STOP = "server.stop"

    const val PLUGIN_LOADED = "plugin.loaded"
    const val PLUGIN_UNLOADED = "plugin.unloaded"

    const val SHELF_ADD = "shelf.add"
    const val SHELF_REMOVE = "shelf.remove"
    const val SHELF_SWITCH_SOURCE = "shelf.switchSource"

    const val SOURCE_IMPORT = "source.import"
    const val SOURCE_DELETE = "source.delete"

    const val PROGRESS_SAVE = "progress.save"

    const val BOOK_CACHE_START = "book.cache.start"
    const val BOOK_CACHE_FINISH = "book.cache.finish"

    /** Prefix reserved for events defined by plugins themselves. */
    const val CUSTOM_PREFIX = "plugin."
}

/** Thrown when a plugin uses a capability it did not declare in `plugin.json`. */
class PluginPermissionException(permission: String) :
    RuntimeException("插件未声明权限: $permission")

/** Thrown by host APIs when a plugin passes invalid arguments. */
class PluginApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
