package io.legado.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class LoginRequest(val password: String)
@Serializable data class LoginResponse(val csrfToken: String)
@Serializable data class SessionResponse(val authenticated: Boolean, val csrfToken: String? = null)
@Serializable data class UserSession(val id: String)

@Serializable data class SourceSummary(
    val id: String,
    val name: String,
    val url: String,
    val group: String? = null,
    val enabled: Boolean,
    val isJsSource: Boolean,
    val hasLogin: Boolean = false,
    val updatedAt: Long,
    val version: Long,
)

@Serializable
data class FlexChildStyle(
    val layout_flexGrow: Float = 0f,
    val layout_flexShrink: Float = 1f,
    val layout_alignSelf: String = "auto",
    val layout_flexBasisPercent: Float = -1f,
    val layout_wrapBefore: Boolean = false,
    val layout_justifySelf: String = "auto",
)

@Serializable
data class SourceLoginUiItem(
    val name: String = "",
    val type: String = "text",
    val action: String? = null,
    val chars: List<String?>? = null,
    val default: String? = null,
    val viewName: String? = null,
    val style: FlexChildStyle? = null,
    val key: String? = null,
    val hint: String? = null,
    val value: String? = null,
    val options: List<String>? = null,
    val countdown: Int? = null,
)

@Serializable
data class SourceLoginUiResponse(
    val sourceId: String,
    val sourceName: String,
    val hasLogin: Boolean,
    val loginUi: List<SourceLoginUiItem>,
    val loginUrl: String? = null,
    val loginInfo: Map<String, String> = emptyMap(),
    val loginHeader: String? = null,
    val sourceVariable: String? = null,
)

@Serializable
data class SourceLoginInfoUpdateRequest(
    val loginInfo: Map<String, String> = emptyMap(),
    val loginHeader: String? = null,
    val sourceVariable: String? = null,
)

@Serializable
data class SourceLoginActionRequest(
    val action: String,
    val loginData: Map<String, String> = emptyMap(),
    val isLongClick: Boolean = false,
)

