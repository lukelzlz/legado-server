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

    @Test
    fun `tryCacheCover falls back to alternate sources when primary cover fails`() {
        val directory = Files.createTempDirectory("legado-cover-cache")
        try {
            val cache = CoverCache(directory) { url ->
                if (url.contains("broken")) throw RuntimeException("404 Not Found")
                "image/jpeg" to byteArrayOf(10, 20, 30)
            }
            val alts = listOf(
                SearchResult("s1", "书名", "作者", "https://s1/book", "https://example.com/broken.jpg"),
                SearchResult("s2", "书名", "作者", "https://s2/book", "https://example.com/valid.jpg")
            )
            val result = tryCacheCover(cache, "https://example.com/primary-broken.jpg", alts)
            assertTrue(result != null)
            assertEquals("image/jpeg", result!!.contentType)
            assertTrue(cache.file(result.key) != null)
            cache.delete(result.key)
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `tryCacheCover returns null when no candidate covers succeed`() {
        val directory = Files.createTempDirectory("legado-cover-cache")
        try {
            val cache = CoverCache(directory) { throw RuntimeException("failed") }
            val alts = listOf(
                SearchResult("s1", "书名", "作者", "https://s1/book", "https://example.com/broken.jpg")
            )
            val result = tryCacheCover(cache, null, alts)
            assertTrue(result == null)
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `getIfCached returns cached cover if file exists and null if not downloaded`() {
        val directory = Files.createTempDirectory("legado-cover-cache")
        try {
            val cache = CoverCache(directory) { "image/png" to byteArrayOf(1, 2, 3) }
            val url = "https://example.com/cover.png"
            org.junit.Assert.assertNull(cache.getIfCached(url))

            val cached = cache.cache(url)
            val check = cache.getIfCached(url)
            org.junit.Assert.assertNotNull(check)
            assertEquals(cached.key, check!!.key)

            // Test by key directly
            val checkByKey = cache.getIfCached(cached.key)
            org.junit.Assert.assertNotNull(checkByKey)
            assertEquals(cached.key, checkByKey!!.key)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tryFindCachedCover returns immediate match if candidate is in cache`() {
        val directory = Files.createTempDirectory("legado-cover-cache")
        try {
            val cache = CoverCache(directory) { "image/jpeg" to byteArrayOf(1, 2) }
            val cached = cache.cache("https://example.com/already-cached.jpg")

            val alts = listOf(
                SearchResult("s1", "书名", "作者", "https://s1/book", "https://example.com/not-cached.jpg"),
                SearchResult("s2", "书名", "作者", "https://s2/book", "https://example.com/already-cached.jpg")
            )
            val found = tryFindCachedCover(cache, "https://example.com/not-cached.jpg", alts)
            org.junit.Assert.assertNotNull(found)
            assertEquals(cached.key, found!!.key)

            val notFound = tryFindCachedCover(cache, "https://example.com/other.jpg", null)
            org.junit.Assert.assertNull(notFound)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

