package io.legado.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore

/**
 * 「书签」页的封面链路：**按书名 + 作者**给一本书找一张本地封面副本。
 *
 * ## 用户要求（2026-11-01）
 *
 * > 「如果在线的书没有封面那就根据书名作者去书源请求，导入时请求，如果超过五次没找到就标记，
 * > 在书签页批量管理旁边添加按钮请求封面，在这个页面点击封面就是手动设置和上传图片，
 * > 在删除完书签后，封面文件同步删除，在书架删除书时不删除书签，
 * > 只要书架或者书签任何一个或两者存在，那书籍的封面文件就保留」
 *
 * ## 两层来源
 *
 * 1. **书架里有这本书**（绝大多数情况）：直接用它自己的 `sourceId + bookUrl + alternateSources`
 *    走既有的 [CoverBackfiller]（外链 → 本书源详情 → 候选源详情），质量最好、路径最成熟；
 * 2. **只在书签里有这本书**：书签表按书名+作者存，没有 sourceId/bookUrl ⇒ 只能用
 *    `书名 + 作者` 去**已启用书源**里搜一次，取第一条书名匹配的结果作为临时书目再走 [CoverBackfiller]。
 *    搜索是重活，所以**限量**（[MAX_SEARCH_SOURCES] 个源、只用第一条命中）。
 *
 * ## "超过五次没找到就标记"
 *
 * 失败次数记在 `book_cover.missing_attempts`，满 [COVER_MISSING_LIMIT] 次后
 * [BookmarkBook.coverMissing] 为真（界面打标记），后台/批量请求默认**跳过**它；
 * 用户手动点「请求封面」（`force = true`）仍然可以再试一次。
 *
 * ## 与封面文件生命周期的关系
 *
 * 本服务只负责"给书挂封面"，**不负责删**。删除时机在：
 * - 移出书架：`Database.removeBookshelf` 的孤儿判定（书架/书签/封面登记三处都不再引用才删）；
 * - 删光某本书的书签：`Routes` 里调 [purgeIfUnused]。
 */
