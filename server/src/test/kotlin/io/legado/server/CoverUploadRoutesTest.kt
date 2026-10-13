package io.legado.server

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CoverUploadRoutesTest {
    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte(),
        0x0d.toByte(), 0x0a.toByte(), 0x1a.toByte(), 0x0a.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0d.toByte(),
        0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
    )
    private val fakeTextBytes = "hello this is plain text not an image".toByteArray()

    @Test
    fun `uploading valid image creates cover and returns key`() {
        val dbPath = Files.createTempFile("legado-cover-upload", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-cover-upload-dir")
        try {
            testApplication {
                val config = ServerConfig(
                    host = "0.0.0.0", port = 8080, databasePath = dbPath,
                    coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
                    initialAdminPassword = "test-password-1234", secureCookies = false,
                )
                application { legadoApplication(config) }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }

                // 1. Login
                val loginResp = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("test-password-1234"))
                }
                assertEquals(HttpStatusCode.OK, loginResp.status)
                val sessionCookie = loginResp.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrfToken = loginResp.bodyAsText().let {
                    Json.decodeFromString<LoginResponse>(it).csrfToken
                }

                // 2. Upload valid image
                val uploadResp = client.post("/api/covers/upload") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrfToken)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("file", pngBytes, Headers.build {
                                    append(HttpHeaders.ContentType, "image/png")
                                    append(HttpHeaders.ContentDisposition, "filename=\"test_cover.png\"")
                                })
                            }
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, uploadResp.status)
                val uploadJson = uploadResp.bodyAsText()
                assertTrue(uploadJson.contains("coverKey"))
                assertTrue(uploadJson.contains("image/png"))

                // Verify file exists on disk
                val key = Json.decodeFromString<Map<String, String>>(uploadJson)["coverKey"]!!
                val coverFile = tempDir.resolve(key)
                assertTrue(Files.exists(coverFile))

                // 3. Update bookshelf info with uploaded coverKey
                val db = Database(dbPath)
                db.saveBookshelf(
                    BookshelfWriteRequest(
                        sourceId = "local_src",
                        bookUrl = "book_1",
                        name = "原始书名",
                        tocUrl = "toc_1",
                    ),
                    null
                )

                val updateResp = client.put("/api/bookshelf/info") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrfToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        BookshelfInfoUpdateRequest(
                            sourceId = "local_src",
                            bookUrl = "book_1",
                            name = "修改后书名",
                            coverKey = key,
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, updateResp.status)
                val updatedShelf = db.getShelfBookByUrl("book_1")
                assertNotNull(updatedShelf)
                assertEquals(key, updatedShelf!!.coverKey)

                // 4. Test cover fetch
                val fetchResp = client.get("/api/covers/$key") {
                    header(HttpHeaders.Cookie, sessionCookie)
                }
                assertEquals(HttpStatusCode.OK, fetchResp.status)
                assertEquals("image/png", fetchResp.contentType()?.withoutParameters()?.toString())
            }
        } finally {
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `uploading non-image file is rejected with 400`() {
        val dbPath = Files.createTempFile("legado-cover-upload-reject", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-cover-upload-reject-dir")
        try {
            testApplication {
                val config = ServerConfig(
                    host = "0.0.0.0", port = 8080, databasePath = dbPath,
                    coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
                    initialAdminPassword = "test-password-1234", secureCookies = false,
                )
                application { legadoApplication(config) }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                }

                val loginResp = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("test-password-1234"))
                }
                val sessionCookie = loginResp.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrfToken = loginResp.bodyAsText().let {
                    Json.decodeFromString<LoginResponse>(it).csrfToken
                }

                val uploadResp = client.post("/api/covers/upload") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrfToken)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("file", fakeTextBytes, Headers.build {
                                    append(HttpHeaders.ContentType, "text/plain")
                                    append(HttpHeaders.ContentDisposition, "filename=\"bad.txt\"")
                                })
                            }
                        )
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, uploadResp.status)
                assertTrue(uploadResp.bodyAsText().contains("invalid_cover"))
            }
        } finally {
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}
