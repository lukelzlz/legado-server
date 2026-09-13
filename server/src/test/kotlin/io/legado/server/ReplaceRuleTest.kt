package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReplaceRuleTest {

    @Test
    fun `scope matching supports global, book name, source url and exclude scope`() {
        val globalRule = ReplaceRule(id = "1", pattern = "foo", replacement = "bar", scope = null)
        assertTrue(ContentProcessor.matchesScope(globalRule, "宅魔女", "https://example.com"))

        val specificBookRule = ReplaceRule(id = "2", pattern = "foo", replacement = "bar", scope = "宅魔女,诡秘之主")
        assertTrue(ContentProcessor.matchesScope(specificBookRule, "宅魔女", "https://other.com"))
        assertTrue(ContentProcessor.matchesScope(specificBookRule, "《诡秘之主》", "https://other.com"))
        assertFalse(ContentProcessor.matchesScope(specificBookRule, "凡人修仙传", "https://other.com"))

        val excludeRule = ReplaceRule(
            id = "3",
            pattern = "foo",
            replacement = "bar",
            scope = null,
            excludeScope = "凡人修仙传"
        )
        assertTrue(ContentProcessor.matchesScope(excludeRule, "宅魔女", "https://example.com"))
        assertFalse(ContentProcessor.matchesScope(excludeRule, "凡人修仙传", "https://example.com"))
    }

    @Test
    fun `antithetical anti-theft obfuscation words correctly inverted`() {
        val sampleText = """
            “大丑阁上，你可以给他们一点时间，让他们重新筹备一些仆从军。”炽天使看了看对面审判官们身前这群魔乱舞的队伍，如此说道。魔男们是是会把强大的魔男同族当做仆从军，那是违法的，少萝茜心外微微无点良心刺痛，只可惜宅魔男还真挺希望和对方好好相处的。
        """.trimIndent()

        val rules = listOf(
            ReplaceRule(
                id = "r1",
                name = "反义词-角色与反派",
                pattern = "(大丑|宅魔男|魔男|少萝茜)",
                replacement = "@js:const map={'大丑':'小丑','宅魔男':'宅魔女','魔男':'魔女','少萝茜':'多萝茜'}; return map[result] || result;",
                isRegex = true,
                scope = "宅魔女",
                order = 1,
            ),
            ReplaceRule(
                id = "r2",
                name = "反义词-常用颠倒词",
                pattern = "(阁上|心外|无点|是是会)",
                replacement = "@js:const map={'阁上':'阁下','心外':'心里','无点':'有点','是是会':'不会'}; return map[result] || result;",
                isRegex = true,
                scope = "宅魔女",
                order = 2,
            )
        )

        val runner = RuleRunner()
        val jsSandbox = JsSandbox(runner)
        val cleaned = ContentProcessor.processContent(
            content = sampleText,
            rules = rules,
            jsSandbox = jsSandbox,
            bookName = "宅魔女",
        )

        assertTrue(cleaned.contains("小丑阁下"))
        assertTrue(cleaned.contains("魔女们不会把强大的魔女同族"))
        assertTrue(cleaned.contains("多萝茜心里微微有点良心刺痛"))
        assertTrue(cleaned.contains("宅魔女还真挺希望"))
        assertFalse(cleaned.contains("大丑阁上"))
        assertFalse(cleaned.contains("宅魔男"))
    }

    @Test
    fun `database replace rules CRUD and import export`() {
        val path = Files.createTempFile("legado-server-replace-rules-test", ".sqlite").toString()
        try {
            val db = Database(path)
            db.initialize("adminPassword123!")

            // 1. Initial list empty
            assertEquals(0, db.listReplaceRules().size)

            // 2. Save rule
            val rule = ReplaceRule(
                name = "去广告",
                group = "净化",
                pattern = "广告.*",
                replacement = "",
                isRegex = true,
                scope = null,
            )
            val saved = db.saveReplaceRule(rule)
            assertNotNull(saved.id)
            assertEquals("去广告", saved.name)

            // 3. Query list
            val list = db.listReplaceRules()
            assertEquals(1, list.size)
            assertEquals(saved.id, list[0].id)

            // 4. Toggle
            db.toggleReplaceRules(listOf(saved.id), false)
            assertFalse(db.getReplaceRule(saved.id)!!.isEnabled)

            // 5. Batch import
            val importJson = listOf(
                ReplaceRule(name = "错字1", pattern = "大丑", replacement = "小丑", scope = "宅魔女"),
                ReplaceRule(name = "错字2", pattern = "魔男", replacement = "魔女", scope = "宅魔女"),
            )
            val res = db.importReplaceRules(importJson)
            assertEquals(2, res.imported)

            // 6. Scope filter query
            val bookRules = db.getEnabledReplaceRulesForScope("宅魔女", "https://test.com")
            assertEquals(2, bookRules.size)
            val otherRules = db.getEnabledReplaceRulesForScope("诛仙", "https://test.com")
            assertEquals(0, otherRules.size)

            // 7. Export
            val exported = db.exportReplaceRules()
            assertEquals(3, exported.size)

            // 8. Delete
            db.deleteReplaceRule(saved.id)
            assertEquals(2, db.listReplaceRules().size)

            db.close()
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }
}
