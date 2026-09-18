package io.legado.server

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val PROPFIND = HttpMethod("PROPFIND")
private val PROPPATCH = HttpMethod("PROPPATCH")
private val MKCOL = HttpMethod("MKCOL")
private val COPY = HttpMethod("COPY")
private val MOVE = HttpMethod("MOVE")
private val LOCK = HttpMethod("LOCK")
private val UNLOCK = HttpMethod("UNLOCK")

private const val WEBDAV_URI_PREFIX = "/webdav"
private const val DAV_CLASSES = "1, 2"
private const val DAV_ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, PROPPATCH, COPY, MOVE, LOCK, UNLOCK"
private const val SUPPORTED_LOCK_XML =
    "<D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>"
private const val DEFAULT_LOCK_SECONDS = 3600L
private const val MAX_LOCK_SECONDS = 86_400L
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val WEBDAV_XML_TYPE = "application/xml"
private const val UPLOAD_TEMP_PREFIX = ".webdav-upload-"
private val WEBDAV_XML = ContentType.parse(WEBDAV_XML_TYPE).withCharset(Charsets.UTF_8)

internal data class WebDavUsage(val files: Int, val directories: Int, val bytes: Long)
/**
 * WebDAV 存储根目录（数据目录下的 `webdav` 文件夹），客户端上传的数据全部原样落盘到该目录。
 * 磁盘路径完全由 URL 路径推导，任何越界路径（`..`、反斜杠、空字节）都会被拒绝。
 */
class WebDavStorage(rootDirectory: Path) {
    val root: Path = rootDirectory.toAbsolutePath().normalize()

    init { Files.createDirectories(root) }

    fun resolve(urlPath: String): Path? {
        val segments = urlPath.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." || it.contains('\\') || it.contains('\u0000') }) return null
        val candidate = segments.fold(root) { parent, segment -> parent.resolve(segment) }.normalize()
        return candidate.takeIf { it.startsWith(root) }
    }

    fun children(directory: Path): List<Path> =
        Files.newDirectoryStream(directory).use { stream -> stream.toList().sortedBy { it.fileName.toString() } }

    /** 目录内容：目录优先，其次按名称排序（设置页面浏览用）。 */
    fun list(directory: Path): List<Path> = children(directory).sortedWith(
        compareByDescending<Path> { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .thenBy { it.fileName.toString().lowercase() },
    )

    /** 统计已存数据（写入中的临时分片不计入）。 */
    internal fun usage(): WebDavUsage {
        var files = 0
        var directories = 0
        var bytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root) directories++
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!file.fileName.toString().startsWith(UPLOAD_TEMP_PREFIX)) {
                    files++
                    bytes += attrs.size()
                }
                return FileVisitResult.CONTINUE
            }
        })
        return WebDavUsage(files, directories, bytes)
    }

    fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun copyRecursively(source: Path, destination: Path) {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination)
            for (child in children(source)) copyRecursively(child, destination.resolve(child.fileName.toString()))
        } else {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}

/** WebDAV 写入锁（Class 2 的最小实现）：只登记与续租，不做强制互斥，避免客户端遗留锁导致后续写入失败。 */
private class DavLock(val token: String, val owner: String?, val expiresAt: Long) {
    fun timeoutSeconds(now: Long = System.currentTimeMillis()): Long = ((expiresAt - now) / 1000).coerceAtLeast(1)
}

private class WebDavLocks {
    private val locks = ConcurrentHashMap<Path, DavLock>()

    fun acquire(path: Path, owner: String?, timeoutSeconds: Long): DavLock {
        prune()
        val lock = DavLock("opaquelocktoken:${UUID.randomUUID()}", owner, System.currentTimeMillis() + timeoutSeconds * 1000)
        locks[path] = lock
        return lock
    }

    fun touch(path: Path, timeoutSeconds: Long): DavLock? {
        prune()
        val existing = locks[path] ?: return null
        val extended = DavLock(existing.token, existing.owner, System.currentTimeMillis() + timeoutSeconds * 1000)
        locks[path] = extended
        return extended
    }

    fun release(path: Path, token: String): Boolean {
        prune()
        val existing = locks[path] ?: return false
        if (existing.token != token) return false
        locks.remove(path)
        return true
    }

