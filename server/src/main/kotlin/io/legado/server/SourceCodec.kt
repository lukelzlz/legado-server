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

    fun parse(text: String): ParsedSource {
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
            group = group,
            enabled = enabled,
            isJs = !objectValue.string("mainJs").isNullOrBlank(),
            hasLogin = hasLogin,
            json = normalizedJson,
        )
    }

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
}
