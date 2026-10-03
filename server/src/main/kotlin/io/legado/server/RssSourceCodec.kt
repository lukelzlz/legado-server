package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 订阅源 JSON ↔ [RssSource] 的解析（**宽容**解析）。
 *
 * 为什么需要它、而不是直接用 kotlinx 的 `decodeFromString<RssSource>`：
 * 真实 `rssSources.json` 是 Legado 手机端用 Gson 产出的，与本服务的 DTO 有几处**形态差异**，
 * 直接反序列化会整条失败或静默丢字段：
 *
 * | 形态 | 实例 | 直接反序列化的后果 |
 * | :--- | :--- | :--- |
 * | `redirectPolicy` 是字符串 | `"ASK_CROSS_ORIGIN"` | 若按 Int 声明 ⇒ 整条解析异常 |
 * | 布尔可能是字符串 | `"true"` | 严格解析抛错 |
 * | 缺省的字段根本不出现 | 7/8 条没有 `ruleArticles` | 严格解析要求非空则失败 |
 *
 * 因此这里逐字段宽容取值（与 [BackupImporter.parseRssSources] 同一套判据），
 * 保证「导入备份」与「前端粘贴 JSON」两条入口**判定口径完全一致**。
 */
object RssSourceCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** [RssSource] → JSON 对象文本（[decode] 的逆向，供内部把模型回喂给求值器）。 */
    fun encode(source: RssSource): String = json.encodeToString(source)

    /** 解析单个订阅源对象；不是对象或缺少 `sourceUrl` 时抛 [RuleExecutionException]。 */
    fun decode(sourceJson: String): RssSource {
        val raw = sourceJson.trim().removePrefix("\uFEFF")
        if (raw.isEmpty()) throw RuleExecutionException("订阅源内容为空")
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw RuleExecutionException("订阅源必须是 JSON 对象")
        val sourceUrl = obj.text("sourceUrl")?.takeIf { it.isNotBlank() }
            ?: throw RuleExecutionException("订阅源缺少 sourceUrl")
        return fromObject(obj, sourceUrl)
    }

    /**
     * 把 JSON 对象转成 [RssSource]（供 [BackupImporter] 与 [decode] 共用判据）。
     *
     * `sourceName` 缺省时回退 `sourceUrl`：真实数据里它不是必填，
     * 但服务端处处要展示名，空名会让列表出现「空白行」。
     */
    fun fromObject(obj: JsonObject, sourceUrl: String): RssSource = RssSource(
        sourceUrl = sourceUrl,
        sourceName = obj.text("sourceName")?.takeIf { it.isNotBlank() } ?: sourceUrl,
        sourceGroup = obj.text("sourceGroup")?.takeIf { it.isNotBlank() },
        sourceIcon = obj.text("sourceIcon")?.takeIf { it.isNotBlank() },
        sourceComment = obj.text("sourceComment")?.takeIf { it.isNotBlank() },
        enabled = obj.flag("enabled") ?: true,
        customOrder = obj.int("customOrder") ?: 0,
        type = obj.int("type") ?: 0,
        articleStyle = obj.int("articleStyle") ?: 0,
        lastUpdateTime = obj.number("lastUpdateTime") ?: 0L,
        singleUrl = obj.flag("singleUrl") ?: false,
        cacheFirst = obj.flag("cacheFirst") ?: false,
        preload = obj.flag("preload") ?: false,
        enableJs = obj.flag("enableJs") ?: false,
        showWebLog = obj.flag("showWebLog") ?: false,
        enabledCookieJar = obj.flag("enabledCookieJar") ?: false,
        loadWithBaseUrl = obj.flag("loadWithBaseUrl") ?: false,
        header = obj.text("header")?.takeIf { it.isNotBlank() },
        sortUrl = obj.text("sortUrl")?.takeIf { it.isNotBlank() },
        ruleArticles = obj.text("ruleArticles")?.takeIf { it.isNotBlank() },
        ruleLink = obj.text("ruleLink")?.takeIf { it.isNotBlank() },
        ruleTitle = obj.text("ruleTitle")?.takeIf { it.isNotBlank() },
        ruleImage = obj.text("ruleImage")?.takeIf { it.isNotBlank() },
        rulePubDate = obj.text("rulePubDate")?.takeIf { it.isNotBlank() },
        loginUrl = obj.text("loginUrl")?.takeIf { it.isNotBlank() },
        loginUi = obj.text("loginUi")?.takeIf { it.isNotBlank() },
        injectJs = obj.text("injectJs")?.takeIf { it.isNotBlank() },
        shouldOverrideUrlLoading = obj.text("shouldOverrideUrlLoading")?.takeIf { it.isNotBlank() },
        jsLib = obj.text("jsLib")?.takeIf { it.isNotBlank() },
        contentBlacklist = obj.text("contentBlacklist")?.takeIf { it.isNotBlank() },
        // 字符串枚举；缺省与手机版 Room 默认值一致
        redirectPolicy = obj.text("redirectPolicy")?.takeIf { it.isNotBlank() } ?: "ASK_CROSS_ORIGIN",
    )

    // ---- 宽容取值：数字/布尔都按「字符串形态」兜一层（Gson 输出与本服务 DTO 可能不同）----

    private fun JsonPrimitive.text(): String? = contentOrNull

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.text()

    private fun JsonObject.number(key: String): Long? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        return primitive.longOrNull
            ?: primitive.booleanOrNull?.let { if (it) 1L else 0L }
            ?: primitive.contentOrNull?.trim()?.toLongOrNull()
    }

    private fun JsonObject.int(key: String): Int? = number(key)?.toInt()

    private fun JsonObject.flag(key: String): Boolean? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
}

/**
 * 列表地址：优先用 `sortUrl` 的第一条分类（手机版就是按分类抓的），否则退回 `sourceUrl`。
 *
 * `sortUrl` 是 `名称::地址` 多行文本（真实数据实测），因此取第一行 `::` 之后的部分；
 * 没有 `::` 时按「名称与地址同名」处理（真实数据里就有 `番茄::番茄` 这种相对值）。
 */
internal fun RssSource.defaultListUrl(): String {
    val firstSort = sortUrl?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        ?.let { line -> line.substringAfter("::", line).trim() }
        ?.takeIf { it.isNotEmpty() }
    return firstSort ?: sourceUrl
}
