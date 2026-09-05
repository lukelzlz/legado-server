package io.legado.server

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class TtsSessionService(
    private val edgeTts: EdgeTtsService,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, TtsSession>()
    private val reaper = scope.launch {
        while (isActive) {
            delay(REAPER_INTERVAL_MS)
            val now = System.currentTimeMillis()
            sessions.values.toList().forEach { session ->
                if (session.shouldExpire(now)) session.close("idle_timeout")
            }
        }
    }

    fun create(ownerId: String): TtsSessionInfo {
        val id = UUID.randomUUID().toString()
        val session = TtsSession(id, ownerId, edgeTts, scope) { removed -> sessions.remove(removed.sessionId, removed) }
        sessions[id] = session
        session.start()
        return TtsSessionInfo(id, "/api/tts/session/$id/audio", "/api/tts/session/$id/events")
    }

    fun get(sessionId: String, ownerId: String): TtsSession? =
        sessions[sessionId]?.takeIf { it.ownerId == ownerId && !it.isClosed() }

    fun remove(session: TtsSession) {
        if (sessions.remove(session.sessionId, session)) session.close("removed")
    }

    override fun close() {
        sessions.values.toList().forEach { it.close("application_stopped") }
        sessions.clear()
        reaper.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val REAPER_INTERVAL_MS = 30_000L
    }
}

