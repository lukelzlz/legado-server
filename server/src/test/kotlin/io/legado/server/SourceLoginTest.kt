package io.legado.server

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SourceLoginTest {

    @Test
    fun `SourceCodec correctly identifies hasLogin`() {
        val noLoginJson = """{"bookSourceUrl":"https://example.com","bookSourceName":"无登录源"}"""
        val parsedNoLogin = SourceCodec.parse(noLoginJson)
        assertFalse(parsedNoLogin.hasLogin)

        val uiLoginJson = """{"bookSourceUrl":"https://example.com","bookSourceName":"UI登录源","loginUi":"[{\"name\":\"用户名\",\"type\":\"text\"}]"}"""
        val parsedUi = SourceCodec.parse(uiLoginJson)
        assertTrue(parsedUi.hasLogin)

        val urlLoginJson = """{"bookSourceUrl":"https://example.com","bookSourceName":"URL登录源","loginUrl":"https://example.com/login"}"""
        val parsedUrl = SourceCodec.parse(urlLoginJson)
        assertTrue(parsedUrl.hasLogin)

        val checkLoginJson = """{"bookSourceUrl":"https://example.com","bookSourceName":"Check登录源","loginCheckJs":"cookie.getCookie(baseUrl) != ''"}"""
        val parsedCheck = SourceCodec.parse(checkLoginJson)
        assertTrue(parsedCheck.hasLogin)
    }

    @Test
    fun `Database source_login_state CRUD works as expected`() {
        val dbPath = Files.createTempFile("legado-login-test", ".sqlite").toString()
        val db = Database(dbPath)
        db.initialize("admin123")

        val sourceId = "https://test-source.com"

        // 1. Initial state is null
        assertNull(db.getSourceLoginState(sourceId))

        // 2. Save loginInfo
        val info = mapOf("username" to "alice", "password" to "secret123")
        assertTrue(db.saveSourceLoginInfo(sourceId, info))
        val state1 = db.getSourceLoginState(sourceId)
        assertNotNull(state1)
        assertEquals("alice", state1!!.loginInfo["username"])
        assertEquals("secret123", state1.loginInfo["password"])

        // 3. Save loginHeader
        val header = """{"Authorization":"Bearer test-token-123"}"""
        assertTrue(db.saveSourceLoginHeader(sourceId, header))
        assertEquals(header, db.getSourceLoginState(sourceId)?.loginHeader)

        // 4. Save variable
        assertTrue(db.saveSourceVariable(sourceId, "var-val-999"))
        assertEquals("var-val-999", db.getSourceVariable(sourceId))

        // 5. Save KV
        assertTrue(db.saveSourceKv(sourceId, "token", "kv-token-abc"))
        assertEquals("kv-token-abc", db.getSourceKv(sourceId, "token"))

        // 6. Cookie operations
        assertTrue(db.setSourceCookie(sourceId, "https://test-source.com/api", "session_id=sess123; user_id=uid456"))
        val cookie = db.getSourceCookie(sourceId, "https://test-source.com/api/user")
        assertNotNull(cookie)
        assertTrue(cookie!!.contains("session_id=sess123"))

        // 7. Remove login header
        assertTrue(db.removeSourceLoginHeader(sourceId))
        assertNull(db.getSourceLoginState(sourceId)?.loginHeader)

        // 8. Clear all state
        assertTrue(db.clearSourceLoginState(sourceId))
        assertNull(db.getSourceLoginState(sourceId))
    }

    @Test
    fun `RuleRunner parses static and dynamic loginUi correctly`() {
        val dbPath = Files.createTempFile("legado-login-ui-test", ".sqlite").toString()
        val db = Database(dbPath)
        db.initialize("admin123")
        val runner = RuleRunner(database = db)

        // Static UI
        val staticJson = """
        {
            "bookSourceUrl": "https://source1.com",
            "bookSourceName": "静态源",
            "loginUi": "[{\"name\":\"邮箱\",\"type\":\"text\",\"style\":{\"layout_flexBasisPercent\":0.5}},{\"name\":\"登录\",\"type\":\"button\",\"action\":\"login()\"}]"
        }
        """.trimIndent()
        val staticItems = runner.parseLoginUi(staticJson)
        assertEquals(2, staticItems.size)
        assertEquals("邮箱", staticItems[0].name)
        assertEquals("text", staticItems[0].type)
        assertEquals(0.5f, staticItems[0].style?.layout_flexBasisPercent ?: 0f, 0.001f)
        assertEquals("登录", staticItems[1].name)
        assertEquals("button", staticItems[1].type)

        // Dynamic @js: UI
        val dynamicJson = """
        {
            "bookSourceUrl": "https://source2.com",
            "bookSourceName": "动态源",
            "loginUi": "@js:JSON.stringify([{name:'动态字段1',type:'password'},{name:'点击',type:'button',action:'doClick()'}])"
        }
        """.trimIndent()
        val dynamicItems = runner.parseLoginUi(dynamicJson)
        assertEquals(2, dynamicItems.size)
        assertEquals("动态字段1", dynamicItems[0].name)
        assertEquals("password", dynamicItems[0].type)

        // Fallback when only loginUrl is present
        val fallbackJson = """
        {
            "bookSourceUrl": "https://source3.com",
            "bookSourceName": "网页登录源",
            "loginUrl": "https://source3.com/login.html"
        }
        """.trimIndent()
        val fallbackItems = runner.parseLoginUi(fallbackJson)
        assertEquals(1, fallbackItems.size)
        assertEquals("button", fallbackItems[0].type)
        assertEquals("https://source3.com/login.html", fallbackItems[0].action)
    }

    @Test
    fun `RuleRunner executes login action with Legado JS bridges`() {
        val dbPath = Files.createTempFile("legado-login-act-test", ".sqlite").toString()
        val db = Database(dbPath)
        db.initialize("admin123")
        val runner = RuleRunner(database = db)

        val sourceJson = """
        {
            "bookSourceUrl": "https://mock-source.com",
            "bookSourceName": "大灰狼测试源",
            "loginUrl": "function login(isVip) { var info = source.getLoginInfoMap(); if (info['user'] == 'admin') { source.putLoginHeader(JSON.stringify({Token:'tok-999'})); cookie.setCookie(source.bookSourceUrl, 'uid=100; auth=true'); java.toast('登录成功！'); java.upLoginData({'status':'已登录'}); } else { java.toast('密码错误'); } }"
        }
        """.trimIndent()

        // 1. Execute login with valid credentials
        val res = runner.executeLoginAction(
            sourceJson = sourceJson,
            actionCode = "login(true)",
            loginData = mapOf("user" to "admin", "pwd" to "123456"),
        )
        assertTrue(res.success)
        assertTrue(res.toastMessages.contains("登录成功！"))
        assertTrue(res.reRenderUi)
        assertEquals("已登录", res.updatedLoginInfo?.get("status"))

        // Check persistence in database
        val state = db.getSourceLoginState("https://mock-source.com")
        assertNotNull(state)
        assertEquals("""{"Token":"tok-999"}""", state!!.loginHeader)
        val cookie = db.getSourceCookie("https://mock-source.com", "https://mock-source.com")
        assertNotNull(cookie)
        assertTrue(cookie!!.contains("uid=100"))

        // 2. Check login check status
        val checkJson = """
        {
            "bookSourceUrl": "https://mock-source.com",
            "loginCheckJs": "cookie.getKey(source.bookSourceUrl, 'auth') == 'true'"
        }
        """.trimIndent()
        val checkRes = runner.checkLoginStatus(checkJson)
        assertTrue(checkRes.loggedIn)
    }

    @Test
    fun `HTTP Login endpoints integration test`() = testApplication {
        val dbPath = Files.createTempFile("legado-routes-login-test", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-routes-login-covers")
        val config = ServerConfig(
            host = "0.0.0.0", port = 8080, databasePath = dbPath,
            coverCacheDirectory = tempDir, initialAdminPassword = "admin123", secureCookies = false
        )

        application {
            legadoApplication(config)
        }

        val client = createClient {
            install(HttpCookies)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        // Login admin
        val loginRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("admin123"))
        }
        assertEquals(HttpStatusCode.OK, loginRes.status)
        val loginBody = loginRes.body<LoginResponse>()
        val csrf = loginBody.csrfToken

        // Insert a source with loginUi
        val sourceJson = """
        {
            "bookSourceUrl": "https://api-source.com",
            "bookSourceName": "API测试源",
            "loginUi": "[{\"name\":\"用户名\",\"type\":\"text\"},{\"name\":\"登录\",\"type\":\"button\",\"action\":\"login()\"}]",
            "loginUrl": "function login() { source.putLoginHeader('{\"X-Auth\":\"tok-123\"}'); java.toast('API登录成功'); }"
        }
        """.trimIndent()
        val saveRes = client.post("/api/sources/import") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-CSRF-Token", csrf)
            setBody(ImportRequest(listOf(sourceJson)))
        }
        assertEquals(HttpStatusCode.OK, saveRes.status)

        val sourcesListRes = client.get("/api/sources")
        assertEquals(HttpStatusCode.OK, sourcesListRes.status)
        val sourcesList = sourcesListRes.body<List<SourceSummary>>()
        val sourceRecord = sourcesList.first { it.url == "https://api-source.com" }
        assertTrue(sourceRecord.hasLogin)

        val encodedId = java.net.URLEncoder.encode(sourceRecord.id, "UTF-8")

        // 1. GET /api/sources/{id}/login-ui
        val uiRes = client.get("/api/sources/$encodedId/login-ui")
        assertEquals(HttpStatusCode.OK, uiRes.status)
        val uiBody = uiRes.body<SourceLoginUiResponse>()
        assertEquals("API测试源", uiBody.sourceName)
        assertTrue(uiBody.hasLogin)
        assertEquals(2, uiBody.loginUi.size)

        // 2. POST /api/sources/{id}/login-info
        val infoRes = client.post("/api/sources/$encodedId/login-info") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-CSRF-Token", csrf)
            setBody(SourceLoginInfoUpdateRequest(loginInfo = mapOf("username" to "user888")))
        }
        assertEquals(HttpStatusCode.OK, infoRes.status)

        // 3. POST /api/sources/{id}/login-action
        val actionRes = client.post("/api/sources/$encodedId/login-action") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-CSRF-Token", csrf)
            setBody(SourceLoginActionRequest(action = "login()", loginData = mapOf("username" to "user888")))
        }
        assertEquals(HttpStatusCode.OK, actionRes.status)
        val actionOutcome = actionRes.body<SourceLoginActionResult>()
        assertTrue(actionOutcome.success)
        assertTrue(actionOutcome.toastMessages.contains("API登录成功"))

        // 4. DELETE /api/sources/$encodedId/login-header
        val delHeaderRes = client.delete("/api/sources/$encodedId/login-header") {
            header("X-CSRF-Token", csrf)
        }
        assertEquals(HttpStatusCode.OK, delHeaderRes.status)

        // 5. POST /api/sources/{id}/login-cookie with JSON Array format (Cookie-Editor format)
        val cookieJsonArray = """[{"name":"token","value":"xyz999"},{"name":"session_id","value":"sess888"}]"""
        val cookieRes = client.post("/api/sources/$encodedId/login-cookie") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(SourceLoginCookieUpdateRequest(cookie = cookieJsonArray, url = sourceRecord.url))
        }
        assertEquals(HttpStatusCode.OK, cookieRes.status)
        val updatedUiRes = client.get("/api/sources/$encodedId/login-ui")
        val updatedUi = updatedUiRes.body<SourceLoginUiResponse>()
        assertNotNull(updatedUi.loginHeader)
        assertTrue(updatedUi.loginHeader!!.contains("token=xyz999"))
        assertTrue(updatedUi.loginHeader!!.contains("session_id=sess888"))

        // 6. DELETE /api/sources/{id}/login-info
        val delInfoRes = client.delete("/api/sources/$encodedId/login-info") {
            header("X-CSRF-Token", csrf)
        }
        assertEquals(HttpStatusCode.OK, delInfoRes.status)
    }

    @Test
    fun `Database parseCookieString handles all major cookie formats`() {
        val dbPath = Files.createTempFile("legado-cookie-parse-test", ".sqlite").toString()
        val db = Database(dbPath)
        db.initialize("admin123")

        // 1. Standard cookie string
        val stdMap = db.parseCookieString("name1=val1; name2=val2=with=eq; name3=val3")
        assertEquals(3, stdMap.size)
        assertEquals("val1", stdMap["name1"])
        assertEquals("val2=with=eq", stdMap["name2"])
        assertEquals("val3", stdMap["name3"])

        // 2. Cookie-Editor JSON array
        val jsonArray = """
        [
            {"name": "token", "value": "secret-abc-123", "domain": ".shubl.com"},
            {"name": "uid", "value": "10086", "path": "/"}
        ]
        """.trimIndent()
        val jsonArrayMap = db.parseCookieString(jsonArray)
        assertEquals(2, jsonArrayMap.size)
        assertEquals("secret-abc-123", jsonArrayMap["token"])
        assertEquals("10086", jsonArrayMap["uid"])

        // 3. JSON Object format
        val jsonObj = """{"Cookie": "sess=xyz; auth=true"}"""
        val jsonObjMap = db.parseCookieString(jsonObj)
        assertEquals(2, jsonObjMap.size)
        assertEquals("xyz", jsonObjMap["sess"])
        assertEquals("true", jsonObjMap["auth"])

        // 4. Netscape format
        val netscape = """
        # Netscape HTTP Cookie File
        .example.com	TRUE	/	FALSE	1999999999	session_key	netscape_val_1
        .example.com	TRUE	/	FALSE	1999999999	account_id	998877
        """.trimIndent()
        val netscapeMap = db.parseCookieString(netscape)
        assertEquals(2, netscapeMap.size)
        assertEquals("netscape_val_1", netscapeMap["session_key"])
        assertEquals("998877", netscapeMap["account_id"])
    }
}
