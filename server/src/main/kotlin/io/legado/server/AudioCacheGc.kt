package io.legado.server

/** 一次音频 GC 的结果（可观测性：写日志，也是测试的断言对象）。 */
data class AudioGcResult(
    /** 本轮是否真的做了事（未超阈值 / 被关闭 ⇒ false）。 */
    val ran: Boolean,
    val usedBytes: Long,
    val highWaterBytes: Long,
    val lowWaterBytes: Long,
    val freedBytes: Long,
    val evictedRows: Int,
    val protectedRows: Int,
    val skippedRows: Int,
)

/**
 * 方案 G2 环节 G8：音频缓存的配额与**滑窗 GC**。
 *
 * ## 与漫画 `ImageCacheGc` 的关系：**同一套部落知识，只换了参数**
 * 判据表（顺位即优先级）逐字沿用语料里的结论，差异只在窗口宽度与顺位 1 的间隔：
 *
 * | 顺位 | 对象 | 判据 | 音频参数 |
 * | :--- | :--- | :--- | :--- |
 * | 1 | 已听完的历史冷数据 | `chapter_index < 锚点 - gap` 且**全书完成度 > 80%** | gap = **5**（漫画 10） |
 * | 2 | 弃坑孤立预取 | 非 pinned，且（不在书架 或 > 14 天未访问） | 同漫画 |
 * | — | 滑窗强保护 | 锚点章 ~ **后方 N 章**永不入选 | N = **5**（漫画 15） |
 * | — | 免死金牌 | `pinned = 1`（用户手动「下载整本」） | 同漫画 |
 *
 * 为什么音频窗口更窄（5 章）：一章音频 10~60 MiB，**"后方 15 章"就是近 1 GiB**；
 * 而一章漫画通常十几 MB。窗口必须按**字节量级**而不是按"章数习惯"来定。
 *
 * ## 三条铁律（与漫画同源）
 * 1. **先表后盘**。
 * 2. **绝不按目录扫盘乱删**：所有删除都由表驱动。
 * 3. **GC 失败一律静默**：后台保洁无权影响播放。
 */
