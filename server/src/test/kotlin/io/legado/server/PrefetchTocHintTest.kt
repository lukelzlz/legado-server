package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * issue #75 根因①：**预取目录的回退链缺「书架 `toc_url`」这一跳**。
 *
 * 聚合源的 `bookUrl` 只有**详情参数载体**的语义
 * （`data:;base64,<载荷>,{"type":"qingtian"}`），真正的目录地址在书架行的
 * `toc_url`（`…,{"type":"qingtian2"}`）。过去的 `ensurePrefetchChapters` 自己抄了一份
 * `chapters(bookUrl) → details(bookUrl).tocUrl → chapters(tocUrl)`，从不问书架
 * ⇒ 直接从书架点「缓存全本」时目录取空 ⇒ `drain` 直接 return ⇒ **音频一段都没下**，
 * 进度永远停在 0。`readCachedToc` 同理只试两个 key，目录落在 `toc_url` 上时
 * `enqueue` 连真实章数都报不出来。
 *
 * 本文件只锁这一条链路（`shelfTocUrlHint` / 目录落两个 key / `enqueue` 报真实章数）。
 * 同一提交里 issue #75 的另外两处断层（`POST /api/bookshelf` 给媒体书写 1/1/ready 占位记录、
 * 书架总章数取占位记录的 `1`）不在本次 backport 范围内。
 */
class PrefetchTocHintTest {

    private val sourceId = "https://audio.example.com"
    private val bookUrl = "$sourceId/book/1"
    /**
     * 书架里存的**真正的目录地址**。
     *
     * 刻意做成 `bookUrl` 的**非 `/toc` 变体**（聚合源就是这种形态：`bookUrl` 是
     * `…,{"type":"qingtian"}`、`toc_url` 是 `…,{"type":"qingtian2"}`，两者只差类型标记、
     * 但 `book_toc_cache` 的 key 完全不同）。若这里写成 `bookUrl + "/toc"`，
     * `readCachedToc` 原有的二 key 变体就会命中 ⇒ 用例对第三 key 失去区分力（实测过）。
     */
    private val shelfTocUrl = "$sourceId/book/1/toc?type=qingtian2"

    private val sourceJson = """{
        "bookSourceUrl": "$sourceId",
        "bookSourceName": "test-audio",
        "bookSourceType": 1,
        "ruleBookInfo": {"tocUrl": "$.toc"},
        "ruleToc": {"chapterList": "$.chapters", "chapterName": "$.name", "chapterUrl": "$.url"},
        "ruleContent": {"content": "$.tracks"}
    }"""

