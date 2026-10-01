package io.legado.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class ParsedSource(
    val id: String,
    val name: String,
    val url: String,
    val group: String?,
    val enabled: Boolean,
    val isJs: Boolean,
    val hasLogin: Boolean = false,
    val json: String,
)

object SourceCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun validate(text: String): ValidateResponse = try {
        parse(text)
        ValidateResponse(true, emptyList(), emptyList())
    } catch (error: IllegalArgumentException) {
        ValidateResponse(false, listOf(error.message ?: "JSON 无效"), emptyList())
    }

    /**
     * 解析书源 JSON。
     *
     * @param keepGroup 是否保留书源自带的 `bookSourceGroup`（书源分组）：
     *   - `true`（默认，编辑器保存 / 备份导入）：按原样保留；
     *   - `false`（**导入书源 JSON 文件 / 订阅更新**）：丢弃分组，让书源落在「未分组」。
     *     注意**同时从归一化后的 payload 里删掉该字段** —— 只清列不清 payload 会留下隐患：
     *     编辑弹窗读的是 payload，会显示一个库里并不存在的分组，用户一保存又把它写回列里。
     */
    fun parse(text: String, keepGroup: Boolean = true): ParsedSource {
        val cleanText = text.trim().removePrefix("\uFEFF")
        require(cleanText.toByteArray().size <= MAX_SOURCE_BYTES) { "书源不能超过 1 MiB" }
        val objectValue = try { json.parseToJsonElement(cleanText) as? JsonObject } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("书源必须是 JSON 对象")
        val rawUrl = (objectValue.string("bookSourceUrl")
            ?: objectValue.string("sourceUrl")
            ?: objectValue.string("url"))?.trim()
            ?: throw IllegalArgumentException("缺少 bookSourceUrl")
        require(rawUrl.isNotBlank()) { "bookSourceUrl 不能为空" }
        val fixedUrl = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> rawUrl
        }
        // Legado source URLs may carry annotations such as `https://host/##@group` or `https://host/#module`.
        // The server only needs the reachable origin; annotations are metadata used by the Android client.
        val url = normalizeSourceUrl(fixedUrl)
        val name = (objectValue.string("bookSourceName")
            ?: objectValue.string("sourceName")
            ?: objectValue.string("name"))
            ?.takeIf { it.isNotBlank() } ?: url
        val group = objectValue.string("bookSourceGroup")
            ?: objectValue.string("sourceGroup")
            ?: objectValue.string("group")
        val enabled = objectValue.boolean("enabled")
            ?: objectValue.boolean("enable")
            ?: true
        val normalizedMap = objectValue.toMutableMap()
        normalizedMap["bookSourceUrl"] = JsonPrimitive(url)
        normalizedMap["bookSourceName"] = JsonPrimitive(name)
        if (!keepGroup) GROUP_KEYS.forEach { normalizedMap.remove(it) }
        val normalizedJson = json.encodeToString(
            JsonElement.serializer(),
            JsonObject(normalizedMap),
        )
        val hasLogin = !objectValue.string("loginUi").isNullOrBlank() ||
            !objectValue.string("loginUrl").isNullOrBlank() ||
            !objectValue.string("loginCheckJs").isNullOrBlank()
        return ParsedSource(
            id = url,
            name = name,
            url = url,
            group = group?.takeIf { keepGroup },
            enabled = enabled,
            isJs = !objectValue.string("mainJs").isNullOrBlank(),
            hasLogin = hasLogin,
            json = normalizedJson,
        )
    }

    /**
     * 把一份「书源集合」响应体拆成**逐条书源 JSON 字符串**。
     *
     * Legado 生态里同一个语义有五六种外层包装，必须全部兼容：
     * 顶层数组、`{ data: [...] }`、`{ sources: [...] }`、`{ bookSources: [...] }`、
     * `{ list: [...] }`，以及「单个书源对象」（某站直接返回一条源时）。
     *
     * **订阅更新与网络导入共用本函数**：两处各写一份解析器迟早会漂移
     * （仓库既有教训：「同一语义要多路径生效 / 两处口径必须同源」）。
     */
    fun parseSourceList(body: String): List<String> {
        val cleanBody = body.trim().removePrefix("\uFEFF")
        val element = try { json.parseToJsonElement(cleanBody) } catch (_: Exception) { null }
            ?: return parseLineDelimited(cleanBody)
        val values = when (element) {
            is JsonArray -> element
            is JsonObject -> when {
                element["data"] is JsonArray -> element["data"] as JsonArray
                element["sources"] is JsonArray -> element["sources"] as JsonArray
                element["bookSources"] is JsonArray -> element["bookSources"] as JsonArray
                element["list"] is JsonArray -> element["list"] as JsonArray
                else -> listOf(element)
            }
            // 顶层既不是数组也不是对象 ⇒ 一定不是书源集合，必须显式拒绝。
            // ⚠️ 本对象的 `json` 是 `isLenient = true`（为兼容 Legado 的伪 JSON），
            // 它会把 `<html>…</html>` 这类文本当成「未加引号的字符串」**解析成功**并返回字面量，
            // 若在这里放行，HTML 错误页会被误判成「1 条不合格书源」而不是「该地址不是书源 JSON」。
            else -> throw IllegalArgumentException("内容不是有效的书源集合")
        }
        return values.map { json.encodeToString(JsonElement.serializer(), it) }
    }

    /**
     * 兜底：**JSONL / NDJSON**（一行一条 JSON）。
     *
     * 书源集合在网上也常以「每行一条」的形式分发，而严格 JSON 解析必然失败。
     * ⚠️ 这个容忍度原先**只存在于前端**（本地导入用的 `parseSourceJsonText`），
     * 服务端没有 ⇒「本地导入能用的文件，走网络导入或走服务端预览就报无效」。
     * 按仓库「同一口径必须同源」的约定，把它上移到本函数，两侧共用一份解析器。
     *
     * 只有**至少解析出一行书源对象**才认；否则仍按「不是有效 JSON」拒绝，避免把任意文本当成书源。
     *
     * ⚠️ 必须 `as? JsonObject` 过滤：本对象的 `json` 是 `isLenient = true`，它会把 `这不是 JSON`
     * 这类**未加引号的文本当成字符串字面量解析成功**。若只做 `runCatching`，任意多行文本都会
     * 变成「N 条书源」，垃圾文件也能出预览（实测踩到）。一条书源必然是一个 JSON 对象。
     */
    private fun parseLineDelimited(cleanBody: String): List<String> {
        val items = cleanBody.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject }
            .toList()
        require(items.isNotEmpty()) { "内容不是有效 JSON" }
        return items.map { json.encodeToString(JsonElement.serializer(), it) }
    }

    /** 对外复用：把书源标识（可带 `#` / `##` 注解）归一化为服务端使用的 sourceId（备份导入时对齐书架 origin）。 */
    fun normalizeSourceId(raw: String): String = normalizeSourceUrl(raw)

    private fun normalizeSourceUrl(rawUrl: String): String = rawUrl
        .substringBefore("##")
        .substringBefore("#")
        .trim()
        .ifBlank { rawUrl }

    private fun JsonObject.string(key: String): String? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        val content = primitive.contentOrNull?.trim()?.lowercase() ?: return null
        return when (content) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private const val MAX_SOURCE_BYTES = 1024 * 1024

    /**
     * 将给定的书源 JSON 中的分组字段同步为指定的分组名。
     *
     * - 当 [group] 为有效非空字符串时：设置 `bookSourceGroup` 为该值，并清理历史别名字段；
     * - 当 [group] 为 null 或全空白时：清理所有分组字段（`bookSourceGroup`, `sourceGroup`, `group`）。
     *
     * 用于 `Database.getSource` 和 `Database.exportSources`，确保返回给前端编辑器和导出的书源 JSON
     * 永远与数据库 `source_group` 真实列保持一致，防止用户在编辑器中保存时因 payload 缺失字段而将分组冲掉。
     */
    fun withGroup(jsonText: String, group: String?): String {
        val cleanText = jsonText.trim().removePrefix("\uFEFF")
        val objectValue = try { json.parseToJsonElement(cleanText) as? JsonObject } catch (_: Exception) { null } ?: return jsonText
        val target = group?.trim()?.takeIf { it.isNotBlank() }
        val currentGroup = objectValue.string("bookSourceGroup")
        if (target == null && GROUP_KEYS.none { objectValue.containsKey(it) }) return jsonText
        if (target != null && currentGroup == target && GROUP_KEYS.drop(1).none { objectValue.containsKey(it) }) return jsonText

        val mutable = objectValue.toMutableMap()
        GROUP_KEYS.forEach { mutable.remove(it) }
        if (target != null) {
            mutable["bookSourceGroup"] = JsonPrimitive(target)
        }
        return json.encodeToString(JsonElement.serializer(), JsonObject(mutable))
    }

    /** `bookSourceGroup` 的各种历史写法；`keepGroup = false` 时全部删净。 */
    private val GROUP_KEYS = listOf("bookSourceGroup", "sourceGroup", "group")
}