class AudioCacheGc(
    private val database: Database,
    private val audioCache: AudioCache,
    private val quotaBytes: Long = ServerConfig.DEFAULT_AUDIO_CACHE_QUOTA_BYTES,
    private val enabled: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    /** 便宜的入口：先用索引总和预筛，只有可能超阈值时才做全盘扫描。 */
    fun maybeRun(now: Long = System.currentTimeMillis(), force: Boolean = false): AudioGcResult {
        if (!enabled) return skipped(0L)
        val indexed = runCatching { audioCache.indexedBytes() }.getOrDefault(0L)
        if (!force && indexed <= highWater()) return skipped(indexed)
        return runCatching { run(now, force) }
            .onFailure { log("audio gc failed: ${it.message}") }
            .getOrElse { skipped(0L) }
    }

    /**
     * 真正跑一轮（测试直接调它，免得为了越过预筛去造 20 GiB 数据）。
     *
     * @param force `true` = 连滞回预筛一起绕过；`enabled = false` 是硬开关，`force` 也绕不过去。
     */
    fun run(now: Long = System.currentTimeMillis(), force: Boolean = false): AudioGcResult {
        if (!enabled) return skipped(0L)
        val high = highWater()
        val low = lowWater()

        var used = physicalOrIndexed()
        if (!force && used <= high) return AudioGcResult(false, used, high, low, 0, 0, 0, 0)

        val protection = Protection(now)
        var freed = 0L
        var evicted = 0
        var protectedCount = 0
        var skippedCount = 0
        var rounds = 0
        while (used > low && rounds < MAX_ROUNDS) {
            rounds++
            val candidates = runCatching { database.listAudioByLastAccess(GC_BATCH) }.getOrDefault(emptyList())
                .filter { it.state == "ready" }
            if (candidates.isEmpty()) break

            protectedCount += candidates.count { protection.isProtected(it) }
            val ordered = candidates.filterNot { protection.isProtected(it) }
            skippedCount += ordered.count { !protection.isReadHistory(it) && !protection.isAbandoned(it, now) }
            // 顺位：① 已听历史 → ② 失活弃坑。档内保持"最冷优先"（SQL 已按 last_access_at 升序）。
            val ranked = ordered.filter { protection.isReadHistory(it) } + ordered.filter { protection.isAbandoned(it, now) }

            val picked = mutableListOf<AudioCacheRecord>()
            var projected = used
            for (row in ranked) {
                if (projected <= low) break
                picked += row
                projected -= row.byteSize.coerceAtLeast(0L)
            }
            if (picked.isEmpty()) break

            evicted += picked.size
            freed += runCatching { audioCache.evictRows(picked) }
                .onFailure { log("audio gc evict failed: ${it.message}") }
                .getOrDefault(0L)
            used = physicalOrIndexed()
        }

        val result = AudioGcResult(true, used, high, low, freed, evicted, protectedCount, skippedCount)
        log("audio gc: used=${result.usedBytes} high=$high low=$low freed=${result.freedBytes} evicted=${result.evictedRows} protected=${result.protectedRows} skipped=${result.skippedRows}")
        return result
    }

    /** 物理占用优先；目录读不到时退回索引总量，绝不因测量失败而放弃 GC。 */
    private fun physicalOrIndexed(): Long = runCatching { audioCache.physicalBytes() }.getOrDefault(0L)
        .takeIf { it > 0 } ?: runCatching { audioCache.indexedBytes() }.getOrDefault(0L)

    private fun skipped(indexed: Long) = AudioGcResult(false, indexed, highWater(), lowWater(), 0, 0, 0, 0)

    private fun highWater(): Long = (quotaBytes * HIGH_WATER_RATIO).toLong()
    private fun lowWater(): Long = (quotaBytes * LOW_WATER_RATIO).toLong()

    /** 保护集：pinned 书 + 每本书的滑窗（锚点章 ~ 后方 5 章）。 */
    private inner class Protection(private val now: Long) {
        private val pinned: Set<Pair<String, String>> =
            runCatching { database.listPinnedAudioBooks() }.getOrDefault(emptySet())
        private val anchorByBook: Map<Pair<String, String>, ReadingProgress> =
            runCatching { database.listReadingAnchors() }.getOrDefault(emptyList())
                .associateBy { it.sourceId to it.bookUrl }
        private val shelfCache = mutableMapOf<Pair<String, String>, Boolean>()
        private val tocSizeCache = mutableMapOf<Pair<String, String>, Int>()
        private val chapterDurationCache = mutableMapOf<Triple<String, String, String>, Long>()

        /** 免死金牌：整本书 pinned（用户手动「下载整本」）。 */
        fun isPinned(row: AudioCacheRecord) = (row.sourceId to row.bookUrl) in pinned

        /** 滑窗强保护：锚点章 ~ 后方 5 章（**含当前章**，正在听的那一章绝不能删）。 */
        fun inWindow(row: AudioCacheRecord): Boolean {
            val anchor = anchorByBook[row.sourceId to row.bookUrl] ?: return false
            return row.chapterIndex >= anchor.chapterIndex && row.chapterIndex <= anchor.chapterIndex + WINDOW_CHAPTERS
        }

        fun isProtected(row: AudioCacheRecord) = isPinned(row) || inWindow(row)

        /**
         * 顺位 1：已听完的历史冷数据。
         *
         * 两个条件都要满足：**落在锚点后方 5 章之外**（确属历史）**且全书完成度 > 80%**
         * （确属"听完了不回头"）。只看其中一个都会误伤。
         */
        fun isReadHistory(row: AudioCacheRecord): Boolean {
            if (isProtected(row)) return false
            val anchor = anchorByBook[row.sourceId to row.bookUrl] ?: return false
            if (row.chapterIndex >= anchor.chapterIndex - READ_HISTORY_GAP) return false
            return bookCompletion(anchor) >= COMPLETION_THRESHOLD
        }

        /** 顺位 2：失活弃坑的孤立预取（非 pinned、不在滑窗内，且超期未访问或已移出书架）。 */
        fun isAbandoned(row: AudioCacheRecord, now: Long): Boolean {
            if (isProtected(row)) return false
            if (!onShelf(row)) return true
            return now - row.lastAccessAt > ABANDON_AFTER_MS
        }

        private fun onShelf(row: AudioCacheRecord): Boolean = shelfCache.getOrPut(row.sourceId to row.bookUrl) {
            // 查不到书架信息时**当作在架**（保守：不删比误删安全）
            runCatching { database.shelfHasBook(row.sourceId, row.bookUrl) }.getOrDefault(true)
        }

        /**
         * 全书完成度 =（当前章序 + **章内比例**）/ 总章数。
         *
         * 章内比例来自**音频自己的时长**：`position_ms / 该章总时长`（时长由前端
         * `loadedmetadata` 回传）。总章数或时长取不到就当作 0 —— **宁可不删**。
         */
        private fun bookCompletion(anchor: ReadingProgress): Double {
            val key = anchor.sourceId to anchor.bookUrl
            val total = tocSizeCache.getOrPut(key) {
                runCatching { database.getTocCache(anchor.sourceId, anchor.bookUrl)?.size ?: 0 }.getOrDefault(0)
            }
            if (total <= 0) return 0.0
            return (anchor.chapterIndex + chapterFraction(anchor)).coerceAtLeast(0.0) / total
        }

        private fun chapterFraction(anchor: ReadingProgress): Double {
            val position = anchor.positionMs ?: return 0.0
            if (position <= 0) return 0.0
            val durationKey = Triple(anchor.sourceId, anchor.bookUrl, anchor.chapterUrl)
            val duration = chapterDurationCache.getOrPut(durationKey) {
                runCatching {
                    database.listAudioForChapter(anchor.sourceId, anchor.bookUrl, anchor.chapterUrl)
                        .sumOf { it.durationMs }
                }.getOrDefault(0L)
            }
            if (duration <= 0) return 0.0
            return (position.toDouble() / duration).coerceIn(0.0, 1.0)
        }
    }

    private companion object {
        /** 占用 > 90% 触发（滞回上沿）。 */
        const val HIGH_WATER_RATIO = 0.90

        /** 淘汰到 75% 停止（滞回下沿）——避免磁盘在临界点反复抖动触发。 */
        const val LOW_WATER_RATIO = 0.75

        /**
         * 滑窗强保护：锚点后方 **5 章**（音频窗口）。
         *
         * 一章音频 10~60 MiB ⇒ 15 章就是近 1 GiB，与漫画的 15 章完全不是一个量级。
         */
        const val WINDOW_CHAPTERS = 5

        /** 顺位 1 需要落在锚点**后方 5 章**之外。 */
        const val READ_HISTORY_GAP = 5

        /** 全书完成度阈值（顺位 1）。 */
        const val COMPLETION_THRESHOLD = 0.8

        /** 顺位 2：超过 14 天未访问即视为失活。 */
        const val ABANDON_AFTER_MS = 14L * 24 * 3600 * 1000

        /** 每轮候选行数（同时也是写事务的批量上界）。 */
        const val GC_BATCH = 500

        /** 单轮 GC 的循环上限（保护集过大时尽早收手，绝不空转）。 */
        const val MAX_ROUNDS = 200
    }
}
