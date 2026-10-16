package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 缓存目录结构（`书名 (书哈希)/章节名 (章节哈希)/8位分段号.扩展名`）的**唯一规格锁**。
 *
 * 锁的都是「不报错但会坏」的事：书名/章节名里的换行、`/`、`:` 把路径拼爆，结尾点与空格在 Windows
 * 上建不出目录，超长名撑爆路径上限，`..`/盘符/绝对路径把文件写到缓存根之外，
 * 分段序号不补零导致排序错乱，扩展名猜错导致"文件打不开"。
 *
 * 另一半（**读取不依赖中文**）同样是静默失败：`file_path` 失效或用户改了中文目录名时，
 * 缓存必须仍然命中；而只认「两个哈希 + 分段号」也意味着**绝不会服务错文件**。
 */
class CachePathLayoutTest {

    // ------------------------------------------------------------------
    // 名称清洗
    // ------------------------------------------------------------------

    @Test
    fun `replaces windows illegal characters and control characters`() {
        assertEquals("a_b_c", cacheSafeName("a/b:c", 60))
        assertEquals("a_b_c_d_e_f_g_h_i_j", cacheSafeName("""a\b/c:d*e?f"g<h>i|j""", 60))
        // 控制字符（换行/制表/0x01）必须落成 _，绝不能原样带进文件名
        // （按规格字面：控制字符与非法字符同一口径，统一替换为 `_`，随后才做空白折叠）
        assertEquals("十日终焉_我成魔", cacheSafeName("十日终焉\n我成魔", 60))
        assertEquals("a_b_c", cacheSafeName("a\u0001b\tc", 60))
    }

    @Test
    fun `collapses whitespace and strips trailing dots and spaces`() {
        assertEquals("第1话 开始", cacheSafeName("  第1话   开始  ", 60))
        // Windows 不允许文件名以点或空格结尾
        assertEquals("书名", cacheSafeName("书名.. ", 60))
        assertEquals("书名", cacheSafeName("书名. . .", 60))
    }

    @Test
    fun `truncates to the limit and still strips trailing dots after truncation`() {
        assertEquals(60, cacheSafeName("x".repeat(70), 60).length)
        // 第 60 个字符正好是点 ⇒ 截断后仍要去尾
        assertEquals("x".repeat(59), cacheSafeName("x".repeat(59) + ".yyy", 60))
        assertEquals(40, cacheSafeName("标".repeat(50), 40).length)
    }

    @Test
    fun `falls back to a placeholder when cleaning yields nothing`() {
        assertEquals(CACHE_UNNAMED, cacheSafeName(null, 60))
        assertEquals(CACHE_UNNAMED, cacheSafeName("   ", 60))
        assertEquals(CACHE_UNNAMED, cacheSafeName("..", 60))
    }

    @Test
    fun `avoids windows reserved device names without hurting lookalikes`() {
        assertEquals("CON_", cacheSafeName("CON", 60))
        assertEquals("con.txt_", cacheSafeName("con.txt", 60))
        assertEquals("COM1_", cacheSafeName("COM1", 60))
        assertEquals("LPT9.mp3_", cacheSafeName("LPT9.mp3", 60))
        // 大小写不敏感判定，但**保留用户原本的大小写**（只追加 _，不改成大写）
        assertEquals("nul_", cacheSafeName("nul", 60))
        // 只是**前缀相同**的普通名字不能被误伤
        assertEquals("CONSOLE", cacheSafeName("CONSOLE", 60))
        assertEquals("com10", cacheSafeName("com10", 60))
    }

    // ------------------------------------------------------------------
    // 扩展名映射
    // ------------------------------------------------------------------