class TtsSession(
    val sessionId: String,
    val ownerId: String,
    private val edgeTts: EdgeTtsService,
    private val parentScope: CoroutineScope,
    private val onClosed: (TtsSession) -> Unit,
) : AutoCloseable {
    private val createdAt = System.currentTimeMillis()
    private val lastActivityAt = AtomicLong(createdAt)
    private val chunks = Channel<TtsSessionChunkRequest>(48)
    private val audio = Channel<ByteArray>(16)
    private val events = MutableSharedFlow<TtsSessionEvent>(replay = 32, extraBufferCapacity = 64)
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val audioAttached = AtomicBoolean(false)
    private val acceptedIds = LinkedHashSet<String>()
    private val lock = Any()
    private var worker: Job? = null
    private var audioCursorMs = 0L

    fun start() {
        worker = parentScope.launch {
            try {
                for (chunk in chunks) {
                    while (paused.get() && isActive && !closed.get()) delay(100)
                    if (!isActive || closed.get()) break
                    process(chunk)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(error.message ?: "TTS 合成失败")
            }
        }
    }

    fun append(request: TtsSessionChunkRequest): Boolean {
        validate(request)
        if (closed.get()) return false
        touch()
        synchronized(lock) {
            if (acceptedIds.contains(request.chunkId)) return true
            if (acceptedIds.size >= 512) acceptedIds.remove(acceptedIds.first())
            acceptedIds.add(request.chunkId)
        }
        val result = chunks.trySend(request)
        if (result.isFailure) {
            synchronized(lock) { acceptedIds.remove(request.chunkId) }
            return false
        }
        return true
    }

    fun pause() {
        if (closed.get()) return
        touch()
        paused.set(true)
        events.tryEmit(TtsSessionEvent("paused", sessionId))
    }

    fun resume() {
        if (closed.get()) return
        touch()
        paused.set(false)
        events.tryEmit(TtsSessionEvent("resumed", sessionId))
    }

    suspend fun streamAudio(output: ByteWriteChannel) {
        check(audioAttached.compareAndSet(false, true)) { "音频流已连接" }
        touch()
        try {
            for (bytes in audio) {
                touch()
                output.writeFully(bytes)
                output.flush()
            }
        } catch (cancelled: CancellationException) {
            close("audio_disconnected")
            throw cancelled
        } catch (error: Throwable) {
            close("audio_disconnected")
            throw error
        } finally {
            audioAttached.set(false)
        }
    }

    suspend fun streamEvents(writer: java.io.Writer) {
        touch()
            events
            .takeWhile { event ->
                touch()
                val eventName = if (event.type == "error") "tts_error" else event.type
                writer.write("event: $eventName\ndata: ${Json.encodeToString(event)}\n\n")
                writer.flush()
                event.type !in TERMINAL_EVENTS
            }
            .collect()
    }

    fun isClosed(): Boolean = closed.get()

    fun shouldExpire(now: Long): Boolean =
        (!closed.get() && now - createdAt > MAX_LIFETIME_MS) ||
            (!closed.get() && !audioAttached.get() && now - lastActivityAt.get() > IDLE_TIMEOUT_MS)

    override fun close() {
        close("stopped")
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        chunks.close()
        audio.close()
        worker?.cancel()
        events.tryEmit(TtsSessionEvent("stopped", sessionId, message = reason))
        onClosed(this)
    }

    private suspend fun process(chunk: TtsSessionChunkRequest) {
        events.tryEmit(
            TtsSessionEvent(
                type = "chunk_start",
                sessionId = sessionId,
                chunkId = chunk.chunkId,
                chapterIndex = chunk.chapterIndex,
                paragraphIndex = chunk.paragraphIndex,
            )
        )
        try {
            val audioStats = if (chunk.engine == "custom") streamCustom(chunk) else streamEdge(chunk)
            audioCursorMs += audioStats.durationMs
            events.tryEmit(
                TtsSessionEvent(
                    type = "chunk_end",
                    sessionId = sessionId,
                    chunkId = chunk.chunkId,
                    chapterIndex = chunk.chapterIndex,
                    paragraphIndex = chunk.paragraphIndex,
                    audioEndMs = audioCursorMs,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error.message ?: "TTS 合成失败", chunk)
        }
    }

    private suspend fun streamEdge(chunk: TtsSessionChunkRequest): AudioStreamStats {
        return streamBlockingAudio { callback ->
            edgeTts.synthesizeStream(
                text = chunk.text,
                voice = chunk.voice.ifBlank { "zh-CN-XiaoxiaoNeural" },
                rate = chunk.rate,
                pitch = chunk.pitch,
                onAudio = callback,
            )
        }
    }

    private suspend fun streamCustom(chunk: TtsSessionChunkRequest): AudioStreamStats {
        return streamBlockingAudio { callback ->
            edgeTts.synthesizeCustomStream(chunk, onAudio = callback)
        }
    }

    private suspend fun streamBlockingAudio(block: (BlockingAudioCallback) -> Unit): AudioStreamStats {
        val queue = ArrayBlockingQueue<ByteArray>(16)
        val end = ByteArray(0)
        var byteCount = 0L
        val durationEstimator = Mp3DurationEstimator()
        kotlinx.coroutines.coroutineScope {
            val producer = async(Dispatchers.IO) {
                try {
                    runInterruptible { block { bytes -> queue.put(bytes) } }
                } finally {
                    queue.offer(end)
                }
            }
            try {
                while (true) {
                    val bytes = withContext(Dispatchers.IO) { queue.poll(250, TimeUnit.MILLISECONDS) }
                    if (bytes == null) {
                        if (producer.isCompleted && queue.isEmpty()) break
                        continue
                    }
                    if (bytes === end) break
                    byteCount += bytes.size
                    durationEstimator.add(bytes)
                    audio.send(bytes)
                }
                producer.await()
            } finally {
                if (!producer.isCompleted) producer.cancel()
            }
        }
        return AudioStreamStats(byteCount, durationEstimator.durationMs())
    }

    private fun validate(request: TtsSessionChunkRequest) {
        require(request.chunkId.isNotBlank()) { "chunkId 不能为空" }
        require(request.text.trim().isNotEmpty()) { "朗读文本不能为空" }
        require(request.text.length <= 20_000) { "朗读文本过长" }
        require(request.engine == "edge" || request.engine == "custom") { "在线 TTS 引擎不受支持" }
        if (request.engine == "custom") require(!request.customUrl.isNullOrBlank()) { "customUrl 不能为空" }
    }

    private fun touch() {
        lastActivityAt.set(System.currentTimeMillis())
    }

    private fun fail(message: String, chunk: TtsSessionChunkRequest? = null) {
        events.tryEmit(
            TtsSessionEvent(
                type = "error",
                sessionId = sessionId,
                chunkId = chunk?.chunkId,
                chapterIndex = chunk?.chapterIndex ?: -1,
                paragraphIndex = chunk?.paragraphIndex ?: -1,
                message = message,
            )
        )
        close("tts_failed")
    }

    companion object {
        private val TERMINAL_EVENTS = setOf("stopped", "error")
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_LIFETIME_MS = 6 * 60 * 60 * 1000L
    }
}

private typealias BlockingAudioCallback = (ByteArray) -> Unit

private data class AudioStreamStats(
    val byteCount: Long,
    val durationMs: Long,
)

private class Mp3DurationEstimator {
    private var pending = ByteArray(0)
    private var durationMs = 0L

    fun add(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val combined = ByteArray(pending.size + bytes.size)
        pending.copyInto(combined)
        bytes.copyInto(combined, pending.size)
        var offset = 0
        while (offset + 4 <= combined.size) {
            if (combined[offset] == 'I'.code.toByte() &&
                combined[offset + 1] == 'D'.code.toByte() &&
                combined[offset + 2] == '3'.code.toByte() &&
                offset + 10 <= combined.size
            ) {
                val tagSize = synchsafeInt(combined, offset + 6)
                if (offset + 10 + tagSize > combined.size) break
                offset += 10 + tagSize
                continue
            }

            val header = parseHeader(combined, offset)
            if (header == null) {
                offset++
                continue
            }
            if (offset + header.frameLength > combined.size) break
            durationMs += header.samplesPerFrame * 1_000L / header.sampleRate
            offset += header.frameLength
        }
        pending = if (offset == combined.size) ByteArray(0) else combined.copyOfRange(offset, combined.size)
    }

    fun durationMs(): Long = durationMs

    private fun parseHeader(data: ByteArray, offset: Int): Mp3Frame? {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val version = (b1 shr 3) and 0x03
        val layer = (b1 shr 1) and 0x03
        if (version == 1 || layer != 1) return null
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
        val bitrate = if (version == 3) MPEG1_LAYER3_BITRATES[bitrateIndex] else MPEG2_LAYER3_BITRATES[bitrateIndex]
        val sampleRate = when (version) {
            3 -> MPEG1_SAMPLE_RATES[sampleRateIndex]
            2 -> MPEG2_SAMPLE_RATES[sampleRateIndex]
            else -> MPEG25_SAMPLE_RATES[sampleRateIndex]
        }
        val padding = (b2 shr 1) and 0x01
        val frameLength = if (version == 3) 144 * bitrate * 1_000 / sampleRate + padding else 72 * bitrate * 1_000 / sampleRate + padding
        if (frameLength <= 4) return null
        return Mp3Frame(frameLength, if (version == 3) 1_152 else 576, sampleRate)
    }

    private fun synchsafeInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)

    private data class Mp3Frame(val frameLength: Int, val samplesPerFrame: Int, val sampleRate: Int)

    companion object {
        private val MPEG1_LAYER3_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val MPEG2_LAYER3_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 0, 0, 0)
        private val MPEG1_SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)
        private val MPEG2_SAMPLE_RATES = intArrayOf(22_050, 24_000, 16_000)
        private val MPEG25_SAMPLE_RATES = intArrayOf(11_025, 12_000, 8_000)
    }
}
