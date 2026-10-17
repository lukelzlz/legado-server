package io.legado.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「书移出书架」必须**连带清理媒体缓存**——用户反馈（原话，重复两遍）：
 * 「为什么书移出书架后漫画，音频，插图没有同步删了」。
 *
 * ## 修复前的行为（本文件为什么对改前代码全红）
 * `DELETE /bookshelf` 只删 `book_shelf` / `reading_progress` / `book_content_cache` /
 * `book_cache_status` / `book_toc_cache` / 孤儿封面。三张媒体表（`manga_image_cache`、
 * `manga_chapter_cache`、`audio_cache`、`illustration_cache`）与三个磁盘根（`comic/`、
 * `audio/`、`images/`）**一个字节都不动** ⇒ 用户看到的正是"移出书架后漫画/音频/插图还在"。
 *
 * ## 这条用例锁定的四件事
 * 1. 三类媒体缓存（漫画/有声/插图）的**行与真实文件**都随书一起走；
 * 2. **只删这一本**：同一书源下的"旁观书"的行与文件必须原样（`sourceId + bookUrl` 精确匹配）；
 * 3. `book_cache_status` 行也走（原本就删，防以后回退）；
 * 4. 正在跑的预取任务必须被取消（否则删除刚做完就又被抓回来）。
 */
class RemoveShelfMediaPurgeTest {

    private val sourceId = "https://media.example.com"
    private val comicBook = "$sourceId/book/comic"
    private val comicBystander = "$sourceId/book/comic-2"
    private val audioBook = "$sourceId/book/audio"
    private val audioBystander = "$sourceId/book/audio-2"
    private val novelBook = "$sourceId/book/novel"
    private val novelBystander = "$sourceId/book/novel-2"

    private val comicImage = "https://img.example.com/comic-1.jpg"
    private val comicImagePending = "https://img.example.com/comic-pending.jpg"
    private val comicBystanderImage = "https://img.example.com/comic-2.jpg"
    private val audioTrack = "https://a.example.com/audio-1.mp3"
    private val audioBystanderTrack = "https://a.example.com/audio-2.mp3"
    private val novelImage = "https://img.example.com/novel-1.jpg"
    private val novelBystanderImage = "https://img.example.com/novel-2.jpg"

    @Test
    fun `removing a book from the shelf purges its manga audio and illustration caches only`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-remove-shelf")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        val comicDir = dataDir.resolve("comic")
        val audioDir = dataDir.resolve("audio")
        val imagesDir = dataDir.resolve("images")

        var comicFile: Path? = null
        var comicBystanderFile: Path? = null
        var audioFile: Path? = null
        var audioBystanderFile: Path? = null
        var illustrationFile: Path? = null
        var illustrationBystanderFile: Path? = null

