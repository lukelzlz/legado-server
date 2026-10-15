package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
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

/**
 * 方案 G2 · G9/§8 的**进度毫秒语义**验证。
 *
 * 这是整份设计里唯一一条「与既有校验直接冲突」的地方（§8）：
 * 服务端把 `scroll_position` 钉在 `0.0..1.0`，毫秒值会被 **400** 直接拒掉。
 * 裁决选 **(a) 新增 `position_ms` 列**（G-1），于是这里要钉住四件事：
 *
 * 1. 毫秒能**往返**（写进去、读出来一模一样）；
 * 2. 文本/漫画的写入路径（不带 positionMs）**绝不抹掉**已有的毫秒进度；
 * 3. **0 毫秒是合法进度**，与"没有毫秒进度"必须可区分（AGENTS.md：严禁用可空列做存在性判断）；
 * 4. 迁移**幂等**（老库补列，重复启动不炸），且毫秒会镜像进手机端进度文件的 `durChapterPos`。
 */
class AudioProgressTest {

    private val sourceId = "https://audio.example.com"
    private val bookUrl = "$sourceId/book/1"
    private val chapterUrl = "$sourceId/chapter/1"

    @Test
    fun `round trips millisecond progress through the api`() = testApplication {
        val dataDir = Files.createTempDirectory("legado-audio-progress")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = dataDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
                audioCacheDirectory = dataDir.resolve("audio"),
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }
            val csrf = login(client, "test-password-1234")

            // 1. 有声书：毫秒 + 比例固定 0（毫秒是唯一权威，绝不混进 scroll_position）
            val saved = client.put("/api/reading-progress") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(ReadingProgress(sourceId, bookUrl, chapterUrl, 3, 0.0, positionMs = 1_834_521))
            }
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(1_834_521L, saved.body<ReadingProgress>().positionMs)

            val fetched = client.get("/api/reading-progress?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertEquals("毫秒必须原样往返", 1_834_521L, fetched.body<ReadingProgress>().positionMs)

            // 2. 文本/漫画的写入路径不带 positionMs ⇒ **绝不能抹掉**毫秒（coalesce 保护）
            val novelWrite = client.put("/api/reading-progress") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(ReadingProgress(sourceId, bookUrl, chapterUrl, 4, 0.42))
            }
            assertEquals(HttpStatusCode.OK, novelWrite.status)
            assertEquals(
                "不带毫秒的写入不得清空已有毫秒进度",
                1_834_521L,
                client.get("/api/reading-progress?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}").body<ReadingProgress>().positionMs,
            )

            // 3. 0 毫秒是**合法**进度（章首），必须与"没有毫秒进度"区分开
            client.put("/api/reading-progress") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(ReadingProgress(sourceId, bookUrl, chapterUrl, 5, 0.0, positionMs = 0))
            }
            assertEquals(
                "0 必须被当成真实值存下来（章首），而不是被当成 null",
                0L,
                client.get("/api/reading-progress?sourceId=${enc(sourceId)}&bookUrl=${enc(bookUrl)}").body<ReadingProgress>().positionMs,
            )

            // 4. 负数毫秒 ⇒ 400（既有的 0..1 校验**原样保留**，只额外加这一条）
            val negative = client.put("/api/reading-progress") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(ReadingProgress(sourceId, bookUrl, chapterUrl, 5, 0.0, positionMs = -1))
            }
            assertEquals(HttpStatusCode.BadRequest, negative.status)

            // 5. 比例越界仍然 400（证明毫秒的加入没有顺手放宽既有语义）
            val outOfRange = client.put("/api/reading-progress") {
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(ReadingProgress(sourceId, bookUrl, chapterUrl, 5, 1_834_521.0))
            }
            assertEquals("毫秒绝不能塞进 scroll_position（会被既有校验拒掉，这正是 §8 的冲突）", HttpStatusCode.BadRequest, outOfRange.status)
        } finally {
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `position_ms migration is idempotent on an existing database`() {
        val dbPath = Files.createTempFile("legado-audio-progress-migrate", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            // 二次 initialize（等价于"服务重启"）：迁移必须幂等，不能因为列已存在而抛
            database.initialize("password-for-test")

            database.saveProgress(ReadingProgress(sourceId, bookUrl, chapterUrl, 1, 0.0, positionMs = 7_500))
            assertEquals(7_500L, database.getProgress(sourceId, bookUrl)?.positionMs)
        } finally {
            database.close()
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    @Test
    fun `listReadingAnchors exposes millisecond position for audio books`() {
        val dbPath = Files.createTempFile("legado-audio-anchor", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            database.saveProgress(ReadingProgress(sourceId, bookUrl, chapterUrl, 3, 0.0, positionMs = 61_000))

            val anchor = database.listReadingAnchors().first { it.bookUrl == bookUrl }
            assertEquals("GC 的完成度要靠它算（毫秒 / 该章总时长）", 61_000L, anchor.positionMs)
        } finally {
            database.close()
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    @Test
    fun `mirrors millisecond position into the phone progress file as durChapterPos`() {
        val root = Files.createTempDirectory("legado-audio-progress-file")
        val dbPath = Files.createTempFile("legado-audio-progress-file", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            val sync = BookProgressSync(root, database)

            // 有声书：毫秒是我们负责的字段 ⇒ 直传成 durChapterPos（与手机端同语义）
            assertTrue(sync.write("测试有声书", "测试作者", chapterIndex = 3, chapterTitle = "第 4 集", positionMs = 1_834_521))
            val file = findProgressFile(root)
            assertNotNull("进度文件必须写出来", file)
            val payload = Json.parseToJsonElement(Files.readString(file!!)) as JsonObject
            assertEquals(1_834_521L, payload["durChapterPos"]!!.jsonPrimitive.longOrNull)

            // 文本/漫画：positionMs 为空 ⇒ 沿用文件里手机端写的值（我们无权改写字符偏移量）
            assertTrue(sync.write("测试有声书", "测试作者", chapterIndex = 4, chapterTitle = "第 5 集", positionMs = null))
            val again = Json.parseToJsonElement(Files.readString(file)) as JsonObject
            assertEquals("不带毫秒时必须保留手机端写入的值", 1_834_521L, again["durChapterPos"]!!.jsonPrimitive.longOrNull)
            assertEquals(4L, again["durChapterIndex"]!!.jsonPrimitive.longOrNull)
        } finally {
            database.close()
            runCatching { root.toFile().deleteRecursively() }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    @Test
    fun `audio progress keeps scroll_position untouched for text books semantics`() {
        val dbPath = Files.createTempFile("legado-audio-progress-semantics", ".sqlite").toString()
        val database = Database(dbPath)
        try {
            database.initialize("password-for-test")
            val novel = "https://novel.example.com/book/1"
            database.saveProgress(ReadingProgress("https://novel.example.com", novel, "ch-1", 1, 0.42))
            assertNull("文本进度不该有毫秒字段", database.getProgress("https://novel.example.com", novel)?.positionMs)
            assertEquals(0.42, database.getProgress("https://novel.example.com", novel)!!.scrollPosition, 0.0001)
        } finally {
            database.close()
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun findProgressFile(root: Path): Path? = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.findFirst().orElse(null)
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun login(client: io.ktor.client.HttpClient, pass: String): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = pass))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return res.body<LoginResponse>().csrfToken
    }
}
