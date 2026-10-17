package io.legado.server

import java.nio.file.Files
import java.nio.file.Path

/**
 * 一章插图在索引里的一行（`illustration_cache`）。
 *
 * 与 `MangaImageRecord` 的形状基本一致，但**刻意分开两张表**：
 * 漫画的清单是「一整章就是这些图」（`manga_chapter_cache.images_json`），
 * 而小说插图是**正文 HTML 的一部分**、由正文顺序决定序号 —— 两套语义混在一张表里，
 * 任何一方换个字段口径就会污染另一方（见 ADR-033）。
 */
data class IllustrationRecord(
    /** `sha256(sourceId + '\n' + 上游URL)`：与漫画/音频同一 hash 口径，含 sourceId 防跨源脏命中。 */
    val urlHash: String,
    val sourceId: String,
    val bookUrl: String,
    val chapterUrl: String,
    /** 仅服务端可见（**绝不下发前端**，否则前端会绕过代理直连：缺 Referer 必裂图 + 泄露用户 IP）。 */
    val upstreamUrl: String,
    val chapterIndex: Int = 0,
    /** 一章内第几张（正文里的出现顺序，**首次登记后永不改** ⇒ 与 `file_path` 的单射关系不会被打破）。 */
    val segmentIndex: Int = 0,
    val contentType: String = "",
    val byteSize: Long = 0,
    /** `sha256(字节)`：ETag 与 immutable 的依据（内容变了它必变）。 */
    val cacheKey: String = "",
    /** pending | ready */
    val state: String = "pending",
    val createdAt: Long = 0,
    val lastAccessAt: Long = 0,
    /** 落盘相对路径；只是快路径，失效时按「两个短哈希 + 8 位序号」重新定位（见 [IllustrationCache.fileFor]）。 */
    val filePath: String = "",
)

/**
 * 小说正文插图的**字节缓存**（`<data>/images/`）。
 *
 * ## 落盘布局（沿用 v3 口径，与漫画完全一致，只是根目录不同）
 * `images/<书名 (20位书哈希)>/<章节名 (20位章节哈希)>/<8位序号>.<真实扩展名>`
 * —— 中文名只给人看，**读取只认两个短哈希 + 序号**（`AtomicFileStore.findByNameFree`），
 * 因此用户改目录名、上游改扩展名都不会裂图；哈希标记没了就判「缺失」并重下，
 * **绝不猜一个文件塞给用户**（错图比裂图难查得多）。
 *
 * ## 为什么序号必须由「正文顺序」定死而不是随手递增
 * 序号是文件名的一部分，也是无中文定位的唯一线索。若同一章两张图被分到同一个序号，
 * 后写的会覆盖先写的 ⇒ 读者看到的是**另一张图**（对错图）。因此 [ensureRow] 在分配新序号时
 * 走 `synchronized` 闸门 + 读库取 `max(segment_index)+1`，而**已登记的序号绝不复用**。
 */
