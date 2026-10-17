package io.legado.server

import java.nio.file.Files
import java.nio.file.Path

/** 一次命中的音频（`fromCache=true` 表示**零上游请求**）。 */
data class CachedAudio(
    val cacheKey: String,
    val contentType: String,
    val byteSize: Long,
    val fromCache: Boolean,
)

/**
 * 一段音频的落盘目标：相对目录 + 成品名生成器（真实内容类型 → 文件名）。
 *
 * 生成器而不是写死的文件名，是因为**扩展名来自抓到头部字节后定下的真实内容类型**。
 */
data class AudioTarget(val directory: String, val nameOf: (String) -> String)

/**
 * 方案 G2 的 **L1 层**：音频字节的**唯一副本** + 上游地址索引。
 *
 * ## 与漫画 `ImageCache` 的两处**刻意差异**（都是 G-2 / G-3 拍板的结果）
 * 1. **URL 寻址，不做内容寻址**：key = `sha256(sourceId + '\n' + 规范化URL)`，**可预测**
 *    ⇒ 前端在"抓取之前"就能拼出 `/api/audio/{key}`。漫画当初被迫改用定位 URL，正是因为
 *    内容寻址下"抓取前 key 未知"。代价是放弃跨 URL 字节去重——音频场景可接受
 *    （同一章的音频不会在多个地址间共享字节，去重收益近零）。
 * 2. **没有 L2 章节清单表**：整章就绪度 = "这一章每一段都 ready"，一条 `having` 就算出来了
 *    （见 `Database.readyAudioChapters`）。多一张表就多一处要同步的状态。
 *
 * ## 三条不变量（与漫画逐字同源）
 * 1. **先盘后表**：文件已在磁盘上才写 `ready` 行；`ready` ⇒ 文件存在（`file_path` 或
 *    「哈希 + 序号」两级定位之一能找到）。
 * 2. **`pending`/`failed` 不是命中**：命中检查必须返回 null 让调用方去抓，
 *    但**绝不能删这些行**——删了服务端就再也找不到那段音频的地址了（key 不可逆）。
 * 3. **GC 先表后盘**，且所有删除都由表驱动。
 *
 * ## 落盘目录结构（`AtomicFileStore` 负责拼装与校验）
 * `<root>/<书名 (书哈希)>/<章节名 (章节哈希)>/<8位分段号.扩展名>`
 * —— 用户拍板「方便我本地查看」。**读取不依赖这些中文**：见 [locate] 的两级策略。
 */
