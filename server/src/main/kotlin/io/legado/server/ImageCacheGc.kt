package io.legado.server

/** 一次 GC 的结果（可观测性：写在日志里，也是测试的断言对象）。 */
data class ImageGcResult(
    /** 本轮是否真的做了事（未超阈值 / 被关闭 ⇒ false）。 */
    val ran: Boolean,
    /** 判定时看到的磁盘占用（字节）。 */
    val usedBytes: Long,
    /** 触发阈值（配额 × 90%）。 */
    val highWaterBytes: Long,
    /** 淘汰目标（配额 × 75%）。 */
    val lowWaterBytes: Long,
    /** 实际释放的磁盘字节。 */
    val freedBytes: Long,
    /** 删掉的索引行数（含"只删映射、保留字节"的行）。 */
    val evictedRows: Int,
    /** 因为落在保护集（滑窗 / pinned）里而被跳过的候选数。 */
    val protectedRows: Int,
    /** 因为不在任何分档顺位里而被跳过的候选数。 */
    val skippedRows: Int,
)

/**
 * 方案 E 环节 E12：图片缓存配额与 GC。
 *
 * ## 为什么不是 LRU
 * 纯 LRU 按"最后访问时间"淘汰，会**优先删掉未读的前瞻章**——用户往下翻一页就断流。
 * 本实现是「围绕阅读锚点的**滑窗保护** + **已读历史优先**淘汰」（lukelzlz 2026-10-13 拍板，
 * 见 PR #45 `0ab69e2` 与 AGENTS.md 的 `[媒体缓存/GC]` 条目）。
 *
 * ## 判据（顺位即优先级）
 * | 顺位 | 对象 | 判据 |
 * | :--- | :--- | :--- |
 * | 1 | 已读完的历史冷数据 | `chapter_index < 锚点 - 10` 且**全书完成度 > 80%** |
 * | 2 | 失活弃坑的孤立预取 | 非 pinned，且（不在书架 或 `last_access_at` 早于 14 天） |
 * | — | 滑窗强保护 | 锚点章 ~ **后方 15 章**（含当前章）永不入选 |
 * | — | 免死金牌 | pinned 书（手动整本下载）永不自动淘汰 |
 *
 * ⚠️ **只淘汰这两档**：既不是"已读历史"也不"失活"的中间态（例如正在追的书的第 3 章）
 * 宁可留着占盘，也不赌一把删掉——磁盘满了是能看见的问题，删错东西是看不见的问题。
 *
 * ## 三条铁律
 * 1. **先表后盘**：删行之后再删文件，且删文件前必须问引用计数（一份字节可能被多个 URL 引用）。
 * 2. **绝不按目录扫盘乱删**：所有删除都由表驱动（表里没有的行不可能被删）。
 * 3. **GC 失败一律静默**：任何一层抛错都只记日志——GC 是后台保洁，无权影响阅读。
 */
