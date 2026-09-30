package io.legado.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

object ServerMessages {
    const val DEFAULT_LOCALE = "zh-CN"

    val SUPPORTED_LOCALES = setOf("zh-CN", "zh-TW", "en-US", "ja-JP")

    private val bundles: Map<String, Map<String, String>> = mapOf(
        "zh-CN" to mapOf(
            "unauthenticated" to "请先登录",
            "csrf_invalid" to "请求验证失败",
            "invalid_credentials" to "密码错误",
            "rate_limited" to "尝试过于频繁，请稍后再试",
            "invalid_text" to "朗读文本不能为空",
            "tts_session_not_found" to "朗读会话不存在",
            "tts_failed" to "语音合成失败",
            "invalid_tts_action" to "不支持的朗读操作",
            "source_execution_failed" to "书源执行失败",
            "invalid_backup" to "备份文件无效",
            "invalid_path" to "路径无效",
            "not_found" to "未找到请求的资源",
            "invalid_group" to "分组名称不能为空",
            "group_not_found" to "分组不存在",
            "invalid_locale" to "不支持的语言代码",
            "unsupported_format" to "不支持的文件格式",
            "file_too_large" to "文件超出大小限制",
            "empty_selection" to "请先选择需要操作的书源",
            "invalid_action" to "无效的批量操作",
            "batch_failed" to "批量操作失败",
            "invalid_keyword" to "请输入搜索关键词",
            "invalid_subscription" to "订阅地址或标识无效",
            "invalid_id" to "缺少有效的 ID 标识",
            "invalid_rule" to "规则匹配模式不能为空",
            "invalid_content" to "缺少书源或章节地址",
            "invalid_bookshelf" to "书架数据或书籍标识无效",
            "invalid_book" to "缺少文件路径",
            "invalid_progress" to "阅读进度数据无效",
            "invalid_request" to "无效的请求参数",
            "missing_parameter" to "缺少必要参数",
            "tts_session_busy" to "朗读队列已满",
            "group_exists" to "分组已存在或创建失败",
            "group_error" to "重命名分组失败",
            "read_failed" to "读取文件失败",
            "too_large" to "文件超过大小限制",
        ),
        "zh-TW" to mapOf(
            "unauthenticated" to "請先登入",
            "csrf_invalid" to "請求驗證失敗",
            "invalid_credentials" to "密碼錯誤",
            "rate_limited" to "嘗試過於頻繁，請稍後再試",
            "invalid_text" to "朗讀文字不能為空",
            "tts_session_not_found" to "朗讀會話不存在",
            "tts_failed" to "語音合成失敗",
            "invalid_tts_action" to "不支援的朗讀操作",
            "source_execution_failed" to "書源執行失敗",
            "invalid_backup" to "備份檔案無效",
            "invalid_path" to "路徑無效",
            "not_found" to "找不到請求的資源",
            "invalid_group" to "分組名稱不能為空",
            "group_not_found" to "分組不存在",
            "invalid_locale" to "不支援的語言代碼",
            "unsupported_format" to "不支援的檔案格式",
            "file_too_large" to "檔案超出大小限制",
            "empty_selection" to "請先選擇需要操作的書源",
            "invalid_action" to "無效的批量操作",
            "batch_failed" to "批量操作失敗",
            "invalid_keyword" to "請輸入搜尋關鍵字",
            "invalid_subscription" to "訂閱網址或識別碼無效",
            "invalid_id" to "缺少有效的 ID 識別碼",
            "invalid_rule" to "規則匹配模式不能為空",
            "invalid_content" to "缺少書源或章節網址",
            "invalid_bookshelf" to "書架資料或書籍識別碼無效",
            "invalid_book" to "缺少檔案路徑",
            "invalid_progress" to "閱讀進度資料無效",
            "invalid_request" to "無效的請求參數",
            "missing_parameter" to "缺少必要參數",
            "tts_session_busy" to "朗讀隊列已滿",
            "group_exists" to "分組已存在或建立失敗",
            "group_error" to "重新命名分組失敗",
            "read_failed" to "讀取檔案失敗",
            "too_large" to "檔案超過大小限制",
        ),
        "en-US" to mapOf(
            "unauthenticated" to "Authentication required",
            "csrf_invalid" to "CSRF token validation failed",
            "invalid_credentials" to "Invalid password",
            "rate_limited" to "Too many attempts, please try again later",
            "invalid_text" to "Speech text cannot be empty",
            "tts_session_not_found" to "TTS session not found",
            "tts_failed" to "Speech synthesis failed",
            "invalid_tts_action" to "Unsupported TTS action",
            "source_execution_failed" to "Book source execution failed",
            "invalid_backup" to "Invalid backup package",
            "invalid_path" to "Invalid path",
            "not_found" to "Requested resource not found",
            "invalid_group" to "Group name cannot be empty",
            "group_not_found" to "Group not found",
            "invalid_locale" to "Unsupported language code",
            "unsupported_format" to "Unsupported file format",
            "file_too_large" to "File exceeds size limit",
            "empty_selection" to "Please select sources to operate on",
            "invalid_action" to "Invalid batch operation",
            "batch_failed" to "Batch operation failed",
            "invalid_keyword" to "Please enter a search keyword",
            "invalid_subscription" to "Invalid subscription URL or ID",
            "invalid_id" to "Missing valid ID",
            "invalid_rule" to "Rule pattern cannot be empty",
            "invalid_content" to "Missing book source or chapter URL",
            "invalid_bookshelf" to "Invalid bookshelf data or book identifier",
            "invalid_book" to "Missing file path",
            "invalid_progress" to "Invalid reading progress data",
            "invalid_request" to "Invalid request parameter",
            "missing_parameter" to "Missing required parameter",
            "tts_session_busy" to "TTS playback queue is full",
            "group_exists" to "Group already exists or creation failed",
            "group_error" to "Failed to rename group",
            "read_failed" to "Failed to read file",
            "too_large" to "File exceeds size limit",
        ),
        "ja-JP" to mapOf(
            "unauthenticated" to "ログインが必要です",
            "csrf_invalid" to "リクエストの検証に失敗しました",
            "invalid_credentials" to "パスワードが正しくありません",
            "rate_limited" to "試行回数が多すぎます。しばらくしてから再試行してください",
            "invalid_text" to "読み上げテキストを入力してください",
            "tts_session_not_found" to "読み上げセッションが見つかりません",
            "tts_failed" to "音声合成に失敗しました",
            "invalid_tts_action" to "サポートされていない音声操作です",
            "source_execution_failed" to "ブックソースの実行に失敗しました",
            "invalid_backup" to "バックアップファイルが無効です",
            "invalid_path" to "パスが無効です",
            "not_found" to "リソースが見つかりません",
            "invalid_group" to "グループ名を入力してください",
            "group_not_found" to "グループが見つかりません",
            "invalid_locale" to "サポートされていない言語コードです",
            "unsupported_format" to "サポートされていないファイル形式です",
            "file_too_large" to "ファイルサイズが上限を超えています",
            "empty_selection" to "操作するブックソースを選択してください",
            "invalid_action" to "無効な一括操作です",
            "batch_failed" to "一括操作に失敗しました",
            "invalid_keyword" to "検索キーワードを入力してください",
            "invalid_subscription" to "購読URLまたはIDが無効です",
            "invalid_id" to "有効なIDが指定されていません",
            "invalid_rule" to "置換ルールパターンを入力してください",
            "invalid_content" to "ブックソースまたは章URLが不足しています",
            "invalid_bookshelf" to "本棚データまたは書籍識別子が無効です",
            "invalid_book" to "ファイルパスが指定されていません",
            "invalid_progress" to "読書進捗データが無効です",
            "invalid_request" to "無効なリクエストパラメータです",
            "missing_parameter" to "必要なパラメータが不足しています",
            "tts_session_busy" to "読み上げキューが満杯です",
            "group_exists" to "グループが既に存在するか作成に失敗しました",
            "group_error" to "グループ名の変更に失敗しました",
            "read_failed" to "ファイルの読み込みに失敗しました",
            "too_large" to "ファイルサイズが制限を超えています",
        ),
    )

