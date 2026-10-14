package io.legado.server

import java.nio.file.Path

/** 一次命中的图片（`fromCache=true` 表示**零上游请求**）。 */
data class CachedImage(
    val urlHash: String,
    val cacheKey: String,
    val contentType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val fromCache: Boolean,
)

/**
 * 方案 E 的 **L1 层**：图片字节的**唯一副本** + 上游地址索引。
 *
 * ## 两级 key（本方案最关键的设计）
 * - `urlHash = sha256(sourceId + "\n" + 上游URL)`：回答「这是哪个源的哪个地址」。
 *   **含 sourceId** —— 否则不同书源指向同一图床路径时会跨源脏命中（图错/水印不对极难排查）。
 *   它同时就是对外地址 `/api/images/{key}` 里的 key：由 URL 派生 ⇒ **可预测**，
 *   于是前端在**抓取之前**就能拼出 `<img src>`，不需要先等整章下载完。
 * - `cacheKey = sha256(字节)`：回答「这份字节存在哪」。多个 urlHash 可指向同一个 cacheKey
 *   ⇒ **内容寻址天然去重**（不同章节/不同源拿到同一张图，盘上只有一份）。
 *
 * ## 为什么要有「登记（pending）」这一步
 * `urlHash` 是哈希、**不可逆**。服务端拿到 key 时必须知道去哪个地址抓，
 * 所以清单阶段要先把 `(key → upstream_url)` 落库（`state='pending'`），
 * 之后 `GET /api/images/{key}` 才会按需抓取并把它翻成 `ready`。
 *
 * ## 三条不变量
 * 1. **先盘后表**：文件已在磁盘上才写 `ready` 行；`ready` ⇒ 文件一定在。
 * 2. **删文件前先问引用计数**：`countMangaImageRefs(cacheKey) == 0` 才允许删物理文件。
 * 3. **`pending`/`failed` 不是命中**：`getByKey` 必须返回 null 让调用方去抓，
 *    但**绝不能删这些行**（删了服务端就再也找不到那个 URL 了）。
 */
