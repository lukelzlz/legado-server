package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 封面**常驻自愈**（[CoverSelfHeal]）：后期封面文件丢了，跑一轮就自动补回来。
 *
 * ## 为什么必须有这条链路
 * 导入时的补抓只在**那一刻**有效。之后封面文件仍会消失（磁盘清理、用户误删、容器换卷、
 * 迁移数据目录时只搬了库没搬 `covers/`）。此时 `book_shelf.cover_key` 还在、
 * `cover_cache.file_path` 还指向一个**已经不存在的文件** —— 书架一片裂图，
 * 而 `POST /bookshelf/refresh-covers` 永远修不到它（它按 `cover_key` **为空**过滤）。
 *
 * 因此选取条件必须是「`cover_key` 为空 **或** 按 `cover_key` 定位不到文件」，
 * 判定必须**真的去盘上看**。
 *
 * 全部用例不触网：下载走 `CoverCache` 的 `fetcher` 接缝，回源走 `fetchDetails` 接缝。
 */
class CoverSelfHealTest {

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private val sourceId = "https://cover.example.com"

    // ------------------------------------------------------------------
    // ① core：文件被删掉之后自动补回来
    // ------------------------------------------------------------------

    /**
     * 核心场景：**`cover_key` 有值、但文件已经不在盘上** ⇒ 一轮自愈后恢复出图。
     *
     * 断言四件事（缺一条都可能"看起来补好了实际没有"）：
     * 1. `cover_key` 仍能按它取到真图（PNG 魔数）—— 换一个 `CoverCache` 实例读，
     *    只依赖磁盘命名 + 数据库索引（进程内缓存救不了跨实例）；
     * 2. `cover_cache.file_path` 非空且指向的文件**真的存在**；
     * 3. 落盘仍是 v3 命名（`书名 (书哈希).png`），**不是第二套命名规则**；
     * 4. 同一批里健康的书**纹丝不动**。
     */
    @Test
    fun `cover_key 有值但文件被删掉时一轮自愈后恢复出图`() {
        withFixture { f ->
            val lost = f.seedCovered("丢封面的书", "$sourceId/book/lost")
            val healthy = f.seedCovered("正常的书", "$sourceId/book/healthy")
            val lostKey = lost.coverKey!!
            val healthyKey = healthy.coverKey!!
            val healthyFile = f.cache.file(healthyKey)!!

            // 人为删掉一本的封面文件（模拟磁盘清理/误删/换卷后文件不在）
            val lostFile = f.cache.file(lostKey)!!
            Files.delete(lostFile)
            assertFalse("确实删掉了", Files.exists(lostFile))

            val result = f.worker().runOnce()

            assertEquals("只有那本丢了文件的算缺封面", 1, result.missing)
            assertEquals(1, result.attempted)
            assertEquals("必须补成功", 1, result.healed)
            assertEquals(0, result.failed)
            assertEquals(0, result.remaining)

            val healed = f.database.getShelfBookByUrl(lost.bookUrl)!!
            assertEquals("同一张图 ⇒ 同一个 key（不是重新发明一套 key）", lostKey, healed.coverKey)
            assertEquals("健康的那本 key 不许变", healthyKey, f.database.getShelfBookByUrl(healthy.bookUrl)!!.coverKey)
            assertTrue("健康的那本文件必须还在", Files.isRegularFile(healthyFile))
            assertEquals("健康的那本不许被重下", 0, f.downloadsFor(healthyKey))
            assertEquals(
                "落盘必须是 v3 命名（自愈不得写第二份命名规则）",
                "丢封面的书 (${bookCacheHash(sourceId, lost.bookUrl)}).png",
                f.database.coverFilePath(lostKey),
            )
            f.assertReadableImage(lostKey)
        }
    }

    // ------------------------------------------------------------------
    // ② cover_key 为空（只有外链）同样要补
    // ------------------------------------------------------------------

    @Test
    fun `cover_key 为空但有可用外链时一轮补上`() {
        withFixture { f ->
            val book = f.seedBare("只有外链的书", "$sourceId/book/bare", "https://img.example.com/ok.png")
            assertNull("种出来的书必须没有本地封面", book.coverKey)

            val result = f.worker().runOnce()

            assertEquals(1, result.missing)
            assertEquals(1, result.healed)
            val after = f.database.getShelfBookByUrl(book.bookUrl)!!
            assertNotNull("必须落 cover_key", after.coverKey)
            assertEquals("外链就是来源，不该被改写", "https://img.example.com/ok.png", after.coverUrl)
            f.assertReadableImage(after.coverKey!!)
        }
    }

