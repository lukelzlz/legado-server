package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小说正文插图 · **内容层**验证（清洗后正文里 `<img>` 的去留与安全）。
 *
 * 回归锚点（修复前的行为）：`RuleRunner.cleanContent()` 用 `Regex("<[^>]+>")` 把一切标签剥光
 * ⇒ 正文里的插图**彻底消失**，用户看到的是"图片位置什么都没有"。
 * 这一组用例同时钉住三条安全判据（只认 http(s) / 剥掉 `on*` 与 `<script>` / `data:` 丢弃），
 * 任何一条被放松都会立刻变红。
 */
class NovelIllustrationContentTest {

    private fun source(contentRule: String = "@css:#content@html") = """{
        "bookSourceUrl":"https://novel.example",
        "ruleContent":{"content":"$contentRule","title":"@css:h2@text"}
    }"""

    private fun runnerReturning(html: String): RuleRunner = RuleRunner { url ->
        when (url) {
            "https://novel.example/c/1.html" -> html
            else -> error("Unexpected URL: $url")
        }
    }

    @Test
    fun `keeps safe inline illustrations in cleaned content`() {
        val runner = runnerReturning(
            """<html><body><h2>第1章</h2><div id="content">
              |<p>第一段文字</p>
              |<p><img src="https://cdn.example.com/a.jpg" alt="插图A" onerror="alert(1)"></p>
              |<p>第二段文字</p>
              |<p><img src="/rel/b.png"></p>
              |<p>第三段文字</p>
              |</div></body></html>""".trimMargin(),
        )

        val content = runner.content(source(), "https://novel.example/c/1.html").content

        assertTrue("插图必须保留：$content", content.contains("<img src=\"https://cdn.example.com/a.jpg\""))
        assertTrue("alt 必须保留：$content", content.contains("alt=\"插图A\""))
        assertTrue(
            "站内相对地址必须按章节页补全（补不全的插图前端取不到字节）：$content",
            content.contains("<img src=\"https://novel.example/rel/b.png\">"),
        )
        // 三处危险面：上游属性、脚本、伪协议
        assertFalse("上游 on* 属性绝不能透传：$content", content.contains("onerror"))
        assertFalse("srcset 之类属性不参与重建：$content", content.contains("alt=\"插图A\" onerror"))
        // 正文文字必须照旧完整（插图不能把段落挤成一行或把文字吃掉）
        assertTrue(content.contains("第一段文字"))
        assertTrue(content.contains("第二段文字"))
        assertTrue(content.contains("第三段文字"))
        // 插图独占一行 ⇒ 前端按行就能渲染成「图片块」而不是文字
        assertTrue(
            "插图必须独占一行：$content",
            content.lines().any { it.trim() == "<img src=\"https://cdn.example.com/a.jpg\" alt=\"插图A\">" },
        )
        // 顺序必须与正文一致（登记序号依赖它）
        assertTrue(
            "插图顺序必须与正文一致：$content",
            content.indexOf("a.jpg") < content.indexOf("b.png"),
        )
    }

    @Test
    fun `drops script tags and non-http illustration sources`() {
        val runner = runnerReturning(
            """<html><body><div id="content">
              |<p>正文前</p>
              |<p><img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></p>
              |<p><img src="javascript:alert(document.cookie)"></p>
              |<p><img src="blob:https://novel.example/123"></p>
              |<script>fetch('/steal')</script>
              |<p>正文后</p>
              |</div></body></html>""".trimMargin(),
        )

        val content = runner.content(source(), "https://novel.example/c/1.html").content

        assertTrue("正文文字必须保留：$content", content.contains("正文前") && content.contains("正文后"))
        assertFalse("data: 插图必须整条丢弃：$content", content.contains("data:image"))
        assertFalse("javascript: 绝不能进正文：$content", content.contains("javascript:"))
        assertFalse("blob: 服务端取不到字节，必须丢弃：$content", content.contains("blob:"))
        assertFalse("<script> 必须被剥掉：$content", content.contains("<script"))
        assertFalse("清洗后不应残留任何插图：$content", content.contains("<img"))
    }

