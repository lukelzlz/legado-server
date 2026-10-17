package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * P2 的 GC（环节 E12）：**滑窗保护 + 已读历史优先**淘汰。
 *
 * 这里锁定的全是"看不见的坏事"：
 * - 忘了保护滑窗 ⇒ 用户往下翻一章就断流（纯 LRU 的经典翻车）；
 * - 忘了问引用计数就删文件 ⇒ 另一本书的图突然全裂（表里还有行，盘上没了）；
 * - 忘记 pin ⇒ 用户手动下载的整本被后台保洁删掉；
 * - 滞回写错 ⇒ 磁盘在临界点反复抖动（每张图落盘都触发一次全盘淘汰）。
 *
 * 测试用小配额（1 KB）＋ `force = true`：配额是**比例**判据（90% / 75%），
 * 只要比例对，1 KB 与 10 GiB 的行为完全一致，不必真造 10 GiB 数据。
 */
class ImageCacheGcTest {

    private companion object {
        const val QUOTA = 1000L
        /** 每张图 200 字节 ⇒ 5 张刚好把 1 KB 配额撑满。 */
        const val IMAGE_BYTES = 200
    }

    @Test
    fun `does nothing while below the high water mark`() {
        withCache { cache, database, gc ->
            // ⚠️ `segmentIndex` 必须显式传且各不相同：它现在是**落盘文件名**的一部分
            //（章节目录名由章节哈希唯一确定），全传默认 0 会让这 4 张图写到同一个文件上（互相覆盖 ⇒ 占用与断言全失真）
            repeat(4) { index ->
                cache.put(
                    "src-a", "book-1", "ch-1", "https://img.example/$index.jpg", "image/jpeg",
                    jpegOfSize(IMAGE_BYTES, seed = index), chapterIndex = 0, segmentIndex = index,
                )
            }

            val result = gc.run()

            assertTrue("占用 800 < 900（90% 阈值）⇒ 不该动任何东西", !result.ran)
            assertEquals(0, result.evictedRows)
            assertEquals(4, database.countMangaImages())
        }
    }

    @Test
    fun `evicts down to the low water mark with hysteresis`() {
        withCache { cache, database, gc ->
            repeat(5) { index -> cache.put("src-a", "book-$index", "ch-1", "https://img.example/$index.jpg", "image/jpeg", jpegOfSize(IMAGE_BYTES, seed = index)) }
            assertEquals(5, database.countMangaImages())

            val result = gc.run(force = true)

            // 1000 > 900 ⇒ 触发；淘汰到 ≤ 750（75%）即停：删 2 张（每张 200）后 600 ≤ 750
            assertEquals(2, result.evictedRows)
            assertEquals(3, database.countMangaImages())
            assertTrue("必须落到低水位以下", result.usedBytes <= result.lowWaterBytes)
            assertEquals(400L, result.freedBytes)
            assertEquals("90% / 75% 滞回阈值", 900L, result.highWaterBytes)
            assertEquals(750L, result.lowWaterBytes)
        }
    }

    @Test
    fun `protects the sliding window around the reading anchor`() {
        withCache { cache, database, gc ->
            // 目录总章数 41：完成度 = 10/41 ≈ 0.24 < 80% ⇒ 不会有顺位 1 的淘汰，单独验滑窗
            database.saveTocCache("src-a", "book-1", (0..40).map { Chapter(it, "第${it}话", "ch-$it") })
            database.saveProgress(ReadingProgress(sourceId = "src-a", bookUrl = "book-1", chapterUrl = "ch-10", chapterIndex = 10))

            val protectedIdx = (10..25).toList() // 锚点章 ~ 后方 15 章：**必须全留住**
            val doomedIdx = listOf(26, 27, 28, 29, 30) // 滑窗之外，且已移出书架/失活 ⇒ 可淘汰
            (protectedIdx + doomedIdx).forEach { index ->
                // ⚠️ `chapterIndex` 必须显式传：GC 的滑窗保护**只认这一列**，
                // 落库时忘了带（默认 0）会让所有图看起来都"在很久以前"，保护形同虚设。
                cache.put(
                    "src-a", "book-1", "ch-$index", "https://img.example/$index.jpg", "image/jpeg",
                    jpegOfSize(IMAGE_BYTES, seed = index), chapterIndex = index, segmentIndex = 0,
                )
            }

            val result = gc.run(force = true)

            for (index in protectedIdx) {
                assertNotNull("滑窗内第 $index 章必须被保护（用户马上要读到）", cache.get("src-a", "https://img.example/$index.jpg"))
            }
            assertTrue("滑窗之外必须被淘汰（否则配额的闸门形同虚设）", result.evictedRows > 0)
            assertNull("第 26 章已在滑窗外", cache.get("src-a", "https://img.example/26.jpg"))
        }
    }

