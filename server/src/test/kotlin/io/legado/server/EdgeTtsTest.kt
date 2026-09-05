package io.legado.server

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path

class EdgeTtsTest {

    @Test
    fun `edge tts service lists default voices correctly`() {
        val service = EdgeTtsService()
        val voices = service.listVoices()
        assertTrue(voices.isNotEmpty())
        assertTrue(voices.any { it.id == "zh-CN-XiaoxiaoNeural" })
        assertTrue(voices.any { it.id == "zh-CN-YunxiNeural" })
        assertTrue(voices.any { it.lang == "zh-CN" })
        assertTrue(voices.any { it.lang == "zh-HK" })
        assertTrue(voices.any { it.lang == "en-US" })
    }

    @Test
    fun `edge tts empty text returns empty byte array`() {
        val service = EdgeTtsService()
        val res = service.synthesize("   ")
        assertEquals(0, res.size)
    }

    @Test
    fun `custom mp3 stream forwards response chunks and rejects other content types`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val audio = "fake-mp3-stream".toByteArray(StandardCharsets.UTF_8)
        server.createContext("/mp3") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg; charset=binary")
            exchange.sendResponseHeaders(200, audio.size.toLong())
            exchange.responseBody.use { it.write(audio) }
        }
        server.createContext("/wav") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.sendResponseHeaders(200, audio.size.toLong())
            exchange.responseBody.use { it.write(audio) }
        }
        server.start()
        try {
            val service = EdgeTtsService(HttpClient.newHttpClient())
            val request = TtsSessionChunkRequest(
                chunkId = "chunk-1",
                text = "测试自定义流",
                engine = "custom",
                customUrl = "http://127.0.0.1:${server.address.port}/mp3?text={{speakText}}",
            )
            val received = ByteArrayOutputStream()
            val contentType = service.synthesizeCustomStream(request) { received.write(it) }
            assertEquals("audio/mpeg", contentType)
            assertArrayEquals(audio, received.toByteArray())

            val invalidRequest = request.copy(customUrl = "http://127.0.0.1:${server.address.port}/wav")
            try {
                service.synthesizeCustomStream(invalidRequest) { }
                fail("non-MP3 custom TTS should be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message!!.contains("audio/mpeg"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sec ms gec token generation produces 64-char uppercase hex string`() {
        val token = EdgeTtsService.generateSecMsGec()
        assertEquals(64, token.length)
        assertTrue(token.matches(Regex("^[0-9A-F]{64}$")))
    }

    @Test
    fun `tts routes provide voice list and reject empty speak requests`() = testApplication {
        val dbPath = Files.createTempFile("legado-tts-test", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-tts-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, initialAdminPassword = "admin-tts-pass", secureCookies = false
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(HttpCookies)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }

            // Unauthenticated should fail
            val unauthResp = client.get("/api/tts/voices")
            assertEquals(HttpStatusCode.Unauthorized, unauthResp.status)

            // Authenticate
            val loginResp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("admin-tts-pass"))
            }
            assertEquals(HttpStatusCode.OK, loginResp.status)
            val csrf = loginResp.body<LoginResponse>().csrfToken

            // List voices
            val voicesResp = client.get("/api/tts/voices")
            assertEquals(HttpStatusCode.OK, voicesResp.status)
            val voices = voicesResp.body<List<TtsVoice>>()
            assertTrue(voices.any { it.id == "zh-CN-XiaoxiaoNeural" })

            // Empty text POST speak should return 400
            val emptyResp = client.post("/api/tts/speak") {
                contentType(ContentType.Application.Json)
                setBody(TtsSpeakRequest(text = "   "))
            }
            assertEquals(HttpStatusCode.BadRequest, emptyResp.status)

            // Empty text GET speak should return 400
            val emptyGetResp = client.get("/api/tts/speak?text=")
            assertEquals(HttpStatusCode.BadRequest, emptyGetResp.status)

            val createSessionResp = client.post("/api/tts/session") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, createSessionResp.status)
            val sessionInfo = createSessionResp.body<TtsSessionInfo>()
            assertTrue(sessionInfo.sessionId.isNotBlank())
            assertTrue(sessionInfo.audioUrl.endsWith("/audio"))

            val invalidChunkResp = client.post(sessionInfo.audioUrl.replace("/audio", "/chunks")) {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(TtsSessionChunkRequest(chunkId = "invalid", text = "   "))
            }
            assertEquals(HttpStatusCode.BadRequest, invalidChunkResp.status)

            val stopResp = client.delete("/api/tts/session/${sessionInfo.sessionId}") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.NoContent, stopResp.status)
        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }
}