    @Test
    fun `maps content types to real extensions`() {
        assertEquals("jpg", contentExtension("image/jpeg"))
        assertEquals("png", contentExtension("image/png"))
        assertEquals("webp", contentExtension("image/webp"))
        assertEquals("gif", contentExtension("image/gif"))
        assertEquals("avif", contentExtension("image/avif"))
        assertEquals("mp3", contentExtension("audio/mpeg"))
        assertEquals("mp3", contentExtension("audio/mp3"))
        assertEquals("m4a", contentExtension("audio/mp4"))
        assertEquals("m4a", contentExtension("audio/x-m4a"))
        assertEquals("m4a", contentExtension("audio/m4a"))
        assertEquals("aac", contentExtension("audio/aac"))
        assertEquals("aac", contentExtension("audio/aacp"))
        assertEquals("ogg", contentExtension("audio/ogg"))
        assertEquals("ogg", contentExtension("application/ogg"))
        assertEquals("ogg", contentExtension("audio/opus"))
        assertEquals("ogg", contentExtension("audio/vorbis"))
        assertEquals("webm", contentExtension("audio/webm"))
        assertEquals("webm", contentExtension("video/webm"))
        assertEquals("wav", contentExtension("audio/wav"))
        assertEquals("wav", contentExtension("audio/x-wav"))
        assertEquals("wav", contentExtension("audio/wave"))
        assertEquals("flac", contentExtension("audio/flac"))
        assertEquals("flac", contentExtension("audio/x-flac"))
        // 大小写与 `; charset=` 参数都要吃得下
        assertEquals("mp3", contentExtension("AUDIO/MPEG; charset=utf-8"))
        // 未知一律 bin（绝不猜：猜错就是"文件打不开"）
        assertEquals("bin", contentExtension("application/octet-stream"))
        assertEquals("bin", contentExtension(null))
    }

    @Test
    fun `video mp4 keeps the truthful mp4 extension`() {
        // 上游常把 M4A 标成 video/mp4；扩展名如实写成 mp4（浏览器解码行为一致，见 AudioBytes）
        assertEquals("mp4", contentExtension("video/mp4"))
    }

    // ------------------------------------------------------------------
    // 书目录 / 章节目录 / 分段文件名
    // ------------------------------------------------------------------

    @Test
    fun `book hash is sha256 of source id and book url, truncated to the shared constant`() {
        val hash = bookCacheHash("src-a", "https://a.example/book/1")
        assertEquals(AtomicFileStore.BOOK_HASH_LENGTH, hash.length)
        assertTrue("必须是纯小写 hex：$hash", Regex("[0-9a-f]+").matches(hash))
        assertEquals(
            ImageBytes.sha256("src-a\nhttps://a.example/book/1").take(AtomicFileStore.BOOK_HASH_LENGTH),
            hash,
        )
        // 同一本书必须稳定（否则每次写盘都会落到新目录）
        assertEquals(hash, bookCacheHash("src-a", "https://a.example/book/1"))
        // 含 sourceId ⇒ 同一地址在两个书源上不串味
        assertTrue(hash != bookCacheHash("src-b", "https://a.example/book/1"))
    }

    @Test
    fun `book folder carries the readable name plus the hash`() {
        val hash = bookCacheHash("src-a", "https://a.example/book/1")
        assertEquals("十日终焉 ($hash)", bookCacheFolder("十日终焉", "src-a", "https://a.example/book/1"))
        // 书不在书架 / 名字是脏数据 ⇒ 兜底名 + 同样的哈希（绝不因此失败）
        assertEquals("$CACHE_UNNAMED ($hash)", bookCacheFolder(null, "src-a", "https://a.example/book/1"))
        assertEquals("$CACHE_UNNAMED ($hash)", bookCacheFolder("   ", "src-a", "https://a.example/book/1"))
    }

