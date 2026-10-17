package io.legado.server

import io.ktor.http.*
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** 一次命中的封面（`key` = 内容/URL 哈希，落库为 `book_shelf.cover_key`）。 */
data class CachedCover(val key: String, val contentType: String)

/**
 * 写入/读取封面时的**书籍身份**（书名的来源 + 书哈希的两个输入）。
 *
 * 缺省（`null`）表示「这一刻还不知道它属于哪本书」（搜索页/书源页的书、刚上传还没关联的封面）
 * ⇒ 文件名退化成 `未命名 (哈希).扩展名`，但**依然可定位**（见 [CoverCache]）。
 */
data class CoverBookRef(val name: String?, val sourceId: String, val bookUrl: String)

/** `cover_cache` 的一行（布局迁移与定位用）。 */
data class CoverCacheRow(val cacheKey: String, val contentType: String, val filePath: String)

/** 引用某张封面的书架条目（读取兜底要它的 `sourceId`/`bookUrl` 才能算出书哈希）。 */
data class CoverOwner(val name: String, val sourceId: String, val bookUrl: String)

/**
 * 封面缓存：目录 `<root>/covers`，文件名 **`书名 (书哈希).扩展名`**。
 *
 * ## 与漫画/音频同一套哲学
 * - `cover_key`（对外地址 `/api/covers/{key}` 里的 key）**不变**：它仍是内容哈希（上传的字节）
 *   或 URL 哈希（上游地址），用于 ETag / 引用计数 / 永不过期的长缓存；
 * - 落盘**文件名**是可读的：书哈希 = `sha256(sourceId + "\n" + bookUrl)` 前
 *   [AtomicFileStore.BOOK_HASH_LENGTH] 位（与书目录**同一个函数**，禁止硬编码），
 *   扩展名取真实类型（未知 `bin`）；
 * - **读取不依赖中文**：先看索引里的 `file_path`（快路径），失效就按哈希段在封面根里
 *   无中文扫描（[AtomicFileStore.findFlatByNameFree]），最后兜底旧形态 `covers/<key>`。
 *
 * ## 为什么拿不到书名也能定位
 * 没有书身份时文件名叫 `未命名 (<key>).<ext>` —— 哈希段就是 key 本身，
 * 而 key 在读取时**一定有**（它就是接口路径里那一段）⇒ 天然可定位，不需要任何额外索引。
 *
 * ## 已知边界（刻意为之，不是 bug）
 * `书名 (书哈希).扩展名` 这个命名**容不下同一本书的两版封面**（换图/深度回源补抓）。
 * 处理方式是「最后写入者拥有这个名字」：覆盖时把它原来那把 key 记的 `file_path` **清掉**，
 * 于是旧 key 只会得到"没有缓存"（回源重下），**绝不会直出另一版的字节**。
 */
