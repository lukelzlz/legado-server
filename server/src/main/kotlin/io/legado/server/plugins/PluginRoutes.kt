package io.legado.server.plugins

import io.legado.plugin.api.PluginJson
import io.legado.plugin.api.PluginRequest
import io.legado.plugin.api.PluginResponse
import io.legado.server.ApiError
import io.legado.server.AuthService
import io.legado.server.UserSession
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * HTTP surface of the plugin system.
 *
 * Two very different audiences share this file:
 * - the **admin endpoints** (`/api/plugins/...`), which manage plugins and require a session;
 * - the **plugin proxy** (`/api/plugins/<id>/r/...`), which forwards to whatever the plugin
 *   registered and may be public for protocol endpoints such as WebDAV.
 */
fun Route.pluginRoutes(manager: PluginManager, auth: AuthService) {
    route("/plugins") {
        get {
            if (auth.requireSession(call) == null) return@get
            call.respond(manager.list())
        }

        post("/reload") {
            if (auth.requireSession(call, true) == null) return@post
            val count = manager.reload()
            call.application.log.info("plugin reload completed: {} plugin(s) registered", count)
            call.respond(mapOf("reloaded" to count))
        }

        get("/{id}") {
            if (auth.requireSession(call) == null) return@get
            val id = call.parameters["id"].orEmpty()
            manager.view(id)?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("plugin_not_found", "插件不存在"))
        }

        post("/{id}/enable") {
            if (auth.requireSession(call, true) == null) return@post
            val id = call.parameters["id"].orEmpty()
            manager.setEnabled(id, true)?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("plugin_not_found", "插件不存在"))
        }

        post("/{id}/disable") {
            if (auth.requireSession(call, true) == null) return@post
            val id = call.parameters["id"].orEmpty()
            manager.setEnabled(id, false)?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("plugin_not_found", "插件不存在"))
        }

        get("/{id}/settings") {
            if (auth.requireSession(call) == null) return@get
            val id = call.parameters["id"].orEmpty()
            manager.settingsOf(id)?.let { call.respondJsonObject(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("plugin_not_found", "插件不存在"))
        }

        put("/{id}/settings") {
            if (auth.requireSession(call, true) == null) return@put
            val id = call.parameters["id"].orEmpty()
            val body = runCatching { call.receiveStream().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("")
            val values = try {
                val element = Json.parseToJsonElement(body.ifBlank { "{}" })
                val obj = element as? JsonObject ?: throw IllegalArgumentException("设置内容必须是 JSON 对象")
                obj.mapValues { (_, value) -> PluginJsonPlain.fromElement(value) }
            } catch (error: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_settings", "设置内容不是合法 JSON 对象"))
                return@put
            }
            manager.updateSettings(id, values)?.let { call.respondJsonObject(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("plugin_not_found", "插件不存在"))
        }

        /**
         * Serves the plugin's frontend module. The web client imports this URL directly, so it must
         * answer with a JavaScript content type and stay uncached (the client appends `?v=` for
         * cache busting on top of this).
         */
        get("/{id}/web.js") {
            if (auth.requireSession(call) == null) return@get
            val plugin = manager.find(call.parameters["id"].orEmpty())
            val module = plugin?.manifest?.web
            if (plugin == null || module == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("web_module_missing", "该插件没有前端模块"))
                return@get
            }
            val file = plugin.directory.resolve(module)
            if (!Files.isRegularFile(file)) {
                call.respond(HttpStatusCode.NotFound, ApiError("web_module_missing", "前端模块文件不存在"))
                return@get
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
            // 前端模块同样剥掉 BOM：浏览器能容忍它，但剥掉后模块源码与插件作者写的字节完全一致，
            // 排查问题时不会因为一个看不见的字符产生干扰。
            val source = Files.readString(file).removePrefix(PluginManifests.BOM)
            call.respondText(source, ContentType.parse("text/javascript; charset=utf-8"))
        }

        get("/{id}/assets/{path...}") {
            if (auth.requireSession(call) == null) return@get
            val plugin = manager.find(call.parameters["id"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("plugin_not_found", "插件不存在"))
            val relative = call.parameters.getAll("path")?.joinToString("/").orEmpty()
            val file = resolveAsset(plugin.directory, relative)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("asset_not_found", "资源不存在"))
            // Assets are served as raw bytes: a plugin UI may ship fonts or images, and decoding
            // them as text would silently corrupt the payload.
            val bytes = Files.readAllBytes(file)
            call.respond(object : OutgoingContent.ByteArrayContent() {
                override val contentType: ContentType = assetContentType(file.fileName.toString())
                override val status: HttpStatusCode = HttpStatusCode.OK
                override fun bytes(): ByteArray = bytes
            })
        }

        // Catch-all proxy for plugin-defined routes. `handle` (rather than get/post/...) accepts
        // every HTTP method, which protocol plugins rely on (PROPFIND, MKCOL, MOVE, ...).
        route("/{id}/r/{path...}") {
            handle {
                val id = call.parameters["id"].orEmpty()
                val plugin = manager.find(id)
                    ?: return@handle call.respond(HttpStatusCode.NotFound, ApiError("plugin_not_found", "插件不存在"))
                // Activation failures and disabled plugins are reported before route matching:
                // otherwise an inactive plugin would masquerade as "route does not exist" and hide
                // the real reason from whoever is debugging it.
                if (!plugin.active) {
                    val reason = plugin.error?.let { "插件未激活: $it" }
                        ?: "插件已停用"
                    return@handle call.respond(HttpStatusCode.ServiceUnavailable, ApiError("plugin_inactive", reason))
                }
                val subPath = "/" + call.parameters.getAll("path")?.joinToString("/").orEmpty()
                val match = manager.matchRoute(id, call.request.httpMethod.value, subPath)
                    ?: return@handle call.respond(HttpStatusCode.NotFound, ApiError("route_not_found", "插件未注册该路由"))

                val session = call.sessions.get<UserSession>()
                val authenticated = session != null && auth.csrf(session) != null
                if (!match.route.isPublic && !authenticated) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "需要登录"))
                    return@handle
                }

                val query = buildMap {
                    call.request.queryParameters.entries().forEach { (name, values) -> put(name, values.firstOrNull().orEmpty()) }
                    putAll(match.pathParameters)
                }
                val headers = call.request.headers.entries().associate { (name, values) -> name to values.joinToString(", ") }
                val request = PluginRequest(
                    method = call.request.httpMethod.value,
                    path = subPath,
                    query = query,
                    headers = headers,
                    body = readBody(call),
                    remoteHost = runCatching { call.request.origin.remoteHost }.getOrNull(),
                    authenticated = authenticated,
                    basePath = "/api/plugins/$id/r",
                )
                val response = try {
                    match.route.handler.handle(request)
                } catch (error: Throwable) {
                    call.application.log.error("plugin route failed: {} {} {}", id, request.method, request.path, error)
                    PluginResponse.error(error.message ?: "插件处理失败")
                }
                call.respondPlugin(response)
            }
        }
    }
}

