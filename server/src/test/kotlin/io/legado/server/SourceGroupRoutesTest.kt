package io.legado.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 书源分组的路由契约：列表 / 改名 / 删除、**两条导入路径的分组语义**，以及
 * **按分组搜书**真的只把该分组的书源交给搜索。
 *
 * 「按分组搜书」的断言用 WebSocket 的第一帧 `start`（其 `totalSources` 就是本次搜索实际选中的书源数）：
 * 这些书源指向不存在的域名，搜索本身不会返回结果，但**选中了哪些书源**是确定的、可离线断言的。
 */
class SourceGroupRoutesTest {

    @Test
    fun `source group routes, import semantics and group scoped search`() = testApplication {
        val dbPath = Files.createTempFile("legado-source-group-routes", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-source-group-routes")
        try {
            val config = ServerConfig(
                host = "0.0.0.0", port = 8080, databasePath = dbPath,
                coverCacheDirectory = tempDir, webDavDirectory = tempDir.resolve("webdav"),
                initialAdminPassword = "test-password-1234", secureCookies = false,
            )
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpCookies)
                install(WebSockets)
            }

            val csrf = login(client, "test-password-1234")
            val bookSources = listOf(
                sourceJson("https://a1.example", "A1", "组A"),
                sourceJson("https://a2.example", "A2", "组A"),
                sourceJson("https://b1.example", "B1", "组B"),
                sourceJson("https://n1.example", "N1", null),
            )

            // ---- 导入书源文件**不自动分组**（用户明确要求）----
            val imported = importSources(client, csrf, bookSources)
            assertEquals(4, imported.imported)
            assertEquals("导入书源文件不采用书源自带分组", 0, imported.sourceGroups)
            assertEquals("因此此时一个分组都没有", emptyList<SourceGroupSummary>(), client.get("/api/source-groups").body<List<SourceGroupSummary>>())

            // ---- 分组只能由用户显式整理（= 「分组管理」面板走的就是这个批量接口）----
            assertEquals(2, batchSetGroup(client, csrf, listOf("https://a1.example", "https://a2.example"), "组A"))
            assertEquals(1, batchSetGroup(client, csrf, listOf("https://b1.example"), "组B"))

            val groups = client.get("/api/source-groups").body<List<SourceGroupSummary>>()
            assertEquals(listOf("组A", "组B"), groups.map { it.name })
            assertEquals(listOf(2, 1), groups.map { it.sourceCount })
            assertEquals(listOf(2, 1), groups.map { it.enabledCount })

            // 再导入一次同一个书源文件：**不得把用户手工分好的组冲掉**
            importSources(client, csrf, bookSources)
            assertEquals(
                "普通导入只写新行、不覆盖已有 source_group",
                listOf("组A", "组B"),
                client.get("/api/source-groups").body<List<SourceGroupSummary>>().map { it.name },
            )

            // ---- 按分组搜书：范围真的收窄了 ----
            assertEquals("选组A → 只搜组A的 2 个源", 2, searchScope(client, csrf, group = "组A"))
            assertEquals("选组B → 只搜组B的 1 个源", 1, searchScope(client, csrf, group = "组B"))
            assertEquals("选未分组 → 只搜没分组的源", 1, searchScope(client, csrf, group = SourceGroupFilter.UNGROUPED))
            assertEquals("不选范围 → 全部已启用书源", 4, searchScope(client, csrf, group = null))

            // ---- 改名（组B 并入组A）----
            val renamed = client.put("/api/source-groups/rename") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SourceGroupRenameRequest(from = "组B", to = "组A"))
            }
            assertEquals(HttpStatusCode.OK, renamed.status)
            assertEquals(1, renamed.body<SourceGroupMutationResponse>().affected)
            val afterRename = client.get("/api/source-groups").body<List<SourceGroupSummary>>()
            assertEquals(listOf("组A"), afterRename.map { it.name })
            assertEquals(3, afterRename.single().sourceCount)
            assertEquals("并入后按组A搜索应当能搜到 3 个源", 3, searchScope(client, csrf, group = "组A"))

            // 改名不存在的分组 → 404（不能静默成功）
            val missing = client.put("/api/source-groups/rename") {
                header(AuthService.CSRF_HEADER, csrf)
                contentType(ContentType.Application.Json)
                setBody(SourceGroupRenameRequest(from = "不存在", to = "组X"))
            }
            assertEquals(HttpStatusCode.NotFound, missing.status)

            // ---- 删除分组：只解绑，不删书源 ----
            val cleared = client.delete("/api/source-groups?name=${URLEncoder.encode("组A", "UTF-8")}") {
                header(AuthService.CSRF_HEADER, csrf)
            }
            assertEquals(HttpStatusCode.OK, cleared.status)
            assertEquals(3, cleared.body<SourceGroupMutationResponse>().affected)
            assertEquals("分组删干净了", emptyList<SourceGroupSummary>(), client.get("/api/source-groups").body<List<SourceGroupSummary>>())
            assertEquals("书源一个都不能少", 4, client.get("/api/sources").body<List<SourceSummary>>().size)
            assertEquals("被删组的书源退化为未分组，仍可搜到", 4, searchScope(client, csrf, group = SourceGroupFilter.UNGROUPED))

            // 未登录不得读分组列表（与其它管理接口一致）
            val anonymous = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/source-groups").status)
        } finally {
            // Windows 上 SQLite 句柄释放滞后，删除数据库文件会抛 FileSystemException
            // （本仓库 54 个既有失败即由此而来，见 SESSION-HIST-008）。
            // 那是环境噪声、不是本用例的断言对象，因此 teardown 只做尽力清理。
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    private suspend fun login(client: HttpClient, password: String): String {
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<LoginResponse>().csrfToken
    }

    private suspend fun importSources(client: HttpClient, csrf: String, sources: List<String>): ImportResponse {
        val resp = client.post("/api/sources/import") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(ImportRequest(sources))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body()
    }

    private suspend fun batchSetGroup(client: HttpClient, csrf: String, ids: List<String>, group: String?): Int {
        val resp = client.post("/api/sources/batch") {
            header(AuthService.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody(BatchSourceRequest(action = "set_group", ids = ids, group = group))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<BatchSourceResponse>().affected
    }

    /**
     * 发起一次流式搜索，只取第一帧 `start` 的 `totalSources`（= 本次实际选中的书源数）后立即断开。
     *
     * 不等待搜索结果：书源指向不存在的域名，等结果只会等到超时，而「范围是否收窄」在 `start` 就已确定。
     */
    private suspend fun searchScope(client: HttpClient, csrf: String, group: String?): Int {
        var total = -1
        client.webSocket("/api/search/stream?csrf=${URLEncoder.encode(csrf, "UTF-8")}") {
            send(Frame.Text(Json.encodeToString(SearchRequest(keyword = "范围测试", group = group))))
            withTimeout(20_000) {
                while (true) {
                    val frame = incoming.receive()
                    if (frame !is Frame.Text) continue
                    val event = Json.decodeFromString<SearchStreamEvent>(frame.readText())
                    if (event.type == "start") { total = event.totalSources; break }
                    if (event.type == "error") break
                }
            }
        }
        return total
    }

    private fun sourceJson(url: String, name: String, group: String?): String {
        val groupField = if (group == null) "" else ""","bookSourceGroup":"$group""""
        return """{"bookSourceUrl":"$url","bookSourceName":"$name","enabled":true$groupField}"""
    }
}
