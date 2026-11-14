package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * 自动缓存（小说 / 漫画 / 有声）的**设置**与**调度**。
 *
 * 锁定的都是"看起来能用、实际会坏"的形态：
 * - 默认值必须是**全关 + 60 分钟 + 每轮 1 本**（不主动打开就绝不能有后台抓取）；
 * - 局部更新必须「改哪个存哪个」（勾一下开关不能把用户还没保存的输入吞掉）；
 * - 非法间隔/本数必须**明确报错且一个字段都不落库**（不能出现"半保存"）；
 * - `ignore_quota` 必须**真的透传成 `pin=true`**（否则书会被 GC 悄悄删掉，用户以为已经缓存完了）；
 * - 开关关掉时**一本都不许入队**。
 *
 * 全程不触网：入队走构造参数注入的假服务，直接断言收到的任务。
 */
class AutoCacheSettingsTest {

    private class Fixture(
        val dataDir: Path,
        val database: Database,
        /** 小说入队收到的请求。 */
        val novels: MutableList<CachedBookRequest>,
        /** 漫画入队收到的请求。 */
        val comics: MutableList<AutoCacheMediaRequest>,
        /** 有声入队收到的请求。 */
        val audios: MutableList<AutoCacheMediaRequest>,
        /** 调度器打的日志。 */
        val logs: MutableList<String>,
    ) {
        val scheduler: AutoCacheScheduler = AutoCacheScheduler(
            database = database,
            enqueueNovel = { novels += it },
            enqueueComic = { comics += it; 1 },
            enqueueAudio = { audios += it; 1 },
            log = { logs += it },
        )
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val dataDir = Files.createTempDirectory("legado-auto-cache")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        var db: Database? = null
        try {
            db = Database(dbPath)
            db.initialize("test-password-1234")
            block(Fixture(dataDir, db, mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf()))
        } finally {
            // ⚠️ 必须先 close 再删：Windows 不允许删除被占用的 SQLite（见 AGENTS.md 的 52 失败根因）
            db?.close()
            dataDir.toFile().deleteRecursively()
        }
    }

    private val now = LocalDateTime.of(2026, 10, 18, 12, 0, 0)

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    @Test
    fun `defaults are all off with a 60 minute interval and one book per round`() = withFixture { fixture ->
        val settings = readAutoCacheSettings(fixture.database)

        // 三组默认**全关**：没主动打开就不该有任何后台抓取
        assertFalse(settings.novelEnabled)
        assertFalse(settings.comicEnabled)
        assertFalse(settings.audioEnabled)
        // 间隔 60 分钟、每轮 1 本（三组各自的默认值都要对）
        assertEquals(60, settings.novelIntervalMinutes)
        assertEquals(60, settings.comicIntervalMinutes)
        assertEquals(60, settings.audioIntervalMinutes)
        assertEquals(1, settings.novelBooksPerRound)
        assertEquals(1, settings.comicBooksPerRound)
        assertEquals(1, settings.audioBooksPerRound)
        // 漫画/有声的「忽略内存大小限制」默认关；小说**没有**这一项
        assertFalse(settings.comicIgnoreQuota)
        assertFalse(settings.audioIgnoreQuota)
    }

    /** 局部更新必须「改哪个存哪个」，否则点一下开关会把用户没保存的输入一起写进去。 */
    @Test
    fun `partial update only touches the provided fields`() = withFixture { fixture ->
        val afterSwitch = applyAutoCacheSettingsUpdate(
            fixture.database,
            AutoCacheSettingsUpdate(comicEnabled = true),
        )
        assertTrue(afterSwitch.comicEnabled)
        // 其余字段全部保持默认，没有被这次更新带偏
        assertFalse(afterSwitch.novelEnabled)
        assertFalse(afterSwitch.audioEnabled)
        assertEquals(60, afterSwitch.comicIntervalMinutes)
        assertEquals(1, afterSwitch.comicBooksPerRound)
        assertFalse(afterSwitch.comicIgnoreQuota)

        val afterNumbers = applyAutoCacheSettingsUpdate(
            fixture.database,
            AutoCacheSettingsUpdate(novelIntervalMinutes = 15, novelBooksPerRound = 3),
        )
        assertEquals(15, afterNumbers.novelIntervalMinutes)
        assertEquals(3, afterNumbers.novelBooksPerRound)
        // 上一次改的漫画开关还在
        assertTrue(afterNumbers.comicEnabled)

        val afterQuota = applyAutoCacheSettingsUpdate(
            fixture.database,
            AutoCacheSettingsUpdate(audioIgnoreQuota = true),
        )
        assertTrue(afterQuota.audioIgnoreQuota)
        // 没传的字段一个都不许动
        assertEquals(15, afterQuota.novelIntervalMinutes)
        assertTrue(afterQuota.comicEnabled)
    }