class IllustrationCache(
    directory: Path,
    private val database: Database,
    private val log: (String) -> Unit = {},
) {
    private val store = AtomicFileStore(directory)
    private val planner = CachePathPlanner(database)

    /**
     * 序号分配的闸门。
     *
     * 只在**同一章的并发首抓**上竞争：两个请求同时为一个尚未登记的地址申请序号，
     * 若各自「读 max + 1」就会拿到同一个号 ⇒ 互相覆盖成错图。
     */
    private val assigning = Any()

    fun urlHash(sourceId: String, upstreamUrl: String): String =
        ImageBytes.sha256("$sourceId\n${upstreamUrl.trim()}")

    /** 按 key 取原始行（不论状态）。 */
    fun rowByKey(urlHash: String): IllustrationRecord? = database.findIllustrationByUrlHash(urlHash)

    /** 命中检查：只有 `ready` 且**文件确实在**才算命中（否则删行重下，绝不"假装命中"）。 */
    fun getByKey(urlHash: String): IllustrationRecord? {
        val row = database.findIllustrationByUrlHash(urlHash) ?: return null
        if (row.state != "ready") return null
        if (locate(row) == null) {
            log("illustration row without file, dropping: ${row.urlHash}")
            database.deleteIllustrationRow(urlHash)
            return null
        }
        return row
    }

    /**
     * 按正文顺序登记一章的插图（幂等）。
     *
     * 已登记的序号**不会被改写**（`on conflict do nothing`）：正文顺序变了只是新图追加在末尾，
     * 而老图的「序号 ↔ 文件」映射保持不变 —— 这正是「绝不返回错图」的前提。
     *
     * @return 本次请求后该章的完整有序行（含既有与新增）
     */
    fun registerChapter(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        chapterIndex: Int,
        upstreamUrls: List<String>,
    ): List<IllustrationRecord> {
        if (upstreamUrls.isEmpty()) return database.listIllustrationsForChapter(sourceId, bookUrl, chapterUrl)
        val records = upstreamUrls.mapIndexedNotNull { index, url ->
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return@mapIndexedNotNull null
            pendingRecord(sourceId, bookUrl, chapterUrl, chapterIndex, index, trimmed)
        }
        database.insertIllustrationRows(records)
        return database.listIllustrationsForChapter(sourceId, bookUrl, chapterUrl)
    }

    /** 从**已清洗的正文**里登记插图（清洗侧与登记侧共用 `IllustrationMarkup` 的判据）。 */
    fun registerFromContent(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        chapterIndex: Int,
        content: String,
    ): Int {
        val urls = IllustrationMarkup.urlsIn(content)
        if (urls.isEmpty()) return 0
        return registerChapter(sourceId, bookUrl, chapterUrl, chapterIndex, urls).size
    }

    /**
     * 拿一行「待抓取」的登记信息（没有就现登记）。
     *
     * 序号来源优先级：
     * 1. 已登记的行（正常路径：正文取回时已按顺序登记过）；
     * 2. **本章正文里的出现位置**（老缓存、或正文在别处被缓存过）；
     * 3. 都没有 ⇒ 追加到本章末尾（幂等闸门内取 `max+1`）。
     */
    fun ensureRow(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        chapterIndex: Int,
        upstreamUrl: String,
    ): IllustrationRecord {
        val trimmed = upstreamUrl.trim()
        val hash = urlHash(sourceId, trimmed)
        database.findIllustrationByUrlHash(hash)?.let { return it }
        // 正文顺序优先：它决定了「第几张图」的稳定口径
        if (bookUrl.isNotBlank() && chapterUrl.isNotBlank()) {
            val cached = database.cachedContent(sourceId, bookUrl, chapterUrl)
            val urls = cached?.let { IllustrationMarkup.urlsIn(it.content) }.orEmpty()
            if (urls.isNotEmpty()) {
                registerChapter(sourceId, bookUrl, chapterUrl, chapterIndex, urls)
                database.findIllustrationByUrlHash(hash)?.let { return it }
            }
        }
        synchronized(assigning) {
            // 闸门内二次确认：并发进来时别人可能刚刚登记好
            database.findIllustrationByUrlHash(hash)?.let { return it }
            val used = database.listIllustrationsForChapter(sourceId, bookUrl, chapterUrl)
                .map { it.segmentIndex }
                .toSet()
            var next = if (used.isEmpty()) 0 else used.max() + 1
            // 极端情况（历史分配留下的空洞）也要落在真正的空号上，绝不与既有文件撞名
            while (used.contains(next)) next++
            database.insertIllustrationRows(
                listOf(pendingRecord(sourceId, bookUrl, chapterUrl, chapterIndex, next, trimmed)),
            )
            return database.findIllustrationByUrlHash(hash)
                ?: pendingRecord(sourceId, bookUrl, chapterUrl, chapterIndex, next, trimmed)
        }
    }

    private fun pendingRecord(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        chapterIndex: Int,
        segmentIndex: Int,
        upstreamUrl: String,
    ): IllustrationRecord {
        val now = System.currentTimeMillis()
        return IllustrationRecord(
            urlHash = urlHash(sourceId, upstreamUrl),
            sourceId = sourceId,
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            upstreamUrl = upstreamUrl,
            chapterIndex = chapterIndex,
            segmentIndex = segmentIndex,
            createdAt = now,
            lastAccessAt = now,
        )
    }

    /**
     * 落盘一份刚抓到的字节（**先盘后表**：文件已就位才把行置 `ready`）。
     *
     * 内容类型与扩展名以**真实字节**为准（上游会谎报 `Content-Type`，那正是「扩展名 / 类型说谎」
     * 这类缺陷的来源）。
     */
    fun put(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        upstreamUrl: String,
        contentType: String,
        bytes: ByteArray,
        chapterIndex: Int = 0,
        segmentIndex: Int = 0,
    ): IllustrationRecord {
        val body = ImageBytes.decompressIfNeeded(bytes)
        require(body.size <= ImageBytes.MAX_BYTES) { "插图超过单图 ${ImageBytes.MAX_BYTES} 字节上限" }
        require(ImageBytes.looksLikeImage(body)) { "插图内容不是可解码的图片" }
        val actualType = ImageBytes.detectImageContentType(body) ?: contentType.substringBefore(';').ifBlank { "image/jpeg" }
        val hash = urlHash(sourceId, upstreamUrl)
        // 同一地址重抓时复用旧路径（否则每重抓一次就在目录里留一份孤儿）
        val existing = database.findIllustrationByUrlHash(hash)?.filePath?.takeIf { it.isNotBlank() && store.exists(it) }
        val relative = existing
            ?: "${planner.directory(sourceId, bookUrl, chapterUrl, chapterIndex)}/${segmentFileName(segmentIndex, actualType)}"
        store.write(relative, body)
        val record = IllustrationRecord(
            urlHash = hash,
            sourceId = sourceId,
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            upstreamUrl = upstreamUrl.trim(),
            chapterIndex = chapterIndex,
            segmentIndex = segmentIndex,
            contentType = actualType,
            byteSize = body.size.toLong(),
            cacheKey = ImageBytes.sha256Bytes(body),
            state = "ready",
            createdAt = 0,
            lastAccessAt = 0,
            filePath = relative,
        )
        database.recordIllustration(record)
        return record
    }

    /** 定位某行的物理文件：`file_path` 快路径 → 「两个短哈希 + 8 位序号」无中文定位（命中回填快路径）。 */
    fun fileFor(row: IllustrationRecord): Path? = locate(row)

    private fun locate(row: IllustrationRecord): Path? {
        val fast = row.filePath.takeIf { it.isNotBlank() }?.let { store.resolveOrNull(it) }
        if (fast != null && Files.isRegularFile(fast)) return fast
        val found = store.findByNameFree(
            bookCacheHash(row.sourceId, row.bookUrl),
            chapterCacheHash(row.sourceId, row.bookUrl, row.chapterUrl),
            row.segmentIndex,
        ) ?: return null
        store.relativeOf(found)?.let { relative ->
            runCatching { database.updateIllustrationFilePath(row.urlHash, relative) }
                .onFailure { log("illustration file_path backfill failed: ${row.urlHash} (${it.message})") }
        }
        return found
    }

    /** 删除整本书的插图缓存（**先表后盘**，只删没有其它行引用的物理文件）。 */
    fun deleteBook(sourceId: String, bookUrl: String): Int {
        val rows = database.listIllustrationsByBook(sourceId, bookUrl)
        val removed = database.deleteIllustrationRowsByBook(sourceId, bookUrl)
        rows.map { it.filePath }.filter { it.isNotBlank() }.distinct().forEach { filePath ->
            runCatching {
                if (database.countIllustrationPaths(filePath) == 0) store.delete(filePath)
            }.onFailure { log("illustration file delete failed: $filePath (${it.message})") }
        }
        return removed
    }
}
