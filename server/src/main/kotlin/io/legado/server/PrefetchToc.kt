package io.legado.server

import kotlinx.coroutines.CancellationException

/**
 * 预取（漫画 / 有声书）的目录取数：**先看本地 TOC 缓存，缺失就自己抓**。
 *
 * ## 为什么必须自己抓（用户反馈「只有打开书了进度才动」的根因）
 * 旧实现只读 `database.getTocCache(...)`：从书架点「缓存全本」时，如果这本书的目录
 * 还没被**阅读器**抓过（即没落进 `book_toc_cache`），`enqueue` 直接把"目录没缓存"
 * 当成"没事可做"返回 0 ⇒ 路由回 `nothing_to_prefetch` ⇒ 预取永远不启动。
 * 只有点开这本书、阅读器抓完目录并落库之后，预取才会跑 —— 这正是用户看到的
 * "只有打开书了进度才动"。
 *
 * ## 回退链与阅读路径（`Routes.kt` 的 `POST /books/chapters`）同源
 * ① `runner.chapters(source.json, bookUrl, bookContext)`；
 * ② 为空 / 抛错 ⇒ `runner.details(...)` 拿 `details.tocUrl`，再 `runner.chapters(tocUrl)`；
 * ③ 成功后 `saveTocCache(bookUrl, chapters)` +（tocUrl 不同时）`saveTocCache(tocUrl, chapters)`
 *    并 `persistBookVariables(bookContext)`（规则里写进书籍变量的值必须留下来，
 *    与在线阅读、`BookCacheService` 完全同口径）。
 *
 * 与阅读路径唯一的**超集**：那里只在①**抛错**时回退，这里①**返回空**也回退。
 * 目录缓存本来就缺失，多花一次请求是可接受的；而聚合源的 `bookUrl` 常是详情页参数载体
 * （`data:;base64,…`），从它取目录得到空数组是常态 —— 不退这一步等于又变回"永远不动"。
 *
 * @return 目录；`null` 表示这次确实没拿到（书源不存在 / 两跳规则都失败），
 *         调用方应当把任务收掉，而不是反复重试打上游。
 */
internal suspend fun ensurePrefetchChapters(
    database: Database,
    runner: RuleRunner,
    sourceId: String,
    bookUrl: String,
    log: (String) -> Unit,
): List<Chapter>? {
    readCachedToc(database, sourceId, bookUrl)?.let { return it }

    val source = database.getSource(sourceId) ?: run {
        log("prefetch toc skipped: 书源不存在 $sourceId")
        return null
    }
    val book = database.bookContext(sourceId, bookUrl)

    var chapters: List<Chapter> = emptyList()
    try {
        chapters = runner.chapters(source.json, bookUrl, book)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log("prefetch toc failed: $bookUrl (${error.message})")
    }

    if (chapters.isEmpty()) {
        // 第二跳：bookUrl 取不到目录 ⇒ 换成详情页给的 tocUrl（与 `/books/chapters` 的兜底一致）。
        try {
            val details = runner.details(source.json, bookUrl, book)
            val tocUrl = details.tocUrl.ifBlank { bookUrl }
            chapters = runner.chapters(source.json, tocUrl, book)
            if (chapters.isNotEmpty() && tocUrl != bookUrl) {
                runCatching { database.saveTocCache(sourceId, tocUrl, chapters) }
                    .onFailure { log("prefetch toc save failed: $tocUrl (${it.message})") }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log("prefetch toc fallback failed: $bookUrl (${error.message})")
        }
    }

    // 变量是规则求值的副作用，可能已经写进 `bookContext`；失败也要落库（失败静默，绝不能因此打断预取）。
    runCatching { database.persistBookVariables(book) }
        .onFailure { log("prefetch toc persist variables failed: $bookUrl (${it.message})") }

    if (chapters.isEmpty()) return null
    runCatching { database.saveTocCache(sourceId, bookUrl, chapters) }
        .onFailure { log("prefetch toc save failed: $bookUrl (${it.message})") }
    return chapters
}

/**
 * **只读**本地 TOC 缓存（不触网、非 suspend）——`enqueue` 用它决定"能不能现在就报出章数"。
 *
 * 两个 key 都试：`bookUrl` 与 `bookUrl/toc` 变体（与 `/books/chapters` 的兜底口径一致）。
 */
internal fun readCachedToc(database: Database, sourceId: String, bookUrl: String): List<Chapter>? {
    val cached = database.getTocCache(sourceId, bookUrl)
        ?: database.getTocCache(sourceId, bookUrl.removeSuffix("/toc") + "/toc")
    return cached?.takeIf { it.isNotEmpty() }
}
