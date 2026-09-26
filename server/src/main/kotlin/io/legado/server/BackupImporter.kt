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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
class BackupImporter(
    private val database: Database,
    private val coverCache: CoverCache? = null,
) {
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
        // 备份包只带封面 URL、不带图片本体，落库后 cover_key 为空。
        // 这里后台把封面抓成本地副本，否则书架封面会完全依赖外部图床
        // （图床挂了 / 离线阅读时就只剩文字占位符）。
        backfillCovers(shelf)
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

    /**
     * 把书架条目的封面 URL 抓成本地缓存副本并回写 `cover_key`。
     *
     * 设计取舍：
     * - **并发 + 上限**：整架书可能上千本，串行抓取会让导入请求长时间挂住，
     *   因此用固定线程池并发，且总量封顶，超出的留给后续按需加载时再补。
     * - **失败静默**：单张封面失败（图床 404/超时/防盗链）绝不能影响整次导入，
     *   前端此时会回退到 `coverUrl` 直连（见 resolveShelfCover）。
     */
    private fun backfillCovers(entries: List<BackupShelfEntry>) {
        val cache = coverCache ?: return
        val targets = entries
            .filter { !it.coverUrl.isNullOrBlank() }
            .distinctBy { "${it.sourceId}\u0000${it.bookUrl}" }
            .take(MAX_COVER_BACKFILL)
        if (targets.isEmpty()) return
        val pool = Executors.newFixedThreadPool(COVER_FETCH_CONCURRENCY)
        try {
            targets.map { entry ->
                pool.submit {
                    val url = entry.coverUrl ?: return@submit
                    // 已缓存过就跳过，避免重复下载同一张图。
                    val cached = runCatching { cache.getIfCached(url) ?: cache.cache(url) }.getOrNull()
                        ?: return@submit
                    runCatching {
                        database.updateBookshelfCover(entry.sourceId, entry.bookUrl, cached.key, cached.contentType)
                    }
                }
            }.forEach { runCatching { it.get(COVER_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
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
        /** 单次导入最多补抓的封面数，避免大书架把导入请求拖到超时。 */
        const val MAX_COVER_BACKFILL = 300
        const val COVER_FETCH_CONCURRENCY = 6
        const val COVER_FETCH_TIMEOUT_SECONDS = 15L
    }
}
