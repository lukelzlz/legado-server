package io.legado.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络书源导入：**从 URL 拉取书源集合 → 预览选择 → 按票据落库**。
 *
 * ## 为什么是「服务端代抓 + 内存票据」两步式（见 [ADR-023]）
 *
 * 1. **跨域**：社区书源站不提供 CORS 头（实测 `shuyuan.nyasama.net` 一个 CORS 头都没有，
 *    `shuyuan-api.yiove.com` 只回 `allow-credentials: true` 而无 `allow-origin`，属无效配置），
 *    浏览器 `fetch` 必被拦；只有服务端（无同源策略）能抓。
 * 2. **体积**：实测一条链接返回 **383 条 / 1.63 MB**。若让前端抓再回传，等于搬两趟，
 *    且 1.6 MB 的 JSON 要长时间驻留浏览器内存。
 * 3. **信任边界**：若由前端回传「要导入什么」，服务端的 SSRF 校验、体积上限、格式校验
 *    全部形同虚设。票据模式下客户端退化成一个**纯选择器**：只能从服务端**已经抓好**的东西里挑。
 *
 * 因此：`preview` 拉取并把**最终要落库的 JSON** 算好存进内存缓存，只回传元数据与一次性 token；
 * `commit` 凭 token 取回缓存、按用户勾选落库。
 */
