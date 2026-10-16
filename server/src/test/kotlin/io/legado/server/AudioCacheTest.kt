package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * 方案 G2 · G2/G3 的核心两件：`AudioCache`（URL 寻址的唯一副本）与 `AudioFetcher`（流式抓取收口）。
 *
 * 这些用例锁定的都是**静默失败**：表里有行但盘上没文件（播放器 404 而数据库看起来正常）、
 * 中断留下半截 `.part`（播到一半断流）、错误页/不支持格式被当成音频存下来、
 * 重复登记把 ready 打回 pending（每播一次都重新下载）。
 *
 * ⚠️ 与漫画最关键的差异：音频是**大字节流**，抓取路径必须**边读边写**。
 * 这里用注入接缝（不触网）验证"写入的是完整字节、且中途失败不留脏文件"。
 */
class AudioCacheTest {

    private companion object {
        /** 与生产默认值一致（20 GiB，G-4 裁决）。 */
        const val TEST_QUOTA_BYTES = 20L * 1024 * 1024 * 1024
        const val SOURCE = "https://audio.example.com"
        const val BOOK = "$SOURCE/book/1"
        const val CHAPTER = "$SOURCE/chapter/1"
    }

    // ------------------------------------------------------------------
    // AudioCache
    // ------------------------------------------------------------------

    @Test
    fun `registers chapter tracks in segment order and keeps them pending`() {
        withCache { cache, _ ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3", "b.mp3", "c.mp3"))

            assertEquals(3, rows.size)
            assertEquals("必须按 segmentIndex 升序返回", listOf(0, 1, 2), rows.map { it.segmentIndex })
            assertTrue("登记阶段一律是 pending", rows.all { it.state == "pending" })
            assertEquals("pending 不是命中", null, cache.getByKey(rows[0].cacheKey))
            // 但**绝不能删这些行**：key 不可逆，删了就再也找不到那段音频的地址了
            assertNotNull("登记行必须留着（否则服务端不知道去哪抓）", cache.rowByKey(rows[0].cacheKey))
        }
    }

    @Test
    fun `key is url addressed so the frontend can predict it before fetching`() {
        withCache { cache, _ ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))

