package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

fun Route.apiRoutes(
    database: Database,
    auth: AuthService,
    runner: RuleRunner,
    coverCache: CoverCache,
    subscriptions: SubscriptionService,
    bookCache: BookCacheService,
    edgeTts: EdgeTtsService = EdgeTtsService(),
    ttsSessions: TtsSessionService = TtsSessionService(edgeTts),
) {
    val webView = WebViewProxy(database)
    route("/api") {
        get("/sources") {
            if (auth.requireSession(call) == null) return@get
            call.respond(database.listSources(call.request.queryParameters["q"]))
        }
        post("/sources/batch") {
            if (auth.requireSession(call, true) == null) return@post
            val req = call.receive<BatchSourceRequest>()
            if (req.ids.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("empty_selection", "请先选择需要操作的书源"))
                return@post
            }
            try {
                val count = database.batchUpdateSources(req.ids, req.action, req.group)
                val msg = when (req.action) {
                    "enable" -> "已成功启用 $count 个书源"
                    "disable" -> "已成功停用 $count 个书源"
                    "delete" -> "已成功删除 $count 个书源"
                    "set_group" -> if (req.group.isNullOrBlank()) "已清空 $count 个书源的分组" else "已将 $count 个书源移至分组“${req.group}”"
                    else -> "已处理 $count 个书源"
                }
                call.application.log.info("source batch operation: action={}, count={}", req.action, count)
                call.respond(BatchSourceResponse(ok = true, affected = count, action = req.action, message = msg))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_action", error.message ?: "无效的批量操作"))
            } catch (error: Throwable) {
                call.application.log.error("source batch operation failed", error)
                call.respond(HttpStatusCode.InternalServerError, ApiError("batch_failed", error.message ?: "批量操作失败"))
            }
        }
        post("/sources/health-check") {
            if (auth.requireSession(call) == null) return@post
            val req = runCatching { call.receive<SourceHealthCheckRequest>() }.getOrElse { SourceHealthCheckRequest() }
            val timeoutMs = req.timeoutMs.coerceIn(1000L, 10000L)
            val allSources = database.listSources(null)
            val targetSources = if (!req.ids.isNullOrEmpty()) {
                val idSet = req.ids.toSet()
                allSources.filter { it.id in idSet }
            } else {
                allSources
            }

            val startTotalNs = System.nanoTime()
            val semaphore = Semaphore(8)
            val probeClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()

            val results = coroutineScope {
                targetSources.map { summary ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val record = database.getSource(summary.id)
                            if (record == null) {
                                SourceHealthCheckItem(
                                    id = summary.id,
                                    name = summary.name,
                                    ok = false,
                                    latencyMs = 0L,
                                    statusCode = 0,
                                    statusCategory = "failed",
                                    error = "书源记录不存在",
                                )
                            } else {
                                probeSingleSourceHealth(probeClient, summary.id, summary.name, record.json, timeoutMs)
                            }
                        }
                    }
                }.awaitAll()
            }
            val totalDurationMs = (System.nanoTime() - startTotalNs) / 1_000_000
            val successCount = results.count { it.statusCategory == "valid" }
            val slowCount = results.count { it.statusCategory == "slow" }
            val failedCount = results.count { it.statusCategory == "failed" || it.statusCategory == "blocked" }

            call.respond(
                SourceHealthCheckResponse(
                    total = results.size,
                    successCount = successCount,
                    slowCount = slowCount,
                    failedCount = failedCount,
                    durationMs = totalDurationMs,
                    results = results,
                )
            )
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
            val response = database.importSources(call.receive<ImportRequest>().sources)
            call.application.log.info("source import completed: imported={}, updated={}, skipped={}", response.imported, response.updated, response.skipped)
            call.respond(response)
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
            get("/login-ui") {
                if (auth.requireSession(call) == null) return@get
                val source = database.getSource(call.parameters["id"]!!) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@get }
                val items = runner.parseLoginUi(source.json)
                val state = database.getSourceLoginState(source.id)
                val sourceObj = runCatching { Json.parseToJsonElement(source.json).jsonObject }.getOrNull()
                val sourceName = (sourceObj?.get("bookSourceName") as? JsonPrimitive)?.contentOrNull ?: source.id
                val loginUrl = (sourceObj?.get("loginUrl") as? JsonPrimitive)?.contentOrNull
                call.respond(
                    SourceLoginUiResponse(
                        sourceId = source.id,
                        sourceName = sourceName,
                        hasLogin = items.isNotEmpty() || !loginUrl.isNullOrBlank() || sourceObj?.get("loginCheckJs") != null,
                        loginUi = items,
                        loginUrl = loginUrl,
                        loginInfo = state?.loginInfo ?: emptyMap(),
                        loginHeader = state?.loginHeader,
                        sourceVariable = state?.sourceVariable,
                    )
                )
            }
            post("/login-info") {
                if (auth.requireSession(call, true) == null) return@post
                val sourceId = call.parameters["id"]!!
                val req = call.receive<SourceLoginInfoUpdateRequest>()
                database.saveSourceLoginInfo(sourceId, req.loginInfo)
                call.respond(mapOf("ok" to true))
            }
            delete("/login-info") {
                if (auth.requireSession(call, true) == null) return@delete
                val sourceId = call.parameters["id"]!!
                database.removeSourceLoginInfo(sourceId)
                call.respond(mapOf("ok" to true))
            }
            post("/login-header") {
                if (auth.requireSession(call, true) == null) return@post
                val sourceId = call.parameters["id"]!!
                val req = call.receive<SourceLoginHeaderUpdateRequest>()
                database.saveSourceLoginHeader(sourceId, req.loginHeader)
                call.respond(mapOf("ok" to true))
            }
            options("/login-cookie") {
                call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
                call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "POST, OPTIONS")
                call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "Content-Type, X-CSRF-Token, Authorization")
                call.respond(HttpStatusCode.OK)
            }
            post("/login-cookie") {
                call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
                val sourceId = call.parameters["id"]!!
                val targetSource = database.getSource(sourceId)
                    ?: database.listSources(null).firstOrNull {
                        it.id.contains(sourceId) || sourceId.contains(it.id) || (it.url.isNotBlank() && (sourceId.contains(it.url) || it.url.contains(sourceId)))
                    }?.let { database.getSource(it.id) }

                val resolvedSourceId = targetSource?.id ?: sourceId
                val req = call.receive<SourceLoginCookieUpdateRequest>()
                val rawCookie = req.cookie.trim()
                var savedCount = 0
                if (rawCookie.isNotEmpty()) {
                    val cookieMap = database.parseCookieString(rawCookie)
                    savedCount = cookieMap.size
                    database.setSourceCookie(resolvedSourceId, req.url ?: resolvedSourceId, rawCookie)
                    val cookieStr = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    val state = database.getSourceLoginState(resolvedSourceId)
                    if (state?.loginHeader.isNullOrBlank() && cookieStr.isNotBlank()) {
                        val headerMap = mapOf("Cookie" to cookieStr)
                        database.saveSourceLoginHeader(resolvedSourceId, Json.encodeToString(headerMap))
                    }
                    call.application.log.info("cookie synchronized for source: {}, count: {}, url: {}", resolvedSourceId, savedCount, req.url)
                }
                call.respond(SourceLoginCookieResponse(ok = true, message = "Cookie 同步成功", count = savedCount))
            }
            delete("/login-header") {
                if (auth.requireSession(call, true) == null) return@delete
                val sourceId = call.parameters["id"]!!
                database.removeSourceLoginHeader(sourceId)
                call.respond(mapOf("ok" to true))
            }
            post("/login-action") {
                if (auth.requireSession(call, true) == null) return@post
                val source = database.getSource(call.parameters["id"]!!) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@post }
                val req = call.receive<SourceLoginActionRequest>()
                val outcome = runner.executeLoginAction(source.json, req.action, req.loginData, req.isLongClick)
                call.respond(outcome)
            }
            get("/login-check") {
                if (auth.requireSession(call) == null) return@get
                val source = database.getSource(call.parameters["id"]!!) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@get }
                val check = runner.checkLoginStatus(source.json)
                call.respond(check)
            }
            route("/browser") {
                post("/session") {
                    if (auth.requireSession(call, true) == null) return@post
                    val source = database.getSource(call.parameters["id"]!!) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@post }
                    val sourceObj = runCatching { Json.parseToJsonElement(source.json).jsonObject }.getOrNull()
                    val loginUrl = (sourceObj?.get("loginUrl") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.startsWith("http") }
                    val sourceUrl = (sourceObj?.get("bookSourceUrl") as? JsonPrimitive)?.contentOrNull?.trim()
                    val homeUrl = sourceUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    // 书源 JS 里的 java.startBrowserAwait(url) 会给出真实入口，优先级最高
                    val requestedRaw = runCatching { call.receive<SourceBrowserSessionRequest>() }.getOrNull()?.url?.trim()
                    val requestedHttp = requestedRaw?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    val inlineOnly = requestedRaw != null && requestedHttp == null && webView.isInlineDataUrl(requestedRaw)
                    if (requestedRaw != null && requestedHttp == null && !inlineOnly) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("unsupported_url", "内置浏览器仅支持 HTTP(S) 地址或书源脚本生成的内置页面"))
                        return@post
                    }
                    val startUrl = requestedHttp ?: loginUrl ?: homeUrl ?: WebViewProxy.defaultStartUrl(source.json)
                    if (startUrl == null && !inlineOnly) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("no_login_url", "该书源未配置 loginUrl，且书源内容中没有任何网址，无法打开内置浏览器"))
                        return@post
                    }
                    val token = webView.issueTicket(source.id, source.json, startUrl)
                    call.application.log.info("webview session opened for source: {}, start: {}, inline: {}", source.id, startUrl ?: "(内置页面)", inlineOnly)
                    call.respond(
                        SourceBrowserSessionResponse(
                            token = token,
                            startUrl = startUrl ?: "",
                            expiresInSeconds = 1800,
                            inlineOnly = inlineOnly,
                        )
                    )
                }
                delete("/session") {
                    if (auth.requireSession(call, true) == null) return@delete
                    call.request.queryParameters["t"]?.let { webView.revokeTicket(it) }
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/cookies") {
                    if (auth.requireSession(call) == null) return@get
                    val sourceId = call.parameters["id"]!!
                    val jar = withContext(Dispatchers.IO) { webView.jarSnapshot(sourceId) }
                    call.respond(SourceBrowserCookieResponse(count = jar.size, domains = jar.keys.toList(), cookies = jar))
                }
                get("/page") {
                    val sourceId = call.parameters["id"]!!
                    val token = call.request.queryParameters["t"]
                    if (token.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("missing_parameter", "缺少 token 参数"))
                        return@get
                    }
                    // i = 服务端托管的内置页面（书源 JS 生成的数据地址，直接进 URL 会超出请求行上限）
                    val inlineKey = call.request.queryParameters["i"]
                    if (!inlineKey.isNullOrBlank()) {
                        call.respondProxied { webView.openInlinePage(sourceId, token, inlineKey) }
                        return@get
                    }
                    val target = call.request.queryParameters["u"]
                    if (target.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("missing_parameter", "缺少 u 参数"))
                        return@get
                    }
                    call.respondProxied { webView.openPage(sourceId, token, target) }
                }
                post("/inline") {
                    if (auth.requireSession(call, true) == null) return@post
                    val sourceId = call.parameters["id"]!!
                    val req = call.receive<SourceBrowserInlineRequest>()
                    try {
                        val key = webView.registerInlinePage(sourceId, req.token, req.url)
                        call.respond(SourceBrowserInlineResponse(key = key))
                    } catch (error: WebViewException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("inline_page_failed", error.message ?: "内置页面无法打开"))
                    }
                }
                get("/res") {
                    val sourceId = call.parameters["id"]!!
                    val token = call.request.queryParameters["t"]
                    val target = call.request.queryParameters["u"]
                    if (token.isNullOrBlank() || target.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("missing_parameter", "缺少 token 或 u 参数"))
                        return@get
                    }
                    call.respondProxied { webView.openResource(sourceId, token, target) }
                }
                get("/submit") {
                    val sourceId = call.parameters["id"]!!
                    val token = call.request.queryParameters["t"]
                    val base = call.request.queryParameters["u"]
                    if (token.isNullOrBlank() || base.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("missing_parameter", "缺少 token 或 u 参数"))
                        return@get
                    }
                    val extras = call.request.queryParameters.entries()
                        .filter { it.key != "t" && it.key != "u" }
                        .flatMap { entry -> entry.value.map { "${URLEncoder.encode(entry.key, Charsets.UTF_8)}=${URLEncoder.encode(it, Charsets.UTF_8)}" } }
                    val target = if (extras.isEmpty()) base else base + (if (base.contains('?')) "&" else "?") + extras.joinToString("&")
                    call.respondProxied { webView.submitForm(sourceId, token, target, "GET", null, ByteArray(0)) }
                }
                post("/submit") {
                    val sourceId = call.parameters["id"]!!
                    val token = call.request.queryParameters["t"]
                    val target = call.request.queryParameters["u"]
                    if (token.isNullOrBlank() || target.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("missing_parameter", "缺少 token 或 u 参数"))
                        return@post
                    }
                    val body = call.receiveStream().readBytes()
                    val contentType = call.request.headers[HttpHeaders.ContentType]
                    call.respondProxied { webView.submitForm(sourceId, token, target, "POST", contentType, body) }
                }
            }
        }
        post("/search") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<SearchRequest>()
            if (request.keyword.isBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_keyword", "请输入搜索关键词")); return@post }
            val sourceRecords = database.listSearchSourceRecords(request.sourceIds)
            val results = boundedConcurrentMap(sourceRecords, sourceSearchConcurrency()) { source -> readableSearchResults(runner, source.json, request.keyword) }.flatten()
            call.respond(results)
        }
        webSocket("/search/stream") {
            val csrf = call.request.queryParameters["csrf"]
            if (!auth.hasWebSocketSession(call, csrf)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "请先登录"))
                return@webSocket
            }
            val request = (incoming.receive() as? Frame.Text)?.readText()?.let { text -> runCatching { Json.decodeFromString<SearchRequest>(text) }.getOrNull() }
            if (request?.keyword.isNullOrBlank()) {
                send(Frame.Text(Json.encodeToString(SearchStreamEvent("error", message = "请输入搜索关键词"))))
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "搜索条件无效"))
                return@webSocket
            }
            val sourceRecords = database.listSearchSourceRecords(request!!.sourceIds)
            send(Frame.Text(Json.encodeToString(SearchStreamEvent("start", totalSources = sourceRecords.size))))
            coroutineScope {
                val events = Channel<SearchStreamEvent>(Channel.BUFFERED)
                val counters = SearchStreamCounters(sourceRecords.size)
                val semaphore = Semaphore(sourceSearchConcurrency())
                val workers = sourceRecords.map { source -> async {
                    semaphore.withPermit {
                        if (!isActive) return@async
                        val outcome = searchSourceOutcome(runner, source.json, request.keyword)
                        if (!isActive) return@async
                        runCatching {
                            if (outcome.results.isNotEmpty()) events.send(SearchStreamEvent("results", results = outcome.results))
                            events.send(counters.complete(outcome))
                        }
                    }
                } }
                val completionJob = launch {
                    try {
                        workers.awaitAll()
                        runCatching { events.send(counters.snapshot("done")) }
                    } catch (_: CancellationException) {
                        // Workers were cancelled
                    } finally {
                        events.close()
                    }
                }
                val cancellationMonitor = launch {
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text && frame.readText().contains("\"cancel\"")) break
                        }
                    } catch (_: Throwable) {
                        // Incoming channel closed
                    } finally {
                        workers.forEach { it.cancel() }
                        completionJob.cancel()
                        events.close()
                    }
                }
                try {
                    for (event in events) send(Frame.Text(Json.encodeToString(event)))
                } catch (_: Throwable) {
                    // Socket closed or disconnected
                } finally {
                    cancellationMonitor.cancel()
                    completionJob.cancel()
                    workers.forEach { it.cancel() }
                    events.close()
                }
            }
        }
        get("/subscriptions") {
            if (auth.requireSession(call) == null) return@get
            call.respond(database.listSubscriptions())
        }
        post("/subscriptions") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<SubscriptionWriteRequest>()
            try {
                validateSubscriptionUrl(request.url)
                call.respond(database.saveSubscription(request))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_subscription", error.message ?: "订阅地址无效"))
            }
        }
        delete("/subscriptions/{id}") {
            if (auth.requireSession(call, true) == null) return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("invalid_subscription", "订阅标识无效"))
            if (database.deleteSubscription(id)) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ApiError("not_found", "订阅不存在"))
        }
        post("/subscriptions/{id}/update") {
            if (auth.requireSession(call, true) == null) return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_subscription", "订阅标识无效"))
            try { call.respond(subscriptions.updateOne(id)) }
            catch (_: NoSuchElementException) { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "订阅不存在")) }
            catch (error: Throwable) { call.respond(HttpStatusCode.BadGateway, ApiError("subscription_update_failed", error.message ?: "订阅更新失败")) }
        }
        post("/subscriptions/update") {
            if (auth.requireSession(call, true) == null) return@post
            val results = subscriptions.updateAll()
            call.respond(mapOf("updated" to results.count { it.second.isSuccess }, "failed" to results.count { it.second.isFailure }))
        }
        post("/books/details") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<BookRequest>(); val source = database.getSource(request.sourceId) ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在")); return@post }
            call.respondCatching { runner.details(source.json, request.bookUrl) }
        }
        post("/books/chapters") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<BookRequest>()
            val source = database.getSource(request.sourceId) ?: run {
                call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在"))
                return@post
            }
            val cachedToc = database.getTocCache(request.sourceId, request.bookUrl)
            if (cachedToc != null && cachedToc.isNotEmpty()) {
                call.respond(cachedToc)
                return@post
            }
            val fallbackFromContent = database.getCachedChaptersFallback(request.sourceId, request.bookUrl)
            call.respondCatching {
                try {
                    withTimeout(4000) {
                        val chapters = runCatching { runner.chapters(source.json, request.bookUrl) }.getOrElse {
                            val details = runner.details(source.json, request.bookUrl)
                            val toc = runner.chapters(source.json, details.tocUrl)
                            database.saveTocCache(request.sourceId, details.tocUrl, toc)
                            toc
                        }
                        if (chapters.isNotEmpty()) {
                            database.saveTocCache(request.sourceId, request.bookUrl, chapters)
                        }
                        chapters
                    }
                } catch (error: Throwable) {
                    if (fallbackFromContent.isNotEmpty()) {
                        fallbackFromContent
                    } else {
                        throw (error as? RuleExecutionException ?: RuleExecutionException(error.message ?: "获取章节列表失败"))
                    }
                }
            }
        }
        route("/replace-rules") {
            get {
                if (auth.requireSession(call) == null) return@get
                val q = call.request.queryParameters["q"]
                val group = call.request.queryParameters["group"]
                val scope = call.request.queryParameters["scope"] ?: call.request.queryParameters["bookName"]
                call.respond(database.listReplaceRules(q, group, scope))
            }
            get("/{id}") {
                if (auth.requireSession(call) == null) return@get
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "缺少规则 ID"))
                database.getReplaceRule(id)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NotFound, ApiError("not_found", "规则不存在"))
            }
            post {
                if (auth.requireSession(call, true) == null) return@post
                val rule = call.receive<ReplaceRule>()
                if (rule.pattern.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_rule", "匹配模式不能为空"))
                    return@post
                }
                call.respond(database.saveReplaceRule(rule))
            }
            put("/{id}") {
                if (auth.requireSession(call, true) == null) return@put
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "缺少规则 ID"))
                val rule = call.receive<ReplaceRule>().copy(id = id)
                if (rule.pattern.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_rule", "匹配模式不能为空"))
                    return@put
                }
                call.respond(database.saveReplaceRule(rule))
            }
            delete("/{id}") {
                if (auth.requireSession(call, true) == null) return@delete
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "缺少规则 ID"))
                if (database.deleteReplaceRule(id)) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, ApiError("not_found", "规则不存在"))
            }
            post("/delete-batch") {
                if (auth.requireSession(call, true) == null) return@post
                val ids = call.receive<List<String>>()
                val count = database.deleteReplaceRules(ids)
                call.respond(mapOf("deleted" to count))
            }
            post("/toggle") {
                if (auth.requireSession(call, true) == null) return@post
                val req = call.receive<ReplaceRuleToggleRequest>()
                val count = database.toggleReplaceRules(req.ids, req.enabled)
                call.respond(mapOf("updated" to count))
            }
            get("/export") {
                if (auth.requireSession(call) == null) return@get
                val ids = call.request.queryParameters.getAll("id")
                call.respondText(
                    text = Json.encodeToString(database.exportReplaceRules(ids)),
                    contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
                )
            }
            post("/import") {
                if (auth.requireSession(call, true) == null) return@post
                val bodyText = call.receiveText()
                val parsedRules = parseImportedReplaceRules(bodyText)
                val response = database.importReplaceRules(parsedRules)
                call.respond(response)
            }
            post("/preview") {
                if (auth.requireSession(call) == null) return@post
                val req = call.receive<ReplaceRulePreviewRequest>()
                val rules = if (req.rule != null) listOf(req.rule) else database.getEnabledReplaceRulesForScope(req.bookName, req.sourceUrl)
                val jsSandbox = JsSandbox(runner)
                val cleaned = ContentProcessor.processContent(req.text, rules, jsSandbox = jsSandbox, bookName = req.bookName)
                val appliedNames = rules.filter { ContentProcessor.applyRule(req.text, it, jsSandbox = jsSandbox, bookName = req.bookName) != req.text }.map { it.name.ifBlank { it.pattern } }
                call.respond(ReplaceRulePreviewResponse(
                    originalText = req.text,
                    cleanedText = cleaned,
                    changed = cleaned != req.text,
                    appliedRules = appliedNames,
                ))
            }
        }
        post("/books/content") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<ContentRequest>()
            if (request.sourceId.isBlank() || request.chapterUrl.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_content", "缺少书源或章节地址"))
                return@post
            }
            val source = database.getSource(request.sourceId) ?: run {
                call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书源不存在"))
                return@post
            }
            val bookName = request.bookUrl?.let { url ->
                database.listBookshelf().firstOrNull { it.sourceId == request.sourceId && it.bookUrl == url }?.name
            }
            val cached = request.bookUrl?.let { database.cachedContent(request.sourceId, it, request.chapterUrl) }
            if (cached != null && cached.content.isNotBlank()) {
                call.respond(cached)
            } else call.respondCatching {
                try {
                    runner.content(source.json, request.chapterUrl, bookName).also { content ->
                        request.bookUrl?.let { database.cacheBookContent(request.sourceId, it, request.chapterUrl, content) }
                    }
                } catch (error: Throwable) {
                    if (cached != null && cached.content.isNotBlank()) cached
                    else throw error
                }
            }
        }
        get("/bookshelf") {
            if (auth.requireSession(call) == null) return@get
            call.respond(database.listBookshelf())
        }
        post("/bookshelf") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<BookshelfWriteRequest>()
            if (request.sourceId.isBlank() || request.bookUrl.isBlank() || request.name.isBlank() || request.tocUrl.isBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bookshelf", "书架数据无效")); return@post }
            val immediateCover = tryFindCachedCover(coverCache, request.coverUrl, request.alternateSources)
            val item = database.saveBookshelf(request, immediateCover)
            bookCache.enqueue(CachedBookRequest(request.sourceId, request.bookUrl, request.tocUrl))
            if (immediateCover == null && (!request.coverUrl.isNullOrBlank() || !request.alternateSources.isNullOrEmpty())) {
                application.launch(Dispatchers.IO) {
                    val cached = tryCacheCover(coverCache, request.coverUrl, request.alternateSources)
                    if (cached != null) {
                        database.updateBookshelfCover(request.sourceId, request.bookUrl, cached.key, cached.contentType)
                    }
                }
            }
            call.respond(item)
        }
        delete("/bookshelf") {
            if (auth.requireSession(call, true) == null) return@delete
            val sourceId = call.request.queryParameters["sourceId"]; val bookUrl = call.request.queryParameters["bookUrl"]
            if (sourceId.isNullOrBlank() || bookUrl.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bookshelf", "缺少书籍标识")); return@delete }
            bookCache.cancel(sourceId, bookUrl)
            database.removeBookshelf(sourceId, bookUrl)?.let(coverCache::delete)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/bookshelf/cache") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<BookCacheRangeRequest>()
            val item = database.listBookshelf().firstOrNull { it.sourceId == request.sourceId && it.bookUrl == request.bookUrl }
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书籍不在书架中"))
            bookCache.enqueue(CachedBookRequest(
                item.sourceId,
                item.bookUrl,
                item.tocUrl,
                request.startIndex,
                request.endIndex,
                request.count
            ))
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
        }
        get("/bookshelf/cached-chapters") {
            if (auth.requireSession(call, false) == null) return@get
            val sourceId = call.request.queryParameters["sourceId"]
            val bookUrl = call.request.queryParameters["bookUrl"]
            if (sourceId.isNullOrBlank() || bookUrl.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bookshelf", "缺少书籍标识"))
                return@get
            }
            val cachedUrls = database.cachedChapterUrls(sourceId, bookUrl).toList()
            val total = database.getTocCache(sourceId, bookUrl)?.size ?: 0
            call.respond(CachedChaptersResponse(sourceId, bookUrl, cachedUrls, cachedUrls.size, total))
        }
        delete("/bookshelf/cache") {
            if (auth.requireSession(call, true) == null) return@delete
            val sourceId = call.request.queryParameters["sourceId"]
            val bookUrl = call.request.queryParameters["bookUrl"]
            val clearData = call.request.queryParameters["clearData"]?.toBooleanStrictOrNull() ?: false
            if (sourceId.isNullOrBlank() || bookUrl.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bookshelf", "缺少书籍标识"))
                return@delete
            }
            bookCache.cancel(sourceId, bookUrl)
            if (clearData) {
                database.clearBookCacheContent(sourceId, bookUrl)
            }
            call.respond(HttpStatusCode.NoContent)
        }
        put("/bookshelf/status") {
            if (auth.requireSession(call, true) == null) return@put
            val request = call.receive<BookshelfStatusRequest>()
            if (request.sourceId.isBlank() || request.bookUrl.isBlank()) return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bookshelf", "缺少书籍标识"))
            database.setBookshelfCompleted(request.sourceId, request.bookUrl, request.completed)?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书籍不在书架中"))
        }
        put("/bookshelf/info") {
            if (auth.requireSession(call, true) == null) return@put
            val request = call.receive<BookshelfInfoUpdateRequest>()
            if (request.sourceId.isBlank() || request.bookUrl.isBlank() || request.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bookshelf", "书名不能为空"))
                return@put
            }
            val cover = request.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { coverCache.cache(url) }.getOrNull()
            }
            database.updateBookshelfInfo(request, cover)?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, ApiError("not_found", "书籍不在书架中"))
        }
        post("/bookshelf/switch-source") {
            if (auth.requireSession(call, true) == null) return@post
            val request = call.receive<BookshelfSourceSwitchRequest>()
            val book = request.book
            if (request.oldSourceId.isBlank() || request.oldBookUrl.isBlank() || book.sourceId.isBlank() || book.bookUrl.isBlank() || book.name.isBlank() || book.tocUrl.isBlank()) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bookshelf", "书架数据无效")); return@post }
            val altList = (request.alternateSources ?: emptyList()) + (book.alternateSources ?: emptyList())
            val cover = tryCacheCover(coverCache, book.coverUrl, altList)
            bookCache.cancel(request.oldSourceId, request.oldBookUrl)
            val (item, orphan) = database.switchBookshelf(request.oldSourceId, request.oldBookUrl, book, cover, request.alternateSources)
            orphan?.let(coverCache::delete)
            bookCache.enqueue(CachedBookRequest(book.sourceId, book.bookUrl, book.tocUrl))
            call.respond(item)
        }
        get("/covers/{key}") {
            if (auth.requireSession(call) == null) return@get
            val key = call.parameters["key"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val file = coverCache.file(key) ?: return@get call.respond(HttpStatusCode.NotFound)
            val type = database.coverContentType(key)?.let(ContentType::parse) ?: ContentType.Application.OctetStream
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 7 * 24 * 60 * 60, visibility = CacheControl.Visibility.Private))
            call.respond(LocalFileContent(file.toFile(), type))
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
            if (progress.sourceId.isBlank() || progress.bookUrl.isBlank() || progress.chapterUrl.isBlank() || progress.chapterIndex < 0 || !progress.scrollPosition.isFinite() || progress.scrollPosition !in 0.0..1.0) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_progress", "阅读进度无效")); return@put }
            call.respond(database.saveProgress(progress))
        }
        route("/tts") {
            get("/voices") {
                if (auth.requireSession(call) == null) return@get
                call.respond(edgeTts.listVoices())
            }
            post("/session") {
                val session = auth.requireSession(call, true) ?: return@post
                call.respond(ttsSessions.create(session.id))
            }
            post("/session/{id}/chunks") {
                val owner = auth.requireSession(call, true) ?: return@post
                val session = ttsSessions.get(call.parameters["id"] ?: "", owner.id)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiError("tts_session_not_found", "朗读会话不存在"))
                        return@post
                    }
                try {
                    val accepted = session.append(call.receive<TtsSessionChunkRequest>())
                    if (!accepted) {
                        call.respond(HttpStatusCode.Conflict, ApiError("tts_session_busy", "朗读队列已满"))
                    } else {
                        call.respond(TtsSessionAck(accepted = true))
                    }
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_tts_chunk", error.message ?: "朗读分片无效"))
                }
            }
            post("/session/{id}/control") {
                val owner = auth.requireSession(call, true) ?: return@post
                val session = ttsSessions.get(call.parameters["id"] ?: "", owner.id)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiError("tts_session_not_found", "朗读会话不存在"))
                        return@post
                    }
                val request = call.receive<TtsSessionControlRequest>()
                when (request.action) {
                    "pause" -> session.pause()
                    "resume" -> session.resume()
                    "stop" -> ttsSessions.remove(session)
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_tts_action", "不支持的朗读操作"))
                        return@post
                    }
                }
                call.respond(TtsSessionAck(accepted = true))
            }
            delete("/session/{id}") {
                val owner = auth.requireSession(call, true) ?: return@delete
                val session = ttsSessions.get(call.parameters["id"] ?: "", owner.id)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiError("tts_session_not_found", "朗读会话不存在"))
                        return@delete
                    }
                ttsSessions.remove(session)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/session/{id}/audio") {
                val owner = auth.requireSession(call) ?: return@get
                val session = ttsSessions.get(call.parameters["id"] ?: "", owner.id)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiError("tts_session_not_found", "朗读会话不存在"))
                        return@get
                    }
                call.response.cacheControl(CacheControl.NoStore(null))
                call.response.header("X-Accel-Buffering", "no")
                call.response.header("Cache-Control", "no-cache, no-transform")
                call.response.header("Connection", "keep-alive")
                call.respondBytesWriter(ContentType.Audio.MPEG, status = HttpStatusCode.OK) {
                    session.streamAudio(this)
                }
            }
            get("/session/{id}/events") {
                val owner = auth.requireSession(call) ?: return@get
                val session = ttsSessions.get(call.parameters["id"] ?: "", owner.id)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiError("tts_session_not_found", "朗读会话不存在"))
                        return@get
                    }
                call.response.cacheControl(CacheControl.NoStore(null))
                call.response.header("X-Accel-Buffering", "no")
                call.response.header("Cache-Control", "no-cache, no-transform")
                call.response.header("Connection", "keep-alive")
                call.respondTextWriter(ContentType.Text.EventStream) {
                    session.streamEvents(this)
                }
            }
            post("/speak") {
                if (auth.requireSession(call) == null) return@post
                val req = call.receive<TtsSpeakRequest>()
                val text = req.text.trim()
                if (text.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_text", "朗读文本不能为空"))
                    return@post
                }
                try {
                    if (req.engine == "custom" && !req.customUrl.isNullOrBlank()) {
                        val (contentType, audioBytes) = edgeTts.synthesizeCustom(req)
                        call.respondBytes(
                            bytes = audioBytes,
                            contentType = ContentType.parse(contentType),
                            status = HttpStatusCode.OK,
                        )
                    } else {
                        val audioBytes = edgeTts.synthesize(
                            text = text,
                            voice = req.voice.ifBlank { "zh-CN-XiaoxiaoNeural" },
                            rate = req.rate,
                            pitch = req.pitch,
                        )
                        call.respondBytes(
                            bytes = audioBytes,
                            contentType = ContentType.Audio.MPEG,
                            status = HttpStatusCode.OK,
                        )
                    }
                } catch (e: Exception) {
                    call.application.log.warn("TTS synthesis failed for voice={}: {}", req.voice, e.message)
                    call.respond(HttpStatusCode.InternalServerError, ApiError("tts_failed", e.message ?: "语音合成失败"))
                }
            }
            get("/speak") {
                if (auth.requireSession(call) == null) return@get
                val text = call.request.queryParameters["text"]?.trim() ?: ""
                if (text.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_text", "朗读文本不能为空"))
                    return@get
                }
                val voice = call.request.queryParameters["voice"] ?: "zh-CN-XiaoxiaoNeural"
                val rate = call.request.queryParameters["rate"]?.toIntOrNull() ?: 0
                val pitch = call.request.queryParameters["pitch"]?.toIntOrNull() ?: 0
                try {
                    val audioBytes = edgeTts.synthesize(text = text, voice = voice, rate = rate, pitch = pitch)
                    call.respondBytes(
                        bytes = audioBytes,
                        contentType = ContentType.Audio.MPEG,
                        status = HttpStatusCode.OK,
                    )
                } catch (e: Exception) {
                    call.application.log.warn("TTS get speak failed: {}", e.message)
                    call.respond(HttpStatusCode.InternalServerError, ApiError("tts_failed", e.message ?: "语音合成失败"))
                }
            }
        }
    }
}

