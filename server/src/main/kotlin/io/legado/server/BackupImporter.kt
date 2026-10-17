package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
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
    /**
     * 书源规则执行器（**仅用于封面回源**：拿详情页里的新 `coverUrl`）。
     *
     * 允许为 null（单测/无规则环境的降级）：此时退化成"只试备份里的 URL"，
     * 导入本身一切照旧 —— 回源是**增强**，绝不能因为它缺席而影响导入。
     */
    private val runner: RuleRunner? = null,
    /** 日志接缝：回源的三态（备份 URL 成功 / 回源成功 / 全部失败）要能看出来，便于排查。 */
    private val logger: (String) -> Unit = {},
    /** 详情页取数接缝（测试用）；缺省走 [fetchBookDetails]（书源规则 + 书籍上下文）。 */
    private val fetchDetails: ((String, String) -> BookDetails?)? = null,
    /**
     * 封面补抓的**后台作用域**：给了就把补抓扔到后台 fire-and-forget（生产路径），
     * 不给就**同步**跑完再返回（单测/需要确定性摘要的场景）。
     *
     * 为什么生产必须后台（用户明确要求「导入完立刻返回，封面在后台继续补」）：
     * 数量上限去掉之后，一个 400 本的备份可能要补几百张封面 + 几百次回源，
     * 同步跑完会让导入接口在浏览器里看起来像卡死几分钟。摘要里改为如实回报
     * 「已受理 N 本，后台补抓中」（`coversQueued`）。
     *
     * 作用域由调用方持有（生产 = Ktor `Application`，随应用停止一起取消）：
     * **进程退出可中断**，重启后由 [CoverSelfHeal] 的常驻巡检接续。
     */
    private val coverBackfillScope: CoroutineScope? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun log(message: String) = runCatching { logger(message) }

    /** 封面补抓的唯一实现（与自愈共用，落盘命名规则只有一份）。 */
    private val backfiller: CoverBackfiller? =
        coverCache?.let { CoverBackfiller(database, it, runner, fetchDetails) }

    /** 最近一次后台补抓的 Job（[awaitCoverBackfill] 与单测用）。 */
    @Volatile
    private var coverBackfillJob: Job? = null

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
        // HTTP TTS（`httpTTS.json`）：字段与备份格式逐字对齐，见 [parseHttpTts]
        // （文件名统一传小写，与 readSection 的匹配口径一致）
        val parsedTts = readSection(zip, entries, "httptts.json")?.let(::parseHttpTts).orEmpty()
        require(sources.isNotEmpty() || rules.isNotEmpty() || parsedShelf.isNotEmpty()) {
            "不是 Legado 备份包：未找到 bookSource.json / replaceRule.json / bookshelf.json"
        }

        // ------------------------------------------------------------------
        // 过滤：**只有本地图书**在服务端没有可用能力，不予导入。
        //
        // - 本地图书：`bookUrl` 是 Android SAF 的 `content://` URI（或 `file://` / `webDav::`），
        //   指向**手机本机**的文件，服务端根本无法读取（表现为章节 0、正文空）。
        // - 音频（听书）：**G0 起不再丢弃**。服务端能解析音频地址、流式缓存字节并按 Range
        //   直出给播放器，落库为 `book_kind='audio'` 走有声书播放器。
        // - 漫画：**漫画链路 P0 起不再按小说处理**，落库为 `book_kind='comic'` 走漫画阅读器
        //   （图片缓存 + 目录抽屉）。按小说入库会进文字阅读器 ⇒ 用户看到的就是"漫画打不开"。
        //
        // 判定严格遵循 `te/分类判别方法.md` 的规范（见 ShelfKind）：
        // 先判本地图书、再判 tab，且**不用扩展名/type 单独判定**。
        // ------------------------------------------------------------------
        val shelf = parsedShelf.filter { it.kind != ShelfKind.LOCAL }
        val skippedLocal = parsedShelf.count { it.kind == ShelfKind.LOCAL }

        // 分组：只导入「有书的分组」。
        // Legado 内置的智能分组（groupId 为负：在读/未读/已读/小说/漫画/全部/本地/音频…）
        // 是按条件动态筛选的虚拟分组，实测在真实备份里都是空的，导入只会得到一堆空分组。
        val usedGroupNames = shelf.mapNotNull { it.groupName?.takeIf { n -> n.isNotBlank() } }.toSet()
        val groups = parsedGroups.filter { it.groupName in usedGroupNames }

        // 书签：只导入「书还在书架上」的。
        // 自 G0/G 漫画放开后，只剩本地图书会被过滤（实测真实备份 47 条书签里 29 条曾挂在
        // 被过滤的书上——音频/漫画那部分现在会正常导入）。
        val shelfKeys = shelf.map { "${it.name}\u0000${it.author.orEmpty()}" }.toSet()
        val bookmarks = parsedBookmarks.filter { "${it.bookName}\u0000${it.bookAuthor.orEmpty()}" in shelfKeys }
        val bookmarksSkipped = parsedBookmarks.size - bookmarks.size

        // 备份导入是**唯一**会采用书源自带分组（`bookSourceGroup`）的入口：
        // 那是用户在手机端整理好的成果，必须原样带过来。
        val sourceResult = database.importSources(sources, applyGroups = true)
        val ruleResult = database.importReplaceRules(rules)
        // HTTP TTS 复用既有导入实现（天然幂等：同 id 覆盖）
        val ttsResult = database.importHttpTts(parsedTts)
        val library = database.importLibrary(shelf)
        // 书源已经在库里了 ⇒ 立刻按**书源自身声明**回填类型（`bookSourceType == 2` ⇒ comic）。
        // 不这么做的话，导入完点开一本漫画会先落到文字阅读器（要等下次重启才被启动期回填修好）。
        database.backfillBookKindFromSources()
        // 分组必须在书架导入**之后**执行：它要把 book_shelf.group_name 补上对应分组名。
        database.importBookGroups(groups, shelf)
        val bookmarksImported = database.importBookmarks(bookmarks)
        // 备份包只带封面 URL、不带图片本体，落库后 cover_key 为空。
        // 这里把封面抓成本地副本，否则书架封面会完全依赖外部图床
        // （图床挂了 / 离线阅读时就只剩文字占位符）。
        // **失败必须如实计数**：备份里的 `coverUrl` 常是带过期签名的图床地址（实测番茄
        // `x-expires` 早已过期 ⇒ 403），静默失败会让用户只看到"封面全裂"却不知道原因。
        //
        // ⚠️ **不限数量、不阻塞导入**（用户明确要求「导入时优先全补上，不限数量上限」）：
        // 有 [coverBackfillScope] 时这里只是把全部待补书**受理**下来（`coversQueued`），
        // 真正的下载/回源在后台继续跑；进程退出可中断，重启后由常驻的 `CoverSelfHeal` 接续。
        val covers = startCoverBackfill(shelf)
        BackupImportSummary(
            sources = sourceResult.imported,
            sourcesUpdated = sourceResult.updated,
            rules = ruleResult.imported,
            rulesUpdated = ruleResult.updated,
            books = library.imported,
            booksUpdated = library.updated,
            progress = library.progress,
            skippedLocal = skippedLocal,
            coversFetched = covers.fetched,
            coversFailed = covers.failed,
            coversResourced = covers.resourced,
            coversQueued = covers.queued,
            bookmarks = bookmarksImported,
            bookmarksSkipped = bookmarksSkipped,
            // 书源分组没有独立条目：它是书源自带的 `bookSourceGroup`，随书源一起落库，
            // 这里只回报「带进来几个分组」（见 Database.importSources）。
            sourceGroups = sourceResult.sourceGroups,
        )
    }

    /**
     * 受理/执行一次封面补抓。
     *
     * - **有** [coverBackfillScope]：立刻返回「已受理 N 本」，补抓在后台协程里继续
     *   （fire-and-forget，随作用域取消；进程退出可中断）；
     * - **没有**：同步跑完再返回（单测与需要确定性摘要的场景）。
     *
     * 待补书目**不设数量上限**（资源上限仍在 [CoverBackfillRunner] 里：并发
     * [CoverBackfillRunner.CONCURRENCY]、单本 [CoverBackfillRunner.TIMEOUT_SECONDS]、
     * 单本候选 [CoverBackfiller] 内部的 4 条 —— 那些是防止拖垮服务的，不是数量配额）。
     */
    private fun startCoverBackfill(entries: List<BackupShelfEntry>): CoverBackfill {
        val worker = backfiller ?: return CoverBackfill()
        val targets = entries
            .filter { !it.coverUrl.isNullOrBlank() || !it.bookUrl.isBlank() }
            .distinctBy { "${it.sourceId}\u0000${it.bookUrl}" }
        if (targets.isEmpty()) return CoverBackfill()
        val scope = coverBackfillScope ?: return runCoverBackfill(worker, targets) { true }
        val job = scope.launch(Dispatchers.IO) {
            // `isActive` 让作用域被取消后不再开新的一本（阻塞式网络 I/O 本身不可中断，
            // 因此在书与书之间收手）。
            runCoverBackfill(worker, targets) { isActive }
        }
        coverBackfillJob = job
        log("backup cover queued: ${targets.size} books, backfill continues in background")
        return CoverBackfill(queued = targets.size)
    }

    /**
     * 等待后台补抓结束（**只给单测/运维脚本用**，生产路径绝不调它）。
     *
     * 没有后台任务时立即返回：调用方不必关心当前走的是同步还是后台模式。
     */
    suspend fun awaitCoverBackfill() {
        coverBackfillJob?.join()
    }

    /** 同步/后台共用的那一轮补抓：逐本走三态，任何单本失败只计数、绝不中断整批。 */
    private fun runCoverBackfill(
        worker: CoverBackfiller,
        targets: List<BackupShelfEntry>,
        shouldContinue: () -> Boolean,
    ): CoverBackfill {
        // 计数器跨线程累加 ⇒ 必须原子（普通 Int 会被并发丢失增量，让"失败张数"报少）
        val fetched = AtomicInteger()
        val failed = AtomicInteger()
        val resourced = AtomicInteger()
        CoverBackfillRunner.run(
            items = targets,
            shouldContinue = shouldContinue,
            work = { entry -> backfillOne(worker, entry) },
        ) { entry, result ->
            when (result.outcome) {
                CoverBackfillOutcome.DIRECT -> {
                    fetched.incrementAndGet()
                    log("backup cover ok: ${entry.name}")
                }
                CoverBackfillOutcome.RESOURCED -> {
                    fetched.incrementAndGet()
                    resourced.incrementAndGet()
                    log("backup cover re-sourced: ${entry.name}")
                }
                CoverBackfillOutcome.FAILED -> {
                    failed.incrementAndGet()
                    log("backup cover failed (backup url + re-source both failed): ${entry.name}${result.note?.let { " [$it]" } ?: ""}")
                }
            }
        }
        log("backup cover summary: fetched=${fetched.get()} resourced=${resourced.get()} failed=${failed.get()} targets=${targets.size}")
        return CoverBackfill(fetched.get(), failed.get(), resourced.get())
    }

    /**
     * 单本补抓：用**备份条目**的身份去落库（`sourceId`/`bookUrl` 以条目为准）。
     *
     * 为什么条目身份优先于 `getShelfBookByUrl(bookUrl)` 查出来的那一行：`book_shelf` 主键是
     * `(source_id, book_url)`，同一个 `bookUrl` 可能被两个书源共用 ⇒ 只按 `bookUrl` 查会
     * 拿到"另一条"。封面必须写回**这本书自己那一行**，否则会出现「A 书的封面贴到 B 书」
     * 的静默串号。候选源（`alternateSources`）仍从与条目同源的那一行取（有就带上）。
     */
    private fun backfillOne(worker: CoverBackfiller, entry: BackupShelfEntry): CoverBackfillResult {
        val shelf = runCatching { database.getShelfBookByUrl(entry.bookUrl) }.getOrNull()
        // 只有「同源那一行」的信息（候选源/最近阅读）才可以借用，别的源的绝不能混进来。
        val own = shelf?.takeIf { it.sourceId == entry.sourceId }
        val item = BookshelfItem(
            sourceId = entry.sourceId,
            bookUrl = entry.bookUrl,
            name = entry.name,
            author = entry.author,
            tocUrl = entry.tocUrl,
            coverUrl = entry.coverUrl,
            lastReadAt = own?.lastReadAt ?: 0L,
            alternateSources = own?.alternateSources.orEmpty(),
        )
        return runCatching { worker.backfill(item, preferredUrl = entry.coverUrl) }
            .getOrElse { CoverBackfillResult.failed(it.message) }
    }

    /** 封面补抓结果（如实回报给用户，见 [BackupImportSummary.coversFailed] / [BackupImportSummary.coversQueued]）。 */
    private data class CoverBackfill(
        val fetched: Int = 0,
        val failed: Int = 0,
        val resourced: Int = 0,
        /** 已受理、转入后台补抓的本数（同步模式下恒为 0）。 */
        val queued: Int = 0,
    )

    private fun readSection(zip: ZipFile, entries: List<ZipEntry>, fileName: String): String? {
        // ⚠️ 比较必须**两侧都转小写**：包内条目大小写与调用方传参不一致（真实备份是
        // `httpTTS.json` / `bookSource.json`，而调用方习惯写小写），只把条目名转小写、
        // 拿 `fileName` 原样比，就会静默返回 null —— 表现为「这一整段没导入」且毫无报错。
        // 我加 httpTTS 时正是踩了这个：传 `"httpTTS.json"` ⇒ `"httptts.json" == "httpTTS.json"` 为假
        // ⇒ TTS 一个都没导（实测库里是 []）。
        val wanted = fileName.lowercase()
        val entry = entries.firstOrNull { it.name.substringAfterLast('/').lowercase() == wanted } ?: return null
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
            // 类别只判一次，过滤（本地图书）与落库类型（audio/comic/novel）共用同一个结果，
            // 绝不允许两处各判一遍 —— 判据一旦分叉就会出现「这本被当成小说导入」的静默误判。
            val kind = ShelfKind.of(book.text("bookUrl"), book.text("origin"), book.text("type"), book.text("kind"))
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
                // 只有有声书会用它（音频的 durChapterPos 就是毫秒）；文本/漫画刻意不用，见 BackupShelfEntry
                chapterPos = book.number("durChapterPos")?.toInt() ?: 0,
                kind = kind,
                // 落库类型**由类别显式映射**：听书 ⇒ audio、漫画 ⇒ comic。
                // 漫画若按小说入库会直接进文字阅读器（用户视角就是"打不开"），所以这里
                // 绝不能只写一个 null 交给兜底。
                //
                // 其余（在线小说）仍只看**书自己的说法**：`tab` 没说就返回 null ⇒ **不写这一列**
                // （已有行保留原值 = "已经和小说一样就跳过"）。
                //
                // ⚠️ 刻意**不**在这里读源声明：聚合源（如大灰狼）根本不声明 `bookSourceType`，
                // 只认源声明会把里面的漫画全部判成小说（用户实测反馈）。
                // 源声明作为**兜底**由导入结束后的 backfillBookKindFromSources() 统一处理。
                bookKind = when (kind) {
                    ShelfKind.AUDIO -> BookKind.AUDIO.wire
                    ShelfKind.COMIC -> BookKind.COMIC.wire
                    // 本地图书会被过滤掉，这里给它一个确定值只为类型完整（不会落库）
                    ShelfKind.LOCAL -> null
                    ShelfKind.ONLINE -> BookKind.resolveOrNull(null, book.text("bookUrl"), null)
                },
                groupName = groupId?.let { nameById[it] },
                // 书籍变量（Legado `Book.variable`，JSON 对象字符串）：听书音色/朗读者就存在这里，
                // 不导入它，用户换设备后音色设置就丢了（导入侧见 `Database.importLibrary` 的 upsert）。
                variable = book.text("variable")?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }

    /**
     * 解析 `httpTTS.json`（真实备份 `backup2026-09-30-PEPM00.zip` 实测字段）：
     * `concurrentRate / contentType / enabledCookieJar / header / id / lastUpdateTime /
     *  loginCheckJs / loginUi / loginUrl / name / url`（本服务另有 `jsLib`，缺失时留空）。
     *
     * 空字符串一律按「未设置」处理（手机端就是用 `""` 表示没有），避免把空串写进库。
     */
    private fun parseHttpTts(text: String): List<HttpTts> = array(text, "httpTTS.json").mapNotNull { element ->
        val tts = element as? JsonObject ?: return@mapNotNull null
        val url = tts.text("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        HttpTts(
            id = tts.number("id") ?: 0L,
            name = tts.text("name")?.takeIf { it.isNotBlank() } ?: "未命名",
            url = url,
            header = tts.text("header")?.takeIf { it.isNotBlank() },
            contentType = tts.text("contentType")?.takeIf { it.isNotBlank() },
            concurrentRate = tts.text("concurrentRate")?.takeIf { it.isNotBlank() },
            loginUrl = tts.text("loginUrl")?.takeIf { it.isNotBlank() },
            loginCheckJs = tts.text("loginCheckJs")?.takeIf { it.isNotBlank() },
            loginUi = tts.text("loginUi")?.takeIf { it.isNotBlank() },
            jsLib = tts.text("jsLib")?.takeIf { it.isNotBlank() },
            enabledCookieJar = tts.flag("enabledCookieJar") ?: false,
            lastUpdateTime = tts.number("lastUpdateTime") ?: 0L,
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
        // ⚠️ 这里**刻意没有任何补抓数量上限**：用户明确要求「导入时优先全补上，不限数量上限」。
        // 曾经存在的 `MAX_COVER_BACKFILL = 300`（补抓总数）与 `MAX_COVER_RESOURCE = 60`（回源次数）
        // 会让大批量导入提前耗尽预算（实测真实 220 本备份：备份 URL 成功 3、回源成功 59、仍失败 153，
        // 其中 60 次预算正是回源被截断的原因）。
        // **资源上限**（并发 / 单本超时 / 单本候选数）保留在 [CoverBackfillRunner] 与 [CoverBackfiller]：
        // 那些是防止拖垮服务的，不是数量配额。
    }
}
