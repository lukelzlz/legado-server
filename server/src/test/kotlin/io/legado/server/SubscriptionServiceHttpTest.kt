package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SubscriptionServiceHttpTest {

    @Test
    fun `subscription service validates and blocks loopback and private ip addresses`() = runBlocking {
        val tempDb = Files.createTempFile("legado-sub-ssrf", ".sqlite").toString()
        try {
            val db = Database(tempDb)
            db.initialize("test-password-1234")
            val service = SubscriptionService(db) {}

            // Save malicious or private network subscriptions
            val sub1 = db.saveSubscription(SubscriptionWriteRequest("http://127.0.0.1:8080/sources.json", enabled = true))
            val sub2 = db.saveSubscription(SubscriptionWriteRequest("http://localhost/sources.json", enabled = true))
            val sub3 = db.saveSubscription(SubscriptionWriteRequest("http://169.254.169.254/latest/meta-data", enabled = true))

            // Attempting to update private / loopback IPs must be blocked by validate()
            assertThrows(Exception::class.java) {
                runBlocking { service.updateOne(sub1.id) }
            }
            assertThrows(Exception::class.java) {
                runBlocking { service.updateOne(sub2.id) }
            }
            assertThrows(Exception::class.java) {
                runBlocking { service.updateOne(sub3.id) }
            }

            // Verify failure was recorded in database
            val loadedSub1 = db.getSubscription(sub1.id)
            assertNotNull(loadedSub1)
            assertNotNull(loadedSub1!!.lastError)
            assertTrue(loadedSub1.lastError!!.contains("拒绝访问") || loadedSub1.lastError!!.contains("内网"))
        } finally {
            Files.deleteIfExists(Path.of(tempDb))
        }
    }

    @Test
    fun `subscription service start and stop lifecycle`() = runBlocking {
        val tempDb = Files.createTempFile("legado-sub-lifecycle", ".sqlite").toString()
        try {
            val db = Database(tempDb)
            db.initialize("test-password-1234")
            val service = SubscriptionService(db) {}

            service.start()
            // Repeated start is a no-op
            service.start()
            service.stop()
        } finally {
            Files.deleteIfExists(Path.of(tempDb))
        }
    }
}