private fun validateSubscriptionUrl(value: String) {
    val uri = runCatching { java.net.URI(value) }.getOrNull()
    require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) { "仅允许 HTTP(S) 订阅地址" }
}

private const val SEARCH_TIMEOUT_MS = 30_000L

private suspend fun readableSearchResults(runner: RuleRunner, sourceJson: String, keyword: String): List<SearchResult> =
    searchSourceOutcome(runner, sourceJson, keyword).results

internal data class SearchSourceOutcome(val results: List<SearchResult>, val failed: Boolean)

internal class SearchStreamCounters(private val totalSources: Int) {
    private val completed = AtomicInteger()
    private val matched = AtomicInteger()
    private val empty = AtomicInteger()
    private val failed = AtomicInteger()
    private val resultCount = AtomicInteger()

    fun complete(outcome: SearchSourceOutcome): SearchStreamEvent {
        when {
            outcome.results.isNotEmpty() -> {
                matched.incrementAndGet()
                resultCount.addAndGet(outcome.results.size)
            }
            outcome.failed -> failed.incrementAndGet()
            else -> empty.incrementAndGet()
        }
        completed.incrementAndGet()
        return snapshot()
    }

    fun snapshot(type: String = "progress") = SearchStreamEvent(
        type = type,
        totalSources = totalSources,
        completedSources = completed.get(),
        matchedSources = matched.get(),
        emptySources = empty.get(),
        failedSources = failed.get(),
        resultCount = resultCount.get(),
    )
}