    @Test
    fun `chapter hash is injective per source book and chapter url`() {
        val hash = chapterCacheHash("src-a", "https://a.example/book/1", "https://a.example/ch/1")
        assertEquals(AtomicFileStore.CHAPTER_HASH_LENGTH, hash.length)
        assertTrue("必须是纯小写 hex：$hash", Regex("[0-9a-f]+").matches(hash))
        assertEquals(
            ImageBytes.sha256("src-a\nhttps://a.example/book/1\nhttps://a.example/ch/1").take(AtomicFileStore.CHAPTER_HASH_LENGTH),
            hash,
        )
        assertEquals(hash, chapterCacheHash("src-a", "https://a.example/book/1", "https://a.example/ch/1"))
        // 三要素任一不同 ⇒ 哈希不同 ⇒ 章节目录天生单射（两章不可能算出同一路径）
        assertTrue(hash != chapterCacheHash("src-b", "https://a.example/book/1", "https://a.example/ch/1"))
        assertTrue(hash != chapterCacheHash("src-a", "https://a.example/book/2", "https://a.example/ch/1"))
        assertTrue(hash != chapterCacheHash("src-a", "https://a.example/book/1", "https://a.example/ch/2"))
    }

    @Test
    fun `chapter folder is the cleaned title plus the chapter hash`() {
        val source = "src-a"
        val book = "https://a.example/book/1"
        val chapter = "https://a.example/ch/499"
        val hash = chapterCacheHash(source, book, chapter)
        assertEquals("真正的生路 ($hash)", chapterCacheFolder("真正的生路", source, book, chapter))
        // 标题里的非法字符被清洗（`:` → `_`）
        assertEquals("第1话_ 开始 ($hash)", chapterCacheFolder("第1话: 开始", source, book, chapter))
        // 标题超长按 40 截断
        assertEquals("${"标".repeat(40)} ($hash)", chapterCacheFolder("标".repeat(60), source, book, chapter))
        // 标题取不到 ⇒ `未命名 (哈希)`：**绝不以空格或括号开头**
        assertEquals("$CACHE_UNNAMED ($hash)", chapterCacheFolder(null, source, book, chapter))
        assertEquals("$CACHE_UNNAMED ($hash)", chapterCacheFolder("   ", source, book, chapter))
        assertFalse("目录名不得以空格开头", chapterCacheFolder(null, source, book, chapter).startsWith(" "))
        assertFalse("目录名不得以括号开头", chapterCacheFolder(null, source, book, chapter).startsWith("("))
    }

    @Test
    fun `segment file name is 8-digit index plus real extension`() {
        assertEquals("00000001.mp3", segmentFileName(1, "audio/mpeg"))
        assertEquals("00000002.m4a", segmentFileName(2, "audio/mp4"))
        assertEquals("00000000.jpg", segmentFileName(0, "image/jpeg"))
        assertEquals("00000005.bin", segmentFileName(5, null))
        assertEquals(CACHE_SEGMENT_INDEX_WIDTH, segmentPrefix(1).length - 1)
    }

    // ------------------------------------------------------------------
    // 路径安全
    // ------------------------------------------------------------------

