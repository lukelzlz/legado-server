package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「导入时**全补**封面、**不限数量**」的用户要求（原话：A+B，导入时优先全补上，不限数量上限）。
 *
 * ## 这条用例抓的是哪一类缺陷
 * 曾经的实现有两道**数量配额**：
 * - `MAX_COVER_BACKFILL = 300`（补抓总数）⇒ 超过 300 本的书**永远不补**；
 * - `MAX_COVER_RESOURCE = 60`（回源次数）⇒ 实测真实 220 本备份里「回源成功 59、仍失败 153」，
 *   60 次预算正是回源被截断的原因（AGENTS.md `[备份导入/封面]`）。
 *
 * 配额去掉之后还多了一个必须锁死的属性：**导入接口不许被补抓拖住**（否则 400 本备份
 * 会在浏览器里"卡几分钟"）。因此补抓在 [BackupImporter] 里改成后台协程 fire-and-forget，
 * 摘要只如实回报「已受理 N 本」（`coversQueued`）。
 *
 * 全部用例**不触网**：封面下载走 `CoverCache` 的 `fetcher` 接缝，回源走 `fetchDetails` 接缝。
 */
class CoverBackfillUnboundedTest {

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private val sourceId = "https://cover.example.com"

    /** 备份里的一条假书（bookUrl 必须**唯一**：主键是 `(source_id, book_url)`）。 */
    private data class TestBook(val name: String, val bookUrl: String, val coverUrl: String?)

    private fun books(count: Int, cover: (Int) -> String? = { "https://img.example.com/cover-$it.png" }): List<TestBook> =
        (1..count).map { i -> TestBook("书$i", "$sourceId/book/$i", cover(i)) }

    // ------------------------------------------------------------------
    // ① 补抓总数不再封顶 300
    // ------------------------------------------------------------------

    /**
     * 310 本书（**超过旧的 300 上限**）、备份 URL 全部可用 ⇒ 必须 310 本都补上。
     *
     * 旧实现在这里只补前 300 本，后 10 本 `cover_key` 永远是空 —— 用户看到的就是
     * 「有些书有封面、有些没有」，而且没有任何上限提示。
     */
    @Test
    fun `导入补抓不再有 300 本的总数上限`() {
        val shelf = books(310)
        withImport(shelf, fetcher = { "image/png" to pngBytes }) { database, _, summary, _ ->
            assertEquals("310 本必须全部补上（旧的 300 上限会漏掉 10 本）", 310, summary.coversFetched)
            assertEquals(0, summary.coversFailed)
            assertEquals("同步模式没有「受理」概念", 0, summary.coversQueued)
            val covered = database.listBookshelf().count { !it.coverKey.isNullOrBlank() }
            assertEquals("库里 310 行都必须有 cover_key", 310, covered)
        }
    }

    // ------------------------------------------------------------------
    // ② 回源次数不再封顶 60
    // ------------------------------------------------------------------

    /**
     * 70 本书：备份 `coverUrl` 已失效（403）、但书源详情能给出**新地址** ⇒ 必须 70 本全部回源成功。
     *
     * 旧实现有 `MAX_COVER_RESOURCE = 60`：第 61 本起直接记失败（日志写
     * `re-source budget exhausted`），实测真实备份正是这个形态（回源成功 59、仍失败 153）。
     */
    @Test
    fun `导入回源不再有 60 次的预算上限`() {
        val shelf = books(70) { "https://img.example.com/expired-$it.png" }
        val detailsCalls = AtomicInteger()
        withImport(
            shelf,
            // 备份里的老地址全部 403；回源拿到的新地址可下载
            fetcher = { url ->
                if (url.contains("expired-")) throw IllegalArgumentException("封面上游返回 HTTP 403")
                "image/png" to pngBytes
            },
            fetchDetails = { _, url ->
                detailsCalls.incrementAndGet()
                val freshUrl = "https://img.example.com/fresh-" + url.substringAfterLast('/') + ".png"
                BookDetails(sourceId = sourceId, name = "详情书名", coverUrl = freshUrl, tocUrl = url)
            },
        ) { database, _, summary, _ ->
            assertEquals("70 本都必须真的尝试回源（旧的 60 预算会截断）", 70, detailsCalls.get())
            assertEquals("70 本全部补上", 70, summary.coversFetched)
            assertEquals("其中 70 张来自回源", 70, summary.coversResourced)
            assertEquals("没有任何一本因为「预算耗尽」而失败", 0, summary.coversFailed)
            assertEquals(70, database.listBookshelf().count { !it.coverKey.isNullOrBlank() })
        }
    }

