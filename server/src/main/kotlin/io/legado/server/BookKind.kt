package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class BookKind {
    NOVEL, COMIC;

    companion object {
        /** 从书源 payload (source.json) 的 `bookSourceType` 字段识别。Legado 语义：0=文本 1=音频 2=图像/漫画。 */
        fun fromSourcePayload(payload: String?): BookKind {
            if (payload.isNullOrBlank()) return NOVEL
            return runCatching {
                val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(payload.trim()).jsonObject
                val type = obj["bookSourceType"]?.jsonPrimitive?.content ?: return@runCatching NOVEL
                if (type.trim() == "2") COMIC else NOVEL
            }.getOrDefault(NOVEL)
        }

        /** 从 Legado 备份 `bookshelf.json` 的 base64 载荷内 `tab` 字段识别。已在 [ShelfKind.decodeTab] 中实现。 */
        fun fromTab(tab: String?): BookKind = if (tab == "漫画") COMIC else NOVEL

        fun from(value: String?): BookKind = if (value == "comic") COMIC else NOVEL
    }
}