class BookmarkCoverService(
    private val database: Database,
    private val coverCache: CoverCache,
    private val runner: RuleRunner? = null,
    /**
     * 搜源接缝（测试用）：给定关键字返回候选书目。
     *
     * 缺省走「扫已启用书源 + `RuleRunner.search`」；单测里塞一个假实现即可验证
     * 「**只在书签里的书**也能补到封面」这条路径（审查指出旧实现根本走不通）。
     */
    private val searchBooks: ((String) -> List<SearchResult>)? = null,
) {
    sealed interface Outcome {
        /** 抓到了，[coverKey] 已落盘并登记。 */
        data class Fetched(val coverKey: String) : Outcome
        /** 尝试了但没找到。 */
        data class Failed(val attempts: Int, val marked: Boolean) : Outcome
        /** 已标记放弃（`missing_attempts >= 5`）而跳过。 */
        data object Skipped : Outcome
    }

    private val backfiller = CoverBackfiller(database, coverCache, runner)

    /**
     * 给一本书找封面。
     *
     * @param force `true` 时忽略"已标记放弃"的限制（用户手动触发），并把失败计数继续累加。
     */
    suspend fun resolve(bookName: String, bookAuthor: String?, force: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        val name = bookName.trim()
        if (name.isEmpty()) return@withContext Outcome.Failed(0, false)
        val existing = database.getBookCover(name, bookAuthor)
        if (!force && existing?.exhausted == true) return@withContext Outcome.Skipped

        // ① 书架里有这本书 ⇒ 用它自己的书目（含候选源）走成熟链路
        val shelfBook = findShelfBook(name, bookAuthor)
        if (shelfBook != null) {
            val result = backfiller.backfill(shelfBook)
            result.cacheKey?.let { key ->
                database.saveBookCover(name, bookAuthor, key)
                return@withContext Outcome.Fetched(key)
            }
            return@withContext recordMiss(name, bookAuthor, existing)
        }

        // ② 只在书签里有 ⇒ 书名 + 作者去书源搜一次，拿到候选书目后**直接下载**封面。
        //
        // ⚠️ 这条路径不能走 [CoverBackfiller]：它最终调 `materializeCover`，而后者要求
        // `updateBookshelfCover` 成功（书必须在书架上）⇒ 非书架书恒返回 null（审查实测复现：
        // 每次都记一次 miss、满 5 次被标成无封面，而下载下来的图还留在磁盘上没人引用）。
        // 这里改成 coverCache 直下 + recordCoverCache + saveBookCover。
        val found = searchBookByName(name, bookAuthor)
        if (found != null) {
            val detailsCover = if (found.coverUrl.isNullOrBlank() && runner != null) {
                runCatching { fetchBookDetails(database, runner, found.sourceId, found.bookUrl) }.getOrNull()?.coverUrl
            } else null
            val url = detailsCover?.trim()?.takeIf { it.isNotEmpty() } ?: found.coverUrl?.trim()
            if (!url.isNullOrEmpty()) {
                val key = downloadCover(url, found.sourceId)
                if (key != null) {
                    database.saveBookCover(name, bookAuthor, key)
                    return@withContext Outcome.Fetched(key)
                }
            }
        }
        recordMiss(name, bookAuthor, existing)
    }

    /** 服务端代下一张封面并落盘（带该书源请求头），返回缓存 key。失败静默 null。 */
    private fun downloadCover(url: String, sourceId: String?): String? = runCatching {
        val cached = coverCache.cache(url, headers = headersOf(sourceId))
        database.recordCoverCache(cached.key, cached.contentType)
        cached.key
    }.getOrNull()

    /** 该书源自带的请求头（`header` + 已落库 Cookie）；拿不到就空表。 */
    private fun headersOf(sourceId: String?): Map<String, String> {
        if (sourceId.isNullOrBlank()) return emptyMap()
        val ruleRunner = runner ?: return emptyMap()
        return runCatching {
            database.getSource(sourceId)?.let { ruleRunner.mediaHeaders(it.json, sourceId) }
        }.getOrNull().orEmpty()
    }

    /**
     * 手动上传/指定封面：写登记表 + 同步到该书所有书架行（书架上也要显示）。
     *
     * 换/删封面时**先把旧 key 记下来**，改完之后再逐个判定：没人引用就删文件
     * （审查指出：只覆盖不回收会让旧图永远留在磁盘上）。
     */
    fun attach(bookName: String, bookAuthor: String?, coverKey: String?): BookCoverState {
        val name = bookName.trim()
        val oldKeys = (listOfNotNull(database.getBookCover(name, bookAuthor)?.coverKey) + database.shelfCoverKeys(name, bookAuthor))
            .distinct()
        if (coverKey.isNullOrBlank()) {
            // 「移除封面」：登记表与书架行都要清（书在架上时封面权威在 book_shelf）
            database.clearBookCover(name, bookAuthor)
            database.clearCoverOnShelfRows(name, bookAuthor)
            reclaimCoverKeys(oldKeys)
            return stateOf(name, bookAuthor)
        }
        database.saveBookCover(name, bookAuthor, coverKey)
        database.attachCoverToShelfRows(name, bookAuthor, coverKey)
        reclaimCoverKeys(oldKeys, keep = coverKey)
        return stateOf(name, bookAuthor)
    }

    /** 回收一批旧封面 key：跳过 [keep]，且只在**再没有任何书/登记引用**时才删文件。 */
    private fun reclaimCoverKeys(keys: Collection<String>, keep: String? = null) {
        keys.asSequence()
            .filter { it.isNotBlank() && it != keep }
            .distinct()
            .forEach { key -> if (!database.isCoverKeyReferenced(key)) runCatching { coverCache.delete(key) } }
    }

    /**
     * 手动挂一张**书源给的外链封面**：服务端代下（带该书源请求头）后落盘，再登记。
     *
     * 为什么不在浏览器里直接 `<img>` + 回传：候选封面常在防盗链/跨域后面，
     * 服务端下载才有书源 header 与 Cookie；另外落盘必须走 [CoverCache] 的魔数校验。
     */
    suspend fun attachFromUrl(bookName: String, bookAuthor: String?, coverUrl: String, sourceId: String? = null): BookCoverState =
        withContext(Dispatchers.IO) {
            val name = bookName.trim()
            val oldKeys = (listOfNotNull(database.getBookCover(name, bookAuthor)?.coverKey) + database.shelfCoverKeys(name, bookAuthor))
                .distinct()
            val cached = runCatching { coverCache.cache(coverUrl, headers = headersOf(sourceId)) }.getOrNull()
            if (cached == null) return@withContext stateOf(name, bookAuthor)
            database.recordCoverCache(cached.key, cached.contentType)
            database.saveBookCover(name, bookAuthor, cached.key)
            database.attachCoverToShelfRows(name, bookAuthor, cached.key)
            reclaimCoverKeys(oldKeys, keep = cached.key)
            stateOf(name, bookAuthor)
        }

    /**
     * 给「手动设置封面」用的候选列表：按书名+作者去已启用书源搜，取**带封面的**前几条。
     *
     * 与 [resolve] 的区别：这里只**列候选**给用户挑，不写库（用户点谁才写谁）。
     */
    suspend fun candidates(bookName: String, bookAuthor: String?): List<BookCoverCandidate> = withContext(Dispatchers.IO) {
        val ruleRunner = runner ?: return@withContext emptyList()
        val keyword = listOfNotNull(bookName.trim(), bookAuthor?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ")
        val sources = runCatching { database.listSearchSourceRecords(null, null) }.getOrNull().orEmpty()
        val out = LinkedHashMap<String, BookCoverCandidate>()
        var tried = 0
        for (source in sources) {
            if (tried >= MAX_SEARCH_SOURCES || out.size >= MAX_CANDIDATES) break
            tried++
            val hits = runCatching { ruleRunner.search(source.json, keyword) }.getOrNull().orEmpty()
            hits.asSequence()
                .filter { sameBookName(it.name, bookName) }
                .forEach { hit ->
                    val url = hit.coverUrl?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return@forEach
                    val key = hit.bookUrl
                    if (!out.containsKey(key) && out.size < MAX_CANDIDATES) {
                        out[key] = BookCoverCandidate(
                            sourceId = hit.sourceId,
                            bookUrl = hit.bookUrl,
                            name = hit.name,
                            author = hit.author,
                            coverUrl = url,
                        )
                    }
                }
        }
        out.values.toList()
    }


    fun stateOf(bookName: String, bookAuthor: String?): BookCoverState {
        val row = database.getBookCover(bookName, bookAuthor)
        val shelfCover = database.listBookshelf()
            .firstOrNull { it.name == bookName && it.author.orEmpty() == bookAuthor?.trim().orEmpty() }
            ?.coverKey
        return BookCoverState(
            bookName = bookName,
            bookAuthor = bookAuthor,
            coverKey = shelfCover ?: row?.coverKey,
            coverMissing = row?.exhausted == true,
            coverAttempts = row?.missingAttempts ?: 0,
        )
    }

    /**
     * 删光某本书的书签之后调用：**书架里也没有它**才把这个封面文件删掉。
     *
     * 用户要求：「在删除完书签后，封面文件同步删除」「只要书架或者书签任何一个或两者存在，
     * 那书籍的封面文件就保留」。
     */
    fun purgeIfUnused(bookName: String, bookAuthor: String?) {
        if (database.hasShelfBookFor(bookName, bookAuthor)) return
        if (database.hasBookmarksFor(bookName, bookAuthor)) return
        val row = database.getBookCover(bookName, bookAuthor)
        database.clearBookCover(bookName, bookAuthor)
        val key = row?.coverKey ?: return
        // 别的书（书架行或封面登记）也引用着同一张图 ⇒ 不能删（封面按内容哈希共享）
        if (database.isCoverKeyReferenced(key)) return
        runCatching { coverCache.delete(key) }
    }

    private fun recordMiss(bookName: String, bookAuthor: String?, existing: Database.BookCoverRow?): Outcome {
        val attempts = database.recordCoverMiss(bookName, bookAuthor)
        val marked = attempts >= COVER_MISSING_LIMIT
        return Outcome.Failed(attempts, marked || existing?.exhausted == true)
    }

    private fun findShelfBook(bookName: String, bookAuthor: String?): BookshelfItem? {
        val author = bookAuthor?.trim().orEmpty()
        val shelf = database.listBookshelf()
        return shelf.firstOrNull { it.name == bookName && it.author.orEmpty().trim() == author }
            ?: shelf.firstOrNull { it.name == bookName } // 作者缺失/不一致时退一步：同名即可
    }

    /** 书名 + 作者去已启用书源搜一次（限量），返回第一条书名匹配的临时书目。 */
    private fun searchBookByName(bookName: String, bookAuthor: String?): BookshelfItem? {
        val keyword = listOfNotNull(bookName, bookAuthor?.takeIf { it.isNotBlank() }).joinToString(" ")
        val hits: List<SearchResult> = searchBooks?.let { search ->
            runCatching { search(keyword) }.getOrNull().orEmpty()
        } ?: run {
            val ruleRunner = runner ?: return null
            val sources = runCatching { database.listSearchSourceRecords(null, null) }.getOrNull().orEmpty()
            var tried = 0
            for (source in sources) {
                if (tried >= MAX_SEARCH_SOURCES) break
                tried++
                val found = runCatching { ruleRunner.search(source.json, keyword) }.getOrNull().orEmpty()
                if (found.any { sameBookName(it.name, bookName) }) return@run found
            }
            emptyList()
        }
        val match = hits.firstOrNull { sameBookName(it.name, bookName) } ?: return null
        return BookshelfItem(
            sourceId = match.sourceId,
            bookUrl = match.bookUrl,
            name = match.name,
            author = match.author ?: bookAuthor,
            tocUrl = match.bookUrl,
            coverUrl = match.coverUrl,
            lastReadAt = 0L,
            bookKind = match.bookKind ?: "novel",
        )
    }

    private fun sameBookName(candidate: String, wanted: String): Boolean =
        candidate.trim().equals(wanted.trim(), ignoreCase = true) ||
            candidate.replace("《", "").replace("》", "").trim() == wanted.replace("《", "").replace("》", "").trim()

    companion object {
        /** 书名搜索最多试几个书源（搜索是重活，尽量小）。 */
        private const val MAX_SEARCH_SOURCES = 3

        /** 「手动设置封面」最多列几条候选。 */
        private const val MAX_CANDIDATES = 12

        /** 批量请求封面时的并发上限（别把书源打爆）。 */
        val BATCH_SEMAPHORE = Semaphore(4)
    }
}

/** 连续抓不到封面的上限：满 5 次就标记"放弃自动补抓"（用户要求「超过五次没找到就标记」）。 */
const val COVER_MISSING_LIMIT = 5