    /**
     * **不打开播放器**、目录也没缓存，书架里只有 `toc_url` 这一条线索。
     *
     * 假响应刻意做成"只有书架那个 tocUrl 能取到目录"：
     *   - `chapters(bookUrl)` ⇒ `{"chapters":[]}`（聚合源 bookUrl 是参数载体的等价形态）
     *   - `details(bookUrl).tocUrl` 规则取不到（`$.toc` 缺）⇒ 回退到 `bookUrl` 本身 ⇒ 又是空数组
     *   ⇒ **缺 hint 的代码**两跳全空 ⇒ 返回 null ⇒ `drain` return ⇒ 音频零落盘。
     *   ⇒ **有 hint** 时先用书架 `toc_url` 做第一跳 ⇒ 2 章 ⇒ 3 段音频 ready。
     */
    @Test
    fun `audio prefetch uses the shelf tocUrl hint and really downloads segments`() {
        val responses = ConcurrentHashMap(
            mapOf(
                bookUrl to """{"chapters":[]}""",
                shelfTocUrl to """{"chapters":[
                    {"name":"ch0","url":"$sourceId/ch/0"},
                    {"name":"ch1","url":"$sourceId/ch/1"}
                ]}""",
                "$sourceId/ch/0" to """{"tracks":["https://cdn.example.com/c0-0.mp3","https://cdn.example.com/c0-1.mp3"]}""",
                "$sourceId/ch/1" to """{"tracks":["https://cdn.example.com/c1-0.mp3"]}""",
            ),
        )
        val requested = ConcurrentHashMap.newKeySet<String>()
        withAudioService(runner = RuleRunner { url -> requested.add(url); responses[url] ?: """{"chapters":[]}""" }) { ctx ->
            // 前置：目录确实没缓存，但书架里存着 toc_url
            assertNull("前置条件：目录没缓存", ctx.database.getTocCache(sourceId, bookUrl))
            assertNull("前置条件：toc 变体也没缓存", ctx.database.getTocCache(sourceId, shelfTocUrl))

            assertEquals("必须受理（≥1 不代表章数已定）", 1, ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 0, ahead = 1))

            assertTrue("两章都要整章就绪（每段 ready 才算一章）", ctx.awaitChaptersReady(2, timeoutMs = 20_000))
            assertEquals("目录必须落库（bookUrl 与 tocUrl 两个 key 都要有）", 2, ctx.database.getTocCache(sourceId, bookUrl)?.size)
            assertEquals("详情页/toc 那个 key 也要落一份", 2, ctx.database.getTocCache(sourceId, shelfTocUrl)?.size)
            assertEquals("3 段音频都要真的抓到", 3, ctx.readyTracks())
            assertTrue("必须先问书架的 tocUrl（否则聚合源永远取不到目录）", requested.contains(shelfTocUrl))
        }
    }

    /** 书不在书架 / 没有 tocUrl 提示时，行为必须与改前一致（回退链自己抓，不抛错）。 */
    @Test
    fun `prefetch falls back to bookUrl when the book has no shelf toc hint`() {
        withTempDatabase { database ->
            database.importSources(listOf(sourceJson), applyGroups = false)
            val runner = RuleRunner { url -> if (url == bookUrl) """{"chapters":[{"name":"c","url":"$sourceId/c"}]}""" else error("unexpected: $url") }
            val chapters = ensurePrefetchChapters(database, runner, sourceId, bookUrl) { }
            assertNotNull("没有书架提示时也要按 bookUrl 自己抓", chapters)
            assertEquals(1, chapters!!.size)
        }
    }

    /** 目录只落在书架 `toc_url` 这个 key 上时，`enqueue` 也得立刻报出真实章数（不是"未知"）。 */
    @Test
    fun `enqueue reports the real chapter count when the toc is cached under the shelf tocUrl`() {
        withAudioService(runner = RuleRunner { url -> error("不该触网：$url") }) { ctx ->
            ctx.database.saveTocCache(
                sourceId, shelfTocUrl,
                (0 until 7).map { Chapter(it, "第${it + 1}章", "$sourceId/ch/$it") },
            )
            // ahead=1 ⇒ 规划 2 章（第 0、1 章），说明目录被认出来了
            assertEquals(2, ctx.service.enqueue(sourceId, bookUrl, fromChapterIndex = 0, ahead = 1))
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private class AudioCtx(
        val database: Database,
        val service: AudioPrefetchService,
        private val sourceId: String,
        private val bookUrl: String,
    ) {
        fun readyTracks(): Int = database
            .readyAudioChapters(sourceId, bookUrl)
            .sumOf { chapterUrl -> database.listAudioForChapter(sourceId, bookUrl, chapterUrl).count { it.state == "ready" } }

        fun awaitChaptersReady(expected: Int, timeoutMs: Long = 15_000): Boolean = runBlocking {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (database.readyAudioChapters(sourceId, bookUrl).size >= expected) return@runBlocking true
                delay(25)
            }
            false
        }
    }

    private fun withAudioService(runner: RuleRunner, block: (AudioCtx) -> Unit) {
        val dir = Files.createTempDirectory("legado-issue75-audio")
        val dbPath = Files.createTempFile("legado-issue75-audio", ".sqlite").toString()
        val database = Database(dbPath)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            database.initialize("password-for-test")
            database.importSources(listOf(sourceJson), applyGroups = false)
            // 书架里存着真正的目录地址（聚合源的 `toc_url`），目录本身**没缓存**
            database.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = sourceId, bookUrl = bookUrl, name = "audio-book", author = "t",
                    tocUrl = shelfTocUrl, bookKind = "audio",
                ),
                null,
            )
            val audioCache = AudioCache(dir.resolve("audio"), database, ServerConfig.DEFAULT_AUDIO_CACHE_QUOTA_BYTES)
            val attempts = AtomicInteger()
            val fetcher = AudioFetcher(
                config = testServerConfig(dir),
                injectedFetcher = { url, _, _, _ ->
                    attempts.incrementAndGet()
                    "audio/mpeg" to mp3Bytes(url)
                },
            )
            val service = AudioPrefetchService(
                database = database,
                runner = runner,
                audioCache = audioCache,
                fetcher = fetcher,
                scope = scope,
            )
            block(AudioCtx(database, service, sourceId, bookUrl))
        } finally {
            scope.cancel()
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    private fun withTempDatabase(block: suspend (Database) -> Unit) {
        val dbPath = Files.createTempFile("legado-issue75-toc", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            runBlocking { block(database) }
        } finally {
            database.close()
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    private fun testServerConfig(dir: Path): ServerConfig = ServerConfig(
        host = "0.0.0.0", port = 8080, databasePath = "unused.sqlite",
        coverCacheDirectory = dir, webDavDirectory = dir.resolve("webdav"),
        initialAdminPassword = null, secureCookies = false,
    )

    /** "看起来是 MP3" 的字节（魔数 `ID3`，判据收口在 [AudioBytes]）。 */
    private fun mp3Bytes(seedUrl: String): ByteArray {
        val padding = (seedUrl.length * 7 + 64) % 48
        return byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) + ByteArray(64 + padding)
    }
}