class NetworkSourceImport(
    private val database: Database,
    private val log: (String) -> Unit = {},
    /**
     * 测试接缝：注入后**跳过真实网络**（与 [CoverCache] 的 `fetcher` 接缝同源）。
     *
     * 进程内注入的接缝不该承受网络侧的强校验（仓库既有教训），因此注入时不做 SSRF 校验；
     * SSRF / 协议校验本身用「不注入 fetcher」的用例覆盖（在真正发起请求前就会拒绝）。
     */
    private val fetcher: ((String) -> String)? = null,
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // 与 SubscriptionService 同源：手动处理重定向，每一跳都要过 SSRF 校验
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val random = SecureRandom()
    private val tickets = ConcurrentHashMap<String, CachedPreview>()

    /** 预览缓存条目。`items` 里的 payload 已在预览期算好，commit 只做挑选。 */
    private data class CachedPreview(
        val url: String,
        val items: List<PreparedItem>,
        val createdAt: Long,
    )

    /**
     * 一条书源在预览期的最终结论。
     *
     * @param status [STATUS_NEW] / [STATUS_UPDATE] / [STATUS_INVALID]
     * @param reason `invalid` 时的原因（直接来自 [SourceCodec.parse] 的报错），用于在弹窗里如实展示
     * @param targetId 落库 id。名称兜底命中时这里是**现有源的 id**（见 [prepareItem]）
     * @param payload 最终落库 JSON；`invalid` 为 null
     */
    private data class PreparedItem(
        val index: Int,
        val name: String,
        val url: String,
        val status: String,
        val reason: String? = null,
        val targetId: String? = null,
        val payload: String? = null,
    )

    // ------------------------------------------------------------------ 预览

    suspend fun preview(url: String): NetworkImportPreviewResponse = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (target.isEmpty()) throw NetworkImportException("import_url_required", "请填写书源地址")
        val body = download(target)
        val rawList = try {
            SourceCodec.parseSourceList(body)
        } catch (_: IllegalArgumentException) {
            throw NetworkImportException("import_content_invalid", "该地址返回的内容不是有效的书源 JSON")
        }
        if (rawList.isEmpty()) throw NetworkImportException("import_empty", "未从该地址解析出任何书源")

        // 现有书源：先用 bookSourceUrl（id）精确匹配，未命中再按名称兜底（兼容改名的书源，
        // 例如「大灰狼融合VIP5.0」这类名称带版本号、URL 却对不上的情况）。
        val byId = HashMap<String, SourceSummary>()
        val byName = HashMap<String, SourceSummary>()
        database.listSources(null).forEach { existing ->
            existing.id.trim().lowercase().takeIf { it.isNotEmpty() }?.let { byId.putIfAbsent(it, existing) }
            existing.name.trim().lowercase().takeIf { it.isNotEmpty() }?.let { byName.putIfAbsent(it, existing) }
        }

        val items = rawList.mapIndexed { index, raw ->
            val parsed = runCatching { SourceCodec.parse(raw, keepGroup = false) }
            parsed.fold(
                onSuccess = { prepareItem(it, index, byId, byName) },
                onFailure = { error ->
                    PreparedItem(
                        index = index,
                        name = displayName(raw, index),
                        url = "",
                        status = STATUS_INVALID,
                        reason = error.message ?: "书源格式无效",
                    )
                },
            )
        }

        val token = newToken()
        store(token, CachedPreview(target, items, System.currentTimeMillis()))
        log("source network preview: url=$target, total=${items.size}, new=${items.count { it.status == STATUS_NEW }}, update=${items.count { it.status == STATUS_UPDATE }}, invalid=${items.count { it.status == STATUS_INVALID }}")

        NetworkImportPreviewResponse(
            token = token,
            url = target,
            total = items.size,
            newCount = items.count { it.status == STATUS_NEW },
            updateCount = items.count { it.status == STATUS_UPDATE },
            invalidCount = items.count { it.status == STATUS_INVALID },
            sources = items.map {
                NetworkImportPreviewItem(
                    index = it.index,
                    name = it.name,
                    url = it.url,
                    status = it.status,
                    reason = it.reason,
                )
            },
        )
    }

    /**
     * 判定单条书源是「新增」还是「更新」。
     *
     * **名称兜底命中时必须把 `bookSourceUrl` 改写为现有源的 id**：`importSources` 是按 id upsert 的，
     * 若沿用新 URL 落库，会凭新 id 插出一条新书源 —— 表现为「提示更新、实际新增了一条重复源」。
     */
    private fun prepareItem(
        parsed: ParsedSource,
        index: Int,
        byId: Map<String, SourceSummary>,
        byName: Map<String, SourceSummary>,
    ): PreparedItem {
        val matched = byId[parsed.id.trim().lowercase()] ?: byName[parsed.name.trim().lowercase()]
        if (matched == null) {
            return PreparedItem(
                index = index,
                name = parsed.name,
                url = parsed.id,
                status = STATUS_NEW,
                targetId = parsed.id,
                payload = parsed.json,
            )
        }
        val payload = if (matched.id == parsed.id) parsed.json else rewriteSourceId(parsed.json, matched.id)
        return PreparedItem(
            index = index,
            name = parsed.name,
            url = matched.id,
            status = STATUS_UPDATE,
            targetId = matched.id,
            payload = payload,
        )
    }

    /** 把归一化后的书源 JSON 的 `bookSourceUrl` 改写为目标 id（名称兜底命中时使用）。 */
    private fun rewriteSourceId(payload: String, targetId: String): String {
        val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return payload
        val mutable = obj.toMutableMap()
        mutable["bookSourceUrl"] = JsonPrimitive(targetId)
        return Json.encodeToString(JsonElement.serializer(), JsonObject(mutable))
    }

    /** `invalid` 条目仍需展示一个可读名字：尽力从原始 JSON 里取书名，取不到就用序号。 */
    private fun displayName(raw: String, index: Int): String {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val name = obj?.let { element ->
            listOf("bookSourceName", "sourceName", "name")
                .firstNotNullOfOrNull { key -> (element[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
        }
        return name ?: "第 ${index + 1} 项"
    }

    // ------------------------------------------------------------------ 落库

    suspend fun commit(token: String, selected: List<Int>, group: String?): ImportResponse = withContext(Dispatchers.IO) {
        purgeExpired()
        val cached = tickets[token] ?: throw NetworkImportException("import_ticket_expired", "预览已过期，请重新拉取")
        val picked = selected.toSet()
        val chosen = cached.items.filter { it.status != STATUS_INVALID && it.index in picked }
        if (chosen.isEmpty()) throw NetworkImportException("import_selection_empty", "没有选中任何可导入的书源")
        val payloads = chosen.mapNotNull { it.payload }
        // 与「导入 JSON 文件」同语义：不带来源文件里的分组，新源落「未分组」，已有分组不被改动。
        val response = database.importSources(payloads, applyGroups = false)
        // 用户显式选了目标分组才套用；没选就保持原样。
        val targetGroup = group?.trim()?.takeIf { it.isNotBlank() }
        if (targetGroup != null) {
            database.batchUpdateSources(chosen.mapNotNull { it.targetId }, "set_group", targetGroup)
        }
        tickets.remove(token)
        log("source network import: url=${cached.url}, selected=${chosen.size}, imported=${response.imported}, updated=${response.updated}, group=${targetGroup ?: "(不改动)"}")
        response
    }

    // ------------------------------------------------------------------ 票据

    private fun store(token: String, preview: CachedPreview) {
        purgeExpired()
        // 上限外的条目按「最旧优先」淘汰：防止有人反复调 preview 把内存撑爆。
        while (tickets.size >= MAX_TICKETS) {
            val oldest = tickets.entries.minByOrNull { it.value.createdAt } ?: break
            tickets.remove(oldest.key)
        }
        tickets[token] = preview
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { now - it.value.createdAt > TICKET_TTL_MS }
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 仅供测试：当前票据数量。 */
    internal fun ticketCount(): Int = tickets.size

    // ------------------------------------------------------------------ 拉取

    private fun download(url: String): String {
        fetcher?.let { return it(url) }
        var uri = parseTarget(url)
        repeat(MAX_REDIRECTS + 1) {
            val response = try {
                client.send(
                    HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "LegadoServer/0.1")
                        .header("Accept", "application/json,text/plain,*/*")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            } catch (error: Exception) {
                throw NetworkImportException("import_upstream_failed", "无法访问该地址：${error.message ?: error.javaClass.simpleName}")
            }
            response.body().use { body ->
                if (response.statusCode() in 300..399) {
                    val location = response.headers().firstValue("location").orElse(null)
                        ?: throw NetworkImportException("import_upstream_failed", "上游重定向缺少地址")
                    uri = parseTarget(uri.resolve(location).toString())
                    return@repeat
                }
                if (response.statusCode() !in 200..299) {
                    // 该 error code 刻意**不**放进四语字典：HTTP 状态码是用户可据以行动的信息
                    // （404 = 链接失效），与既有 `subscription_update_failed` 的处理方式一致。
                    throw NetworkImportException("import_upstream_failed", "上游返回 HTTP ${response.statusCode()}")
                }
                return body.readBounded(MAX_BYTES).toString(Charsets.UTF_8)
            }
        }
        throw NetworkImportException("import_upstream_failed", "重定向次数超过限制")
    }

    /** 解析并校验目标地址：非 HTTP(S) 直接拒；内网/环回/链路本地地址由 [NetworkSecurity] 拦下（防 SSRF）。 */
    private fun parseTarget(raw: String): URI {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw NetworkImportException("import_url_scheme", "地址格式无效")
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw NetworkImportException("import_url_scheme", "仅支持 HTTP(S) 地址")
        }
        try {
            NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "书源")
        } catch (error: IllegalArgumentException) {
            throw NetworkImportException("import_target_blocked", error.message ?: "该地址不被允许访问")
        }
        return uri
    }

    /** 有上限地读满整个响应体：超过立即中止，避免异常上游把服务端内存吃光。 */
    internal fun InputStream.readBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) {
                throw NetworkImportException("import_payload_too_large", "响应体超过 ${limit / 1024 / 1024} MiB 上限")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        const val STATUS_NEW = "new"
        const val STATUS_UPDATE = "update"
        const val STATUS_INVALID = "invalid"

        /** 单次拉取的响应体上限：与 [WebViewProxy] 的代理口径同量级（8 MiB）。 */
        internal const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        /** 同时最多缓存 8 份预览（正常用户不会同时开 8 个导入弹窗）。 */
        private const val MAX_TICKETS = 8
        private const val TICKET_TTL_MS = 10 * 60 * 1000L
    }
}

/** 带稳定错误码的网络导入异常；由路由层翻译成 `respondApiError`。 */
class NetworkImportException(val code: String, override val message: String) : IllegalArgumentException(message)
