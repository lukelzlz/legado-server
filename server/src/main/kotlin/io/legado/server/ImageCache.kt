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
 * ## 两级 key（这是本方案最关键的一个设计）
 * - `urlHash = sha256(sourceId + "\n" + 上游URL)`：回答「这是哪个源的哪个地址」。
 *   **含 sourceId** —— 否则不同书源指向同一图床路径时会跨源脏命中（图错/水印不对极难排查）。
 * - `cacheKey = sha256(字节)`：回答「这份字节存在哪」。多个 urlHash 可以指向同一个 cacheKey
 *   ⇒ **内容寻址天然去重**（不同章节/不同源拿到同一张图，盘上只有一份）。
 *
 * ## 两条不变量
 * 1. **先盘后表**：只有文件已经在磁盘上，才写数据库行；表里有行 ⇒ 文件一定在。
 * 2. **删文件前先问引用计数**：`countMangaImageRefs(cacheKey) == 0` 才允许删物理文件。
 */
class ImageCache(
    directory: Path,
    private val database: Database,
    private val log: (String) -> Unit = {},
) {
    private val store = AtomicFileStore(directory)

    fun urlHash(sourceId: String, upstreamUrl: String): String =
        ImageBytes.sha256("$sourceId\n${upstreamUrl.trim()}")

    /**
     * 命中检查。返回 null 表示「没缓存或缓存已失效」，调用方应回源抓取。
     *
     * ⚠️ 若数据库有行但**磁盘文件没了**（外部清理、磁盘事故），这里会**删掉这行**再返回 null。
     * 绝不能"假装命中"——那会让前端拿到 404 裂图，而排查时数据库看起来一切正常。
     */
    fun get(sourceId: String, upstreamUrl: String): CachedImage? {
        val hash = urlHash(sourceId, upstreamUrl)
        val row = database.findMangaImageByUrlHash(hash) ?: return null
        if (!store.exists(row.cacheKey)) {
            log("manga image row without file, dropping: ${row.cacheKey}")
            database.deleteMangaImageRow(hash)
            return null
        }
        // last_access_at 是 GC 的兜底排序依据，但**不能每张图都写一次库**：
        // 一章 20~30 张图，逐张 update 会把读路径变成写密集（且毫无必要）。
        if (System.currentTimeMillis() - row.lastAccessAt > TOUCH_INTERVAL_MS) {
            database.touchMangaImage(hash)
        }
        return CachedImage(hash, row.cacheKey, row.contentType, row.byteSize, row.width, row.height, fromCache = true)
    }

    /**
     * 落盘一份刚抓到的字节（已在 [MangaImageFetcher] 里过了解压与魔数校验，这里再兜一次底，
     * 因为内部注入接缝可能不经过网络边界）。
     */
    fun put(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        upstreamUrl: String,
        contentType: String,
        bytes: ByteArray,
    ): CachedImage {
        val body = ImageBytes.decompressIfNeeded(bytes)
        require(body.size <= ImageBytes.MAX_BYTES) { "图片超过 5 MiB 限制" }
        require(ImageBytes.looksLikeImage(body)) { "图片内容不是可解码的图片" }

        val cacheKey = ImageBytes.sha256Bytes(body)
        // 内容寻址：同一份字节只落一次盘（不同 URL 命中同一个 key 时不重复写）
        if (!store.exists(cacheKey)) store.write(cacheKey, body)

        val size = ImageBytes.probeSize(body)
        // 内容类型以**真实字节**为准，不信任上游响应头（它会谎报）
        val actualType = ImageBytes.detectImageContentType(body) ?: contentType.substringBefore(';')
        database.recordMangaImage(
            MangaImageRecord(
                urlHash = urlHash(sourceId, upstreamUrl),
                cacheKey = cacheKey,
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                upstreamUrl = upstreamUrl,
                contentType = actualType,
                byteSize = body.size.toLong(),
                width = size?.first,
                height = size?.second,
            ),
        )
        return CachedImage(cacheKey = cacheKey, urlHash = urlHash(sourceId, upstreamUrl), contentType = actualType,
            byteSize = body.size.toLong(), width = size?.first, height = size?.second, fromCache = false)
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
        rows.map { it.cacheKey }.distinct().forEach { cacheKey ->
            // 引用计数为 0 才删文件；失败静默（GC/清理绝不能因单文件失败而中断）
            runCatching {
                if (database.countMangaImageRefs(cacheKey) == 0) store.delete(cacheKey)
            }.onFailure { log("manga image file delete failed: $cacheKey (${it.message})") }
        }
    }

    fun stats(quotaBytes: Long): MangaCacheStats = MangaCacheStats(
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