    /** `file_path` 被清空、但文件按**书哈希**仍能定位 ⇒ 不算「没封面」，绝不重复下载。 */
    @Test
    fun `file_path 为空但文件仍可定位时不判为缺封面`() {
        withFixture { f ->
            val book = f.seedCovered("索引失效的书", "$sourceId/book/stale")
            // 模拟索引列被清空（旧版本/迁移留下的形态）：文件还在，只是快路径没了
            f.database.clearCoverFilePath(book.coverKey!!)

            val result = f.worker().runOnce()

            assertEquals("文件还在就不该判为缺封面", 0, result.missing)
            assertEquals(0, result.attempted)
            assertEquals("健康文件必须没被重下", 0, f.downloads.get())
        }
    }

    // ------------------------------------------------------------------
    // ③ 失败退避（避免对着挂掉的上游反复热点打爆）
    // ------------------------------------------------------------------

    @Test
    fun `失败的书计入 failed 并按轮次退避，手动触发可立刻重试`() {
        withFixture(fetcher = { throw IllegalStateException("图床 403") }) { f ->
            f.seedBare("永远抓不到的书", "$sourceId/book/dead", "https://img.example.com/dead.png")
            val worker = f.worker()

            val first = worker.runOnce()
            assertEquals(1, first.attempted)
            assertEquals(1, first.failed)
            assertEquals(0, first.healed)

            // 紧接着的一轮：同一本不许再打上游（退避）
            val second = worker.runOnce()
            assertEquals("退避期内不再尝试", 0, second.attempted)
            assertEquals("但缺封面的本数必须如实回报", 1, second.missing)
            assertEquals(1, second.backoffSkipped)
            assertEquals("退避期不应产生新的请求", 1, f.downloads.get())

            // 手动触发（force）忽略退避：用户点了"现在就补"不该被挡住
            val forced = worker.runOnce(force = true)
            assertEquals(1, forced.attempted)
            assertEquals(1, forced.failed)
            assertEquals(2, f.downloads.get())
        }
    }

    // ------------------------------------------------------------------
    // ④ 分批投放（本轮上限，不是总量配额）
    // ------------------------------------------------------------------

    @Test
    fun `每轮最多尝试 20 本，剩下的由下一轮接着补`() {
        withFixture { f ->
            repeat(25) { i ->
                f.seedBare("书$i", "$sourceId/book/batch-$i", "https://img.example.com/batch-$i.png")
            }
            val worker = f.worker()

            val first = worker.runOnce()
            assertEquals(25, first.missing)
            assertEquals("单轮上限 20（资源上限，防止一次把整架书丢给上游）", 20, first.attempted)
            assertEquals(20, first.healed)
            assertEquals(5, first.remaining)

            val second = worker.runOnce()
            assertEquals(5, second.missing)
            assertEquals(5, second.attempted)
            assertEquals(5, second.healed)
            assertEquals("全部补完", 0, second.remaining)

            val third = worker.runOnce()
            assertTrue(third.ran)
            assertEquals(0, third.missing)
            assertEquals(0, third.attempted)
        }
    }

    // ------------------------------------------------------------------
    // ⑤ 单本失败不影响整批 + 开关
    // ------------------------------------------------------------------

    @Test
    fun `单本失败不影响同批其余书`() {
        withFixture(
            fetcher = { url ->
                if (url.contains("bad")) throw IllegalStateException("图床 403")
                "image/png" to pngBytes
            },
        ) { f ->
            f.seedBare("好书1", "$sourceId/book/good-1", "https://img.example.com/good-1.png")
            f.seedBare("坏书", "$sourceId/book/bad", "https://img.example.com/bad.png")
            f.seedBare("好书2", "$sourceId/book/good-2", "https://img.example.com/good-2.png")

            val result = f.worker().runOnce()

            assertEquals(3, result.attempted)
            assertEquals(2, result.healed)
            assertEquals(1, result.failed)
            assertEquals(2, f.database.listBookshelf().count { !it.coverKey.isNullOrBlank() })
        }
    }

    @Test
    fun `关闭时不抓任何东西`() {
        withFixture { f ->
            f.seedBare("书", "$sourceId/book/off", "https://img.example.com/off.png")
            val result = f.worker(enabled = false).runOnce()
            assertFalse(result.ran)
            assertEquals(0, result.attempted)
            assertEquals("关掉就必须一个请求都不发", 0, f.downloads.get())
        }
    }

    // ------------------------------------------------------------------
    // ⑥ 自引用数据（历史脏数据）不得被当成外链去抓
    // ------------------------------------------------------------------