    fun active(path: Path): DavLock? {
        prune()
        return locks[path]
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        locks.entries.removeIf { it.value.expiresAt <= now }
    }
}

/**
 * WebDAV 服务端入口：`/webdav` 与 `/webdav/<路径>` 同时映射到数据目录下的 `webdav` 文件夹；
 * `/api/webdav/info` 为 Web 设置页面提供存储状态与目录列表。
 */
fun Route.webDavRoutes(auth: AuthService, storage: WebDavStorage) {
    val locks = WebDavLocks()
    route("/api/webdav") { get("/info") { call.serveWebDavInfo(auth, storage) } }
    route(WEBDAV_URI_PREFIX) { webDavEndpoints(auth, storage, locks) }
    route("$WEBDAV_URI_PREFIX/{path...}") { webDavEndpoints(auth, storage, locks) }
}

private fun Route.webDavEndpoints(auth: AuthService, storage: WebDavStorage, locks: WebDavLocks) {
    method(HttpMethod.Options) { handle { call.serveWebDavOptions() } }
    method(PROPFIND) { handle { call.servePropfind(auth, storage, locks) } }
    method(PROPPATCH) { handle { call.serveProppatch(auth, storage) } }
    method(MKCOL) { handle { call.serveMkcol(auth, storage) } }
    method(COPY) { handle { call.copyOrMove(auth, storage, locks, move = false) } }
    method(MOVE) { handle { call.copyOrMove(auth, storage, locks, move = true) } }
    method(LOCK) { handle { call.serveLock(auth, storage, locks) } }
    method(UNLOCK) { handle { call.serveUnlock(auth, storage, locks) } }
    get { call.serveGetFile(auth, storage) }
    head { call.serveHeadFile(auth, storage) }
    put { call.servePutFile(auth, storage) }
    delete { call.serveDeleteResource(auth, storage) }
}

private suspend fun ApplicationCall.serveWebDavOptions() {
    response.header(HttpHeaders.DAV, DAV_CLASSES)
    response.header(HttpHeaders.Allow, DAV_ALLOW)
    // Windows WebDAV 客户端（WebClient）要求该头才开放写入。
    response.header("MS-Author-Via", "DAV")
    respond(HttpStatusCode.OK)
}

private suspend fun ApplicationCall.servePropfind(auth: AuthService, storage: WebDavStorage, locks: WebDavLocks) {
    if (!requireWebDavAuth(auth)) return
    val urlPath = davPath()
    val target = storage.resolve(urlPath) ?: return respond(HttpStatusCode.Forbidden)
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.NotFound)
    val collection = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
    val depth = request.headers[HttpHeaders.Depth]?.trim()?.lowercase() ?: "infinity"
    // 与 nginx dav 模块一致：只支持 Depth 0/1，拒绝无限深度以免整棵目录树被递归扫描。
    if (collection && depth == "infinity") return respondDavError(HttpStatusCode.Forbidden, "propfind-finite-depth")
    val entries = buildList {
        add(DavEntry(urlPath, target))
        if (collection && depth == "1") {
            storage.children(target).forEach { child ->
                add(DavEntry(if (urlPath.isEmpty()) child.fileName.toString() else "$urlPath/${child.fileName}", child))
            }
        }
    }
    respondText(multistatusXml(entries, locks), WEBDAV_XML, HttpStatusCode.MultiStatus)
}

private suspend fun ApplicationCall.serveProppatch(auth: AuthService, storage: WebDavStorage) {
    if (!requireWebDavAuth(auth, write = true)) return
    val urlPath = davPath()
    val target = storage.resolve(urlPath) ?: return respond(HttpStatusCode.Forbidden)
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.NotFound)
    // 不支持死属性（dead properties），按 RFC 4918 在 207 中回复失败状态。
    respondText(proppatchRefusedXml(urlPath, Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)), WEBDAV_XML, HttpStatusCode.MultiStatus)
}

private suspend fun ApplicationCall.serveMkcol(auth: AuthService, storage: WebDavStorage) {
    if (!requireWebDavAuth(auth, write = true)) return
    val target = storage.resolve(davPath())
    if (target == null || target == storage.root) return respond(HttpStatusCode.Forbidden)
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.MethodNotAllowed)
    if (!Files.isDirectory(target.parent, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.Conflict)
    try {
        Files.createDirectory(target)
    } catch (_: FileAlreadyExistsException) {
        return respond(HttpStatusCode.MethodNotAllowed)
    } catch (_: IOException) {
        return respond(HttpStatusCode.Conflict)
    }
    respond(HttpStatusCode.Created)
}

