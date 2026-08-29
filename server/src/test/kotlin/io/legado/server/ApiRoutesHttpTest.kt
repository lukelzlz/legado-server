package io.legado.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ApiRoutesHttpTest {

    private suspend fun setupAuthenticatedClient(client: HttpClient, password: String): String {
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val loginData = resp.body<LoginResponse>()
        return loginData.csrfToken
    }

    @Test
    fun `healthz endpoint returns ok status`() = testApplication {
        val dbPath = Files.createTempFile("legado-routes-health", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-routes-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, initialAdminPassword = "test-password-1234", secureCookies = false
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }

            val resp = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.bodyAsText().contains("ok"))
        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source management endpoints full crud, validate, export and import`() = testApplication {
        val dbPath = Files.createTempFile("legado-routes-sources", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-routes-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, initialAdminPassword = "test-password-1234", secureCookies = false
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            // 1. Unauthenticated request to /api/sources should fail with 401
            val unauthResp = client.get("/api/sources")
            assertEquals(HttpStatusCode.Unauthorized, unauthResp.status)

            val csrf = setupAuthenticatedClient(client, "test-password-1234")

            // 2. Import valid sources
            val sampleSource1 = """{"bookSourceUrl":"https://source1.com","bookSourceName":"测试源1","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"}}"""
            val sampleSource2 = """{"bookSourceUrl":"https://source2.com","bookSourceName":"测试源2","searchUrl":"/search?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"}}"""

            val importResp = client.post("/api/sources/import") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(ImportRequest(listOf(sampleSource1, sampleSource2)))
            }
            assertEquals(HttpStatusCode.OK, importResp.status)
            val importResult = importResp.body<ImportResponse>()
            assertEquals(2, importResult.imported)

            // 3. List sources
            val listResp = client.get("/api/sources")
            assertEquals(HttpStatusCode.OK, listResp.status)
            val sources = listResp.body<List<SourceSummary>>()
            assertEquals(2, sources.size)

            // 4. Filter sources with query parameter
            val queryResp = client.get("/api/sources?q=测试源1")
            assertEquals(HttpStatusCode.OK, queryResp.status)
            val querySources = queryResp.body<List<SourceSummary>>()
            assertEquals(1, querySources.size)
            assertEquals("测试源1", querySources[0].name)

            // 5. Get source by ID
            val getResp = client.get("/api/sources/https%3A%2F%2Fsource1.com")
            assertEquals(HttpStatusCode.OK, getResp.status)
            val sourceRecord = getResp.body<SourceRecord>()
            assertEquals("https://source1.com", sourceRecord.id)

            // Get non-existent source by ID -> 404
            val notFoundResp = client.get("/api/sources/https%3A%2F%2Fnonexistent.com")
            assertEquals(HttpStatusCode.NotFound, notFoundResp.status)

            // 6. Validate source
            val validateResp = client.post("/api/sources/https%3A%2F%2Fsource1.com/validate") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.OK, validateResp.status)
            val validateResult = validateResp.body<ValidateResponse>()
            assertTrue(validateResult.valid)

            // 7. Update source (PUT)
            val updatedJson = """{"bookSourceUrl":"https://source1.com","bookSourceName":"更新后的源1","searchUrl":"/s?k={{key}}","ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"}}"""
            val putResp = client.put("/api/sources/https%3A%2F%2Fsource1.com") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SourceWriteRequest(updatedJson, sourceRecord.version))
            }
            assertEquals(HttpStatusCode.OK, putResp.status)

            // Update with conflicting version -> 409
            val conflictResp = client.put("/api/sources/https%3A%2F%2Fsource1.com") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SourceWriteRequest(updatedJson, 999))
            }
            assertEquals(HttpStatusCode.Conflict, conflictResp.status)

            // 8. Export sources
            val exportResp = client.get("/api/sources/export")
            assertEquals(HttpStatusCode.OK, exportResp.status)
            assertTrue(exportResp.bodyAsText().contains("https://source1.com"))

            val exportSingleResp = client.get("/api/sources/export?id=https%3A%2F%2Fsource1.com")
            assertEquals(HttpStatusCode.OK, exportSingleResp.status)
            assertTrue(exportSingleResp.bodyAsText().contains("更新后的源1"))

            // 9. Delete source
            val deleteResp = client.delete("/api/sources/https%3A%2F%2Fsource2.com") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NoContent, deleteResp.status)

            // Delete already deleted source -> 404
            val deleteAgainResp = client.delete("/api/sources/https%3A%2F%2Fsource2.com") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NotFound, deleteAgainResp.status)
        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bookshelf crud, reading progress, status and source switch routes`() = testApplication {
        val dbPath = Files.createTempFile("legado-routes-shelf", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-routes-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, initialAdminPassword = "test-password-1234", secureCookies = false
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            val csrf = setupAuthenticatedClient(client, "test-password-1234")

            // 1. Initial bookshelf empty
            val initialShelfResp = client.get("/api/bookshelf")
            assertEquals(HttpStatusCode.OK, initialShelfResp.status)
            assertEquals(0, initialShelfResp.body<List<BookshelfItem>>().size)

            // 2. Add book to bookshelf
            val addShelfResp = client.post("/api/bookshelf") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    BookshelfWriteRequest(
                        sourceId = "https://src.com",
                        bookUrl = "https://src.com/book/1",
                        name = "修仙奇传",
                        author = "仙人",
                        tocUrl = "https://src.com/toc/1",
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, addShelfResp.status)
            val addedItem = addShelfResp.body<BookshelfItem>()
            assertEquals("修仙奇传", addedItem.name)

            // 3. Update reading progress
            val saveProgressResp = client.put("/api/reading-progress") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    ReadingProgress(
                        sourceId = "https://src.com",
                        bookUrl = "https://src.com/book/1",
                        chapterUrl = "https://src.com/c/5",
                        chapterIndex = 4,
                        scrollPosition = 0.5,
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, saveProgressResp.status)

            // 4. Get reading progress
            val getProgressResp = client.get("/api/reading-progress?sourceId=https%3A%2F%2Fsrc.com&bookUrl=https%3A%2F%2Fsrc.com%2Fbook%2F1")
            assertEquals(HttpStatusCode.OK, getProgressResp.status)
            val loadedProgress = getProgressResp.body<ReadingProgress>()
            assertEquals(4, loadedProgress.chapterIndex)
            assertEquals(0.5, loadedProgress.scrollPosition, 0.001)

            // Get progress for non-existent book -> 204 NoContent
            val nonExistentProgress = client.get("/api/reading-progress?sourceId=https%3A%2F%2Fsrc.com&bookUrl=https%3A%2F%2Fnone.com")
            assertEquals(HttpStatusCode.NoContent, nonExistentProgress.status)

            // 5. Update completed status
            val setCompletedResp = client.put("/api/bookshelf/status") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(BookshelfStatusRequest("https://src.com", "https://src.com/book/1", completed = true))
            }
            assertEquals(HttpStatusCode.OK, setCompletedResp.status)
            assertTrue(setCompletedResp.body<BookshelfItem>().completed)

            // 6. Switch source
            val switchResp = client.post("/api/bookshelf/switch-source") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    BookshelfSourceSwitchRequest(
                        oldSourceId = "https://src.com",
                        oldBookUrl = "https://src.com/book/1",
                        book = BookshelfWriteRequest(
                            sourceId = "https://newsrc.com",
                            bookUrl = "https://newsrc.com/book/1",
                            name = "修仙奇传",
                            author = "仙人",
                            tocUrl = "https://newsrc.com/toc/1",
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, switchResp.status)
            val switchedItem = switchResp.body<BookshelfItem>()
            assertEquals("https://newsrc.com", switchedItem.sourceId)

            // 7. Bookshelf cache endpoints
            val queueCacheResp = client.post("/api/bookshelf/cache") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(BookRequest("https://newsrc.com", "https://newsrc.com/book/1"))
            }
            assertEquals(HttpStatusCode.Accepted, queueCacheResp.status)

            val cancelCacheResp = client.delete("/api/bookshelf/cache?sourceId=https%3A%2F%2Fnewsrc.com&bookUrl=https%3A%2F%2Fnewsrc.com%2Fbook%2F1") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NoContent, cancelCacheResp.status)

            // 8. Delete book from bookshelf
            val deleteShelfResp = client.delete("/api/bookshelf?sourceId=https%3A%2F%2Fnewsrc.com&bookUrl=https%3A%2F%2Fnewsrc.com%2Fbook%2F1") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NoContent, deleteShelfResp.status)
        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `subscriptions crud and update endpoints`() = testApplication {
        val dbPath = Files.createTempFile("legado-routes-subs", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-routes-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, initialAdminPassword = "test-password-1234", secureCookies = false
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            val csrf = setupAuthenticatedClient(client, "test-password-1234")

            // 1. Initial list empty
            val listResp = client.get("/api/subscriptions")
            assertEquals(HttpStatusCode.OK, listResp.status)
            assertEquals(0, listResp.body<List<SourceSubscription>>().size)

            // 2. Add subscription with invalid URL -> 400
            val invalidAddResp = client.post("/api/subscriptions") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SubscriptionWriteRequest("not-a-valid-url", enabled = true))
            }
            assertEquals(HttpStatusCode.BadRequest, invalidAddResp.status)

            // 3. Add subscription with valid URL
            val validAddResp = client.post("/api/subscriptions") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SubscriptionWriteRequest("https://example.com/sources.json", enabled = true))
            }
            assertEquals(HttpStatusCode.OK, validAddResp.status)
            val sub = validAddResp.body<SourceSubscription>()
            assertEquals("https://example.com/sources.json", sub.url)

            // 4. Delete subscription
            val deleteSubResp = client.delete("/api/subscriptions/${sub.id}") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NoContent, deleteSubResp.status)

            // Delete non-existent subscription -> 404
            val deleteNonExistent = client.delete("/api/subscriptions/99999") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NotFound, deleteNonExistent.status)
        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `search, debug, book details, chapters and content routes`() = testApplication {
        val dbPath = Files.createTempFile("legado-routes-books", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-routes-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, initialAdminPassword = "test-password-1234", secureCookies = false
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
            }

            val csrf = setupAuthenticatedClient(client, "test-password-1234")

            // 1. Search with blank keyword -> 400
            val blankSearchResp = client.post("/api/search") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SearchRequest(keyword = "   "))
            }
            assertEquals(HttpStatusCode.BadRequest, blankSearchResp.status)

            // 2. Import a test source
            val sourceJson = """{
                "bookSourceUrl":"https://testbook.com",
                "bookSourceName":"书籍测试源",
                "searchUrl":"/search?k={{key}}",
                "ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/b/{{$.id}}"},
                "ruleBookInfo":{"name":"$.title","tocUrl":"/toc/1"},
                "ruleToc":{"chapterList":"$.chapters","chapterName":"$.title","chapterUrl":"$.url"},
                "ruleContent":{"content":"$.content"}
            }"""
            client.post("/api/sources/import") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(ImportRequest(listOf(sourceJson)))
            }

            // 3. Debug SSE endpoint
            val debugResp = client.get("/api/sources/https%3A%2F%2Ftestbook.com/debug?keyword=测试")
            assertEquals(HttpStatusCode.OK, debugResp.status)
            assertTrue(debugResp.bodyAsText().contains("event:"))

            // Debug non-existent source -> 404
            val debugNotFound = client.get("/api/sources/https%3A%2F%2Fnone.com/debug")
            assertEquals(HttpStatusCode.NotFound, debugNotFound.status)

            // 4. Books content endpoint validations
            val invalidContentResp = client.post("/api/books/content") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(ContentRequest(sourceId = "", chapterUrl = ""))
            }
            assertEquals(HttpStatusCode.BadRequest, invalidContentResp.status)

            val notFoundContentResp = client.post("/api/books/content") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(ContentRequest(sourceId = "https://nonexistent.com", chapterUrl = "https://nonexistent.com/c1"))
            }
            assertEquals(HttpStatusCode.NotFound, notFoundContentResp.status)

            // 5. Covers endpoint (non-existent key -> 404)
            val coverNotFoundResp = client.get("/api/covers/nonexistent-key-1234")
            assertEquals(HttpStatusCode.NotFound, coverNotFoundResp.status)
        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }
}