    @Test
    fun `never evicts a pinned book`() {
        withCache { cache, database, gc ->
            database.saveMangaChapter(MangaChapterRecord(sourceId = "src-a", bookUrl = "book-1", chapterUrl = "ch-0", chapterIndex = 0, pinned = true, imagesJson = "[]"))
            database.setMangaBookPinned("src-a", "book-1", true)
            // 五章各自的 `chapterUrl` 不同 ⇒ 章节目录名（章节哈希）不同 ⇒ 五个独立文件
            repeat(5) { index -> cache.put("src-a", "book-1", "ch-$index", "https://img.example/$index.jpg", "image/jpeg", jpegOfSize(IMAGE_BYTES, seed = index)) }

            val result = gc.run(force = true)

            assertEquals("免死金牌：pinned 书一张都不能删", 0, result.evictedRows)
            assertEquals(5, database.countMangaImages())
            assertTrue("必须把它记为「因保护而跳过」，否则日志会误报成「没有可删的候选」", result.protectedRows >= 5)
        }
    }

    @Test
    fun `prefers read history over colder abandoned prefetch`() {
        withCache { cache, database, gc ->
            val now = System.currentTimeMillis()
            // 甲书：读到 85/100 章（完成度 85% > 80%），锚点后方 10 章之外 ⇒ 顺位 1
            database.saveTocCache("src-a", "book-a", (0..99).map { Chapter(it, "第${it}话", "ch-$it") })
            database.saveProgress(ReadingProgress(sourceId = "src-a", bookUrl = "book-a", chapterUrl = "ch-85", chapterIndex = 85, scrollPosition = 0.5))
            val readHistory = (0..3).map { index ->
                cache.put(
                    "src-a", "book-a", "ch-$index", "https://img.example/a-$index.jpg", "image/jpeg",
                    jpegOfSize(IMAGE_BYTES, seed = 100 + index), chapterIndex = index, segmentIndex = 0,
                ).urlHash
            }
            // 乙书：不在书架、且 30 天没碰过 ⇒ 顺位 2，但**更冷**（last_access 更早）
            val abandoned = (0..3).map { index ->
                cache.put(
                    "src-b", "book-b", "ch-$index", "https://img.example/b-$index.jpg", "image/jpeg",
                    jpegOfSize(IMAGE_BYTES, seed = 200 + index), chapterIndex = index, segmentIndex = 0,
                ).urlHash
            }
            readHistory.forEach { database.touchMangaImage(it, now) }
            abandoned.forEach { database.touchMangaImage(it, now - 30L * 24 * 3600 * 1000) }

            val result = gc.run(force = true)

            assertTrue("必须至少淘汰几张", result.evictedRows >= 4)
            readHistory.forEach { assertNull("顺位 1（已读历史）必须先走", database.findMangaImageByUrlHash(it)) }
            assertTrue(
                "淘汰顺位必须压过「最冷优先」（否则就退化成纯 LRU 了）",
                database.listMangaImagesByBook("src-b", "book-b").isNotEmpty(),
            )
        }
    }

