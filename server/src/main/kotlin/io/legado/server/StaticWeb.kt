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

    // Modern Web React App at root
    get("/index.html") { call.respondResource("static/index.html") }
    staticResources("/", "static")
    get("/") { call.respondResource("static/index.html") }
}
