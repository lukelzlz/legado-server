package io.legado.server

import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 一次封面补抓的**结局**（三态）。
 *
 * 为什么必须区分三态而不是「成功 / 失败」：用户与排障都需要知道这张封面是
 * **备份里那条外链**直接抓到的，还是**回源**（书源详情页给了一份新地址）救活的
 * —— 实测真实备份里备份 URL 成功 3 张、回源成功 59 张、全失败 153 张
 * （见 AGENTS.md `[备份导入/封面]`）。混成一种就没法判断回源到底有没有用。
 */
enum class CoverBackfillOutcome {
    /** 用**手上已有的外链**（导入：备份里的 `coverUrl`；自愈：库里的 `cover_url`）下载成功。 */
    DIRECT,

    /** 外链失败后**回源**（本书源详情 → 候选源）拿到新地址并下载成功。 */
    RESOURCED,

    /** 全部来源都失败（图床 404 / 签名过期 / 防盗链 / 超时 / 不是图片）。 */
    FAILED,
}

/**
 * 单本补抓结果。
 *
 * @param cacheKey 成功时落库的 `cover_key`（失败为 null）
 * @param note 失败原因的**短**标签（`timeout` / 具体异常消息），只用于日志，不进接口
 */
data class CoverBackfillResult(
    val outcome: CoverBackfillOutcome,
    val cacheKey: String? = null,
    val note: String? = null,
) {
    val succeeded: Boolean get() = outcome != CoverBackfillOutcome.FAILED

    companion object {
        fun failed(note: String? = null) = CoverBackfillResult(CoverBackfillOutcome.FAILED, null, note)
    }
}

/**
 * 「封面补抓」的**唯一实现**：三步回源 + 并发/超时驱动。
 *
 * ## 为什么抽成一个类（而不是各写一份）
 * 这条链路的**落盘命名规则**只有一份（`CoverCache` 的 v3 `书名 (书哈希).扩展名` +
 * `cover_cache.file_path`）。导入（[BackupImporter]）与常驻自愈（[CoverSelfHeal]）
 * 都必须走同一条路：自己拼文件名 = 直出路由按 v3 命名定位不到 = 书架全裂（历史事故见 SESSION-024）。
 * 因此两者共用本类，各自只负责「拿谁当 targets」与「怎么统计」。
 *
 * ## 三步来源（顺序固定）
 * 1. **手上已有的外链**（[preferredUrl]）：最快，绝大多数情况够用；
 * 2. **本书源详情页的 `coverUrl`**：备份/历史里的地址往往带**过期签名**
 *    （实测番茄 `x-expires` 早已过期 ⇒ 403），回源是唯一能"自动补上"的办法；
 * 3. **候选源**（`alternateSources`）：本书源不灵时的最后手段。
 *
 * 下载第 2/3 步拿到的地址时**必须带该书的书源请求头**（`header` + 已落库 Cookie）：
 * 详情页能过防盗链不代表裸请求也能过。
 *
 * ## 硬性约束
 * - 任何失败都只返回 [CoverBackfillOutcome.FAILED]，**绝不抛给调用方**
 *   （单本失败不许中断批量、更不许抛给用户）；
 * - **不碰元数据**：回源详情会带回书名/作者/简介，这里**一个都不写库**（补封面不是校正元数据）；
 * - 落盘只走 [CoverCache]（内建下载路径的魔数校验与 gzip 兜底解压都在它内部，不要绕过）。
 */