    fun matchLocale(header: String?): String {
        if (header.isNullOrBlank()) return DEFAULT_LOCALE
        val tokens = header.split(',').map { it.substringBefore(';').trim().lowercase() }
        for (tag in tokens) {
            when {
                tag.startsWith("zh-tw") || tag.startsWith("zh-hk") || tag.startsWith("zh-hant") || tag.startsWith("zh-mo") -> return "zh-TW"
                tag.startsWith("zh") -> return "zh-CN"
                tag.startsWith("ja") -> return "ja-JP"
                tag.startsWith("en") -> return "en-US"
            }
        }
        return DEFAULT_LOCALE
    }

    fun normalizeLocale(locale: String?): String? {
        if (locale.isNullOrBlank()) return null
        val lower = locale.trim().lowercase()
        return when {
            lower == "zh-cn" || lower == "zh" || lower == "zh-hans" -> "zh-CN"
            lower == "zh-tw" || lower == "zh-hk" || lower == "zh-hant" || lower == "zh-mo" -> "zh-TW"
            lower == "en-us" || lower == "en" || lower == "en-gb" -> "en-US"
            lower == "ja-jp" || lower == "ja" -> "ja-JP"
            else -> null
        }
    }

    fun resolve(code: String, rawMessage: String, acceptLanguage: String?): String {
        val locale = matchLocale(acceptLanguage)
        val bundle = bundles[locale] ?: bundles[DEFAULT_LOCALE] ?: return rawMessage
        return bundle[code] ?: rawMessage.ifBlank { code }
    }
}

suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    fallbackMessage: String = "",
) {
    val acceptLang = request.headers[HttpHeaders.AcceptLanguage]
    val message = ServerMessages.resolve(code, fallbackMessage, acceptLang)
    respond(status, ApiError(code = code, message = message))
}
