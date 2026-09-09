package io.legado.server

import com.sun.net.httpserver.HttpServer
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readFully
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSessionServiceTest {

    @Test
    fun `session forwards custom mp3 bytes in order and deduplicates chunk ids`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val first = "first-mp3".toByteArray(StandardCharsets.UTF_8)
        val calls = AtomicInteger(0)
        server.createContext("/tts") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, first.size.toLong())
            exchange.responseBody.use { it.write(first) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = ByteChannel(autoFlush = true)
        val session = TtsSession("session-1", "owner-1", EdgeTtsService(HttpClient.newHttpClient()), scope) { }
        val audioJob = scope.launch { session.streamAudio(output) }
        try {
            session.start()
            val request = TtsSessionChunkRequest(
                chunkId = "chunk-1",
                text = "测试内容",
                engine = "custom",
                customUrl = "http://127.0.0.1:${server.address.port}/tts",
            )
            assertTrue(session.append(request))
            assertTrue(session.append(request))
            val preamble = ByteArray(144)
            withTimeout(5_000) { output.readFully(preamble) }
            val received = ByteArray(first.size)
            withTimeout(5_000) { output.readFully(received) }
            assertArrayEquals(first, received)
            assertEquals(1, calls.get())
        } finally {
            session.close("test_done")
            audioJob.cancelAndJoin()
            scope.cancel()
            server.stop(0)
        }
    }

    @Test
    fun `session emits chunk_end with durationMs and audioEndMs`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mp3Bytes = "test-mp3-bytes".toByteArray(StandardCharsets.UTF_8)
        server.createContext("/tts") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, mp3Bytes.size.toLong())
            exchange.responseBody.use { it.write(mp3Bytes) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = ByteChannel(autoFlush = true)
        val session = TtsSession("session-2", "owner-1", EdgeTtsService(HttpClient.newHttpClient()), scope) { }
        val audioJob = scope.launch { session.streamAudio(output) }
        val eventWriter = java.io.StringWriter()
        val eventJob = scope.launch { session.streamEvents(eventWriter) }
        try {
            session.start()
            val request = TtsSessionChunkRequest(
                chunkId = "chunk-test-meta",
                text = "测试内容元数据",
                engine = "custom",
                customUrl = "http://127.0.0.1:${server.address.port}/tts",
            )
            assertTrue(session.append(request))
            val preamble = ByteArray(144)
            withTimeout(5_000) { output.readFully(preamble) }
            val received = ByteArray(mp3Bytes.size)
            withTimeout(5_000) { output.readFully(received) }
            kotlinx.coroutines.delay(200)
            val eventsOutput = eventWriter.toString()
            assertTrue(eventsOutput.contains("\"chunkId\":\"chunk-test-meta\""))
            assertTrue(eventsOutput.contains("\"type\":\"chunk_end\""))
            assertTrue(eventsOutput.contains("\"durationMs\":"))
            assertTrue(eventsOutput.contains("\"audioEndMs\":"))
        } finally {
            session.close("test_done")
            audioJob.cancelAndJoin()
            eventJob.cancelAndJoin()
            scope.cancel()
            server.stop(0)
        }
    }

    @Test
    fun `session supports concurrent or reconnecting audio streams without error`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mp3Bytes = "concurrent-stream-data".toByteArray(StandardCharsets.UTF_8)
        server.createContext("/tts") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, mp3Bytes.size.toLong())
            exchange.responseBody.use { it.write(mp3Bytes) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output1 = ByteChannel(autoFlush = true)
        val output2 = ByteChannel(autoFlush = true)
        val session = TtsSession("session-multi", "owner-1", EdgeTtsService(HttpClient.newHttpClient()), scope) { }
        val audioJob1 = scope.launch { session.streamAudio(output1) }
        val audioJob2 = scope.launch { session.streamAudio(output2) }
        try {
            session.start()
            val request = TtsSessionChunkRequest(
                chunkId = "chunk-multi",
                text = "测试多流并发与断连",
                engine = "custom",
                customUrl = "http://127.0.0.1:${server.address.port}/tts",
            )
            assertTrue(session.append(request))
            val preamble1 = ByteArray(144)
            val preamble2 = ByteArray(144)
            withTimeout(5_000) { output1.readFully(preamble1) }
            withTimeout(5_000) { output2.readFully(preamble2) }
            val received1 = ByteArray(mp3Bytes.size)
            val received2 = ByteArray(mp3Bytes.size)
            withTimeout(5_000) { output1.readFully(received1) }
            withTimeout(5_000) { output2.readFully(received2) }
            assertArrayEquals(mp3Bytes, received1)
            assertArrayEquals(mp3Bytes, received2)
        } finally {
            session.close("test_done")
            audioJob1.cancelAndJoin()
            audioJob2.cancelAndJoin()
            scope.cancel()
            server.stop(0)
        }
    }
}