    // ------------------------------------------------------------------
    // ③ 导入不被补抓阻塞（fire-and-forget）
    // ------------------------------------------------------------------

    /**
     * 给了后台作用域 ⇒ **导入立刻返回**，封面在后台继续抓。
     *
     * 用一个「卡住不放」的 fetcher 来证明导入真的没等它：导入必须先返回（摘要里
     * `coversQueued > 0` / `coversFetched == 0`），随后放开闩锁，后台才把封面落库。
     */
    @Test
    fun `有后台作用域时导入立刻返回，封面在后台补完`() {
        val shelf = books(3)
        val gate = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withImport(
                shelf,
                fetcher = {
                    // 卡住补抓，直到测试放开：如果导入是同步的，这一步会直接死锁
                    gate.await(20, TimeUnit.SECONDS)
                    "image/png" to pngBytes
                },
                scope = scope,
            ) { database, _, summary, importer ->
                assertEquals("导入必须先返回，把 3 本全部受理下来", 3, summary.coversQueued)
                assertEquals("返回时还没来得及抓完（如实为 0）", 0, summary.coversFetched)
                assertEquals("库里此刻还没有封面", 3, database.listBookshelf().count { it.coverKey.isNullOrBlank() })

                // 放开后台线程，等它跑完
                gate.countDown()
                runBlocking { importer.awaitCoverBackfill() }

                val covered = database.listBookshelf().count { !it.coverKey.isNullOrBlank() }
                assertEquals("后台必须把 3 本都补上", 3, covered)
            }
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }

