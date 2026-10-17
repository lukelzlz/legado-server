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

/**
 * 本地书源文件预览请求。
 *
 * 只提交**原始文本**：解析、体积上限与最终落库 payload 全部由服务端产出
 * （与 `POST /api/sources/import` 的信任边界相同）。
 *
 * @param label 文件名，仅用于日志与界面展示，不参与任何判定
 */
@Serializable data class LocalSourcePreviewRequest(
    val content: String,
    val label: String = "",
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
    /**
     * 书籍变量（Legado `Book.variable` 解析后的键值表）。
     *
     * 前端「编辑书籍信息」里的**书籍变量**设置栏要读它（编辑 `custom`），
     * 而书源规则侧读的是同一份数据（见 `RuleRunner.BookContext`）。
     */
    val variables: Map<String, String> = emptyMap(),
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
    /**
     * 深度模式：对「`cover_key` 为空且 `cover_url` 非空」的书**先回书源取新地址再物化**。
     *
     * 存在的理由：番茄（fqnovelpic）等 CDN 的封面地址是**带签名的**（约 60 天过期），
     * 历史数据里存的地址早已过期 ⇒ 服务器抓不到、客户端外链兜底也失效。
     * 深度模式回源拿到的是**全新的可用地址**，并会把它一并写回 `cover_url`。
     *
     * 默认 false ⇒ 行为与不加本字段时**完全一致**（只在已有的 `cover_url` 上重试）。
     */
    val deep: Boolean = false,
)

@Serializable data class CoverRefreshResponse(
    val total: Int,
    val refreshed: Int,
    val failed: Int,
    val skipped: Int,
    /** 深度模式下**回源取到与库里不同的新地址**的条数（便于判断这次补抓是否真的靠回源救活）。 */
    val resourced: Int = 0,
)

/**
 * 本地书重新解析请求。留空 [sourceId]/[bookUrl] 表示「全部本地书」。
 *
 * 用于修复历史数据：解析器缺陷修复**之前**导入的本地书，目录与正文早已按旧逻辑落库。
 */
@Serializable data class LocalReparseRequest(
    val sourceId: String? = null,
    val bookUrl: String? = null,
)

/** 单本本地书的重新解析结果。[status] 取 `reparsed` / `skipped` / `failed`。 */
@Serializable data class LocalReparseItem(
    val bookUrl: String,
    val name: String,
    val chapters: Int,
    val status: String,
    /** 仅 `skipped`（缺原始文件等）与 `failed`（解析/写库异常）时给出原因。 */
    val error: String? = null,
)

