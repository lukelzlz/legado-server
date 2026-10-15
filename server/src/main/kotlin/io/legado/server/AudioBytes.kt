package io.legado.server

/**
 * 音频字节的**唯一判据收口**：格式魔数探测 + 内容类型白名单 + 失败原因文案。
 *
 * ## 为什么与图片的判据**刻意不同**（这是本文件存在的全部理由）
 * `ImageBytes.looksLikeImage` 是**强魔数校验**：不认识就拒绝。音频不能照抄，因为
 * ① 音频容器/编码极其庞杂（`mp4` 容器里可能是 AAC、也可能是 ALAC），拿"必须是某种魔数"去卡它
 * 会**误杀合法编码**；② 服务端不做转码（Non-Goal），所以判据的目标不是"能不能播"，
 * 而是"**是不是一张 HTML/JSON 错误页**"。
 *
 * 因此这里的策略与 G2 §14 一致：**弱校验（非 HTML/非 JSON）+ 内容类型白名单**，
 * 不认识就**如实报错**（如 FLAC/APE/WAV 浏览器解不了 ⇒ 明确告诉用户是格式问题，而不是静默返回坏字节）。
 *
 * ## 与 `ImageBytes` 的关系（复杂度惩罚）
 * sha256 / gzip 兜底解压这类**与媒体类型无关**的字节原语**直接复用** `ImageBytes`
 * （它就是"二进制媒体字节的唯一收口"，KDoc 里原本就写着与有声书共用），
 * 这里**只新增音频独有的判据**——刻意不复制一份哈希/GZIP 实现，避免两处漂移。
 */
object AudioBytes {

    /**
     * 单个音频分段的体积上限（200 MiB，G2 G3）。
     *
     * 音频是 10~60 MiB/章的大字节，**必须流式落盘**；这个上限是"上游返回了整张专辑/黑洞流"的兜底。
     */
    const val MAX_SEGMENT_BYTES: Long = 200L * 1024 * 1024

    /** 只读文件头，用来判格式与内容类型。 */
    const val HEAD_BYTES: Int = 4096

    /**
     * 允许直出的内容类型白名单（G-6 裁决：MP3 / M4A / AAC / OGG / WEBM）。
     *
     * `video/mp4` 与 `video/webm` **刻意放进白名单**：`audio/mp4` 在真实上游常被标成前者，
     * 而浏览器对同一份字节的 `<audio>` 解码行为完全一致；把它们判成"不支持"会误杀 M4A。
     */
    val WHITELIST: Set<String> = setOf(
        "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg",
        "audio/mp4", "audio/x-m4a", "audio/m4a",
        "audio/aac", "audio/aacp", "audio/x-aac",
        "audio/ogg", "application/ogg", "audio/opus", "audio/vorbis",
        "audio/webm", "video/webm",
        "video/mp4",
    )

    /**
     * **浏览器解不了、且本服务不转码**的格式：识别出来是为了给用户一句**有用的话**，
     * 而不是丢一句"抓取失败"（AGENTS.md：静默/含糊地丢数据比抛错危险）。
     */
    private val UNSUPPORTED: Map<String, String> = mapOf(
        "audio/flac" to "FLAC",
        "audio/x-flac" to "FLAC",
        "audio/wav" to "WAV",
        "audio/x-wav" to "WAV",
        "audio/wave" to "WAV",
        "audio/ape" to "APE",
        "audio/x-ape" to "APE",
        "audio/x-monkeys-audio" to "APE",
        "audio/wavpack" to "WavPack",
        "audio/x-tta" to "TTA",
    )

    fun sha256(value: String): String = ImageBytes.sha256(value)

    /** 与图片同源：按魔数兜底解压（对流式路径不适用，见 `AudioFetcher` 的流式解压）。 */
    fun decompressIfNeeded(raw: ByteArray): ByteArray = ImageBytes.decompressIfNeeded(raw)

    /** `true` 表示这段字节看起来是 gzip（上游谎报/无视 `Accept-Encoding` 时用）。 */
    fun looksGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b

