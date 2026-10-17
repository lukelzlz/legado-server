package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 有声书的后台批量缓存（G5）—— 用户反馈「音频无法缓存」的三条根因各一个回归锁。
 *
 * 实测数据（真实库）：`audio_cache` 34 段里 ready 24 / failed 8 / pending 2，
 * 失败原因全是 `HTTP connect timed out` 与 `Connection reset`（上游连接层，不是登录态也不是超限：
 * 同一批 17 MiB 的段落大量成功即是反证）。据此修复：
 * 1. 目录缺失时自己去抓（旧实现 `enqueue` 直接返回 0 ⇒ 书架"缓存全本"什么都不会发生）；
 * 2. 自动单章预取不得把"全本"顶窄；
 * 3. 每段带退避的有限重试，失败原因进日志 + 状态（`lastError`）。
 *
 * 全程**不触网**：抓取走 [AudioFetcher] 的注入接缝，目录/清单走 `RuleRunner` 的假响应。
 */
class AudioPrefetchServiceTest {

    private val sourceJson = """{
        "bookSourceUrl": "https://audio.example.com",
        "bookSourceName": "测试音频源",
        "bookSourceType": 1,
        "ruleBookInfo": {"tocUrl": "$.toc"},
        "ruleToc": {"chapterList": "$.chapters", "chapterName": "$.name", "chapterUrl": "$.url"},
        "ruleContent": {"content": "$.tracks"}
    }"""

    private val sourceId = "https://audio.example.com"
    private val bookUrl = "https://audio.example.com/book/1"

