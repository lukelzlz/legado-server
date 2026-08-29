package io.legado.server

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
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

class ServerConfigAndStaticTest {

    @Test
    fun `server config from environment handles defaults and custom settings`() {
        // Default environment
        val defaultConfig = ServerConfig.fromEnvironment(emptyMap())
        assertEquals("0.0.0.0", defaultConfig.host)
        assertEquals(8080, defaultConfig.port)
        assertTrue(defaultConfig.databasePath.endsWith("legado.sqlite"))
        assertTrue(defaultConfig.coverCacheDirectory.endsWith("covers"))
        assertNull(defaultConfig.initialAdminPassword)
        assertTrue(defaultConfig.secureCookies)

        // Custom environment
        val customEnv = mapOf(
            "LEGADO_HOST" to "127.0.0.1",
            "LEGADO_PORT" to "9090",
            "LEGADO_DATABASE" to "/tmp/test.db",
            "ADMIN_PASSWORD" to "admin-secret-pass",
            "LEGADO_SECURE_COOKIES" to "false",
        )
        val customConfig = ServerConfig.fromEnvironment(customEnv)
        assertEquals("127.0.0.1", customConfig.host)
        assertEquals(9090, customConfig.port)
        assertEquals("/tmp/test.db", customConfig.databasePath)
        assertEquals("admin-secret-pass", customConfig.initialAdminPassword)
        assertFalse(customConfig.secureCookies)
    }

    @Test
    fun `reset password command validates arguments and changes password`() {
        val tempDb = Files.createTempFile("legado-reset-pass-test", ".sqlite").toString()
        try {
            val db = Database(tempDb)
            db.initialize("old-password-1234")
            assertTrue(db.verifyPassword("old-password-1234"))

            // Invalid arguments count
            assertThrows(IllegalArgumentException::class.java) {
                resetPasswordMain(emptyArray())
            }

            // Too short password (< 12 chars)
            assertThrows(IllegalArgumentException::class.java) {
                resetPasswordMain(arrayOf("short-pass"))
            }

            // Valid password reset via Database method
            db.resetPassword("new-super-secure-password-1234")
            assertFalse(db.verifyPassword("old-password-1234"))
            assertTrue(db.verifyPassword("new-super-secure-password-1234"))
        } finally {
            Files.deleteIfExists(Path.of(tempDb))
        }
    }
}
