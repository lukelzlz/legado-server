package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*

fun Route.staticWeb() {
    val respondIndex: suspend (ApplicationCall) -> Unit = { call ->
        call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        call.response.header(HttpHeaders.Expires, "0")
        call.respondResource("static/index.html")
    }
    get("/index.html") { respondIndex(call) }
    head("/index.html") { respondIndex(call) }
    staticResources("/assets", "static/assets")
    staticResources("/", "static")
    get("/") { respondIndex(call) }
    head("/") { respondIndex(call) }
}
