package io.legado.server

import java.nio.file.Files
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
 * 方案 E 的 **L1 层**：图片字节索引 + 上游地址索引。
 *
 * ## 两级 key
 * - `urlHash = sha256(sourceId + "\n" + 上游URL)`：回答「这是哪个源的哪个地址」。
 *   **含 sourceId** —— 否则不同书源指向同一图床路径时会跨源脏命中（图错/水印不对极难排查）。
 *   它同时就是对外地址 `/api/images/{key}` 里的 key：由 URL 派生 ⇒ **可预测**，
 *   于是前端在**抓取之前**就能拼出 `<img src>`，不需要先等整章下载完。
 * - `cacheKey = sha256(字节)`：回答「这份字节的指纹是什么」。它仍用于 ETag 与"多少 URL 指向同一份字节"的统计，
 *   但**不再决定文件落在哪**：落盘位置由 `(书, 章, 分段)` 决定（见下），因此
 *   「同一张图出现在两章」会在两章各留一份可读文件，而**不再做跨 URL 的字节去重**
 *   （用户原话："方便我本地查看"；配额与 GC 本来就是按行驱动的，不受影响）。
 *
 * ## 为什么要有「登记（pending）」这一步
 * `urlHash` 是哈希、**不可逆**。服务端拿到 key 时必须知道去哪个地址抓，
 * 所以清单阶段要先把 `(key → upstream_url)` 落库（`state='pending'`），
 * 之后 `GET /api/images/{key}` 才会按需抓取并把它翻成 `ready`。
 *
 * ## 三条不变量
 * 1. **先盘后表**：文件已在磁盘上才写 `ready` 行；`ready` ⇒ 文件存在（`file_path` 或
 *    「两个哈希 + 分段号」定位之一能找到）。
 * 2. **删文件前先问引用计数**：`countMangaImagePaths(filePath) == 0` 才允许删物理文件。
 * 3. **`pending`/`failed` 不是命中**：`getByKey` 必须返回 null 让调用方去抓，
 *    但**绝不能删这些行**（删了服务端就再也找不到那个 URL 了）。
 *
 * ## 落盘目录结构（`AtomicFileStore` 负责拼装与校验）
 * `<root>/<书名 (书哈希)>/<章节名 (章节哈希)>/<8位分段号.扩展名>`
 * —— 用户拍板「方便我本地查看」。**读取不依赖这些中文**：见 [locate] 的两级策略。
 */
