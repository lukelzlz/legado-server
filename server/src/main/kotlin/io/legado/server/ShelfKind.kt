package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Legado 备份 `bookshelf.json` 条目的**类别判定**。
 *
 * 规范来源：`te/分类判别方法.md`（由真实 434 条数据归纳，数量已逐项核对）。
 *
 * ## 为什么需要它
 *
 * 服务端只能提供**文本阅读**能力，因此备份里这两类必须过滤掉：
 *
 * | 类别 | 为什么服务端用不了 |
 * | :--- | :--- |
 * | **本地图书** | `bookUrl` 是手机本机的 `content://`（Android SAF）或 `file://` / `webDav::` 路径，服务端读不到 ⇒ 章节 0、正文空 |
 * | **音频（听书）** | 内容源是 TTS 音频流，服务端只做文本渲染 ⇒ 点开也是空 |
 *
 * ## 判定顺序（**不可调换**）
 *
 * 1. **先判本地图书** —— 本地书 `bookUrl` 不是 base64 串，没有 `tab` 字段；
 *    若先判 `tab`，本地书会被挤进兜底分支而误判为在线小说。
 * 2. **再判 `tab`** —— `"听书"` → 音频；`"小说"` → 在线。
 * 3. **兜底** —— `tab` 解析失败一律算在线小说。
 *
 * ## 明确不用的判据（踩过的坑）
 *
 * - **不用文件扩展名判本地书**：`.epub` 只是本批数据的巧合，
 *   `.txt` / `.pdf` / `.mobi` 走的是**完全相同**的 `content://` + `type:264` 机制，用扩展名会全部漏判。
 * - **不用 `type` 单独判定**：`type` 是 Legado 内部枚举，跨版本会变
 *   （实测同一份数据里混有 `8 / 24 / 32 / 264`），只作交叉验证。
 */
enum class ShelfKind {
    /** 在线小说 / 书源 —— 服务端唯一可用的类别。 */
    ONLINE,

    /** 本地图书（手机本机文件）—— 服务端读不到，导入时过滤。 */
    LOCAL,

    /** 音频 / 听书 —— 服务端只做文本，导入时过滤。 */
    AUDIO,
    ;

    companion object {
        /** `bookUrl` 是 `data:;base64,<载荷>,{"type":...}` 形态时的 base64 锚点（`{"` 的 base64）。 */
        private const val BASE64_ANCHOR = "eyJ"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 判定一条书架记录的类别。
         *
         * @param bookUrl 记录的关键字段：在线书源是 base64 载荷，本地书是 `content://` 等 URI
         * @param origin  书源名，或本地文件的真实路径（`webDav::...` / `loc_book`）
         * @param type    Legado 内部类型枚举（仅作交叉验证，**不作为唯一判据**）
         * @param kind    分类/评分/状态拼接串（本地书为空）
         */
        fun of(bookUrl: String?, origin: String?, type: String? = null, kind: String? = null): ShelfKind {
            // 第 1 步：本地图书（必须最先判）
            if (isLocalBook(bookUrl, origin, type)) return LOCAL

            // 第 2 步：按 tab 区分音频与在线小说
            return when (decodeTab(bookUrl)) {
                "听书" -> AUDIO
                // 第 3 步：兜底 —— tab 为 "小说" 或解析失败，都归在线小说
                else -> ONLINE
            }
        }

        /**
         * 是否本地图书。
         *
         * 判据（满足任一即为本地）：
         * - `bookUrl` 以 `content://` 开头（Android SAF 文件选择器）
         * - `bookUrl` 以 `file://` 开头
         * - `origin` 以 `webDav::` 开头（坚果云等 WebDAV 同步的本地文件）
         * - `origin` 以 `loc_book` 开头（**本服务自己**导入本地书时写入的虚拟书源）
         * - `type == 264`
         *
         * 注意最后一条 `type` 是**交叉验证**而非主判据，但保留它可覆盖
         * `origin` 恰好既非 webDav 也非 loc_book 的历史数据。
         */
        fun isLocalBook(bookUrl: String?, origin: String?, type: String? = null): Boolean {
            val url = bookUrl.orEmpty()
            val org = origin.orEmpty()
            return url.startsWith("content://", ignoreCase = true) ||
                url.startsWith("file://", ignoreCase = true) ||
                org.startsWith("webDav::", ignoreCase = true) ||
                // 本服务导入本地书时把 origin 写成 loc_book（见 LocalBookParser 的虚拟书源约定）
                org.startsWith("loc_book", ignoreCase = true) ||
                type?.trim() == "264"
        }

        /**
         * 从 `bookUrl` 的 base64 载荷里取 `tab`。
         *
         * 在线书源的 `bookUrl` 形如：
         * ```
         * data:;base64,eyJ...LCJ0YWIiOiLlkKzkuJoifQ==,{"type":"qingtian"}
         * ```
         * 其中 base64 解码后是 `{"book_id":"…","sources":"番茄","tab":"听书","url":""}`。
         *
         * **任一步失败都返回 null（不抛异常）** —— 本地书走的就是这条路径，
         * 判 null 是正常流程而非错误。
         */
        fun decodeTab(bookUrl: String?): String? {
            val url = bookUrl.orEmpty()
            if (url.isEmpty()) return null
            return runCatching {
                val start = url.indexOf(BASE64_ANCHOR)
                if (start < 0) return null
                // 从锚点起取到 base64 字符集结束（base64 字母表 + '=' 填充）
                val end = url.indexOfFirst(start) { !isBase64Char(it) }
                val payload = url.substring(start, if (end < 0) url.length else end)
                if (payload.isEmpty()) return null
                val decoded = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
                json.parseToJsonElement(decoded).jsonObject["tab"]?.jsonPrimitive?.content
            }.getOrNull()
        }

        private fun isBase64Char(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='

        /** 从 [start] 起找第一个满足条件的下标；找不到返回 -1。 */
        private inline fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
            for (i in start until length) if (predicate(this[i])) return i
            return -1
        }
    }
}
