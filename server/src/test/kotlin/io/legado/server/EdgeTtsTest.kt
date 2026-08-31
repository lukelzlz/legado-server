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
        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }
}
