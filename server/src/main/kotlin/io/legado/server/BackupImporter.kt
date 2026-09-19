package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Legado App 备份包（`backup-*.zip`）导入器。
 *
 * 只识别备份包内的固定文件名：`bookSource.json`（书源）、`replaceRule.json`（替换净化规则）、
 * `bookshelf.json`（书架 + 阅读进度）。RSS / TTS / 字典 / 主题 / 阅读统计 / 书签等条目在服务端
 * 没有对应能力，一律忽略而不报错。
 *
 * 实现要点：
 * 1. 全程用 [ZipFile] 按条目名读取，绝不把条目解包到磁盘，从根本上规避 zip-slip 路径穿越；
 * 2. 声明解压体积超限直接拒绝，避免 zip 炸弹撑爆内存；
 * 3. 书架的 `origin` 经 [SourceCodec.normalizeSourceId] 归一化，才能与书源表主键（也是 sourceId）对齐。
 */
class BackupImporter(private val database: Database) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun import(file: Path): BackupImportSummary = ZipFile(file.toFile()).use { zip ->
        val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
        require(entries.sumOf { it.size.coerceAtLeast(0L) } <= MAX_TOTAL_BYTES) {
            "备份包展开后超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MiB，已拒绝"
        }
        val sources = readSection(zip, entries, "booksource.json")?.let(::parseSources).orEmpty()
        val rules = readSection(zip, entries, "replacerule.json")?.let(::parseRules).orEmpty()
        val shelf = readSection(zip, entries, "bookshelf.json")?.let(::parseShelf).orEmpty()
        require(sources.isNotEmpty() || rules.isNotEmpty() || shelf.isNotEmpty()) {
            "不是 Legado 备份包：未找到 bookSource.json / replaceRule.json / bookshelf.json"
        }

        val sourceResult = database.importSources(sources)
        val ruleResult = database.importReplaceRules(rules)
        val library = database.importLibrary(shelf)
        BackupImportSummary(
            sources = sourceResult.imported,
            sourcesUpdated = sourceResult.updated,
            rules = ruleResult.imported,
            rulesUpdated = ruleResult.updated,
            books = library.imported,
            booksUpdated = library.updated,
            progress = library.progress,
        )
    }

    private fun readSection(zip: ZipFile, entries: List<ZipEntry>, fileName: String): String? {
        val entry = entries.firstOrNull { it.name.substringAfterLast('/').lowercase() == fileName } ?: return null
        require(entry.size <= MAX_ENTRY_BYTES) { "$fileName 展开后超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MiB，已拒绝" }
        return zip.getInputStream(entry).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }

    private fun parseSources(text: String): List<String> =
        array(text, "bookSource.json").mapNotNull { element -> (element as? JsonObject)?.toString() }

    private fun parseRules(text: String): List<ReplaceRule> = array(text, "replaceRule.json").mapNotNull { element ->
        val rule = element as? JsonObject ?: return@mapNotNull null
        val pattern = rule.text("pattern") ?: return@mapNotNull null
        ReplaceRule(
            id = rule.text("id").orEmpty(),
            name = rule.text("name") ?: pattern,
            group = rule.text("group"),
            pattern = pattern,
            replacement = rule.text("replacement").orEmpty(),
            isRegex = rule.flag("isRegex") ?: true,
            scope = rule.text("scope"),
            excludeScope = rule.text("excludeScope"),
            scopeTitle = rule.flag("scopeTitle") ?: false,
            scopeContent = rule.flag("scopeContent") ?: true,
            isEnabled = rule.flag("isEnabled") ?: true,
            order = rule.number("order")?.toInt() ?: 0,
            timeoutMillisecond = rule.number("timeoutMillisecond") ?: 3000L,
        )
    }

    private fun parseShelf(text: String): List<BackupShelfEntry> = array(text, "bookshelf.json").mapNotNull { element ->
        val book = element as? JsonObject ?: return@mapNotNull null
        val origin = book.text("origin")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val bookUrl = book.text("bookUrl")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val sourceId = SourceCodec.normalizeSourceId(origin)
        if (sourceId.isBlank()) return@mapNotNull null
        BackupShelfEntry(
            sourceId = sourceId,
            bookUrl = bookUrl,
            name = book.text("name")?.takeIf { it.isNotBlank() } ?: bookUrl,
            author = book.text("author")?.takeIf { it.isNotBlank() },
            tocUrl = book.text("tocUrl")?.takeIf { it.isNotBlank() } ?: bookUrl,
            coverUrl = (book.text("coverUrl") ?: book.text("customCoverUrl"))?.takeIf { it.isNotBlank() },
            completed = book.text("kind")?.contains("完结") == true,
            chapterIndex = book.number("durChapterIndex")?.toInt() ?: 0,
            readAt = book.number("durChapterTime") ?: 0L,
        )
    }

    private fun array(text: String, fileName: String): List<JsonElement> =
        json.parseToJsonElement(text.removePrefix("\uFEFF")).let { element ->
            element as? JsonArray ?: throw IllegalArgumentException("$fileName 必须是 JSON 数组")
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}