class AudioCache(
    directory: Path,
    private val database: Database,
    /** 配额（字节）：默认 20 GiB，环境变量 `LEGADO_AUDIO_CACHE_MAX_GB`（与漫画 10 GiB 分开配置）。 */
    private val quotaBytes: Long = ServerConfig.DEFAULT_AUDIO_CACHE_QUOTA_BYTES,
    private val log: (String) -> Unit = {},
) {
    /**
     * 原子落盘存储。**刻意放开给 `AudioFetcher` 用**：它必须把上游字节**边读边写**到
     * `.part`（60 MiB 的 `readAllBytes()` 会直接 OOM），而"抓到字节"和"落盘"本来就是
     * 同一条链路的两半——`AtomicFileStore` 的 KDoc 原本就写着"漫画与有声书共用"。
     */
    val store = AtomicFileStore(directory)

    private val planner = CachePathPlanner(database)

    /** 对外 key：`sha256(sourceId + '\n' + URL)`。含 sourceId，防跨源脏命中（同漫画的裁决）。 */
    fun cacheKey(sourceId: String, upstreamUrl: String): String =
        AudioBytes.sha256("$sourceId\n${upstreamUrl.trim()}")

    // ------------------------------------------------------------------
    // 落盘位置（可读目录）与定位（不依赖中文）
    // ------------------------------------------------------------------

    /**
     * 该段应落的**相对目录**：`<书名 (书哈希)>/<章节名 (章节哈希)>`。
     *
     * 抓取前就要知道目录（`.part` 必须与成品同目录才能 `ATOMIC_MOVE`），而**扩展名**
     * 要等拿到头部字节、定下真实 `content_type` 之后才知道 ⇒ 由 [AudioFetcher] 两段式提交。
     * 真正落盘用 [targetFor]（它会优先沿用该行已有的目录，避免重复抓取留孤儿）。
     */
    fun fileDirFor(row: AudioCacheRecord): String =
        planner.directory(row.sourceId, row.bookUrl, row.chapterUrl, row.chapterIndex)

    /** 该段**已知内容类型**时的首选相对路径（测试与预置数据用）。 */
    fun relativePathFor(row: AudioCacheRecord, contentType: String): String =
        "${fileDirFor(row)}/${segmentFileName(row.segmentIndex, contentType)}"

    /**
     * 该段的**落盘目标**（目录 + 成品名生成器）：抓取前必须先定下来，因为 `.part` 要与成品同目录。
     *
     * 成品名要等真实内容类型才知道 ⇒ 用生成器表达。章节目录名带**章节哈希**，
     * 因此路径天生单射（两章不可能算出同一路径），不需要任何冲突兜底。
     */
    fun targetFor(row: AudioCacheRecord): AudioTarget {
        if (row.filePath.isNotBlank()) {
            // 已有落盘记录：沿用它的目录，绝不搬家 —— 否则每次重抓都会在新地方多留一份孤儿文件
            val directory = row.filePath.substringBeforeLast('/', "")
            return AudioTarget(directory) { contentType -> segmentFileName(row.segmentIndex, contentType) }
        }
        return AudioTarget(fileDirFor(row)) { contentType -> segmentFileName(row.segmentIndex, contentType) }
    }

    /**
     * 定位某段的物理文件（**读取路径，绝不依赖中文**）：
     * ① 行里的 `file_path`（快路径）；② 命中不到就按「书哈希 → 章节哈希 → 8 位分段号前缀」无中文扫描，
     * 命中后**回填 `file_path`**。两条都 miss ⇒ null（调用方按"重新下载"处理），**不抛异常**。
     */
    private fun locate(row: AudioCacheRecord): Path? {
        val fast = row.filePath.takeIf { it.isNotBlank() }?.let { store.resolveOrNull(it) }
        if (fast != null && Files.isRegularFile(fast)) return fast
        val found = store.findByNameFree(
            bookCacheHash(row.sourceId, row.bookUrl),
            chapterCacheHash(row.sourceId, row.bookUrl, row.chapterUrl),
            row.segmentIndex,
        ) ?: return null
        store.relativeOf(found)?.let { relative ->
            runCatching { database.updateAudioFilePath(row.cacheKey, relative) }
                .onFailure { log("audio file_path backfill failed: ${row.cacheKey} (${it.message})") }
        }
        return found
    }

    /**
     * 清单阶段：登记本章的全部分段（幂等），返回按段序号排列的行。
     *
     * 已 `ready` 的行不会被重置回 `pending`（见 `Database.registerAudioTracks`）；
     * 书源换了某段地址时，旧行会被清掉，**且它的字节也要回收**（否则永远占盘）。
     */
    fun registerChapter(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        chapterIndex: Int,
        tracks: List<AudioTrackSpec>,
    ): List<AudioCacheRecord> {
        val now = System.currentTimeMillis()
        val records = tracks.mapIndexed { index, spec ->
            AudioCacheRecord(
                cacheKey = cacheKey(sourceId, spec.url),
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                chapterIndex = chapterIndex,
                segmentIndex = index,
                upstreamUrl = spec.url,
                headersJson = spec.headersJson,
                createdAt = now,
                lastAccessAt = now,
            )
        }
        // 登记前先记下旧行：数据库只会清掉"已不在清单里"的索引行，
        // 而**字节只能由这一层回收**（Database 不认识落盘存储）。
        val previous = database.listAudioForChapter(sourceId, bookUrl, chapterUrl)
        database.registerAudioTracks(records, records.map { it.cacheKey })
        val kept = records.map { it.cacheKey }.toSet()
        releaseFiles(previous.filterNot { it.cacheKey in kept })
        return database.listAudioForChapter(sourceId, bookUrl, chapterUrl)
    }

    /** 本章的已登记行（清单接口的取数口径）。 */
    fun chapterRows(sourceId: String, bookUrl: String, chapterUrl: String): List<AudioCacheRecord> =
        database.listAudioForChapter(sourceId, bookUrl, chapterUrl)

    /** 按 key 取原始行（不管状态）—— 路由需要它拿到 `upstream_url`/`headers_json` 才知道怎么抓。 */
    fun rowByKey(cacheKey: String): AudioCacheRecord? = database.findAudioByKey(cacheKey)

    /**
     * 命中检查（只有 `ready` 且**文件在**才算命中）。
     *
     * ⚠️ 若行是 `ready` 但文件真的找不到（外部清理/磁盘事故），**删掉这行**再返回 null：
     * 绝不能"假装命中"，否则播放器拿到 404，而数据库看起来一切正常。
     * 「文件在不在」走 [locate] 的两级定位 ⇒ 中文目录被改名也不会误判。
     */
    fun getByKey(cacheKey: String): AudioCacheRecord? {
        val row = database.findAudioByKey(cacheKey) ?: return null
        if (row.state != "ready") return null
        if (locate(row) == null) {
            log("audio row without file, dropping: $cacheKey")
            database.deleteAudioRow(cacheKey)
            return null
        }
        // `last_access_at` 是 GC 的兜底排序依据，但**不能每次读都写库**（播放器 seek 会打一串请求）。
        if (System.currentTimeMillis() - row.lastAccessAt > TOUCH_INTERVAL_MS) {
            database.touchAudio(cacheKey)
        }
        return row
    }

    /**
     * 「**先盘后表**」的翻牌：字节已经由 [AudioFetcher] **流式**写进 [store]（因此这里没有
     * `block: (OutputStream) -> Unit` 这种写法——那会要求调用方把字节再提供一遍，
     * 正是"两次落盘 / 空文件覆盖"这类事故的来源）。
     *
     * @param filePath [AudioFetcher] 提交后的**相对路径**（成品名含真实扩展名，
     *   只有抓到头部字节之后才定得下来）——它是 `file_path` 快路径的来源。
     * @throws IllegalArgumentException 文件不在时拒绝写 `ready`：
     *   绝不允许出现"表说 ready、盘上没有"的行（那会让播放器拿到 404 而数据库看起来一切正常）。
     */
    fun markReady(row: AudioCacheRecord, contentType: String, byteSize: Long, filePath: String): CachedAudio {
        require(store.exists(filePath)) { "音频文件未落盘，拒绝写 ready：$filePath" }
        val actualSize = byteSize.takeIf { it > 0 } ?: (store.size(filePath) ?: 0L)
        database.recordAudioTrack(
            row.copy(contentType = contentType, byteSize = actualSize, state = "ready", lastError = null, filePath = filePath),
        )
        return CachedAudio(row.cacheKey, contentType, actualSize, fromCache = false)
    }

    fun markFailed(cacheKey: String, error: String?) = database.markAudioFailed(cacheKey, error)

    /**
     * **预置/注入一份已就绪的缓存**（字节已在手上，不走抓取链路）。
     *
     * 存在的理由是**路径装配必须同源**：预置数据（测试、以及任何"已经有字节"的内部调用方）
     * 若自己拼一遍目录，就会和 `AudioFetcher` 拼出不同的路径 —— 那正是"表里说 ready、
     * 直出却 404"这类事故的温床。
     *
     * @return 落盘的相对路径（与 `AudioFetcher` 提交的完全同形）
     */
    fun putReady(row: AudioCacheRecord, contentType: String, bytes: ByteArray): String {
        val target = targetFor(row)
        val relative = "${target.directory}/${target.nameOf(contentType)}"
        store.write(relative, bytes)
        markReady(row, contentType, bytes.size.toLong(), relative)
        return relative
    }

    fun updateDuration(cacheKey: String, durationMs: Long): Int = database.updateAudioDuration(cacheKey, durationMs)

    fun markListened(cacheKey: String, listened: Boolean) = database.markAudioListened(cacheKey, listened)

    /**
     * 整章状态：`ready == total` ⇒ `ready`（目录绿点 `●` 的唯一口径）；
     * 有失败且已无在途 ⇒ `partial`。
     */
    fun chapterState(rows: List<AudioCacheRecord>): String {
        val total = rows.size
        if (total == 0) return "failed"
        val ready = rows.count { it.state == "ready" }
        val failed = rows.count { it.state == "failed" }
        return when {
            ready == total -> "ready"
            ready + failed == total -> "partial"
            else -> "pending"
        }
    }

    /** 按 key 定位（内部先取行，再走两级定位；未命中返回 null 而非抛错）。 */
    fun fileFor(cacheKey: String): Path? = database.findAudioByKey(cacheKey)?.let { locate(it) }

    /** 按**具体行**定位文件（直出路由必须用这个：一行一个文件，问别的行会串味）。 */
    fun fileFor(row: AudioCacheRecord): Path? = locate(row)

    /** 删除整本书的音频缓存：**先表后盘**。 */
    fun deleteBook(sourceId: String, bookUrl: String): Int {
        val rows = database.listAudioByBook(sourceId, bookUrl)
        val removed = database.deleteAudioRowsByBook(sourceId, bookUrl)
        releaseFiles(rows)
        return removed
    }

    /** 清空全部音频缓存（`?all=true`）。 */
    fun deleteAll(): Int {
        val rows = database.listAllAudio()
        val removed = database.deleteAllAudioRows()
        releaseFiles(rows)
        return removed
    }

    /** 单段删除（先表后盘）。 */
    fun deleteKey(cacheKey: String): Int {
        val row = database.findAudioByKey(cacheKey) ?: return 0
        database.deleteAudioRow(cacheKey)
        releaseFiles(listOf(row))
        return 1
    }

    private fun releaseFiles(rows: List<AudioCacheRecord>) {
        // 一个 key 一份字节，但仍**必须问一次表**再删：同一段音频可能被另一章（甚至另一本书）
        // 引用（共享片头/同地址多章），无条件删文件会造成"表里还有行、盘上却没了"的裂图式事故。
        rows.map { it.filePath }.filter { it.isNotBlank() }.distinct().forEach { filePath ->
            runCatching {
                if (database.countAudioPaths(filePath) == 0) store.delete(filePath)
            }.onFailure { log("audio file delete failed: $filePath (${it.message})") }
        }
    }

    /** 磁盘物理占用。**配额管的是磁盘**。 */
    fun physicalBytes(): Long = store.totalBytes()

    /** 索引里登记的字节总量（便宜，用作 GC 预筛）。 */
    fun indexedBytes(): Long = database.sumAudioBytes()

    /**
     * GC 驱逐一批行（**先表后盘**）。
     *
     * @return 实际释放的磁盘字节数（用于把 `used` 递减，免得每轮都全盘扫描）
     */
    fun evictRows(rows: List<AudioCacheRecord>): Long {
        if (rows.isEmpty()) return 0L
        database.deleteAudioRows(rows.map { it.cacheKey })
        var freed = 0L
        rows.map { it.filePath }.filter { it.isNotBlank() }.distinct().forEach { filePath ->
            runCatching {
                if (database.countAudioPaths(filePath) == 0) {
                    // 先量再删：删完再问大小恒为 0，上报的释放量会永远是 0
                    val size = store.size(filePath) ?: 0L
                    if (store.delete(filePath)) freed += size
                }
            }.onFailure { log("audio gc file delete failed: $filePath (${it.message})") }
        }
        return freed
    }

    fun stats(): AudioCacheStats = AudioCacheStats(
        totalBytes = database.sumAudioBytes(),
        quotaBytes = quotaBytes,
        trackCount = database.countAudioTracks(),
        // 实际磁盘占用单独报：表与盘一旦漂移（孤儿文件），这里一眼可见
        diskBytes = store.totalBytes(),
        books = database.audioCacheStatsByBook(),
    )

    private companion object {
        /** `last_access_at` 的写库节流窗口（10 分钟，同漫画）。 */
        const val TOUCH_INTERVAL_MS = 10 * 60 * 1000L
    }
}
