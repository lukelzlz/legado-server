package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对抗审查：针对本次新增逻辑做**挑刺式**边界测试。
 *
 * 审查者立场：假设实现者（我）在下面这些地方可能想错了。
 */
class LegadoRuleCompatAdversarialTest {

    // ------------------------------------------------------------------
    // 挑刺 1：`##` 正则替换 与 `@js:` 后置处理同时出现时，谁先谁后？
    // ------------------------------------------------------------------

    /**
     * 真实书源常见 `路径@js:代码##正则##替换`。若顺序处理不当，
     * `##` 部分会被当成 JS 代码的一部分丢给 Rhino ⇒ 语法错误。
     */
    @Test
    fun `js post processor combined with regex replace`() {
        val payload = """{"data":{"list":[{"path":"/a/1.html","chapterName":"第1章"}]}}"""
        val runner = RuleRunner(responseFetcher = { payload })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://e.com","ruleToc":{"chapterList":"$.data.list[*]","chapterName":"$.chapterName","chapterUrl":"$.path@js:result.replace('/a/','/b/')##\\?.*"}}""",
            "https://e.com/book/1/",
        )
        // 只要不抛异常且能取到值即可；重点验证不会因 ## 导致整条规则失效
        assertEquals("规则链不应整体失效", 1, chapters.size)
        assertTrue("应保留章节地址，实际：${chapters.firstOrNull()?.url}", chapters.first().url.isNotBlank())
    }

    // ------------------------------------------------------------------
    // 挑刺 2：`@js:` 后置处理的左路径为空（规则以 `@js:` 以外的形式起头）
    // ------------------------------------------------------------------

    /** 规则本身就是纯 JS 表达式且不含路径时，`findJsPostProcessor` 应返回 -1，走原有分支。 */
    @Test
    fun `leading js rule is not treated as post processor`() {
        val html = "<html><body><p>正文</p></body></html>"
        val runner = RuleRunner(responseFetcher = { html })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://e.com","ruleToc":{"chapterList":"@js:[{title:'第1章',url:'/1.html'}]","chapterName":"$.title","chapterUrl":"$.url"}}""",
            "https://e.com/book/1/",
        )
        assertEquals(1, chapters.size)
        assertTrue(chapters.first().url.endsWith("/1.html"))
    }

    // ------------------------------------------------------------------
    // 挑刺 3：header 宽容解析遇到恶意/畸形输入会不会「吞掉整条规则」
    // ------------------------------------------------------------------

    /** 值里带转义引号：不能把值截断，也不能因此丢掉后续键。 */
    @Test
    fun `header with escaped quotes does not swallow later keys`() {
        val raw = """{ 'A': 'say \'hi\'', 'B': 'ok' }"""
        val headers = RuleRunner(responseFetcher = { "" }).parseHeaderMap(raw)
        assertEquals("A 与 B 都应解析出来，实际：$headers", 2, headers.size)
        assertEquals("ok", headers["B"])
    }

    /** 只有一个不完整条目时不能抛异常，也不能返回垃圾键。 */
    @Test
    fun `truncated header yields partial result without throwing`() {
        val headers = RuleRunner(responseFetcher = { "" }).parseHeaderMap("{ 'A': '1', 'B': ")
        assertTrue("应至少解析出 A，实际：$headers", headers["A"] == "1")
        assertTrue("不应产生空键，实际：$headers", headers.keys.none { it.isBlank() })
    }

    /** 数字/布尔型 header 值（JSON 合法）应被保留为文本。 */
    @Test
    fun `non-string json header values are preserved as text`() {
        val headers = RuleRunner(responseFetcher = { "" }).parseHeaderMap("""{"X-Num": 123, "X-Bool": true}""")
        assertEquals("123", headers["X-Num"])
        assertEquals("true", headers["X-Bool"])
    }

    // ------------------------------------------------------------------
    // 挑刺 4：选择器翻译不能「误伤」合法 CSS
    // ------------------------------------------------------------------

    /** `id` 作为**标签名**（罕见的自定义标签）不该被翻译；只有 `id.xxx` 形式才是 Legado 写法。 */
    @Test
    fun `bare id or tag tokens are not translated`() {
        assertEquals("id", translateSelector("id"))
        assertEquals("tag", translateSelector("tag"))
        assertEquals("idfoo", translateSelector("idfoo"))
    }

    /** 复合选择器（含空格、属性、伪类）必须原样保留。 */
    @Test
    fun `complex css selectors pass through unchanged`() {
        val cases = listOf(
            "div#list > dd",
            "a[href^='/book/']",
            ".content p:first-child",
            "ul li:nth-child(2)",
        )
        for (c in cases) assertEquals(c, translateSelector(c))
    }

    /** `id.x` 带下划线/数字/连字符的 id 名也要支持。 */
    @Test
    fun `id selector supports underscore dash and digits`() {
        assertEquals("#chapter_list", translateSelector("id.chapter_list"))
        assertEquals("#list-2", translateSelector("id.list-2"))
        assertEquals("#a1", translateSelector("id.a1"))
    }

    // ------------------------------------------------------------------
    // 挑刺 5：多下标排除的边界
    // ------------------------------------------------------------------

    /** 排除下标超出实际元素数时不应报错，且不能把全部元素排掉。 */
    @Test
    fun `exclusion beyond element count keeps remaining items`() {
        val html = """<html><body><div class="m"><ul>
            <li><a href="/0.html">0</a></li>
            <li><a href="/1.html">1</a></li>
            <li><a href="/2.html">2</a></li>
        </ul></div></body></html>"""
        val runner = RuleRunner(responseFetcher = { html })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://e.com","ruleToc":{"chapterList":".m@ul@li!0:1:99","chapterName":"a@text","chapterUrl":"a@href"}}""",
            "https://e.com/b/",
        )
        assertEquals("只排除 0、1，应剩 1 章", 1, chapters.size)
        assertEquals("2", chapters.first().title)
    }

    /** 重复下标不应产生重复的 `:not()` 而报错。 */
    @Test
    fun `duplicate exclusion indices are deduplicated`() {
        val html = """<html><body><div class="m"><ul>
            <li><a href="/0.html">0</a></li>
            <li><a href="/1.html">1</a></li>
        </ul></div></body></html>"""
        val runner = RuleRunner(responseFetcher = { html })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://e.com","ruleToc":{"chapterList":".m@ul@li!0:0:0","chapterName":"a@text","chapterUrl":"a@href"}}""",
            "https://e.com/b/",
        )
        assertEquals(1, chapters.size)
        assertEquals("1", chapters.first().title)
    }

    // ------------------------------------------------------------------
    // 挑刺 6：AES 的 IV / transformation 缺省分支
    // ------------------------------------------------------------------

    /** 不传 IV 时应回落到无 IV 模式（ECB）而不是崩溃。 */
    @Test
    fun `aes without iv falls back to ecb`() {
        val sandbox = JsSandbox()
        val enc = sandbox.eval(
            """java.aesBase64EncodeToString("hello","1234567890123456","AES/ECB/PKCS5Padding","");""",
            emptyMap(), JsExecutionContext(sourceId = "t"),
        )
        val dec = sandbox.eval(
            """java.aesBase64DecodeToString("$enc","1234567890123456","AES/ECB/PKCS5Padding","");""",
            emptyMap(), JsExecutionContext(sourceId = "t"),
        )
        assertEquals("hello", dec)
    }

    /** 加解密往返必须一致（CBC）。 */
    @Test
    fun `aes cbc round trip is stable`() {
        val sandbox = JsSandbox()
        val enc = sandbox.eval(
            """java.aesBase64EncodeToString("原始文本 with spaces","f041c49714d39908","AES/CBC/PKCS5Padding","0123456789abcdef");""",
            emptyMap(), JsExecutionContext(sourceId = "t"),
        )
        val dec = sandbox.eval(
            """java.aesBase64DecodeToString("$enc","f041c49714d39908","AES/CBC/PKCS5Padding","0123456789abcdef");""",
            emptyMap(), JsExecutionContext(sourceId = "t"),
        )
        assertEquals("原始文本 with spaces", dec)
    }

    /** `createSymmetricCrypto` 的对象方法要能正常调用。 */
    @Test
    fun `createSymmetricCrypto round trips`() {
        val sandbox = JsSandbox()
        val result = sandbox.eval(
            """
            var c = java.createSymmetricCrypto("AES/CBC/PKCS5Padding","f041c49714d39908","0123456789abcdef");
            var e = c.encrypt("中文测试");
            c.decrypt(e);
            """.trimIndent(),
            emptyMap(), JsExecutionContext(sourceId = "t"),
        )
        assertEquals("中文测试", result)
    }
}