    @Test
    fun `keeps illustration when the chapter body is wrapped in a single div`() {
        // 聚合源常见形态：正文整体包一层 div，旧实现的结构性删除会把正文吃光（走 fallback 分支）。
        // 这里锁定：**fallback 分支同样保留插图**（两条清洗路径都要生效，修一条忘一条 = 没修）。
        val runner = runnerReturning(
            """<html><body><div id="content"><div rs-native="true">正文一段<img src="https://cdn.example.com/w.jpg"><br>正文二段</div></div></body></html>""",
        )

        val content = runner.content(source(), "https://novel.example/c/1.html").content
        assertTrue("外层 div 包裹的正文也必须保留插图：$content", content.contains("<img src=\"https://cdn.example.com/w.jpg\">"))
        assertTrue(content.contains("正文一段"))
        assertTrue(content.contains("正文二段"))
    }

    @Test
    fun `url normalisation accepts only absolute http and https`() {
        val base = "https://novel.example/c/1.html"
        assertEquals("https://cdn.example.com/a.jpg", IllustrationMarkup.safeHttpUrl("https://cdn.example.com/a.jpg", base))
        assertEquals("https://cdn.example.com/a.jpg", IllustrationMarkup.safeHttpUrl("//cdn.example.com/a.jpg", base))
        assertEquals("https://novel.example/rel/b.png", IllustrationMarkup.safeHttpUrl("/rel/b.png", base))
        assertEquals("https://cdn.example.com/c.jpg", IllustrationMarkup.safeHttpUrl("  https://cdn.example.com/c.jpg  ", base))
        // 协议相对地址在没有基准时补 https（绝不补成明文 http）
        assertEquals("https://cdn.example.com/d.jpg", IllustrationMarkup.safeHttpUrl("//cdn.example.com/d.jpg", null))

        assertNull(IllustrationMarkup.safeHttpUrl("data:image/png;base64,AAAA", base))
        assertNull(IllustrationMarkup.safeHttpUrl("javascript:alert(1)", base))
        assertNull(IllustrationMarkup.safeHttpUrl("java\nscript:alert(1)", base))
        assertNull(IllustrationMarkup.safeHttpUrl("blob:https://novel.example/1", base))
        assertNull(IllustrationMarkup.safeHttpUrl("file:///etc/passwd", base))
        assertNull(IllustrationMarkup.safeHttpUrl("", base))
        assertNull("没有基准的站内相对地址无处可补，必须丢弃", IllustrationMarkup.safeHttpUrl("/rel/b.png", null))
        // 聚合源的章节地址是 data: 载荷 ⇒ 基准不可用，只有绝对地址能留下
        assertNull(IllustrationMarkup.safeHttpUrl("/rel/b.png", "data:;base64,eyJhIjoxfQ=="))
        assertEquals(
            "https://cdn.example.com/e.jpg",
            IllustrationMarkup.safeHttpUrl("https://cdn.example.com/e.jpg", "data:;base64,eyJhIjoxfQ=="),
        )
    }

    @Test
    fun `extracts illustration urls in document order`() {
        val content = """
            第一段
            <img src="https://cdn.example.com/1.jpg">
            第二段
            <img src="https://cdn.example.com/2.jpg" alt="二">
            <img src="javascript:alert(1)">
            <img src="https://cdn.example.com/1.jpg">
        """.trimIndent()

        val urls = IllustrationMarkup.urlsIn(content)
        assertEquals(
            "只取 http(s)、按出现顺序、重复项也照原样返回（序号由序列位置决定）：$urls",
            listOf("https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg", "https://cdn.example.com/1.jpg"),
            urls,
        )
        assertTrue(IllustrationMarkup.urlsIn("纯文本，没有图").isEmpty())
    }

    @Test
    fun `attribute escaping round-trips ampersands in illustration urls`() {
        val base = "https://novel.example/c/1.html"
        val tag = IllustrationMarkup.safeTag(
            """<img src="https://cdn.example.com/a.jpg?w=1&h=2&sig=x" alt="a &amp; b">""",
            base,
        )
        assertEquals("""<img src="https://cdn.example.com/a.jpg?w=1&amp;h=2&amp;sig=x" alt="a &amp; b">""", tag)
        // 读取侧（登记序号）必须拿到**未转义**的真实地址，否则抓取时 query 会带上 `&amp;`
        assertEquals(listOf("https://cdn.example.com/a.jpg?w=1&h=2&sig=x"), IllustrationMarkup.urlsIn(tag!!))
    }
}
