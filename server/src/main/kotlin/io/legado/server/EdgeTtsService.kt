package io.legado.server

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EdgeTtsService(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val maxCacheSize = 250

    companion object {
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_BASE_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val WIN_EPOCH = 11644473600L
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

        val DEFAULT_VOICES = listOf(
            TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓 (女声·温暖自然·推荐)", "zh-CN", "Female", "中文普通话", "edge", "适合小说叙述与日常对话"),
            TtsVoice("zh-CN-YunxiNeural", "云希 (男声·沉稳磁性·推荐)", "zh-CN", "Male", "中文普通话", "edge", "适合玄幻、都市小说旁白与对话"),
            TtsVoice("zh-CN-YunjianNeural", "云健 (男声·激情影视解说)", "zh-CN", "Male", "中文普通话", "edge", "影视解说、热血玄幻音色"),
            TtsVoice("zh-CN-XiaoyiNeural", "晓伊 (女声·柔和抒情)", "zh-CN", "Female", "中文普通话", "edge", "抒情、言情、文艺小说"),
            TtsVoice("zh-CN-YunyangNeural", "云扬 (男声·专业新闻播报)", "zh-CN", "Male", "中文普通话", "edge", "专业稳重播音员音色"),
            TtsVoice("zh-CN-liaoning-XiaobeiNeural", "晓北 (东北话·风趣女声)", "zh-CN-liaoning", "Female", "东北话", "edge", "风趣幽默的东北口音"),
            TtsVoice("zh-CN-shaanxi-XiaoniNeural", "晓妮 (陕西话·特色女声)", "zh-CN-shaanxi", "Female", "陕西话", "edge", "地道西北陕西口音"),
            TtsVoice("zh-TW-HsiaoChenNeural", "晓臻 (台湾腔·温柔女声)", "zh-TW", "Female", "台湾国语", "edge", "台湾国语自然女声"),
            TtsVoice("zh-TW-YunJheNeural", "云哲 (台湾腔·清澈男声)", "zh-TW", "Male", "台湾国语", "edge", "台湾国语阳光男声"),
            TtsVoice("zh-HK-HiuMaanNeural", "晓曼 (粤语·自然女声)", "zh-HK", "Female", "粤语", "edge", "标准粤语自然女声"),
            TtsVoice("zh-HK-WanLungNeural", "云龙 (粤语·磁性男声)", "zh-HK", "Male", "粤语", "edge", "标准粤语磁性男声"),
            TtsVoice("en-US-JennyNeural", "Jenny (英文·自然女声)", "en-US", "Female", "English (US)", "edge", "Natural American English Female"),
            TtsVoice("en-US-GuyNeural", "Guy (英文·自然男声)", "en-US", "Male", "English (US)", "edge", "Natural American English Male"),
        )

        fun generateSecMsGec(): String {
            val nowSeconds = System.currentTimeMillis() / 1000L
            var ticks = nowSeconds + WIN_EPOCH
            ticks -= (ticks % 300L)
            val fileTimeTicks = ticks * 10000000L
            val strToHash = "$fileTimeTicks$TRUSTED_CLIENT_TOKEN"
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(strToHash.toByteArray(StandardCharsets.US_ASCII))
            return digest.joinToString("") { b -> "%02X".format(b) }
        }

        fun generateMuid(): String {
            val bytes = ByteArray(16)
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes)
            return bytes.joinToString("") { b -> "%02X".format(b) }
        }
    }

    fun listVoices(): List<TtsVoice> = DEFAULT_VOICES

    fun synthesize(
        text: String,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: Int = 0,
        pitch: Int = 0,
        timeoutSeconds: Long = 15,
    ): ByteArray {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return ByteArray(0)

        // Guard: skip synthesis for punctuation-only or trivially short text (e.g. a bare `"` quote)
        // which would cause Edge-TTS to return 0 bytes, leading to ERR_REQUEST_RANGE_NOT_SATISFIABLE.
        val strippedForCheck = cleanText.replace(Regex("[\\s\\p{Punct}\\u2000-\\u206F\\u2018\\u2019\\u201C\\u201D\\u3000-\\u303F\\uFF00-\\uFFEF]"), "")
        if (strippedForCheck.length < 2) return ByteArray(0)

        val cacheKey = "$voice:$rate:$pitch:$cleanText"
        cache[cacheKey]?.let { return it }

        val rateStr = if (rate >= 0) "+$rate%" else "$rate%"
        val pitchStr = if (pitch >= 0) "+${pitch}Hz" else "${pitch}Hz"
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val escapedText = xmlEscape(cleanText)
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                "<voice name='$voice'>" +
                "<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>$escapedText</prosody>" +
                "</voice></speak>"

        val audioBuffer = ByteArrayOutputStream()
        val future = CompletableFuture<ByteArray>()

        val listener = object : WebSocket.Listener {
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                val str = data.toString()
                if (str.contains("Path:turn.end")) {
                    future.complete(audioBuffer.toByteArray())
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Done")
                }
                return super.onText(webSocket, data, last)
            }

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                if (data.remaining() > 2) {
                    val headerLen = data.short.toInt() and 0xFFFF
                    if (data.remaining() >= headerLen) {
                        val headerBytes = ByteArray(headerLen)
                        data.get(headerBytes)
                        val audioBytes = ByteArray(data.remaining())
                        data.get(audioBytes)
                        if (audioBytes.isNotEmpty()) {
                            synchronized(audioBuffer) {
                                audioBuffer.write(audioBytes)
                            }
                        }
                    }
                }
                return super.onBinary(webSocket, data, last)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                if (!future.isDone) {
                    future.completeExceptionally(error)
                }
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                if (!future.isDone) {
                    future.complete(audioBuffer.toByteArray())
                }
                return super.onClose(webSocket, statusCode, reason)
            }
        }

        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val secMsGec = generateSecMsGec()
        val muid = generateMuid()
        val wssUrl = "$WSS_BASE_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION&ConnectionId=$connectionId"

        val ws = client.newWebSocketBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .buildAsync(URI.create(wssUrl), listener)
            .get(timeoutSeconds, TimeUnit.SECONDS)

        try {
            val configMsg = "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

            ws.sendText(configMsg, true)

            val ssmlMsg = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "Path:ssml\r\n\r\n" +
                    ssml

            ws.sendText(ssmlMsg, true)

            val result = future.get(timeoutSeconds, TimeUnit.SECONDS)
            if (result.isNotEmpty()) {
                if (cache.size > maxCacheSize) {
                    cache.clear()
                }
                cache[cacheKey] = result
            }
            return result
        } catch (e: Exception) {
            ws.abort()
            throw e
        }
    }

    fun synthesizeCustom(request: TtsSpeakRequest, timeoutSeconds: Long = 15): Pair<String, ByteArray> {
        val targetUrl = request.customUrl ?: throw IllegalArgumentException("customUrl is required for custom TTS")
        val cleanText = request.text.trim()
        val voice = request.voice
        val speedMultiplier = (1.0 + request.rate / 100.0).coerceIn(0.2, 4.0)

        var resolvedUrl = targetUrl
            .replace("{{speakText}}", URLEncoder.encode(cleanText, StandardCharsets.UTF_8))
            .replace("{{speakVoice}}", URLEncoder.encode(voice, StandardCharsets.UTF_8))
            .replace("{{speakSpeed}}", speedMultiplier.toString())

        val method = request.customMethod?.uppercase() ?: "GET"
        val reqBuilder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(timeoutSeconds))

        request.customHeader?.let { headerRaw ->
            runCatching {
                val json = Json.parseToJsonElement(headerRaw).jsonObject
                for ((k, v) in json) {
                    reqBuilder.header(k, v.jsonPrimitive.content)
                }
            }
        }

        if (method == "POST") {
            val bodyTemplate = request.customBody ?: ""
            val resolvedBody = bodyTemplate
                .replace("{{speakText}}", cleanText)
                .replace("{{speakVoice}}", voice)
                .replace("{{speakSpeed}}", speedMultiplier.toString())
            reqBuilder.uri(URI.create(resolvedUrl))
            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(resolvedBody, StandardCharsets.UTF_8))
        } else {
            reqBuilder.uri(URI.create(resolvedUrl))
            reqBuilder.GET()
        }

        val response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val contentType = response.headers().firstValue("Content-Type").orElse("audio/mpeg")
        return Pair(contentType, response.body())
    }

    private fun xmlEscape(str: String): String {
        return buildString(str.length + 16) {
            for (ch in str) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
    }
}
