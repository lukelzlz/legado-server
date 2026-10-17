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
        "header": "{\"Referer\":\"https://manga.example.com/\"}"
    }"""

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
    fun `returns zero instead of failing when the toc is not cached`() {
        withService { ctx ->
            // 只登记了章节清单、没有目录缓存 ⇒ 无法知道"后 N 章"是谁
            assertEquals(0, ctx.service.enqueue(ctx.sourceId, ctx.bookUrl, 0, 3))
            assertEquals("不该白抓任何一张图", 0, ctx.fetches.get())
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
        beforeFetch: ((String) -> Unit)? = null,
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
                    beforeFetch?.invoke(url)
                    fetches.incrementAndGet()
                    if (failFetches) throw java.io.IOException("upstream 503")
                    // 每张图字节不同（内容寻址是去重的，字节一样会掩盖"抓了几张"的事实）
                    "image/jpeg" to jpeg(seed = url.hashCode())
                },
            )
            val service = MangaPrefetchService(
                database = database,
                runner = RuleRunner(database = database),
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
