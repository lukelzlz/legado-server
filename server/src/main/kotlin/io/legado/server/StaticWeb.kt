package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*

fun Route.staticWeb() {
    staticResources("/", "static")
    get("/") { call.respondText("Legado Server is running. Build web/ and copy its dist/ into server/src/main/resources/static/.", ContentType.Text.Plain) }
}
