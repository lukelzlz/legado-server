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
 * **书源分组**（Legado 的 `bookSourceGroup`）没有独立条目，它是书源自带的字段，
 * 因此随 `bookSource.json` 一起落库（见 [Database.importSources]）——不需要也不该另建分组文件。
 * `bookGroup.json` 是**书架**分组，与书源分组是两件事（见 [Database.importBookGroups]）。
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
        val parsedGroups = readSection(zip, entries, "bookgroup.json")?.let(::parseGroups).orEmpty()
        val parsedShelf = readSection(zip, entries, "bookshelf.json")?.let { parseShelf(it, parsedGroups) }.orEmpty()
        val parsedBookmarks = readSection(zip, entries, "bookmark.json")?.let(::parseBookmarks).orEmpty()
        require(sources.isNotEmpty() || rules.isNotEmpty() || parsedShelf.isNotEmpty()) {
            "不是 Legado 备份包：未找到 bookSource.json / replaceRule.json / bookshelf.json"
        }

        // ------------------------------------------------------------------
        // 过滤：本地图书 与 音频（听书）在服务端**没有可用能力**，不予导入。
        //
        // - 本地图书：`bookUrl` 是 Android SAF 的 `content://` URI（或 `file://` / `webDav::`），
        //   指向**手机本机**的文件，服务端根本无法读取（表现为章节 0、正文空）。
        // - 音频（听书）：内容源是 TTS 音频流，服务端只做文本阅读，导入后同样点不开。
        //
        // 判定严格遵循 `te/分类判别方法.md` 的规范（见 ShelfKind）：
        // 先判本地图书、再判 tab，且**不用扩展名/type 单独判定**。
        // ------------------------------------------------------------------
        val shelf = parsedShelf.filter { it.kind == ShelfKind.ONLINE }
        val skippedLocal = parsedShelf.count { it.kind == ShelfKind.LOCAL }
        val skippedAudio = parsedShelf.count { it.kind == ShelfKind.AUDIO }

        // 分组：只导入「有书的分组」。
        // Legado 内置的智能分组（groupId 为负：在读/未读/已读/小说/漫画/全部/本地/音频…）
        // 是按条件动态筛选的虚拟分组，实测在真实备份里都是空的，导入只会得到一堆空分组。
        val usedGroupNames = shelf.mapNotNull { it.groupName?.takeIf { n -> n.isNotBlank() } }.toSet()
        val groups = parsedGroups.filter { it.groupName in usedGroupNames }

        // 书签：只导入「书还在书架上」的。
        // 实测真实备份 47 条里 29 条挂在被过滤的书上（本地图书/音频），那些书不在书架上，
        // 书签也就没有展示位置。
        val shelfKeys = shelf.map { "${it.name}\u0000${it.author.orEmpty()}" }.toSet()
        val bookmarks = parsedBookmarks.filter { "${it.bookName}\u0000${it.bookAuthor.orEmpty()}" in shelfKeys }
        val bookmarksSkipped = parsedBookmarks.size - bookmarks.size

        // 备份导入是**唯一**会采用书源自带分组（`bookSourceGroup`）的入口：
        // 那是用户在手机端整理好的成果，必须原样带过来。
        val sourceResult = database.importSources(sources, applyGroups = true)
        val ruleResult = database.importReplaceRules(rules)
        val library = database.importLibrary(shelf)
        // 分组必须在书架导入**之后**执行：它要把 book_shelf.group_name 补上对应分组名。
        database.importBookGroups(groups, shelf)
        val bookmarksImported = database.importBookmarks(bookmarks)
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
            skippedLocal = skippedLocal,
            skippedAudio = skippedAudio,
            bookmarks = bookmarksImported,
            bookmarksSkipped = bookmarksSkipped,
            // 书源分组没有独立条目：它是书源自带的 `bookSourceGroup`，随书源一起落库，
            // 这里只回报「带进来几个分组」（见 Database.importSources）。
            sourceGroups = sourceResult.sourceGroups,
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

    /**
     * 解析 `bookGroup.json`。
     *
     * 真实字段：`bookSort, enableRefresh, groupId, groupName, order, show`。
     * `groupId` 为负表示 Legado 内置的智能分组（[BackupGroupEntry.builtIn]）。
     */
    private fun parseGroups(text: String): List<BackupGroupEntry> =
        array(text, "bookGroup.json").mapNotNull { element ->
            val group = element as? JsonObject ?: return@mapNotNull null
            val id = group.number("groupId") ?: return@mapNotNull null
            val name = group.text("groupName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BackupGroupEntry(
                groupId = id,
                groupName = name,
                order = group.number("order")?.toInt() ?: 0,
                builtIn = id < 0,
            )
        }

    /**
     * 解析 `bookmark.json`（书签 / 阅读记录）。
     *
     * 真实字段：`bookAuthor, bookName, bookText, chapterIndex, chapterName, chapterPos, content, time`。
     */
    private fun parseBookmarks(text: String): List<BackupBookmarkEntry> =
        array(text, "bookmark.json").mapNotNull { element ->
            val mark = element as? JsonObject ?: return@mapNotNull null
            val bookName = mark.text("bookName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BackupBookmarkEntry(
                bookName = bookName,
                bookAuthor = mark.text("bookAuthor")?.takeIf { it.isNotBlank() },
                chapterIndex = mark.number("chapterIndex")?.toInt() ?: 0,
                chapterName = mark.text("chapterName")?.takeIf { it.isNotBlank() },
                chapterPos = mark.number("chapterPos")?.toInt() ?: 0,
                bookText = mark.text("bookText"),
                content = mark.text("content"),
                time = mark.number("time") ?: 0L,
            )
        }

    /**
     * 解析 `bookshelf.json`。
     *
     * @param groups 已解析的 `bookGroup.json`，用于把书籍的**数字 `group` id** 解析成分组名
     *   （本服务的 `book_group` / `book_shelf.group_name` 是**按名字**关联的）。
     *   传空列表时所有书籍的 `groupName` 都是 null（等价于未分组）。
     */
    private fun parseShelf(
        text: String,
        groups: List<BackupGroupEntry> = emptyList(),
    ): List<BackupShelfEntry> {
        val nameById = groups.associate { it.groupId to it.groupName }
        return array(text, "bookshelf.json").mapNotNull { element ->
            val book = element as? JsonObject ?: return@mapNotNull null
            val origin = book.text("origin")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bookUrl = book.text("bookUrl")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sourceId = SourceCodec.normalizeSourceId(origin)
            if (sourceId.isBlank()) return@mapNotNull null
            // group = 0 表示未分组；负数/正数都要查表换成名字
            val groupId = book.number("group")?.takeIf { it != 0L }
            BackupShelfEntry(
                sourceId = sourceId,
                bookUrl = bookUrl,
                name = book.text("name")?.takeIf { it.isNotBlank() } ?: bookUrl,
                author = book.text("author")?.takeIf { it.isNotBlank() },
                tocUrl = book.text("tocUrl")?.takeIf { it.isNotBlank() } ?: bookUrl,
                coverUrl = (book.text("coverUrl") ?: book.text("customCoverUrl"))?.takeIf { it.isNotBlank() },
                // 「已读完」不由导入判定：导入只负责**书籍与阅读进度**，完结与否照导入。
                // 备份里表示连载状态的字段形态极不统一（实测 378 条真实数据里同时存在
                // "都市脑洞,6.2,连载中,番茄" / "连载中,9.3分,都市高武,…" / "已完结,,," / 字段缺失），
                // 书源自己的状态也常常滞后。拿它推「已读完」既不可靠，又会覆盖用户手动标的已读完，
                // 因此这里恒为 false，并且入库时对已有记录**保留原值**（见 Database.importLibrary）。
                completed = false,
                chapterIndex = book.number("durChapterIndex")?.toInt() ?: 0,
                readAt = book.number("durChapterTime") ?: 0L,
                kind = ShelfKind.of(book.text("bookUrl"), book.text("origin"), book.text("type"), book.text("kind")),
                groupName = groupId?.let { nameById[it] },
            )
        }
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