        // 预取"卡"在这个闸门上：抓取一旦开始就永远不返回 ⇒ 删除前后都能观察到"任务在跑"。
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)

        val seed = Database(dbPath)
        try {
            seed.initialize("test-password-1234")
            // 预置数据等价于"已经升级到 v3 布局的既有部署"：否则启动迁移会按旧布局语义清掉预置缓存
            seed.setSetting(CACHE_LAYOUT_KEY, CACHE_LAYOUT_VERSION)
            seed.importSources(
                listOf("""{"bookSourceUrl":"$sourceId","bookSourceName":"媒体测试源","bookSourceType":2}"""),
                applyGroups = false,
            )
            listOf(comicBook, comicBystander, audioBook, audioBystander, novelBook, novelBystander)
                .forEachIndexed { index, url ->
                    seed.saveBookshelf(
                        BookshelfWriteRequest(sourceId = sourceId, bookUrl = url, name = "媒体书$index", tocUrl = url),
                        cover = null,
                    )
                }

            // ① 漫画：目录 + 章节清单（2 张图：第 1 张已落盘、第 2 张 pending ⇒ 预取会去抓它）
            val manga = ImageCache(comicDir, seed)
            seed.saveTocCache(sourceId, comicBook, listOf(Chapter(0, "第 1 话", "c-comic")))
            seed.saveMangaChapter(
                MangaChapterRecord(
                    sourceId = sourceId, bookUrl = comicBook, chapterUrl = "c-comic", chapterIndex = 0,
                    imagesJson = encodeMangaImageUrls(listOf(comicImage, comicImagePending)), total = 2, state = "pending",
                ),
            )
            manga.registerChapter(sourceId, comicBook, "c-comic", 0, listOf(comicImage, comicImagePending))
            comicFile = manga.fileFor(
                manga.put(sourceId, comicBook, "c-comic", comicImage, "image/jpeg", jpeg(seed = 1), chapterIndex = 0, segmentIndex = 0).cacheKey,
            )
            seed.saveMangaChapter(
                MangaChapterRecord(
                    sourceId = sourceId, bookUrl = comicBystander, chapterUrl = "c-comic-2", chapterIndex = 0,
                    imagesJson = encodeMangaImageUrls(listOf(comicBystanderImage)), total = 1, state = "ready",
                ),
            )
            comicBystanderFile = manga.fileFor(
                manga.put(sourceId, comicBystander, "c-comic-2", comicBystanderImage, "image/jpeg", jpeg(seed = 2), chapterIndex = 0, segmentIndex = 0).cacheKey,
            )

            // ② 有声书：音频分段 + 真实文件
            val audio = AudioCache(audioDir, seed)
            val audioRows = audio.registerChapter(sourceId, audioBook, "c-audio", 0, listOf(AudioTrackSpec(audioTrack)))
            audio.putReady(audioRows.single(), "audio/mpeg", mp3Bytes(1024))
            audioFile = audio.fileFor(audioRows.single())
            val audioBystanderRows = audio.registerChapter(
                sourceId, audioBystander, "c-audio-2", 0, listOf(AudioTrackSpec(audioBystanderTrack)),
            )
            audio.putReady(audioBystanderRows.single(), "audio/mpeg", mp3Bytes(2048))
            audioBystanderFile = audio.fileFor(audioBystanderRows.single())

            // ③ 小说插图：一行 + 真实文件
            val illustrations = IllustrationCache(imagesDir, seed)
            val illustration = illustrations.put(
                sourceId, novelBook, "c-novel", novelImage, "image/jpeg", jpeg(seed = 3),
                chapterIndex = 0, segmentIndex = 0,
            )
            illustrationFile = illustrations.fileFor(illustration)
            val illustrationBystander = illustrations.put(
                sourceId, novelBystander, "c-novel-2", novelBystanderImage, "image/jpeg", jpeg(seed = 4),
                chapterIndex = 0, segmentIndex = 0,
            )
            illustrationBystanderFile = illustrations.fileFor(illustrationBystander)

            // ④ 正文缓存进度行（原本就要删，防以后回退）。
            //    用 `finishBookCache`（state='ready'）而不是 `beginBookCache`（'caching'）：后者会让
            //    启动时的「续做」把这些虚构书当待缓存任务真去抓网络（测试必须离线且确定）。
            seed.finishBookCache(sourceId, comicBook)
            seed.finishBookCache(sourceId, audioBook)
            seed.finishBookCache(sourceId, novelBook)
        } finally {
            seed.close()
        }
        listOf(comicFile, comicBystanderFile, audioFile, audioBystanderFile, illustrationFile, illustrationBystanderFile)
            .forEach { assertNotNull("前置条件：预置文件必须真的落盘", it) }
        assertTrue("前置条件：预置文件必须存在", Files.exists(comicFile!!) && Files.exists(audioFile!!) && Files.exists(illustrationFile!!))

        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234",
                secureCookies = false,
                comicCacheDirectory = comicDir,
                audioCacheDirectory = audioDir,
                illustrationCacheDirectory = imagesDir,
            )
            val blockingFetcher = MangaImageFetcher(
                config = config,
                injectedFetcher = { _, _, _ ->
                    fetchStarted.countDown()
                    releaseFetch.await(30, TimeUnit.SECONDS)
                    throw java.io.IOException("test: 抓取已被取消")
                },
            )
            application { legadoApplication(config, injectedImageFetcher = blockingFetcher) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val csrf = login(client, "test-password-1234")

            // 让漫画的预取真的跑起来（前置条件，不是被测行为）
            val accepted = client.post("/api/books/images/prefetch") {
                contentType(ContentType.Application.Json)
                header(AuthService.CSRF_HEADER, csrf)
                setBody(MangaPrefetchRequest(sourceId = sourceId, bookUrl = comicBook, fromChapterIndex = 0, ahead = 0))
            }
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertTrue("前置条件：预取必须真的发起抓取", fetchStarted.await(20, TimeUnit.SECONDS))
            assertTrue("前置条件：状态接口必须看得到正在跑的任务", awaitComicPrefetchVisible(client))

            // ========== 把三本（漫画 / 有声 / 带插图小说）依次移出书架 ==========
            assertEquals(HttpStatusCode.NoContent, deleteShelf(client, csrf, comicBook).status)
            assertEquals(HttpStatusCode.NoContent, deleteShelf(client, csrf, audioBook).status)
            assertEquals(HttpStatusCode.NoContent, deleteShelf(client, csrf, novelBook).status)

            // ========== 断言：这三本书的媒体缓存连行带盘全清 ==========
            // 漫画：图片行 + 章节清单（`pinned` 就存在清单上，留下等于让 GC 永远不敢回收）
            assertEquals("漫画图片行必须跟着书一起删", 0, countRows(dbPath, "manga_image_cache", comicBook))
            assertEquals("漫画章节清单必须跟着删（pin 在这张表上，留下就是永久泄漏）", 0, countRows(dbPath, "manga_chapter_cache", comicBook))
            assertFalse("漫画图片文件必须从 comic/ 删掉", Files.exists(comicFile))
            // 有声书：分段行 + 文件
            assertEquals("音频分段行必须跟着书一起删", 0, countRows(dbPath, "audio_cache", audioBook))
            assertFalse("音频文件必须从 audio/ 删掉", Files.exists(audioFile))
            // 带插图的小说：插图行 + 文件
            assertEquals("插图行必须跟着书一起删", 0, countRows(dbPath, "illustration_cache", novelBook))
            assertFalse("插图文件必须从 images/ 删掉", Files.exists(illustrationFile))
            // 旧语义里的两张表（防以后回退）
            assertEquals("book_cache_status 行必须跟着删", 0, countRows(dbPath, "book_cache_status", comicBook))
            assertEquals("有声书的 book_cache_status 也要走", 0, countRows(dbPath, "book_cache_status", audioBook))
            assertEquals("书架行本身当然也要没", 0, countRows(dbPath, "book_shelf", comicBook))

            // ========== 只删这一本：同一书源下的"旁观书"的行与文件必须原样 ==========
            assertEquals("别的书的图片行绝不能动", 1, countRows(dbPath, "manga_image_cache", comicBystander))
            assertEquals("别的书的章节清单绝不能动", 1, countRows(dbPath, "manga_chapter_cache", comicBystander))
            assertTrue("别的书的图片文件绝不能动", Files.exists(comicBystanderFile))
            assertEquals("别的书的音频行绝不能动", 1, countRows(dbPath, "audio_cache", audioBystander))
            assertTrue("别的书的音频文件绝不能动", Files.exists(audioBystanderFile))
            assertEquals("别的书的插图行绝不能动", 1, countRows(dbPath, "illustration_cache", novelBystander))
            assertTrue("别的书的插图文件绝不能动", Files.exists(illustrationBystanderFile))

            // ========== 正在跑的预取必须被取消（否则刚清完又被抓回来） ==========
            assertEquals(
                "移出书架必须取消正在跑的预取任务",
                0,
                client.get("/api/books/images/prefetch").body<List<MangaPrefetchStatus>>().size,
            )

            // ========== 幂等：再删一次同一本书也必须成功（行与文件都不在了） ==========
            assertEquals(HttpStatusCode.NoContent, deleteShelf(client, csrf, comicBook).status)
            assertEquals(HttpStatusCode.NoContent, deleteShelf(client, csrf, audioBook).status)
        } finally {
            releaseFetch.countDown()
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    /**
     * 直接对**唯一入口** `purgeBookMediaCaches` 的契约：
     * 幂等、容错（文件已被删）、本地书安全、以及音频预取取消。
     *
     * 走 HTTP 需要给 `legadoApplication` 再加一个"音频抓取注入接缝"，为一条断言扩生产 API 不值当，
     * 因此音频预取取消在这里（同一入口、同一服务实例）验证。
     */
    @Test
    fun `purge is idempotent tolerates missing files keeps local content and cancels audio prefetch`() {
        val dataDir = Files.createTempDirectory("legado-purge-idempotent")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        val database = Database(dbPath)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val missingFileBook = "$sourceId/book/audio-missing-file"
        val localUrl = "local://book123"
        try {
            database.initialize("test-password-1234")
            database.importSources(
                listOf("""{"bookSourceUrl":"$sourceId","bookSourceName":"媒体测试源","bookSourceType":2}"""),
                applyGroups = false,
            )
            val imageCache = ImageCache(dataDir.resolve("comic"), database)
            val audioCache = AudioCache(dataDir.resolve("audio"), database)
            val illustrations = IllustrationCache(dataDir.resolve("images"), database)

            // ① 预取目标：只登记（pending），不需要预置字节 ⇒ 预取一定会去抓
            val chapterUrl = "$audioBook/ch/0"
            audioCache.registerChapter(sourceId, audioBook, chapterUrl, 0, listOf(AudioTrackSpec(audioTrack)))
            database.saveTocCache(sourceId, audioBook, listOf(Chapter(0, "第 1 集", chapterUrl)))

            val prefetch = AudioPrefetchService(
                database = database,
                runner = RuleRunner { url -> error("Unexpected URL: $url") },
                audioCache = audioCache,
                fetcher = AudioFetcher(
                    config = testConfig(dataDir),
                    injectedFetcher = { _, _, _, _ ->
                        fetchStarted.countDown()
                        releaseFetch.await(20, TimeUnit.SECONDS)
                        throw java.io.IOException("test: 抓取已被取消")
                    },
                ),
                scope = scope,
            )
            prefetch.enqueue(sourceId, audioBook, fromChapterIndex = 0, ahead = 0)
            assertTrue("前置条件：音频预取必须真的跑起来", fetchStarted.await(20, TimeUnit.SECONDS))
            assertTrue("前置条件：状态里必须看得到它", prefetch.status().isNotEmpty())

            // ② 容错样本：行在、**盘上文件已经被删**（外部清理/磁盘事故）
            val staleRows = audioCache.registerChapter(
                sourceId, missingFileBook, "$missingFileBook/ch/0", 0, listOf(AudioTrackSpec("https://a.example.com/stale.mp3")),
            )
            audioCache.putReady(staleRows.single(), "audio/mpeg", mp3Bytes(512))
            assertTrue("前置条件：容错样本的文件必须先存在", Files.deleteIfExists(audioCache.fileFor(staleRows.single())!!))

            // ③ 本地书正文：在 book_content_cache 里，purge 与它无关（本地书语义不得被改）
            database.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = LocalBookParser.LOC_BOOK_SOURCE_ID, bookUrl = localUrl, name = "本地书", tocUrl = "$localUrl/toc",
                ),
                cover = null,
            )
            database.cacheBookContent(
                LocalBookParser.LOC_BOOK_SOURCE_ID, localUrl, "$localUrl/chapter_1",
                ChapterContent(title = "第一章", content = "本地书的正文"),
            )

            // ④ 第一次清理：行全清、任务取消
            purgeBookMediaCaches(
                sourceId = sourceId, bookUrl = audioBook, database = database,
                imageCache = imageCache, audioCache = audioCache, illustrationCache = illustrations,
                audioPrefetch = prefetch,
            )
            assertEquals("音频行必须清掉", 0, database.listAudioByBook(sourceId, audioBook).size)
            assertTrue("正在跑的音频预取任务必须被取消", prefetch.status().isEmpty())

            // ⑤ 幂等：行与文件都不存在时再调一次，不得抛错
            purgeBookMediaCaches(
                sourceId = sourceId, bookUrl = audioBook, database = database,
                imageCache = imageCache, audioCache = audioCache, illustrationCache = illustrations,
                audioPrefetch = prefetch,
            )
            // ⑥ 容错：盘上文件已经没了也必须正常返回（不抛异常就通过）
            purgeBookMediaCaches(
                sourceId = sourceId, bookUrl = missingFileBook, database = database,
                imageCache = imageCache, audioCache = audioCache, illustrationCache = illustrations,
            )
            assertEquals("文件已被删的行也必须跟着清掉", 0, database.listAudioByBook(sourceId, missingFileBook).size)

            // ⑦ 本地书：调用安全，且**正文缓存一动不动**（本地书的正文就是它的全部内容）
            purgeBookMediaCaches(
                sourceId = LocalBookParser.LOC_BOOK_SOURCE_ID, bookUrl = localUrl, database = database,
                imageCache = imageCache, audioCache = audioCache, illustrationCache = illustrations,
            )
            assertNotNull(
                "本地书正文在 book_content_cache：purge 绝不能碰它",
                database.cachedContent(LocalBookParser.LOC_BOOK_SOURCE_ID, localUrl, "$localUrl/chapter_1"),
            )

            // ⑧ 完全不存在的书：同样是 no-op（不能因为"没有这本书"就抛错）
            purgeBookMediaCaches(
                sourceId = sourceId, bookUrl = "$sourceId/book/never-existed", database = database,
                imageCache = imageCache, audioCache = audioCache, illustrationCache = illustrations,
            )
        } finally {
            releaseFetch.countDown()
            scope.cancel()
            database.close()
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    /**
     * 「不该删的东西」：**被别的书引用的同一份物理字节**必须留着。
     *
     * 三个缓存类的 `deleteBook` 都是「先删行、再按引用计数回收文件」，这里把这个契约钉在用例里：
     * 同一张插图被两本书引用（字节去重 / 同一图床地址落到同一份盘）时，移出其中一本
     * **只能删它自己的行**，文件必须留到最后一个引用者离开才回收 —— 否则另一本书会裂图。
     */
    @Test
    fun `purge keeps a physical file another book still references`() {
        val dataDir = Files.createTempDirectory("legado-purge-shared")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        val database = Database(dbPath)
        val bookA = "$sourceId/book/shared-a"
        val bookB = "$sourceId/book/shared-b"
        try {
            database.initialize("test-password-1234")
            val imageCache = ImageCache(dataDir.resolve("comic"), database)
            val audioCache = AudioCache(dataDir.resolve("audio"), database)
            val illustrations = IllustrationCache(dataDir.resolve("images"), database)

            val sharedFileUrl = "https://img.example.com/shared.jpg"
            val recordA = illustrations.put(
                sourceId, bookA, "$bookA/ch/0", sharedFileUrl, "image/jpeg", jpeg(seed = 11),
                chapterIndex = 0, segmentIndex = 0,
            )
            val sharedFile = illustrations.fileFor(recordA)
            assertNotNull("前置条件：共享文件必须落盘", sharedFile)

            // 第二本书引用**同一份物理文件**（真实形态：同一张图被两本书用到）
            database.recordIllustration(
                IllustrationRecord(
                    urlHash = illustrations.urlHash(sourceId, "https://img.example.com/shared-alias.jpg"),
                    sourceId = sourceId,
                    bookUrl = bookB,
                    chapterUrl = "$bookB/ch/0",
                    upstreamUrl = "https://img.example.com/shared-alias.jpg",
                    contentType = "image/jpeg",
                    byteSize = recordA.byteSize,
                    state = "ready",
                    filePath = recordA.filePath,
                ),
            )

            // 移出 A ⇒ A 的行没了，但**文件还在**（B 仍引用）
            purgeBookMediaCaches(
                sourceId = sourceId, bookUrl = bookA, database = database,
                imageCache = imageCache, audioCache = audioCache, illustrationCache = illustrations,
            )
            assertEquals("A 的行必须删掉", 0, database.listIllustrationsByBook(sourceId, bookA).size)
            assertEquals("B 的行绝不能动", 1, database.listIllustrationsByBook(sourceId, bookB).size)
            assertTrue("还有别的书引用这份字节 ⇒ 文件绝不能删（否则 B 直接裂图）", Files.exists(sharedFile))

            // 最后一个引用者也走了 ⇒ 此时才回收
            purgeBookMediaCaches(
                sourceId = sourceId, bookUrl = bookB, database = database,
                imageCache = imageCache, audioCache = audioCache, illustrationCache = illustrations,
            )
            assertFalse("引用计数归零后文件必须回收", Files.exists(sharedFile))
        } finally {
            database.close()
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private suspend fun awaitComicPrefetchVisible(client: HttpClient): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val res = client.get("/api/books/images/prefetch")
            if (res.status == HttpStatusCode.OK && res.body<List<MangaPrefetchStatus>>().isNotEmpty()) return true
            delay(25)
        }
        return false
    }

    private suspend fun deleteShelf(client: HttpClient, csrf: String, bookUrl: String) =
        client.delete("/api/bookshelf?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}") {
            header(AuthService.CSRF_HEADER, csrf)
        }

    private suspend fun login(client: HttpClient, pass: String): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = pass))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return res.body<LoginResponse>().csrfToken
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    /**
     * 直接数表里属于这本书的行。
     *
     * `book_cache_status` 没有公开读取方法（它的唯一消费者是书架列表的 join），而这里恰恰要在
     * **书已移出书架**之后断言它没了 ⇒ 只能直读库。SQLite 侧只发 SELECT，不影响被测写入路径。
     */
    private fun countRows(dbPath: String, table: String, bookUrl: String): Int =
        org.sqlite.SQLiteConfig().apply { setBusyTimeout(10_000) }
            .createConnection("jdbc:sqlite:$dbPath").use { connection: Connection ->
                connection.prepareStatement("select count(*) from $table where source_id=? and book_url=?").use { statement ->
                    statement.setString(1, sourceId)
                    statement.setString(2, bookUrl)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }

    private fun testConfig(dir: Path): ServerConfig = ServerConfig(
        host = "0.0.0.0", port = 8080, databasePath = "unused.sqlite",
        coverCacheDirectory = dir.resolve("covers"), webDavDirectory = dir.resolve("webdav"),
        initialAdminPassword = null, secureCookies = false,
        comicCacheDirectory = dir.resolve("comic"),
        audioCacheDirectory = dir.resolve("audio"),
        illustrationCacheDirectory = dir.resolve("images"),
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

    /** 一段"看起来是 MP3"的字节（魔数 `ID3`，判据收口在 [AudioBytes]）。 */
    private fun mp3Bytes(size: Int): ByteArray {
        val header = ByteArray(10).also {
            "ID3".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
            it[3] = 0x03
        }
        if (size <= header.size) return header.copyOf(size)
        return header.copyOf(size)
    }
}
