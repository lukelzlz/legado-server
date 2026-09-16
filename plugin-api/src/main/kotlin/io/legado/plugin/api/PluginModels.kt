package io.legado.plugin.api

/**
 * An inbound HTTP request forwarded to a plugin route.
 *
 * [path] is relative to the plugin mount point: a request to
 * `/api/plugins/<id>/r/books/2024` reaches the plugin as `/books/2024`.
 */
class PluginRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val remoteHost: String? = null,
    /** True when a valid admin session cookie accompanied the request. */
    val authenticated: Boolean = false,
    /**
     * Absolute mount prefix of this plugin, e.g. `/api/plugins/webdav/r`.
     *
     * Plugins that emit links (WebDAV hrefs, OPDS feeds, redirects) must build them from this value
     * rather than hardcoding a path, so the hosting scheme can change without breaking them.
     */
    val basePath: String = "",
) {
    val bodyText: String get() = body?.toString(Charsets.UTF_8) ?: ""

    /** Case-insensitive header lookup, matching HTTP semantics. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun queryParam(name: String): String? = query[name]

    override fun toString(): String = "$method $path"
}

/**
 * A response produced by a plugin route.
 *
 * Use the companion factories ([text], [json], [bytes], [status], [notFound]) instead of the
 * constructor so content types and encodings stay consistent.
 */
class PluginResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    val bodyText: String get() = body?.toString(Charsets.UTF_8) ?: ""

    fun withHeader(name: String, value: String): PluginResponse =
        PluginResponse(status, headers + (name to value), body)

    companion object {
        private const val TEXT = "text/plain; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"

        @JvmStatic
        @JvmOverloads
        fun text(value: String, status: Int = 200, contentType: String = TEXT): PluginResponse =
            PluginResponse(status, mapOf("Content-Type" to contentType), value.toByteArray(Charsets.UTF_8))

        /** Serializes [value] (Map / List / primitives) and answers with `application/json`. */
        @JvmStatic
        @JvmOverloads
        fun json(value: Any?, status: Int = 200): PluginResponse =
            PluginResponse(status, mapOf("Content-Type" to JSON), PluginJson.write(value).toByteArray(Charsets.UTF_8))

        @JvmStatic
        @JvmOverloads
        fun bytes(value: ByteArray, status: Int = 200, contentType: String = "application/octet-stream"): PluginResponse =
            PluginResponse(status, mapOf("Content-Type" to contentType), value)

        @JvmStatic
        @JvmOverloads
        fun status(status: Int, message: String = ""): PluginResponse =
            PluginResponse(status, mapOf("Content-Type" to TEXT), message.toByteArray(Charsets.UTF_8))

        @JvmStatic
        @JvmOverloads
        fun notFound(message: String = "resource not found"): PluginResponse = status(404, message)

        @JvmStatic
        @JvmOverloads
        fun error(message: String, status: Int = 500): PluginResponse = status(status, message)
    }
}

/** Result of a plugin-initiated outbound HTTP call. */
class PluginHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val isSuccessful: Boolean get() = status in 200..299

    override fun toString(): String = "HTTP $status (${body.size} bytes)"
}

/** An event delivered to [PluginContext.on] listeners. */
class PluginEvent(
    val type: String,
    val payload: Map<String, Any?> = emptyMap(),
    /** Id of the plugin that published the event, or `"server"` for host events. */
    val origin: String = "server",
) {
    fun string(key: String): String? = payload[key]?.toString()
}

/** Handles one request for a route registered by a plugin. */
fun interface PluginRouteHandler {
    fun handle(request: PluginRequest): PluginResponse
}

/** Receives events published on the host event bus. */
fun interface PluginEventListener {
    fun onEvent(event: PluginEvent)
}

/** Handle for a recurring task registered through [PluginContext.schedule]. */
fun interface PluginTask {
    fun cancel()
}