private suspend fun ApplicationCall.serveGetFile(auth: AuthService, storage: WebDavStorage) {
    if (!requireWebDavAuth(auth)) return
    val urlPath = davPath()
    val target = storage.resolve(urlPath) ?: return respond(HttpStatusCode.Forbidden)
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.NotFound)
    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        respondText(directoryListingHtml(urlPath, target, storage), ContentType.Text.Html.withCharset(Charsets.UTF_8))
        return
    }
    respondFileContent(target)
}

private suspend fun ApplicationCall.serveHeadFile(auth: AuthService, storage: WebDavStorage) {
    if (!requireWebDavAuth(auth)) return
    val target = storage.resolve(davPath()) ?: return respond(HttpStatusCode.Forbidden)
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.NotFound)
    val length = if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) 0L else Files.size(target)
    respond(HeadOnlyContent(length))
}

private suspend fun ApplicationCall.servePutFile(auth: AuthService, storage: WebDavStorage) {
    if (!requireWebDavAuth(auth, write = true)) return
    val target = storage.resolve(davPath())
    if (target == null || target == storage.root) return respond(HttpStatusCode.Forbidden)
    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.MethodNotAllowed)
    val parent = target.parent
    if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
        return respond(HttpStatusCode.Conflict)
    }
    val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    withContext(Dispatchers.IO) {
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, UPLOAD_TEMP_PREFIX, ".part")
        try {
            receiveStream().use { input ->
                Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Throwable) {
            Files.deleteIfExists(temp)
            throw error
        }
    }
    application.log.info("webdav upload stored: {} ({} bytes)", davPath(), runCatching { Files.size(target) }.getOrDefault(0L))
    respond(if (existed) HttpStatusCode.NoContent else HttpStatusCode.Created)
}

private suspend fun ApplicationCall.serveDeleteResource(auth: AuthService, storage: WebDavStorage) {
    if (!requireWebDavAuth(auth, write = true)) return
    val target = storage.resolve(davPath())
    if (target == null || target == storage.root) return respond(HttpStatusCode.Forbidden)
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.NotFound)
    withContext(Dispatchers.IO) { storage.deleteRecursively(target) }
    application.log.info("webdav resource deleted: {}", davPath())
    respond(HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.serveLock(auth: AuthService, storage: WebDavStorage, locks: WebDavLocks) {
    if (!requireWebDavAuth(auth, write = true)) return
    val urlPath = davPath()
    val target = storage.resolve(urlPath) ?: return respond(HttpStatusCode.Forbidden)
    val timeout = lockTimeoutSeconds(request.headers[HttpHeaders.Timeout])
    val body = runCatching { receiveText() }.getOrDefault("")
    // 空 body 的 LOCK 是 RFC 4918 的续租语义，沿用原锁令牌；否则建立新锁。
    val lock = if (body.isBlank()) locks.touch(target, timeout) else null
    val resolved = lock ?: locks.acquire(target, lockOwner(body), timeout)
    var created = false
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        withContext(Dispatchers.IO) {
            target.parent?.let { Files.createDirectories(it) }
            runCatching { Files.createFile(target) }
        }
        created = true
    }
    response.header(HttpHeaders.LockToken, "<${resolved.token}>")
    respondText(
        lockDiscoveryXml(resolved, urlPath, Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)),
        WEBDAV_XML,
        if (created) HttpStatusCode.Created else HttpStatusCode.OK,
    )
}

private suspend fun ApplicationCall.serveUnlock(auth: AuthService, storage: WebDavStorage, locks: WebDavLocks) {
    if (!requireWebDavAuth(auth, write = true)) return
    val target = storage.resolve(davPath())
    val token = request.headers[HttpHeaders.LockToken]?.trim()?.trim('<', '>')
    if (target == null || token.isNullOrBlank() || !locks.release(target, token)) return respond(HttpStatusCode.Conflict)
    respond(HttpStatusCode.NoContent)
}