    @Test
    fun `accepts the task even when the toc is not cached`() {
        withService(runner = RuleRunner { url -> error("Unexpected URL: $url") }) { ctx ->
            // 目录没缓存、规则也抓不到 ⇒ 仍然**受理**（返回 ≥1），只是这次做不成。
            // 旧实现返回 0，路由据此回 `nothing_to_cache`，用户点了"缓存全本"却什么都不发生。
            assertEquals(1, ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 0, ahead = 5))
            assertEquals(0, ctx.attempts.get())
        }
    }

    /** 问题 A：不打开听书（目录没被播放器缓存过）也要能把整章音频落盘。 */
    @Test
    fun `fetches the toc itself and caches the whole book without opening it`() {
        val responses = ConcurrentHashMap(
            mapOf(
                // ① 从 bookUrl 取目录得到空数组（聚合源 bookUrl 是详情页参数载体）
                bookUrl to """{"toc":"https://audio.example.com/book/1/toc","chapters":[]}""",
                // ② 回退 details.tocUrl 才拿到目录
                "https://audio.example.com/book/1/toc" to
                    """{"chapters":[{"name":"第1章","url":"https://audio.example.com/ch/0"},{"name":"第2章","url":"https://audio.example.com/ch/1"}]}""",
                "https://audio.example.com/ch/0" to """{"tracks":["https://a.example.com/c0-0.mp3","https://a.example.com/c0-1.mp3"]}""",
                "https://audio.example.com/ch/1" to """{"tracks":["https://a.example.com/c1-0.mp3"]}""",
            ),
        )
        withService(runner = RuleRunner { url -> responses[url] ?: error("Unexpected URL: $url") }) { ctx ->
            assertTrue("前置条件：目录确实没有缓存", ctx.database.getTocCache(sourceId, bookUrl).isNullOrEmpty())

            assertEquals(1, ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 0, ahead = 1))

            assertTrue("两章都要整章就绪", ctx.awaitAllReady(timeoutMs = 20_000))
            assertEquals("3 段音频都抓到了", 3, ctx.attempts.get())
            assertEquals("目录必须落进 book_toc_cache", 2, ctx.database.getTocCache(sourceId, bookUrl)?.size)
            assertEquals(
                "详情页给的 tocUrl 也要存一份（与阅读路径同口径）",
                2,
                ctx.database.getTocCache(sourceId, "https://audio.example.com/book/1/toc")?.size,
            )
        }
    }

    /**
     * 问题 B：`done/planned` 是**章**，`doneTracks/plannedTracks` 是**分段**。
     *
     * 让第 2 章的抓取卡住，在"第 1 章已完成、第 2 章在飞"的瞬间读状态：
     * 2 章 / 3+3 段 ⇒ `planned=2`、`plannedTracks=6`、`done=1`、`doneTracks=3`。
     */
    @Test
    fun `status reports chapters for done planned and tracks for doneTracks plannedTracks`() {
        val blocking = CountDownLatch(1)
        val started = CountDownLatch(1)
        withService(
            beforeFetch = { url ->
                if (url.contains("b-")) {
                    started.countDown()
                    assertTrue("等测试读状态", blocking.await(15, TimeUnit.SECONDS))
                }
            },
        ) { ctx ->
            ctx.seedChapter(0, (0..2).map { "https://a.example.com/a-$it.mp3" })
            ctx.seedChapter(1, (0..2).map { "https://a.example.com/b-$it.mp3" })

            assertEquals(2, ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 0, ahead = 1))
            assertTrue("第 2 章必须真的在飞", started.await(15, TimeUnit.SECONDS))

            val snapshot = ctx.service.status().single()
            assertEquals("planned = 规划**章数**", 2, snapshot.planned)
            assertEquals("done = 已完成**章数**（第 1 章整章落盘）", 1, snapshot.done)
            assertEquals("plannedTracks = 规划**分段**数（细节口径）", 6, snapshot.plannedTracks)
            assertEquals("doneTracks = 已落盘**分段**数（细节口径）", 3, snapshot.doneTracks)
            assertEquals("还没失败", 0, snapshot.failed)

            blocking.countDown()
            assertTrue(ctx.awaitAllReady(timeoutMs = 20_000))
            assertEquals(6, ctx.readyTracks())
        }
    }

    /**
     * 上游连接抖动：**带退避的有限重试**要把"抖一下"救回来；真救不回来时
     * 状态里必须带出失败段数与首条原因（旧实现只有"进度不动"，用户无从判断）。
     *
     * 编排：第 1 章一过性失败（重试成功）→ 第 2 章永久失败（重试到上限）→
     * 第 3 章卡住，好让"第 2 章的失败"留在状态里被读到。
     */
    @Test
    fun `retries transient failures and surfaces the reason when they persist`() {
        val transientFailed = AtomicBoolean(false)
        val blocking = CountDownLatch(1)
        val started = CountDownLatch(1)
        val attemptsByUrl = ConcurrentHashMap<String, AtomicInteger>()
        withService(
            fetchHook = { url ->
                val name = url.substringAfterLast('/')
                attemptsByUrl.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
                when {
                    // 第 1 章第 1 段：第一次失败、重试成功（真实的一过性抖动）
                    name == "a-0.mp3" && !transientFailed.getAndSet(true) ->
                        throw java.io.IOException("Connection reset")
                    // 第 2 章第 1 段：永远失败，用来验证"失败原因如实报出"
                    name == "b-0.mp3" -> throw java.io.IOException("HTTP connect timed out")
                    // 第 3 章：卡住，让第 2 章的失败在状态里可见
                    name == "c-0.mp3" -> {
                        started.countDown()
                        assertTrue(blocking.await(15, TimeUnit.SECONDS))
                    }
                }
            },
        ) { ctx ->
            ctx.seedChapter(0, listOf("https://a.example.com/a-0.mp3"))
            ctx.seedChapter(1, listOf("https://a.example.com/b-0.mp3"))
            ctx.seedChapter(2, listOf("https://a.example.com/c-0.mp3"))

            ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 0, ahead = 2)

            // 第 1 章：一过性失败被重试救回来（整章就绪）
            assertTrue("一过性失败必须被重试救回来", ctx.awaitChapterReady("https://audio.example.com/ch/0", 20_000))
            assertEquals("a-0 应被抓 2 次（首次失败 + 一次重试）", 2, attemptsByUrl["a-0.mp3"]?.get())

            // 第 3 章开始抓 ⇒ 第 2 章已经用尽重试并放弃
            assertTrue(started.await(60, TimeUnit.SECONDS))
            assertEquals("b-0 只退避重试一次（AudioFetcher 内部另有一次）", 2, attemptsByUrl["b-0.mp3"]?.get())

            val snapshot = ctx.service.status().single()
            assertEquals("失败段数必须如实上报", 1, snapshot.failed)
            assertTrue("首条原因要如实带出：${snapshot.lastError}", (snapshot.lastError ?: "").contains("connect timed out"))
            assertEquals("失败的那一章不能算完成（章口径）", 1, snapshot.done)
            assertEquals("3 章都规划在内", 3, snapshot.planned)

            blocking.countDown()
            assertTrue(ctx.awaitChapterReady("https://audio.example.com/ch/2", 20_000))
            assertEquals("失败的行仍然留在库里（下次任务会重试）", "failed", ctx.database.listAudioForChapter(sourceId, bookUrl, "https://audio.example.com/ch/1").single().state)
        }
    }

    /** 自动单章预取（`ahead=0`）不得把"全本/后 N 章"顶窄。 */
    @Test
    fun `automatic single chapter prefetch does not narrow a running bulk task`() {
        val blocking = CountDownLatch(1)
        val started = CountDownLatch(1)
        withService(
            beforeFetch = { url ->
                if (url.contains("a-")) {
                    started.countDown()
                    assertTrue(blocking.await(15, TimeUnit.SECONDS))
                }
            },
        ) { ctx ->
            ctx.seedChapter(0, (0..2).map { "https://a.example.com/a-$it.mp3" })
            ctx.seedChapter(1, (0..2).map { "https://a.example.com/b-$it.mp3" })
            ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 0, ahead = 1)
            assertTrue(started.await(15, TimeUnit.SECONDS))

            // 播放器此刻投递"下一章"（ahead=0）⇒ 落在批量区间里 ⇒ 忽略
            assertEquals(1, ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 1, ahead = 0))
            assertEquals("批量任务的规划章数不能被顶成 1", 2, ctx.service.status().single().planned)

            blocking.countDown()
            assertTrue("批量任务必须继续跑完第 2 章", ctx.awaitAllReady(timeoutMs = 20_000))
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private class Ctx(
        val database: Database,
        val audioCache: AudioCache,
        val service: AudioPrefetchService,
        val attempts: AtomicInteger,
        val sourceId: String,
        val bookUrl: String,
    ) {
        /** 造第 index 章的清单（等价于"播放器刚才打开过这一章"）+ 目录缓存。 */
        fun seedChapter(index: Int, trackUrls: List<String>) {
            val chapterUrl = "https://audio.example.com/ch/$index"
            audioCache.registerChapter(
                sourceId, bookUrl, chapterUrl, index,
                trackUrls.map { AudioTrackSpec(it) },
            )
            database.saveTocCache(sourceId, bookUrl, (0..index).map { Chapter(it, "第${it + 1}章", "https://audio.example.com/ch/$it") })
        }

        fun awaitChapterReady(chapterUrl: String, timeoutMs: Long = 15_000): Boolean =
            awaitUntil(timeoutMs) { chapterUrl in database.readyAudioChapters(sourceId, bookUrl) }

        fun awaitAllReady(timeoutMs: Long = 15_000): Boolean =
            awaitUntil(timeoutMs) {
                val total = database.getTocCache(sourceId, bookUrl)?.size ?: return@awaitUntil false
                total > 0 && database.readyAudioChapters(sourceId, bookUrl).size == total
            }

        fun readyTracks(): Int = database.readyAudioChapters(sourceId, bookUrl)
            .sumOf { chapterUrl -> database.listAudioForChapter(sourceId, bookUrl, chapterUrl).count { it.state == "ready" } }

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
        runner: RuleRunner? = null,
        /** 每段抓取前的钩子（可以在里面卡住，用于观察中间状态）。 */
        beforeFetch: (String) -> Unit = {},
        /** 注入接缝的"上游"行为；默认返回一段合法 MP3 字节。 */
        fetchHook: (String) -> Unit = {},
        block: (Ctx) -> Unit,
    ) {
        val dir = Files.createTempDirectory("legado-audio-prefetch")
        val dbPath = Files.createTempFile("legado-audio-prefetch", ".sqlite").toString()
        val database = Database(dbPath)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            database.initialize("password-for-test")
            database.importSources(listOf(sourceJson), applyGroups = false)
            val audioCache = AudioCache(dir, database, ServerConfig.DEFAULT_AUDIO_CACHE_QUOTA_BYTES)
            val attempts = AtomicInteger()
            val fetcher = AudioFetcher(
                config = testConfig(dir),
                injectedFetcher = { url, _, _, _ ->
                    beforeFetch(url)
                    fetchHook(url)
                    attempts.incrementAndGet()
                    "audio/mpeg" to mp3()
                },
            )
            val service = AudioPrefetchService(
                database = database,
                runner = runner ?: RuleRunner { url -> error("Unexpected URL: $url") },
                audioCache = audioCache,
                fetcher = fetcher,
                scope = scope,
            )
            block(Ctx(database, audioCache, service, attempts, sourceId, bookUrl))
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

    /** 一段"看起来是 MP3"的字节（魔数 `ID3`，判据收口在 [AudioBytes]）。 */
    private fun mp3(): ByteArray = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) + ByteArray(64)
}
