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
 * ## 回退链与阅读路径**完全同源**（issue #75）
 * 这里不再自己抄一份 `chapters → details → chapters`，而是直接调
 * [resolveBookChapters]（`POST /books/chapters` 用的就是它）。理由很硬：抄一份的下场
 * 就是两边慢慢分叉 —— 阅读路径早就会用**书架里存的 `toc_url`** 当第二跳，而预取这边
 * 一直只试 `bookUrl`，于是聚合源（`bookUrl` 只是一个详情参数载体
 * `data:;base64,…`，直接从它取目录会得到空数组）出现「能读却缓存不动」：
 * 漫画/有声点了「缓存全本」一段都没下，进度停在 0 或 1。
 *
 * 顺序见 [resolveBookChapters]：TOC 缓存 → 书架 `toc_url` 提示 → `bookUrl` →
 * `details(bookUrl).tocUrl`；命中的 key 都会写进 `book_toc_cache`
 * （`bookUrl` 与真正的 toc 各一份，与阅读路径同口径）。
 *
 * 与阅读路径唯一的**超集**：那里只在①**抛错**时回退，这里①**返回空**也回退
 * （由 [resolveBookChapters] 内部的候选循环保证）。目录缓存本来就缺失，多花一次请求
 * 是可接受的；不退这一步等于又变回"永远不动"。
 *
 * @return 目录；`null` 表示这次确实没拿到（书源不存在 / 所有候选都失败），
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
    val tocUrlHint = shelfTocUrlHint(database, sourceId, bookUrl)
    val where = "hint=${tocUrlHint?.take(64) ?: "无"}"

    val chapters = try {
        resolveBookChapters(database, runner, sourceId, bookUrl, source.json, book, tocUrlHint)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log("prefetch toc failed: $bookUrl ($where, ${error.message})")
        persistBookVariablesQuietly(database, book, bookUrl, log)
        return null
    }

    // 变量是规则求值的副作用，可能已经写进 `bookContext`；失败也要落库（静默，绝不能因此打断预取）。
    persistBookVariablesQuietly(database, book, bookUrl, log)

    if (chapters.isEmpty()) {
        log("prefetch toc skipped: 目录不可用 $bookUrl ($where)")
        return null
    }
    return chapters
}

/**
 * 书架里存的 `toc_url`（= 详情页给出的"真正的目录地址"），预取的第二跳就用它。
 *
 * 为什么必须读书架而不是相信 `bookUrl`：聚合源的 `bookUrl` 常常是
 * `data:;base64,<载荷>,{"type":"qingtian"}` 这种**参数载体**，而真正的目录地址是
 * `…,{"type":"qingtian2"}` —— 两者只差一个类型标记，但只有后者能取到目录。
 * 用户从书架点「缓存全本」时手里只有 `sourceId + bookUrl`，所以这一步必须回书架上取。
 *
 * 按 `(sourceId, bookUrl)` 精确匹配（[Database.getShelfBookByUrl] 只按 url 查，
 * 这里再校验一次 source，避免不同书源的同名 url 串书）。书不在书架上就返回 null。
 */
internal fun shelfTocUrlHint(database: Database, sourceId: String, bookUrl: String): String? =
    runCatching { database.getShelfBookByUrl(bookUrl) }
        .getOrNull()
        ?.takeIf { it.sourceId == sourceId }
        ?.tocUrl
        ?.takeIf { it.isNotBlank() && it != bookUrl }

private fun persistBookVariablesQuietly(
    database: Database,
    book: BookContext,
    bookUrl: String,
    log: (String) -> Unit,
) {
    runCatching { database.persistBookVariables(book) }
        .onFailure { log("prefetch toc persist variables failed: $bookUrl (${it.message})") }
}

/**
 * **只读**本地 TOC 缓存（不触网、非 suspend）——`enqueue` 用它决定"能不能现在就报出章数"。
 *
 * 三个 key 都试（与 [resolveBookChapters] / `/books/chapters` 的兜底口径一致）：
 * ① `bookUrl`；② `bookUrl/toc` 变体；③ **书架里存的 `toc_url`**。
 *
 * ③ 是 issue #75 补上的：目录可能只落在 `toc_url` 这个 key 上（聚合源的 `bookUrl` 与
 * `toc_url` 只差一个类型标记，但 key 不同）。少了它的后果很直观 —— 目录明明已经缓存，
 * `enqueue` 却认定"章数未知"，于是书架的「缓存全本」连总章数都报不出来。
 */
internal fun readCachedToc(database: Database, sourceId: String, bookUrl: String): List<Chapter>? {
    database.getTocCache(sourceId, bookUrl)?.takeIf { it.isNotEmpty() }?.let { return it }
    database.getTocCache(sourceId, bookUrl.removeSuffix("/toc") + "/toc")?.takeIf { it.isNotEmpty() }?.let { return it }
    val tocUrlHint = shelfTocUrlHint(database, sourceId, bookUrl) ?: return null
    return database.getTocCache(sourceId, tocUrlHint)?.takeIf { it.isNotEmpty() }
}