internal suspend fun searchSourceOutcome(runner: RuleRunner, sourceJson: String, keyword: String): SearchSourceOutcome {
    val results = try {
        withTimeout(SEARCH_TIMEOUT_MS) { withContext(Dispatchers.IO) { runner.search(sourceJson, keyword) } }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        return SearchSourceOutcome(emptyList(), failed = true)
    }
    return SearchSourceOutcome(results, failed = false)
}

internal fun sourceSearchConcurrency(processors: Int = Runtime.getRuntime().availableProcessors()): Int =
    processors.coerceIn(16, 32)

internal suspend fun <T, R> boundedConcurrentMap(values: List<T>, limit: Int, action: suspend (T) -> R): List<R> = coroutineScope {
    val semaphore = Semaphore(limit.coerceAtLeast(1))
    values.map { value -> async { semaphore.withPermit { action(value) } } }.awaitAll()
}

private suspend fun ApplicationCall.respondCatching(block: suspend () -> Any) {
    try { respond(block()) }
    catch (error: RuleExecutionException) { respond(HttpStatusCode.BadGateway, ApiError("source_execution_failed", error.message ?: "书源执行失败")) }
    catch (error: Exception) { respond(HttpStatusCode.BadGateway, ApiError("source_execution_failed", error.message ?: "书源执行失败")) }
}