internal class CoverBackfiller(
    private val database: Database,
    private val coverCache: CoverCache,
    /**
     * 书源规则执行器（**仅用于回源**：拿详情页里的新 `coverUrl`）。
     *
     * 允许为 null（单测/无规则环境的降级）：此时退化成"只试手上已有的外链"，
     * 补抓本身一切照旧 —— 回源是**增强**，绝不能因为它缺席而算出错的结果。
     */
    private val runner: RuleRunner? = null,
    /** 详情页取数接缝（测试用）；缺省走 [fetchBookDetails]（书源规则 + 书籍上下文）。 */
    private val fetchDetails: ((String, String) -> BookDetails?)? = null,
) {

    /**
     * 给一本**书架里的书**补一张本地封面副本并回写 `cover_key` / `cover_url`。
     *
     * @param preferredUrl 第一步试的外链。导入传备份条目里的 `coverUrl`
     *   （它还没落库），自愈传库里的 `cover_url`。留空即跳过第一步直接回源。
     */
    fun backfill(item: BookshelfItem, preferredUrl: String? = item.coverUrl): CoverBackfillResult {
        val attempted = preferredUrl?.takeIf { usableExternalUrl(it) }
        if (attempted != null) {
            // 第一步**不带**书源请求头：历史行为就是这样（备份 URL 是图床直链），
            // 且带着书源 header 去请求另一家图床反而可能被拒。
            materialize(item, attempted)?.let { return CoverBackfillResult(CoverBackfillOutcome.DIRECT, it.key) }
        }
        for (candidate in freshCoverCandidates(item, skipUrl = attempted)) {
            val headers = headersOf(candidate.sourceId)
            materialize(item, candidate.url, headers)?.let { return CoverBackfillResult(CoverBackfillOutcome.RESOURCED, it.key) }
        }
        return CoverBackfillResult.failed()
    }

    /** 回源候选（**只读详情页，不写库**）。任何一步失败都静默跳过。 */
    private fun freshCoverCandidates(item: BookshelfItem, skipUrl: String? = null): List<CoverCandidate> {
        if (runner == null && fetchDetails == null) return emptyList()
        val candidates = mutableListOf<CoverCandidate>()
        // ① 本书源
        detailsOf(item.sourceId, item.bookUrl)?.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { candidates += CoverCandidate(it, item.sourceId) }
        // ② 候选源：先用它手里现成的 coverUrl（免费），再试它的详情页
        item.alternateSources.forEach { alternate ->
            alternate.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { candidates += CoverCandidate(it, alternate.sourceId) }
            detailsOf(alternate.sourceId, alternate.bookUrl)?.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { candidates += CoverCandidate(it, alternate.sourceId) }
        }
        return candidates
            // 已经在第一步试过的那条地址不再重复请求（失败过的东西不会因为换了个来源就成功）
            .filterNot { it.url == skipUrl }
            .filter { usableExternalUrl(it.url) }
            .distinctBy { it.url }
            .take(MAX_CANDIDATES)
    }

    /**
     * 取详情页（书源可能已失效/登录态丢失 ⇒ 静默 null，由调用方计入失败）。
     */
    private fun detailsOf(sourceId: String, bookUrl: String): BookDetails? {
        val injected = fetchDetails
        if (injected != null) return runCatching { injected(sourceId, bookUrl) }.getOrNull()
        val ruleRunner = runner ?: return null
        // loc_book 没有在线详情页，交给 fetchBookDetails 里的本地分支处理
        return runCatching { fetchBookDetails(database, ruleRunner, sourceId, bookUrl) }.getOrNull()
    }

    /** 该书源自带的请求头（`header` + 已落库的 Cookie）；拿不到就空表。 */
    private fun headersOf(sourceId: String): Map<String, String> {
        val ruleRunner = runner ?: return emptyMap()
        return runCatching { database.getSource(sourceId)?.let { ruleRunner.mediaHeaders(it.json, sourceId) } }.getOrNull().orEmpty()
    }

    /** 物化一张封面：走 [materializeCover]（v3 命名 + `file_path` + `cover_key`/`cover_url` 回写）。 */
    private fun materialize(item: BookshelfItem, url: String, headers: Map<String, String> = emptyMap()): CachedCover? =
        materializeCover(database, coverCache, item, url, headers)

    /**
     * 能否拿它当「外部封面地址」去下载。
     *
     * **必须挡住本服务自己的封面接口**（`/api/covers/<key>`）：历史上编辑弹窗在没有外部
     * URL 时会回退 `api.cover(coverKey)` 并把它写进 `cover_url`，形成自引用。
     * 拿它去请求自己的接口只会拿到 404/401（会话缺失），对这个链条毫无意义
     * （见 AGENTS.md `[数据质量/封面]`）。
     */
    private fun usableExternalUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return false
        return !SELF_COVER_PATH.containsMatchIn(trimmed)
    }

    private data class CoverCandidate(val url: String, val sourceId: String)

    private companion object {
        /** 单本书最多试几个回源候选（本书源详情 + 候选源现成 coverUrl/详情）——**资源上限**，不是数量配额。 */
        const val MAX_CANDIDATES = 4

        /** 本服务自己的封面接口路径（自引用数据必须挡住）。 */
        val SELF_COVER_PATH = Regex("""/api/covers/[0-9a-f]{64}""")
    }
}

