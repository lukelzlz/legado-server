package io.legado.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * 封面「深度回源补抓」契约：`POST /api/bookshelf/refresh-covers` 的 `deep` 模式。
 *
 * 背景（实测）：书架里有些书 `cover_key` 为空，`cover_url` 是番茄（fqnovelpic）的
 * **签名 CDN 链接、已过期** ⇒ 服务器抓不到，客户端回退外链也失效。而 `POST /api/books/details`
 * 能向书源重新取到**全新的、可用的**签名地址（实测有效期约 60 天）。
 * 深度模式就是：先回源取新地址 → 物化成本地副本 → **同时**把新地址写回 `cover_url`。
 *
 * 三个必须锁死的语义：
 * 1. `deep = false` ⇒ 行为与不加该字段时**完全一致**（只用库里已有的 `cover_url` 重试，绝不回源）；
 * 2. `deep = true` 且回源拿到新地址 ⇒ `cover_key` 落库 **且** `cover_url` 被更新成新地址；
 * 3. 回源失败 / 取不到新地址 / 下载失败 ⇒ 只计入 `failed`，**其余书照常处理**，绝不中断整批。
 *
 * 全部用例**不触网**，靠两个既有接缝：
 * - 回源：走聚合源常见的 `data:;base64,…` 书目地址 —— `RuleRunner.details` 直接把它当详情报文，
 *   一个网络请求都不发（真实书源形态，见 `declarativeDetails`）；
 * - 封面下载：`CoverCache` 命中已存在的 key 就直接返回 —— 用例预先往缓存目录放好字节。
 */
class CoverDeepRefreshTest {

    private val password = "test-password-1234"

    private val sourceId = "https://agg.example"

    /**
     * 书源规则**照抄真实聚合源（大灰狼融合VIP5.0）的形态**：
     * `ruleBookInfo.init` 是 `<js>…</js>$.data` 规则链，`name`/`coverUrl` 用 `$.` 路径取值。
     * 真实书源在 init 的 JS 里发请求到聚合服务器；这里的 JS 只做等价的本地变换
     * （把 `data:;base64,…` 载荷解析成同样的 `{data:{…}}` 形状）⇒ 一个请求都不发。
     */
    private val sourceJson =
        """{"bookSourceUrl":"$sourceId","bookSourceName":"聚合源","searchUrl":"/search?k={{key}}",""" +
            """"ruleBookInfo":{"init":"<js>JSON.stringify({data: JSON.parse(result)})</js>$.data",""" +
            """"name":"$.book_name","coverUrl":"$.thumb_url","tocUrl":"$.toc_url"}}"""

    private val oldUrl = "https://p3-reading-sign.fqnovelpic.com/novel-pic/old~tplv-noop.image?x-expires=100&x-signature=old"
    private val freshUrl = "https://p3-reading-sign.fqnovelpic.com/novel-pic/new~tplv-noop.image?x-expires=9999999&x-signature=new"

    /** 聚合源的详情地址：`data:;base64,<载荷>`，RuleRunner 直接把载荷交给规则，不发请求。 */
    private fun detailsUrl(payload: String) = "data:;base64," + Base64.getEncoder().encodeToString(payload.toByteArray())

    /** 与真实详情载荷同形的字段名：`book_name`/`thumb_url`/`toc_url`。 */
    private fun payload(name: String, cover: String?): String =
        if (cover == null) """{"book_name":"$name","toc_url":"https://agg.example/toc"}"""
        else """{"book_name":"$name","thumb_url":"$cover","toc_url":"https://agg.example/toc"}"""

    private fun shelfBook(name: String, cover: String?): BookshelfWriteRequest = BookshelfWriteRequest(
        sourceId = sourceId,
        bookUrl = detailsUrl(payload(name, cover)),
        name = name,
        author = "作者",
        tocUrl = "https://agg.example/toc",
        coverUrl = oldUrl,
    )