    /**
     * 按**字节魔数**探测音频内容类型；认不出来返回 `null`（**不抛错**——调用方还要看响应头白名单）。
     */
    fun detectAudioContentType(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        fun at(i: Int) = bytes[i].toInt() and 0xff
        fun ascii(from: Int, length: Int): String =
            if (bytes.size >= from + length) String(bytes, from, length, Charsets.US_ASCII) else ""

        // MP3：ID3v2 标签，或裸帧同步字 0xFFEx / 0xFFFx（0xFF 后的 3 个高位必须全 1）
        if (ascii(0, 3) == "ID3") return "audio/mpeg"
        if (at(0) == 0xff && (at(1) and 0xe0) == 0xe0 && (at(1) and 0x06) != 0) return "audio/mpeg"
        // AAC（ADTS）：0xFFF1 / 0xFFF9
        if (at(0) == 0xff && (at(1) == 0xf1 || at(1) == 0xf9)) return "audio/aac"
        // OGG 容器（Vorbis / Opus 都在里面）
        if (ascii(0, 4) == "OggS") return "audio/ogg"
        // EBML（WebM / Matroska）
        if (at(0) == 0x1a && at(1) == 0x45 && at(2) == 0xdf && at(3) == 0xa3) return "audio/webm"
        // ISO-BMFF / MP4 家族：第 4..7 字节是 `ftyp`，主 brand 在第 8..11 字节
        if (ascii(4, 4) == "ftyp") {
            return when (ascii(8, 4).trim()) {
                "M4A", "M4B", "mp42", "mp41", "isom", "iso2", "iso4", "iso5", "iso6", "dash", "cmfc", "qt" -> "audio/mp4"
                // 认得出是 MP4 容器但 brand 不认识：仍按 audio/mp4 处理（容器能播就够）
                else -> "audio/mp4"
            }
        }
        // 不认识但确实是"解不了"的格式（如实报错用）
        if (ascii(0, 4) == "fLaC") return "audio/flac"
        if (ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE") return "audio/wav"
        if (ascii(0, 4) == "MAC ") return "audio/ape"
        if (ascii(0, 4) == "wvpk") return "audio/wavpack"
        return null
    }

    /** 响应头里的内容类型归一化（小写、去掉 `; charset=` 之类的参数）。 */
    fun normalizeContentType(declared: String?): String =
        declared?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    /**
     * 最终内容类型：**魔数优先，其次响应头**（上游会谎报，同 SESSION-027 的教训）。
     * 都认不出来时给 `application/octet-stream`（调用方会因不在白名单而拒绝）。
     */
    fun resolveContentType(head: ByteArray, declared: String?): String =
        detectAudioContentType(head) ?: normalizeContentType(declared).ifBlank { "application/octet-stream" }

    fun isWhitelisted(contentType: String): Boolean = normalizeContentType(contentType) in WHITELIST

    /** 看起来像 HTML / JSON 错误页（弱校验的主要目标）。 */
    fun looksLikeTextErrorPage(bytes: ByteArray): Boolean {
        val head = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart()
        if (head.isEmpty()) return false
        return head.startsWith("<") || head.startsWith("{") || head.startsWith("[") ||
            head.startsWith("<?xml")
    }

    /**
     * 拒绝原因（**必须能指导下一步动作**）。
     *
     * @param injected 是否来自进程内注入接缝：注入方是可信生产者，此时不套网络侧白名单
     *   （与 `ImageBytes` 的既有裁决同源：进程内接缝不该承受网络侧强校验）。
     */
    fun rejectionReason(head: ByteArray, declared: String?): String {
        val resolved = normalizeContentType(resolveContentType(head, declared))
        UNSUPPORTED[resolved]?.let {
            return "该书源的音频是 $it 格式，不在直出白名单（MP3/M4A/AAC/OGG/WEBM）内，" +
                "而本服务不做转码（Non-Goal）⇒ 无法播放。请换一个提供该格式音频的书源。"
        }
        if (looksLikeTextErrorPage(head)) return "上游返回的是网页/JSON 而不是音频（多为需要登录或防盗链）"
        return "上游返回的内容不是可播放的音频（内容类型：${resolved.ifBlank { "未知" }}）"
    }
}
