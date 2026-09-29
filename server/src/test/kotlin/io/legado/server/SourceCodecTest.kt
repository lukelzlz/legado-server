package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCodecTest {
    @Test
    fun `parses Android compatible source fields`() {
        val source = SourceCodec.parse(
            """{"bookSourceUrl":"https://example.com","bookSourceName":"示例","bookSourceGroup":"测试","enabled":false,"mainJs":"function search(){}"}"""
        )

        assertEquals("https://example.com", source.id)
        assertEquals("示例", source.name)
        assertEquals("测试", source.group)
        assertFalse(source.enabled)
        assertTrue(source.isJs)
    }

    @Test
    fun `rejects missing or empty source URLs`() {
        val result = SourceCodec.validate("""{"bookSourceName":"无URL"}""")
        assertFalse(result.valid)
        assertTrue(result.errors.single().contains("缺少 bookSourceUrl"))

        val emptyResult = SourceCodec.validate("""{"bookSourceUrl":"   "}""")
        assertFalse(emptyResult.valid)
        assertTrue(emptyResult.errors.single().contains("不能为空"))
    }

    @Test
    fun `parses custom ID source URLs like Chinese names`() {
        val source = SourceCodec.parse("""{"bookSourceUrl":"大灰狼融合VIP5.0","bookSourceName":"🍅大灰狼聚合5.8.20(vip完全版)","bookSourceGroup":"大灰狼聚合"}""")
        assertEquals("大灰狼融合VIP5.0", source.id)
        assertEquals("🍅大灰狼聚合5.8.20(vip完全版)", source.name)
        assertEquals("大灰狼聚合", source.group)
        assertTrue(source.enabled)
    }

    @Test
    fun `strips legado annotations from book source urls`() {
        val legacy = SourceCodec.parse("""{"bookSourceUrl":"https://cn.ttkan.co/##@遇知","bookSourceName":"天天看书","searchUrl":"/novel/search?q={{key}}"}""")
        assertEquals("https://cn.ttkan.co/", legacy.id)
        assertTrue(legacy.json.contains(""""bookSourceUrl":"https://cn.ttkan.co/""""))

        val hash = SourceCodec.parse("""{"bookSourceUrl":"https://www.linovel.net#yc1101","searchUrl":"/search/?kw={{key}}"}""")
        assertEquals("https://www.linovel.net", hash.id)
    }

    @Test
    fun `handles UTF-8 BOM and whitespace in url and names`() {
        val bomSource = "\uFEFF  {\"bookSourceUrl\":\"  https://example.org/ \",\"bookSourceName\":\"  BOM源  \"}  "
        val parsed = SourceCodec.parse(bomSource)
        assertEquals("https://example.org/", parsed.id)
        assertEquals("BOM源", parsed.name)
        assertTrue(parsed.enabled)
    }

    @Test
    fun `supports alternative field names like sourceUrl and enable`() {
        val source = SourceCodec.parse("""{"sourceUrl":"https://legacy.example.com","sourceName":"旧版书源","sourceGroup":"旧版","enable":"0"}""")
        assertEquals("https://legacy.example.com", source.id)
        assertEquals("旧版书源", source.name)
        assertEquals("旧版", source.group)
        assertFalse(source.enabled)
    }

    @Test
    fun `auto converts protocol relative urls`() {
        val protoRelative = SourceCodec.parse("""{"bookSourceUrl":"//relative.example.com","bookSourceName":"相对协议"}""")
        assertEquals("https://relative.example.com", protoRelative.id)
    }

    @Test
    fun `keepGroup false strips the group from both the record and the payload`() {
        // 导入书源文件时不采用书源自带分组；**列与 payload 必须同时清掉**，
        // 只清列会让编辑弹窗显示一个库里并不存在的分组，用户一保存又把它写回去。
        val plain = SourceCodec.parse(
            """{"bookSourceUrl":"https://example.com","bookSourceName":"示例","bookSourceGroup":"测试","enabled":true}""",
            keepGroup = false,
        )
        assertEquals(null, plain.group)
        assertFalse(plain.json.contains("bookSourceGroup"))
        assertTrue("其余字段不能被误删", plain.json.contains("https://example.com"))

        // 历史别名（sourceGroup / group）同样要删净
        val legacy = SourceCodec.parse(
            """{"sourceUrl":"https://legacy.example.com","sourceName":"旧版","sourceGroup":"旧版"}""",
            keepGroup = false,
        )
        assertEquals(null, legacy.group)
        assertFalse(legacy.json.contains("sourceGroup"))

        // 默认行为（编辑器保存 / 备份导入）保持不变
        assertEquals("测试", SourceCodec.parse("""{"bookSourceUrl":"https://example.com","bookSourceGroup":"测试"}""").group)
    }

    @Test
    fun `parses real-world complex shareBookSource JSON`() {
        val sampleFile = java.io.File("/Users/zhangran/Downloads/shareBookSource(1).json")
        if (sampleFile.exists()) {
            val content = sampleFile.readText()
            val list = kotlinx.serialization.json.Json.parseToJsonElement(content) as kotlinx.serialization.json.JsonArray
            val first = list[0].toString()
            val parsed = SourceCodec.parse(first)
            assertEquals("大灰狼融合VIP5.0", parsed.id)
            assertEquals("🍅大灰狼聚合5.8.20(vip完全版)", parsed.name)
            assertEquals("大灰狼聚合", parsed.group)
            assertTrue(parsed.hasLogin)
        }
    }

    @Test
    fun `withGroup updates or removes bookSourceGroup in payload cleanly`() {
        val originalNoGroup = """{"bookSourceUrl":"https://example.com","bookSourceName":"测试"}"""
        val withGroup = SourceCodec.withGroup(originalNoGroup, "新分组")
        assertTrue(withGroup.contains(""""bookSourceGroup":"新分组""""))
        assertEquals("新分组", SourceCodec.parse(withGroup).group)

        // 替换已有分组并清除别名
        val originalLegacy = """{"bookSourceUrl":"https://example.com","sourceGroup":"旧别名","group":"另一别名"}"""
        val updated = SourceCodec.withGroup(originalLegacy, "统一分组")
        assertTrue(updated.contains(""""bookSourceGroup":"统一分组""""))
        assertFalse(updated.contains("sourceGroup"))
        assertFalse(updated.contains(""""group":"""))

        // null 或空白清空分组
        val cleared = SourceCodec.withGroup(withGroup, null)
        assertFalse(cleared.contains("bookSourceGroup"))
        val clearedBlank = SourceCodec.withGroup(withGroup, "   ")
        assertFalse(clearedBlank.contains("bookSourceGroup"))
    }
}
