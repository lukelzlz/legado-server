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
    fun `rejects non HTTP source URLs`() {
        val result = SourceCodec.validate("""{"bookSourceUrl":"file:///etc/passwd"}""")

        assertFalse(result.valid)
        assertTrue(result.errors.single().contains("HTTP"))
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
    fun `auto prepends http or https to protocol relative or bare domain urls`() {
        val protoRelative = SourceCodec.parse("""{"bookSourceUrl":"//relative.example.com","bookSourceName":"相对协议"}""")
        assertEquals("https://relative.example.com", protoRelative.id)

        val bareDomain = SourceCodec.parse("""{"bookSourceUrl":"bare.example.com","bookSourceName":"无协议"}""")
        assertEquals("http://bare.example.com", bareDomain.id)
    }
}