class ImageCache(
    directory: Path,
    private val database: Database,
    /** 配额（字节）：随缓存实例保存，`stats()` 才能报出水位。默认 10 GiB，见 `ServerConfig`。 */
    private val quotaBytes: Long = ServerConfig.DEFAULT_IMAGE_CACHE_QUOTA_BYTES,
    private val log: (String) -> Unit = {},
) {
    private val store = AtomicFileStore(directory)

    fun urlHash(sourceId: String, upstreamUrl: String): String =
        ImageBytes.sha256("$sourceId\n${upstreamUrl.trim()}")

    /**
     * 清单阶段：登记本章的全部图片（幂等），返回按章节内序号排列的行。
     *
     * 已 `ready` 的行不会被重置回 `pending`（见 `registerMangaImages`）。
     */
    fun registerChapter(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        chapterIndex: Int,
        upstreamUrls: List<String>,
    ): List<MangaImageRecord> {
        val now = System.currentTimeMillis()
        val records = upstreamUrls.mapIndexed { index, url ->
            MangaImageRecord(
                urlHash = urlHash(sourceId, url),
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                upstreamUrl = url,
                chapterIndex = chapterIndex,
                segmentIndex = index,
                createdAt = now,
                lastAccessAt = now,
            )
        }
        database.registerMangaImages(records)
        return database.listMangaImagesForChapter(sourceId, bookUrl, chapterUrl)
    }

    /** 本章的已登记行（清单接口的取数口径）。 */
    fun chapterRows(sourceId: String, bookUrl: String, chapterUrl: String): List<MangaImageRecord> =
        database.listMangaImagesForChapter(sourceId, bookUrl, chapterUrl)

    /** 按 key 取原始行（不管状态）—— 路由需要它拿到 `upstream_url` 才知道去哪抓。 */
    fun rowByKey(urlHash: String): MangaImageRecord? = database.findMangaImageByUrlHash(urlHash)

    /**
     * 命中检查（只有 `ready` 且文件在才算命中）。
     *
     * ⚠️ 若行是 `ready` 但**磁盘文件没了**（外部清理、磁盘事故），会**删掉这行**再返回 null：
     * 绝不能"假装命中"，否则前端拿到 404 裂图，而排查时数据库看起来一切正常。
     * （`pending`/`failed` 行**不删** —— 还要靠它的 `upstream_url` 去抓。）
     */
    fun getByKey(urlHash: String): MangaImageRecord? {
        val row = database.findMangaImageByUrlHash(urlHash) ?: return null
        if (row.state != "ready") return null
        if (!store.exists(row.cacheKey)) {
            log("manga image row without file, dropping: ${row.cacheKey}")
            database.deleteMangaImageRow(urlHash)
            return null
        }
        // last_access_at 是 GC 的兜底排序依据，但**不能每张图都写一次库**：
        // 一章 20~30 张图，逐张 update 会把读路径变成写密集（且毫无必要）。
        if (System.currentTimeMillis() - row.lastAccessAt > TOUCH_INTERVAL_MS) {
            database.touchMangaImage(urlHash)
        }
        return row
    }

    /** 便捷重载：按 (源, 上游地址) 命中。 */
    fun get(sourceId: String, upstreamUrl: String): CachedImage? {
        val row = getByKey(urlHash(sourceId, upstreamUrl)) ?: return null
        return CachedImage(row.urlHash, row.cacheKey, row.contentType, row.byteSize, row.width, row.height, fromCache = true)
    }

    /**
     * 落盘一份刚抓到的字节（已在 [MangaImageFetcher] 里过了解压与魔数校验，这里再兜一次底）。
     *
     * @param chapterIndex / segmentIndex 由清单登记时确定，抓取路径必须原样带回来，
     *        否则「一章内第几张」会丢失，滑窗 GC 与保序拼装都会失准。
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
    ): CachedImage {
        val body = ImageBytes.decompressIfNeeded(bytes)
        require(body.size <= ImageBytes.MAX_BYTES) { "图片超过 5 MiB 限制" }
        require(ImageBytes.looksLikeImage(body)) { "图片内容不是可解码的图片" }

        val cacheKey = ImageBytes.sha256Bytes(body)
        // 内容寻址：同一份字节只落一次盘（不同 URL 命中同一 key 时不重复写）
        if (!store.exists(cacheKey)) store.write(cacheKey, body)

        val size = ImageBytes.probeSize(body)
        // 内容类型以**真实字节**为准，不信任上游响应头（它会谎报）
        val actualType = ImageBytes.detectImageContentType(body) ?: contentType.substringBefore(';')
        val hash = urlHash(sourceId, upstreamUrl)
        database.recordMangaImage(
            MangaImageRecord(
                urlHash = hash,
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                upstreamUrl = upstreamUrl,
                cacheKey = cacheKey,
                chapterIndex = chapterIndex,
                segmentIndex = segmentIndex,
                contentType = actualType,
                byteSize = body.size.toLong(),
                width = size?.first,
                height = size?.second,
                state = "ready",
            ),
        )
        return CachedImage(hash, cacheKey, actualType, body.size.toLong(), size?.first, size?.second, fromCache = false)
    }

    fun markFailed(urlHash: String, error: String?) = database.markMangaImageFailed(urlHash, error)

    /**
     * 依据本章各图状态刷新「整章就绪度」。
     *
     * `ready == total` 才 `ready`（目录绿点 `●` 的唯一口径）；有失败但已无在途任务 ⇒ `partial`。
     * 返回已就绪张数。
     */
    fun refreshChapterReadiness(sourceId: String, bookUrl: String, chapterUrl: String): Int {
        val rows = database.listMangaImagesForChapter(sourceId, bookUrl, chapterUrl)
        val total = rows.size
        val ready = rows.count { it.state == "ready" }
        val failed = rows.count { it.state == "failed" }
        val state = when {
            total == 0 -> "failed"
            ready == total -> "ready"
            ready + failed == total -> "partial"
            else -> "pending"
        }
        database.updateMangaChapterProgress(sourceId, bookUrl, chapterUrl, ready, failed, state, null)
        return ready
    }

    fun read(cacheKey: String): ByteArray? = store.read(cacheKey)

    fun fileFor(cacheKey: String): Path? = if (store.exists(cacheKey)) store.pathFor(cacheKey) else null

    /** 删除整本书的图片缓存：**先表后盘**，且只删没有其它引用的物理文件。 */
    fun deleteBook(sourceId: String, bookUrl: String): Int {
        val rows = database.listMangaImagesByBook(sourceId, bookUrl)
        val removed = database.deleteMangaImageRowsByBook(sourceId, bookUrl)
        releaseOrphanFiles(rows)
        return removed
    }

    /** 清空全部图片缓存（`?all=true`）。 */
    fun deleteAll(): Int {
        val rows = database.listAllMangaImages()
        val removed = database.deleteAllMangaImageRows()
        releaseOrphanFiles(rows)
        return removed
    }

    private fun releaseOrphanFiles(rows: List<MangaImageRecord>) {
        rows.map { it.cacheKey }.filter { it.isNotBlank() }.distinct().forEach { cacheKey ->
            // 引用计数为 0 才删文件；失败静默（GC/清理绝不能因单文件失败而中断）
            runCatching {
                if (database.countMangaImageRefs(cacheKey) == 0) store.delete(cacheKey)
            }.onFailure { log("manga image file delete failed: $cacheKey (${it.message})") }
        }
    }

    fun stats(): MangaCacheStats = MangaCacheStats(
        totalBytes = database.sumMangaImageBytes(),
        quotaBytes = quotaBytes,
        imageCount = database.countMangaImages(),
        // 实际磁盘占用单独报：表与盘一旦漂移（孤儿文件），这里一眼可见
        diskBytes = store.totalBytes(),
        books = database.mangaCacheStatsByBook(),
    )

    private companion object {
        /** `last_access_at` 的写库节流窗口（10 分钟）。 */
        const val TOUCH_INTERVAL_MS = 10 * 60 * 1000L
    }
}
