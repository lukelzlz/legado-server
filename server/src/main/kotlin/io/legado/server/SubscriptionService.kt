package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

class SubscriptionService(private val database: Database, private val log: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateMutex = Mutex()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private var scheduler: Job? = null

    fun start() {
        if (scheduler != null) return
        scheduler = scope.launch {
            while (true) {
                delay(UPDATE_INTERVAL_MS)
                updateAll()
            }
        }
    }

    fun stop() { scope.cancel() }

    suspend fun updateAll(): List<Pair<Long, Result<ImportResponse>>> = updateMutex.withLock {
        database.listSubscriptions(enabledOnly = true).map { subscription ->
            subscription.id to runCatching { update(subscription) }
        }
    }

    suspend fun updateOne(id: Long): ImportResponse = updateMutex.withLock {
        val subscription = database.getSubscription(id) ?: throw NoSuchElementException("订阅不存在")
        update(subscription)
    }

    private fun update(subscription: SourceSubscription): ImportResponse {
        return try {
            val body = download(subscription.url)
            val sources = parseSources(body)
            val result = database.importSources(sources)
            database.recordSubscriptionSuccess(subscription.id, result, sha256(body))
            log("source subscription updated: id=${subscription.id}, imported=${result.imported}, updated=${result.updated}")
            result
        } catch (error: Throwable) {
            database.recordSubscriptionFailure(subscription.id, error.message ?: "订阅更新失败")
            throw error
        }
    }

    private fun download(url: String): String {
        var uri = URI(url)
        validate(uri)
        repeat(MAX_REDIRECTS + 1) {
            val response = client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent", "LegadoServer/0.1").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            response.body().use { body ->
                if (response.statusCode() in 300..399) {
                    uri = uri.resolve(response.headers().firstValue("location").orElseThrow { IllegalArgumentException("订阅重定向缺少地址") })
                    validate(uri)
                    return@repeat
                }
                require(response.statusCode() in 200..299) { "订阅上游返回 HTTP ${response.statusCode()}" }
                return body.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw IllegalArgumentException("订阅重定向次数超过限制")
    }

    private fun parseSources(body: String): List<String> {
        val cleanBody = body.trim().removePrefix("\uFEFF")
        val element = try { Json.parseToJsonElement(cleanBody) } catch (_: Exception) { throw IllegalArgumentException("订阅内容不是有效 JSON") }
        val values = when (element) {
            is JsonArray -> element
            is JsonObject -> when {
                element["data"] is JsonArray -> element["data"] as JsonArray
                element["sources"] is JsonArray -> element["sources"] as JsonArray
                element["bookSources"] is JsonArray -> element["bookSources"] as JsonArray
                element["list"] is JsonArray -> element["list"] as JsonArray
                else -> listOf(element)
            }
            else -> listOf(element)
        }
        return values.map { Json.encodeToString(JsonElement.serializer(), it) }
    }

    private fun validate(uri: URI) {
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "订阅")
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val MAX_REDIRECTS = 3
    }
}