    /** 手工拼请求体：bookUrl 是 base64 数据地址，直接内插即可（不含引号/反斜杠）。 */
    private fun refreshBody(deep: Boolean = false, sourceIdFilter: String? = null, bookUrl: String? = null): String {
        val parts = buildList {
            sourceIdFilter?.let { add(""""sourceId":"$it"""") }
            bookUrl?.let { add(""""bookUrl":"$it"""") }
            if (deep) add(""""deep":true""")
        }
        return "{" + parts.joinToString(",") + "}"
    }

    /**
     * 每个用例独立的临时库 + 缓存目录 + 真实 Ktor 应用。
     *
     * 预置书架与书源必须在**启动应用之前**完成（并且关库），清理则一律在 [testApplication]
     * **之外**：块内应用还开着，Windows 上会报「另一个程序正在使用此文件」
     * （本仓库既有失败的真实根因）。
     */
    private class Scenario(val dbPath: String, val coverDir: Path, val preseeded: MutableMap<String, CachedCover> = mutableMapOf()) {
        /** 往缓存目录预放一份「已缓存」的封面：命中即返回，不会再走网络。 */
        fun preseed(url: String): CachedCover =
            preseeded.getOrPut(url) { CoverCache(coverDir) { "image/jpeg" to JPEG }.cache(url) }
    }

    private fun scenario(prefix: String, vararg books: BookshelfWriteRequest): Scenario {
        val dbPath = Files.createTempFile("legado-$prefix", ".sqlite").toString()
        val coverDir = Files.createTempDirectory("legado-$prefix-covers")
        val database = Database(dbPath)
        database.initialize(password)
        database.importSources(listOf(sourceJson))
        books.forEach { database.saveBookshelf(it, null) }
        database.close()
        return Scenario(dbPath, coverDir)
    }

    private fun Scenario.run(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) {
        try {
            testApplication {
                application {
                    legadoApplication(
                        ServerConfig(
                            host = "0.0.0.0", port = 8080, databasePath = dbPath,
                            coverCacheDirectory = coverDir, webDavDirectory = coverDir.resolve("webdav"),
                            initialAdminPassword = password, secureCookies = false,
                        )
                    )
                }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    install(HttpCookies)
                }
                val login = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(password))
                }
                assertEquals(HttpStatusCode.OK, login.status)
                block(client, login.body<LoginResponse>().csrfToken)
            }
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { coverDir.toFile().deleteRecursively() }
        }
    }

    private suspend fun HttpClient.refreshCovers(csrf: String, body: String): CoverRefreshResponse {
        val resp = post("/api/bookshelf/refresh-covers") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body()
    }

    private suspend fun HttpClient.shelfItem(bookUrl: String): BookshelfItem =
        get("/api/bookshelf").body<List<BookshelfItem>>().first { it.bookUrl == bookUrl }

    /**
     * `deep = false`：行为与改动前完全一致 —— 只用库里已有的 `cover_url` 重试。
     *
     * 刻意的对照条件：**新旧两个地址都已缓存**，于是「拿到哪个 key / 外链有没有变」
     * 就能直接证明走的是哪条路径 —— 若误回源，`cover_key` 一定会变成新地址的 key。
     */
    @Test
    fun `deep=false 不回源，只按库里已有的 cover_url 物化`() {
        val book = shelfBook("旧地址书", freshUrl)
        val s = scenario("deep-false", book)
        val cachedOld = s.preseed(oldUrl)
        val cachedFresh = s.preseed(freshUrl)

        s.run { client, csrf ->
            val resp = client.refreshCovers(csrf, refreshBody(bookUrl = book.bookUrl))

            assertEquals(1, resp.total)
            assertEquals(1, resp.refreshed)
            assertEquals(0, resp.failed)
            assertEquals(0, resp.skipped)
            // 没有回源 ⇒ 没有任何「取到新地址」的条数
            assertEquals(0, resp.resourced)

            val item = client.shelfItem(book.bookUrl)
            assertEquals("必须用库里那份旧地址的副本", cachedOld.key, item.coverKey)
            assertTrue("绝不能用回源得到的新地址", cachedFresh.key != item.coverKey)
            assertEquals("deep=false 不许改写外链", oldUrl, item.coverUrl)
        }
    }

    /**
     * `deep = true` 且回源拿到新地址：`cover_key` 落库，**并且** `cover_url` 更新成新地址
     * （这样前端的外链兜底也是活的）。顺带验证 `/api/covers/<key>` 真的能出图。
     */
    @Test
    fun `deep=true 回源取到新地址时落库 cover_key 并更新 cover_url`() {
        val book = shelfBook("签名过期书", freshUrl)
        val s = scenario("deep-true", book)
        val cachedFresh = s.preseed(freshUrl)

        s.run { client, csrf ->
            val resp = client.refreshCovers(csrf, refreshBody(deep = true, bookUrl = book.bookUrl))

            assertEquals(1, resp.total)
            assertEquals(1, resp.refreshed)
            assertEquals(0, resp.failed)
            assertEquals(0, resp.skipped)
            assertEquals("回源取到了一份与库里不同的新地址", 1, resp.resourced)

            val item = client.shelfItem(book.bookUrl)
            assertEquals(cachedFresh.key, item.coverKey)
            assertEquals("64 位 sha256", 64, item.coverKey!!.length)
            assertEquals("外链也要换成新地址", freshUrl, item.coverUrl)

            // 物化后的副本必须真能取出来（200 + JPEG 魔数），并带上永不过期的缓存头
            val cover = client.get("/api/covers/${item.coverKey}")
            assertEquals(HttpStatusCode.OK, cover.status)
            assertEquals(
                "private, max-age=31536000, immutable",
                cover.headers[HttpHeaders.CacheControl],
            )
            val bytes = cover.bodyAsBytes()
            assertEquals("FFD8FFE0 魔数", listOf(0xFF, 0xD8, 0xFF, 0xE0), bytes.take(4).map { it.toInt() and 0xff })
        }
    }

    /**
     * 回源失败（取不到新地址）时：该条计入 `failed`，**其余书照常处理** —— 绝不能中断整批。
     *
     * 同批两条书：能取到封面的那条必须成功，取不到的那条纹丝不动。
     */
    @Test
    fun `deep=true 回源取不到地址时只计入 failed 且不影响其余书`() {
        val good = shelfBook("有封面的书", freshUrl)
        // 详情能取回，但书源这次就是没给封面地址（规则失效/图床下线的常见形态）
        val bad = shelfBook("没有封面的书", null)
        val s = scenario("deep-partial", good, bad)
        val cachedFresh = s.preseed(freshUrl)

        s.run { client, csrf ->
            val resp = client.refreshCovers(csrf, refreshBody(deep = true, sourceIdFilter = sourceId))

            assertEquals(2, resp.total)
            assertEquals(1, resp.refreshed)
            assertEquals(1, resp.failed)
            assertEquals(0, resp.skipped)
            assertEquals(1, resp.resourced)

            val goodItem = client.shelfItem(good.bookUrl)
            assertEquals(cachedFresh.key, goodItem.coverKey)
            assertEquals(freshUrl, goodItem.coverUrl)

            val badItem = client.shelfItem(bad.bookUrl)
            assertNull("取不到新地址就什么都不写", badItem.coverKey)
            assertEquals("失败也不许破坏原有外链", oldUrl, badItem.coverUrl)
        }
    }

    /**
     * 注入式接缝：回源抛异常、详情里没有地址、封面下载失败全都必须**静默**，
     * 不能把异常抛出去 —— 路由层靠它把单本失败降级成 `failed` 计数并继续下一本。
     */
    @Test
    fun `回源异常与下载失败都静默返回 null`() {
        val item = BookshelfItem(
            sourceId = sourceId, bookUrl = "$sourceId/book/1", name = "书",
            tocUrl = "$sourceId/toc", coverUrl = oldUrl, lastReadAt = 0L,
        )

        assertNull("回源抛异常必须被吞掉", resolveFreshCoverUrl(item) { throw RuleExecutionException("上游 502") })
        assertNull(
            "详情里没有封面地址",
            resolveFreshCoverUrl(item) { BookDetails(sourceId, "书", coverUrl = "  ", tocUrl = "toc") },
        )
        assertEquals(
            "正常回源取到地址（首尾空白要裁掉）",
            freshUrl,
            resolveFreshCoverUrl(item) { BookDetails(sourceId, "书", coverUrl = " $freshUrl ", tocUrl = "toc") },
        )

        val dbPath = Files.createTempFile("legado-deep-seam", ".sqlite").toString()
        val coverDir = Files.createTempDirectory("legado-deep-seam-covers")
        try {
            val database = Database(dbPath)
            database.initialize(password)
            database.saveBookshelf(
                BookshelfWriteRequest(sourceId, item.bookUrl, "书", "作者", "$sourceId/toc", oldUrl),
                null,
            )
            // 下载失败（图床 404 / 防盗链）同样必须静默
            val failing = CoverCache(coverDir) { throw IllegalStateException("图床 404") }
            assertNull(materializeCover(database, failing, item, freshUrl))
            val after = database.getShelfBookByUrl(item.bookUrl)!!
            assertNull("下载失败时绝不落库", after.coverKey)
            assertEquals("外链保持原样", oldUrl, after.coverUrl)
            database.close()
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { coverDir.toFile().deleteRecursively() }
        }
    }

    /**
     * 抽出 [fetchBookDetails] 之后，本地书（`loc_book`）的详情形状必须与重构前**逐字一致**：
     * `coverUrl` 仍是本服务自己的 `/api/covers/<key>`（不是外链），不在书架则返回 null。
     */
    @Test
    fun `fetchBookDetails 本地书仍返回本地封面地址`() {
        val dbPath = Files.createTempFile("legado-deep-local", ".sqlite").toString()
        try {
            val database = Database(dbPath)
            database.initialize(password)
            val coverKey = "e".repeat(64)
            database.saveBookshelf(
                BookshelfWriteRequest(LocalBookParser.LOC_BOOK_SOURCE_ID, "local://book-1", "本地书", "作者", "local://book-1/toc"),
                CachedCover(coverKey, "image/jpeg"),
            )
            val runner = RuleRunner(database = database)

            val details = fetchBookDetails(database, runner, LocalBookParser.LOC_BOOK_SOURCE_ID, "local://book-1")!!
            assertEquals(LocalBookParser.LOC_BOOK_SOURCE_ID, details.sourceId)
            assertEquals("本地书", details.name)
            assertEquals("作者", details.author)
            assertEquals("/api/covers/$coverKey", details.coverUrl)
            assertEquals("local://book-1/toc", details.tocUrl)

            assertNull(
                "不在书架时必须返回 null（调用方据此回 404「本地书籍不存在」）",
                fetchBookDetails(database, runner, LocalBookParser.LOC_BOOK_SOURCE_ID, "local://missing"),
            )
            assertNull(
                "书源不存在同样返回 null（调用方据此回 404「书源不存在」）",
                fetchBookDetails(database, runner, "https://no-such-source.example", "https://x/1"),
            )
            database.close()
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    /** `updateBookshelfCover` 的新可选参数：给了就一起写 `cover_url`，不给则**逐字**保持原行为。 */
    @Test
    fun `updateBookshelfCover 只在显式传入 cover_url 时改写外链`() {
        val dbPath = Files.createTempFile("legado-deep-db", ".sqlite").toString()
        try {
            val database = Database(dbPath)
            database.initialize(password)
            val bookUrl = "$sourceId/book/1"
            database.saveBookshelf(BookshelfWriteRequest(sourceId, bookUrl, "书", "作者", "$sourceId/toc", oldUrl), null)

            val key = "c".repeat(64)
            // 不带 coverUrl ⇒ 老调用点的语义：只动 cover_key
            assertTrue(database.updateBookshelfCover(sourceId, bookUrl, key, "image/jpeg"))
            val first = database.getShelfBookByUrl(bookUrl)!!
            assertEquals(key, first.coverKey)
            assertEquals(oldUrl, first.coverUrl)

            // 带 coverUrl ⇒ 两列一起更新
            val newKey = "d".repeat(64)
            assertTrue(database.updateBookshelfCover(sourceId, bookUrl, newKey, "image/png", coverUrl = freshUrl))
            val second = database.getShelfBookByUrl(bookUrl)!!
            assertEquals(newKey, second.coverKey)
            assertEquals(freshUrl, second.coverUrl)
            assertEquals("image/png", database.coverContentType(newKey))
            database.close()
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    private companion object {
        /** 魔数为 JPEG（FF D8 FF E0）的最小样本，足以通过「是图片」的可用性判断。 */
        val JPEG = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        )
    }
}
