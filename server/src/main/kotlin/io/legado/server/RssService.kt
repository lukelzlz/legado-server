package io.legado.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * RSS 订阅源刷新服务：抓文章列表并落库。
 *
 * ## 两条核心设计原则
 *
 * **1. 失败必须如实报出，绝不退化成「0 篇文章」。**
 * 抓不到（上游 5xx / 规则报错 / DNS 不通）与「源里确实没有文章」是**完全不同**的两件事，
 * 但两者在旧式实现里都表现为「列表 0 条」，用户无从判断。因此：
 * - 失败时写 `last_error` + `last_attempt_at`（[Database.recordRssRefreshFailure]）；
 * - 成功时写 `last_success_at` 并清空 `last_error`；
 * - 返回体里带 `failed` 与 `message`，前端可直接展示。
 *
 * **2. 并发限流加在「全局」而不是单个任务内部。**
 * 本服务在启动续做上吃过大亏（见 AGENTS.md：单任务内部限流 × 无界任务数 = 没限流），
 * 因此这里用**一个** [Semaphore] 罩住所有源的抓取，而不是每源各自限流。
 */
class RssService(
    private val database: Database,
    private val runner: RuleRunner,
) {

    /** 刷新单个源。**不抛异常** —— 失败信息随 [RssRefreshResponse] 返回，便于批量刷新不中断。 */
    suspend fun refresh(sourceUrl: String): RssRefreshResponse = withContext(Dispatchers.IO) {
        val source = database.getRssSource(sourceUrl)
            ?: return@withContext RssRefreshResponse(sourceUrl, 0, 0, failed = true, message = "订阅源不存在")
        refreshOne(source)
    }

    /** 刷新全部启用的源（可指定子集；不传则取所有启用的源）。 */
    suspend fun refreshAll(sourceUrls: List<String>? = null): List<RssRefreshResponse> = coroutineScope {
        val targets = sourceUrls
            ?.mapNotNull { database.getRssSource(it) }
            ?: database.listRssSources(enabledOnly = true)
        if (targets.isEmpty()) return@coroutineScope emptyList()
        targets.map { source -> async(Dispatchers.IO) { refreshOne(source) } }.awaitAll()
    }

    private suspend fun refreshOne(source: RssSource): RssRefreshResponse {
        val sourceUrl = source.sourceUrl
        // 单个源的规则里可能内嵌多次 java.ajax，因此按源限流而不是按「次请求」限流
        return fetchPermit.withPermit {
            try {
                val parsed = RssRuleParser.parse(RssSourceCodec.encode(source), "", 1, runner)
                val added = database.saveRssArticles(sourceUrl, parsed.articles)
                database.recordRssRefreshSuccess(sourceUrl, parsed.articles.size)
                RssRefreshResponse(sourceUrl, parsed.articles.size, added)
            } catch (error: Throwable) {
                // 失败如实落库 + 如实回报；message 取原始异常文案（含「上游返回 HTTP xxx」这类明确原因）
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                runCatching { database.recordRssRefreshFailure(sourceUrl, detail) }
                RssRefreshResponse(sourceUrl, 0, 0, failed = true, message = detail)
            }
        }
    }

    private companion object {
        /**
         * 全局并发上限。
         *
         * RSS 列表请求比书源章节抓取「重」得多（一条规则可能内嵌多次 ajax），
         * 因此取一个偏保守的值：既能让几十个源在可接受时间内刷完，又不会把上游打死。
         */
        const val MAX_CONCURRENT_REFRESH = 4
    }

    private val fetchPermit = Semaphore(MAX_CONCURRENT_REFRESH)
}
