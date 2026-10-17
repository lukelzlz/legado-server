package io.legado.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
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
 * 缓存落盘布局**第 3 版**的规格锁（PR #50 的延续）：
 *
 * 1. 漫画缓存根从 `images` 改名成 `comic` —— 布局没变，只有根目录名变了；
 * 2. 于是 v2 → v3 的迁移动作是**移动**（用户数据零损失），不是 v1 那种"清掉重下"；
 * 3. 封面文件名从 `covers/<key>` 改成 `covers/<书名 (书哈希).扩展名>`（哈希与书目录同源）；
 * 4. `images/` 保留下来当**小说插图**的预留根（空目录、本次不实现写入）。
 *
 * 锁的都是"不报错但会坏"的事：迁移把用户缓存删了、目标同名被覆盖、跑两次第二次又动盘、
 * 中文一改封面就不认了、`file_path` 失效就判"没有缓存"（于是白重下、甚至直出别人的图）。
 */
class CacheLayoutV3Test {

    private val sourceId = "https://a.example"
    private val bookUrl = "$sourceId/book/1"
    private val bookName = "十日终焉"
    private val chapterUrl = "$sourceId/chapter/1"

    // ------------------------------------------------------------------
    // 1. 漫画根改名：v2 → v3 是「移动」，不是「清理」
    // ------------------------------------------------------------------

    @Test
    fun `v3 migration moves the v2 images files into comic without losing rows or bytes`() {
        withTempLayout("legado-v3-move") { database, dir ->
            val illustrationRoot = dir.resolve("images")
            val comicRoot = dir.resolve("comic")
            val audioRoot = dir.resolve("audio")
            val coverRoot = dir.resolve("covers")
            database.saveBookshelf(BookshelfWriteRequest(sourceId = sourceId, bookUrl = bookUrl, name = bookName, tocUrl = bookUrl), null)

            // 预置一份"v2 部署"的漫画缓存：文件在 `images/` 里、布局已经是可读目录
            val v2Cache = ImageCache(illustrationRoot, database)
            val stored = v2Cache.put(sourceId, bookUrl, chapterUrl, "https://img.example/1.jpg", "image/jpeg", jpegBytes(), chapterIndex = 0, segmentIndex = 0)
            val relative = database.findMangaImageByUrlHash(stored.urlHash)!!.filePath
            assertTrue("v2 的文件必须在 images 根里", Files.isRegularFile(illustrationRoot.resolve(relative)))
            database.setSetting(CACHE_LAYOUT_KEY, CACHE_LAYOUT_VERSION_V2)

            val logs = mutableListOf<String>()
            assertTrue(
                "v2 → v3 必须真动盘",
                migrateCacheLayout(database, comicRoot, illustrationRoot, audioRoot, coverRoot) { logs += it },
            )

            // 文件**移**过去了（同一相对路径），源目录被搬空
            assertTrue("文件必须出现在 comic 根里", Files.isRegularFile(comicRoot.resolve(relative)))
            assertFalse("images 里不能再留着它", Files.exists(illustrationRoot.resolve(relative)))
            assertTrue("插图预留根必须存在（空目录）", Files.isDirectory(illustrationRoot))
            assertEquals("插图预留根必须被搬空", 0L, AtomicFileStore(illustrationRoot).totalBytes())
            // 索引行**没有**被删（v2 那次"清掉重下"在这里是错的：布局没变，凭什么让用户重下）
            assertEquals("索引行绝不能删", 1, database.countMangaImages())
            assertNotNull("改名后必须仍然读得到（零数据损失）", ImageCache(comicRoot, database).fileFor(stored.cacheKey))
            assertEquals(CACHE_LAYOUT_VERSION, database.getSetting(CACHE_LAYOUT_KEY))
            assertTrue("日志必须如实报告移动战果：$logs", logs.any { it.contains("移动文件=1") })
        }
    }

