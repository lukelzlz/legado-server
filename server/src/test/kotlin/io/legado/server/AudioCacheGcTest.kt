package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * 方案 G2 · G8 的 GC：**滑窗保护（后方 5 章）+ 已听历史优先 + pinned 免死**。
 *
 * 这里锁定的全是"看不见的坏事"：
 * - 忘了保护滑窗 ⇒ 用户听到下一章就断流（纯 LRU 的经典翻车）；
 * - 忘记 pin ⇒ 用户手动"下载整本"的音频被后台保洁删掉；
 * - 滞回写错 ⇒ 磁盘在临界点反复抖动（每段音频落盘都触发一次全盘淘汰）；
 * - 顺位 1 只看下标不看完成度 ⇒ 把刚开始听的书的开头几章删掉。
 *
 * 测试用小配额（1 KB）＋ `force = true`：配额是**比例**判据（90% / 75%），
 * 只要比例对，1 KB 与 20 GiB 的行为完全一致，不必真造 20 GiB 数据。
 */
class AudioCacheGcTest {

    private companion object {
        const val QUOTA = 1000L
        /** 每段 200 字节 ⇒ 5 段刚好把 1 KB 配额撑满。 */
        const val TRACK_BYTES = 200
        const val SOURCE = "src-a"
        const val BOOK = "book-1"
        const val DAY_MS = 24L * 3600 * 1000
    }