/**
 * Reads the request body only when the client actually sent one.
 *
 * Reading eagerly would block on chunked requests that never deliver a body, and `GET`/`PROPFIND`
 * carry none — so the content-length/transfer-encoding headers decide.
 */
private suspend fun readBody(call: ApplicationCall): ByteArray? {
    val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    val chunked = call.request.headers[HttpHeaders.TransferEncoding] != null
    if (declaredLength <= 0L && !chunked) return null
    return runCatching { call.receiveStream().use { it.readBytes() } }.getOrNull()
}

/** Resolves a plugin asset path, refusing anything that escapes the plugin folder. */
private fun resolveAsset(directory: Path, relative: String): Path? {
    if (relative.isBlank()) return null
    val resolved = directory.resolve(relative).normalize()
    if (!resolved.startsWith(directory.normalize())) return null
    return resolved.takeIf { Files.isRegularFile(it) }
}

/** Minimal extension → MIME mapping; enough for plugin UI assets without pulling in a MIME table. */
private fun assetContentType(fileName: String): ContentType = when (fileName.substringAfterLast('.', "").lowercase()) {
    "js", "mjs" -> ContentType.parse("text/javascript; charset=utf-8")
    "css" -> ContentType.parse("text/css; charset=utf-8")
    "json", "map" -> ContentType.parse("application/json; charset=utf-8")
    "html", "htm" -> ContentType.parse("text/html; charset=utf-8")
    "svg" -> ContentType.parse("image/svg+xml")
    "png" -> ContentType.parse("image/png")
    "jpg", "jpeg" -> ContentType.parse("image/jpeg")
    "gif" -> ContentType.parse("image/gif")
    "webp" -> ContentType.parse("image/webp")
    "ico" -> ContentType.parse("image/x-icon")
    "woff2" -> ContentType.parse("font/woff2")
    "txt", "md" -> ContentType.parse("text/plain; charset=utf-8")
    else -> ContentType.parse("application/octet-stream")
}

/** Writes a [PluginResponse] straight onto the Ktor response, status and headers included. */
private suspend fun ApplicationCall.respondPlugin(response: PluginResponse) {
    respond(object : OutgoingContent.ByteArrayContent() {
        override val status: HttpStatusCode = HttpStatusCode.fromValue(response.status)
        override val headers: Headers = Headers.build {
            response.headers.forEach { (name, value) -> append(name, value) }
        }

        override fun bytes(): ByteArray = response.body ?: ByteArray(0)
    })
}

/**
 * Answers with an arbitrary JSON object (plugin settings).
 *
 * `call.respond(map)` is a trap here: the map's static type is `Map<String, Any?>`, so Ktor's kotlinx
 * converter falls back to guessing a serializer from the runtime values and throws
 * `Serializing collections of different element types is not yet supported` as soon as a settings
 * object mixes strings with booleans or numbers — which is the most ordinary settings shape there is.
 * Plugin settings are JSON-shaped by construction, so they are emitted as JSON text, matching how the
 * source-export endpoints already answer.
 */
private suspend fun ApplicationCall.respondJsonObject(value: Map<String, Any?>) {
    respondText(
        text = PluginJson.write(value),
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
    )
}

/** Converts JSON settings values into the plain Kotlin values the plugin API speaks. */
internal object PluginJsonPlain {
    fun fromElement(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is kotlinx.serialization.json.JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            element.content == "null" -> null
            else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
        }
        is kotlinx.serialization.json.JsonArray -> element.map(::fromElement)
        is kotlinx.serialization.json.JsonObject -> element.mapValues { (_, value) -> fromElement(value) }
    }
}