    /**
     * 历史上编辑弹窗会把本服务自己的封面接口写进 `cover_url`（`/api/covers/<key>`，自引用）。
     * 自愈绝不许拿它去请求自己：那只会 404/401，且毫无意义（真正的封面就是 `cover_key`）。
     *
     * 写入侧（`Database.saveBookshelf`）已在剥离这种值，所以这里直接构造条目测**补抓器**的守卫：
     * 落库路径会拦住它，取数路径也必须拦住（双保险）。
     */
    @Test
    fun `自引用的 cover_url 不会被当成外链去抓`() {
        withFixture { f ->
            val book = f.seedCovered("自引用书", "$sourceId/book/self")
            val key = book.coverKey!!
            Files.delete(f.cache.file(key)!!)
            val item = f.database.getShelfBookByUrl(book.bookUrl)!!.copy(coverUrl = "/api/covers/${"a".repeat(64)}")

            val result = CoverBackfiller(f.database, f.cache).backfill(item)

            assertEquals(CoverBackfillOutcome.FAILED, result.outcome)
            assertEquals("绝不允许请求本服务自己的封面接口", 0, f.downloads.get())
            assertEquals("失败不许清掉原有的 cover_key", key, f.database.getShelfBookByUrl(book.bookUrl)!!.coverKey)
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private inner class Fixture(
        val directory: Path,
        val database: Database,
        val coverDir: Path,
        /** 种子的「随便都能成功」缓存；自愈用的实例在 [worker] 里另建（互不共享进程内缓存）。 */
        val cache: CoverCache,
        /** 自愈真正发起的下载次数（种子不计入）。 */
        val downloads: AtomicInteger,
        /** 自愈侧下载行为的接缝。 */
        var fetcher: (String) -> Pair<String, ByteArray>,
    ) {
        /** 种一本**已经有本地封面**的书，返回落库后的书架条目。 */
        fun seedCovered(name: String, bookUrl: String, url: String = "https://img.example.com/cover-${bookUrl.substringAfterLast('/')}.png"): BookshelfItem {
            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, name, "作者", "$bookUrl/toc", url), null)
            val item = database.getShelfBookByUrl(bookUrl)!!
            requireNotNull(materializeCover(database, cache, item, url)) { "种子封面必须落盘成功" }
            return database.getShelfBookByUrl(bookUrl)!!
        }

        /** 种一本**没有本地封面**的书（`cover_key` 为空，只有外链）。 */
        fun seedBare(name: String, bookUrl: String, coverUrl: String?): BookshelfItem {
            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, name, "作者", "$bookUrl/toc", coverUrl), null)
            return database.getShelfBookByUrl(bookUrl)!!
        }

        private val downloadsByKey = mutableMapOf<String, Int>()

        /** 用**独立实例**（= 生产里 worker 那一侧，没有进程内 written 缓存）跑一轮。 */
        fun worker(enabled: Boolean = true, batchLimit: Int = 20): CoverSelfHeal {
            val cache = CoverCache(coverDir, database) { url ->
                downloads.incrementAndGet()
                val key = ImageBytes.sha256(url)
                downloadsByKey[key] = (downloadsByKey[key] ?: 0) + 1
                fetcher(url)
            }
            return CoverSelfHeal(database, cache, enabled = enabled, batchLimit = batchLimit)
        }

        fun downloadsFor(key: String): Int = downloadsByKey[key] ?: 0

        /** 按 key 取到的必须是**真图片**（换新实例读：只依赖磁盘命名 + 索引）。 */
        fun assertReadableImage(key: String) {
            val located = CoverCache(coverDir, database).file(key)
            assertNotNull("按 cover_key 必须能定位到封面文件（否则书架裂图）", located)
            assertTrue("file_path 指向的文件必须真的存在", Files.exists(located!!))
            val bytes = Files.readAllBytes(located)
            assertTrue(
                "必须是真 PNG 字节（魔数 89 50 4E 47），实际 ${bytes.take(4).map { it.toInt() and 0xff }}",
                bytes.size > pngMagic.size && pngMagic.indices.all { bytes[it] == pngMagic[it] },
            )
            val path = database.coverFilePath(key)
            assertTrue(
                "cover_cache.file_path 必须回填且文件存在：$path",
                !path.isNullOrBlank() && Files.isRegularFile(coverDir.resolve(path)),
            )
        }
    }

    private fun withFixture(
        fetcher: ((String) -> Pair<String, ByteArray>)? = null,
        block: (Fixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("legado-selfheal")
        val database = Database(directory.resolve("legado.sqlite").toString())
        val coverDir = directory.resolve("covers")
        val downloads = AtomicInteger()
        // 种子数据的 fetcher：永远成功（返回真 PNG），且**不计入** downloads（只看自愈自己的请求）
        val cache = CoverCache(coverDir, database) { "image/png" to pngBytes }
        val fixture = Fixture(directory, database, coverDir, cache, downloads, fetcher ?: { "image/png" to pngBytes })
        try {
            database.initialize("password-for-test")
            block(fixture)
        } finally {
            database.close()
            val dbPath = directory.resolve("legado.sqlite").toString()
            listOf("", "-wal", "-shm").forEach { runCatching { Files.deleteIfExists(Path.of(dbPath + it)) } }
            runCatching { directory.toFile().deleteRecursively() }
        }
    }
}
