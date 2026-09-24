package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*

fun Route.staticWeb() {
    // Simple-Web (Kindle / E-ink Web UI) - define specific routes first
    get("/kindle") { call.respondRedirect("/simple/") }
    get("/kindle/") { call.respondRedirect("/simple/") }
    get("/simple") { call.respondRedirect("/simple/") }
    get("/simple/") { call.respondResource("simple/index.html") }
    get("/simple/index.html") { call.respondResource("simple/index.html") }
    get("/simple/reader.html") { call.respondResource("simple/reader.html") }
    get("/simple/search.html") { call.respondResource("simple/search.html") }
    get("/simple/rss.html") { call.respondResource("simple/rss.html") }
    staticResources("/simple", "simple")

    val respondNoCache: suspend (ApplicationCall, String, ContentType?) -> Unit = { call, resource, contentType ->
        call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        call.response.header(HttpHeaders.Expires, "0")
        if (contentType != null) {
            call.response.header(HttpHeaders.ContentType, contentType.toString())
        }
        call.respondResource(resource)
    }

    val respondIndex: suspend (ApplicationCall) -> Unit = { call ->
        respondNoCache(call, "static/index.html", ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    get("/index.html") { respondIndex(call) }
    head("/index.html") { respondIndex(call) }

    get("/sw.js") { respondNoCache(call, "static/sw.js", ContentType.parse("application/javascript")) }
    head("/sw.js") { respondNoCache(call, "static/sw.js", ContentType.parse("application/javascript")) }
    get("/registerSW.js") { respondNoCache(call, "static/registerSW.js", ContentType.parse("application/javascript")) }
    head("/registerSW.js") { respondNoCache(call, "static/registerSW.js", ContentType.parse("application/javascript")) }
    get("/manifest.webmanifest") { respondNoCache(call, "static/manifest.webmanifest", ContentType.parse("application/manifest+json")) }
    head("/manifest.webmanifest") { respondNoCache(call, "static/manifest.webmanifest", ContentType.parse("application/manifest+json")) }
    get("/manifest.json") { respondNoCache(call, "static/manifest.webmanifest", ContentType.parse("application/manifest+json")) }
    head("/manifest.json") { respondNoCache(call, "static/manifest.webmanifest", ContentType.parse("application/manifest+json")) }

    staticResources("/assets", "static/assets")
    staticResources("/", "static")
    get("/") { respondIndex(call) }
    head("/") { respondIndex(call) }
}
