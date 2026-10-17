package io.legado.server

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一轮自愈的结果（日志一行摘要，也是手动触发接口与单测的断言对象）。
 *
 * @param ran 本轮是否真的跑起来（被关掉 / 出异常 ⇒ false）
 * @param round 第几轮（进程内自增，用于失败退避判断）
 * @param missing 判定为「没有封面」的书总数（含本轮因上限/退避而未尝试的）
 * @param attempted 本轮真的尝试了几本
 * @param healed 其中补成功几本
 * @param failed 其中失败几本
 * @param backoffSkipped 因**失败退避**或**本轮上限**被跳过的本数
 * @param remaining 本轮结束时仍缺封面的本数（`missing - healed`，近似值）
 */
@Serializable
data class CoverSelfHealResult(
    val ran: Boolean,
    val round: Int = 0,
    val missing: Int = 0,
    val attempted: Int = 0,
    val healed: Int = 0,
    val failed: Int = 0,
    val backoffSkipped: Int = 0,
    val remaining: Int = 0,
)

/**
 * 封面**常驻自愈**：后期封面文件丢了自动补回来（用户要求「后期封面没了就自动自愈」）。
 *
 * ## 为什么必须有它（不是锦上添花）
 * 导入时的补抓只在**那一刻**有效。之后封面文件仍会消失：磁盘清理、用户误删、容器换卷、
 * 迁移数据目录只搬了库没搬 `covers/`……此时 `book_shelf.cover_key` 还在、`cover_cache.file_path`
 * 还指向一个**已经不存在的文件**，书架就变成一片裂图（前端只能退到文字占位）。
 * `cover_key` 有值不等于封面还在 —— 这个区分是本 worker 存在的全部理由。
 *
 * ## 选取条件（两种都算「没封面」）
 * 1. `book_shelf.cover_key` 为空；
 * 2. `cover_key` 有值，但按它**定位不到任何文件**（[CoverCache.file] 为空 ——
 *    `file_path` 指向的文件已不存在，且按书哈希也扫不到、旧形态 `covers/<key>` 也没有）。
 *
 * ## 与 `AudioCacheGc`/`ImageCacheGc` 同源的**启动教训**（见 AGENTS.md `[启动/缓存]`）
 * 后台保洁绝不能拖垮服务：**全局限流、分批投放、绝不阻塞启动**。因此：
 * - 由 [io.legado.server.Application] 在**启动后延迟** [ServerConfig.COVER_SELF_HEAL_START_DELAY_MS] 才投第一轮；
 * - 每轮最多 [batchLimit] 本（默认 20），并发 [concurrency]（默认 6），单本 [timeoutSeconds]（默认 15s）；
 * - 同一本失败后**退避** [retryBackoffRounds] 轮再试（避免对着一个挂掉的上游反复热点打爆）；
 * - 全程只 **读 DB + 网络抓取**，失败一律静默并只写一行日志；不渲染、不阻塞任何用户请求。
 *
 * ## 写回
 * 补成功后走 [CoverBackfiller] → [materializeCover] 这**同一条落盘路径**（导入用的也是它）：
 * `cover_key` / `cover_url` / `cover_cache.file_path` 一起写回，落盘命名仍是
 * `书名 (书哈希).扩展名`（v3）。**绝不**另起一套命名规则 —— 那会让直出路由定位不到文件。
 */
