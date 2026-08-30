package io.legado.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI

class NetworkSecurityTest {

    @Before
    @After
    fun reset() {
        NetworkSecurity.clearCache()
    }

    @Test
    fun `rejects invalid schemes and blank hosts`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSecurity.resolveAndValidateSafeHttpTarget(URI("ftp://example.com/file"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSecurity.resolveAndValidateSafeHttpTarget(URI("file:///etc/passwd"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSecurity.resolveAndValidateSafeHttpTarget(URI("http:///path"))
        }
    }

    @Test
    fun `blocks SSRF targets including localhost, loopback and private networks`() {
        val blockedUris = listOf(
            "http://127.0.0.1/test",
            "http://localhost:8080/api",
            "http://10.0.0.1/admin",
            "http://192.168.1.1/secret",
            "http://172.16.0.1/status",
            "http://169.254.169.254/latest/meta-data"
        )
        for (uriStr in blockedUris) {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                NetworkSecurity.resolveAndValidateSafeHttpTarget(URI(uriStr))
            }
            assertTrue(ex.message?.contains("拒绝访问内网") == true || ex.message?.contains("无法解析") == true)
        }
    }

    @Test
    fun `caches resolved DNS lookups for subsequent requests`() {
        assertEquals(0, NetworkSecurity.cacheSize())
        val uri = URI("https://example.com/books")
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri)
        assertEquals(1, NetworkSecurity.cacheSize())

        // Second call should hit in-memory cache without errors
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri)
        assertEquals(1, NetworkSecurity.cacheSize())
    }
}
