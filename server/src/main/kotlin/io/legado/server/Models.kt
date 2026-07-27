package io.legado.server

import kotlinx.serialization.Serializable

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
    val updatedAt: Long,
    val version: Long,
)

@Serializable data class SourceRecord(
    val id: String,
    val json: String,
    val version: Long,
    val updatedAt: Long,
)

@Serializable data class SourceWriteRequest(val json: String, val version: Long? = null)
@Serializable data class ImportRequest(val sources: List<String>, val overwrite: Boolean = true)
@Serializable data class ImportResponse(val imported: Int, val updated: Int = 0, val skipped: Int, val errors: List<String>)
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
@Serializable data class SearchRequest(val keyword: String, val sourceIds: List<String>? = null)
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
@Serializable data class ContentRequest(val sourceId: String, val chapterUrl: String)
@Serializable data class ChapterContent(val title: String? = null, val content: String)
@Serializable data class ReadingProgress(
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int,
    val scrollPosition: Double = 0.0,
    val updatedAt: Long = 0,
)
@Serializable data class BookshelfWriteRequest(
    val sourceId: String,
    val bookUrl: String,
    val name: String,
    val author: String? = null,
    val tocUrl: String,
    val coverUrl: String? = null,
)
@Serializable data class BookshelfItem(
    val sourceId: String,
    val bookUrl: String,
    val name: String,
    val author: String? = null,
    val tocUrl: String,
    val coverKey: String? = null,
    val chapterIndex: Int? = null,
    val scrollPosition: Double? = null,
    val lastReadAt: Long,
)