class CoverSelfHeal(
    private val database: Database,
    private val coverCache: CoverCache,
    runner: RuleRunner? = null,
    fetchDetails: ((String, String) -> BookDetails?)? = null,
    private val enabled: Boolean = true,
    /** 每轮最多尝试几本（**本轮上限**，不是总量配额；下一轮接着来）。 */
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    private val concurrency: Int = CoverBackfillRunner.CONCURRENCY,
    private val timeoutSeconds: Long = CoverBackfillRunner.TIMEOUT_SECONDS,
    /** 同一本失败后隔几轮再试。 */
    private val retryBackoffRounds: Int = DEFAULT_RETRY_BACKOFF_ROUNDS,
    private val log: (String) -> Unit = {},
) {
    private val backfiller = CoverBackfiller(database, coverCache, runner, fetchDetails)

    /** 进程内轮次计数（退避以"轮"为单位，不依赖墙钟 ⇒ 测试可注入确定的时间观）。 */
    private val rounds = AtomicInteger()

    /**
     * 失败退避表：`书标识 -> 失败时所在的轮次`。
     *
     * 用轮次而不是时间戳：巡检间隔一变（测试、运维调参），「隔 3 轮再试」的语义都成立。
     * 成功或不再需要自愈时**立刻移除**（不留下无界的记忆）。
     */
    private val lastFailureRound = ConcurrentHashMap<String, Int>()

    /** 单飞：定时巡检与手动触发撞在一起时，只让一个跑（避免同一批书被请求两遍）。 */
    private val running = AtomicBoolean(false)

    private fun note(message: String) = runCatching { log(message) }

    /**
     * 跑一轮。
     *
     * @param force `true` = 手动触发：**忽略失败退避**（用户点了"现在就补"，不该被退避挡住），
     *   但**不**忽略 [batchLimit]（一次最多补多少本是防拖垮服务的资源上限）。
     * @param limitOverride 单轮上限覆盖（手动接口的 `limit`）；<= 0 或 null ⇒ 用 [batchLimit]。
     */
    fun runOnce(force: Boolean = false, limitOverride: Int? = null): CoverSelfHealResult {
        if (!enabled) return CoverSelfHealResult(ran = false)
        // 上一轮还没跑完（手动触发撞上定时巡检）⇒ 直接让路，不重复请求同一批上游。
        if (!running.compareAndSet(false, true)) {
            note("cover self-heal: skipped (previous round still running)")
            return CoverSelfHealResult(ran = false, round = rounds.get())
        }
        val round = rounds.incrementAndGet()
        try {
            return runCatching { runRound(round, force, limitOverride) }
                .getOrElse { error ->
                    // 自愈是后台保洁，任何异常都只记一行日志：绝不允许把失败冒给用户请求。
                    note("cover self-heal failed: ${error.message}")
                    CoverSelfHealResult(ran = false, round = round)
                }
        } finally {
            running.set(false)
        }
    }

    private fun runRound(round: Int, force: Boolean, limitOverride: Int?): CoverSelfHealResult {
        val shelf = database.listBookshelf()
        // 判定「没有封面」：cover_key 为空，或按 cover_key 定位不到文件。
        // 注意必须**实际去盘上看**（coverCache.file 会走 `file_path` → 书哈希扫描 → 旧形态兜底），
        // 只看 `file_path` 非空会把"文件已被删掉"误判成"有封面"。
        val missing = shelf.filter { needsHeal(it) }
        if (missing.isEmpty()) {
            lastFailureRound.clear()
            note("cover self-heal: round=$round nothing to heal")
            return CoverSelfHealResult(ran = true, round = round)
        }
        val backoffCount = missing.count { !force && inBackoff(it, round) }
        val eligible = missing.filter { force || !inBackoff(it, round) }
        val limit = limitOverride?.takeIf { it > 0 } ?: batchLimit
        val batch = eligible.take(limit)
        val skipped = missing.size - batch.size

        var healed = 0
        var failed = 0
        CoverBackfillRunner.run(
            items = batch,
            concurrency = concurrency,
            timeoutSeconds = timeoutSeconds,
            work = { item -> runCatching { backfiller.backfill(item) }.getOrElse { CoverBackfillResult.failed(it.message) } },
        ) { item, result ->
            if (result.succeeded) {
                healed++
                lastFailureRound.remove(identityOf(item))
            } else {
                failed++
                lastFailureRound[identityOf(item)] = round
            }
        }
        // 已经不需要自愈的书（补好了 / 被用户删了 / 换了封面）从退避表里清掉，避免无界增长。
        val stillMissing = missing.map(::identityOf).toSet()
        lastFailureRound.keys.retainAll(stillMissing)

        note(
            "cover self-heal: round=$round missing=${missing.size} attempted=${batch.size} healed=$healed" +
                " failed=$failed skipped=$skipped backoff=$backoffCount remaining=${(missing.size - healed).coerceAtLeast(0)}",
        )
        return CoverSelfHealResult(
            ran = true,
            round = round,
            missing = missing.size,
            attempted = batch.size,
            healed = healed,
            failed = failed,
            backoffSkipped = skipped,
            remaining = (missing.size - healed).coerceAtLeast(0),
        )
    }

    /**
     * 这本书是不是「没有封面」。
     *
     * 刻意**不**看 `cover_url`：只有外链的书同样需要本地副本
     * （外链会过期、离线阅读会全瞎，见 AGENTS.md `[备份导入/封面]`）。
     *
     * 本地书（`loc_book`）**排除在外**：它的 `cover_key` 是导入时生成的文字占位 SVG，
     * 没有任何可抓取的外链，纳入自愈只会每一轮都白跑一次并永久计入 `failed`
     * （丢了就由「重新解析本地书 / 手动换封面」恢复，不是自愈能做的事）。
     */
    private fun needsHeal(item: BookshelfItem): Boolean {
        if (item.sourceId == LocalBookParser.LOC_BOOK_SOURCE_ID) return false
        val key = item.coverKey
        if (key.isNullOrBlank()) return true
        return runCatching { coverCache.file(key) }.getOrNull() == null
    }

    private fun inBackoff(item: BookshelfItem, round: Int): Boolean {
        val failedAt = lastFailureRound[identityOf(item)] ?: return false
        return round - failedAt <= retryBackoffRounds
    }

    private fun identityOf(item: BookshelfItem) = "${item.sourceId}\u0000${item.bookUrl}"

    private companion object {
        /** 每轮尝试上限：分批投放，绝不一次把整架书丢给上游。 */
        const val DEFAULT_BATCH_LIMIT = 20

        /** 同一本失败后隔 3 轮再试（间隔 10 分钟 ⇒ 约半小时后再碰它）。 */
        const val DEFAULT_RETRY_BACKOFF_ROUNDS = 3
    }
}
