package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 书籍内容类型（决定前端用哪个阅读器）。
 *
 * ## 判定原则（用户 2026-10-15 的明确要求）
 * **以「书自己的说法」为准，源声明只作兜底。**
 * 理由是实测出来的：聚合源（如 `大灰狼融合VIP5.0`）**根本不声明** `bookSourceType`，
 * 但它里面确实有漫画频道（`tab=漫画`）。若只认源声明，这些漫画会被判成小说 ⇒ 进文字阅读器 ⇒
 * 表现为"漫画打不开"，且用户完全看不出是谁改的。
 *
 * 因此 [resolveOrNull] 的优先序是：
 * 1. **调用方显式指定**（用户/前端明确说这是什么）；
 * 2. **书自身载荷里的 `tab`**（`漫画` → COMIC，`听书` → AUDIO）；
 * 3. **书源声明** `bookSourceType`（2 → COMIC，1 → AUDIO）；
 * 4. 都没说 ⇒ `null`（**不写库**，按小说处理；"已经和小说一样就跳过"）。
 */
enum class BookKind {
    NOVEL, COMIC, AUDIO;

    /** 落库用的字符串（`book_shelf.book_kind`）。 */
    val wire: String get() = name.lowercase()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 从书源 payload (source.json) 的 `bookSourceType` 识别。Legado 语义：0=文本 1=音频 2=图像/漫画。 */
        fun fromSourcePayload(payload: String?): BookKind {
            if (payload.isNullOrBlank()) return NOVEL
            return runCatching {
                val obj = json.parseToJsonElement(payload.trim()).jsonObject
                val type = obj["bookSourceType"]?.jsonPrimitive?.content ?: return@runCatching NOVEL
                when (type.trim()) {
                    "2" -> COMIC
                    "1" -> AUDIO
                    else -> NOVEL
                }
            }.getOrDefault(NOVEL)
        }

        /** 从 Legado 备份 `bookshelf.json` 的 base64 载荷内 `tab` 字段识别（实现见 [ShelfKind.decodeTab]）。 */
        fun fromTab(tab: String?): BookKind = when (tab) {
            "漫画" -> COMIC
            "听书" -> AUDIO
            else -> NOVEL
        }

        /** 从落库的字符串还原（`comic` / `audio` / 其它一律 `novel`）。 */
        fun from(value: String?): BookKind = when (value?.trim()?.lowercase()) {
            "comic" -> COMIC
            "audio" -> AUDIO
            else -> NOVEL
        }

        /**
         * 综合判定；**没有任何一方给出明确类型时返回 `null`**（调用方据此决定"不写库"）。
         *
         * @param explicit      调用方显式指定的类型字符串（可空）
         * @param bookUrl       书的 URL —— 内含 base64 载荷时其中的 `tab` 就是"书自己的说法"
         * @param sourcePayload 书源 payload（可空；聚合源常常没有 `bookSourceType`）
         */
        fun resolveOrNull(explicit: String?, bookUrl: String?, sourcePayload: String?): String? {
            val normalized = explicit?.trim()?.lowercase()?.takeIf { it == "comic" || it == "audio" || it == "novel" }
            if (normalized != null) return normalized
            if (bookUrl.isNullOrBlank()) return null
            // 注意顺序：先看书自己的 tab，再看源声明（聚合源不声明，只能靠前者）
            fromTab(ShelfKind.decodeTab(bookUrl)).takeIf { it != NOVEL }?.let { return it.wire }
            fromSourcePayload(sourcePayload).takeIf { it != NOVEL }?.let { return it.wire }
            return null
        }

        /** 同 [resolveOrNull]，但缺省为 `novel`（写库场景用）。 */
        fun resolve(explicit: String?, bookUrl: String?, sourcePayload: String?): String =
            resolveOrNull(explicit, bookUrl, sourcePayload) ?: NOVEL.wire
    }
}
