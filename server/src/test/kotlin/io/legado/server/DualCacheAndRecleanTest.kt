package io.legado.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager

class DualCacheAndRecleanTest {

    @Test
    fun `dual cache storage preserves raw content and allows recleaning`() {
        val path = Files.createTempFile("test-dual-cache", ".sqlite").toString()
        val db = Database(path)
        db.initialize("admin123")
        val runner = RuleRunner(db)

        val sourceId = "test-source-1"
        val bookUrl = "https://test.com/book/1"
        val chapterUrl = "https://test.com/book/1/c1"

        // 1. Add book to shelf
        db.saveBookshelf(
            BookshelfWriteRequest(
                sourceId = sourceId,
                bookUrl = bookUrl,
                name = "诡秘之主",
                author = "爱潜水的乌贼",
                tocUrl = "https://test.com/book/1/toc",
            ),
            null,
        )

        // 2. Cache content with raw and cleaned versions
        val originalRaw = "这是一个包含广告 https://ad.com 的章节，主角叫小丑。"
        val initialCleaned = "这是一个包含广告 https://ad.com 的章节，主角叫小丑。"
        db.cacheBookContent(
            sourceId,
            bookUrl,
            chapterUrl,
            ChapterContent(
                title = "第一章 绯红",
                content = initialCleaned,
                rawTitle = "第一章 绯红",
                rawContent = originalRaw,
            )
        )

        val cached1 = db.cachedContent(sourceId, bookUrl, chapterUrl)
        assertNotNull(cached1)
        assertEquals("这是一个包含广告 https://ad.com 的章节，主角叫小丑。", cached1!!.content)
        assertEquals(originalRaw, cached1.rawContent)

        // 3. Add replace rules for this book
        val rule1 = ReplaceRule(
            name = "去广告",
            pattern = "https?://\\S+",
            replacement = "",
            isRegex = true,
            scope = "诡秘之主",
        )
        val rule2 = ReplaceRule(
            name = "改名",
            pattern = "小丑",
            replacement = "愚者",
            isRegex = false,
            scope = "诡秘之主",
        )
        val saved1 = db.saveReplaceRule(rule1)
        val saved2 = db.saveReplaceRule(rule2)

        // 4. Trigger reclean
        val recleanRes = db.recleanBookCache(sourceId, bookUrl, runner.jsSandbox)
        assertEquals(1, recleanRes.recleanedChapters)
        assertEquals(1, recleanRes.totalChapters)

        val cached2 = db.cachedContent(sourceId, bookUrl, chapterUrl)
        assertNotNull(cached2)
        assertEquals("这是一个包含广告  的章节，主角叫愚者。", cached2!!.content)
        assertEquals(originalRaw, cached2.rawContent) // Raw content must remain unmodified

        // 5. Update rules (change 愚者 to 克莱恩)
        db.deleteReplaceRules(listOf(saved2.id))
        val rule3 = ReplaceRule(
            name = "改名2",
            pattern = "小丑",
            replacement = "克莱恩",
            isRegex = false,
            scope = "诡秘之主",
        )
        val saved3 = db.saveReplaceRule(rule3)

        // Re-clean again based on raw_content
        db.recleanBookCache(sourceId, bookUrl, runner.jsSandbox)
        val cached3 = db.cachedContent(sourceId, bookUrl, chapterUrl)
        assertNotNull(cached3)
        assertEquals("这是一个包含广告  的章节，主角叫克莱恩。", cached3!!.content)
        assertEquals(originalRaw, cached3.rawContent)

        // 6. Delete all rules and reclean -> restores original raw content
        db.deleteReplaceRules(listOf(saved1.id, saved3.id))
        db.recleanBookCache(sourceId, bookUrl, runner.jsSandbox)
        val cached4 = db.cachedContent(sourceId, bookUrl, chapterUrl)
        assertNotNull(cached4)
        assertEquals(originalRaw, cached4!!.content)
        assertEquals(originalRaw, cached4.rawContent)
    }