    /**
     * 后台作用域**已被取消**（例如进程正在退出）⇒ 补抓不再开工，但**导入本身照常成功**。
     *
     * 这是「进程退出可中断」的可测形态：补抓随时可以被丢掉，绝不能反过来把导入、或
     * 把 JVM 退出拖住（线程是 daemon 的）。
     */
    @Test
    fun `作用域已取消时不补抓但导入照常成功`() {
        val shelf = books(5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.cancel()
        val fetched = AtomicInteger()
        try {
            withImport(
                shelf,
                fetcher = { fetched.incrementAndGet(); "image/png" to pngBytes },
                scope = scope,
            ) { database, _, summary, importer ->
                runBlocking { importer.awaitCoverBackfill() }
                assertEquals("导入必须照常成功", 5, summary.books)
                assertEquals("受理数如实回报", 5, summary.coversQueued)
                assertEquals("作用域已取消 ⇒ 一张都不抓", 0, fetched.get())
                assertEquals(5, database.listBookshelf().count { it.coverKey.isNullOrBlank() })
            }
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // ④ 驱动本身：单本超时只算一本，绝不中断整批
    // ------------------------------------------------------------------

    /**
     * [CoverBackfillRunner] 的滑动窗口：**单本超时只影响那一本**。
     *
     * 这同时锁住一个隐蔽缺陷：旧实现是「N 个任务全丢进线程池再逐个 `get(15s)`」，
     * `get` 的 15 秒里**包含排队时间** ⇒ 任务一多，排在后面的书根本没开始跑就被判超时，
     * 结果是「既没成功也没失败」的静默漏数（被 300/60 配额掩盖了很久）。
     */
    @Test
    fun `单本超时只计一本失败且不影响其余`() {
        val slow = CountDownLatch(1)
        val outcomes = mutableListOf<Pair<Int, CoverBackfillOutcome>>()
        CoverBackfillRunner.run(
            items = (1..6).toList(),
            concurrency = 2,
            timeoutSeconds = 1,
            work = { item ->
                if (item == 1) slow.await(10, TimeUnit.SECONDS)
                when (item) {
                    1 -> CoverBackfillResult.failed("timeout")
                    2 -> CoverBackfillResult.failed("上游 403")
                    else -> CoverBackfillResult(CoverBackfillOutcome.DIRECT, cacheKey = "k$item")
                }
            },
        ) { item, result -> outcomes += item to result.outcome }
        slow.countDown()

        assertEquals("6 本必须各有一个结局（旧实现会静默漏数）", 6, outcomes.size)
        assertEquals("被卡住的那本判超时失败", CoverBackfillOutcome.FAILED, outcomes.first { it.first == 1 }.second)
        assertEquals("抛错的那本判失败", CoverBackfillOutcome.FAILED, outcomes.first { it.first == 2 }.second)
        assertEquals(
            "其余 4 本照常成功（一本失败绝不中断整批）",
            4,
            outcomes.count { it.second == CoverBackfillOutcome.DIRECT },
        )
    }

    /** 空清单不启动线程、不加计数（自愈 worker 每轮都会走到这条路径）。 */
    @Test
    fun `空清单直接返回`() {
        val calls = AtomicInteger()
        CoverBackfillRunner.run<String>(items = emptyList(), work = { CoverBackfillResult.failed() }) { _, _ -> calls.incrementAndGet() }
        assertEquals(0, calls.get())
    }

    // ------------------------------------------------------------------
    // ⑤ 三态日志与「失败不影响导入」的老契约仍然成立
    // ------------------------------------------------------------------

    /**
     * 回源成功必须**同时**把新地址写回 `cover_url`（否则前端外链兜底仍是那条死链），
     * 且落盘仍是 v3 命名（`书名 (书哈希).png`）——导入与自愈共用的那条路径。
     */
    @Test
    fun `回源成功写回 cover_url 并按 v3 命名落盘`() {
        val book = TestBook("单本", "$sourceId/book/single", "https://img.example.com/expired.png")
        withImport(
            listOf(book),
            fetcher = { url ->
                if (url.contains("expired")) throw IllegalArgumentException("封面上游返回 HTTP 403")
                "image/png" to pngBytes
            },
            fetchDetails = { _, url ->
                BookDetails(sourceId = sourceId, name = "详情书名（不该写库）", coverUrl = "https://img.example.com/fresh.png", tocUrl = url)
            },
        ) { database, coverDir, summary, _ ->
            assertEquals(1, summary.coversResourced)
            val item = database.listBookshelf().single()
            assertEquals("外链必须换成回源得到的新地址", "https://img.example.com/fresh.png", item.coverUrl)
            assertEquals("书名的权威来源是备份，不是详情页", "单本", item.name)
            val expected = "单本 (${bookCacheHash(sourceId, book.bookUrl)}).png"
            assertEquals("落盘文件名必须是 v3（导入不得自己拼命名）", expected, database.coverFilePath(item.coverKey!!))
            assertTrue("文件必须真的在盘上", Files.isRegularFile(coverDir.resolve(expected)))
        }
    }

    /** 后台模式下的摘要绝不谎报：`coversFetched/coversFailed` 为 0，受理数单独如实回报。 */
    @Test
    fun `后台模式摘要只回报受理数不谎报成功数`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withImport(books(2), fetcher = { "image/png" to pngBytes }, scope = scope) { _, _, summary, importer ->
                runBlocking { importer.awaitCoverBackfill() }
                assertEquals(2, summary.coversQueued)
                assertEquals(0, summary.coversFetched)
                assertEquals(0, summary.coversFailed)
                assertEquals(0, summary.coversResourced)
            }
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun withImport(
        shelf: List<TestBook>,
        fetcher: (String) -> Pair<String, ByteArray>,
        fetchDetails: ((String, String) -> BookDetails?)? = null,
        scope: CoroutineScope? = null,
        block: (Database, Path, BackupImportSummary, BackupImporter) -> Unit,
    ) {
        val directory = Files.createTempDirectory("legado-unbounded")
        val database = Database(directory.resolve("legado.sqlite").toString())
        val coverDir = directory.resolve("covers")
        try {
            database.initialize("password-for-test")
            val cache = CoverCache(coverDir, database = database, fetcher = fetcher)
            val archive = directory.resolve("backup.zip")
            writeArchive(archive, shelf)
            val importer = BackupImporter(
                database, cache,
                fetchDetails = fetchDetails,
                coverBackfillScope = scope,
            )
            val summary = importer.import(archive)
            block(database, coverDir, summary, importer)
        } finally {
            database.close()
            val dbPath = directory.resolve("legado.sqlite").toString()
            listOf("", "-wal", "-shm").forEach { runCatching { Files.deleteIfExists(Path.of(dbPath + it)) } }
            runCatching { directory.toFile().deleteRecursively() }
        }
    }

    private fun writeArchive(target: Path, shelf: List<TestBook>) {
        val books = shelf.joinToString(",") { book ->
            val cover = book.coverUrl?.let { """"coverUrl":"$it",""" }.orEmpty()
            """{"name":"${book.name}","author":"作者","bookUrl":"${book.bookUrl}","origin":"$sourceId",""" +
                """"tocUrl":"${book.bookUrl}",$cover"type":8,"group":0,"durChapterIndex":1,"durChapterTime":1790000000000}"""
        }
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("bookSource.json"))
            zip.write("""[{"bookSourceUrl":"$sourceId","bookSourceName":"封面源","enabled":true}]""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bookshelf.json"))
            zip.write("[$books]".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
