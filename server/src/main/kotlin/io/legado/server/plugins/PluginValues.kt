package io.legado.server.plugins

import io.legado.server.BookDetails
import io.legado.server.BookshelfItem
import io.legado.server.CachedCover
import io.legado.server.Chapter
import io.legado.server.ChapterContent
import io.legado.server.ImportResponse
import io.legado.server.ReadingProgress
import io.legado.server.SearchResult
import io.legado.server.SourceRecord
import io.legado.server.SourceSubscription
import io.legado.server.SourceSummary

/**
 * Converts server models into the plain JSON-shaped maps plugins receive.
 *
 * The plugin boundary deliberately does not expose the internal `@Serializable` classes: plugin
 * authors get a documented, stable field set, and the server stays free to reshape its own models.
 * Timestamps are epoch milliseconds, matching the REST API the web client already consumes.
 */
object PluginValues {

    fun shelfItem(item: BookshelfItem): Map<String, Any?> = mapOf(
        "sourceId" to item.sourceId,
        "bookUrl" to item.bookUrl,
        "name" to item.name,
        "author" to item.author,
        "tocUrl" to item.tocUrl,
        "coverKey" to item.coverKey,
        "coverUrl" to item.coverKey?.let { "/api/covers/$it" },
        "chapterIndex" to item.chapterIndex,
        "scrollPosition" to item.scrollPosition,
        "lastReadAt" to item.lastReadAt,
        "cachedChapters" to item.cachedChapters,
        "totalChapters" to item.totalChapters,
        "cacheState" to item.cacheState,
        "cacheError" to item.cacheError,
        "completed" to item.completed,
        "alternateSources" to item.alternateSources.map(::searchResult),
    )

    fun searchResult(result: SearchResult): Map<String, Any?> = mapOf(
        "sourceId" to result.sourceId,
        "name" to result.name,
        "author" to result.author,
        "bookUrl" to result.bookUrl,
        "coverUrl" to result.coverUrl,
        "intro" to result.intro,
    )

    fun bookDetails(details: BookDetails): Map<String, Any?> = mapOf(
        "sourceId" to details.sourceId,
        "name" to details.name,
        "author" to details.author,
        "intro" to details.intro,
        "coverUrl" to details.coverUrl,
        "tocUrl" to details.tocUrl,
    )

    fun chapter(chapter: Chapter): Map<String, Any?> = mapOf(
        "index" to chapter.index,
        "title" to chapter.title,
        "url" to chapter.url,
    )

    fun chapterContent(content: ChapterContent, cachedAt: Long? = null): Map<String, Any?> = mapOf(
        "title" to content.title,
        "content" to content.content,
        "length" to content.content.length,
        "cachedAt" to cachedAt,
    )

    fun progress(progress: ReadingProgress): Map<String, Any?> = mapOf(
        "sourceId" to progress.sourceId,
        "bookUrl" to progress.bookUrl,
        "chapterUrl" to progress.chapterUrl,
        "chapterIndex" to progress.chapterIndex,
        "scrollPosition" to progress.scrollPosition,
        "updatedAt" to progress.updatedAt,
    )

    fun sourceSummary(source: SourceSummary): Map<String, Any?> = mapOf(
        "id" to source.id,
        "name" to source.name,
        "url" to source.url,
        "group" to source.group,
        "enabled" to source.enabled,
        "isJsSource" to source.isJsSource,
        "hasLogin" to source.hasLogin,
        "updatedAt" to source.updatedAt,
        "version" to source.version,
    )

    fun sourceRecord(source: SourceRecord): Map<String, Any?> = mapOf(
        "id" to source.id,
        "json" to source.json,
        "version" to source.version,
        "updatedAt" to source.updatedAt,
    )

    fun subscription(subscription: SourceSubscription): Map<String, Any?> = mapOf(
        "id" to subscription.id,
        "url" to subscription.url,
        "enabled" to subscription.enabled,
        "createdAt" to subscription.createdAt,
        "updatedAt" to subscription.updatedAt,
        "lastSuccessAt" to subscription.lastSuccessAt,
        "lastAttemptAt" to subscription.lastAttemptAt,
        "lastError" to subscription.lastError,
        "lastImported" to subscription.lastImported,
        "contentHash" to subscription.contentHash,
    )

    fun importResponse(response: ImportResponse): Map<String, Any?> = mapOf(
        "imported" to response.imported,
        "updated" to response.updated,
        "skipped" to response.skipped,
        "errors" to response.errors,
    )

    fun cover(cover: CachedCover): Map<String, Any?> = mapOf(
        "key" to cover.key,
        "contentType" to cover.contentType,
        "url" to "/api/covers/${cover.key}",
    )

    // --- map -> model helpers (used when plugins write data back) ---------------------------

    fun stringField(fields: Map<String, Any?>, key: String): String? = fields[key]?.toString()?.takeIf { it.isNotBlank() }

    fun stringField(fields: Map<String, Any?>, key: String, fallback: String): String =
        stringField(fields, key) ?: fallback

    fun intField(fields: Map<String, Any?>, key: String, fallback: Int = 0): Int = when (val value = fields[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }

    fun longField(fields: Map<String, Any?>, key: String, fallback: Long = 0L): Long = when (val value = fields[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: fallback
        else -> fallback
    }

    fun doubleField(fields: Map<String, Any?>, key: String, fallback: Double = 0.0): Double = when (val value = fields[key]) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: fallback
        else -> fallback
    }

    fun boolField(fields: Map<String, Any?>, key: String, fallback: Boolean = false): Boolean = when (val value = fields[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> fallback
    }
}
