package io.legado.server

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReplaceRuleBatchAndGroupTest {

    @Test
    fun `test batch replace rule actions - enable, disable, set_group, delete`() = testApplication {
        val dbPath = Files.createTempFile("legado-rule-batch-test", ".sqlite").toString()
        val tempDir = Files.createTempDirectory("legado-rule-batch-covers")
        try {
            val config = ServerConfig(
                host = "0.0.0.0",
                port = 8080,
                databasePath = dbPath,
                coverCacheDirectory = tempDir,
                webDavDirectory = tempDir.resolve("webdav"),
                initialAdminPassword = "adminPassword123!",
                secureCookies = false,
            )
            application {
                legadoApplication(config)
            }
            val database = Database(dbPath)

            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
                install(HttpCookies)
            }

            // 1. 登录
            val loginResp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("password" to "adminPassword123!"))
            }
            val loginResult = loginResp.body<LoginResponse>()
            val csrf = loginResult.csrfToken

            // 2. 创建 3 条测试规则
            val r1 = database.saveReplaceRule(ReplaceRule(pattern = "foo", replacement = "bar", name = "R1", group = "旧组", isEnabled = true))
            val r2 = database.saveReplaceRule(ReplaceRule(pattern = "baz", replacement = "qux", name = "R2", group = "旧组", isEnabled = true))
            val r3 = database.saveReplaceRule(ReplaceRule(pattern = "hello", replacement = "world", name = "R3", group = "另一组", isEnabled = false))

            // 3. 批量禁用 r1, r2
            val disableResp = client.post("/api/replace-rules/batch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(BatchReplaceRuleRequest(action = "disable", ids = listOf(r1.id, r2.id)))
            }
            assertEquals(HttpStatusCode.OK, disableResp.status)
            val disableBody = disableResp.body<BatchReplaceRuleResponse>()
            assertEquals(2, disableBody.affected)
            assertFalse(database.getReplaceRule(r1.id)!!.isEnabled)
            assertFalse(database.getReplaceRule(r2.id)!!.isEnabled)

            // 4. 批量启用 r2, r3
            val enableResp = client.post("/api/replace-rules/batch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(BatchReplaceRuleRequest(action = "enable", ids = listOf(r2.id, r3.id)))
            }
            assertEquals(HttpStatusCode.OK, enableResp.status)
            assertTrue(database.getReplaceRule(r2.id)!!.isEnabled)
            assertTrue(database.getReplaceRule(r3.id)!!.isEnabled)

            // 5. 批量设置分组到「新组」
            val setGroupResp = client.post("/api/replace-rules/batch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(BatchReplaceRuleRequest(action = "set_group", ids = listOf(r1.id, r3.id), group = "新组"))
            }
            assertEquals(HttpStatusCode.OK, setGroupResp.status)
            assertEquals("新组", database.getReplaceRule(r1.id)!!.group)
            assertEquals("旧组", database.getReplaceRule(r2.id)!!.group)
            assertEquals("新组", database.getReplaceRule(r3.id)!!.group)

            // 6. 分组列表检查
            val groupsResp = client.get("/api/replace-rule-groups")
            assertEquals(HttpStatusCode.OK, groupsResp.status)
            val groups = groupsResp.body<List<ReplaceRuleGroupSummary>>()
            assertEquals(2, groups.size)
            val newGroup = groups.first { it.name == "新组" }
            assertEquals(2, newGroup.ruleCount)
            assertEquals(1, newGroup.enabledCount) // r3 enabled, r1 disabled

            // 7. 分组重命名: 新组 -> 超级组
            val renameResp = client.put("/api/replace-rule-groups/rename") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(ReplaceRuleGroupRenameRequest(from = "新组", to = "超级组"))
            }
            assertEquals(HttpStatusCode.OK, renameResp.status)
            assertEquals("超级组", database.getReplaceRule(r1.id)!!.group)
            assertEquals("超级组", database.getReplaceRule(r3.id)!!.group)

            // 8. 分组删除（只解绑，不删规则）
            val deleteGroupResp = client.delete("/api/replace-rule-groups?name=超级组") {
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.OK, deleteGroupResp.status)
            assertNull(database.getReplaceRule(r1.id)!!.group)
            assertNull(database.getReplaceRule(r3.id)!!.group)
            assertNotNull(database.getReplaceRule(r1.id)) // 规则未被删除

            // 9. 批量删除 r1, r2
            val batchDeleteResp = client.post("/api/replace-rules/batch") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(BatchReplaceRuleRequest(action = "delete", ids = listOf(r1.id, r2.id)))
            }
            assertEquals(HttpStatusCode.OK, batchDeleteResp.status)
            assertNull(database.getReplaceRule(r1.id))
            assertNull(database.getReplaceRule(r2.id))
            assertNotNull(database.getReplaceRule(r3.id))
        } finally {
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(dbPath)) }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}