    @Test
    fun `does nothing while below the high water mark`() {
        withGc { cache, database, gc ->
            repeat(4) { index -> seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3") }

            val result = gc.run()

            assertTrue("占用 800 < 900（90% 阈值）⇒ 不该动任何东西", !result.ran)
            assertEquals(0, result.evictedRows)
            assertEquals(4, database.countAudioTracks())
        }
    }

    @Test
    fun `evicts down to the low water mark with hysteresis`() {
        withGc { cache, database, gc ->
            repeat(5) { index -> seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3") }
            assertEquals(5, database.countAudioTracks())

            val result = gc.run(force = true)

            // 1000 > 900 ⇒ 触发；淘汰到 ≤ 750（75%）即停：删 2 段（每段 200）后 600 ≤ 750
            assertEquals(2, result.evictedRows)
            assertEquals(3, database.countAudioTracks())
            assertTrue("必须落到低水位以下", result.usedBytes <= result.lowWaterBytes)
            assertEquals(400L, result.freedBytes)
            assertEquals("90% / 75% 滞回阈值", 900L, result.highWaterBytes)
            assertEquals(750L, result.lowWaterBytes)
        }
    }

    @Test
    fun `evicted rows are removed from the table first and their bytes are reclaimed`() {
        withGc { cache, database, gc ->
            val keys = (0 until 5).map { index -> seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3") }

            gc.run(force = true)

            // 先表后盘：被淘汰的行必须已经不在了，且**物理文件也没了**
            val survivors = database.listAllAudio().map { it.cacheKey }.toSet()
            val evicted = keys.filterNot { it in survivors }
            assertTrue("必须真的淘汰了东西", evicted.isNotEmpty())
            evicted.forEach { key ->
                assertNull("被淘汰的行必须删掉", database.findAudioByKey(key))
                assertFalse("被淘汰的字节必须回收", Files.exists(cache.store.pathFor(key)))
            }
            assertTrue("淘汰后表与盘必须一致", cache.stats().totalBytes == cache.stats().diskBytes)
        }
    }

    @Test
    fun `protects the sliding window of 5 chapters ahead of the listening anchor`() {
        withGc { cache, database, gc ->
            // 目录总章数 41：完成度 = 10/41 ≈ 0.24 < 80% ⇒ 不会有顺位 1 的淘汰，单独验滑窗
            database.saveTocCache(SOURCE, BOOK, (0..40).map { Chapter(it, "第${it}集", "ch-$it") })
            database.saveProgress(
                ReadingProgress(sourceId = SOURCE, bookUrl = BOOK, chapterUrl = "ch-10", chapterIndex = 10, positionMs = 0),
            )

            val protectedIdx = (10..15).toList() // 锚点章 ~ 后方 **5** 章（音频窗口）：必须全留住
            val doomedIdx = listOf(16, 17, 18, 19, 20) // 滑窗之外，且已移出书架/失活 ⇒ 可淘汰
            (protectedIdx + doomedIdx).forEach { index ->
                // ⚠️ `chapterIndex` 必须显式落库：GC 的滑窗保护**只认这一列**，
                // 落库时忘了带（默认 0）会让所有数据都"看起来很久以前"，保护形同虚设。
                seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3")
            }

            val result = gc.run(force = true)

            val remaining = database.listAllAudio().map { it.chapterIndex }.toSet()
            protectedIdx.forEach { assertTrue("滑窗内第 $it 章绝不能被删", it in remaining) }
            assertTrue("滑窗外的冷数据必须被淘汰", doomedIdx.any { it !in remaining })
            assertTrue("本轮必须真的保护过行（protected > 0）", result.protectedRows > 0)
        }
    }

    @Test
    fun `audio window is 5 chapters — a chapter far behind is history, the middle state is kept`() {
        withGc { cache, database, gc ->
            // 10 章的书、读到第 9 章 ⇒ 完成度 0.9 > 80% ⇒ 顺位 1 生效
            database.saveTocCache(SOURCE, BOOK, (0..9).map { Chapter(it, "第${it}集", "ch-$it") })
            database.saveProgress(
                ReadingProgress(sourceId = SOURCE, bookUrl = BOOK, chapterUrl = "ch-9", chapterIndex = 9, positionMs = 0),
            )
            // 书还在书架上 ⇒ 中间态那几段不会因为"已移出书架"而被顺位 2 带走
            database.saveBookshelf(
                BookshelfWriteRequest(sourceId = SOURCE, bookUrl = BOOK, name = "测试有声书", tocUrl = BOOK),
                cover = null,
            )

            // gap = 5：`chapterIndex < 9 - 5 = 4` 才算已听历史
            val history = listOf(0, 1, 2, 3)
            val middle = listOf(4, 5, 6, 7, 8)
            history.forEach { index ->
                // 30 天前访问过 + 已完成 ⇒ 典型的历史冷数据
                seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3", lastAccessAt = System.currentTimeMillis() - 30 * DAY_MS)
            }
            middle.forEach { index ->
                // 刚刚访问过 + 仍在书架 ⇒ 既不在滑窗、也不属历史 ⇒ **宁可留着占盘**
                seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3", lastAccessAt = System.currentTimeMillis())
            }

            val result = gc.run(force = true)

            val remaining = database.listAllAudio().map { it.chapterIndex }.toSet()
            assertTrue("落后 5 章以上且全书完成度 > 80% ⇒ 必须被淘汰", history.any { it !in remaining })
            assertTrue("既不在滑窗、也不属历史（中间态）⇒ 宁可留着占盘", middle.all { it in remaining })
            assertTrue("必须真的淘汰了东西", result.evictedRows > 0)
        }
    }

    @Test
    fun `pinned book is never evicted automatically`() {
        withGc { cache, database, gc ->
            val keys = (0 until 5).map { index -> seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3") }
            database.setAudioBookPinned(SOURCE, BOOK, true)

            val result = gc.run(force = true)

            assertEquals("免死金牌 ⇒ 一段都不该删", 0, result.evictedRows)
            assertEquals(5, database.countAudioTracks())
            keys.forEach { assertNotNull("pinned 的行必须完好", database.findAudioByKey(it)) }
            assertEquals("5 行都进了保护集", 5, result.protectedRows)
        }
    }

    @Test
    fun `disabled switch is hard — force cannot bypass it`() {
        val dir = Files.createTempDirectory("legado-audio-gc-off")
        val dbPath = Files.createTempFile("legado-audio-gc-off", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            val cache = AudioCache(dir, database, QUOTA)
            val gc = AudioCacheGc(database, cache, QUOTA, enabled = false)
            repeat(5) { index -> seed(cache, database, "ch-$index", index, "https://a.example/$index.mp3") }

            val result = gc.run(force = true)

            assertTrue("关闭后连 force 都不该动（回滚开关必须是硬的）", !result.ran)
            assertEquals(5, database.countAudioTracks())
            // 物理字节也必须还在（GC 是唯一会删字节的路径）
            assertEquals(1000L, cache.physicalBytes())
        } finally {
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /**
     * 预置一段"已落盘且 ready"的音频，并**显式设定"最后访问时间"**。
     *
     * 淘汰顺序完全由 `last_access_at` 决定（SQL 按它升序），因此这里必须可控——
     * 依赖"同一毫秒内登记的顺序"会让断言随机翻车。
     *
     * 每段字节**互不相同**（尾部写 seed）：否则"淘汰了几段"的断言会失真（内容寻址的老坑）。
     */
    private fun seed(
        cache: AudioCache,
        database: Database,
        chapterUrl: String,
        chapterIndex: Int,
        upstreamUrl: String,
        lastAccessAt: Long = 1_000_000L + chapterIndex,
    ): String {
        val rows = cache.registerChapter(SOURCE, BOOK, chapterUrl, chapterIndex, listOf(AudioTrackSpec(upstreamUrl)))
        val key = rows.first().cacheKey
        cache.store.writeStream(key) { it.write(mp3OfSize(TRACK_BYTES, seed = chapterIndex)) }
        cache.markReady(rows.first(), "audio/mpeg", TRACK_BYTES.toLong())
        database.touchAudio(key, lastAccessAt)
        return key
    }

    private fun withGc(block: (AudioCache, Database, AudioCacheGc) -> Unit) {
        val dir = Files.createTempDirectory("legado-audio-gc")
        val dbPath = Files.createTempFile("legado-audio-gc", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            val cache = AudioCache(dir, database, QUOTA)
            block(cache, database, AudioCacheGc(database, cache, QUOTA))
        } finally {
            // 必须先 close 再删：否则 Windows 上会因文件占用删不掉（本机既有失败的根因）
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    /** 造一段**恰好 [totalBytes] 字节**的 MP3（ID3v2 头 + 尾部填充，尾部写 seed 保证字节唯一）。 */
    private fun mp3OfSize(totalBytes: Int, seed: Int = 0): ByteArray {
        val header = ByteArray(10).also {
            "ID3".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
            it[3] = 0x03
        }
        if (totalBytes <= header.size) return header.copyOf(totalBytes)
        val bytes = header.copyOf(totalBytes)
        // 尾部写 seed：长度不变、字节唯一（内容寻址的老坑：字节一样会让"淘汰了几段"失真）
        bytes[bytes.size - 4] = (seed ushr 24).toByte()
        bytes[bytes.size - 3] = (seed ushr 16).toByte()
        bytes[bytes.size - 2] = (seed ushr 8).toByte()
        bytes[bytes.size - 1] = seed.toByte()
        return bytes
    }
}
