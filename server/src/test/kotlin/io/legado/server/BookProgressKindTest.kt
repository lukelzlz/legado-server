package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 验证 WebDAV 进度镜像（bookProgress 目录）针对小说、听书、漫画的类型隔离。
 *
 * 核心诉求：
 * - 同一本书（同名同作者），如果是小说、听书、漫画，严禁互相覆盖；
 * - 有声加上 `_audio`（`<书名>_<作者>_audio.json`）；
 * - 漫画加上 `_comic`（`<书名>_<作者>_comic.json`）；
 * - 小说（novel / local / null）保持无后缀（`<书名>_<作者>.json`），手机端开源阅读只同步小说；
 * - 读取与写入严格按类型隔离，严禁跨类型误读或降级借用。
 */
class BookProgressKindTest {

    private fun tempRoot(): Path = Files.createTempDirectory("progress-kind-test")

    private fun newDb(): Database {
        val db = Database(Files.createTempFile("progress-kind-db", ".sqlite").toString())
        db.initialize("test-pass")
        return db
    }

    private fun sync(root: Path, db: Database = newDb()) = BookProgressSync(root, db)

    @Test
    fun `same book name with novel, audio, and comic writes to independent files without collision`() {
        val root = tempRoot()
        val s = sync(root)
        val name = "凡人修仙传"
        val author = "忘语"

        // 1. 小说进度：第 500 章
        assertTrue("小说写入应成功", s.write(name, author, chapterIndex = 500, chapterTitle = "第五百章 元婴", bookKind = "novel"))

        // 2. 有声书进度：第 30 集（带毫秒）
        assertTrue("有声写入应成功", s.write(name, author, chapterIndex = 30, chapterTitle = "第 30 集 筑基", positionMs = 120_000L, bookKind = "audio"))

        // 3. 漫画进度：第 10 话
        assertTrue("漫画写入应成功", s.write(name, author, chapterIndex = 10, chapterTitle = "第 10 话 初战", bookKind = "comic"))

        val dir = s.directory()!!
        val novelFile = dir.resolve("${name}_${author}.json")
        val audioFile = dir.resolve("${name}_${author}_audio.json")
        val comicFile = dir.resolve("${name}_${author}_comic.json")

        assertTrue("小说进度文件应存在且为原格式", Files.exists(novelFile))
        assertTrue("有声书进度文件应存在且带 _audio", Files.exists(audioFile))
        assertTrue("漫画进度文件应存在且带 _comic", Files.exists(comicFile))

        // 验证小说进度未被覆盖
        val novelJson = Json.parseToJsonElement(novelFile.readText()) as JsonObject
        assertEquals(500L, novelJson["durChapterIndex"]!!.jsonPrimitive.longOrNull)
        assertEquals("第五百章 元婴", novelJson["durChapterTitle"]!!.jsonPrimitive.content)

        // 验证有声进度独立
        val audioJson = Json.parseToJsonElement(audioFile.readText()) as JsonObject
        assertEquals(30L, audioJson["durChapterIndex"]!!.jsonPrimitive.longOrNull)
        assertEquals(120_000L, audioJson["durChapterPos"]!!.jsonPrimitive.longOrNull)
        assertEquals("第 30 集 筑基", audioJson["durChapterTitle"]!!.jsonPrimitive.content)

        // 验证漫画进度独立
        val comicJson = Json.parseToJsonElement(comicFile.readText()) as JsonObject
        assertEquals(10L, comicJson["durChapterIndex"]!!.jsonPrimitive.longOrNull)
        assertEquals("第 10 话 初战", comicJson["durChapterTitle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reading progress by bookKind is strictly isolated and never reads other kinds`() {
        val root = tempRoot()
        val s = sync(root)
        val name = "斗罗大陆"
        val author = "唐家三少"

        s.write(name, author, chapterIndex = 100, chapterTitle = "小说第100章", bookKind = "novel")
        s.write(name, author, chapterIndex = 25, chapterTitle = "有声第25集", positionMs = 60_000L, bookKind = "audio")
        s.write(name, author, chapterIndex = 5, chapterTitle = "漫画第5话", bookKind = "comic")

        // 小说只读小说
        val readNovel = s.read(name, author, "novel")
        assertNotNull("应读出小说进度", readNovel)
        assertEquals(100, readNovel!!.chapterIndex)
        assertEquals("小说第100章", readNovel.chapterTitle)
        assertEquals("${name}_${author}.json", readNovel.fileName)

        // 缺省/local 均视为小说
        val readDefault = s.read(name, author, null)
        assertNotNull(readDefault)
        assertEquals(100, readDefault!!.chapterIndex)

        val readLocal = s.read(name, author, "local")
        assertNotNull(readLocal)
        assertEquals(100, readLocal!!.chapterIndex)

        // 有声只读有声
        val readAudio = s.read(name, author, "audio")
        assertNotNull("应读出有声进度", readAudio)
        assertEquals(25, readAudio!!.chapterIndex)
        assertEquals("有声第25集", readAudio.chapterTitle)
        assertEquals("${name}_${author}_audio.json", readAudio.fileName)

        // 漫画只读漫画
        val readComic = s.read(name, author, "comic")
        assertNotNull("应读出漫画进度", readComic)
        assertEquals(5, readComic!!.chapterIndex)
        assertEquals("漫画第5话", readComic.chapterTitle)
        assertEquals("${name}_${author}_comic.json", readComic.fileName)
    }

    @Test
    fun `reading non-existent kind returns null and never borrows from novel`() {
        val root = tempRoot()
        val s = sync(root)
        val name = "只看小说不听书"
        val author = "某作者"

        // 只写了小说
        s.write(name, author, chapterIndex = 88, chapterTitle = "第88章", bookKind = "novel")

        // 用 audio 或 comic 来读，必须返回 null，绝不能跨媒介借用小说进度
        assertNull("只有小说时，读取 audio 必须返回 null", s.read(name, author, "audio"))
        assertNull("只有小说时，读取 comic 必须返回 null", s.read(name, author, "comic"))
    }

    @Test
    fun `reading novel returns null when only audio or comic exists`() {
        val root = tempRoot()
        val s = sync(root)
        val name = "纯有声书"
        val author = "演播家"

        // 只写了有声
        s.write(name, author, chapterIndex = 12, chapterTitle = "第12集", positionMs = 50_000L, bookKind = "audio")

        // 手机端开源阅读看小说，只找无后缀的小说进度，绝不能把有声进度读成小说进度
        assertNull("只有有声时，读取小说必须返回 null", s.read(name, author, "novel"))
        assertNull("只有有声时，缺省类别读取必须返回 null", s.read(name, author, null))
    }

    @Test
    fun `candidates without author use bookName_audio and bookName_comic`() {
        val root = tempRoot()
        val s = sync(root)
        val name = "十日终焉"

        s.write(name, author = null, chapterIndex = 1, chapterTitle = "第1章", bookKind = "novel")
        s.write(name, author = null, chapterIndex = 2, chapterTitle = "第2集", positionMs = 30_000L, bookKind = "audio")
        s.write(name, author = null, chapterIndex = 3, chapterTitle = "第3话", bookKind = "comic")

        val dir = s.directory()!!
        assertTrue(Files.exists(dir.resolve("$name.json")))
        assertTrue(Files.exists(dir.resolve("${name}_audio.json")))
        assertTrue(Files.exists(dir.resolve("${name}_comic.json")))

        assertEquals(1, s.read(name, null, "novel")!!.chapterIndex)
        assertEquals(2, s.read(name, null, "audio")!!.chapterIndex)
        assertEquals(3, s.read(name, null, "comic")!!.chapterIndex)
    }

    @Test
    fun `fallback scan isolates by kind even with title cleaning`() {
        val root = tempRoot()
        val s = sync(root)
        // 书名带换行和特殊字符
        val rawNovelName = "十日终焉我成魔\n第八十章 星尘归寂"
        val rawAuthor = "梁灼安/特注"

        s.write(rawNovelName, rawAuthor, chapterIndex = 80, chapterTitle = "第八十章", bookKind = "novel")
        s.write(rawNovelName, rawAuthor, chapterIndex = 15, chapterTitle = "第十五集", positionMs = 80_000L, bookKind = "audio")

        // 兜底比对必须严格按类别
        val readNovel = s.read(rawNovelName, rawAuthor, "novel")
        assertNotNull(readNovel)
        assertEquals(80, readNovel!!.chapterIndex)

        val readAudio = s.read(rawNovelName, rawAuthor, "audio")
        assertNotNull(readAudio)
        assertEquals(15, readAudio!!.chapterIndex)
    }

    @Test
    fun `end-to-end http progress save and merge isolates by bookKind on the same book name`() {
        val dataDir = Files.createTempDirectory("legado-progress-kind-http")
        try {
            val dbPath = dataDir.resolve("test.sqlite").toString()
            val database = Database(dbPath)
            database.initialize("test-password-progress-kind")

            val sourceNovel = "src-novel"
            val sourceAudio = "src-audio"
            val sourceComic = "src-comic"
            val bookName = "一念永恒"
            val author = "耳根"

            val urlNovel = "https://example.com/novel/yn"
            val urlAudio = "https://example.com/audio/yn"
            val urlComic = "https://example.com/comic/yn"

            // 预置三本同名书到书架
            database.saveBookshelf(BookshelfWriteRequest(sourceNovel, urlNovel, bookName, author, "$urlNovel/toc", bookKind = "novel"), cover = null)
            database.saveBookshelf(BookshelfWriteRequest(sourceAudio, urlAudio, bookName, author, "$urlAudio/toc", bookKind = "audio"), cover = null)
            database.saveBookshelf(BookshelfWriteRequest(sourceComic, urlComic, bookName, author, "$urlComic/toc", bookKind = "comic"), cover = null)

            // 预置目录章节
            database.saveTocCache(sourceNovel, "$urlNovel/toc", listOf(Chapter(150, "第一百五十章 筑基试炼", "$urlNovel/c150")))
            database.saveTocCache(sourceAudio, "$urlAudio/toc", listOf(Chapter(30, "第30集 试炼开启", "$urlAudio/c30")))
            database.saveTocCache(sourceComic, "$urlComic/toc", listOf(Chapter(8, "第8话 宗门大比", "$urlComic/c8")))

            val webDavDir = dataDir.resolve("webdav")
            Files.createDirectories(webDavDir)

            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = webDavDir,
                initialAdminPassword = "test-password-progress-kind", secureCookies = false,
                audioCacheDirectory = dataDir.resolve("audio"),
            )

            testApplication {
                application { legadoApplication(config) }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    install(HttpCookies)
                }

                // 登录
                val loginRes = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(password = "test-password-progress-kind"))
                }
                assertEquals(HttpStatusCode.OK, loginRes.status)
                val csrf = loginRes.body<LoginResponse>().csrfToken

                // 1. 小说保存进度：第 150 章
                val putNovel = client.put("/api/reading-progress") {
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(ReadingProgress(sourceNovel, urlNovel, "$urlNovel/c150", 150, 0.5, chapterTitle = "第一百五十章 筑基试炼", bookKind = "novel"))
                }
                assertEquals(HttpStatusCode.OK, putNovel.status)

                // 2. 有声书保存进度：第 30 集
                val putAudio = client.put("/api/reading-progress") {
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(ReadingProgress(sourceAudio, urlAudio, "$urlAudio/c30", 30, 0.0, chapterTitle = "第30集 试炼开启", positionMs = 150_000L, bookKind = "audio"))
                }
                assertEquals(HttpStatusCode.OK, putAudio.status)

                // 3. 漫画保存进度：第 8 话
                val putComic = client.put("/api/reading-progress") {
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(ReadingProgress(sourceComic, urlComic, "$urlComic/c8", 8, 0.8, chapterTitle = "第8话 宗门大比", bookKind = "comic"))
                }
                assertEquals(HttpStatusCode.OK, putComic.status)

                // 检查 WebDAV 进度目录：三个文件各自独立，小说进度绝未被覆盖！
                val syncDir = webDavDir.resolve("legado").resolve("bookProgress")
                val novelFile = syncDir.resolve("${bookName}_${author}.json")
                val audioFile = syncDir.resolve("${bookName}_${author}_audio.json")
                val comicFile = syncDir.resolve("${bookName}_${author}_comic.json")

                assertTrue("小说进度文件存在", Files.exists(novelFile))
                assertTrue("有声进度文件存在", Files.exists(audioFile))
                assertTrue("漫画进度文件存在", Files.exists(comicFile))

                val novelData = Json.parseToJsonElement(novelFile.readText()) as JsonObject
                assertEquals("小说进度必须是 150 章，绝不能被有声或漫画冲掉", 150L, novelData["durChapterIndex"]!!.jsonPrimitive.longOrNull)

                val audioData = Json.parseToJsonElement(audioFile.readText()) as JsonObject
                assertEquals(30L, audioData["durChapterIndex"]!!.jsonPrimitive.longOrNull)
                assertEquals(150_000L, audioData["durChapterPos"]!!.jsonPrimitive.longOrNull)

                val comicData = Json.parseToJsonElement(comicFile.readText()) as JsonObject
                assertEquals(8L, comicData["durChapterIndex"]!!.jsonPrimitive.longOrNull)

                // 4. 打开书时的进度合并 (/progress-sync/merge)
                // 小说合并：只能匹配到小说文件
                val mergeNovel = client.post("/api/progress-sync/merge") {
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(ProgressMergeRequest(sourceNovel, urlNovel, listOf(Chapter(150, "第一百五十章 筑基试炼", "$urlNovel/c150")), bookKind = "novel"))
                }
                assertEquals(HttpStatusCode.OK, mergeNovel.status)
                val novelMergeRes = mergeNovel.body<ProgressMergeResponse>()
                assertTrue(novelMergeRes.fileFound)
                assertEquals(150, novelMergeRes.progress?.chapterIndex)

                // 有声合并：只能匹配到有声文件
                val mergeAudio = client.post("/api/progress-sync/merge") {
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(ProgressMergeRequest(sourceAudio, urlAudio, listOf(Chapter(30, "第30集 试炼开启", "$urlAudio/c30")), bookKind = "audio"))
                }
                assertEquals(HttpStatusCode.OK, mergeAudio.status)
                val audioMergeRes = mergeAudio.body<ProgressMergeResponse>()
                assertTrue(audioMergeRes.fileFound)
                assertEquals(30, audioMergeRes.progress?.chapterIndex)
                assertEquals(150_000L, audioMergeRes.progress?.positionMs)

                // 漫画合并：只能匹配到漫画文件
                val mergeComic = client.post("/api/progress-sync/merge") {
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(ProgressMergeRequest(sourceComic, urlComic, listOf(Chapter(8, "第8话 宗门大比", "$urlComic/c8")), bookKind = "comic"))
                }
                assertEquals(HttpStatusCode.OK, mergeComic.status)
                val comicMergeRes = mergeComic.body<ProgressMergeResponse>()
                assertTrue(comicMergeRes.fileFound)
                assertEquals(8, comicMergeRes.progress?.chapterIndex)
            }
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }
}