@Serializable
data class SourceLoginActionResult(
    val success: Boolean,
    val toastMessages: List<String> = emptyList(),
    val openUrl: String? = null,
    val copyText: String? = null,
    val updatedLoginInfo: Map<String, String>? = null,
    val updatedLoginHeader: String? = null,
    val updatedVariable: String? = null,
    val reRenderUi: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SourceLoginCheckResult(
    val loggedIn: Boolean,
    val message: String? = null,
)

@Serializable
data class SourceLoginHeaderUpdateRequest(
    val loginHeader: String?,
)

@Serializable
data class SourceLoginCookieUpdateRequest(
    val cookie: String,
    val url: String? = null,
)

@Serializable
data class SourceLoginCookieResponse(
    val ok: Boolean,
    val message: String? = null,
    val count: Int = 0,
)

@Serializable
data class SourceBrowserSessionResponse(
    val token: String,
    val startUrl: String,
    val expiresInSeconds: Int,
    /** true 表示入口是书源脚本生成的内置页面，由服务端托管，startUrl 为空 */
    val inlineOnly: Boolean = false,
)

@Serializable
data class SourceBrowserSessionRequest(
    val url: String? = null,
)

@Serializable
data class SourceBrowserInlineRequest(
    val token: String,
    val url: String,
)

@Serializable
data class SourceBrowserInlineResponse(
    val key: String,
)

@Serializable
data class SourceBrowserCookieResponse(
    val count: Int,
    val domains: List<String>,
    val cookies: Map<String, String>,
)

/**
 * 书源自生成页面（设置中心 / 段评气泡）回传的设置结果。
 *
 * 页面里的 `#…-settings-result` 容器由代理层注入的采集脚本读取后 postMessage 给宿主，
 * 宿主再原样送给服务端落库；等价于安卓端 `startBrowserAwait` 的返回体通道。
 */
@Serializable
data class SourceBrowserResultRequest(
    val token: String,
    val settings: JsonObject,
    val resultId: String = "",
)

@Serializable
data class SourceBrowserResultResponse(
    val saved: Boolean,
    val keys: Int = 0,
)

data class SourceLoginStateRecord(
    val sourceId: String,
    val loginInfo: Map<String, String>,
    val loginHeader: String?,
    val sourceVariable: String?,
    val sourceKv: Map<String, String>,
    val cookieJar: Map<String, String>,
    val updatedAt: Long,
)

@Serializable data class SourceRecord(
    val id: String,
    val json: String,
    val version: Long,
    val updatedAt: Long,
)

@Serializable data class SourceWriteRequest(val json: String, val version: Long? = null)
@Serializable data class ImportRequest(val sources: List<String>, val overwrite: Boolean = true)
@Serializable data class ImportResponse(
    val imported: Int,
    val updated: Int = 0,
    val skipped: Int,
    val errors: List<String>,
    /**
     * 本批书源携带的**不同书源分组名**个数（未分组不计）。
     *
     * 分组是书源自带的元数据（Legado 的 `bookSourceGroup`），导入时随书源一起落库；
     * 这个数字用于让「导入手机备份」的提示能如实说出「顺带带进来几个分组」，
     * 否则用户无法判断分组到底有没有跟着进来。
     */
    val sourceGroups: Int = 0,
)
/** 网络书源导入：预览请求。只给地址，拉取、SSRF 校验与解析全部在服务端完成。 */
@Serializable data class NetworkImportPreviewRequest(val url: String)

/**
 * 预览列表里的一条书源。
 *
 * @param status `new`（新增）/ `update`（更新）/ `invalid`（不可导入）
 * @param reason 仅 `invalid` 有值：直接来自 [SourceCodec.parse] 的报错，用于在弹窗里如实说明原因
 */
@Serializable data class NetworkImportPreviewItem(
    val index: Int,
    val name: String,
    val url: String,
    val status: String,
    val reason: String? = null,
)

/**
 * 预览响应：**只回传元数据**，完整书源原文留在服务端内存缓存里由 [token] 引用。
 *
 * 这样 383 条 / 1.63 MB 的集合不会在浏览器与服务端之间来回搬运两次（见 [ADR-023]）。
 */
@Serializable data class NetworkImportPreviewResponse(
    val token: String,
    val url: String,
    val total: Int,
    val newCount: Int,
    val updateCount: Int,
    val invalidCount: Int,
    val sources: List<NetworkImportPreviewItem>,
)

/**
 * 网络书源导入：确认请求。
 *
 * @param selected 预览列表里的**下标**（不是书源 id —— 集合里可能存在重复 id，用下标才无歧义）
 * @param group 目标分组；为空表示**不改动**分组（新源落「未分组」，已有分组保持原样）
 */
@Serializable data class NetworkImportCommitRequest(
    val token: String,
    val selected: List<Int>,
    val group: String? = null,
)

@Serializable data class SubscriptionWriteRequest(val url: String, val enabled: Boolean = true)
@Serializable data class SourceSubscription(
    val id: Long,
    val url: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val lastImported: Int = 0,
    val contentHash: String? = null,
)
@Serializable data class ValidateResponse(val valid: Boolean, val errors: List<String>, val warnings: List<String>)
@Serializable data class DebugRequest(val keyword: String = "测试")
@Serializable data class SearchRequest(
    val keyword: String = "",
    val query: String = "",
    /**
     * 只在这些**书源**里搜索（书源 id，即归一化后的 `bookSourceUrl`）。
     *
     * 与 [group] 互斥使用（同时给出时按 AND 取交集），前端「搜索范围」二选一。
     */
    val sourceIds: List<String>? = null,
    /**
     * 只在指定的**书源分组**里搜索；[SourceGroupFilter.UNGROUPED] 表示「未分组」。
     *
     * 空 / 空白表示不按分组过滤（搜索全部已启用书源）。
     */
    val group: String? = null,
) {
    val effectiveKeyword: String get() = keyword.ifBlank { query }
}
@Serializable data class SearchStreamEvent(
    val type: String,
    val totalSources: Int = 0,
    val completedSources: Int = 0,
    val matchedSources: Int = 0,
    val emptySources: Int = 0,
    val failedSources: Int = 0,
    val resultCount: Int = 0,
    val results: List<SearchResult> = emptyList(),
    val message: String? = null,
)
@Serializable data class SearchResult(
    val sourceId: String,
    val name: String,
    val author: String? = null,
    val bookUrl: String,
    val coverUrl: String? = null,
    val intro: String? = null,
)
@Serializable data class BookRequest(val sourceId: String, val bookUrl: String)
@Serializable data class BookDetails(
    val sourceId: String,
    val name: String,
    val author: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val tocUrl: String,
)
@Serializable data class Chapter(val index: Int, val title: String, val url: String)
@Serializable data class ContentRequest(val sourceId: String, val chapterUrl: String = "", val bookUrl: String? = null)
@Serializable data class ChapterContent(
    val title: String? = null,
    val content: String,
    val rawTitle: String? = null,
    val rawContent: String? = null,
)
@Serializable data class BookRecleanRequest(
    val sourceId: String,
    val bookUrl: String,
)
@Serializable data class BookRecleanResponse(
    val sourceId: String,
    val bookUrl: String,
    val recleanedChapters: Int,
    val totalChapters: Int,
)

/**
 * 封面补抓请求。留空 [sourceId]/[bookUrl] 表示「整架补抓」。
 *
 * 用于修复历史数据：在封面补抓逻辑上线**之前**导入的书架，
 * `cover_key` 全为空，需要一次性把封面抓成本地副本。
 */
@Serializable data class CoverRefreshRequest(
    val sourceId: String? = null,
    val bookUrl: String? = null,
)

@Serializable data class CoverRefreshResponse(
    val total: Int,
    val refreshed: Int,
    val failed: Int,
    val skipped: Int,
)
@Serializable data class BatchBookRecleanRequest(
    val books: List<BookRecleanRequest>,
)
@Serializable data class BatchBookRecleanResponse(
    val totalRecleaned: Int,
    val results: List<BookRecleanResponse>,
)
@Serializable data class ReadingProgress(
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int,
    val scrollPosition: Double = 0.0,
    val updatedAt: Long = 0,
    /** 可选：前端已知章节标题时一并传入，免去服务端回查目录缓存。 */
    val chapterTitle: String? = null,
)

// ---------------------------------------------------------------------------
// 书籍进度同步（Legado 手机端 bookProgress 文件夹）
// ---------------------------------------------------------------------------
@Serializable data class ProgressSyncSettings(
    val directoryName: String,
    val directoryPath: String? = null,
    val available: Boolean = false,
    val fileCount: Int = 0,
)
@Serializable data class ProgressSyncSettingsUpdate(val directoryName: String)
@Serializable data class ProgressMergeRequest(
    val sourceId: String,
    val bookUrl: String,
    /** 当前书源的章节列表，用于把进度文件里的标题对齐到本地章节。 */
    val chapters: List<Chapter> = emptyList(),
)
@Serializable data class ProgressMergeResponse(
    /** `file` = 采用了进度文件；`database` = 采用数据库（文件不存在/更旧）。 */
    val source: String,
    val progress: ReadingProgress? = null,
    val fileFound: Boolean = false,
    val alignedIndex: Int? = null,
)
@Serializable data class BookshelfWriteRequest(
    val sourceId: String,
    val bookUrl: String,
    val name: String,
    val author: String? = null,
    val tocUrl: String,
    val coverUrl: String? = null,
    val alternateSources: List<SearchResult>? = null,
    val groupName: String? = null,
)
@Serializable data class BookshelfSourceSwitchRequest(
    val oldSourceId: String,
    val oldBookUrl: String,
    val book: BookshelfWriteRequest,
    val alternateSources: List<SearchResult>? = null,
)
@Serializable data class BookshelfStatusRequest(val sourceId: String, val bookUrl: String, val completed: Boolean)
@Serializable data class BookshelfInfoUpdateRequest(
    val sourceId: String,
    val bookUrl: String,
    val name: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val groupName: String? = null,
    val alternateSources: List<SearchResult>? = null,
)
data class CachedBookRequest(
    val sourceId: String,
    val bookUrl: String,
    val tocUrl: String,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
    val count: Int? = null,
)
@Serializable data class BookCacheRangeRequest(
    val sourceId: String,
    val bookUrl: String,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
    val count: Int? = null,
)
@Serializable data class CachedChaptersResponse(
    val sourceId: String,
    val bookUrl: String,
    val cachedChapterUrls: List<String>,
    val cachedCount: Int,
    val totalChapters: Int = 0,
)
@Serializable data class BookshelfItem(
    val sourceId: String,
    val bookUrl: String,
    val name: String,
    val author: String? = null,
    val tocUrl: String,
    val coverKey: String? = null,
    /**
     * 原始封面地址。备份导入的书架条目只有 URL 而无本地缓存副本，
     * 因此前端必须能在 [coverKey] 为空时回退到本字段直连加载，
     * 否则整架书的封面都会退化成文字占位符。
     */
    val coverUrl: String? = null,
    val chapterIndex: Int? = null,
    val scrollPosition: Double? = null,
    val lastReadAt: Long,
    val cachedChapters: Int = 0,
    val totalChapters: Int = 0,
    val cacheState: String = "idle",
    val cacheError: String? = null,
    val completed: Boolean = false,
    val alternateSources: List<SearchResult> = emptyList(),
    val groupName: String? = null,
)

@Serializable
data class BookGroup(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val bookCount: Int = 0,
)

/** 一条书签 / 阅读记录（来自备份包 `bookmark.json`）。 */
@Serializable
data class Bookmark(
    val id: Long,
    val bookName: String,
    val bookAuthor: String? = null,
    val chapterIndex: Int,
    val chapterName: String? = null,
    val chapterPos: Int = 0,
    val bookText: String? = null,
    val content: String? = null,
    val createdAt: Long = 0L,
)

@Serializable
data class BookGroupCreateRequest(val name: String)

@Serializable
data class BookGroupRenameRequest(val oldName: String, val newName: String)

@Serializable
data class BookGroupsOrderRequest(val groupNames: List<String>)

@Serializable
data class BookGroupUpdateRequest(
    val sourceId: String,
    val bookUrl: String,
    val groupName: String? = null,
)

@Serializable
data class BookKeyRequest(val sourceId: String, val bookUrl: String)

@Serializable
data class BookshelfBatchRequest(
    val action: String, // "move_group", "mark_completed", "delete"
    val items: List<BookKeyRequest>,
    val targetGroup: String? = null,
    val completed: Boolean? = null,
)

@Serializable
data class TtsVoice(
    val id: String,
    val name: String,
    val lang: String,
    val gender: String,
    val localeName: String,
    val engine: String = "edge",
    val description: String? = null,
)

@Serializable
data class TtsSpeakRequest(
    val text: String,
    val voice: String = "zh-CN-XiaoxiaoNeural",
    val rate: Int = 0,
    val pitch: Int = 0,
    val engine: String = "edge",
    val customUrl: String? = null,
    val customHeader: String? = null,
    val customMethod: String? = null,
    val customBody: String? = null,
)

@Serializable
data class TtsSessionInfo(
    val sessionId: String,
    val audioUrl: String,
    val eventsUrl: String,
)

@Serializable
data class TtsSessionChunkRequest(
    val chunkId: String,
    val text: String,
    val chapterIndex: Int = -1,
    val paragraphIndex: Int = -1,
    val engine: String = "edge",
    val voice: String = "zh-CN-XiaoxiaoNeural",
    val rate: Int = 0,
    val pitch: Int = 0,
    val customUrl: String? = null,
    val customHeader: String? = null,
    val customMethod: String? = null,
    val customBody: String? = null,
)

@Serializable
data class TtsSessionControlRequest(
    val action: String,
)

@Serializable
data class TtsSessionAck(
    val accepted: Boolean,
)

@Serializable
data class TtsSessionEvent(
    val type: String,
    val sessionId: String,
    val chunkId: String? = null,
    val chapterIndex: Int = -1,
    val paragraphIndex: Int = -1,
    val audioEndMs: Long? = null,
    val durationMs: Long? = null,
    val message: String? = null,
)

@Serializable
data class ReplaceRule(
    val id: String = "",
    val name: String = "",
    val group: String? = null,
    val pattern: String = "",
    val replacement: String = "",
    val isRegex: Boolean = true,
    val scope: String? = null,
    val excludeScope: String? = null,
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val isEnabled: Boolean = true,
    val order: Int = 0,
    val timeoutMillisecond: Long = 3000L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class ReplaceRuleToggleRequest(
    val ids: List<String>,
    val enabled: Boolean,
)

@Serializable
data class ReplaceRuleImportRequest(
    val rules: List<ReplaceRule>? = null,
    val url: String? = null,
)

@Serializable
data class ReplaceRuleImportResponse(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val total: Int,
)

@Serializable
data class ReplaceRulePreviewRequest(
    val text: String,
    val rule: ReplaceRule? = null,
    val bookName: String? = null,
    val sourceUrl: String? = null,
)

@Serializable
data class ReplaceRulePreviewResponse(
    val originalText: String,
    val cleanedText: String,
    val changed: Boolean,
    val appliedRules: List<String> = emptyList(),
)

@Serializable
data class BatchSourceRequest(
    val action: String, // "enable", "disable", "delete", "set_group"
    val ids: List<String>,
    val group: String? = null,
)

@Serializable
data class BatchSourceResponse(
    val ok: Boolean,
    val affected: Int,
    val action: String,
    val message: String? = null,
)

/**
 * 书源分组概览（一个分组一行，用于「按分组搜书」的选择项与分组管理）。
 *
 * 未分组（`source_group` 为 null / 空串）**不在此列表**：它不是一个真实的分组，
 * 只是「还没有分组」的状态，由前端单独提供「未分组」选项。
 */
@Serializable
data class SourceGroupSummary(
    val name: String,
    /** 组内书源总数。 */
    val sourceCount: Int,
    /** 其中已启用的数量 —— 搜索实际会用到的条数。 */
    val enabledCount: Int,
)

/** 书源分组重命名（把 `from` 组整体改名为 `to`；`to` 已存在时等价于合并）。 */
@Serializable
data class SourceGroupRenameRequest(
    val from: String = "",
    val to: String = "",
)

/** 书源分组改名/删除的结果。 */
@Serializable
data class SourceGroupMutationResponse(
    val ok: Boolean,
    val affected: Int,
    val message: String,
)

/**
 * 书源分组筛选里的特殊取值。
 *
 * 放在服务端与前端各自硬编码的「魔法字符串」会漂移，因此约定这个唯一来源：
 * 前端选择「未分组」时传 [UNGROUPED]，服务端翻译成 `source_group is null or trim(...)=''`。
 */
object SourceGroupFilter {
    const val UNGROUPED = "__ungrouped__"
}

@Serializable
data class SourceHealthCheckRequest(
    val ids: List<String>? = null,
    val timeoutMs: Long = 5000L,
)

@Serializable
data class SourceHealthCheckItem(
    val id: String,
    val name: String,
    val ok: Boolean,
    val latencyMs: Long,
    val statusCode: Int = 0,
    val statusCategory: String, // "valid", "slow", "failed", "blocked"
    val error: String? = null,
)

@Serializable
data class SourceHealthCheckResponse(
    val total: Int,
    val successCount: Int,
    val slowCount: Int,
    val failedCount: Int,
    val durationMs: Long,
    val results: List<SourceHealthCheckItem>,
)


/** WebDAV 存储区中的一个条目（供设置页面浏览使用）。 */
@Serializable
data class WebDavEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

/** WebDAV 设置页「导入」：以存储区内的相对路径指定一份 Legado 备份包。 */
@Serializable
data class BackupImportRequest(val path: String)

/**
 * WebDAV 设置页「导入书籍」：以存储区内的相对路径指定一本本地书籍（TXT / EPUB）。
 *
 * 与 [BackupImportRequest] 分开是为了让两个入口的契约各自独立演进 ——
 * 备份包导入的是「书源/规则/书架/分组/书签」，而这里只导入「一本可读的书」。
 */
@Serializable
data class WebDavBookImportRequest(val path: String)

/** 备份导入结果统计（供页面提示使用）。 */
@Serializable
data class BackupImportSummary(
    val sources: Int,
    val sourcesUpdated: Int,
    val rules: Int,
    val rulesUpdated: Int,
    val books: Int,
    val booksUpdated: Int,
    val progress: Int,
    /** 因是**本地图书**（手机本机文件，服务端读不到）而跳过的条数。 */
    val skippedLocal: Int = 0,
    /** 因是**音频/听书**（服务端只做文本阅读）而跳过的条数。 */
    val skippedAudio: Int = 0,
    /** 新增的书签数。 */
    val bookmarks: Int = 0,
    /** 因所属书籍未导入（本地图书/音频）而跳过的书签数。 */
    val bookmarksSkipped: Int = 0,
    /**
     * 随书源一起导入的**不同书源分组**个数。
     *
     * 备份包里**没有**独立的分组文件：书源分组是 Legado 书源自带的 `bookSourceGroup` 字段
     * （`bookGroup.json` 是书架分组，不是书源分组）。它跟着书源一起落库，
     * 这里只把个数如实报出来，便于用户确认分组确实进来了。
     */
    val sourceGroups: Int = 0,
)

/** 备份包 `bookshelf.json` 中的一条书架记录（含阅读进度），仅用于导入。 */
data class BackupShelfEntry(
    val sourceId: String,
    val bookUrl: String,
    val name: String,
    val author: String?,
    val tocUrl: String,
    val coverUrl: String?,
    val completed: Boolean,
    val chapterIndex: Int,
    val readAt: Long,
    /** 类别判定结果，用于导入时过滤本地图书与音频（见 [ShelfKind]）。 */
    val kind: ShelfKind = ShelfKind.ONLINE,
    /**
     * 分组名（已由 `bookGroup.json` 的 `groupId` 解析而来）。
     *
     * `bookshelf.json` 里存的是**数字 `group` id**，而本服务的 `book_group` 是**按名字**关联的
     * （见 `listBookGroups` 的 `s.group_name = g.name`），因此导入时必须先把 id 映射成名字。
     * 未分组 / 找不到对应分组时为 null。
     */
    val groupName: String? = null,
)

/**
 * 备份包 `bookGroup.json` 中的一条分组记录，仅用于导入。
 *
 * 字段来自真实备份：`bookSort, enableRefresh, groupId, groupName, order, show`。
 */
data class BackupGroupEntry(
    val groupId: Long,
    val groupName: String,
    val order: Int,
    /**
     * 是否为 Legado **内置的智能分组**（`groupId` 为负数）。
     *
     * 如 `在读(-20)`/`未读(-21)`/`已读(-22)`/`小说(-8)`/`漫画(-7)`/`全部(-1)`/`本地(-2)`/`音频(-3)`
     * 等，它们是**按条件动态筛选**的虚拟分组、不是真实归类，实测这些分组在真实备份里都是空的。
     */
    val builtIn: Boolean,
)

/**
 * 备份包 `bookmark.json` 中的一条书签/阅读记录，仅用于导入。
 *
 * 字段来自真实备份：`bookAuthor, bookName, bookText, chapterIndex, chapterName, chapterPos, content, time`。
 */
data class BackupBookmarkEntry(
    val bookName: String,
    val bookAuthor: String?,
    val chapterIndex: Int,
    val chapterName: String?,
    val chapterPos: Int,
    val bookText: String?,
    val content: String?,
    val time: Long,
)

/** 书架与阅读进度导入统计。 */
data class LibraryImportResult(val imported: Int, val updated: Int, val progress: Int)

/** 分组导入统计。 */
data class GroupImportResult(val created: Int, val assigned: Int)

/** WebDAV 设置页面的服务状态与当前目录内容。 */
@Serializable
data class WebDavInfoResponse(
    val url: String,
    val directory: String,
    val path: String,
    val parent: String? = null,
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long,
    val entries: List<WebDavEntry> = emptyList(),
)

/** 本地图书导入返回条目 */
@Serializable
data class LocalBookImportItem(
    val filename: String,
    val success: Boolean,
    val bookUrl: String? = null,
    val name: String? = null,
    val author: String? = null,
    val totalChapters: Int = 0,
    val error: String? = null,
)

/** 本地图书导入批量响应 */
@Serializable
data class LocalBookImportResponse(
    val total: Int,
    val imported: Int,
    val failed: Int,
    val results: List<LocalBookImportItem> = emptyList(),
)

/** 语言偏好设置请求与响应 */
@Serializable
data class LocaleSettingRequest(
    val locale: String,
)

@Serializable
data class LocaleSettingResponse(
    val locale: String?,
)


