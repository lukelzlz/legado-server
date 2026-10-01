package io.legado.server

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HttpTtsService(
    private val database: Database,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val jsSandbox: JsSandbox = JsSandbox(),
) {
    companion object {
        private val PLACEHOLDER_REGEX = Regex("""\{\{([\s\S]*?)\}\}""")
        private const val MAX_AUDIO_BYTES = 30 * 1024 * 1024 // 30 MB
    }

    private data class ParsedUrlOptions(
        val url: String,
        val method: String?,
        val body: String?,
        val headers: Map<String, String>,
    )

    private fun splitUrlOptions(rawUrl: String): ParsedUrlOptions {
        val trimmed = rawUrl.trim()
        val jsonStart = trimmed.indexOf(",{")
        if (jsonStart < 0) {
            return ParsedUrlOptions(url = trimmed, method = null, body = null, headers = emptyMap())
        }
        val targetUrl = trimmed.substring(0, jsonStart).trim()
        val optionsText = trimmed.substring(jsonStart + 1).trim()
        val optionsObject = runCatching {
            Json.parseToJsonElement(optionsText).jsonObject
        }.getOrNull() ?: return ParsedUrlOptions(url = targetUrl, method = null, body = null, headers = emptyMap())

        val method = optionsObject["method"]?.jsonPrimitive?.content?.uppercase()
        val body = optionsObject["body"]?.let {
            if (it is JsonPrimitive) it.content else it.toString()
        }
        val headers = mutableMapOf<String, String>()
        optionsObject["headers"]?.let { hElement ->
            if (hElement is JsonObject) {
                for ((k, v) in hElement) {
                    headers[k] = if (v is JsonPrimitive) v.content else v.toString()
                }
            }
        }
        return ParsedUrlOptions(url = targetUrl, method = method, body = body, headers = headers)
    }

    fun renderTemplate(
        template: String,
        bindings: Map<String, Any?>,
        urlEncodeRawVars: Boolean = false,
    ): String {
        if (!template.contains("{{")) return template
        return PLACEHOLDER_REGEX.replace(template) { match ->
            val expr = match.groupValues[1].trim()
            if (expr.isEmpty()) return@replace ""

            when (expr) {
                "speakText" -> {
                    val raw = bindings["speakText"]?.toString() ?: ""
                    if (urlEncodeRawVars) URLEncoder.encode(raw, StandardCharsets.UTF_8) else raw
                }
                "speakSpeed" -> (bindings["speakSpeed"] ?: 1.0).toString()
                "speakVoice" -> {
                    val raw = bindings["speakVoice"]?.toString() ?: ""
                    if (urlEncodeRawVars) URLEncoder.encode(raw, StandardCharsets.UTF_8) else raw
                }
                "speakPitch" -> (bindings["speakPitch"] ?: 1.0).toString()
                else -> {
                    // Try evaluating through JsSandbox for complex expressions like {{java.encodeURI(...)}} or math
                    val evalResult = runCatching {
                        jsSandbox.eval(expr, bindings)
                    }.getOrNull()
                    evalResult ?: ""
                }
            }
        }
    }

    fun synthesizeById(
        id: Long,
        text: String,
        speed: Double = 1.0,
        voice: String = "",
        pitch: Double = 1.0,
        timeoutSeconds: Long = 15,
    ): Pair<String, ByteArray> {
        val tts = database.getHttpTts(id)
            ?: throw IllegalArgumentException("未找到 ID 为 $id 的自定义 HTTP TTS 配置")
        return synthesize(tts, text, speed, voice, pitch, timeoutSeconds)
    }

    fun synthesize(
        tts: HttpTts,
        text: String,
        speed: Double = 1.0,
        voice: String = "",
        pitch: Double = 1.0,
        timeoutSeconds: Long = 15,
    ): Pair<String, ByteArray> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return Pair("audio/mpeg", ByteArray(0))

        val options = splitUrlOptions(tts.url)
        val bindings = mapOf(
            "speakText" to cleanText,
            "speakSpeed" to speed,
            "speakVoice" to voice,
            "speakPitch" to pitch,
        )

        val resolvedUrl = renderTemplate(options.url, bindings, urlEncodeRawVars = true)
        if (!resolvedUrl.startsWith("http://", ignoreCase = true) &&
            !resolvedUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw IllegalArgumentException("HTTP TTS URL 格式无效: $resolvedUrl")
        }

        val method = options.method ?: if (options.body != null) "POST" else "GET"
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(resolvedUrl))
            .timeout(Duration.ofSeconds(timeoutSeconds))

        // Merge headers from options and tts.header
        val mergedHeaders = mutableMapOf<String, String>()
        options.headers.forEach { (k, v) ->
            mergedHeaders[k] = renderTemplate(v, bindings, urlEncodeRawVars = false)
        }

        tts.header?.takeIf { it.isNotBlank() }?.let { rawHeader ->
            runCatching {
                val headerObj = Json.parseToJsonElement(rawHeader).jsonObject
                for ((k, v) in headerObj) {
                    val rawVal = if (v is JsonPrimitive) v.content else v.toString()
                    mergedHeaders[k] = renderTemplate(rawVal, bindings, urlEncodeRawVars = false)
                }
            }
        }

        // Apply headers
        for ((k, v) in mergedHeaders) {
            if (k.equals("Host", ignoreCase = true) || k.equals("Content-Length", ignoreCase = true)) continue
            reqBuilder.header(k, v)
        }

        if (method == "POST") {
            val rawBody = options.body ?: ""
            val resolvedBody = renderTemplate(rawBody, bindings, urlEncodeRawVars = false)
            if (mergedHeaders.none { it.key.equals("Content-Type", ignoreCase = true) }) {
                val inferredCt = if (resolvedBody.trimStart().startsWith("{")) "application/json; charset=utf-8" else "application/x-www-form-urlencoded; charset=utf-8"
                reqBuilder.header("Content-Type", inferredCt)
            }
            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(resolvedBody, StandardCharsets.UTF_8))
        } else {
            reqBuilder.GET()
        }

        val response = runCatching {
            client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        }.getOrElse { err ->
            throw RuntimeException("请求 HTTP TTS 接口失败: ${err.message}", err)
        }

        val statusCode = response.statusCode()
        val bodyBytes = response.body() ?: ByteArray(0)

        if (statusCode !in 200..299) {
            val errorMsg = String(bodyBytes.take(300).toByteArray(), StandardCharsets.UTF_8)
            throw RuntimeException("HTTP TTS 返回错误状态码 $statusCode: $errorMsg")
        }

        if (bodyBytes.size > MAX_AUDIO_BYTES) {
            throw RuntimeException("HTTP TTS 返回音频超出最大大小限制 (30MB)")
        }

        // Check if response is actually a JSON error despite 200 status
        if (bodyBytes.size < 1024) {
            val preview = String(bodyBytes, StandardCharsets.UTF_8).trim()
            if (preview.startsWith("{") && preview.contains("\"message\"") && !isAudioBytes(bodyBytes)) {
                val errMsg = runCatching {
                    Json.parseToJsonElement(preview).jsonObject["message"]?.jsonPrimitive?.content
                }.getOrNull()
                if (!errMsg.isNullOrBlank()) {
                    throw RuntimeException("HTTP TTS 错误: $errMsg")
                }
            }
        }

        val respContentType = response.headers().firstValue("Content-Type").orElse(null)
        val finalContentType = if (!respContentType.isNullOrBlank() && !respContentType.contains("text/html")) {
            respContentType
        } else if (!tts.contentType.isNullOrBlank()) {
            tts.contentType
        } else {
            detectAudioContentType(bodyBytes)
        }

        return Pair(finalContentType, bodyBytes)
    }

    private fun isAudioBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // MP3 ID3
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) return true
        // MP3 Sync frame 0xFF 0xFB / 0xF3 / 0xF2
        if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) return true
        // WAV RIFF
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) return true
        // OGG OggS
        if (bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()) return true
        return false
    }

    private fun detectAudioContentType(bytes: ByteArray): String {
        if (bytes.size >= 4) {
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
                return "audio/wav"
            }
            if (bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()) {
                return "audio/ogg"
            }
        }
        return "audio/mpeg"
    }

    /**
     * 估算音频时长 (ms)
     */
    fun estimateDurationMs(bytes: ByteArray, text: String): Long {
        if (bytes.isEmpty()) return 0L
        // Try WAV estimation
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            val byteRate = ByteBuffer.wrap(bytes, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (byteRate > 0) {
                val dataSize = bytes.size - 44
                return (dataSize.toLong() * 1000L) / byteRate
            }
        }
        // Fallback: character count approximation (250ms per Chinese char)
        return (text.length.coerceAtLeast(1) * 260L).coerceAtLeast(300L)
    }
}