            // G-2 裁决：URL 寻址 ⇒ key 可预测（前端在抓取前就能拼出 src）
            assertEquals(AudioBytes.sha256("$SOURCE\nhttps://a.example/a.mp3"), rows[0].cacheKey)
            // 含 sourceId ⇒ 跨源不脏命中
            assertTrue(cache.cacheKey("other-source", "https://a.example/a.mp3") != rows[0].cacheKey)
        }
    }

    @Test
    fun `markReady refuses to write ready when the bytes are not on disk`() {
        withCache { cache, _ ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))
            var threw = false
            try {
                // 「先盘后表」的反面：磁盘上没有文件就写 ready ⇒ 播放器拿到 404 而数据库看起来正常
                cache.markReady(rows[0], "audio/mpeg", 123, cache.relativePathFor(rows[0], "audio/mpeg"))
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("文件不在时必须拒绝写 ready", threw)
        }
    }

    @Test
    fun `drops the row when the file disappeared instead of pretending a hit`() {
        withCache { cache, database ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))
            writeReady(cache, rows[0], mp3Bytes(1024))
            assertNotNull("落盘后必须命中", cache.getByKey(rows[0].cacheKey))

            Files.deleteIfExists(cache.fileFor(rows[0].cacheKey)!!)

            assertNull("盘上没文件就必须回源，绝不能假装命中", cache.getByKey(rows[0].cacheKey))
            assertNull("失效的行必须被清掉", database.findAudioByKey(rows[0].cacheKey))
        }
    }

    @Test
    fun `re-registering a chapter never resets ready rows back to pending`() {
        withCache { cache, _ ->
            val first = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3", "b.mp3"))
            writeReady(cache, first[0], mp3Bytes(2048))

            // 二次打开同一章（清单重新登记）——ready 绝不能被重置，否则每播一次都重新下载
            val again = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3", "b.mp3"))

            assertEquals("ready 必须保持", "ready", again.first { it.cacheKey == first[0].cacheKey }.state)
            assertEquals("另一个仍是 pending", "pending", again.first { it.cacheKey == first[1].cacheKey }.state)
            // 还有分段没落定 ⇒ 仍算 pending（"整章就绪"还没发生，也没失败）
            assertEquals("pending", cache.chapterState(again))

            // 另一段确定失败后 ⇒ 才转为 partial（"已无在途任务，但没凑齐"）
            cache.markFailed(first[1].cacheKey, "上游 403")
            assertEquals("partial", cache.chapterState(cache.chapterRows(SOURCE, BOOK, CHAPTER)))
        }
    }

    @Test
    fun `re-registering prunes stale keys when the source changed the audio url`() {
        withCache { cache, database ->
            val before = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("old.mp3"))
            writeReady(cache, before[0], mp3Bytes(512))

            // 书源换了这一段的地址 ⇒ 旧 key 必须被清掉（否则永远占盘，且"整章就绪度"永远凑不齐）
            val after = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("new.mp3"))

            assertEquals(1, after.size)
            assertNull("旧 key 的行必须被清掉", database.findAudioByKey(before[0].cacheKey))
            assertFalse("旧字节也必须回收", Files.exists(cache.fileFor(before[0].cacheKey) ?: Path.of("nowhere")))
        }
    }

    @Test
    fun `chapter state is the single source of truth for the green dot`() {
        withCache { cache, database ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3", "b.mp3"))
            assertEquals("pending", cache.chapterState(rows))
            assertEquals("还没整章就绪 ⇒ 绿点口径里不该有它", emptyList<String>(), database.readyAudioChapters(SOURCE, BOOK))

            writeReady(cache, rows[0], mp3Bytes(256))
            assertEquals("还有分段没落定 ⇒ pending（不是 partial）", "pending", cache.chapterState(cache.chapterRows(SOURCE, BOOK, CHAPTER)))
            assertEquals("没凑齐就不算离线可用", emptyList<String>(), database.readyAudioChapters(SOURCE, BOOK))

            writeReady(cache, rows[1], mp3Bytes(256))
            assertEquals("ready", cache.chapterState(cache.chapterRows(SOURCE, BOOK, CHAPTER)))
            assertEquals("整章就绪才进绿点口径", listOf(CHAPTER), database.readyAudioChapters(SOURCE, BOOK))
        }
    }

    @Test
    fun `duration update ignores zero so a later unknown read cannot wipe a known value`() {
        withCache { cache, _ ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))
            writeReady(cache, rows[0], mp3Bytes(128))

            cache.updateDuration(rows[0].cacheKey, 183_452)
            assertEquals(183_452L, cache.rowByKey(rows[0].cacheKey)!!.durationMs)
            // 0 = "前端也不知道时长"，绝不能覆盖已知值
            assertEquals("0 不该写库", 0, cache.updateDuration(rows[0].cacheKey, 0))
            assertEquals(183_452L, cache.rowByKey(rows[0].cacheKey)!!.durationMs)
        }
    }

    @Test
    fun `reports stats with table and disk bytes`() {
        withCache { cache, _ ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))
            writeReady(cache, rows[0], mp3Bytes(4096))

            val stats = cache.stats()
            assertEquals(1, stats.trackCount)
            assertEquals(4096L, stats.totalBytes)
            assertEquals("表内字节与磁盘占用必须一致", stats.totalBytes, stats.diskBytes)
            assertEquals(TEST_QUOTA_BYTES, stats.quotaBytes)
            assertEquals(1, stats.books.size)
            assertEquals(1, stats.books.first().readyChapterCount)
        }
    }

    @Test
    fun `delete removes index and bytes`() {
        withCache { cache, _ ->
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))
            writeReady(cache, rows[0], mp3Bytes(64))
            val file = cache.fileFor(rows[0].cacheKey)!!

            assertEquals(1, cache.deleteKey(rows[0].cacheKey))
            assertNull(cache.rowByKey(rows[0].cacheKey))
            assertFalse("文件必须一起删（先表后盘）", Files.exists(file))

            val more = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("b.mp3"))
            writeReady(cache, more[0], mp3Bytes(64))
            assertEquals(1, cache.deleteBook(SOURCE, BOOK))
            assertEquals(0, cache.stats().trackCount)
            assertEquals(0L, cache.physicalBytes())
        }
    }

    // ------------------------------------------------------------------
    // AudioFetcher（注入接缝，不触网）
    // ------------------------------------------------------------------

    @Test
    fun `streams upstream bytes to disk and flips the row to ready`() {
        withCache { cache, _ ->
            val bytes = mp3Bytes(65_536)
            val fetcher = fetcherReturning("audio/mpeg", bytes)
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))

            val outcome = runBlocking {
                fetcher.fetchInto(
                    "https://a.example/a.mp3", emptyMap(), CHAPTER, null, cache.store,
                    cache.targetFor(rows[0]),
                )
            }

            assertTrue("抓取必须成功：$outcome", outcome is AudioFetchOutcome.Success)
            val success = outcome as AudioFetchOutcome.Success
            assertEquals("audio/mpeg", success.contentType)
            assertEquals(bytes.size.toLong(), success.byteSize)
            cache.markReady(rows[0], success.contentType, success.byteSize, success.filePath)

            val hit = cache.getByKey(rows[0].cacheKey)
            assertNotNull("落盘后必须命中（二次播放零上游请求）", hit)
            assertEquals(bytes.size.toLong(), hit!!.byteSize)
            assertEquals("文件内容必须是完整的原始字节", bytes.size.toLong(), Files.size(cache.fileFor(hit.cacheKey)!!))
            // 成品路径必须是**可读目录 + 8 位分段号 + 真实扩展名**（本书源只有一段 ⇒ 序号 0）
            assertTrue("落盘相对路径必须可读：${success.filePath}", success.filePath.endsWith("/00000000.mp3"))
        }
    }

    @Test
    fun `leaves no part file behind when the upstream body is rejected`() {
        withCache { cache, _ ->
            val fetcher = fetcherReturning("text/html", "<html>403 Forbidden</html>".toByteArray())
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))

            val outcome = runBlocking {
                fetcher.fetchInto("https://a.example/a.mp3", emptyMap(), null, null, cache.store, cache.targetFor(rows[0]))
            }

            assertTrue("错误页必须被拒绝：$outcome", outcome is AudioFetchOutcome.Failure)
            val reason = (outcome as AudioFetchOutcome.Failure).reason
            // 失败原因必须能指导下一步动作（去登录 / 检查防盗链），而不是一句「抓取失败」
            assertTrue("失败原因必须指向「要登录/防盗链」这类动作：$reason", reason.contains("网页") || reason.contains("登录"))
            assertNull("绝不能留下成品文件", cache.fileFor(rows[0].cacheKey))
            assertFalse("中转文件必须被清掉", hasTempFiles(cache))
        }
    }

    @Test
    fun `accepts wav and flac because browsers can decode them`() {
        withCache { cache, _ ->
            // 真实回归（2026-10-15）：番茄系音频源返回 mime_type=audio_wav，
            // 原先 WAV 被判成"不支持" ⇒ 用户的真实源**整章放不出**。
            // G-6 的原话是条件句「浏览器解不了就如实报错」，而浏览器**能**解 WAV/FLAC
            // —— 格式白名单不该比浏览器更严格。
            val wavRows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.wav"))
            val wavOutcome = runBlocking {
                fetcherReturning("audio/wav", wavBytes(8192)).fetchInto(
                    "https://a.example/a.wav", emptyMap(), CHAPTER, null, cache.store,
                    cache.targetFor(wavRows[0]),
                )
            }
            assertTrue("WAV 必须被接受：$wavOutcome", wavOutcome is AudioFetchOutcome.Success)
            assertEquals("audio/wav", (wavOutcome as AudioFetchOutcome.Success).contentType)

            val flacRows = cache.registerChapter(SOURCE, BOOK, "ch-flac", 1, specs("a.flac"))
            val flacOutcome = runBlocking {
                fetcherReturning("audio/flac", flacBytes()).fetchInto(
                    "https://a.example/a.flac", emptyMap(), CHAPTER, null, cache.store,
                    cache.targetFor(flacRows[0]),
                )
            }
            assertTrue(
                "FLAC 也必须被接受（Chrome/Firefox/Safari 均支持）：$flacOutcome",
                flacOutcome is AudioFetchOutcome.Success,
            )
        }
    }

    @Test
    fun `reports genuinely unsupported formats honestly instead of storing garbage`() {
        withCache { cache, _ ->
            // APE（Monkey's Audio）确实没有任何主流浏览器支持 ⇒ 如实报错，且**不引转码**
            val fetcher = fetcherReturning("audio/ape", apeBytes())
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.ape"))

            val outcome = runBlocking {
                fetcher.fetchInto("https://a.example/a.ape", emptyMap(), null, null, cache.store, cache.targetFor(rows[0]))
            }

            val failure = outcome as? AudioFetchOutcome.Failure
            assertNotNull("APE 必须**如实报错**，而不是静默返回坏字节", failure)
            assertTrue("必须点名格式与白名单：${failure!!.reason}", failure.reason.contains("APE"))
            assertTrue("必须说明本服务不转码", failure.reason.contains("转码"))
        }
    }

    @Test
    fun `accepts mp3 identified by magic even when upstream mislabels the content type`() {
        withCache { cache, _ ->
            // 上游谎报 application/octet-stream，但字节是 ID3 开头的 MP3 ⇒ 以**字节**为准
            val fetcher = fetcherReturning("application/octet-stream", mp3Bytes(2048))
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))

            val outcome = runBlocking {
                fetcher.fetchInto("https://a.example/a.mp3", emptyMap(), null, null, cache.store, cache.targetFor(rows[0]))
            }

            assertTrue("魔数指出是 MP3 就必须接受：$outcome", outcome is AudioFetchOutcome.Success)
            assertEquals("audio/mpeg", (outcome as AudioFetchOutcome.Success).contentType)
        }
    }

    @Test
    fun `decompresses gzip wrapped audio from upstream`() {
        withCache { cache, _ ->
            val mp3 = mp3Bytes(4096)
            val fetcher = fetcherReturning("audio/mpeg", gzipOf(mp3))
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))

            val outcome = runBlocking {
                fetcher.fetchInto("https://a.example/a.mp3", emptyMap(), null, null, cache.store, cache.targetFor(rows[0]))
            }
            val success = outcome as AudioFetchOutcome.Success
            cache.markReady(rows[0], success.contentType, success.byteSize, success.filePath)

            val file = cache.fileFor(rows[0].cacheKey)!!
            assertTrue("落盘的必须是解压后的音频（gzip 假数据是 SESSION-027 的老坑）", Files.size(file) == mp3.size.toLong())
            val head = Files.newInputStream(file).use { it.readNBytes(3) }
            assertEquals("ID3", String(head, Charsets.US_ASCII))
        }
    }

    @Test
    fun `rejects oversized audio segments`() {
        withCache { cache, _ ->
            val huge = ByteArray((AudioBytes.MAX_SEGMENT_BYTES + 1).toInt())
            mp3Bytes(1024).copyInto(huge, 0)
            val fetcher = fetcherReturning("audio/mpeg", huge)
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))

            val outcome = runBlocking {
                fetcher.fetchInto("https://a.example/a.mp3", emptyMap(), null, null, cache.store, cache.targetFor(rows[0]))
            }

            assertTrue("超过 200 MiB 必须失败", outcome is AudioFetchOutcome.Failure)
            assertFalse("失败时不留中转文件", hasTempFiles(cache))
        }
    }

    @Test
    fun `swallows upstream failures without breaking the play flow`() {
        val dir = Files.createTempDirectory("legado-audio-fetch-fail")
        val database = Database(Files.createTempFile("legado-audio-fetch-fail", ".sqlite").toString())
        try {
            database.initialize("password-for-test")
            val cache = AudioCache(dir, database, TEST_QUOTA_BYTES)
            val fetcher = AudioFetcher(testConfig(), injectedFetcher = { _, _, _, _ -> throw java.io.IOException("connection reset") })
            val rows = cache.registerChapter(SOURCE, BOOK, CHAPTER, 0, specs("a.mp3"))

            val outcome = runBlocking {
                fetcher.fetchInto("https://a.example/a.mp3", emptyMap(), null, null, cache.store, cache.targetFor(rows[0]))
            }

            // 音频与漫画的差别：**必须带原因回给前端**（整章听不了时用户要知道下一步做什么）
            assertTrue(outcome is AudioFetchOutcome.Failure)
            assertTrue((outcome as AudioFetchOutcome.Failure).reason.contains("connection reset"))
        } finally {
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `merges per segment headers over the source headers`() {
        var seen: Map<String, String> = emptyMap()
        var seenTrackHeaders: String? = null
        val fetcher = AudioFetcher(
            testConfig(),
            injectedFetcher = { _, headers, _, trackHeaders ->
                seen = headers
                seenTrackHeaders = trackHeaders
                "audio/mpeg" to mp3Bytes(64)
            },
        )
        val dir = Files.createTempDirectory("legado-audio-headers")
        val database = Database(Files.createTempFile("legado-audio-headers", ".sqlite").toString())
        try {
            database.initialize("password-for-test")
            val cache = AudioCache(dir, database, TEST_QUOTA_BYTES)
            val rows = cache.registerChapter(
                SOURCE, BOOK, CHAPTER, 0,
                listOf(AudioTrackSpec("https://a.example/a.mp3", """{"Referer":"https://audio.example.com/chapter/1"}""")),
            )

            runBlocking {
                fetcher.fetchInto(
                    "https://a.example/a.mp3",
                    mapOf("Cookie" to "sid=1", "Referer" to "https://audio.example.com/old"),
                    CHAPTER,
                    rows[0].headersJson,
                    cache.store,
                    cache.targetFor(rows[0]),
                )
            }

            assertEquals("源级 Cookie 必须保留", "sid=1", seen["Cookie"])
            assertEquals(
                "分段专属头必须**覆盖**源级头（否则重拉 403）",
                "https://audio.example.com/chapter/1",
                seen["Referer"],
            )
            assertTrue("分段头必须一路传到抓取收口", seenTrackHeaders!!.contains("chapter/1"))
        } finally {
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun specs(vararg urls: String): List<AudioTrackSpec> = urls.map { AudioTrackSpec("https://a.example/$it") }

    /**
     * 直接落盘 + 翻牌（绕开网络）：把"已经抓过一次"的状态预置出来。
     *
     * 路径装配走生产同一份代码（`AudioCache.putReady`），绝不在这里自己拼目录。
     */
    private fun writeReady(cache: AudioCache, row: AudioCacheRecord, bytes: ByteArray, contentType: String = "audio/mpeg"): String =
        cache.putReady(row, contentType, bytes)

    /**
     * 是否有残留的 `tmp_*.part`。
     *
     * 中转文件必须**一个都不剩**：半截文件被读到就是"播到一半断流"，且极难复现与排查。
     */
    private fun hasTempFiles(cache: AudioCache): Boolean =
        Files.walk(cache.store.root).use { paths ->
            paths.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".part") }
        }

    private fun fetcherReturning(contentType: String, bytes: ByteArray): AudioFetcher =
        AudioFetcher(testConfig(), injectedFetcher = { _, _, _, _ -> contentType to bytes })

    private fun withCache(block: (AudioCache, Database) -> Unit) {
        val dir = Files.createTempDirectory("legado-audio-cache")
        val dbPath = Files.createTempFile("legado-audio-cache", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            block(AudioCache(dir, database, TEST_QUOTA_BYTES), database)
        } finally {
            // 必须先 close 再删：否则 Windows 上会因文件占用删不掉（本机既有失败的根因）
            database.close()
            runCatching { dir.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    private fun testConfig(): ServerConfig {
        val dir = Files.createTempDirectory("legado-audio-fetcher-config")
        return ServerConfig(
            host = "0.0.0.0", port = 8080, databasePath = "unused.sqlite",
            coverCacheDirectory = dir, webDavDirectory = dir.resolve("webdav"),
            initialAdminPassword = null, secureCookies = false,
        )
    }

    private fun gzipOf(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * 一段**恰好 [size] 字节**的最小 MP3（ID3v2 头 + 帧同步字节填充）。
     *
     * ⚠️ 长度必须精确：`stats().totalBytes`（表内 sum）与 `diskBytes`（真实文件大小）是要
     * 逐字节对齐的，差 2 个字节就会把"表盘漂移"的断言变成假失败。
     */
    private fun mp3Bytes(size: Int): ByteArray {
        val header = ByteArray(10).also {
            "ID3".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
            it[3] = 0x03
        }
        if (size <= header.size) return header.copyOf(size)
        val bytes = header.copyOf(size)
        var offset = header.size
        while (offset + 4 <= size) {
            bytes[offset] = 0xff.toByte(); bytes[offset + 1] = 0xfb.toByte()
            bytes[offset + 2] = 0x90.toByte(); bytes[offset + 3] = 0x00
            offset += 4
        }
        // 尾部余量保持 0（仍属于 ID3 填充区，不影响魔数判定）
        return bytes
    }

    private fun flacBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(64))
        return out.toByteArray()
    }

    /** 一段最小的 WAV（RIFF....WAVE 魔数 + 填充），长度必须精确（stats 的表盘对齐断言依赖它）。 */
    private fun wavBytes(size: Int): ByteArray {
        val bytes = ByteArray(size.coerceAtLeast(64))
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        return bytes
    }

    /** 一段最小的 APE（`MAC ` 魔数）—— 主流浏览器确实解不了，用作"如实报错"的样本。 */
    private fun apeBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MAC ".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(64))
        return out.toByteArray()
    }
}