    @Test
    fun `invalid interval or books per round is rejected without writing anything`() = withFixture { fixture ->
        // 间隔必须 1..1440
        assertRejected("小说间隔 0") {
            applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(novelIntervalMinutes = 0))
        }
        assertRejected("漫画间隔 1441") {
            applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(comicIntervalMinutes = 1441))
        }
        assertRejected("有声间隔负数") {
            applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(audioIntervalMinutes = -5))
        }
        // 每轮本数必须 1..20
        assertRejected("小说每轮 0 本") {
            applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(novelBooksPerRound = 0))
        }
        assertRejected("漫画每轮 21 本") {
            applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(comicBooksPerRound = 21))
        }

        // **半保存**是必须挡住的形态：合法字段与非法字段同时传时，一个都不许落库
        assertRejected("启用 + 非法间隔") {
            applyAutoCacheSettingsUpdate(
                fixture.database,
                AutoCacheSettingsUpdate(novelEnabled = true, novelIntervalMinutes = 0),
            )
        }
        val settings = readAutoCacheSettings(fixture.database)
        assertFalse("非法更新整体被拒时开关也不许被打开", settings.novelEnabled)
        assertEquals(60, settings.novelIntervalMinutes)
    }

    /** 库里存了坏值（手工改库/旧版本残留）时回退默认，绝不因为一个脏字段把设置接口打成 500。 */
    @Test
    fun `corrupt stored values fall back to defaults instead of throwing`() = withFixture { fixture ->
        fixture.database.setSetting(AUTO_CACHE_NOVEL_INTERVAL_KEY, "abc")
        fixture.database.setSetting(AUTO_CACHE_COMIC_BOOKS_KEY, "9999")
        fixture.database.setSetting(AUTO_CACHE_AUDIO_ENABLED_KEY, "yes")

        val settings = readAutoCacheSettings(fixture.database)
        assertEquals(60, settings.novelIntervalMinutes)
        assertEquals(1, settings.comicBooksPerRound)
        // 只有字面量 "true" 才算打开（"yes" 不是）
        assertFalse(settings.audioEnabled)
    }

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    /**
     * `ignore_quota` 的落地语义 = `pin = true`（既有 pinned 免 GC 机制）。
     *
     * 这条断言是本次改动的**核心契约**：传丢了 `pin`，书迟早被 GC 删掉，
     * 而用户看到的是「明明勾了忽略内存限制，缓存还是没了」。
     */
    @Test
    fun `ignore_quota reaches the prefetch services as pin true`() = withFixture { fixture ->
        fixture.database.saveBookshelf(comic("https://comic/1"), null)
        fixture.database.saveBookshelf(audio("https://audio/1"), null)
        applyAutoCacheSettingsUpdate(
            fixture.database,
            AutoCacheSettingsUpdate(comicEnabled = true, comicIgnoreQuota = true, audioEnabled = true, audioIgnoreQuota = true),
        )

        runBlocking { fixture.scheduler.tick(now) }

        val comicTask = fixture.comics.single()
        assertEquals("https://comic/1", comicTask.bookUrl)
        assertEquals(0, comicTask.fromChapterIndex)
        assertTrue("漫画的忽略内存限制必须落成 pin=true", comicTask.pin)

        val audioTask = fixture.audios.single()
        assertEquals("https://audio/1", audioTask.bookUrl)
        assertTrue("有声的忽略内存限制必须落成 pin=true", audioTask.pin)
    }

    @Test
    fun `unchecked ignore quota keeps pin false`() = withFixture { fixture ->
        fixture.database.saveBookshelf(comic("https://comic/1"), null)
        applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(comicEnabled = true, comicIgnoreQuota = false))

        runBlocking { fixture.scheduler.tick(now) }

        assertFalse("没勾忽略内存限制就绝不能 pinned", fixture.comics.single().pin)
    }

    @Test
    fun `disabled groups never enqueue anything`() = withFixture { fixture ->
        // 书架里三种书都有，但三组开关全关（默认值）
        fixture.database.saveBookshelf(novel("https://book/1"), null)
        fixture.database.beginBookCache("https://s1.test", "https://book/1", 1)
        fixture.database.saveBookshelf(comic("https://comic/1"), null)
        fixture.database.saveBookshelf(audio("https://audio/1"), null)

        runBlocking { fixture.scheduler.tick(now) }

        assertTrue("开关全关时小说不许入队", fixture.novels.isEmpty())
        assertTrue("开关全关时漫画不许入队", fixture.comics.isEmpty())
        assertTrue("开关全关时有声不许入队", fixture.audios.isEmpty())
    }

    @Test
    fun `novel reuses the existing pending caching 口径`() = withFixture { fixture ->
        // 只有 state in ('pending','caching') 的书才算「缓存未完成」
        fixture.database.saveBookshelf(novel("https://book/pending"), null)
        fixture.database.beginBookCache("https://s1.test", "https://book/pending", 5)
        // 已缓存完（ready）的书不该再排进队列
        fixture.database.saveBookshelf(novel("https://book/done"), null)
        fixture.database.beginBookCache("https://s1.test", "https://book/done", 1)
        fixture.database.finishBookCache("https://s1.test", "https://book/done")

        applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(novelEnabled = true))
        runBlocking { fixture.scheduler.tick(now) }

        assertEquals(1, fixture.novels.size)
        assertEquals("https://book/pending", fixture.novels.single().bookUrl)
    }

    @Test
    fun `books per round caps the batch and the interval gates the next round`() = withFixture { fixture ->
        repeat(3) { index ->
            fixture.database.saveBookshelf(comic("https://comic/$index"), null)
        }
        applyAutoCacheSettingsUpdate(
            fixture.database,
            AutoCacheSettingsUpdate(comicEnabled = true, comicBooksPerRound = 2, comicIntervalMinutes = 60),
        )

        runBlocking { fixture.scheduler.tick(now) }
        assertEquals("每轮最多 2 本", 2, fixture.comics.size)

        // 同一个时刻再 tick 一次：间隔没到，不该再入队
        runBlocking { fixture.scheduler.tick(now.plusMinutes(30)) }
        assertEquals("间隔没到不许再跑一轮", 2, fixture.comics.size)

        // 到了下一个整间隔 ⇒ 再跑一轮
        runBlocking { fixture.scheduler.tick(now.plusMinutes(60)) }
        assertEquals("满 60 分钟必须再跑一轮", 4, fixture.comics.size)
    }

    @Test
    fun `a fully cached comic is no longer a candidate`() = withFixture { fixture ->
        val bookUrl = "https://comic/full"
        fixture.database.saveBookshelf(comic(bookUrl), null)
        // 目录 2 章，且两章都整章就绪 ⇒ 已缓存完，不该再占额度
        fixture.database.saveTocCache(
            "https://s1.test", "$bookUrl/toc",
            listOf(Chapter(0, "第1话", "$bookUrl/0"), Chapter(1, "第2话", "$bookUrl/1")),
        )
        // 目录缓存的 toc_url 与书架 toc_url 必须一致，`readCachedToc` 才找得到
        seedReadyMangaChapter(fixture.database, bookUrl, "$bookUrl/0", 0)
        seedReadyMangaChapter(fixture.database, bookUrl, "$bookUrl/1", 1)

        applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(comicEnabled = true))
        runBlocking { fixture.scheduler.tick(now) }

        assertTrue("已经缓存完的书不该再被自动缓存挑中", fixture.comics.isEmpty())
    }

    @Test
    fun `media ahead covers the whole book when the toc is cached`() = withFixture { fixture ->
        val bookUrl = "https://comic/1"
        fixture.database.saveBookshelf(comic(bookUrl), null)
        fixture.database.saveTocCache(
            "https://s1.test", "$bookUrl/toc",
            listOf(Chapter(0, "第1话", "$bookUrl/0"), Chapter(1, "第2话", "$bookUrl/1"), Chapter(2, "第3话", "$bookUrl/2")),
        )

        applyAutoCacheSettingsUpdate(fixture.database, AutoCacheSettingsUpdate(comicEnabled = true))
        runBlocking { fixture.scheduler.tick(now) }

        // ahead = 章节数 - 1（含起始章共 ahead+1 章）⇒ 从第 0 章起正好整本
        assertEquals(2, fixture.comics.single().ahead)
        assertEquals(0, fixture.comics.single().fromChapterIndex)
    }

    @Test
    fun `every round logs one line per book kind`() = withFixture { fixture ->
        fixture.database.saveBookshelf(comic("https://comic/1"), null)
        applyAutoCacheSettingsUpdate(
            fixture.database,
            AutoCacheSettingsUpdate(novelEnabled = true, comicEnabled = true, audioEnabled = true),
        )

        runBlocking { fixture.scheduler.tick(now) }

        // 三型各一条，格式固定便于在容器日志里 grep
        assertTrue(fixture.logs.any { it.startsWith("[auto-cache] novel: queued=0 candidates=0 skipped=0") })
        assertTrue(fixture.logs.any { it.startsWith("[auto-cache] comic: queued=1 candidates=1 skipped=0") })
        assertTrue(fixture.logs.any { it.startsWith("[auto-cache] audio: queued=0 candidates=0 skipped=0") })
    }

    @Test
    fun `a book already running is skipped so the next one gets a turn`() = withFixture { fixture ->
        fixture.database.saveBookshelf(comic("https://comic/a"), null)
        fixture.database.saveBookshelf(comic("https://comic/b"), null)
        applyAutoCacheSettingsUpdate(
            fixture.database,
            AutoCacheSettingsUpdate(comicEnabled = true, comicBooksPerRound = 1),
        )

        val onlyA = AutoCacheScheduler(
            database = fixture.database,
            enqueueNovel = { },
            enqueueComic = { fixture.comics += it; 1 },
            enqueueAudio = { 0 },
            // A 正在跑 ⇒ 这一轮必须轮到 B，否则每轮都挑中 A、其余书永远轮不到
            comicRunning = { setOf("https://s1.test\u0000https://comic/a") },
        )

        runBlocking { onlyA.tick(now) }

        assertEquals(1, fixture.comics.size)
        assertEquals("https://comic/b", fixture.comics.single().bookUrl)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun novel(bookUrl: String) = BookshelfWriteRequest(
        sourceId = "https://s1.test",
        bookUrl = bookUrl,
        name = "小说 $bookUrl",
        tocUrl = "$bookUrl/toc",
    )

    private fun comic(bookUrl: String) = BookshelfWriteRequest(
        sourceId = "https://s1.test",
        bookUrl = bookUrl,
        name = "漫画 $bookUrl",
        tocUrl = "$bookUrl/toc",
        bookKind = "comic",
    )

    private fun audio(bookUrl: String) = BookshelfWriteRequest(
        sourceId = "https://s1.test",
        bookUrl = bookUrl,
        name = "有声 $bookUrl",
        tocUrl = "$bookUrl/toc",
        bookKind = "audio",
    )

    /** 让「整章图片全部落盘」这条唯一口径成立（`state='ready'` 才是目录绿点）。 */
    private fun seedReadyMangaChapter(database: Database, bookUrl: String, chapterUrl: String, index: Int) {
        database.saveMangaChapter(
            MangaChapterRecord(
                sourceId = "https://s1.test",
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                chapterIndex = index,
                imagesJson = "[]",
                total = 0,
                ready = 0,
                state = "ready",
            ),
        )
    }

    private fun assertRejected(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what 应当被拒绝（IllegalArgumentException），但实际通过了")
        } catch (expected: IllegalArgumentException) {
            // 错误信息必须**可读**：路由层直接把 message 回给前端（400）
            assertTrue("错误信息不能为空：$what", (expected.message ?: "").isNotBlank())
        }
    }

    /** 让「小说没有 ignore_quota 这一项」这件事在编译期就成立（防止有人好心加一个用不上的字段）。 */
    @Test
    fun `only comic and audio expose ignore quota`() {
        val fields = AutoCacheSettings::class.java.declaredFields.map { it.name }
        assertTrue(fields.contains("comicIgnoreQuota"))
        assertTrue(fields.contains("audioIgnoreQuota"))
        assertNull(
            "小说不该有 ignore_quota（正文缓存不占图/音配额）",
            fields.firstOrNull { it.startsWith("novel") && it.contains("Ignore") },
        )
    }
}
