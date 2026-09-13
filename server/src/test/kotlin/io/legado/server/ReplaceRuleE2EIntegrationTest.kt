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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ReplaceRuleE2EIntegrationTest {

    @Test
    fun `full end-to-end integration test for replace rules API and reading content purification`() = testApplication {
        val dbPath = Files.createTempFile("legado-e2e-replace", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-e2e-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0",
                port = 8080,
                databasePath = dbPath,
                coverCacheDirectory = tempDir,
                initialAdminPassword = "adminPassword123!",
                secureCookies = false,
            )

            application {
                legadoApplication(config)
            }

            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
                install(HttpCookies)
            }

            // 1. Health check
            val healthResp = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, healthResp.status)

            // 2. Login
            val loginResp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("adminPassword123!"))
            }
            assertEquals(HttpStatusCode.OK, loginResp.status)
            val csrfToken = loginResp.body<LoginResponse>().csrfToken
            assertNotNull(csrfToken)

            // 3. Test Preview API before saving
            val previewResp = client.post("/api/replace-rules/preview") {
                header("X-CSRF-Token", csrfToken)
                contentType(ContentType.Application.Json)
                setBody(
                    ReplaceRulePreviewRequest(
                        text = "大丑阁上，少萝茜心外无点刺痛，魔男们是是会把强大的魔男当做仆从军。",
                        rule = ReplaceRule(
                            name = "反爬测试",
                            pattern = "(大丑|魔男|少萝茜|阁上|心外|无点|是是会)",
                            replacement = "@js:const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下','心外':'心里','无点':'有点','是是会':'不会'}; return map[result] || result;",
                            isRegex = true,
                            scope = "宅魔女",
                        ),
                        bookName = "宅魔女",
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, previewResp.status)
            val previewResult = previewResp.body<ReplaceRulePreviewResponse>()
            assertTrue(previewResult.changed)
            assertEquals("小丑阁下，多萝茜心里有点刺痛，魔女们不会把强大的魔女当做仆从军。", previewResult.cleanedText)

            // 4. Save Replace Rule
            val saveRuleResp = client.post("/api/replace-rules") {
                header("X-CSRF-Token", csrfToken)
                contentType(ContentType.Application.Json)
                setBody(
                    ReplaceRule(
                        name = "宅魔女-反爬错字清洗",
                        group = "起点反爬",
                        pattern = "(大丑|魔男|少萝茜|阁上|心外|无点|是是会)",
                        replacement = "@js:const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下','心外':'心里','无点':'有点','是是会':'不会'}; return map[result] || result;",
                        isRegex = true,
                        scope = "宅魔女",
                        order = 1,
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, saveRuleResp.status)
            val savedRule = saveRuleResp.body<ReplaceRule>()
            assertNotNull(savedRule.id)
            assertEquals("宅魔女-反爬错字清洗", savedRule.name)

            // 5. Query Replace Rules List
            val listResp = client.get("/api/replace-rules") {
                header("X-CSRF-Token", csrfToken)
            }
            assertEquals(HttpStatusCode.OK, listResp.status)
            val rulesList = listResp.body<List<ReplaceRule>>()
            assertEquals(1, rulesList.size)
            assertEquals(savedRule.id, rulesList[0].id)

            // 6. Test Scope Filter
            val scopeFilterResp = client.get("/api/replace-rules?bookName=宅魔女") {
                header("X-CSRF-Token", csrfToken)
            }
            assertEquals(HttpStatusCode.OK, scopeFilterResp.status)
            val filteredRules = scopeFilterResp.body<List<ReplaceRule>>()
            assertEquals(1, filteredRules.size)

            val otherBookFilterResp = client.get("/api/replace-rules?bookName=诛仙") {
                header("X-CSRF-Token", csrfToken)
            }
            assertEquals(HttpStatusCode.OK, otherBookFilterResp.status)
            val otherFilteredRules = otherBookFilterResp.body<List<ReplaceRule>>()
            assertEquals(0, otherFilteredRules.size)

            // 7. Toggle Rule
            val toggleResp = client.post("/api/replace-rules/toggle") {
                header("X-CSRF-Token", csrfToken)
                contentType(ContentType.Application.Json)
                setBody(ReplaceRuleToggleRequest(ids = listOf(savedRule.id), enabled = false))
            }
            assertEquals(HttpStatusCode.OK, toggleResp.status)
            val getRuleResp = client.get("/api/replace-rules/${savedRule.id}") {
                header("X-CSRF-Token", csrfToken)
            }
            assertFalse(getRuleResp.body<ReplaceRule>().isEnabled)

            // Re-enable
            client.post("/api/replace-rules/toggle") {
                header("X-CSRF-Token", csrfToken)
                contentType(ContentType.Application.Json)
                setBody(ReplaceRuleToggleRequest(ids = listOf(savedRule.id), enabled = true))
            }

            // 8. Test Export
            val exportResp = client.get("/api/replace-rules/export") {
                header("X-CSRF-Token", csrfToken)
            }
            assertEquals(HttpStatusCode.OK, exportResp.status)
            val exportedRules = exportResp.body<List<ReplaceRule>>()
            assertEquals(1, exportedRules.size)

            // 9. Test Import
            val importResp = client.post("/api/replace-rules/import") {
                header("X-CSRF-Token", csrfToken)
                contentType(ContentType.Application.Json)
                setBody(
                    ReplaceRuleImportRequest(
                        rules = listOf(
                            ReplaceRule(name = "通用去广告", pattern = "https?://\\S+", replacement = "", isRegex = true)
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, importResp.status)
            val importResult = importResp.body<ReplaceRuleImportResponse>()
            assertEquals(1, importResult.imported)

            // List again: should have 2 rules
            val finalListResp = client.get("/api/replace-rules") {
                header("X-CSRF-Token", csrfToken)
            }
            assertEquals(2, finalListResp.body<List<ReplaceRule>>().size)

        } finally {
            Files.deleteIfExists(Path.of(dbPath))
            tempDir.toFile().deleteRecursively()
        }
    }
}