    @Test
    fun `v3 migration is a no-op the second time and never touches the new root`() {
        withTempLayout("legado-v3-idempotent") { database, dir ->
            val illustrationRoot = dir.resolve("images")
            val comicRoot = dir.resolve("comic")
            val audioRoot = dir.resolve("audio")
            val coverRoot = dir.resolve("covers")
            database.setSetting(CACHE_LAYOUT_KEY, CACHE_LAYOUT_VERSION_V2)
            AtomicFileStore(illustrationRoot).write("ab/cd.jpg", ByteArray(5))

            assertTrue(migrateCacheLayout(database, comicRoot, illustrationRoot, audioRoot, coverRoot))
            val comicStore = AtomicFileStore(comicRoot)
            assertTrue(comicStore.exists("ab/cd.jpg"))

            // 第二次启动：不扫盘、不写库，更不能顺手删掉 v3 布局的文件
            comicStore.write("ab/new.jpg", ByteArray(3))
            assertFalse("v3 之后必须 no-op", migrateCacheLayout(database, comicRoot, illustrationRoot, audioRoot, coverRoot))
            assertTrue("no-op 不能删文件", comicStore.exists("ab/new.jpg"))
            assertTrue("no-op 不能删文件（另一份）", comicStore.exists("ab/cd.jpg"))
            assertEquals(CACHE_LAYOUT_VERSION, database.getSetting(CACHE_LAYOUT_KEY))
        }
    }

