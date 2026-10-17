package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2 的后台预取（环节 E8）。
 *
 * 这些用例锁定的都是"预取把阅读搞坏"的经典形态：
 * - 预取失败冒泡 ⇒ 用户明明只想看当前章，却被一个过期图片搞成报错弹窗；
 * - 二次预取又抓一遍 ⇒ 上游请求数不降反增（预取的意义全没了）；
 * - 目录没缓存就闷头跑 ⇒ 每章都白解析一遍，日志刷屏且毫无产出；
 * - 关掉开关还在跑 ⇒ 回滚开关形同虚设。
 *
 * 全程**不触网**：抓取走 `MangaImageFetcher` 的注入接缝，清单走已缓存的 L2（P1 的能力）。
 */
class MangaPrefetchServiceTest {

    private val sourceJson = """{
        "bookSourceUrl": "https://manga.example.com",
        "bookSourceName": "测试漫画源",
        "bookSourceType": 2,
        "header": "{\"Referer\":\"https://manga.example.com/\"}",
        "ruleBookInfo": {"tocUrl": "$.toc"},
        "ruleToc": {"chapterList": "$.chapters", "chapterName": "$.name", "chapterUrl": "$.url"},
        "ruleContent": {"content": "$.images"}
    }"""

    private companion object {
        const val BOOK_URL = "https://manga.example.com/book/1"
        const val TOC_URL = "https://manga.example.com/book/1/toc"
        const val CHAPTER_0 = "https://manga.example.com/ch/0"
        const val CHAPTER_1 = "https://manga.example.com/ch/1"
    }