    @Test
    fun `resolve rejects escapes, absolute paths, drive letters and empty bytes`() {
        val root = Files.createTempDirectory("legado-cache-path-safety")
        try {
            val store = AtomicFileStore(root)
            listOf("../escape", "a/../../b", "/etc/passwd", "C:/Windows/x", "\\\\server\\share", "", "a\u0000b").forEach { bad ->
                var threw = false
                try { store.resolve(bad) } catch (e: IllegalArgumentException) { threw = true }
                assertTrue("非法路径必须显式抛错：[$bad]", threw)
                assertNull("读取路径遇到非法输入只能返回 null：[$bad]", store.read(bad))
            }
            // 合法相对路径必须留在根目录内
            val relative = "十日终焉 (abc)/真正的生路 (def)/00000001.mp3"
            val target = store.resolve(relative)
            assertTrue(target.startsWith(root.normalize()))
            assertEquals(relative, store.relativeOf(target))
            assertNull("越界路径不得被当成相对路径", store.relativeOf(root.resolveSibling("outside/x.mp3")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // purgeAll（启动清理的底层）
    // ------------------------------------------------------------------

    @Test
    fun `purgeAll deletes every regular file including part files and counts them`() {
        val root = Files.createTempDirectory("legado-cache-purge")
        try {
            val store = AtomicFileStore(root)
            store.write("十日终焉 (abc)/真正的生路 (def)/00000001.mp3", ByteArray(10))
            store.write("十日终焉 (abc)/真正的生路 (def)/00000002.mp3", ByteArray(20))
            Files.write(store.resolve("十日终焉 (abc)/真正的生路 (def)/tmp_x.part"), ByteArray(5))

            val result = store.purgeAll()

            assertEquals("在途的 .part 也必须清掉，且计入文件数", 3, result.files)
            assertEquals(35L, result.bytes)
            assertEquals(0L, store.totalBytes())
            assertTrue("缓存根目录本身必须留着", Files.isDirectory(root))

            val second = store.purgeAll()
            assertEquals("第二次必须是 no-op", 0, second.files)
            assertEquals(0L, second.bytes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy layout purge is one shot and clears both rows and files`() {
        val dir = Files.createTempDirectory("legado-cache-layout-purge")
        val database = Database(dir.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val imageRoot = dir.resolve("images")
            val audioRoot = dir.resolve("audio")
            val imageStore = AtomicFileStore(imageRoot)
            val audioStore = AtomicFileStore(audioRoot)
            imageStore.write("ab/deadbeef.jpg", ByteArray(7))
            audioStore.write("cd/deadbeef.mp3", ByteArray(9))
            database.registerMangaImages(listOf(MangaImageRecord(urlHash = "h1", sourceId = "s", bookUrl = "b", chapterUrl = "c", upstreamUrl = "u")))
            database.registerAudioTracks(
                listOf(AudioCacheRecord(cacheKey = "k1", sourceId = "s", bookUrl = "b", chapterUrl = "c", upstreamUrl = "u")),
                listOf("k1"),
            )
            assertEquals(1, database.countMangaImages())
            assertEquals(1, database.countAudioTracks())

            assertTrue("非 v2 布局必须清一次", purgeLegacyCacheLayout(database, imageRoot, audioRoot))
            assertEquals(0, database.countMangaImages())
            assertEquals(0, database.countAudioTracks())
            assertEquals(0L, imageStore.totalBytes())
            assertEquals(0L, audioStore.totalBytes())
            assertEquals(CACHE_LAYOUT_VERSION, database.getSetting(CACHE_LAYOUT_KEY))

            // 第二次启动：既不清表也不扫盘（no-op）
            imageStore.write("ab/new.jpg", ByteArray(3))
            assertFalse("v2 之后必须 no-op", purgeLegacyCacheLayout(database, imageRoot, audioRoot))
            assertTrue("no-op 不能顺手删掉新布局的文件", imageStore.exists("ab/new.jpg"))
        } finally {
            database.close()
            runCatching { Files.deleteIfExists(dir.resolve("legado.sqlite")) }
            runCatching { Files.deleteIfExists(dir.resolve("legado.sqlite-wal")) }
            runCatching { Files.deleteIfExists(dir.resolve("legado.sqlite-shm")) }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // 无中文定位（读取不依赖中文）
    // ------------------------------------------------------------------

    @Test
    fun `name free lookup survives renamed chinese folders and a changed extension`() {
        val dir = Files.createTempDirectory("legado-cache-namefree")
        try {
            val store = AtomicFileStore(dir)
            val source = "src-a"
            val book = "https://a.example/book/1"
            val chapter = "https://a.example/ch/499"
            val bookHash = bookCacheHash(source, book)
            val chapterHash = chapterCacheHash(source, book, chapter)
            val relative = "${bookCacheFolder("十日终焉", source, book)}/" +
                "${chapterCacheFolder("真正的生路", source, book, chapter)}/${segmentFileName(1, "audio/mpeg")}"
            store.write(relative, ByteArray(4))

            // 先命中一次，把「进程内目录名缓存」喂饱（下面要验证改名后它能失效重试）
            assertEquals(store.resolve(relative), store.findByNameFree(bookHash, chapterHash, 1))

            // 书名目录改成乱码（只留 `(hash)`，大小写也改成大写），章节目录的中文也改掉，扩展名改成 .m4a
            val bookDir = dir.resolve(relative.substringBefore('/'))
            val renamedBook = dir.resolve("涔熻 (${bookHash.uppercase()})")
            Files.move(bookDir, renamedBook)
            val chapterDir = renamedBook.resolve(relative.split('/')[1])
            val renamedChapter = renamedBook.resolve("乱码 (${chapterHash.uppercase()})")
            Files.move(chapterDir, renamedChapter)
            val renamedFile = renamedChapter.resolve("00000001.m4a")
            Files.move(renamedChapter.resolve("00000001.mp3"), renamedFile)

            assertEquals(
                "目录改名 + 扩展名改标都不能让缓存失联",
                renamedFile,
                store.findByNameFree(bookHash, chapterHash, 1),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `name free lookup misses when the hash marker is gone instead of serving a wrong file`() {
        withTempDatabase("legado-cache-hash-marker-gone") { database, dir ->
            val source = "src-a"
            val book = "https://a.example/book/1"
            val chapter = "https://a.example/ch/1"
            val cache = ImageCache(dir.resolve("images"), database)
            val store = AtomicFileStore(dir.resolve("images"))
            val stored = cache.put(source, book, chapter, "https://img.example/a.jpg", "image/jpeg", jpegBytes(100, 100), chapterIndex = 0)
            val relative = database.findMangaImageByUrlHash(stored.urlHash)!!.filePath
            assertNotNull("正常情况必须命中", cache.fileFor(stored.cacheKey))

            // 把章节目录名里的 `(章节哈希)` 删掉：标识没了 ⇒ 必须判"不存在"（重下），绝不能瞎猜一个文件
            val chapterDir = store.resolve(relative).parent
            val stripped = chapterDir.resolveSibling(relative.split('/')[1].substringBefore(" ("))
            Files.move(chapterDir, stripped)

            assertNull("哈希标识消失后必须判不存在，绝不能返回错文件", cache.fileFor(stored.cacheKey))
        }
    }

    @Test
    fun `name free lookup returns null instead of throwing on misses and bad input`() {
        val dir = Files.createTempDirectory("legado-cache-namefree-miss")
        try {
            val store = AtomicFileStore(dir)
            val bookHash = bookCacheHash("src-a", "https://a.example/book/1")
            val chapterHash = chapterCacheHash("src-a", "https://a.example/book/1", "https://a.example/ch/1")
            assertNull(store.findByNameFree(bookHash, chapterHash, 1))
            assertNull("非法哈希直接返回 null", store.findByNameFree("书\n名", chapterHash, 0))
            assertNull("书哈希位数不对直接返回 null", store.findByNameFree(bookHash.take(4), chapterHash, 0))
            assertNull("章节哈希位数不对直接返回 null", store.findByNameFree(bookHash, chapterHash.take(4), 0))
            assertNull("负数分段号直接返回 null", store.findByNameFree(bookHash, chapterHash, -1))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 单射：两章同号也各占一个目录，绝不互相覆盖
    // ------------------------------------------------------------------

    /**
     * 章节目录名带**章节哈希** ⇒ 即使两章的 `chapterIndex` 相同（甚至都缺省为 0）、标题都取不到，
     * 也各自落一个目录（`未命名 (hashA)` / `未命名 (hashB)`），**没有任何覆盖**。
     */
    @Test
    fun `two chapters with the same index never overwrite each other`() {
        withTempDatabase("legado-cache-injective-images") { database, dir ->
            val source = "src-a"
            val book = "https://a.example/book/1"
            val chapterA = "$book/chapter/1"
            val chapterB = "$book/chapter/2"
            val cache = ImageCache(dir.resolve("images"), database)
            val store = AtomicFileStore(dir.resolve("images"))
            val jpegA = jpegBytes(100, 100)
            val jpegB = jpegBytes(200, 200)

            val a = cache.put(source, book, chapterA, "https://img.example/a.jpg", "image/jpeg", jpegA, chapterIndex = 0, segmentIndex = 0)
            val b = cache.put(source, book, chapterB, "https://img.example/b.jpg", "image/jpeg", jpegB, chapterIndex = 0, segmentIndex = 0)

            val pathA = database.findMangaImageByUrlHash(a.urlHash)!!.filePath
            val pathB = database.findMangaImageByUrlHash(b.urlHash)!!.filePath
            assertEquals(
                "每章一个 `章节名 (章节哈希)` 目录；标题取不到 ⇒ 未命名 (哈希)",
                "${bookCacheFolder(null, source, book)}/${chapterCacheFolder(null, source, book, chapterA)}/${segmentFileName(0, "image/jpeg")}",
                pathA,
            )
            assertEquals(
                "${bookCacheFolder(null, source, book)}/${chapterCacheFolder(null, source, book, chapterB)}/${segmentFileName(0, "image/jpeg")}",
                pathB,
            )
            assertTrue("两章必须算出不同路径", pathA != pathB)
            // 磁盘上两份都在，且内容各自正确（没有互相覆盖）
            assertArrayEquals(jpegA, Files.readAllBytes(store.resolve(pathA)))
            assertArrayEquals(jpegB, Files.readAllBytes(store.resolve(pathB)))
            assertArrayEquals(jpegA, cache.read(a.cacheKey))
            assertArrayEquals(jpegB, cache.read(b.cacheKey))
        }
    }

    @Test
    fun `two audio chapters with the same index never overwrite each other`() {
        withTempDatabase("legado-cache-injective-audio") { database, dir ->
            val source = "https://audio.example"
            val book = "$source/book/1"
            val chapterA = "$source/chapter/1"
            val chapterB = "$source/chapter/2"
            val cache = AudioCache(dir.resolve("audio"), database)
            val store = AtomicFileStore(dir.resolve("audio"))

            suspend fun grab(chapterUrl: String, bytes: ByteArray): AudioFetchOutcome.Success {
                val rows = cache.registerChapter(source, book, chapterUrl, 0, listOf(AudioTrackSpec("https://cdn.example/$chapterUrl.mp3")))
                val outcome = AudioFetcher(testConfig(), injectedFetcher = { _, _, _, _ -> "audio/mpeg" to bytes })
                    .fetchInto(
                        upstreamUrl = "https://cdn.example/$chapterUrl.mp3",
                        mediaHeaders = emptyMap(),
                        referer = chapterUrl,
                        trackHeadersJson = null,
                        store = cache.store,
                        target = cache.targetFor(rows[0]),
                    )
                val success = outcome as AudioFetchOutcome.Success
                cache.markReady(rows[0], success.contentType, success.byteSize, success.filePath)
                return success
            }

            val a = runBlocking { grab(chapterA, mp3Bytes(1024)) }
            val b = runBlocking { grab(chapterB, mp3Bytes(2048)) }

            assertEquals(
                "${bookCacheFolder(null, source, book)}/${chapterCacheFolder(null, source, book, chapterA)}/${segmentFileName(0, "audio/mpeg")}",
                a.filePath,
            )
            assertEquals(
                "${bookCacheFolder(null, source, book)}/${chapterCacheFolder(null, source, book, chapterB)}/${segmentFileName(0, "audio/mpeg")}",
                b.filePath,
            )
            assertEquals(1024L, Files.size(store.resolve(a.filePath)))
            assertEquals(2048L, Files.size(store.resolve(b.filePath)))
        }
    }

    // ------------------------------------------------------------------
    // 端到端：真跑一次写入，磁盘上必须是可读路径
    // ------------------------------------------------------------------

    /**
     * 端到端（真落盘，不触网）——同时也是**验收证据**：测试把目录树打印到 stdout 且**不清理**，
     * 固定落在 `server/build/cache-layout-e2e`，可以直接用 `Get-ChildItem -Recurse` 复核。
     */
    @Test
    fun `writes readable paths for audio and images end to end`() {
        val root = Paths.get("build", "cache-layout-e2e")
        if (Files.exists(root)) root.toFile().deleteRecursively()
        Files.createDirectories(root)
        val database = Database(root.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")

            // 书架名字 + TOC 标题是目录名的来源（取不到也有兜底，见上面的纯函数用例）
            val audioSource = "https://audio.example"
            val audioBook = "$audioSource/book/1"
            val audioChapter = "$audioSource/chapter/499"
            database.saveBookshelf(
                BookshelfWriteRequest(sourceId = audioSource, bookUrl = audioBook, name = "十日终焉", tocUrl = audioBook),
                cover = null,
            )
            database.saveTocCache(audioSource, audioBook, listOf(Chapter(499, "真正的生路", audioChapter)))

            val audioCache = AudioCache(root.resolve("audio"), database)
            val rows = audioCache.registerChapter(audioSource, audioBook, audioChapter, 499, listOf(AudioTrackSpec("https://cdn.example/1.mp3")))
            val outcome = runBlocking {
                AudioFetcher(testConfig(), injectedFetcher = { _, _, _, _ -> "audio/mpeg" to mp3Bytes(2048) })
                    .fetchInto(
                        upstreamUrl = "https://cdn.example/1.mp3",
                        mediaHeaders = emptyMap(),
                        referer = audioChapter,
                        trackHeadersJson = null,
                        store = audioCache.store,
                        target = audioCache.targetFor(rows[0]),
                    )
            }
            assertTrue("端到端抓取必须成功：$outcome", outcome is AudioFetchOutcome.Success)
            val audioSuccess = outcome as AudioFetchOutcome.Success
            audioCache.markReady(rows[0], audioSuccess.contentType, audioSuccess.byteSize, audioSuccess.filePath)

            val expectedAudio = "${bookCacheFolder("十日终焉", audioSource, audioBook)}/" +
                "${chapterCacheFolder("真正的生路", audioSource, audioBook, audioChapter)}/${segmentFileName(0, "audio/mpeg")}"
            assertEquals(expectedAudio, audioSuccess.filePath)
            assertTrue("磁盘上必须真的有这个文件", Files.isRegularFile(root.resolve("audio").resolve(expectedAudio)))
            assertEquals("file_path 必须落库（快路径）", expectedAudio, database.findAudioByKey(rows[0].cacheKey)?.filePath)

            val imageSource = "https://manga.example"
            val imageBook = "$imageSource/book/1"
            val imageChapter = "$imageSource/chapter/1"
            database.saveBookshelf(
                BookshelfWriteRequest(sourceId = imageSource, bookUrl = imageBook, name = "某漫画", tocUrl = imageBook),
                cover = null,
            )
            database.saveTocCache(imageSource, imageBook, listOf(Chapter(1, "第1话", imageChapter)))

            val imageCache = ImageCache(root.resolve("images"), database)
            val stored = imageCache.put(imageSource, imageBook, imageChapter, "https://img.example/1.jpg", "image/jpeg", jpegBytes(), chapterIndex = 1)
            val expectedImage = "${bookCacheFolder("某漫画", imageSource, imageBook)}/" +
                "${chapterCacheFolder("第1话", imageSource, imageBook, imageChapter)}/${segmentFileName(0, "image/jpeg")}"
            assertEquals(expectedImage, database.findMangaImageByUrlHash(stored.urlHash)?.filePath)
            assertTrue(Files.isRegularFile(root.resolve("images").resolve(expectedImage)))

            println("[cache-layout-e2e] 绝对根目录: ${root.toAbsolutePath()}")
            listFiles(root).forEach { println("  $it") }
        } finally {
            database.close()
        }
    }

    /**
     * `file_path` 只是**快路径缓存**：指着不存在的路径时，读取必须靠「两个哈希 + 分段号」找回文件并回填。
     */
    @Test
    fun `reads still hit when the stored file path is broken and then backfill it`() {
        withTempDatabase("legado-cache-broken-path") { database, dir ->
            val source = "https://a.example"
            val book = "$source/book/1"
            val chapter = "$source/chapter/1"
            database.saveBookshelf(
                BookshelfWriteRequest(sourceId = source, bookUrl = book, name = "十日终焉", tocUrl = book),
                cover = null,
            )
            database.saveTocCache(source, book, listOf(Chapter(1, "第1话", chapter)))

            val audioCache = AudioCache(dir.resolve("audio"), database)
            val rows = audioCache.registerChapter(source, book, chapter, 1, listOf(AudioTrackSpec("https://cdn.example/1.mp3")))
            val realPath = audioCache.putReady(rows[0], "audio/mpeg", mp3Bytes(512))
            // 磁盘上其实叫 .m4a（模拟"上游改标 content_type"留下的历史文件）
            val renamed = Files.move(audioCache.store.resolve(realPath), audioCache.store.resolve(realPath.replace(".mp3", ".m4a")))
            database.updateAudioFilePath(rows[0].cacheKey, "不存在的书 (deadbeef)/不存在的章 (beefdead)/00000001.mp3")

            val located = audioCache.fileFor(rows[0].cacheKey)
            assertEquals("快路径失效后必须靠哈希+分段号命中（扩展名不参与匹配）", renamed, located)
            assertEquals(
                "命中后必须回填 file_path（下次回到快路径）",
                audioCache.store.relativeOf(renamed),
                database.findAudioByKey(rows[0].cacheKey)?.filePath,
            )

            // 图片同理：把 file_path 指歪，read/fileFor 仍然命中并回填
            val imageCache = ImageCache(dir.resolve("images"), database)
            val stored = imageCache.put(source, book, chapter, "https://img.example/1.jpg", "image/jpeg", jpegBytes(), chapterIndex = 1)
            val imageRow = database.findMangaImageByUrlHash(stored.urlHash)!!
            database.updateMangaImageFilePath(stored.urlHash, "不存在 (deadbeef)/不存在 (beefdead)/00000001.jpg")
            assertNotNull("file_path 指歪也必须能读到", imageCache.read(stored.cacheKey))
            assertEquals(
                "图片命中后同样要回填",
                imageRow.filePath,
                database.findMangaImageByUrlHash(stored.urlHash)?.filePath,
            )
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun listFiles(root: Path): List<String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }
            .map { root.relativize(it).toString().replace('\\', '/') }
            .sorted()
            .toList()
    }

    /** 临时库 + 临时目录的样板（Windows 上必须先 close 再删，否则文件被占用删不掉）。 */
    private fun withTempDatabase(name: String, block: (Database, Path) -> Unit) {
        val dir = Files.createTempDirectory(name)
        val database = Database(dir.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            block(database, dir)
        } finally {
            database.close()
            runCatching { Files.deleteIfExists(dir.resolve("legado.sqlite")) }
            runCatching { Files.deleteIfExists(dir.resolve("legado.sqlite-wal")) }
            runCatching { Files.deleteIfExists(dir.resolve("legado.sqlite-shm")) }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun testConfig(): ServerConfig {
        val dir = Files.createTempDirectory("legado-cache-layout-config")
        return ServerConfig(
            host = "0.0.0.0", port = 8080, databasePath = "unused.sqlite",
            coverCacheDirectory = dir, webDavDirectory = dir.resolve("webdav"),
            initialAdminPassword = null, secureCookies = false,
        )
    }

    /** 最小合法 JPEG（含 SOF0，宽高可探测）。 */
    private fun jpegBytes(width: Int = 800, height: Int = 1200): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        out.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0x00, 0x10))
        out.write(ByteArray(14))
        out.write(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x00, 0x11, 0x08))
        out.write(byteArrayOf((height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf(0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01))
        return out.toByteArray()
    }

    /** 恰好 [size] 字节的最小 MP3（ID3v2 头 + 帧同步填充）。 */
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
        return bytes
    }
}