/**
 * 批量补抓的**驱动**：并发上限 + 单本超时 + 一本失败不影响其余。
 *
 * ## 为什么不是「把 N 个任务全丢进线程池再逐个 get(timeout)」
 * 那样 `get` 的 15 秒里**包含排队时间**：任务数一多，后面还没开始跑的书就会被判超时，
 * 结果是「既没成功也没失败」的静默漏数（旧实现正是如此，只是被 300/60 的配额掩盖了）。
 * 这里改成**滑动窗口**：同时在飞的最多 [concurrency] 个，每个 `get(timeout)` 都对应
 * 一本**已经真正开始跑**的书 ⇒ 超时语义准确，且一本失败只计一本。
 *
 * ## 线程性质
 * - 线程是 **daemon**：后台补抓绝不能阻止进程退出（进程退出可中断，重启后由
 *   [CoverSelfHeal] 接续）；
 * - [shouldContinue] 在**每本书开跑之前**检查一次，让调用方（协程作用域）能及时取消
 *   —— 阻塞式网络 I/O 本身不可中断，所以在书与书之间收手。
 */
internal object CoverBackfillRunner {

    /** 全局并发上限（同时下载/回源的封面上限）。**资源上限保留**：它不是数量配额，是防止拖垮服务。 */
    const val CONCURRENCY = 6

    /** 单本超时（秒）。**资源上限保留**：图床卡住时不许拖住整批。 */
    const val TIMEOUT_SECONDS = 15L

    fun <T> run(
        items: List<T>,
        concurrency: Int = CONCURRENCY,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
        shouldContinue: () -> Boolean = { true },
        work: (T) -> CoverBackfillResult,
        onDone: (T, CoverBackfillResult) -> Unit,
    ) {
        if (items.isEmpty()) return
        val width = concurrency.coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(width) { runnable ->
            Thread(runnable, "cover-backfill").apply { isDaemon = true }
        }
        try {
            val queue = ArrayDeque(items)
            val inFlight = ArrayDeque<Pair<T, Future<CoverBackfillResult>>>()
            while (true) {
                while (inFlight.size < width && queue.isNotEmpty()) {
                    if (!shouldContinue()) {
                        queue.clear()
                        break
                    }
                    val next = queue.removeFirst()
                    inFlight += next to pool.submit(Callable { work(next) })
                }
                if (inFlight.isEmpty()) break
                val (item, future) = inFlight.removeFirst()
                val result = runCatching { future.get(timeoutSeconds, TimeUnit.SECONDS) }
                    .getOrElse { error ->
                        // 超时/任务内异常都只算这一本失败：取消掉别再占着线程，也绝不中断整批。
                        future.cancel(true)
                        CoverBackfillResult.failed(
                            if (error is java.util.concurrent.TimeoutException) "timeout" else (error.cause?.message ?: error.message),
                        )
                    }
                onDone(item, result)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