@Serializable data class LocalReparseResponse(
    /** 命中的本地书总数 */
    val total: Int,
    val reparsed: Int,
    val skipped: Int,
    val failed: Int,
    /** 本次重新写入的章节总数 */
    val chapters: Int,
    val items: List<LocalReparseItem> = emptyList(),
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
    /**
     * 文本/漫画的**比例**位置（0.0~1.0）。
     *
     * ⚠️ **仅 audio 之外的书籍使用它**：有声书的进度语义是**毫秒偏移**，
     * 走 [positionMs]。两者共用一个字段会让「一个 Double 三种语义」，
     * 且现有校验（`!in 0.0..1.0` ⇒ 400）会把毫秒值直接拒掉（G2 §8 的裁决，选 (a) 新增列）。
     */
    val scrollPosition: Double = 0.0,
    val updatedAt: Long = 0,
    /** 可选：前端已知章节标题时一并传入，免去服务端回查目录缓存。 */
    val chapterTitle: String? = null,
    /**
     * 有声书的**毫秒偏移**（与安卓 `durChapterPos` 同语义 ⇒ 跨端镜像可直传，无需换算）。
     * 非 audio 的书一律为 `null`。
     */
    val positionMs: Long? = null,
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

// ---------------------------------------------------------------------------
// 备份导出（Legado 格式 `backup<日期>-<设备名>.zip`）
// ---------------------------------------------------------------------------
/**
 * 备份导出设置。
 *
 * @param exportDir  WebDAV 根目录下的相对目录，空串表示**根目录**（这样导出的 zip 会直接
 *   出现在「文件管理」列表里，可当场下载，也能被手机 App 通过 WebDAV 取走）。默认 `legado`。
 * @param deviceName 设备名后缀，拼在日期之后（`backup2026-09-30-web.zip`）。默认 `web`。
 * @param autoExport **总开关**：关掉时下面三个子开关一律不生效。
 * @param exportOnPageClose 关闭网页时自动导出一次。
 * @param exportOnBookClose 关闭正在阅读的书时自动导出一次。
 * @param scheduledExport 定时导出。
 * @param scheduledTime 定时时刻，`HH:mm`（24 小时制），默认 `03:00`。
 */
@Serializable data class BackupExportSettings(
    val exportDir: String = "legado",
    val deviceName: String = "web",
    val autoExport: Boolean = false,
    val exportOnPageClose: Boolean = false,
    val exportOnBookClose: Boolean = false,
    val scheduledExport: Boolean = false,
    val scheduledTime: String = "03:00",
)

/**
 * 导出设置的**局部**更新：为 null 的字段表示「这次不改」。
 *
 * 页面上「导出路径」「设备名后缀」各有独立保存按钮，勾选类开关是勾了就存，
 * 因此契约必须支持「改哪个存哪个」，不能整个覆盖。
 */
@Serializable data class BackupExportSettingsUpdate(
    val exportDir: String? = null,
    val deviceName: String? = null,
    val autoExport: Boolean? = null,
    val exportOnPageClose: Boolean? = null,
    val exportOnBookClose: Boolean? = null,
    val scheduledExport: Boolean? = null,
    val scheduledTime: String? = null,
)

/**
 * 自动导出的响应。
 *
 * `exported=false` 是**正常结果**（开关没开），不是错误 —— 前端在页面关闭时无条件上报，
 * 是否真的导出由**服务端**判定（信任边界：客户端不该决定要不要写盘）。
 */
@Serializable data class BackupAutoExportResult(
    val exported: Boolean,
    val reason: String? = null,
    val fileName: String? = null,
    val path: String? = null,
    val size: Long = 0,
)

/** 导出结果。`path` 是相对 WebDAV 根的路径，便于前端提示与定位。 */
@Serializable data class BackupExportResult(
    val fileName: String,
    val path: String,
    val size: Long,
    val books: Int,
    val sources: Int,
    val bookmarks: Int,
    val groups: Int,
)
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
    val bookKind: String? = null,
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
    val coverKey: String? = null,
    val groupName: String? = null,
    val alternateSources: List<SearchResult>? = null,
    /**
     * 书籍变量里的 **`custom` 键的新值**（对齐 Legado 的 `putCustomVariable(value)`）。
     *
     * - `null`  ⇒ **不动**已有变量（弹窗只改书名/作者时绝不能顺手清空音色设置）；
     * - `""` 与任意字符串 ⇒ 写入该键（空串读出来就是空串，与"没有这个键"对 `book.getVariable('custom')`
     *   的返回值**完全一致**，因此不必区分）。
     *
     * 刻意**不**让前端提交整张变量表的 JSON：键名 `custom` 的语义在 Legado 里是定死的，
     * 让服务端做合并（而不是信任前端拼的 JSON）才能保证"编辑音色"永远不会误删别的键。
     */
    val customVariable: String? = null,
)
@Serializable data class CoverUploadResponse(
    val coverKey: String,
    val contentType: String,
    val url: String,
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
    val bookKind: String = "novel",
    /**
     * 有声书进度的**毫秒偏移**（`reading_progress.position_ms`，与手机端 `durChapterPos` 同语义）。
     *
     * 非 audio 的书恒为 null：文本/漫画的章内位置是 [scrollPosition] 比例，两套语义不混用。
     * 导出备份时它直传成 `durChapterPos`（音频那一类才写），否则换设备后听书得从头开始。
     */
    val positionMs: Long? = null,
    /**
     * **书籍变量**（Legado `Book.variable`）—— 一个 JSON 对象字符串，键值都是字符串。
     *
     * 用途（对齐开源阅读 `legado-with-MD3` 的做法）：书源规则在 JS 里用
     * `book.getVariable('custom')` 读取书籍级变量，最典型的场景就是**切换听书音色/朗读者**
     * （如 `🍅大灰狼聚合` 源的注释：「详情页会显示本书可用音色及数字编号，**右上角书籍变量填写编号**即可切换」）。
     * `custom` 在 Legado 里是一等公民（`getCustomVariable()` / `putCustomVariable()`），
     * 前端编辑弹窗只编辑它，但底层存的始终是**整张表**的 JSON。
     */
    val variable: String? = null,
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
    val httpTtsId: Long? = null,
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
    val httpTtsId: Long? = null,
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
data class BatchReplaceRuleRequest(
    val action: String, // "enable", "disable", "delete", "set_group"
    val ids: List<String>,
    val group: String? = null,
)

@Serializable
data class BatchReplaceRuleResponse(
    val ok: Boolean,
    val affected: Int,
    val action: String,
    val message: String? = null,
)

@Serializable
data class ReplaceRuleGroupSummary(
    val name: String,
    val ruleCount: Int,
    val enabledCount: Int,
)

@Serializable
data class ReplaceRuleGroupRenameRequest(
    val from: String = "",
    val to: String = "",
)

@Serializable
data class ReplaceRuleGroupMutationResponse(
    val ok: Boolean,
    val affected: Int,
    val message: String,
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
    /**
     * 因是**本地图书**（手机本机文件，服务端读不到）而跳过的条数。
     *
     * **这是唯一会跳过的类别**：音频/听书与漫画都已是一等公民（各自有 `book_kind`、
     * 缓存目录与阅读器），导入时必须放行。前端据此如实提示"跳过原因"。
     */
    val skippedLocal: Int = 0,
    /** 本次成功补抓成本地副本的封面张数（导入摘要提示用）。 */
    val coversFetched: Int = 0,
    /**
     * 其中有多少张是**回源**成功的（备份里的地址已失效 ⇒ 回书源详情页取新地址后下载成功）。
     *
     * 与 [coversFetched] 是父子关系（`coversResourced <= coversFetched`），
     * 单独回报是为了让"自动补上了"这件事可观测（用户与排障都需要）。
     */
    val coversResourced: Int = 0,
    /**
     * 封面补抓失败的张数（图床失效 / 签名过期 / 防盗链 / 超时）。
     *
     * 失败的封面**不影响导入本身**：该书仍会入架，只是没有本地副本，前端回退到
     * `coverUrl` 直连、再失败则显示文字占位。这个计数是为了让"封面全裂"有可解释的原因，
     * 而不是静默吞掉。
     */
    val coversFailed: Int = 0,
    /**
     * 已**受理**、转入**后台**补抓的本数（导入接口不再等封面抓完就返回）。
     *
     * 与 [coversFetched] 的关系：同步模式下它恒为 0（抓完才返回，计数在
     * `coversFetched/coversFailed`）；后台模式下 `coversFetched/coversFailed/coversResourced`
     * 都还来不及有结果（如实为 0），真正受理了多少本由本字段说明。
     *
     * 为什么不再有「补抓数量上限」：用户明确要求「导入时优先全补上，不限数量上限」。
     * 没补上的书由 `CoverSelfHeal` 的常驻巡检在之后自动接续（后期封面丢了同样由它补）。
     */
    val coversQueued: Int = 0,
    /** 新增的书签数。 */
    val bookmarks: Int = 0,
    /** 因所属书籍未导入（**仅本地图书**）而跳过的书签数。 */
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
    /**
     * 手机端的**章内位置**（`durChapterPos`）。
     *
     * ⚠️ 只有**有声书**会用它：对音频它就是毫秒，与 `reading_progress.position_ms` **同语义、直传**
     * （G11）。文本/漫画**刻意不导入它**——安卓的字符偏移量与我们的 `0..1` 比例不是一回事
     * （AGENTS.md 明文：「Android 的字符偏移量 `durChapterPos` 不得强塞入服务端的百分比 `scroll_position`」）。
     */
    val chapterPos: Int = 0,
    /** 类别判定结果，用于导入时过滤本地图书（见 [ShelfKind]）。 */
    val kind: ShelfKind = ShelfKind.ONLINE,
    /**
     * 分组名（已由 `bookGroup.json` 的 `groupId` 解析而来）。
     *
     * `bookshelf.json` 里存的是**数字 `group` id**，而本服务的 `book_group` 是**按名字**关联的
     * （见 `listBookGroups` 的 `s.group_name = g.name`），因此导入时必须先把 id 映射成名字。
     * 未分组 / 找不到对应分组时为 null。
     */
    val groupName: String? = null,
    /**
     * 书的内容类型（`novel`/`comic`/`audio`）——**由 [kind] 显式映射**：
     * 听书 ⇒ `audio`、漫画 ⇒ `comic`；在线小说仍只看书自己载荷里的 `tab`（没说 ⇒ null）。
     *
     * 为什么漫画必须显式落 `comic`：按小说入库会进文字阅读器，代价是"漫画打不开"而不是报错。
     * 为什么在线小说仍允许 `null`：`null` 表示「不指定」，新行留 NULL（按小说处理），
     * 已有行**保留原值**（`coalesce(excluded.book_kind, book_shelf.book_kind)`），
     * 也就是"已经和小说一样就跳过"。源声明（`bookSourceType`）由
     * [Database.backfillBookKindFromSources] 在导入结束时统一兜底。
     */
    val bookKind: String? = null,
    /**
     * 书籍变量（Legado `Book.variable`，JSON 对象字符串）。
     *
     * 手机端备份的 `bookshelf.json` 里就有这个字段，且**正是音色/朗读者选择的落点**
     * （见 `BookshelfItem.variable`）。不导入它，用户换设备后就丢了听书音色设置。
     */
    val variable: String? = null,
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

/** 自定义 HTTP TTS 规则，对齐 Legado httpTTS.json 规范 */
@Serializable
data class HttpTts(
    val id: Long = 0L,
    val name: String = "",
    val url: String = "",
    val header: String? = null,
    val contentType: String? = null,
    val concurrentRate: String? = null,
    val loginUrl: String? = null,
    val loginCheckJs: String? = null,
    val loginUi: String? = null,
    val jsLib: String? = null,
    val enabledCookieJar: Boolean = false,
    val lastUpdateTime: Long = 0L,
)

/** HTTP TTS 连通性测试请求 */
@Serializable
data class HttpTtsTestRequest(
    val tts: HttpTts,
    val text: String = "欢迎使用开源阅读自定义朗读",
    val speed: Double = 1.0,
    val voice: String = "",
)

/** HTTP TTS 导入响应 */
@Serializable
data class HttpTtsImportResponse(
    val total: Int,
    val imported: Int,
    val failed: Int,
)

/** 书源发现分类结构 */
@Serializable
data class ExploreCategory(
    val title: String,
    val url: String? = null,
    val subCategories: List<ExploreCategory> = emptyList(),
)

/** 带有发现页的书源摘要 */
@Serializable
data class ExploreSourceItem(
    val id: String,
    val name: String,
    val group: String? = null,
    val enabled: Boolean = true,
)

// ---------------------------------------------------------------------------
// 漫画（图片书源）章节清单 —— 方案 E · P0 解析打通
// ---------------------------------------------------------------------------

/**
 * P1：单张图片的清单项。**只给服务端 key，绝不给上游 URL** ——
 * 否则前端会绕过字节缓存直连（防盗链、离线、去重全部失效，属"死配置"类错误）。
 */
@Serializable
data class MangaImageItem(
    val index: Int,
    /** `GET /api/images/{key}` 的 key（= sha256(sourceId + '\n' + 上游URL)）。 */
    val key: String,
    /** pending | ready | failed */
    val status: String,
    val width: Int? = null,
    val height: Int? = null,
)

/** 章节图片清单。P1 起 `images` 只含服务端 key 与状态；前端按 key 请求图片。 */
@Serializable
data class MangaChapterManifest(
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    val title: String? = null,
    val chapterIndex: Int = 0,
    val total: Int,
    /** 已就绪张数；`ready == total` ⇒ 整章可离线（目录绿点 `●`）。 */
    val ready: Int = 0,
    /** 整章状态：pending | partial | ready | failed */
    val state: String = "pending",
    val images: List<MangaImageItem>,
)

/** 漫画清单请求。`chapterIndex` 由前端从目录带过来，用于滑窗 GC 的锚点计算。 */
@Serializable
data class MangaManifestRequest(
    val sourceId: String,
    val bookUrl: String? = null,
    val chapterUrl: String = "",
    val chapterIndex: Int = 0,
)

// ---------------------------------------------------------------------------
// 漫画链路 · P1 服务端缓存（L1 字节索引 + L2 章节清单）
// ---------------------------------------------------------------------------

/**
 * 一章的图片清单与「整章就绪度」（L2 视图层）。
 *
 * `ready == total` 才算整章可离线，目录才打绿点 `●` —— 这是**唯一口径**，
 * 前端不要自己按「有清单就算缓存」判断（否则断网会看到半截裂图）。
 */
data class MangaChapterRecord(
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    /** 章节序号，滑窗 GC 的锚点计算必需（落后当前进度 N 章 ⇒ 第 1 顺位淘汰候选）。 */
    val chapterIndex: Int = 0,
    /** 免死金牌：用户手动「下载整本/离线」的章节，GC 永不自动淘汰。 */
    val pinned: Boolean = false,
    /** 有序上游 URL 数组的 JSON（**仅服务端可见，绝不下发前端**）。 */
    val imagesJson: String,
    val total: Int = 0,
    val ready: Int = 0,
    val skipped: Int = 0,
    /** pending | partial | ready | failed */
    val state: String = "pending",
    val lastError: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** 一张图片的字节索引（L1）。`cacheKey` 指向唯一字节副本；`urlHash` 是"哪个上游地址"的索引。 */
data class MangaImageRecord(
    /** sha256(sourceId + '\n' + 上游URL)：**含 sourceId**，防跨源脏命中。对外即 `/api/images/{key}` 里的 key。 */
    val urlHash: String,
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    /** 仅服务端可见，**绝不下发前端**（否则前端会绕过缓存直连，防盗链与离线全失效）。 */
    val upstreamUrl: String,
    /** sha256(字节)；`state != ready` 时为空。 */
    val cacheKey: String = "",
    val chapterIndex: Int = 0,
    /** 一章内第几张图（多分段/保序拼装用）。 */
    val segmentIndex: Int = 0,
    val contentType: String = "",
    val byteSize: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    /** pending | ready | failed */
    val state: String = "pending",
    val lastError: String? = null,
    val createdAt: Long = 0,
    val lastAccessAt: Long = 0,
    /**
     * 落盘的**相对路径**（如 `十日终焉 (3f9c21ab7d0412e8c9f0)/00000499 真正的生路/00000001.mp3`）。
     *
     * ⚠️ 它只是**快路径缓存**，不是唯一真相：读取定位的第二级是
     * `AtomicFileStore.findByNameFree`（只认 `sha256(sourceId+'\n'+bookUrl)` 前
     * `AtomicFileStore.BOOK_HASH_LENGTH` 位 + 两个 8 位序号）。
     * 为空（或指向的文件已不在）时按"快路径未命中"处理，绝不因此判定"文件不存在"。
     */
    val filePath: String = "",
)

/** 按书聚合的缓存占用（缓存管理 UI 与 stats 接口用）。 */
@Serializable
data class MangaCacheBookStat(
    val sourceId: String,
    val bookUrl: String,
    val name: String? = null,
    val imageCount: Int,
    val totalBytes: Long,
    val chapterCount: Int = 0,
    val readyChapterCount: Int = 0,
    /** 免死金牌（用户手动"整本离线"）：缓存管理 UI 必须能看出"这本书为什么删不掉"。 */
    val pinned: Boolean = false,
)

/**
 * 图片缓存总体统计（`GET /api/books/images/cache/stats`）。
 *
 * `totalBytes`（表内字节和）与 `diskBytes`（实际磁盘占用）**分开报**：
 * 两者一旦明显漂移，就说明盘上有孤儿文件或表里有失效行 —— 排障时一眼可见。
 */
@Serializable
data class MangaCacheStats(
    val totalBytes: Long,
    val quotaBytes: Long,
    val imageCount: Int,
    val diskBytes: Long,
    val books: List<MangaCacheBookStat> = emptyList(),
)

// ---------------------------------------------------------------------------
// 漫画链路 · P2 预取（环节 E8）
// ---------------------------------------------------------------------------

/**
 * 预取请求（`POST /api/books/images/prefetch`）。
 *
 * `fromChapterIndex` 由前端从**自己的目录**带过来：服务端虽然也缓存了目录，
 * 但前端此刻手里就有一份权威的、和用户看到的一致的章节列表，不必让服务端再猜一次。
 */
@Serializable
data class MangaPrefetchRequest(
    val sourceId: String,
    val bookUrl: String? = null,
    /** 起始章地址（用于回显与排障；真正的区间由 [fromChapterIndex] + [ahead] 决定）。 */
    val chapterUrl: String? = null,
    val fromChapterIndex: Int = 0,
    /** 从起始章起预取多少章（含起始章）：`1` = 只本章；`50`/`100` = 目录里的"后 N 章"。 */
    val ahead: Int = 1,
    /**
     * `true` = 用户手动"整本离线" ⇒ 顺手打 **pinned 免死金牌**（GC 永不自动淘汰）。
     * 只有明确的用户动作才配得上免死金牌，滚动触发的自动预取一律 `false`。
     */
    val pin: Boolean = false,
)

/**
 * 预取受理结果。**`queued=false` 不是错误**：可能是开关关了、目录未缓存或区间为空。
 *
 * 阅读器对它的唯一正确反应是"什么都不做"——预取是增强，失败绝不能冒泡给用户。
 */
@Serializable
data class MangaPrefetchResponse(
    val queued: Boolean,
    val chapters: Int = 0,
    val reason: String? = null,
)

/** pin 开关结果（`PUT /api/books/images/cache/pin`）。 */
@Serializable
data class MangaPinResponse(
    val sourceId: String,
    val bookUrl: String,
    val pinned: Boolean,
)

// ---------------------------------------------------------------------------
// 有声书（音频书源）—— 方案 G2（PROPOSAL-029 / ADR-029）
// ---------------------------------------------------------------------------

/**
 * 一章里的**一个音频分段**。
 *
 * ⚠️ 与漫画清单同一条铁律：**只给服务端 key，绝不给上游 URL**
 * （给了前端就会绕过缓存直连上游 ⇒ 防盗链、离线、Range 复用全部失效）。
 */
@Serializable
data class AudioTrack(
    /** 段内序号（0 起）；一章多分段时前端按它顺序串播。 */
    val index: Int,
    /** `GET /api/audio/{key}` 的 key（= sha256(sourceId + '\n' + 规范化上游URL)）。 */
    val key: String,
    /** 与 [index] 同值，显式带出便于前端与 DB 对齐（DB 列名就是 `segment_index`）。 */
    val segmentIndex: Int = 0,
    /** pending | downloading | ready | failed */
    val status: String = "pending",
    /** 毫秒时长；由前端 `loadedmetadata` 回传（服务端不引音频解析库）。 */
    val durationMs: Long = 0,
    val byteSize: Long = 0,
)

/** 一章音频清单（`POST /api/books/audio` 的响应）。 */
@Serializable
data class AudioChapterManifest(
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int = 0,
    val total: Int,
    /** 已就绪分段数；`ready == total` ⇒ 整章可离线（目录绿点 `●` 的唯一口径）。 */
    val ready: Int = 0,
    /** pending | partial | ready | failed */
    val state: String = "pending",
    val tracks: List<AudioTrack> = emptyList(),
)

/** 音频清单请求。`chapterIndex` 由前端从目录带过来，用于滑窗 GC 的锚点计算。 */
@Serializable
data class AudioManifestRequest(
    val sourceId: String,
    val bookUrl: String? = null,
    val chapterUrl: String = "",
    val chapterIndex: Int = 0,
)

/**
 * 解析出来的「一段音频」的抓取规格。
 *
 * `url,{"headers":{...}}` 形态（Legado 生态常态）里每段可带**独立请求头**，
 * 必须随分段一起传给 Fetcher，否则重拉时会 403（G2 §5.1 的第 2 处规格缺口）。
 */
data class AudioTrackSpec(
    val url: String,
    val headersJson: String? = null,
)

/** 一段音频的缓存索引行（`audio_cache`，L1）。 */
data class AudioCacheRecord(
    /** sha256(sourceId + '\n' + 规范化URL)：**含 sourceId**，防跨源脏命中。对外即 `/api/audio/{key}`。 */
    val cacheKey: String,
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int = 0,
    /** 一章内第几段（多分段保序拼装用）。 */
    val segmentIndex: Int = 0,
    /** 仅服务端可见，**绝不下发前端**。 */
    val upstreamUrl: String,
    /** 该段专属请求头 JSON（`{"headers":{...}}` 的 `headers` 部分），重拉/GC 后重下必需。 */
    val headersJson: String? = null,
    val contentType: String = "",
    val byteSize: Long = 0,
    val durationMs: Long = 0,
    /** pending | downloading | ready | failed */
    val state: String = "pending",
    val lastError: String? = null,
    /** 免死金牌（用户手动「下载整本」）：GC 永不自动淘汰。 */
    val pinned: Boolean = false,
    /** 听完（完成度 > 80%）⇒ GC 第 1 顺位淘汰候选。 */
    val listened: Boolean = false,
    val createdAt: Long = 0,
    val lastAccessAt: Long = 0,
    /**
     * 落盘的**相对路径**（如 `十日终焉 (3f9c21ab7d0412e8c9f0)/00000499 真正的生路/00000001.mp3`）。
     *
     * ⚠️ 同 [MangaImageRecord.filePath]：**快路径缓存**而已，读取的第二级是无中文定位
     * （`AtomicFileStore.findByNameFree`），因此中文目录被改名也读得到。
     */
    val filePath: String = "",
)

/** 按书聚合的音频缓存占用。 */
@Serializable
data class AudioCacheBookStat(
    val sourceId: String,
    val bookUrl: String,
    val name: String? = null,
    val trackCount: Int,
    val totalBytes: Long,
    val chapterCount: Int = 0,
    val readyChapterCount: Int = 0,
    val pinned: Boolean = false,
)

/**
 * 音频缓存总体统计（`GET /api/audio/cache/stats`）。
 *
 * `totalBytes`（表内字节和）与 `diskBytes`（实际磁盘占用）**分开报**：两者漂移即说明有孤儿文件或失效行。
 */
@Serializable
data class AudioCacheStats(
    val totalBytes: Long,
    val quotaBytes: Long,
    val trackCount: Int,
    val diskBytes: Long,
    val books: List<AudioCacheBookStat> = emptyList(),
)

/** 批量缓存请求（`POST /api/books/audio/cache`）。 */
@Serializable
data class AudioPrefetchRequest(
    val sourceId: String,
    val bookUrl: String? = null,
    val chapterUrl: String? = null,
    val fromChapterIndex: Int = 0,
    /** 从起始章起缓存多少章（含起始章）。 */
    val ahead: Int = 1,
    /** `true` = 用户手动「整本离线」⇒ 顺手打 pinned 免死金牌。 */
    val pin: Boolean = false,
)

/** 批量缓存受理结果。**`queued=false` 不是错误**（开关关了/目录未缓存/区间为空）。 */
@Serializable
data class AudioPrefetchResponse(
    val queued: Boolean,
    val chapters: Int = 0,
    val reason: String? = null,
)

/**
 * 一本书的音频缓存任务进度（`GET /api/books/audio/prefetch`）。
 *
 * ## 单位契约（与 [MangaPrefetchStatus] 逐字同源）
 * [done] / [planned] 是**章**（`done` 只统计"整章分段全部落盘"的章节），
 * [doneTracks] / [plannedTracks] 才是**分段**数。用户反馈"进度写着章节、
 * 实际是每章的分段数"，就是因为旧字段曾经是分段口径而文案写"章"。
 * 只**新增**字段、不复用旧字段表达别的含义。
 */
@Serializable
data class AudioPrefetchStatus(
    val sourceId: String,
    val bookUrl: String,
    val running: Boolean = true,
    /** **已完成章节数**（整章分段全部落盘才算完成；本次任务开始前就已就绪的章也计入）。 */
    val done: Int = 0,
    /** 本次任务**规划**的章节数（目录还没抓到时为 0，抓完立刻回填）。 */
    val planned: Int = 0,
    val currentChapterIndex: Int = 0,
    /** 已落盘**分段**数（细节口径，不是"章"）。 */
    val doneTracks: Int = 0,
    /** 本次任务已解析出的**分段**数（逐章增长，细节口径，不是"章"）。 */
    val plannedTracks: Int = 0,
    /** 本次任务内失败的分段数（>0 时用 [lastError] 说明首条原因）。 */
    val failed: Int = 0,
    /** 首条失败原因（`HTTP connect timed out` / `上游返回 HTTP 403` / 超限…）。 */
    val lastError: String? = null,
)

/** pin 开关结果（`PUT /api/audio/cache/pin`）。 */
@Serializable
data class AudioPinResponse(
    val sourceId: String,
    val bookUrl: String,
    val pinned: Boolean,
)

/**
 * 前端 `loadedmetadata` 回传的音频时长（`POST /api/audio/duration`）。
 *
 * 服务端**不引音频解析库**（Non-Goal）：时长只用于统计与 GC 的"听完"判据，
 * 拿不到就保持 0，绝不能因此影响播放。
 */
@Serializable
data class AudioDurationRequest(
    val key: String,
    val durationMs: Long,
    /**
     * 当前播放位置（毫秒），可空。
     *
     * 前端**顺手带一次**就够了：服务端据 `positionMs / durationMs >= 80%` 给这一段打
     * 「听完」标记（GC 的输入之一）。刻意不新增一个"上报收听进度"的接口——
     * 位置本身已经通过 `/api/reading-progress` 的 `position_ms` 落库了，这里只是附带的判据。
     */
    val positionMs: Long = 0,
)



