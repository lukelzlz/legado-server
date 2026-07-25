package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*

fun Route.staticWeb() {
    staticResources("/", "static")
    get("/") { call.respondResource("static/index.html") }
}
