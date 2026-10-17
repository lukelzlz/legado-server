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
 *
 * ## `LOCAL`（本地导入书）不是"书的说法"，而是**书的身份**
 * 用户要求（issue #66）：本地导入的电子书也要在 `book_shelf.book_kind` 里有自己的类型，
 * 书架角标四类（本地 / 漫画 / 听书 / 小说）互斥。
 * 它由 [isLocalRef]（`sourceId = loc_book` 或 `bookUrl` 以 `local://` 开头）判定，
 * 因此**不会**从 `tab` / `bookSourceType` / 历史 `book_kind` 里推导出来 —— 只有三条写入路径：
 * ① 导入时显式写；② 启动/导入结束的幂等回填；③ 「加入书架」路由对本地书的显式保护。
 */
enum class BookKind {
    NOVEL, COMIC, AUDIO, LOCAL;

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

        /** 从落库的字符串还原（`comic` / `audio` / `local` / 其它一律 `novel`）。 */
        fun from(value: String?): BookKind = when (value?.trim()?.lowercase()) {
            "comic" -> COMIC
            "audio" -> AUDIO
            "local" -> LOCAL
            else -> NOVEL
        }

        /**
         * 这条书架记录是不是**本地导入书**（`book_shelf` 的 `source_id` / `book_url` 身份判据）。
         *
         * 与前端 `isLocalBook` / 服务端 [ShelfKind.isLocalBook] 的 `loc_book` / `local://` 两条判据逐字对齐：
         * 本地书是「本服务自己解析上传文件入库」的书，**没有"重新下载"这一步**，
         * 因此它自成一类（[LOCAL]），且永远不该出现缓存/清缓存动作。
         */
        fun isLocalRef(sourceId: String?, bookUrl: String?): Boolean =
            sourceId == LocalBookParser.LOC_BOOK_SOURCE_ID || bookUrl.orEmpty().startsWith("local://")

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