    @Test
    fun `keeps shared bytes when another row still references them`() {
        withCache { cache, database, gc ->
            val shared = jpegOfSize(IMAGE_BYTES, seed = 7)
            // 乙书 pinned 且引用同一份字节；甲书可淘汰 ⇒ 删甲的映射时必须保留物理文件
            database.saveMangaChapter(MangaChapterRecord(sourceId = "src-b", bookUrl = "book-b", chapterUrl = "ch-0", chapterIndex = 0, pinned = true, imagesJson = "[]"))
            database.setMangaBookPinned("src-b", "book-b", true)
            val keep = cache.put("src-b", "book-b", "ch-0", "https://img.example/keep.jpg", "image/jpeg", shared)
            cache.put("src-a", "book-a", "ch-0", "https://img.example/shared.jpg", "image/jpeg", shared)
            // 再塞 4 张独享字节的图，把物理占用顶到 1000 字节以上
            // （四章的 `chapterUrl` 不同 ⇒ 章节目录名不同 ⇒ 四个独立文件）
            repeat(4) { index -> cache.put("src-c", "book-c", "ch-$index", "https://img.example/c-$index.jpg", "image/jpeg", jpegOfSize(IMAGE_BYTES, seed = 300 + index)) }

            gc.run(force = true)

            assertNotNull("被引用计数保护：乙书还在用这份字节，文件绝不能删", cache.fileFor(keep.cacheKey))
            assertNotNull("乙书必须仍然命中", cache.get("src-b", "https://img.example/keep.jpg"))
            assertEquals("甲书的映射已被删，引用计数回到 1", 1, database.countMangaImageRefs(keep.cacheKey))
        }
    }

    @Test
    fun `maybeRun is cheap when the index is below quota`() {
        withCache { cache, database, gc ->
            cache.put("src-a", "book-1", "ch-1", "https://img.example/1.jpg", "image/jpeg", jpegOfSize(IMAGE_BYTES, seed = 1))

            val result = gc.maybeRun()

            assertTrue("预筛不过就不该做全盘扫描/淘汰", !result.ran)
            assertEquals(1, database.countMangaImages())
        }
    }

    @Test
    fun `stays silent when disabled`() {
        val dir = Files.createTempDirectory("legado-image-gc-off")
        val dbPath = Files.createTempFile("legado-image-gc-off", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            val cache = ImageCache(dir, database, QUOTA)
            repeat(5) { index -> cache.put("src-a", "book-$index", "ch-1", "https://img.example/$index.jpg", "image/jpeg", jpegOfSize(IMAGE_BYTES, seed = index)) }
            val gc = ImageCacheGc(database, cache, QUOTA, enabled = false)

            val result = gc.run(force = true)

            assertTrue("关闭后连 force 都不该动（回滚开关必须是硬的）", !result.ran)
            assertEquals(5, database.countMangaImages())
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

    private fun withCache(block: (ImageCache, Database, ImageCacheGc) -> Unit) {
        val dir = Files.createTempDirectory("legado-image-gc")
        val dbPath = Files.createTempFile("legado-image-gc", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            val cache = ImageCache(dir, database, QUOTA)
            block(cache, database, ImageCacheGc(database, cache, QUOTA))
        } finally {
            // 必须先 close 再删：否则 Windows 上会因文件占用删不掉（本机既有失败的根因）
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    /**
     * 造一张**指定字节数**的 JPEG：文件头 + SOF0 + 尾部填充。
     *
     * 填充挂在 SOF 之后，于是 `probeSize` 先命中 SOF（宽高正常），字节数又完全可控。
     *
     * ⚠️ [seed] 必须逐张不同：L1 是**内容寻址**的，字节一模一样就只会落一份物理文件
     * （这正是去重的意义，但会让"淘汰了几张"的断言全部失真——实测踩过）。
     */
    private fun jpegOfSize(totalBytes: Int, width: Int = 800, height: Int = 1200, seed: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        out.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0x00, 0x10))
        out.write(ByteArray(14))
        out.write(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x00, 0x11, 0x08))
        out.write(byteArrayOf((height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf(0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01))
        val header = out.toByteArray()
        if (totalBytes <= header.size) return header
        val bytes = header + ByteArray(totalBytes - header.size)
        // 把 seed 写进填充区尾部：尺寸不变，字节唯一
        bytes[bytes.size - 4] = (seed ushr 24).toByte()
        bytes[bytes.size - 3] = (seed ushr 16).toByte()
        bytes[bytes.size - 2] = (seed ushr 8).toByte()
        bytes[bytes.size - 1] = seed.toByte()
        return bytes
    }
}
