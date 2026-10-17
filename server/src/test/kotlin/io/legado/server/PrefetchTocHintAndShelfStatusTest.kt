package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * issue #75：有声书在书架点「离线缓存全本」失效的三处根因，各自一条回归锁。
 *
 * ## 三处根因（都实测过）
 * 1. **目录回退链缺「书架 tocUrl」这一跳**：`ensurePrefetchChapters` 过去只试
 *    `bookUrl`，而聚合源的 `bookUrl` 只是**详情参数载体**
 *    （`data:;base64,<载荷>,{"type":"qingtian"}`），真正的目录地址是从书架
 *    `toc_url`（`…,{"type":"qingtian2"}`）拿的 ⇒ 目录取空 ⇒ `drain` 直接 return
 *    ⇒ **音频一段都没下**，用户在书架上怎么点都不动。
 * 2. **书架元数据被占位记录污染**：`POST /api/bookshelf` 对**非文本书（comic/audio）**
 *    也走 `ensureLocalBookStatusReady(...)`（那是"本地书正文已全量落库"的语义），
 *    把 `book_cache_status` 强写成 `total_chapters=1 / cached_chapters=1 / ready`
 *    ⇒ 前端算成「100% (1/1) 章」。
 * 3. **总章数取了这条占位记录的 `1`**：`bookshelfWithMediaCache` 里
 *    `item.totalChapters.coerceAtLeast(ready)`，`item.totalChapters` 正来自
 *    `book_cache_status` ⇒ 目录未知时显示"1 章"而不是"未知"。
 *
 * 三条测试在**改前代码**上都会红：①目录取不到 ⇒ 没有 ready 分段；
 * ②`book_cache_status` 出现 1/1/ready；③`totalChapters` 等于 1。
 */
class PrefetchTocHintAndShelfStatusTest {

    private val sourceId = "https://audio.example.com"
    private val bookUrl = "$sourceId/book/1"
    private val shelfTocUrl = "$sourceId/book/1/toc"
    private val chapterUrls = listOf("$sourceId/ch/0", "$sourceId/ch/1")

    private val sourceJson = """{
        "bookSourceUrl": "$sourceId",
        "bookSourceName": "test-audio",
        "bookSourceType": 1,
        "ruleBookInfo": {"tocUrl": "$.toc"},
        "ruleToc": {"chapterList": "$.chapters", "chapterName": "$.name", "chapterUrl": "$.url"},
        "ruleContent": {"content": "$.tracks"}
    }"""

    // ------------------------------------------------------------------
    // ① 目录回退：只用书架的 tocUrl 提示也必须抓到目录并真的下音频
    // ------------------------------------------------------------------