    @Test
    fun `prefetches a whole chapter and marks it ready for the green dot`() {
        withService { ctx ->
            val urls = (0..2).map { "https://img.example/$it.jpg" }
            ctx.seedChapter(0, urls)

            val planned = ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 0, ahead = 0)

            assertEquals(1, planned)
            assertTrue("必须等到整章就绪", ctx.awaitReady("ch-0"))
            urls.forEach { assertNotNull("每张图都必须落进 L1", ctx.cache.get(ctx.sourceId, it)) }
            assertEquals(
                "整章全部就绪 ⇒ 目录绿点（唯一口径）",
                listOf("ch-0"),
                ctx.database.listReadyMangaChapterUrls(ctx.sourceId, ctx.bookUrl),
            )
        }
    }

    @Test
    fun `prefetches the next chapters ahead`() {
        withService { ctx ->
            ctx.seedChapter(0, (0..1).map { "https://img.example/a-$it.jpg" })
            ctx.seedChapter(1, (0..1).map { "https://img.example/b-$it.jpg" })

            val planned = ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 0, ahead = 1)

            assertEquals(2, planned)
            assertTrue(ctx.awaitReady("ch-1"))
            assertEquals(listOf("ch-0", "ch-1"), ctx.database.listReadyMangaChapterUrls(ctx.sourceId, ctx.bookUrl))
        }
    }

    @Test
    fun `second prefetch makes zero upstream calls`() {
        withService { ctx ->
            val urls = (0..2).map { "https://img.example/$it.jpg" }
            ctx.seedChapter(0, urls)
            ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, 0, 0)
            assertTrue(ctx.awaitReady("ch-0"))
            val afterFirst = ctx.fetches.get()
            assertEquals(3, afterFirst)

            ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, 0, 0)
            assertTrue(ctx.awaitReady("ch-0"))
            ctx.awaitIdle()

            assertEquals("已就绪的图绝不能再抓一次（预取的全部意义就在这里）", afterFirst, ctx.fetches.get())
        }
    }

    @Test
    fun `accepts the task and fetches the toc itself when it is not cached`() {
        withService { ctx ->
            // 没有目录缓存、书源也没有目录规则 ⇒ 预取仍然**受理**（返回 ≥1），
            // 只是这次抓不到目录 ⇒ 不做（绝不返回 0 让上层误判成"没事可做"）。
            // 这是"只有打开书了进度才动"的回归锁：旧实现在这里返回 0。
            assertEquals(1, ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, 0, 3))
            assertEquals("抓不到目录就不能白抓图片", 0, ctx.fetches.get())
        }
    }

    /**
     * 问题 A 的回归：**目录没被阅读器缓存过**，预取也要自己把目录抓下来并把整章缓存完。
     *
     * 旧行为：`enqueue` 只读 `book_toc_cache` ⇒ 缺失就返回 0 ⇒ 路由回 `nothing_to_prefetch`
     * ⇒ 从书架点"缓存全本"什么都不发生，直到用户点开这本书。
     */
    @Test
    fun `fetches the toc with the reading path fallback and caches the whole book`() {
        val responses = mutableMapOf(
            // ① 从 bookUrl 取目录：详情页参数载体，`$.chapters` 取到空数组（聚合源常见形态）
            BOOK_URL to """{"toc":"$TOC_URL","chapters":[]}""",
            // ② 回退到 details.tocUrl 才拿到真正的目录
            TOC_URL to """{"chapters":[{"name":"第1话","url":"$CHAPTER_0"},{"name":"第2话","url":"$CHAPTER_1"}]}""",
            CHAPTER_0 to """{"images":["https://img.example/1-0.jpg","https://img.example/1-1.jpg"]}""",
            CHAPTER_1 to """{"images":["https://img.example/2-0.jpg","https://img.example/2-1.jpg","https://img.example/2-2.jpg"]}""",
        )
        val runner = RuleRunner { url -> responses[url] ?: error("Unexpected URL: $url") }
        withService(runner = runner) { ctx ->
            assertTrue("前置条件：目录确实没有缓存", ctx.database.getTocCache(ctx.sourceId, ctx.bookUrl).isNullOrEmpty())

            val accepted = ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 0, ahead = 1)

            assertEquals("目录未知时也要受理（≥1）", 1, accepted)
            assertTrue("整章都要落盘（不打开书也能缓存）", ctx.awaitReady(CHAPTER_1))
            assertEquals(
                "两章都必须就绪",
                listOf(CHAPTER_0, CHAPTER_1),
                ctx.database.listReadyMangaChapterUrls(ctx.sourceId, ctx.bookUrl),
            )
            assertEquals("2 + 3 张图都抓到了", 5, ctx.fetches.get())
            assertEquals(
                "目录必须落进 book_toc_cache（阅读器不必再抓一次）",
                2,
                ctx.database.getTocCache(ctx.sourceId, ctx.bookUrl)?.size,
            )
            assertEquals(
                "详情页给的 tocUrl 也要存一份（与 /books/chapters 同口径）",
                2,
                ctx.database.getTocCache(ctx.sourceId, TOC_URL)?.size,
            )
        }
    }

    /**
     * 问题 B 的回归：**`done/planned` 是章口径，图片张数单独用 `doneImages/plannedImages`**。
     *
     * 用户反馈「进度写的是章节，实际是每章图片的进度」（51 章显示成 `83/136`）。
     * 这里让第 2 章的抓取卡住，在"第 1 章已完成、第 2 章在飞"的瞬间读一次状态：
     * 2 章 / 3+3 张 ⇒ `planned=2`、`plannedImages=6`、`done=1`、`doneImages=3`。
     */
    @Test
    fun `status reports chapters for done planned and images for doneImages plannedImages`() {
        val blocking = CountDownLatch(1)
        val started = CountDownLatch(1)
        withService(beforeFetch = { url ->
            if (url.contains("/2-")) {
                started.countDown()
                assertTrue("等测试读状态", blocking.await(15, TimeUnit.SECONDS))
            }
        }) { ctx ->
            ctx.seedChapter(0, (0..2).map { "https://img.example/1-$it.jpg" })
            ctx.seedChapter(1, (0..2).map { "https://img.example/2-$it.jpg" })

            assertEquals(2, ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 0, ahead = 1))
            assertTrue("第 2 章必须真的在飞", started.await(15, TimeUnit.SECONDS))

            val snapshot = ctx.service.status().single()
            assertEquals("planned = 规划**章数**", 2, snapshot.planned)
            assertEquals("done = 已完成**章数**（第 1 章整章落盘，第 2 章在飞）", 1, snapshot.done)
            assertEquals("plannedImages = 规划**图片**张数（细节口径）", 6, snapshot.plannedImages)
            assertEquals("doneImages = 已落盘**图片**张数（细节口径）", 3, snapshot.doneImages)

            blocking.countDown()
            // 注意：seedChapter 造的章节地址是 `ch-N`（本用例的目录由 seedChapter 写进缓存）
            assertTrue(ctx.awaitReady("ch-1"))
            assertTrue("全部完成后不应再有在跑任务", ctx.awaitIdle())
            assertEquals(
                "6 张图都 ready（图片口径的最终值）",
                6,
                ctx.database.listMangaImagesForChapter(ctx.sourceId, ctx.bookUrl, "ch-0").count { it.state == "ready" } +
                    ctx.database.listMangaImagesForChapter(ctx.sourceId, ctx.bookUrl, "ch-1").count { it.state == "ready" },
            )
        }
    }

    /** 自动单章预取（`ahead=0`）不得把"全本"顶窄：否则批量任务被切成一条条单章（进度看着不动）。 */
    @Test
    fun `automatic single chapter prefetch does not narrow a running bulk task`() {
        val blocking = CountDownLatch(1)
        val started = CountDownLatch(1)
        withService(beforeFetch = { url ->
            if (url.contains("/1-")) {
                started.countDown()
                assertTrue(blocking.await(15, TimeUnit.SECONDS))
            }
        }) { ctx ->
            ctx.seedChapter(0, (0..2).map { "https://img.example/1-$it.jpg" })
            ctx.seedChapter(1, (0..2).map { "https://img.example/2-$it.jpg" })
            ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 0, ahead = 1)
            assertTrue(started.await(15, TimeUnit.SECONDS))

            // 阅读器此刻投递"下一章"（ahead=0）：它落在批量区间里 ⇒ 忽略，批量继续
            assertEquals(1, ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 1, ahead = 0))
            assertEquals("批量任务的规划章数不能被顶成 1", 2, ctx.service.status().single().planned)

            blocking.countDown()
            assertTrue("批量任务必须继续跑完第 2 章", ctx.awaitReady("ch-1"))
        }
    }

    /**
     * 回归：**入队那一刻就必须能被 `status()` 看到**。
     *
     * 书架的 `cacheState='caching'`（以及前端那个「只要不是 caching 就停表」的轮询门）
     * 都只认 `status()`。旧实现只在 `drain` 里第一章清单解析完才第一次写 `progress`
     * ⇒ 入队后有一段（抓目录时可达数秒）状态为空 ⇒ 书架列表报 `idle/ready` ⇒
     * 前端轮询**永久熄火**，用户看到的就是「退回书架后缓存不动了」。
     *
     * 真机实测（8099 实例、真实书架）：入队后 53ms / 265ms 两次采样都还是 `ready`。
     */
    @Test
    fun `reports a running task immediately after enqueue`() {
        val blocking = CountDownLatch(1)
        val started = CountDownLatch(1)
        withService(beforeFetch = { _ ->
            started.countDown()
            blocking.await(15, TimeUnit.SECONDS)
        }) { ctx ->
            ctx.seedChapter(0, (0..2).map { "https://img.example/$it.jpg" })

            assertEquals(1, ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 0, ahead = 0))

            // 刻意**不等**抓完：入队返回后立刻查状态（旧实现在这里 `single()` 会抛 NoSuchElementException）
            val snapshot = ctx.service.status().single()
            assertEquals(ctx.sourceId, snapshot.sourceId)
            assertEquals("目录已知 ⇒ planned 立刻就是章数", 1, snapshot.planned)
            assertEquals("还没抓完 ⇒ done 必须是 0", 0, snapshot.done)
            assertTrue("必须如实报 running", snapshot.running)

            blocking.countDown()
            assertTrue(ctx.awaitReady("ch-0"))
        }
    }

    /**
     * 回归：目录**还没抓到**时也要先登账（`planned=0` 表示"章数未知"）。
     *
     * 锁的是「不打开书就点缓存」的最坏时序：抓目录要几跳网络请求，期间状态接口绝不能是空的
     * ——否则书架列表会先报一次 `idle`，把前端的轮询门关掉。
     */
    @Test
    fun `reports a running task while it is still fetching the toc`() {
        val blocking = CountDownLatch(1)
        val started = CountDownLatch(1)
        val runner = RuleRunner { url ->
            started.countDown()
            blocking.await(15, TimeUnit.SECONDS)
            throw IllegalStateException("目录抓不到: $url")
        }
        withService(runner = runner) { ctx ->
            assertTrue("前置条件：目录确实没有缓存", ctx.database.getTocCache(ctx.sourceId, ctx.bookUrl).isNullOrEmpty())

            ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, fromChapterIndex = 0, ahead = 3)
            assertTrue("预取必须真的去抓目录", started.await(15, TimeUnit.SECONDS))

            val snapshot = ctx.service.status().single()
            assertEquals("目录未知 ⇒ planned=0（未知），不能被当成 0 章", 0, snapshot.planned)
            assertEquals(0, snapshot.done)
            blocking.countDown()
        }
    }

    @Test
    fun `is a hard no-op when disabled`() {
        withService(enabled = false) { ctx ->
            ctx.seedChapter(0, listOf("https://img.example/0.jpg"))

            assertEquals(0, ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, 0, 0))
            assertEquals(0, ctx.fetches.get())
            assertTrue(ctx.database.listReadyMangaChapterUrls(ctx.sourceId, ctx.bookUrl).isEmpty())
        }
    }

    @Test
    fun `swallows upstream failures without throwing`() {
        withService(failFetches = true) { ctx ->
            val urls = (0..2).map { "https://img.example/$it.jpg" }
            ctx.seedChapter(0, urls)

            val planned = ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, 0, 0)

            assertEquals("受理成功，但抓取会失败——这不影响调用方", 1, planned)
            assertTrue(ctx.awaitAllFailed("ch-0"))
            val rows = ctx.database.listMangaImagesForChapter(ctx.sourceId, ctx.bookUrl, "ch-0")
            assertEquals(3, rows.count { it.state == "failed" })
            assertEquals(
                "有失败且无在途任务 ⇒ partial（不是 ready，绿点绝不能亮）",
                "partial",
                ctx.database.getMangaChapter(ctx.sourceId, ctx.bookUrl, "ch-0")?.state,
            )
            assertTrue(ctx.database.listReadyMangaChapterUrls(ctx.sourceId, ctx.bookUrl).isEmpty())
        }
    }

    @Test
    fun `pin marks the book as never evictable`() {
        withService { ctx ->
            ctx.seedChapter(0, listOf("https://img.example/0.jpg"))

            ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, 0, 0, pin = true)
            assertTrue(ctx.awaitReady("ch-0"))

            assertTrue(
                "整本离线 ⇒ 免死金牌（GC 的 listPinnedMangaBooks 必须看得见）",
                (ctx.sourceId to ctx.bookUrl) in ctx.database.listPinnedMangaBooks(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private class Ctx(
        val database: Database,
        val cache: ImageCache,
        val service: MangaPrefetchService,
        val fetches: AtomicInteger,
        val sourceId: String = "https://manga.example.com",
        val bookUrl: String = "https://manga.example.com/book/1",
    ) {
        /** 造第 index 章的 L2 清单（等价于"用户刚才打开过这一章"）。 */
        fun seedChapter(index: Int, urls: List<String>) {
            database.saveMangaChapter(
                MangaChapterRecord(
                    sourceId = sourceId, bookUrl = bookUrl, chapterUrl = "ch-$index", chapterIndex = index,
                    imagesJson = encodeMangaImageUrls(urls), total = urls.size, state = "pending",
                ),
            )
            database.saveTocCache(
                sourceId, bookUrl, (0..(index + 1)).map { Chapter(it, "第${it}话", "ch-$it") },
            )
        }

        fun awaitReady(chapterUrl: String, timeoutMs: Long = 15_000): Boolean =
            awaitUntil(timeoutMs) { chapterUrl in database.listReadyMangaChapterUrls(sourceId, bookUrl) }

        fun awaitAllFailed(chapterUrl: String, timeoutMs: Long = 15_000): Boolean = awaitUntil(timeoutMs) {
            database.listMangaImagesForChapter(sourceId, bookUrl, chapterUrl).let { rows ->
                rows.isNotEmpty() && rows.all { it.state == "failed" }
            }
        }

        fun awaitIdle(timeoutMs: Long = 15_000): Boolean = awaitUntil(timeoutMs) { service.status().isEmpty() }

        private fun awaitUntil(timeoutMs: Long, block: () -> Boolean): Boolean = runBlocking {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (block()) return@runBlocking true
                delay(25)
            }
            false
        }
    }

    private fun withService(
        enabled: Boolean = true,
        failFetches: Boolean = false,
        /** 目录/清单规则走这里：**默认离线**，误触网会立刻抛错（本文件不该有网络依赖）。 */
        runner: RuleRunner? = null,
        /** 每张图抓取前的钩子（测试用它在指定章节上"卡住"，好观察中间状态）。 */
        beforeFetch: (String) -> Unit = {},
        block: (Ctx) -> Unit,
    ) {
        val dir = Files.createTempDirectory("legado-manga-prefetch")
        val dbPath = Files.createTempFile("legado-manga-prefetch", ".sqlite").toString()
        val database = Database(dbPath)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            database.initialize("password-for-test")
            database.importSources(listOf(sourceJson), applyGroups = false)
            val cache = ImageCache(dir, database, ServerConfig.DEFAULT_IMAGE_CACHE_QUOTA_BYTES)
            val fetches = AtomicInteger()
            val fetcher = MangaImageFetcher(
                config = testConfig(dir),
                injectedFetcher = { url, _, _ ->
                    beforeFetch(url)
                    fetches.incrementAndGet()
                    if (failFetches) throw java.io.IOException("upstream 503")
                    // 每张图字节不同（内容寻址是去重的，字节一样会掩盖"抓了几张"的事实）
                    "image/jpeg" to jpeg(seed = url.hashCode())
                },
            )
            val service = MangaPrefetchService(
                database = database,
                runner = runner ?: RuleRunner { url -> error("Unexpected URL: $url") },
                imageCache = cache,
                fetcher = fetcher,
                scope = scope,
                enabled = enabled,
            )
            block(Ctx(database, cache, service, fetches))
        } finally {
            scope.cancel()
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    private fun testConfig(dir: Path): ServerConfig = ServerConfig(
        host = "0.0.0.0", port = 8080, databasePath = "unused.sqlite",
        coverCacheDirectory = dir, webDavDirectory = dir.resolve("webdav"),
        initialAdminPassword = null, secureCookies = false,
    )

    private fun jpeg(seed: Int = 0, width: Int = 800, height: Int = 1200): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        out.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0x00, 0x10))
        out.write(ByteArray(14))
        out.write(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x00, 0x11, 0x08))
        out.write(byteArrayOf((height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf(0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01))
        out.write(ByteArray(64))
        val bytes = out.toByteArray()
        bytes[bytes.size - 1] = seed.toByte()
        return bytes
    }
}