class ImageCacheGc(
    private val database: Database,
    private val imageCache: ImageCache,
    private val quotaBytes: Long = ServerConfig.DEFAULT_IMAGE_CACHE_QUOTA_BYTES,
    private val enabled: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    /**
     * 便宜的入口：先用索引总和预筛，只有可能超阈值时才做全盘扫描。
     *
     * 预筛偏大是**安全**的（多行共享同一份字节时 `sum` 会重复计数 ⇒ 只是更早触发真正的一轮）。
     */
    fun maybeRun(now: Long = System.currentTimeMillis(), force: Boolean = false): ImageGcResult {
        if (!enabled) return skipped(0L)
        val indexed = runCatching { imageCache.indexedBytes() }.getOrDefault(0L)
        if (!force && indexed <= highWater()) return skipped(indexed)
        return runCatching { run(now, force) }
            .onFailure { log("manga gc failed: ${it.message}") }
            .getOrElse { skipped(0L) }
    }

    /**
     * 真正跑一轮（测试直接调它，免得为了越过预筛去造 10 GiB 数据）。
     *
     * @param now 估算"14 天未访问"等时间判据的基准时刻（测试注入固定时刻）
     * @param force `true` = **连滞回预筛一起绕过**（测试与"手动清理到低水位"用）。
     *              ⚠️ `enabled = false` 是硬开关，`force` 也绕不过去（回滚必须干净）。
     */
    fun run(now: Long = System.currentTimeMillis(), force: Boolean = false): ImageGcResult {
        if (!enabled) return skipped(0L)
        val high = highWater()
        val low = lowWater()

        // 全盘扫描拿到**物理**占用：配额管的是磁盘，去重后同一份字节只占一份，
        // 用索引 sum 判定会在"多 URL 指向同一字节"时虚高。
        var used = physicalOrIndexed()
        if (!force && used <= high) return ImageGcResult(false, used, high, low, 0, 0, 0, 0)

        val protection = Protection(now)
        var freed = 0L
        var evicted = 0
        var protectedCount = 0
        var skippedCount = 0
        // 有界轮次：每轮取最冷的 [GC_BATCH] 行。既是内存上界，也防止"保护集过大导致空转"。
        var rounds = 0
        while (used > low && rounds < MAX_ROUNDS) {
            rounds++
            val candidates = runCatching { database.listMangaImagesByLastAccess(GC_BATCH) }.getOrDefault(emptyList())
                .filter { it.state == "ready" && it.cacheKey.isNotBlank() }
            if (candidates.isEmpty()) break

            protectedCount += candidates.count { protection.isProtected(it) }
            val ordered = candidates.filterNot { protection.isProtected(it) }
            skippedCount += ordered.count { !protection.isReadHistory(it) && !protection.isAbandoned(it, now) }
            // 顺位：① 已读历史 → ② 失活弃坑。两档内部都保持"最冷优先"（SQL 已按 last_access_at 升序）。
            val ranked = ordered.filter { protection.isReadHistory(it) } + ordered.filter { protection.isAbandoned(it, now) }

            val picked = mutableListOf<MangaImageRecord>()
            var projected = used
            for (row in ranked) {
                if (projected <= low) break
                picked += row
                projected -= row.byteSize.coerceAtLeast(0L)
            }
            if (picked.isEmpty()) break

            evicted += picked.size
            freed += runCatching { imageCache.evictRows(picked) }
                .onFailure { log("manga gc evict failed: ${it.message}") }
                .getOrDefault(0L)
            used = physicalOrIndexed()
        }

        val result = ImageGcResult(true, used, high, low, freed, evicted, protectedCount, skippedCount)
        log("manga gc: used=${result.usedBytes} high=$high low=$low freed=${result.freedBytes} evicted=${result.evictedRows} protected=${result.protectedRows} skipped=${result.skippedRows}")
        return result
    }

    /** 物理占用优先；目录读不到（权限/IO 异常）时退回索引总量，绝不因测量失败而放弃 GC。 */
    private fun physicalOrIndexed(): Long = runCatching { imageCache.physicalBytes() }.getOrDefault(0L)
        .takeIf { it > 0 } ?: runCatching { imageCache.indexedBytes() }.getOrDefault(0L)

    private fun skipped(indexed: Long) = ImageGcResult(false, indexed, highWater(), lowWater(), 0, 0, 0, 0)

    private fun highWater(): Long = (quotaBytes * HIGH_WATER_RATIO).toLong()
    private fun lowWater(): Long = (quotaBytes * LOW_WATER_RATIO).toLong()

    /** 保护集：pinned 书 + 每本书的滑窗（锚点章 ~ 后方 15 章）。 */
    private inner class Protection(private val now: Long) {
        private val pinned: Set<Pair<String, String>> =
            runCatching { database.listPinnedMangaBooks() }.getOrDefault(emptySet())
        private val anchorByBook: Map<Pair<String, String>, ReadingProgress> =
            runCatching { database.listReadingAnchors() }.getOrDefault(emptyList())
                .associateBy { it.sourceId to it.bookUrl }
        private val shelfCache = mutableMapOf<Pair<String, String>, Boolean>()
        private val tocSizeCache = mutableMapOf<Pair<String, String>, Int>()

        /** 免死金牌：整本书 pinned（手动"整本下载 / 离线"）。 */
        fun isPinned(row: MangaImageRecord) = (row.sourceId to row.bookUrl) in pinned

        /** 滑窗强保护：锚点章 ~ 后方 15 章（**含当前章**，当前章绝不能删）。 */
        fun inWindow(row: MangaImageRecord): Boolean {
            val anchor = anchorByBook[row.sourceId to row.bookUrl] ?: return false
            return row.chapterIndex >= anchor.chapterIndex && row.chapterIndex <= anchor.chapterIndex + WINDOW_CHAPTERS
        }

        fun isProtected(row: MangaImageRecord) = isPinned(row) || inWindow(row)

        /**
         * 顺位 1：已读完的历史冷数据。
         *
         * 两个条件都要满足：**落在锚点后方 10 章之外**（确属历史）**且全书完成度 > 80%**
         * （确属"读完了不回头"）。只看其中一个都会误伤：只看下标会删掉刚开始读的前几章，
         * 只看完成度会删掉"跳着读、后面还要回头补"的章节。
         */
        fun isReadHistory(row: MangaImageRecord): Boolean {
            if (isProtected(row)) return false
            val anchor = anchorByBook[row.sourceId to row.bookUrl] ?: return false
            if (row.chapterIndex >= anchor.chapterIndex - READ_HISTORY_GAP) return false
            return bookCompletion(anchor) >= COMPLETION_THRESHOLD
        }

        /** 顺位 2：失活弃坑的孤立预取（非 pinned、不在滑窗内，且超期未访问或已移出书架）。 */
        fun isAbandoned(row: MangaImageRecord, now: Long): Boolean {
            if (isProtected(row)) return false
            if (!onShelf(row)) return true
            return now - row.lastAccessAt > ABANDON_AFTER_MS
        }

        private fun onShelf(row: MangaImageRecord): Boolean = shelfCache.getOrPut(row.sourceId to row.bookUrl) {
            // 查不到书架信息时**当作在架**（保守：不删比误删安全）
            runCatching { database.shelfHasBook(row.sourceId, row.bookUrl) }.getOrDefault(true)
        }

        /** 全书完成度 =（当前章序 + 章内位置）/ 总章数。总章数取不到就当作 0（宁可不删）。 */
        private fun bookCompletion(anchor: ReadingProgress): Double {
            val key = anchor.sourceId to anchor.bookUrl
            val total = tocSizeCache.getOrPut(key) {
                runCatching { database.getTocCache(anchor.sourceId, anchor.bookUrl)?.size ?: 0 }.getOrDefault(0)
            }
            if (total <= 0) return 0.0
            return (anchor.chapterIndex + anchor.scrollPosition.coerceIn(0.0, 1.0)) / total
        }
    }

    private companion object {
        /** 占用 > 90% 触发（滞回上沿）。 */
        const val HIGH_WATER_RATIO = 0.90

        /** 淘汰到 75% 停止（滞回下沿）——避免磁盘在临界点反复抖动触发。 */
        const val LOW_WATER_RATIO = 0.75

        /** 滑窗强保护：锚点后方 15 章（漫画窗口；音频为 5 章）。 */
        const val WINDOW_CHAPTERS = 15

        /** 顺位 1 需要落在锚点**后方 10 章**之外。 */
        const val READ_HISTORY_GAP = 10

        /** 全书完成度阈值（顺位 1）。 */
        const val COMPLETION_THRESHOLD = 0.8

        /** 顺位 2：超过 14 天未访问即视为失活。 */
        const val ABANDON_AFTER_MS = 14L * 24 * 3600 * 1000

        /** 每轮候选行数（同时也是写事务的批量上界）。 */
        const val GC_BATCH = 1000

        /** 单轮 GC 的循环上限（保护集过大时尽早收手，绝不空转）。 */
        const val MAX_ROUNDS = 200
    }
}