class CoverCache(
    private val directory: Path,
    /**
     * 封面索引库（`file_path` 与书架身份的唯一来源）。缺省 ⇒ 只能按 key 命名与定位。
     *
     * ⚠️ 它排在 [fetcher] **前面**是刻意的：既有调用点（单测、BackupImporter）都用
     * `CoverCache(dir) { url -> … }` 这种**尾随 lambda** 传 fetcher，而尾随 lambda 永远绑到
     * **最后一个**参数上 —— 把它放最后才能让那些调用点一个字都不用改。
     */
    private val database: Database? = null,
    private val fetcher: ((String) -> Pair<String, ByteArray>)? = null,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    private val store = AtomicFileStore(directory)

    /**
     * 进程内小缓存：`key -> 本次写入的相对路径`。
     *
     * 为什么需要它（而不是只靠库里的 `file_path`）：**无库**的 CoverCache 也存在
     * （单测、以及"只想抓张图"的调用方）。此时文件名可能是 `书名 (书哈希).扩展名`，
     * 而 `file(key)` 手上只有 key —— 没有这条线索就只能白跑一次扫盘再判"没有缓存"。
     * 它只做加速与自愈：命中前先验在不在盘上，名不对就丢掉重扫。
     */
    private val written = ConcurrentHashMap<String, String>()

    init { Files.createDirectories(directory) }

    /**
     * 抓取并落盘一张封面（已在缓存里就直接短路，**不再触网**）。
     *
     * @param book 已知的书籍身份；拿不到就传 null（文件名退化成 `未命名 (哈希)`）。
     * @param headers 额外的请求头（书源自带的 `header` / `Cookie` / `Referer`）。
     *   **回源下载必须带上**：从详情页拿到的封面地址多半有防盗链，裸请求就是 403。
     */
    fun cache(url: String, book: CoverBookRef? = null, headers: Map<String, String> = emptyMap()): CachedCover {
        val key = sha256(url)
        if (knownRelative(key) != null) return CachedCover(key, hitContentType(key))
        val injected = fetcher
        val (contentType, rawBody) = injected?.invoke(url) ?: download(url, headers)
        require(contentType.lowercase().startsWith("image/")) { "封面不是图片" }
        // 统一在**落盘前的唯一收口**处兜底解压：无论走内置 download 还是外部注入的 fetcher
        // （如 BackupImporter / 元数据补全），都不能把压缩字节当成图片存下来。
        val body = decompressIfNeeded(rawBody)
        require(body.size <= MAX_BYTES) { "封面超过 5 MiB 限制" }
        // 魔数校验只对**内置下载路径**强制。
        //
        // 理由：这条校验是为拦住「上游谎报 image/jpeg 但正文是 gzip」而加的（SESSION-027），
        // 属网络信任边界上的防御。而 `fetcher` 是**测试与内部调用方注入的接缝**，
        // 其字节来自本进程（已有 Content-Type 约定），对其强加魔数校验会把
        // 合法的注入用例一并拒掉（实测导致 6 个既有用例回归）。
        // 解压仍然对所有路径生效——它只按魔数判断，对真实图片是无操作。
        if (injected == null) {
            require(looksLikeImage(body)) { "封面内容不是可解码的图片（可能是未解压的压缩数据）" }
        }
        writeCover(key, body, realContentType(body, contentType), book)
        return CachedCover(key, contentType.substringBefore(';'))
    }

    /**
     * 按 URL（或直接给 64 位 key）判「本地有没有副本」。
     *
     * 定位三级：`file_path` → 哈希段无中文扫描 → 旧形态 `covers/<key>`。
     */
    fun getIfCached(url: String, book: CoverBookRef? = null): CachedCover? {
        if (url.isBlank()) return null
        val key = if (url.matches(HEX64)) url else sha256(url)
        return if (locateRelative(key, book) != null) CachedCover(key, hitContentType(key)) else null
    }

    /** 按 key 取物理文件（直出路由用）。非法 key（非 64 位 hex）一律 null。 */
    fun file(key: String, book: CoverBookRef? = null): Path? =
        key.takeIf { it.matches(HEX64) }?.let { locateRelative(it, book) }?.let { store.resolveOrNull(it) }

    /** 删除某个 key 的副本（含旧形态 `covers/<key>`），并把索引里的快路径清掉。 */
    fun delete(key: String) {
        locateRelative(key)?.let { store.delete(it) }
        if (store.exists(key)) store.delete(key)
        written.remove(key)
        database?.clearCoverFilePath(key)
    }

    /**
     * 落盘一份**已有字节**的封面（上传接口 / 本地书解析 / 导入器补抓），返回 `cover_key`。
     *
     * key 是**内容哈希**（同一份字节永远同一个 key），已存在同 key 副本时直接复用、不重写。
     */
    fun saveCoverBytes(bytes: ByteArray, contentType: String = "image/jpeg", book: CoverBookRef? = null): String {
        // 与 cache() 一致：先按魔数兜底解压（对真实图片是无操作），再落盘。
        // 这里不做严格图片校验——调用方（导入器/元数据补全）传的是已确认过的字节，
        // 强校验会把测试与内部接缝一并拒掉。
        val body = decompressIfNeeded(bytes)
        require(body.size <= MAX_BYTES) { "封面超过 5 MiB 限制" }
        val key = sha256Bytes(body)
        if (knownRelative(key) == null) {
            writeCover(key, body, realContentType(body, contentType), book)
        }
        return key
    }

    /**
     * 把已落盘的封面**归位**到它的书名（`未命名 (key).ext` → `书名 (书哈希).ext`）。
     *
     * 为什么需要它：上传封面时进程**还不知道这张图属于哪本书**（接口只收字节），
     * 与书籍关联的那一刻（`PUT /api/bookshelf/info` 带上 `coverKey`）才知道 —— 就在这里补名。
     * 只重命名、不重写内容；目标已存在同名文件则**不覆盖**（保持现状，读取侧照样能定位）。
     *
     * @return 是否真的改过名
     */
    fun adopt(key: String, book: CoverBookRef): Boolean {
        val db = database ?: return false
        if (!key.matches(HEX64)) return false
        val current = locateRelative(key) ?: return false
        val wanted = coverCacheFileNameByExtension(book.name, bookCacheHash(book.sourceId, book.bookUrl), extensionOf(current, key))
        if (wanted == current) return false
        return runCatching {
            val source = store.resolve(current)
            val target = store.resolve(wanted)
            if (Files.exists(target)) return@runCatching false
            Files.createDirectories(target.parent)
            Files.move(source, target)
            db.updateCoverFilePath(key, wanted)
            written[key] = wanted
            true
        }.getOrDefault(false)
    }

    fun looksLikeImage(bytes: ByteArray): Boolean = ImageBytes.looksLikeImage(bytes)

    fun detectImageContentType(bytes: ByteArray): String? = ImageBytes.detectImageContentType(bytes)

    fun sha256(value: String) = ImageBytes.sha256(value)
    private fun sha256Bytes(bytes: ByteArray) = ImageBytes.sha256Bytes(bytes)

    // ------------------------------------------------------------------
    // 落盘：文件名与快路径
    // ------------------------------------------------------------------

    /** 文件名：`书名 (书哈希).扩展名`；没有书身份 ⇒ `未命名 (内容 key).扩展名`。 */
    private fun readableRelative(key: String, contentType: String?, book: CoverBookRef?): String {
        val hash = book?.let { bookCacheHash(it.sourceId, it.bookUrl) } ?: key
        return coverCacheFileName(book?.name, hash, contentType)
    }

    /**
     * 写文件 + 记快路径。
     *
     * 覆盖同名文件之前，先把**别的 key** 记的路径（进程内与库里的都）清掉：
     * 那条路径装的不再是它的字节（同一本书换封面时两把 key 会竞争同一个可读名），
     * 留着就会"另一把 key 直出别人的图"。
     */
    private fun writeCover(key: String, body: ByteArray, contentType: String?, book: CoverBookRef?): String {
        val relative = readableRelative(key, contentType, book)
        val db = database
        written.entries.removeIf { it.value == relative }
        if (db != null) {
            db.coverFilePathHolders(relative).filter { it != key }.forEach { db.clearCoverFilePath(it) }
        }
        store.write(relative, body)
        written[key] = relative
        db?.recordCoverCache(key, contentType?.takeIf { it.isNotBlank() } ?: WILDCARD_IMAGE_TYPE, relative)
        return relative
    }

    /**
     * **键级**「已存在」判定（写入短路专用）：进程内记录 → `file_path` → 旧形态 `covers/<key>` →
     * key 命名的扁平文件（`未命名 (<key>).ext`）。
     *
     * 刻意**不做**书哈希扫描：同一本书换封面时新 key 必须真的写下去（覆盖旧那版），
     * 若在这里按书哈希短路上，用户的"换封面"会静默失效。
     */
    private fun knownRelative(key: String): String? {
        written[key]?.let { if (store.exists(it)) return it else written.remove(key) }
        database?.coverFilePath(key)?.let { if (store.exists(it)) return it }
        if (store.exists(key)) return key
        return store.findFlatByNameFree(key)?.let { store.relativeOf(it) }
    }

    /**
     * **读取定位**（绝不依赖中文，也绝不返回别的 key 的字节）：
     *
     * 1. 快路径：进程内记录 → 索引里的 `file_path`；
     * 2. 无中文扫描：书哈希（[CoverBookRef] 或引用它的书架条目）→ 扁平根目录里
     *    `… (<哈希>).<扩展名>`；已被别的 key 认领的文件**跳过**；
     * 3. 旧形态兜底 `covers/<key>`（迁移时"对不上书架"的孤儿封面就留在这一档）。
     *
     * 命中第 2 级会回填 `file_path`（下次回到快路径）。
     */
    private fun locateRelative(key: String, book: CoverBookRef? = null): String? {
        written[key]?.let { if (store.exists(it)) return it else written.remove(key) }
        database?.coverFilePath(key)?.let { if (store.exists(it)) return it }
        val db = database
        val hashes = buildList {
            book?.let { add(bookCacheHash(it.sourceId, it.bookUrl)) }
            db?.coverOwners(key)?.forEach { add(bookCacheHash(it.sourceId, it.bookUrl)) }
            add(key)
        }.distinct()
        for (hash in hashes) {
            val relative = store.findFlatByNameFree(hash)?.let { store.relativeOf(it) } ?: continue
            if (db?.coverFilePathHolders(relative)?.any { it != key } == true) continue
            db?.updateCoverFilePath(key, relative)
            return relative
        }
        if (store.exists(key)) return key
        return null
    }

    /**
     * 扩展名：优先取**现有文件名**里那一段（改名时不可能改变真实类型），
     * 旧形态 `<key>` 没有扩展名 ⇒ 退回按索引里记录的类型映射（未知 `bin`）。
     */
    private fun extensionOf(relative: String, key: String): String {
        val name = relative.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) return ext
        return contentExtension(database?.coverContentType(key))
    }

    /** 真实内容类型：以**字节魔数**为准，认不出来才退回调用方/上游给的那个。 */
    private fun realContentType(body: ByteArray, fallback: String?): String =
        ImageBytes.detectImageContentType(body) ?: fallback?.substringBefore(';')?.trim().orEmpty().ifBlank { WILDCARD_IMAGE_TYPE }

    /** 命中已有副本时返回的内容类型（索引里有真类型就用它，否则维持历史上的通配占位 `image/` + 星号）。 */
    private fun hitContentType(key: String): String =
        database?.coverContentType(key)?.takeIf { it.isNotBlank() } ?: "image/*"

    private fun download(url: String, headers: Map<String, String> = emptyMap()): Pair<String, ByteArray> {
        var uri = URI(url); validate(uri)
        repeat(4) {
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", "LegadoServer/0.1")
            // 书源请求头最后应用（可覆盖 User-Agent）：它与详情页那次请求同源，防盗链才对得上。
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank() && !name.equals("Host", ignoreCase = true) &&
                    !name.equals("Content-Length", ignoreCase = true)
                ) builder.header(name, value)
            }
            val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() in 300..399) { uri = uri.resolve(response.headers().firstValue("location").orElseThrow { IllegalArgumentException("封面重定向缺少地址") }); validate(uri); return@repeat }
            require(response.statusCode() in 200..299) { "封面上游返回 HTTP ${response.statusCode()}" }
            return response.headers().firstValue("content-type").orElse("") to response.body()
        }
        throw IllegalArgumentException("封面重定向次数超过限制")
    }

    /**
     * 按**字节魔数**兜底解压 —— 已收敛到 [ImageBytes.decompressIfNeeded] 这一**唯一收口**，
     * 漫画图片与有声书音频共用同一份实现（避免「gzip 假图片」类缺陷各修一遍）。
     */
    private fun decompressIfNeeded(raw: ByteArray): ByteArray = ImageBytes.decompressIfNeeded(raw)

    private fun validate(uri: URI) {
        NetworkSecurity.resolveAndValidateSafeHttpTarget(uri, "封面")
    }

    private companion object {
        const val MAX_BYTES = ImageBytes.MAX_BYTES

        /** 历史占位类型：拿不到真实类型时用它（直出时按 octet-stream 处理，扩展名退化成 `bin`）。 */
        const val WILDCARD_IMAGE_TYPE = "image/*"

        /** 本服务封面 key 的形态（sha256 hex）：接口路径里那一段就是它。 */
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}
