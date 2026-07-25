package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

fun Route.apiRoutes(database: Database, auth: AuthService, runner: RuleRunner) {
    route("/api") {
        get("/sources") {
            if (auth.requireSession(call) == null) return@get
            call.respond(database.listSources(call.request.queryParameters["q"]))
        }
        get("/sources/export") {
            if (auth.requireSession(call) == null) return@get
            call.respondText(
                text = Json.encodeToString(database.exportSources(call.request.queryParameters.getAll("id"))),
                contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
        }
        post("/sources/import") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<ImportRequest>(); var imported = 0; var skipped = 0; val errors = mutableListOf<String>()
            request.sources.forEachIndexed { index, raw ->
                try { database.saveSource(SourceCodec.parse(raw), null); imported++ } catch (error: IllegalArgumentException) { skipped++; errors += "第 ${index + 1} 项：${error.message}" }
            }
            call.application.log.info("source import completed: imported={}, skipped={}", imported, skipped)
            call.respond(ImportResponse(imported, skipped, errors))
        }
        route("/sources/{id}") {
            get {
                if (auth.requireSession(call) == null) return@get
                val source = database.getSource(call.parameters["id"]!!)
                if (source == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")) else call.respond(source)
            }
            put {
                if (auth.requireSession(call, true) == null) return@put
                val request = call.receive<SourceWriteRequest>()
                try { call.respond(database.saveSource(SourceCodec.parse(request.json), request.version)); call.application.log.info("source updated: {}", call.parameters["id"]) }
                catch (_: VersionConflict) { call.respond(HttpStatusCode.Conflict, ApiError("version_conflict", "书源已在其他页面更新，请刷新后重试")) }
                catch (error: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_source", error.message ?: "书源无效")) }
            }
            delete {
                if (auth.requireSession(call, true) == null) return@delete
                if (database.deleteSource(call.parameters["id"]!!)) { call.application.log.info("source deleted: {}", call.parameters["id"]); call.respond(HttpStatusCode.NoContent) } else call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在"))
            }
            post("/validate") {
                if (auth.requireSession(call, true) == null) return@post
                val source = database.getSource(call.parameters["id"]!!) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@post }
                call.respond(SourceCodec.validate(source.json))
            }
            get("/debug") {
                if (auth.requireSession(call) == null) return@get
                val source = database.getSource(call.parameters["id"]!!) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@get }
                call.response.cacheControl(CacheControl.NoStore(null)); call.response.headers.append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
                call.respondTextWriter(ContentType.Text.EventStream) {
                    write("event: start\ndata: ${jsonEvent("开始调试 ${source.id}")}\n\n"); flush(); delay(20)
                    val validation = SourceCodec.validate(source.json)
                    if (!validation.valid) write("event: error\ndata: ${jsonEvent(validation.errors.joinToString())}\n\n")
                    else {
                        val keyword = call.request.queryParameters["keyword"] ?: "测试"
                        runCatching { runner.search(source.json, keyword) }
                            .onSuccess { results -> write("event: result\ndata: ${jsonEvent("搜索完成：${results.size} 项")}\n\n") }
                            .onFailure { error -> write("event: error\ndata: ${jsonEvent(error.message ?: "执行失败")}\n\n") }
                    }
                    flush()
                }
            }
        }
        post("/search") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<SearchRequest>()
            if (request.keyword.isBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_keyword", "请输入搜索关键词")); return@post }
            val sources = database.listSources(null).filter { it.enabled && (request.sourceIds.isNullOrEmpty() || it.id in request.sourceIds) }
            val results = sources.flatMap { summary ->
                val source = database.getSource(summary.id) ?: return@flatMap emptyList()
                runCatching { runner.search(source.json, request.keyword) }.getOrElse { emptyList() }
            }
            call.respond(results)
        }
        post("/books/details") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<BookRequest>(); val source = database.getSource(request.sourceId) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@post }
            call.respondCatching { runner.details(source.json, request.bookUrl) }
        }
        post("/books/chapters") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<BookRequest>(); val source = database.getSource(request.sourceId) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@post }
            call.respondCatching { runner.chapters(source.json, request.bookUrl) }
        }
        post("/books/content") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<ContentRequest>(); val source = database.getSource(request.sourceId) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@post }
            call.respondCatching { runner.content(source.json, request.chapterUrl) }
        }
        get("/reading-progress") {
            if (auth.requireSession(call) == null) return@get
            val sourceId = call.request.queryParameters["sourceId"]
            val bookUrl = call.request.queryParameters["bookUrl"]
            if (sourceId.isNullOrBlank() || bookUrl.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_progress", "缺少 sourceId 或 bookUrl")); return@get }
            database.getProgress(sourceId, bookUrl)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NoContent)
        }
        put("/reading-progress") {
            if (auth.requireSession(call, true) == null) return@put
            val progress = call.receive<ReadingProgress>()
            if (progress.sourceId.isBlank() || progress.bookUrl.isBlank() || progress.chapterUrl.isBlank() || progress.chapterIndex < 0) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_progress", "阅读进度无效")); return@put }
            call.respond(database.saveProgress(progress))
        }
    }
}

private suspend fun ApplicationCall.respondCatching(block: () -> Any) {
    try { respond(block()) }
    catch (error: RuleExecutionException) { respond(HttpStatusCode.BadGateway, ApiError("source_execution_failed", error.message ?: "书源执行失败")) }
}

private fun jsonEvent(message: String): String = Json.encodeToString(message)