internal fun tryFindCachedCover(coverCache: CoverCache, primaryUrl: String?, alternateSources: List<SearchResult>?): CachedCover? {
    val candidates = buildList {
        primaryUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        alternateSources?.forEach { s ->
            s.coverUrl?.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
        }
    }
    for (url in candidates) {
        val cached = coverCache.getIfCached(url)
        if (cached != null) return cached
    }
    return null
}

internal fun tryCacheCover(coverCache: CoverCache, primaryUrl: String?, alternateSources: List<SearchResult>?): CachedCover? {
    val candidates = buildList {
        primaryUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        alternateSources?.forEach { s ->
            s.coverUrl?.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
        }
    }
    for (url in candidates) {
        val cached = runCatching { coverCache.cache(url) }.getOrNull()
        if (cached != null) return cached
    }
    return null
}

private fun jsonEvent(message: String): String = Json.encodeToString(message)

/**
 * 把代理结果写回响应；失败时返回一张可读的 HTML 错误页（因为它会被渲染进内置浏览器 iframe）。
 */
private suspend fun ApplicationCall.respondProxied(block: () -> ProxiedPayload) {
    // iframe 处于 sandbox 不透明源下，页面内 JS 发起的请求需要 CORS 放行；
    // 这些端点本身由一次性令牌鉴权，不依赖管理会话，因此放开 * 不会泄露权限。
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    try {
        val payload = withContext(Dispatchers.IO) { block() }
        respondBytes(
            bytes = payload.body,
            contentType = runCatching { ContentType.parse(payload.contentType) }.getOrNull(),
            status = HttpStatusCode.fromValue(payload.status),
        )
    } catch (error: WebViewException) {
        application.log.warn("webview proxy failed: {}", error.message)
        respondText(
            text = webViewErrorPage(error.message ?: "内置浏览器请求失败"),
            contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8),
            status = HttpStatusCode.BadGateway,
        )
    }
}