    @Test
    fun `v3 migration skips an existing target instead of overwriting it`() {
        val dir = Files.createTempDirectory("legado-v3-skip")
        try {
            val from = dir.resolve("images")
            val to = dir.resolve("comic")
            AtomicFileStore(from).write("ab/keep.jpg", byteArrayOf(1, 1, 1, 1))
            AtomicFileStore(from).write("ab/move.jpg", byteArrayOf(2, 2, 2))
            AtomicFileStore(to).write("ab/keep.jpg", byteArrayOf(9, 9, 9, 9, 9))

            val result = moveCacheRoot(from, to)

            assertEquals("只有没冲突的那个被移动", 1, result.files)
            assertEquals(3L, result.bytes)
            assertEquals("目标已存在的那个必须跳过并计数", 1, result.skipped)
            assertEquals(0, result.failed)
            assertArrayEquals(byteArrayOf(9, 9, 9, 9, 9), Files.readAllBytes(to.resolve("ab/keep.jpg")))
            assertTrue("跳过时原件不许删（宁可冗余，也不赌两个文件等价）", Files.isRegularFile(from.resolve("ab/keep.jpg")))
            assertArrayEquals(byteArrayOf(2, 2, 2), Files.readAllBytes(to.resolve("ab/move.jpg")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 2. 封面命名：`书名 (书哈希).扩展名`
    // ------------------------------------------------------------------

    @Test
    fun `cover file name carries the book name and the same hash as the book folder`() {
        val hash = bookCacheHash(sourceId, bookUrl)
        assertEquals(AtomicFileStore.BOOK_HASH_LENGTH, hash.length)
        assertEquals(
            "书哈希口径必须与书目录完全一致（sha256(sourceId + 换行 + bookUrl) 前 20 位）",
            ImageBytes.sha256("$sourceId\n$bookUrl").take(AtomicFileStore.BOOK_HASH_LENGTH),
            hash,
        )
        assertEquals("$bookName ($hash).jpg", coverCacheFileName(bookName, hash, "image/jpeg"))
        assertEquals("$bookName ($hash).png", coverCacheFileName(bookName, hash, "image/png"))
        assertEquals("$bookName ($hash).webp", coverCacheFileName(bookName, hash, "image/webp"))
        assertEquals("$bookName ($hash).gif", coverCacheFileName(bookName, hash, "image/gif"))
        assertEquals("$bookName ($hash).avif", coverCacheFileName(bookName, hash, "image/avif"))
        // 未知类型 → bin（绝不猜：猜错就是"文件打不开"）
        assertEquals("$bookName ($hash).bin", coverCacheFileName(bookName, hash, "application/octet-stream"))
        assertEquals("$bookName ($hash).bin", coverCacheFileName(bookName, hash, null))
        // 拿不到书名 ⇒ 兜底 `未命名 (哈希).扩展名`（绝不因此失败，也绝不以空格或括号开头）
        assertEquals("$CACHE_UNNAMED ($hash).jpg", coverCacheFileName(null, hash, "image/jpeg"))
        assertEquals("$CACHE_UNNAMED ($hash).jpg", coverCacheFileName("   ", hash, "image/jpeg"))
        assertFalse(coverCacheFileName(null, hash, "image/jpeg").startsWith(" "))
        // 书名里的路径非法字符仍然要清洗（与书目录同一套规则）
        assertEquals("第1话_ 开始 ($hash).jpg", coverCacheFileName("第1话: 开始", hash, "image/jpeg"))
    }

    // ------------------------------------------------------------------
    // 3. 封面落盘 + 读取不依赖中文 + 路由直出
    // ------------------------------------------------------------------

    @Test
    fun `cover is stored under the book name and still served after the chinese part is garbled`() {
        val dir = Files.createTempDirectory("legado-v3-cover-route")
        val dbPath = dir.resolve("legado.sqlite").toString()
        val coversDir = dir.resolve("covers")
        val hash = bookCacheHash(sourceId, bookUrl)
        val expectedName = "$bookName ($hash).jpg"
        val garbledName = "涔熻 (${hash.uppercase()}).jpg"
        var key = ""
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            // 置成 v3 ⇒ 启动迁移 no-op，本用例只验证封面这一件事
            database.setSetting(CACHE_LAYOUT_KEY, CACHE_LAYOUT_VERSION)
            database.saveBookshelf(BookshelfWriteRequest(sourceId = sourceId, bookUrl = bookUrl, name = bookName, tocUrl = bookUrl), null)

            val cache = CoverCache(coversDir, database, { "image/jpeg" to jpegBytes() })
            val cover = cache.cache("https://img.example/cover.jpg", CoverBookRef(bookName, sourceId, bookUrl))
            key = cover.key
            assertEquals("文件名必须是 书名 (书哈希).扩展名", expectedName, database.coverFilePath(key))
            assertTrue(Files.isRegularFile(coversDir.resolve(expectedName)))

            // 挂到书架上（真实链路：materializeCover → updateBookshelfCover）
            assertTrue(database.updateBookshelfCover(sourceId, bookUrl, key, "image/jpeg"))

            // 中文段改成乱码（保留 `(书哈希)`，大小写也换掉）⇒ 读取必须仍然命中，并回填 file_path
            Files.move(coversDir.resolve(expectedName), coversDir.resolve(garbledName))
            assertNotNull("中文乱码不能让封面失联", cache.file(key))
            assertEquals("命中后必须回填 file_path", garbledName, database.coverFilePath(key))

            // file_path 也指歪（模拟索引与磁盘漂移）⇒ 只剩哈希段这一条线索也必须命中
            database.updateCoverFilePath(key, "不存在 (00000000000000000000).jpg")
            assertEquals(garbledName, coversDir.relativize(cache.file(key)!!).toString())
            assertEquals("兜底命中后同样要回填", garbledName, database.coverFilePath(key))

            // 传了书身份时（`file(key, book)`）即便没有任何索引也照样命中
            assertNotNull(cache.file(key, CoverBookRef(bookName, sourceId, bookUrl)))
        } finally {
            database.close()
        }

        try {
            // 路由级直出：中文是乱码，`GET /api/covers/{key}` 仍必须出图
            testApplication {
                application {
                    legadoApplication(
                        ServerConfig(
                            host = "0.0.0.0", port = 8080, databasePath = dbPath,
                            coverCacheDirectory = coversDir, webDavDirectory = dir.resolve("webdav"),
                            initialAdminPassword = "password-for-test", secureCookies = false,
                        ),
                    )
                }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    install(HttpCookies)
                }
                assertTrue(login(client, "password-for-test").isNotBlank())
                val response = client.get("/api/covers/$key")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertArrayEquals("直出的必须还是那张图", jpegBytes(), response.bodyAsBytes())
            }
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `cover without a book name falls back to unnamed file that is still locatable by key`() {
        withTempLayout("legado-v3-cover-unnamed") { database, dir ->
            val coversDir = dir.resolve("covers")
            // 上传封面：这一刻还不知道属于哪本书 ⇒ 文件名退回 `未命名 (内容 key).扩展名`
            val upload = CoverCache(coversDir, database = database)
            val key = upload.saveCoverBytes(pngBytes(), "image/png")
            val name = "$CACHE_UNNAMED ($key).png"
            assertEquals(name, database.coverFilePath(key))
            assertTrue(Files.isRegularFile(coversDir.resolve(name)))

            // 关联到书籍之后必须能"归位"成 `书名 (书哈希).png`
            assertTrue("adopt 必须真的改名", upload.adopt(key, CoverBookRef(bookName, sourceId, bookUrl)))
            val adopted = "$bookName (${bookCacheHash(sourceId, bookUrl)}).png"
            assertEquals(adopted, database.coverFilePath(key))
            assertTrue(Files.isRegularFile(coversDir.resolve(adopted)))
            assertFalse("旧名字不该留下副本", Files.exists(coversDir.resolve(name)))
            assertNotNull("归位后必须仍然读得到", upload.file(key))
        }
    }

    @Test
    fun `cover lookup never serves another revision of the same book`() {
        withTempLayout("legado-v3-cover-two-revisions") { database, dir ->
            val coversDir = dir.resolve("covers")
            database.saveBookshelf(BookshelfWriteRequest(sourceId = sourceId, bookUrl = bookUrl, name = bookName, tocUrl = bookUrl), null)
            val identity = CoverBookRef(bookName, sourceId, bookUrl)
            // 同一本书换封面：两把 key 会竞争同一个可读名（书名 + 书哈希 + 扩展名都一样）
            val cache = CoverCache(coversDir, database, { "image/jpeg" to jpegBytes(400, 600) })
            val oldKey = cache.sha256("https://img.example/old.jpg")
            val newKey = cache.sha256("https://img.example/new.jpg")

            val first = cache.cache("https://img.example/old.jpg", identity)
            assertEquals(oldKey, first.key)
            assertTrue(database.updateBookshelfCover(sourceId, bookUrl, oldKey, "image/jpeg"))
            val second = cache.cache("https://img.example/new.jpg", identity)
            assertEquals(newKey, second.key)
            assertTrue(database.updateBookshelfCover(sourceId, bookUrl, newKey, "image/jpeg"))

            // 可读名归最后写入者：旧 key 的快路径必须被清掉，它记的那个路径现在装的是**另一版**字节
            assertNull("旧 key 不许再认领那个可读名", database.coverFilePath(oldKey))
            assertNotNull("新 key 必须读得到自己的那版", cache.file(newKey))
            assertNull("旧 key 宁可判『没有』回源重下，也绝不直出另一版", cache.file(oldKey))
        }
    }

    // ------------------------------------------------------------------
    // 4. 封面目录迁移：能对上书架的改名，孤儿留在原处
    // ------------------------------------------------------------------

    @Test
    fun `cover migration renames shelf covers and leaves orphans where they are`() {
        withTempLayout("legado-v3-cover-migrate") { database, dir ->
            val coversDir = dir.resolve("covers")
            val coverStore = AtomicFileStore(coversDir)
            val linkedKey = "c".repeat(64)
            val orphanKey = "d".repeat(64)
            coverStore.write(linkedKey, jpegBytes())
            coverStore.write(orphanKey, pngBytes())
            database.recordCoverCache(linkedKey, "image/jpeg")
            database.recordCoverCache(orphanKey, "image/png")
            database.saveBookshelf(
                BookshelfWriteRequest(sourceId = sourceId, bookUrl = bookUrl, name = bookName, tocUrl = bookUrl),
                CachedCover(linkedKey, "image/jpeg"),
            )
            assertEquals("迁移前认的是旧名字", null, database.coverFilePath(linkedKey))

            val logs = mutableListOf<String>()
            // 版本未设置 ⇒ 走 v1 分支（清旧布局），封面改名在**同一处**一并完成
            assertTrue(migrateCacheLayout(database, dir.resolve("comic"), dir.resolve("images"), dir.resolve("audio"), coversDir) { logs += it })

            val renamed = "$bookName (${bookCacheHash(sourceId, bookUrl)}).jpg"
            assertTrue("能对上书架的封面必须改名成 书名 (书哈希).扩展名", Files.isRegularFile(coversDir.resolve(renamed)))
            assertFalse("改完名不许留下旧文件", Files.exists(coversDir.resolve(linkedKey)))
            assertEquals("改名后必须把快路径记上", renamed, database.coverFilePath(linkedKey))
            assertNotNull("改名后必须仍然读得到", CoverCache(coversDir, database = database).file(linkedKey))
            // 孤儿（没有书架记录引用）：留在原处。理由见 `migrateCoverFileNames` 的注释 ——
            // 它没有书身份（算不出书哈希），而 `<key>` 命名本身就是读取侧最后一档兜底 ⇒ 留在原处零损失
            assertTrue("孤儿封面必须留在原处", Files.isRegularFile(coversDir.resolve(orphanKey)))
            assertNull("孤儿不留假的快路径", database.coverFilePath(orphanKey))
            assertTrue("日志要如实报告跳过：$logs", logs.any { it.contains("无书架引用") })
        }
    }

    // ------------------------------------------------------------------
    // 5. 反向断言：漫画缓存根只可能是 comic，images 只留给插图
    // ------------------------------------------------------------------

    @Test
    fun `comic cache root is comic and images is reserved for novel illustrations`() {
        val dir = Files.createTempDirectory("legado-v3-names")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = "unused.sqlite",
                coverCacheDirectory = dir.resolve("covers"), webDavDirectory = dir.resolve("webdav"),
                initialAdminPassword = null, secureCookies = false,
            )
            assertEquals("漫画缓存根必须叫 comic", "comic", config.comicCacheDirectory.fileName.toString())
            assertEquals("插图预留根必须叫 images", "images", config.illustrationCacheDirectory.fileName.toString())
            assertEquals(config.coverCacheDirectory.parent, config.comicCacheDirectory.parent)
            // 环境变量入口同样只认 comic（别只在默认值上改一半）
            val fromEnv = ServerConfig.fromEnvironment(mapOf("LEGADO_DATA_DIR" to dir.toString()))
            assertEquals("comic", fromEnv.comicCacheDirectory.fileName.toString())
            assertEquals("images", fromEnv.illustrationCacheDirectory.fileName.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }

        // grep 级反向断言：源码里**没有**任何把漫画缓存往 `images` 根里写的路径
        val sourcesRoot = Paths.get("src", "main", "kotlin")
        assertTrue("测试工作目录必须是 server/，否则这条断言会假通过", Files.isDirectory(sourcesRoot))
        val files = Files.walk(sourcesRoot).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
        assertTrue("必须真的扫到服务端源码", files.isNotEmpty())
        val offenders = mutableListOf<String>()
        files.forEach { file ->
            Files.readAllLines(file).forEachIndexed { index, raw ->
                val where = "${file.fileName}:${index + 1}"
                // 注释不参与（文档里当然可以聊 `images`）：这里只砍掉行尾注释
                val code = raw.substringBefore("//")
                if (raw.contains("resolve(\"images\")") || raw.contains("resolveSibling(\"images\")")) {
                    offenders += "$where 把缓存根拼成了 images：${raw.trim()}"
                } else if (code.contains("\"images\"") && !code.contains("ILLUSTRATION_DIR_NAME")) {
                    offenders += "$where 出现了 images 字面量：${raw.trim()}"
                }
            }
        }
        assertEquals("漫画缓存根必须叫 comic，images 只留给小说插图：\n${offenders.joinToString("\n")}", emptyList<String>(), offenders)

        // 接线检查：漫画缓存实例只能挂在 comic 根上（常量改了却接错线是最常见的"改一半"）
        val wiring = files.filter { it.fileName.toString() == "Application.kt" }
            .flatMap { Files.readAllLines(it) }
            .filter { it.contains("ImageCache(") && !it.trimStart().startsWith("//") }
        assertTrue("必须能扫到 ImageCache 的构造点", wiring.isNotEmpty())
        wiring.forEach { line -> assertTrue("漫画缓存实例必须挂在 comic 根上：${line.trim()}", line.contains("comicCacheDirectory")) }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun withTempLayout(name: String, block: (Database, Path) -> Unit) {
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

    private suspend fun login(client: HttpClient, password: String): String {
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body<LoginResponse>().csrfToken
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

    /** 最小合法 PNG 头（魔数可识别，扩展名会落成 png）。 */
    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )
}
