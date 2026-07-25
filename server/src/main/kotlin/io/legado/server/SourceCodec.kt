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
    val json: String,
)

object SourceCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(text: String): ValidateResponse = try {
        parse(text)
        ValidateResponse(true, emptyList(), listOf("首版仅执行声明式规则及受限 JS API；Android WebView 登录不执行"))
    } catch (error: IllegalArgumentException) {
        ValidateResponse(false, listOf(error.message ?: "JSON 无效"), emptyList())
    }

    fun parse(text: String): ParsedSource {
        require(text.toByteArray().size <= MAX_SOURCE_BYTES) { "书源不能超过 1 MiB" }
        val objectValue = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("书源必须是 JSON 对象")
        val url = objectValue.string("bookSourceUrl") ?: throw IllegalArgumentException("缺少 bookSourceUrl")
        require(url.startsWith("http://") || url.startsWith("https://")) { "bookSourceUrl 必须是 HTTP(S) 地址" }
        val name = objectValue.string("bookSourceName")?.takeIf { it.isNotBlank() } ?: url
        return ParsedSource(
            id = url,
            name = name,
            url = url,
            group = objectValue.string("bookSourceGroup"),
            enabled = objectValue.boolean("enabled") ?: true,
            isJs = !objectValue.string("mainJs").isNullOrBlank(),
            json = json.encodeToString(JsonElement.serializer(), objectValue),
        )
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
    private const val MAX_SOURCE_BYTES = 1024 * 1024
}