/** MOVE / COPY：Destination 指向本服务 `/webdav` 前缀之外的地址时按 RFC 4918 返回 502。 */
private suspend fun ApplicationCall.copyOrMove(auth: AuthService, storage: WebDavStorage, locks: WebDavLocks, move: Boolean) {
    if (!requireWebDavAuth(auth, write = true)) return
    val source = storage.resolve(davPath())
    if (source == null || source == storage.root) return respond(HttpStatusCode.Forbidden)
    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.NotFound)
    val destinationHeader = request.headers[HttpHeaders.Destination] ?: return respond(HttpStatusCode.BadRequest)
    val destination = destinationPath(storage, destinationHeader) ?: return respond(HttpStatusCode.BadGateway)
    val overwrite = request.headers[HttpHeaders.Overwrite]?.trim()?.equals("F", ignoreCase = true) != true
    // 源与目标相同、或目标位于源内部（自包含复制会无限递归）一律拒绝。
    if (destination.startsWith(source)) return respond(HttpStatusCode.Forbidden)
    if (!Files.isDirectory(destination.parent, LinkOption.NOFOLLOW_LINKS)) return respond(HttpStatusCode.Conflict)
    val destinationExisted = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
    if (destinationExisted && !overwrite) return respond(HttpStatusCode.PreconditionFailed)
    val depthZero = request.headers[HttpHeaders.Depth]?.trim() == "0"
    withContext(Dispatchers.IO) {
        if (destinationExisted) storage.deleteRecursively(destination)
        if (move) {
            runCatching { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching { Files.move(source, destination) }
                .getOrThrow()
            locks.active(source)?.let { locks.release(source, it.token) }
        } else if (depthZero && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination)
        } else {
            storage.copyRecursively(source, destination)
        }
    }
    application.log.info("webdav {} completed: {} -> {}", if (move) "move" else "copy", davPath(), destinationHeader)
    respond(if (destinationExisted) HttpStatusCode.NoContent else HttpStatusCode.Created)
}

private data class DavEntry(val urlPath: String, val path: Path)

private class HeadOnlyContent(private val length: Long) : OutgoingContent.NoContent() {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
    override val headers: Headers get() = Headers.build { append(HttpHeaders.ContentLength, length.toString()) }
}

internal sealed interface ByteRange {
    data class Satisfiable(val start: Long, val endExclusive: Long) : ByteRange
    data object Unsatisfiable : ByteRange
}

/**
 * 请求对应的 WebDAV 相对路径。
 * 注意：Ktor 的 `{path...}` 尾卡参数只捕获首段（/webdav/a/b 得到 "a"），因此这里从原始请求 URI 推导。
 */
private fun ApplicationCall.davPath(): String = runCatching {
    request.path().removePrefix(WEBDAV_URI_PREFIX).decodeURLPart()
}.getOrDefault("").trim('/')

private suspend fun ApplicationCall.requireWebDavAuth(auth: AuthService, write: Boolean = false): Boolean {
    if (auth.verifyBasicAuthorization(request.headers[HttpHeaders.Authorization])) return true
    val session = sessions.get<UserSession>()
    if (session != null) {
        val csrf = auth.csrf(session)
        if (csrf != null) {
            // 浏览器（Web 设置页）走会话鉴权：写操作必须带 CSRF 头，避免被跨站请求伪造。
            if (!write || request.headers[AuthService.CSRF_HEADER] == csrf) return true
            respond(HttpStatusCode.Forbidden, ApiError("csrf_invalid", "请求验证失败"))
            return false
        }
    }
    response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Legado WebDAV\", charset=\"UTF-8\"")
    respond(HttpStatusCode.Unauthorized, ApiError("unauthenticated", "WebDAV 需要管理员密码认证"))
    return false
}

/** Web 设置页面所需的存储状态与当前目录内容。 */
private suspend fun ApplicationCall.serveWebDavInfo(auth: AuthService, storage: WebDavStorage) {
    if (!requireWebDavAuth(auth)) return
    val relative = request.queryParameters["path"].orEmpty().trim('/')
    val target = storage.resolve(relative)
    if (target == null) return respond(HttpStatusCode.BadRequest, ApiError("invalid_path", "路径无效"))
    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        return respond(HttpStatusCode.NotFound, ApiError("not_found", "目录不存在"))
    }
    val usage = withContext(Dispatchers.IO) { storage.usage() }
    val entries = withContext(Dispatchers.IO) { storage.list(target) }.map { child ->
        val directory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
        WebDavEntry(
            name = child.fileName.toString(),
            path = if (relative.isEmpty()) child.fileName.toString() else "$relative/${child.fileName}",
            directory = directory,
            size = if (directory) 0L else runCatching { Files.size(child) }.getOrDefault(0L),
            modifiedAt = runCatching { Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L),
        )
    }
    respond(
        WebDavInfoResponse(
            url = WEBDAV_URI_PREFIX,
            directory = storage.root.toString(),
            path = relative,
            parent = when {
                relative.isEmpty() -> null
                relative.contains('/') -> relative.substringBeforeLast('/')
                else -> ""
            },
            fileCount = usage.files,
            directoryCount = usage.directories,
            totalBytes = usage.bytes,
            entries = entries,
        )
    )
}

