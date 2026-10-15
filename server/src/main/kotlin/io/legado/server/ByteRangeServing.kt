package io.legado.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * **单段 Range 文件直出**的唯一实现（WebDAV 下载 与 有声书 `GET /api/audio/{key}` 共用）。
 *
 * ## 为什么不各写一份（AGENTS.md 铁律）
 * > `[WebDAV/数据落盘]`：`GET` 用 `respondBytesWriter(contentType, status, contentLength)` 配合
 * > `FileChannel.position(offset)` 手写单段 Range（`206`/`416`）。
 *
 * AGENTS.md 另有一条更硬的：**Range 写错 ⇒ 播放器 seek 卡死 / 416**。
 * 音频播放器会把 `seek` 拆成密集的 Range 请求，任何一处边界算错都表现为"拖着进度条就断"，
 * 而 WebDAV 下载量小、不易暴露 ⇒ 两套实现必然漂移。因此这里把 WebDAV 那份实现**原样提出来共用**，
 * WebDAV 改为调用它（行为逐字不变），音频只额外挂 `ETag` / `Cache-Control`。
 *
 * ## 支持范围（刻意保持最小）
 * 只做**单段** Range：多段（`bytes=0-99,200-299`）返回 `null` ⇒ 按整文件 200 响应。
 * 这是刻意的取舍：多段 Range 需要 `multipart/byteranges` 与额外边界计算，
 * 而 `<audio>` / 下载器 / 阅读器全都只用单段（省一块最容易写错、又最没用的代码）。
 */

/** 目录/网络读缓冲（64 KiB）。 */
internal const val COPY_BUFFER_BYTES = 64 * 1024

internal sealed interface ByteRange {
    data class Satisfiable(val start: Long, val endExclusive: Long) : ByteRange
    data object Unsatisfiable : ByteRange
}

/**
 * 直出文件内容，按需返回 `200` / `206` / `416`。
 *
 * @param etag 非空时写入 `ETag`，并在 `If-None-Match` 命中时直接 `304`（**不读文件**）。
 * @param cacheControl 非空时写入 `Cache-Control`（音频用 `public, max-age=31536000, immutable`）。
 * @return `true` 表示已响应（调用方不得再 respond）。
 */
internal suspend fun ApplicationCall.respondFileRange(
    file: Path,
    contentType: ContentType,
    etag: String? = null,
    cacheControl: String? = null,
): Boolean {
    val size = java.nio.file.Files.size(file)
    response.header(HttpHeaders.AcceptRanges, "bytes")
    etag?.let { response.header(HttpHeaders.ETag, "\"$it\"") }
    cacheControl?.let { response.header(HttpHeaders.CacheControl, it) }
    // `304` 必须在解析 Range **之前**判：命中缓存的内容哈希时连 Range 都不必算，
    // 更不该去碰磁盘（播放器一次 seek 会打一批请求，省下的是实打实的 IO）。
    if (etag != null && request.header(HttpHeaders.IfNoneMatch)?.contains(etag) == true) {
        respond(HttpStatusCode.NotModified)
        return true
    }
    when (val range = parseByteRange(request.header(HttpHeaders.Range), size)) {
        is ByteRange.Unsatisfiable -> {
            // RFC 7233：416 必须回 `Content-Range: bytes */<size>`，否则播放器无法纠正自己的区间
            response.header(HttpHeaders.ContentRange, "bytes */$size")
            respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        is ByteRange.Satisfiable -> {
            response.header(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endExclusive - 1}/$size")
            respondBytesWriter(contentType, HttpStatusCode.PartialContent, range.endExclusive - range.start) {
                writeFileRange(this, file, range.start, range.endExclusive)
            }
        }
        null -> respondBytesWriter(contentType, HttpStatusCode.OK, size) {
            writeFileRange(this, file, 0, size)
        }
    }
    return true
}

/** `FileChannel.position(offset)` + 有界缓冲拷贝：**绝不把整文件读进内存**（音频 60 MiB × 并发 = OOM）。 */
internal suspend fun writeFileRange(output: ByteWriteChannel, file: Path, start: Long, endExclusive: Long) {
    withContext(Dispatchers.IO) {
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
            var remaining = endExclusive - start
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = channel.read(buffer)
                if (read <= 0) break
                buffer.flip()
                val bytes = ByteArray(read)
                buffer.get(bytes)
                output.writeFully(bytes)
                remaining -= read
            }
            output.flush()
        }
    }
}

/** 解析单段 Range：返回 null 表示按整文件响应，`Unsatisfiable` 表示 416。 */
internal fun parseByteRange(header: String?, size: Long): ByteRange? {
    val value = header?.trim()?.removePrefix("bytes=")?.trim() ?: return null
    if (value.isEmpty() || value.contains(',')) return null
    val startText = value.substringBefore('-').trim()
    val endText = value.substringAfter('-', "").trim()
    if (size <= 0) return ByteRange.Unsatisfiable
    if (startText.isEmpty()) {
        val suffixLength = endText.toLongOrNull() ?: return null
        if (suffixLength <= 0) return ByteRange.Unsatisfiable
        return ByteRange.Satisfiable((size - suffixLength).coerceAtLeast(0), size)
    }
    val start = startText.toLongOrNull() ?: return null
    if (start < 0 || start >= size) return ByteRange.Unsatisfiable
    if (endText.isEmpty()) return ByteRange.Satisfiable(start, size)
    val end = endText.toLongOrNull() ?: return null
    if (end < start) return ByteRange.Unsatisfiable
    return ByteRange.Satisfiable(start, minOf(end, size - 1) + 1)
}
