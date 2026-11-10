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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * 规则订阅（对齐开源阅读 `RuleSub`）：一条订阅 = 地址 + 类型（书源 / 订阅源 / 替换规则）。
 *
 * - 手动导入走界面的预览 / 确认流程，不经过这里；
 * - 这里负责**不经确认的直接更新**：定时自动更新（条目开启 `autoUpdate` 且到了间隔）与「全部更新」。
 */
class SubscriptionService(
    private val database: Database,
    /** 测试接缝：注入后跳过真实网络（与 [NetworkSourceImport] 的 `fetcher` 同源）。 */
    private val fetcher: ((String) -> String)? = null,
    replaceRuleParser: suspend (String) -> List<ReplaceRule> = ::parseImportedReplaceRules,
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateMutex = Mutex()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val importer = OnlineImport(database, ::download, replaceRuleParser)
    private var scheduler: Job? = null

    fun start() {
        if (scheduler != null) return
        scheduler = scope.launch {
            while (true) {
                delay(TICK_MS)
                runCatching { updateDue() }
            }
        }
    }

    fun stop() { scope.cancel() }

    /** 更新全部已启用的订阅（「全部更新」按钮）。 */
    suspend fun updateAll(): List<Pair<Long, Result<ImportResponse>>> = updateMutex.withLock {
        database.listSubscriptions(enabledOnly = true).map { it.id to runCatching { update(it) } }
    }

    /** 只更新开启了自动更新且已到间隔的订阅（定时器）。 */
    suspend fun updateDue(now: Long = System.currentTimeMillis()): List<Pair<Long, Result<ImportResponse>>> = updateMutex.withLock {
        database.listSubscriptions(enabledOnly = true).filter { isDue(it, now) }.map { it.id to runCatching { update(it) } }
    }

    suspend fun updateOne(id: Long): ImportResponse = updateMutex.withLock {
        val subscription = database.getSubscription(id) ?: throw NoSuchElementException("订阅不存在")
        update(subscription)
    }

    private suspend fun update(subscription: SourceSubscription): ImportResponse {
        return try {
            val body = download(subscription.url)
            val kind = OnlineImport.kindOfSubscriptionType(subscription.type)
            // 类型填错（最常见：把订阅源地址填成书源）时逐条解析全部失败，
            // 必须报失败并说明原因，不能记成「更新成功、处理 0 条」。
            val detected = RssSourceCodec.detectKind(body)
            require(detected == null || detected == kind) { "订阅内容是${kindLabel(detected)}，与订阅类型「${kindLabel(kind)}」不符" }
            val result = try {
                importer.importBody(kind, body)
            } catch (error: NetworkImportException) {
                throw error
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("订阅内容${error.message ?: "解析失败"}")
            }
            database.recordSubscriptionSuccess(subscription.id, result, sha256(body))
            log("subscription updated: id=${subscription.id}, kind=$kind, imported=${result.imported}, updated=${result.updated}")
            result
        } catch (error: Throwable) {
            database.recordSubscriptionFailure(subscription.id, error.message ?: "订阅更新失败")
            throw error
        }
    }

    private fun download(url: String): String {
        fetcher?.let { return it(url) }
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

    private fun validate(uri: URI) {
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "订阅")
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TICK_MS = 10 * 60 * 1000L
        private const val MAX_REDIRECTS = 3
        private const val HOUR_MS = 60 * 60 * 1000L

        /** 到期判定：开启自动更新、间隔 > 0，且距上次尝试已满间隔（从未尝试过视为到期）。 */
        internal fun isDue(subscription: SourceSubscription, now: Long): Boolean =
            subscription.autoUpdate && subscription.updateInterval > 0 &&
                now - (subscription.lastAttemptAt ?: 0L) >= subscription.updateInterval * HOUR_MS

        private fun kindLabel(kind: String?): String = when (kind) {
            RssSourceCodec.KIND_BOOK_SOURCE -> "书源"
            RssSourceCodec.KIND_RSS_SOURCE -> "订阅源"
            RssSourceCodec.KIND_REPLACE_RULE -> "替换规则"
            RssSourceCodec.KIND_HTTP_TTS -> "朗读引擎"
            else -> "未知类型"
        }
    }
}