private suspend fun ApplicationCall.respondDavError(status: HttpStatusCode, condition: String) {
    respondText(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:error xmlns:D=\"DAV:\"><D:$condition/></D:error>",
        WEBDAV_XML,
        status,
    )
}

/** GET 文件：支持单段 Range 断点续传，其余情况整文件流式下发。 */
private suspend fun ApplicationCall.respondFileContent(file: Path) {
    val size = Files.size(file)
    val contentType = ContentType.defaultForPath(file)
    response.header(HttpHeaders.AcceptRanges, "bytes")
    when (val range = parseByteRange(request.headers[HttpHeaders.Range], size)) {
        is ByteRange.Unsatisfiable -> {
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
}

private suspend fun writeFileRange(output: ByteWriteChannel, file: Path, start: Long, endExclusive: Long) {
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

/** 解析单段 Range：返回 null 表示按整文件响应，Unsatisfiable 表示 416。 */
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

private fun destinationPath(storage: WebDavStorage, header: String): Path? {
    val rawPath = if (header.startsWith("http://", ignoreCase = true) || header.startsWith("https://", ignoreCase = true)) {
        runCatching { URI(header).rawPath }.getOrNull() ?: return null
    } else {
        header.substringBefore('?')
    }
    if (rawPath != WEBDAV_URI_PREFIX && !rawPath.startsWith("$WEBDAV_URI_PREFIX/")) return null
    val decoded = runCatching { rawPath.decodeURLPart() }.getOrNull() ?: return null
    return storage.resolve(decoded.removePrefix(WEBDAV_URI_PREFIX))
}

private fun lockTimeoutSeconds(header: String?): Long {
    val requested = header?.split(',')
        ?.mapNotNull { part -> part.trim().removePrefix("Second-").toLongOrNull() }
        ?.maxOrNull() ?: DEFAULT_LOCK_SECONDS
    return requested.coerceIn(60L, MAX_LOCK_SECONDS)
}

private fun lockOwner(body: String): String? {
    val owner = Regex("<[A-Za-z0-9]*:?owner[^>]*>(.*?)</[A-Za-z0-9]*:?owner>", RegexOption.DOT_MATCHES_ALL)
        .find(body)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim()
    return owner?.takeIf { it.isNotEmpty() }?.take(512)
}

private fun multistatusXml(entries: List<DavEntry>, locks: WebDavLocks): String {
    val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n")
    for (entry in entries) {
        val collection = Files.isDirectory(entry.path, LinkOption.NOFOLLOW_LINKS)
        val attributes = Files.readAttributes(entry.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        xml.append("  <D:response>\n")
        xml.append("    <D:href>").append(xmlEscape(davHref(entry.urlPath, collection))).append("</D:href>\n")
        xml.append("    <D:propstat>\n      <D:prop>\n")
        xml.append("        <D:displayname>").append(xmlEscape(entry.path.fileName?.toString().orEmpty())).append("</D:displayname>\n")
        xml.append("        <D:resourcetype>").append(if (collection) "<D:collection/>" else "").append("</D:resourcetype>\n")
        xml.append("        <D:getlastmodified>").append(httpDate(attributes.lastModifiedTime().toInstant())).append("</D:getlastmodified>\n")
        xml.append("        <D:creationdate>").append(DateTimeFormatter.ISO_INSTANT.format(attributes.creationTime().toInstant())).append("</D:creationdate>\n")
        if (collection) {
            xml.append("        <D:getcontenttype>httpd/unix-directory</D:getcontenttype>\n")
        } else {
            xml.append("        <D:getcontentlength>").append(attributes.size()).append("</D:getcontentlength>\n")
            xml.append("        <D:getcontenttype>").append(xmlEscape(ContentType.defaultForPath(entry.path).toString())).append("</D:getcontenttype>\n")
        }
        xml.append("        <D:supportedlock>").append(SUPPORTED_LOCK_XML).append("</D:supportedlock>\n")
        xml.append("        <D:lockdiscovery>").append(activeLockXml(locks.active(entry.path))).append("</D:lockdiscovery>\n")
        xml.append("      </D:prop>\n      <D:status>HTTP/1.1 200 OK</D:status>\n    </D:propstat>\n")
        xml.append("  </D:response>\n")
    }
    xml.append("</D:multistatus>")
    return xml.toString()
}

private fun proppatchRefusedXml(urlPath: String, collection: Boolean): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n")
    append("  <D:response>\n    <D:href>").append(xmlEscape(davHref(urlPath, collection))).append("</D:href>\n")
    append("    <D:propstat>\n      <D:prop/>\n      <D:status>HTTP/1.1 403 Forbidden</D:status>\n    </D:propstat>\n  </D:response>\n")
    append("</D:multistatus>")
}

private fun lockDiscoveryXml(lock: DavLock, urlPath: String, collection: Boolean): String =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery>" +
        activeLockXml(lock, davHref(urlPath, collection)) +
        "</D:lockdiscovery></D:prop>"

private fun activeLockXml(lock: DavLock?, lockRootHref: String = ""): String {
    if (lock == null) return ""
    return buildString {
        append("<D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>")
        append("<D:depth>infinity</D:depth>")
        lock.owner?.let { append("<D:owner>").append(xmlEscape(it)).append("</D:owner>") }
        append("<D:timeout>Second-").append(lock.timeoutSeconds()).append("</D:timeout>")
        append("<D:locktoken><D:href>").append(xmlEscape(lock.token)).append("</D:href></D:locktoken>")
        if (lockRootHref.isNotEmpty()) append("<D:lockroot><D:href>").append(xmlEscape(lockRootHref)).append("</D:href></D:lockroot>")
        append("</D:activelock>")
    }
}

private fun davHref(urlPath: String, collection: Boolean): String {
    val encoded = urlPath.split('/').filter { it.isNotEmpty() }.joinToString("/") { it.encodeURLPathPart() }
    val base = if (encoded.isEmpty()) "$WEBDAV_URI_PREFIX/" else "$WEBDAV_URI_PREFIX/$encoded"
    return if (collection && !base.endsWith("/")) "$base/" else base
}

private fun directoryListingHtml(urlPath: String, directory: Path, storage: WebDavStorage): String = buildString {
    append("<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>").append(xmlEscape(davHref(urlPath, true))).append("</title>")
    append("<style>body{margin:0;padding:24px;font-family:system-ui,-apple-system,\"Segoe UI\",sans-serif;background:#0f1115;color:#e6e8eb}")
    append("h1{font-size:15px;font-weight:600;margin:0 0 16px}a{color:#7dd3fc;text-decoration:none}a:hover{text-decoration:underline}")
    append("ul{list-style:none;margin:0;padding:0}li{padding:6px 0;border-bottom:1px solid #232733;font-size:13px;display:flex;justify-content:space-between;gap:16px}")
    append(".size{color:#8b95a5;font-variant-numeric:tabular-nums}</style></head><body>")
    append("<h1>").append(xmlEscape(davHref(urlPath, true))).append("</h1><ul>")
    if (urlPath.isNotEmpty()) append("<li><a href=\"../\">../</a><span class=\"size\"></span></li>")
    for (child in storage.children(directory)) {
        val childPath = if (urlPath.isEmpty()) child.fileName.toString() else "$urlPath/${child.fileName}"
        val isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
        append("<li><a href=\"").append(xmlEscape(davHref(childPath, isDirectory))).append("\">")
        append(xmlEscape(child.fileName.toString())).append(if (isDirectory) "/" else "").append("</a>")
        append("<span class=\"size\">").append(if (isDirectory) "-" else humanSize(Files.size(child))).append("</span></li>")
    }
    append("</ul></body></html>")
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun httpDate(instant: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC))

private fun xmlEscape(value: String): String = buildString(value.length) {
    for (ch in value) when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(ch)
    }
}
