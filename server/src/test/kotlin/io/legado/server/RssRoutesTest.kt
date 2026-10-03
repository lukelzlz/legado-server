package io.legado.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/api/rss/` 下的路由契约测试。
 *
 * 覆盖三件事：
 * 1. **鉴权**：读要会话、写要会话 + CSRF（与项目其它写接口同规矩）；
 * 2. **CRUD 与导入**：粘贴 JSON 导入、启用开关、删除（顺带删文章）；
 * 3. **刷新语义**：成功落文章、失败**如实回报 message** 而不是静默 0 条，
 *    以及重复刷新**不把已读文章重新标成未读**（这是 RSS 阅读器最不可接受的回归）。
 *
 * ⚠️ 全程不联网：刷新用 `responseFetcher` 接缝注入响应。
 */
class RssRoutesTest {

    private val sourceJson = """
        {
          "sourceUrl": "https://feed.example.com/rss",
          "sourceName": "示例订阅源",
          "sourceGroup": "legado",
          "ruleArticles": "$.items[*]",
          "ruleTitle": "$.title",
          "ruleLink": "$.url",
          "rulePubDate": "$.date"
        }
    """.trimIndent()

    private val feedBody = """
        {"items":[
          {"title":"文章一","url":"https://feed.example.com/a","date":"2026-10-01"},
          {"title":"文章二","url":"https://feed.example.com/b","date":"2026-10-02"},
          {"title":"文章三","url":"https://feed.example.com/c","date":"2026-10-03"}
        ]}
    """.trimIndent()

    private fun rssApplication(
        dbPath: String,
        tempDir: Path,
        fetch: (String) -> String = { feedBody },
    ): (io.ktor.server.application.Application) -> Unit = { config ->
        config.legadoApplication(
            ServerConfig(
                host = "0.0.0.0",
                port = 8080,
                databasePath = dbPath,
                coverCacheDirectory = tempDir,
                webDavDirectory = tempDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234",
                secureCookies = false,
            ),
            fetch,
        )
    }

    private suspend fun HttpClient.login(): String {
        val resp = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("test-password-1234"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<LoginResponse>().csrfToken
    }

    /**
     * 起一个只服务 RSS 场景的应用，并准备好已装 Cookie/JSON 的客户端。
     *
     * 刻意做成**泛型**（`block` 返回 `T`）而不是 `Unit` 回调：后者在本项目里
     * 会让 `client` 的类型推导退化（Ktor 的 `Application` 恰好也有 `get(String)`），
     * 报出一堆「actual type is 'String', but 'Int' was expected」这类误导性错误。
     */
    private fun <T> withRssApp(
        fetch: (String) -> String = { feedBody },
        block: suspend (HttpClient) -> T,
    ): T {
        var outcome: T? = null
        var failure: Throwable? = null
        testApplication {
            val dbPath = Files.createTempFile("legado-rss-routes", ".sqlite").toString()
            val tempDir = Files.createTempDirectory("legado-rss-routes")
            try {
                application { rssApplication(dbPath, tempDir, fetch)(this) }
                val httpClient = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    install(HttpCookies)
                }
                outcome = block(httpClient)
            } catch (error: Throwable) {
                failure = error
            } finally {
                listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
                runCatching { tempDir.toFile().deleteRecursively() }
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return outcome as T
    }

    @Test
    fun `rss 读写接口都必须鉴权且写操作校验 csrf`() = withRssApp { client ->
        // 未登录：读与写都应 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rss/sources").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/rss/sources") { setBody(sourceJson) }.status,
        )

        val csrf = client.login()

        // 已登录但缺 CSRF：写操作必须 403
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/rss/sources") { setBody(sourceJson) }.status,
        )

        // 带 CSRF：成功
        val created = client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val source = created.body<RssSource>()
        assertEquals("https://feed.example.com/rss", source.sourceUrl)
        assertEquals("示例订阅源", source.sourceName)
        // 规则必须原样落库（下标取值路径不能被改写）
        assertEquals("$.items[*]", source.ruleArticles)
        assertNull("导入不该伪造成功时间", source.lastSuccessAt)
    }

    @Test
    fun `刷新成功落文章且未读计数正确`() = withRssApp { client ->
        val csrf = client.login()
        client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }

        val refreshed = client.post("/api/rss/sources/refresh?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val result = refreshed.body<RssRefreshResponse>()
        assertTrue("刷新不该失败：" + result.message, !result.failed)
        assertEquals(3, result.articles)
        assertEquals("首次刷新 3 条都是新增", 3, result.newArticles)

        val articles = client.get("/api/rss/articles?sourceId=https://feed.example.com/rss").body<List<RssArticle>>()
        assertEquals(3, articles.size)
        // 列表按 id 倒序（最新在前）
        assertEquals("文章三", articles[0].title)
        assertEquals("https://feed.example.com/c", articles[0].link)
        assertEquals(false, articles[0].read)

        // 源的未读/总数统计
        val sources = client.get("/api/rss/sources").body<List<RssSource>>()
        assertEquals(1, sources.size)
        assertEquals(3, sources[0].articleCount)
        assertEquals(3, sources[0].unreadCount)
        assertNotNull("成功刷新应记录时间", sources[0].lastSuccessAt)
        assertNull(sources[0].lastError)

        // 只看未读
        val unread = client.get("/api/rss/articles?sourceId=https://feed.example.com/rss&unreadOnly=true")
            .body<List<RssArticle>>()
        assertEquals(3, unread.size)
    }

    /**
     * **重复刷新不能把已读文章重新标成未读。**
     *
     * 这是 RSS 阅读器最不可接受的回归：用户读完一轮，刷新一次全部又变成未读。
     * `saveRssArticles` 的 upsert 刻意**不更新 read 列**，本用例锁死该行为。
     */
    @Test
    fun `重复刷新必须保留已读标记`() = withRssApp { client ->
        val csrf = client.login()
        client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }
        client.post("/api/rss/sources/refresh?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
        }

        val before = client.get("/api/rss/articles?sourceId=https://feed.example.com/rss").body<List<RssArticle>>()
        val target = before.first { it.title == "文章二" }

        // 标记为已读
        val marked = client.post("/api/rss/articles/${target.id}/read") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(RssArticleReadRequest(true))
        }
        assertEquals(HttpStatusCode.NoContent, marked.status)

        val unreadAfterMark = client.get("/api/rss/articles?sourceId=https://feed.example.com/rss&unreadOnly=true")
            .body<List<RssArticle>>()
        assertEquals(2, unreadAfterMark.size)

        // 再刷新：第二次应全部算「更新」而非新增，且已读标记仍在
        val second = client.post("/api/rss/sources/refresh?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
        }.body<RssRefreshResponse>()
        assertEquals("第二次刷新不该再算新增", 0, second.newArticles)
        assertEquals(3, second.articles)

        val unreadAfterRefresh = client.get("/api/rss/articles?sourceId=https://feed.example.com/rss&unreadOnly=true")
            .body<List<RssArticle>>()
        assertEquals("刷新后已读的那条必须仍是已读", 2, unreadAfterRefresh.size)
        val stillRead = client.get("/api/rss/articles?sourceId=https://feed.example.com/rss").body<List<RssArticle>>()
            .first { it.title == "文章二" }
        assertEquals(true, stillRead.read)
    }

    /**
     * 抓取失败必须**如实回报**，而不是伪装成「0 篇文章」。
     *
     * 用户看到「0 篇」无法区分「源里确实没内容」与「上游挂了」，因此失败要带 message、
     * 落库 last_error，并且**不能**写 last_success_at。
     */
    @Test
    fun `刷新失败必须如实回报错误而不是静默 0 条`() = withRssApp(fetch = { throw RuleExecutionException("上游返回 HTTP 503") }) { client ->
        val csrf = client.login()
        client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }

        val resp = client.post("/api/rss/sources/refresh?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val result = resp.body<RssRefreshResponse>()
        assertEquals(true, result.failed)
        assertTrue("message 应带出上游原因，实际：" + result.message, result.message!!.contains("503"))
        assertEquals(0, result.articles)

        val source = client.get("/api/rss/sources").body<List<RssSource>>().single()
        assertNull("失败不得写成功时间", source.lastSuccessAt)
        assertTrue("失败必须落库 last_error", source.lastError!!.contains("503"))
    }

    /** 源不存在时刷新要明确 404 语义（而不是 500 或静默 0 条）。 */
    @Test
    fun `刷新不存在的源要如实报错`() = withRssApp { client ->
        val csrf = client.login()
        val resp = client.post("/api/rss/sources/refresh?sourceId=https://nope.example.com") {
            header(AuthService.CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val result = resp.body<RssRefreshResponse>()
        assertEquals(true, result.failed)
        assertTrue(result.message!!.contains("不存在"))
    }

    @Test
    fun `批量刷新与全部标为已读`() = withRssApp { client ->
        val csrf = client.login()
        client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }
        // 批量刷新（不传体 ⇒ 刷新所有启用的源）
        val bulk = client.post("/api/rss/refresh") { header(AuthService.CSRF_HEADER, csrf) }
        assertEquals(HttpStatusCode.OK, bulk.status)
        val results = bulk.body<List<RssRefreshResponse>>()
        assertEquals(1, results.size)
        assertEquals(3, results[0].articles)

        // 全部标为已读
        val readAll = client.post("/api/rss/sources/read-all?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.OK, readAll.status)
        assertEquals(3, readAll.body<RssBulkReadResponse>().updated)

        val unread = client.get("/api/rss/articles?sourceId=https://feed.example.com/rss&unreadOnly=true")
            .body<List<RssArticle>>()
        assertTrue("全部已读后未读应为空", unread.isEmpty())
    }

    @Test
    fun `删除源会连带删除它的文章`() = withRssApp { client ->
        val csrf = client.login()
        client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }
        client.post("/api/rss/sources/refresh?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
        }

        val deleted = client.delete("/api/rss/sources?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertTrue(client.get("/api/rss/sources").body<List<RssSource>>().isEmpty())
        assertTrue(
            "源没了，文章也不该残留",
            client.get("/api/rss/articles?sourceId=https://feed.example.com/rss").body<List<RssArticle>>().isEmpty(),
        )
    }

    /** 无效 JSON 必须 400 且带明确错误码，而不是 500。 */
    @Test
    fun `导入非法订阅源返回 400`() = withRssApp { client ->
        val csrf = client.login()
        val resp = client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"没有地址"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("sourceUrl"))
    }

    /** 更新时不允许用请求体改主键（否则等于删一个建一个，旧文章会悬空）。 */
    @Test
    fun `更新时不允许改 sourceUrl 主键`() = withRssApp { client ->
        val csrf = client.login()
        client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }

        val resp = client.put("/api/rss/sources?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson.replace("https://feed.example.com/rss", "https://other.example.com/rss"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("source_url_mismatch"))
    }

    /** 缺 `sourceId` 的文章查询必须 400（否则会顺手返回全库文章）。 */
    @Test
    fun `文章查询缺 sourceId 返回 400`() = withRssApp { client ->
        client.login()
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/rss/articles").status)
    }

    /** 启用开关通过 PUT 生效，且不影响规则。 */
    @Test
    fun `更新订阅源可以关闭启用开关`() = withRssApp { client ->
        val csrf = client.login()
        client.post("/api/rss/sources") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(sourceJson)
        }

        val disabled = sourceJson.replace("\"sourceName\": \"示例订阅源\"", "\"sourceName\": \"已改名\", \"enabled\": false")
        val resp = client.put("/api/rss/sources?sourceId=https://feed.example.com/rss") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(disabled)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val updated = resp.body<RssSource>()
        assertEquals(false, updated.enabled)
        assertEquals("已改名", updated.sourceName)
        assertEquals("改开关不该丢规则", "$.items[*]", updated.ruleArticles)
    }
}
