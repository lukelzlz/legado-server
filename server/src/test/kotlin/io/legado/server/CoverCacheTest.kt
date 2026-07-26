package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CoverCacheTest {
    @Test
    fun `stores a valid image and returns the disk cache on repeat access`() {
        val directory = Files.createTempDirectory("legado-cover-cache")
        var calls = 0
        val cache = CoverCache(directory) { calls++; "image/png" to byteArrayOf(1, 2, 3) }
        val first = cache.cache("https://example.com/a.png")
        val second = cache.cache("https://example.com/a.png")
        assertEquals(first.key, second.key); assertEquals(1, calls); assertTrue(cache.file(first.key) != null)
        cache.delete(first.key); Files.deleteIfExists(directory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non image response`() {
        val directory = Files.createTempDirectory("legado-cover-cache")
        try { CoverCache(directory) { "text/html" to byteArrayOf(1) }.cache("https://example.com/not-image") } finally { Files.deleteIfExists(directory) }
    }
}