class ImageCache(
    directory: Path,
    private val database: Database,
    /** 配额（字节）：随缓存实例保存，`stats()` 才能报出水位。默认 10 GiB，见 `ServerConfig`。 */
    private val quotaBytes: Long = ServerConfig.DEFAULT_IMAGE_CACHE_QUOTA_BYTES,
    private val log: (String) -> Unit = {},
) {
    private val store = AtomicFileStore(directory)
    private val planner = CachePathPlanner(database)

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
     * 命中检查（只有 `ready` 且**文件在**才算命中）。
     *
     * ⚠️ 若行是 `ready` 但**磁盘文件真的找不到**（外部清理、磁盘事故），会**删掉这行**再返回 null：
     * 绝不能"假装命中"，否则前端拿到 404 裂图，而排查时数据库看起来一切正常。
     * （`pending`/`failed` 行**不删** —— 还要靠它的 `upstream_url` 去抓。）
     *
     * 「文件在不在」走 [locate] 的两级定位（`file_path` 快路径 → 无中文扫描），
     * 因此中文目录被改名/`file_path` 失效都不会被误判成"文件不存在"。
     */
    fun getByKey(urlHash: String): MangaImageRecord? {
        val row = database.findMangaImageByUrlHash(urlHash) ?: return null
        if (row.state != "ready") return null
        if (locate(row) == null) {
            log("manga image row without file, dropping: ${row.filePath.ifBlank { row.cacheKey }}")
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

    /**
     * 定位某行的物理文件（**读取路径，绝不依赖中文**）：
     *
     * 1. 快路径：行里的 `file_path`（写库时落下的相对路径）；
     * 2. 无中文定位：`sha256(sourceId + '\n' + bookUrl)` 前 [AtomicFileStore.BOOK_HASH_LENGTH] 位
     *    定位书目录 → `sha256(sourceId + '\n' + bookUrl + '\n' + chapterUrl)` 前
     *    [AtomicFileStore.CHAPTER_HASH_LENGTH] 位定位章节目录 → 8 位分段号前缀定位文件
     *    （扩展名不参与）——**命中后回填 `file_path`**，下次回到快路径。
     *
     * 两条都 miss 返回 null（调用方按"重新下载"处理），**不抛异常**。
     * 两个哈希让每章一个目录，所以第二级**不可能**命中别的章节的文件。
     */
    private fun locate(row: MangaImageRecord): Path? {
        val fast = row.filePath.takeIf { it.isNotBlank() }?.let { store.resolveOrNull(it) }
        if (fast != null && Files.isRegularFile(fast)) return fast
        val found = store.findByNameFree(
            bookCacheHash(row.sourceId, row.bookUrl),
            chapterCacheHash(row.sourceId, row.bookUrl, row.chapterUrl),
            row.segmentIndex,
        ) ?: return null
        store.relativeOf(found)?.let { relative ->
            runCatching { database.updateMangaImageFilePath(row.urlHash, relative) }
                .onFailure { log("manga image file_path backfill failed: ${row.urlHash} (${it.message})") }
        }
        return found
    }

    /** 便捷重载：按 (源, 上游地址) 命中。 */
    fun get(sourceId: String, upstreamUrl: String): CachedImage? {
        val row = getByKey(urlHash(sourceId, upstreamUrl)) ?: return null
        return CachedImage(row.urlHash, row.cacheKey, row.contentType, row.byteSize, row.width, row.height, fromCache = true)
    }

    /**
     * 落盘一份刚抓到的字节（已在 [MangaImageFetcher] 里过了解压与魔数校验，这里再兜一次底）。
     *
     * 落盘位置是**可读目录**：`<书名 (短哈希)>/<8位章节号 标题>/<8位分段号.扩展名>`，
     * 相对路径一并写进 `file_path`（**快路径缓存**，读取定位的第二级是无中文扫描）。
     *
     * ⚠️ 与旧实现的**一处刻意行为变化**：旧版按 `sha256(字节)` 内容寻址，相同字节只落一份盘；
     * 现在每段都要在自己章节目录下有一份**看得见的真实文件**（用户原话："方便我本地查看"），
     * 因此跨 URL 的字节去重被放弃。配额/GC 不受影响（它们本来就是按行驱动的）。
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
        val size = ImageBytes.probeSize(body)
        // 内容类型以**真实字节**为准，不信任上游响应头（它会谎报）——扩展名也由它决定
        val actualType = ImageBytes.detectImageContentType(body) ?: contentType.substringBefore(';')
        val hash = urlHash(sourceId, upstreamUrl)

        // 同一个上游地址重复抓取时**复用旧文件的相对路径**（否则每重抓一次就在新目录里多留一份孤儿）
        val existing = database.findMangaImageByUrlHash(hash)?.filePath?.takeIf { it.isNotBlank() && store.exists(it) }
        // 章节目录名带章节哈希 ⇒ 路径**天生单射**（两章不可能算出同一路径），因此不需要任何冲突兜底
        val relative = existing
            ?: "${planner.directory(sourceId, bookUrl, chapterUrl, chapterIndex)}/${segmentFileName(segmentIndex, actualType)}"
        store.write(relative, body)

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
                filePath = relative,
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

    /**
     * 读字节（`cacheKey` 口径）：先取任一引用这份字节的行，再按两级定位找文件。
     * 找不到返回 null（调用方回源重下），**绝不抛异常**。
     */
    fun read(cacheKey: String): ByteArray? = runCatching {
        val row = database.findMangaImageByCacheKey(cacheKey) ?: return null
        locate(row)?.let { Files.readAllBytes(it) }
    }.getOrNull()

    /** 按字节 key 定位（任一引用行；无中文定位 + 回填都在 [locate] 里）。 */
    fun fileFor(cacheKey: String): Path? = database.findMangaImageByCacheKey(cacheKey)?.let { locate(it) }

    /** 按**具体行**定位文件（直出路由必须用这个：一行一个文件，问别的行会串味）。 */
    fun fileFor(row: MangaImageRecord): Path? = locate(row)

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
        rows.map { it.filePath }.filter { it.isNotBlank() }.distinct().forEach { filePath ->
            // 路径引用计数为 0 才删文件；失败静默（GC/清理绝不能因单文件失败而中断）
            runCatching {
                if (database.countMangaImagePaths(filePath) == 0) store.delete(filePath)
            }.onFailure { log("manga image file delete failed: $filePath (${it.message})") }
        }
    }

    /** 磁盘物理占用。**配额管的是磁盘**（去重后同一份字节只占一份），因此配额判定用它。 */
    fun physicalBytes(): Long = store.totalBytes()

    /**
     * 索引里登记的字节总量。
     *
     * 便宜（一条 `sum`），用作 GC 的**预筛**：一份字节被多个 `url_hash` 引用时它偏大
     * ⇒ 只会让 GC 更早触发，绝不会漏触发，方向是安全的。
     */
    fun indexedBytes(): Long = database.sumMangaImageBytes()

    /**
     * GC 驱逐一批行（**先表后盘**），只删「引用计数为 0」的物理文件。
     *
     * @return 实际释放的磁盘字节数（用于把 `used` 递减，免得每轮都全盘扫描）
     */
    fun evictRows(rows: List<MangaImageRecord>): Long {
        if (rows.isEmpty()) return 0L
        database.deleteMangaImageRows(rows.map { it.urlHash })
        var freed = 0L
        rows.map { it.filePath }.filter { it.isNotBlank() }.distinct().forEach { filePath ->
            runCatching {
                if (database.countMangaImagePaths(filePath) == 0) {
                    // 先量再删：删完再问大小恒为 0，上报的释放量会永远是 0
                    val size = store.size(filePath) ?: 0L
                    if (store.delete(filePath)) freed += size
                }
            }.onFailure { log("manga gc file delete failed: $filePath (${it.message})") }
        }
        return freed
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
