package io.legado.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
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