    @Test
    fun `legacy cache with null raw columns falls back gracefully on reclean`() {
        val path = Files.createTempFile("test-legacy-cache", ".sqlite").toString()
        val db = Database(path)
        db.initialize("admin123")
        val runner = RuleRunner(db)

        val sourceId = "legacy-source"
        val bookUrl = "https://test.com/legacy/1"
        val chapterUrl = "https://test.com/legacy/1/c1"

        db.saveBookshelf(
            BookshelfWriteRequest(
                sourceId = sourceId,
                bookUrl = bookUrl,
                name = "凡人修仙传",
                author = "忘语",
                tocUrl = "https://test.com/legacy/1/toc",
            ),
            null,
        )

        // Directly insert legacy row with null raw_title and null raw_content
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.prepareStatement("""
                insert into book_content_cache(source_id, book_url, chapter_url, title, content, cached_at, raw_title, raw_content)
                values(?, ?, ?, ?, ?, ?, null, null)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, sourceId)
                stmt.setString(2, bookUrl)
                stmt.setString(3, chapterUrl)
                stmt.setString(4, "第一章 山边小村")
                stmt.setString(5, "韩立看着眼前的草药。")
                stmt.setLong(6, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }

        val cachedLegacy = db.cachedContent(sourceId, bookUrl, chapterUrl)
        assertNotNull(cachedLegacy)
        assertEquals("韩立看着眼前的草药。", cachedLegacy!!.content)
        assertEquals("韩立看着眼前的草药。", cachedLegacy.rawContent) // Coalesced

        // Add rule and reclean
        db.saveReplaceRule(
            ReplaceRule(
                name = "韩天尊",
                pattern = "韩立",
                replacement = "韩天尊",
                isRegex = false,
                scope = "凡人修仙传",
            )
        )

        val recleanRes = db.recleanBookCache(sourceId, bookUrl, runner.jsSandbox)
        assertEquals(1, recleanRes.recleanedChapters)

        val cachedAfter = db.cachedContent(sourceId, bookUrl, chapterUrl)
        assertNotNull(cachedAfter)
        assertEquals("韩天尊看着眼前的草药。", cachedAfter!!.content)
    }

    @Test
    fun `http endpoints for single and batch reclean`() = testApplication {
        val dbPath = Files.createTempFile("legado-e2e-reclean", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-e2e-reclean-dir")
        val config = ServerConfig(
            host = "0.0.0.0",
            port = 8080,
            databasePath = dbPath,
            coverCacheDirectory = tempDir,
            webDavDirectory = tempDir.resolve("webdav"),
            initialAdminPassword = "adminPassword123!",
            secureCookies = false,
        )

        application {
            legadoApplication(config)
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
        }

        // Login
        val loginResp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("adminPassword123!"))
        }
        assertEquals(HttpStatusCode.OK, loginResp.status)
        val csrfToken = loginResp.headers["X-CSRF-Token"] ?: loginResp.body<LoginResponse>().csrfToken

        // Add bookshelf book & cached content
        val sourceId = "http-test-src"
        val bookUrl = "https://test.com/book/http"
        val chapterUrl = "https://test.com/book/http/c1"

        val db = Database(dbPath)
        db.initialize("adminPassword123!")
        db.saveBookshelf(
            BookshelfWriteRequest(
                sourceId = sourceId,
                bookUrl = bookUrl,
                name = "宿命之环",
                author = "爱潜水的乌贼",
                tocUrl = "https://test.com/book/http/toc",
            ),
            null,
        )
        db.cacheBookContent(
            sourceId,
            bookUrl,
            chapterUrl,
            ChapterContent(
                title = "第1章 外乡人",
                content = "卢米安在科尔杜村散步。",
                rawTitle = "第1章 外乡人",
                rawContent = "卢米安在科尔杜村散步。",
            )
        )

        // Save replace rule
        val ruleResp = client.post("/api/replace-rules") {
            header("X-CSRF-Token", csrfToken)
            contentType(ContentType.Application.Json)
            setBody(
                ReplaceRule(
                    name = "卢宝",
                    pattern = "卢米安",
                    replacement = "卢宝",
                    isRegex = false,
                    scope = "宿命之环",
                )
            )
        }
        assertEquals(HttpStatusCode.OK, ruleResp.status)

        // Test POST /api/bookshelf/reclean
        val recleanResp = client.post("/api/bookshelf/reclean") {
            header("X-CSRF-Token", csrfToken)
            contentType(ContentType.Application.Json)
            setBody(BookRecleanRequest(sourceId, bookUrl))
        }
        assertEquals(HttpStatusCode.OK, recleanResp.status)
        val recleanResult = recleanResp.body<BookRecleanResponse>()
        assertEquals(1, recleanResult.recleanedChapters)

        val contentAfter = db.cachedContent(sourceId, bookUrl, chapterUrl)
        assertEquals("卢宝在科尔杜村散步。", contentAfter?.content)

        // Test POST /api/bookshelf/batch-reclean
        val batchResp = client.post("/api/bookshelf/batch-reclean") {
            header("X-CSRF-Token", csrfToken)
            contentType(ContentType.Application.Json)
            setBody(BatchBookRecleanRequest(listOf(BookRecleanRequest(sourceId, bookUrl))))
        }
        assertEquals(HttpStatusCode.OK, batchResp.status)
        val batchResult = batchResp.body<BatchBookRecleanResponse>()
        assertEquals(1, batchResult.totalRecleaned)
    }
}