    /**
     * **不打开播放器**、目录也没缓存，书架里只有 `toc_url` 这一条线索。
     *
     * 假响应刻意做成"只有书架那个 tocUrl 能取到目录"：
     *   - `chapters(bookUrl)` ⇒ `{"chapters":[]}`（聚合源 bookUrl 是参数载体的等价形态）
     *   - `details(bookUrl).tocUrl` 规则取不到（`$.toc` 缺）⇒ 回退到 `bookUrl` 本身
     *     ⇒ 又是空数组
     *   ⇒ **改前代码**两跳全空 ⇒ 返回 null ⇒ `drain` return ⇒ 音频零落盘。
     *   ⇒ **改后**先用书架 `toc_url` 做第一跳 ⇒ 2 章 ⇒ 3 段音频 ready。
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
    // ② 「加入书架」不再给 comic/audio 写本地书语义的占位状态
    // ------------------------------------------------------------------

    /**
     * 漫画/有声加入书架后，`book_cache_status` **不许**出现 `1/1/ready` 这种假记录。
     *
     * 预置一条已缓存的正文行（等价于"这本书以前被误当成小说缓存过 1 章"）：改前
     * `ensureLocalBookStatusReady` 会据它写出 `total=1 / cached=1 / ready` —— 正是用户
     * 看到的「100% (1/1) 章」；改后这条路径对媒体书完全不执行。
     */
    @Test
    fun `adding comic or audio to the shelf no longer writes a 1of1 ready placeholder`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-issue75-shelf")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        val comicUrl = "$sourceId/comic/9"
        val comicTocUrl = "$sourceId/comic/9/toc"

        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            seed.importSources(listOf(sourceJson), applyGroups = false)
            // 先制造"已缓存 1 章正文"：这正是改前被 ensureLocalBookStatusReady 折算成 1/1 的输入
            seed.cacheBookContent(sourceId, bookUrl, chapterUrls.first(), ChapterContent(title = "t", content = "x"))
            seed.cacheBookContent(sourceId, comicUrl, "$comicUrl/ch/1", ChapterContent(title = "t", content = "x"))
        } finally {
            seed.close()
        }

        val config = ServerConfig(
            host = "0.0.0.0", port = 8080, databasePath = dbPath,
            coverCacheDirectory = dataDir.resolve("covers"), webDavDirectory = dataDir.resolve("webdav"),
            initialAdminPassword = "test-password-1234", secureCookies = false,
            audioCacheDirectory = dataDir.resolve("audio"),
        )
        application { legadoApplication(config) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            install(HttpCookies)
        }
        val csrf = login(client, "test-password-1234")

        for ((kind, url, toc) in listOf(
            Triple("audio", bookUrl, shelfTocUrl),
            Triple("comic", comicUrl, comicTocUrl),
        )) {
            val res = client.post("/api/bookshelf") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(
                    BookshelfWriteRequest(
                        sourceId = sourceId, bookUrl = url, name = "issue75-$kind", author = "t",
                        tocUrl = toc, bookKind = kind,
                    ),
                )
            }
            assertEquals("加入书架必须成功（$kind）", HttpStatusCode.OK, res.status)
        }

        // 直接读库：占位记录必须**整条不存在**（不是"值改小一点"）
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            for ((kind, url) in listOf("audio" to bookUrl, "comic" to comicUrl)) {
                conn.prepareStatement(
                    "select total_chapters, cached_chapters, state from book_cache_status where source_id = ? and book_url = ?",
                ).use { ps ->
                    ps.setString(1, sourceId)
                    ps.setString(2, url)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            val total = rs.getInt(1)
                            val cached = rs.getInt(2)
                            val state = rs.getString(3)
                            assertTrue(
                                "$kind 绝不能出现 1/1/ready 的占位记录，实际 total=$total cached=$cached state=$state",
                                !(total == 1 && cached == 1 && state == "ready"),
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // ③ 目录未知时，书架**不得**把占位记录的 1 当总章数
    // ------------------------------------------------------------------

    @Test
    fun `bookshelf reports unknown chapter count as zero instead of the placeholder one`() {
        withTempDatabase { database ->
            database.importSources(listOf(sourceJson), applyGroups = false)
            val audio = BookshelfWriteRequest(
                sourceId = sourceId, bookUrl = bookUrl, name = "audio-no-toc", author = "t",
                tocUrl = shelfTocUrl, bookKind = "audio",
            )
            database.saveBookshelf(audio, null)
            database.cacheBookContent(sourceId, bookUrl, chapterUrls.first(), ChapterContent(title = "t", content = "x"))
            // 复刻被污染的历史数据：1/1/ready
            database.ensureLocalBookStatusReady(sourceId, bookUrl)
            assertEquals(
                "前置条件：库里的正文缓存表确实是 1/1/ready",
                1,
                database.listBookshelf().first { it.bookUrl == bookUrl }.totalChapters,
            )

            val listed = bookshelfWithMediaCache(database, null, null)
                .first { it.bookUrl == bookUrl && it.sourceId == sourceId }
            assertNotEquals("目录未知 ⇒ 不能显示成 1 章", 1, listed.totalChapters)
            assertEquals("目录未知 ⇒ 如实给 0（前端显示 ?）", 0, listed.totalChapters)
            assertEquals("什么都没有缓存时不能报 ready", "idle", listed.cacheState)
        }
    }

    /** 目录缓存存在时，总章数取**目录**的章数（不是正文缓存表，也不是媒体条数）。 */
    @Test
    fun `bookshelf takes the total chapter count from the cached toc`() {
        withTempDatabase { database ->
            database.importSources(listOf(sourceJson), applyGroups = false)
            database.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = sourceId, bookUrl = bookUrl, name = "audio-with-toc", author = "t",
                    tocUrl = shelfTocUrl, bookKind = "audio",
                ),
                null,
            )
            // 目录落在 shelf toc_url 这个 key 上（阅读器/预取都可能存这里）
            database.saveTocCache(sourceId, shelfTocUrl, (0 until 42).map { Chapter(it, "第${it + 1}章", "$sourceId/ch/$it") })
            database.ensureLocalBookStatusReady(sourceId, bookUrl)

            val listed = bookshelfWithMediaCache(database, null, null).first { it.bookUrl == bookUrl }
            assertEquals("总章数必须来自目录", 42, listed.totalChapters)
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private class AudioCtx(
        val database: Database,
        val service: AudioPrefetchService,
        val attempts: AtomicInteger,
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
            block(AudioCtx(database, service, attempts, sourceId, bookUrl))
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

    private suspend fun login(client: io.ktor.client.HttpClient, pass: String): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = pass))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return res.body<LoginResponse>().csrfToken
    }
}
