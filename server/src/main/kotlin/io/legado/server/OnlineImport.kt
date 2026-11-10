package io.legado.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一键导入：对齐开源阅读 `OnLineImportActivity` 的分发 —— 给一个地址和（可选的）类型，
 * 由服务端代抓、按类型落库。类型缺省时按内容判别（[RssSourceCodec.detectKind]）。
 *
 * 书源**不在这里落库**：书源要走「预览勾选 + 票据」（[NetworkSourceImport]），
 * 本类对书源只做 `dryRun` 判别，界面据此转去预览弹窗。
 *
 * @param fetch 拉取地址文本。SSRF 校验、重定向与体积上限由它负责（生产传 [NetworkSourceImport.fetchText]）
 */
class OnlineImport(
    private val database: Database,
    private val fetch: (String) -> String,
    private val parseReplaceRules: suspend (String) -> List<ReplaceRule> = ::parseImportedReplaceRules,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun run(url: String, kind: String?, dryRun: Boolean): OnlineImportResponse {
        val target = url.trim()
        if (target.isEmpty()) throw NetworkImportException("import_url_required", "请填写地址")
        val body = withContext(Dispatchers.IO) { fetch(target) }
        val resolved = kind?.takeIf { it in KINDS }
            ?: RssSourceCodec.detectKind(body)
            ?: throw NetworkImportException("import_kind_unknown", "无法识别该地址的内容类型（支持书源、订阅源、替换规则、朗读引擎）")
        val items = runCatching { SourceCodec.parseSourceList(body) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw NetworkImportException("import_content_invalid", "内容不是有效的 JSON")
        val names = items.take(PREVIEW_NAMES).mapNotNull(::displayName)
        if (dryRun) return OnlineImportResponse(kind = resolved, total = items.size, names = names)
        if (resolved == RssSourceCodec.KIND_BOOK_SOURCE) {
            throw NetworkImportException("import_use_preview", "书源请通过预览勾选后导入")
        }
        val result = importBody(resolved, body)
        return OnlineImportResponse(resolved, items.size, names, result.imported, result.updated, result.skipped, result.errors)
    }

    /** 把一份已拿到的内容按 [kind] 落库（订阅条目的定时更新也走这里，保证两条路同一口径）。 */
    suspend fun importBody(kind: String, body: String): ImportResponse = when (kind) {
        RssSourceCodec.KIND_BOOK_SOURCE -> database.importSources(SourceCodec.parseSourceList(body), applyGroups = false)
        RssSourceCodec.KIND_RSS_SOURCE -> database.importRssSources(SourceCodec.parseSourceList(body))
        RssSourceCodec.KIND_REPLACE_RULE -> {
            val rules = parseReplaceRules(body)
            val result = database.importReplaceRules(rules)
            ImportResponse(result.imported, result.updated, result.skipped, emptyList())
        }
        RssSourceCodec.KIND_HTTP_TTS -> {
            val list = SourceCodec.parseSourceList(body).mapNotNull { raw ->
                runCatching { json.decodeFromString(HttpTts.serializer(), raw) }.getOrNull()
            }
            val result = database.importHttpTts(list)
            ImportResponse(result.imported, 0, result.total - result.imported, emptyList())
        }
        else -> throw NetworkImportException("import_kind_unknown", "不支持的导入类型：$kind")
    }

    private fun displayName(raw: String): String? {
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        return listOf("bookSourceName", "sourceName", "name", "pattern")
            .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
    }

    companion object {
        val KINDS = setOf(
            RssSourceCodec.KIND_BOOK_SOURCE, RssSourceCodec.KIND_RSS_SOURCE,
            RssSourceCodec.KIND_REPLACE_RULE, RssSourceCodec.KIND_HTTP_TTS,
        )
        private const val PREVIEW_NAMES = 8

        /** 订阅条目类型（开源阅读 `RuleSub.type`）→ 导入类型。 */
        fun kindOfSubscriptionType(type: Int): String = when (type) {
            1 -> RssSourceCodec.KIND_RSS_SOURCE
            2 -> RssSourceCodec.KIND_REPLACE_RULE
            else -> RssSourceCodec.KIND_BOOK_SOURCE
        }
    }
}