private fun webViewErrorPage(message: String): String {
    val escaped = message
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8"><title>内置浏览器无法打开页面</title>
        <style>
          body{margin:0;padding:32px;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;
               background:#0f1115;color:#e6e8eb;display:flex;align-items:center;justify-content:center;min-height:100vh}
          .card{max-width:520px;background:#171a21;border:1px solid #262b36;border-radius:12px;padding:24px}
          h1{margin:0 0 12px;font-size:16px;color:#f87171}
          p{margin:0;font-size:13px;line-height:1.7;color:#9aa4b2;word-break:break-all}
        </style></head>
        <body><div class="card"><h1>无法打开该页面</h1><p>$escaped</p></div></body></html>
    """.trimIndent()
}

private suspend fun parseImportedReplaceRules(bodyText: String): List<ReplaceRule> {
    val cleanText = bodyText.trim().removePrefix("\uFEFF")
    if (cleanText.isBlank()) return emptyList()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val jsonElement = runCatching { json.parseToJsonElement(cleanText) }.getOrNull() ?: return emptyList()

    // Check if it's an object with "url"
    if (jsonElement is kotlinx.serialization.json.JsonObject) {
        val url = jsonElement["url"]?.let { if (it is JsonPrimitive) it.contentOrNull else null }
        if (!url.isNullOrBlank()) {
            validateSubscriptionUrl(url)
            val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
            val req = java.net.http.HttpRequest.newBuilder().uri(java.net.URI(url)).GET().build()
            val resp = withContext(Dispatchers.IO) { client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)) }
            return parseImportedReplaceRules(resp.body())
        }
    }

    val array = when (jsonElement) {
        is kotlinx.serialization.json.JsonArray -> jsonElement
        is kotlinx.serialization.json.JsonObject -> {
            (jsonElement["rules"] ?: jsonElement["data"] ?: jsonElement["replaceRules"] ?: jsonElement["items"]) as? kotlinx.serialization.json.JsonArray
        }
        else -> null
    } ?: return emptyList()

    return array.mapNotNull { item ->
        if (item !is kotlinx.serialization.json.JsonObject) return@mapNotNull null
        val pattern = item["pattern"]?.let { if (it is JsonPrimitive) it.contentOrNull else null } ?: return@mapNotNull null
        if (pattern.isBlank()) return@mapNotNull null
        val id = item["id"]?.let { if (it is JsonPrimitive) it.contentOrNull else null } ?: ""
        val name = item["name"]?.let { if (it is JsonPrimitive) it.contentOrNull else null } ?: pattern
        val group = item["group"]?.let { if (it is JsonPrimitive) it.contentOrNull else null } ?: item["groupName"]?.let { if (it is JsonPrimitive) it.contentOrNull else null }
        val replacement = item["replacement"]?.let { if (it is JsonPrimitive) it.contentOrNull else null } ?: ""
        val isRegex = item["isRegex"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toBooleanStrictOrNull() ?: (it.contentOrNull?.toIntOrNull() != 0) else null } ?: true
        val scope = item["scope"]?.let { if (it is JsonPrimitive) it.contentOrNull else null }
        val excludeScope = item["excludeScope"]?.let { if (it is JsonPrimitive) it.contentOrNull else null }
        val scopeTitle = item["scopeTitle"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toBooleanStrictOrNull() ?: (it.contentOrNull?.toIntOrNull() == 1) else null } ?: false
        val scopeContent = item["scopeContent"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toBooleanStrictOrNull() ?: (it.contentOrNull?.toIntOrNull() != 0) else null } ?: true
        val isEnabled = item["isEnabled"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toBooleanStrictOrNull() ?: (it.contentOrNull?.toIntOrNull() != 0) else null } ?: true
        val order = item["order"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toIntOrNull() else null } ?: item["sortOrder"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toIntOrNull() else null } ?: 0
        val timeoutMs = item["timeoutMillisecond"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toLongOrNull() else null } ?: item["timeout_ms"]?.let { if (it is JsonPrimitive) it.contentOrNull?.toLongOrNull() else null } ?: 3000L

        ReplaceRule(
            id = id,
            name = name,
            group = group,
            pattern = pattern,
            replacement = replacement,
            isRegex = isRegex,
            scope = scope,
            excludeScope = excludeScope,
            scopeTitle = scopeTitle,
            scopeContent = scopeContent,
            isEnabled = isEnabled,
            order = order,
            timeoutMillisecond = timeoutMs,
        )
    }
}

private suspend fun probeSingleSourceHealth(
    client: java.net.http.HttpClient,
    sourceId: String,
    sourceName: String,
    rawJson: String,
    timeoutMs: Long,
): SourceHealthCheckItem {
    val validation = SourceCodec.validate(rawJson)
    if (!validation.valid) {
        return SourceHealthCheckItem(
            id = sourceId,
            name = sourceName,
            ok = false,
            latencyMs = 0L,
            statusCode = 0,
            statusCategory = "failed",
            error = validation.errors.firstOrNull() ?: "书源规则语法无效",
        )
    }

    val sourceObj = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
    val isJs = sourceObj?.get("mainJs")?.let { (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() } == true
    val bookSourceUrl = (sourceObj?.get("bookSourceUrl") as? JsonPrimitive)?.contentOrNull?.trim()
    val searchUrl = (sourceObj?.get("searchUrl") as? JsonPrimitive)?.contentOrNull?.trim()

    val candidateUrl = when {
        bookSourceUrl?.startsWith("http://", ignoreCase = true) == true || bookSourceUrl?.startsWith("https://", ignoreCase = true) == true -> bookSourceUrl
        searchUrl?.startsWith("http://", ignoreCase = true) == true || searchUrl?.startsWith("https://", ignoreCase = true) == true -> searchUrl.substringBefore("{{").substringBefore("@").substringBefore(",")
        else -> null
    }

    if (candidateUrl == null) {
        return if (isJs) {
            SourceHealthCheckItem(
                id = sourceId,
                name = sourceName,
                ok = true,
                latencyMs = 1L,
                statusCode = 200,
                statusCategory = "valid",
                error = null,
            )
        } else {
            SourceHealthCheckItem(
                id = sourceId,
                name = sourceName,
                ok = false,
                latencyMs = 0L,
                statusCode = 0,
                statusCategory = "failed",
                error = "未配置可达的 HTTP(S) 地址",
            )
        }
    }

    val startNs = System.nanoTime()
    return try {
        val cleanUrl = candidateUrl.substringBefore("#").trim()
        val targetUri = java.net.URI.create(cleanUrl)
        try {
            NetworkSecurity.resolveAndValidateSafeHttpTarget(targetUri, "书源")
        } catch (ssrfEx: IllegalArgumentException) {
            return SourceHealthCheckItem(
                id = sourceId,
                name = sourceName,
                ok = false,
                latencyMs = 0L,
                statusCode = 0,
                statusCategory = "failed",
                error = ssrfEx.message ?: "安全校验未通过",
            )
        }

        val req = java.net.http.HttpRequest.newBuilder(targetUri)
            .timeout(java.time.Duration.ofMillis(timeoutMs))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .GET()
            .build()

        val resp = withContext(Dispatchers.IO) {
            client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
        }
        val latencyMs = (System.nanoTime() - startNs) / 1_000_000
        val code = resp.statusCode()

        when (code) {
            in 200..399 -> {
                val category = if (latencyMs > 2500) "slow" else "valid"
                SourceHealthCheckItem(
                    id = sourceId,
                    name = sourceName,
                    ok = true,
                    latencyMs = latencyMs,
                    statusCode = code,
                    statusCategory = category,
                    error = null,
                )
            }
            401, 403 -> {
                SourceHealthCheckItem(
                    id = sourceId,
                    name = sourceName,
                    ok = false,
                    latencyMs = latencyMs,
                    statusCode = code,
                    statusCategory = "blocked",
                    error = "访问受限 (HTTP $code)",
                )
            }
            else -> {
                SourceHealthCheckItem(
                    id = sourceId,
                    name = sourceName,
                    ok = false,
                    latencyMs = latencyMs,
                    statusCode = code,
                    statusCategory = "failed",
                    error = "响应异常 (HTTP $code)",
                )
            }
        }
    } catch (ex: java.net.http.HttpTimeoutException) {
        val latencyMs = (System.nanoTime() - startNs) / 1_000_000
        SourceHealthCheckItem(
            id = sourceId,
            name = sourceName,
            ok = false,
            latencyMs = latencyMs,
            statusCode = 0,
            statusCategory = "failed",
            error = "连接超时 (>${timeoutMs}ms)",
        )
    } catch (ex: java.net.UnknownHostException) {
        SourceHealthCheckItem(
            id = sourceId,
            name = sourceName,
            ok = false,
            latencyMs = 0L,
            statusCode = 0,
            statusCategory = "failed",
            error = "域名解析失败 (${ex.message ?: "DNS Error"})",
        )
    } catch (ex: javax.net.ssl.SSLException) {
        val latencyMs = (System.nanoTime() - startNs) / 1_000_000
        SourceHealthCheckItem(
            id = sourceId,
            name = sourceName,
            ok = false,
            latencyMs = latencyMs,
            statusCode = 0,
            statusCategory = "failed",
            error = "SSL证书或握手失败 (${ex.message ?: "SSL Error"})",
        )
    } catch (ex: java.net.ConnectException) {
        val latencyMs = (System.nanoTime() - startNs) / 1_000_000
        SourceHealthCheckItem(
            id = sourceId,
            name = sourceName,
            ok = false,
            latencyMs = latencyMs,
            statusCode = 0,
            statusCategory = "failed",
            error = "连接被拒绝 (${ex.message ?: "Connection Refused"})",
        )
    } catch (ex: Throwable) {
        val latencyMs = (System.nanoTime() - startNs) / 1_000_000
        SourceHealthCheckItem(
            id = sourceId,
            name = sourceName,
            ok = false,
            latencyMs = latencyMs,
            statusCode = 0,
            statusCategory = "failed",
            error = ex.message ?: "探测异常",
        )
    }
}


