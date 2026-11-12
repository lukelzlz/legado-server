package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 一条解析后的订阅源。`id` 即 `sourceUrl`（与开源阅读 `RssSource` 的主键一致）。 */
data class ParsedRssSource(
    val id: String,
    val name: String,
    val icon: String?,
    val group: String?,
    val enabled: Boolean,
    val singleUrl: Boolean,
    val sortUrl: String?,
    val customOrder: Int,
    val json: String,
)

/**
 * 开源阅读「订阅源」（`RssSource`）的解析，以及各类规则 JSON 的**类型判别**。
 *
 * 订阅源与书源是两种东西：订阅源以 `sourceUrl` 为主键、没有 `bookSourceUrl`，
 * 多数（`singleUrl = true`）只是一个**网页入口** —— 在内置浏览器里打开，
 * 用户再点页面上的 `legado://import/…` / `yuedu://…/importonline` 链接完成导入。
 */
object RssSourceCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val KIND_BOOK_SOURCE = "bookSource"
    const val KIND_RSS_SOURCE = "rssSource"
    const val KIND_REPLACE_RULE = "replaceRule"
    const val KIND_HTTP_TTS = "httpTts"

    /**
     * 按首个条目的字段判别类型，判定顺序照抄开源阅读 `jsonImportType`
     * （`ui/association/BaseAssociationViewModel.kt`）中本服务支持的四类。
     */
    fun detectKind(body: String): String? {
        val first = runCatching { SourceCodec.parseSourceList(body) }.getOrNull()?.firstOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(first) }.getOrNull() as? JsonObject ?: return null
        return when {
            "bookSourceUrl" in obj -> KIND_BOOK_SOURCE
            "sourceUrl" in obj -> KIND_RSS_SOURCE
            // 带 style / uuid 的 pattern 是高亮规则，不是替换规则
            "pattern" in obj && ("style" in obj || "uuid" in obj) -> null
            "pattern" in obj -> KIND_REPLACE_RULE
            "name" in obj && "url" in obj -> KIND_HTTP_TTS
            else -> null
        }
    }

    fun parse(text: String): ParsedRssSource {
        val clean = text.trim().removePrefix("﻿")
        require(clean.toByteArray().size <= SourceCodec.MAX_SOURCE_BYTES) { "订阅源不能超过 1 MiB" }
        val obj = runCatching { json.parseToJsonElement(clean) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("订阅源必须是 JSON 对象")
        require("bookSourceUrl" !in obj) { "这是书源而不是订阅源" }
        val url = obj.text("sourceUrl") ?: throw IllegalArgumentException("缺少 sourceUrl")
        val name = obj.text("sourceName") ?: url
        return ParsedRssSource(
            id = url,
            name = name,
            icon = obj.text("sourceIcon"),
            group = obj.text("sourceGroup"),
            enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            singleUrl = (obj["singleUrl"] as? JsonPrimitive)?.booleanOrNull ?: false,
            sortUrl = obj.text("sortUrl"),
            customOrder = (obj["customOrder"] as? JsonPrimitive)?.intOrNull ?: 0,
            json = json.encodeToString(JsonElement.serializer(), obj),
        )
    }

    /**
     * 订阅源在内置浏览器里的入口地址，对齐开源阅读 `RssViewModel.getSingleUrl`：
     * `sortUrl` 非空时取第一项（`名称::地址` 取地址），否则用 `sourceUrl`。
     *
     * 脚本型 `sortUrl`（`@js:` / `<js>`）需要订阅源的 JS 运行环境，本服务未实现 ⇒ 退回 `sourceUrl`。
     * 结果不是 http(s) 地址时返回 null（内置浏览器打不开）。
     */
    fun openUrl(sourceUrl: String, sortUrl: String?): String? {
        val sort = sortUrl?.trim().orEmpty()
        val candidate = when {
            sort.isEmpty() || sort.startsWith("@js:") || sort.startsWith("<js>") -> sourceUrl
            else -> {
                val first = sort.split(SORT_SEPARATOR).firstOrNull { it.isNotBlank() }.orEmpty()
                (if (first.contains("::")) first.substringAfter("::") else first).trim().ifEmpty { sourceUrl }
            }
        }
        return listOf(candidate, sourceUrl).firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private val SORT_SEPARATOR = Regex("""(?:&&|\r?\n)+""")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
