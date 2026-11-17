package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归测试：Legado 取值规则的兼容性补齐。
 *
 * 这四条的共同点是**静默失败**——规则写对了却取不到值，日志里没有任何错误：
 *
 * | 写法 | 旧行为 | 后果 |
 * | :-- | :-- | :-- |
 * | `A||B` 主备选择器 | 整串丢给 Jsoup/JsonPath | 恒取不到值 ⇒ 分页/目录规则失效 |
 * | `class.a b`（多类名） | 当成 CSS 后代选择器 | 恒返回空集 ⇒ 目录 0 章 |
 * | `a.-1`（负数索引） | `coerceAtLeast(0)` 变成 `a.0` | 永远取第一个 ⇒ 拿错链接 |
 * | `text.下一页@href` | 不认 `text.` 前缀 | 恒返回空 ⇒ `nextContentUrl` 永远为空 |
 *
 * 全部用例都是离线造 HTML，不打真实站点。
 */
class RuleValueCompatTest {

    // ------------------------------------------------------------------
    // 1. `A||B` 多规则回退
    // ------------------------------------------------------------------

    /** 主选择器不命中时回退到备用选择器；两者都空才是真空。 */
    @Test
    fun `falls back to the alternative rule when the first one is empty`() {
        val html = """<html><body><p class="real">正文在这里</p></body></html>"""

        assertEquals("正文在这里", value("@css:.missing@text||@css:.real@text", html))
        assertNull(value("@css:.missing@text||@css:.also-missing@text", html))
    }

    /** 命中首个非空即返回，不会因为后面还有规则而拼接。 */
    @Test
    fun `returns the first non empty alternative only`() {
        val html = """<html><body><p class="a">第一</p><p class="b">第二</p></body></html>"""

        assertEquals("第一", value("@css:.a@text||@css:.b@text", html))
    }

    /** `@js:` 里的 `||` 是逻辑或，**不能**被当成规则分隔符切开。 */
    @Test
    fun `logical or inside js rule is not treated as rule separator`() {
        val html = """<html><body><p>x</p></body></html>"""
        val runner = RuleRunner()

        // 不切分 ⇒ 求值 '' || 'fallback' ⇒ "fallback"；若被切开，前半段求值为空、后半段当选择器 ⇒ 取不到
        assertEquals("fallback", NodeValue.document(html).value("@js:'' || 'fallback'", runner.jsSandbox, html, "https://x.example/"))
    }

    /**
     * 选择器里被引号包起来的 `||` 属于**规则内容**，切开后前后两段都会变成非法规则。
     * `splitAlternatives()` 因此必须做引号（以及 `##正则##` 段）感知，而不是无脑 split。
     */
    @Test
    fun `quoted pipes inside a selector are not split`() {
        val html = """<html><body><span data-t="a||b">命中</span></body></html>"""

        assertEquals("命中", value("""@css:.missing@text||@css:[data-t="a||b"]@text""", html))
    }

    // ------------------------------------------------------------------
    // 2. 多类名 `class.a b`
    // ------------------------------------------------------------------

    /**
     * `class.col-md-6 text-center`：写规则的人要的是「同时具备两个类」。
     * 旧实现走 CSS 选择器 `.col-md-6 text-center`（后代选择器，语法上等于 `.col-md-6` 内的 text-center 标签），
     * 恒返回空集 —— 真实书源上表现为「目录 0 章」。
     */
    @Test
    fun `multi class selector requires both classes`() {
        val html = """
            <html><body>
              <div class="col-md-6 text-center"><a href="/both.html">命中</a></div>
              <div class="col-md-6"><a href="/only-one.html">不该命中</a></div>
            </body></html>
        """.trimIndent()

        assertEquals("/both.html", value("class.col-md-6 text-center@a@href", html))
    }

    // ------------------------------------------------------------------
    // 3. 负数索引
    // ------------------------------------------------------------------

    /** `a.-1` 是「最后一个 a」，旧实现被 `coerceAtLeast(0)` 折成 `a.0`，永远取第一个。 */
    @Test
    fun `negative index picks from the end of the tag list`() {
        val html = """
            <html><body>
              <a href="/1.html">一</a><a href="/2.html">二</a><a href="/3.html">末</a>
            </body></html>
        """.trimIndent()

        assertEquals("/3.html", value("a.-1@href", html))
        assertEquals("/1.html", value("a.0@href", html))
    }

    /** `class.xxx.-1` 同样从末尾取，用于「目录最后一条」「分页组最后一个」这类规则。 */
    @Test
    fun `negative index works on class selector too`() {
        val html = """
            <html><body>
              <li class="ch"><a href="/c1.html">第1章</a></li>
              <li class="ch"><a href="/c2.html">第2章</a></li>
              <li class="ch"><a href="/c3.html">第3章</a></li>
            </body></html>
        """.trimIndent()

        assertEquals("/c3.html", value("class.ch.-1@a@href", html))
    }

    // ------------------------------------------------------------------
    // 4. 文本选择器取祖先属性
    // ------------------------------------------------------------------

    /**
     * `text.下一页@href` 命中的常是内层元素：`<a href><button>下一页</button></a>`。
     * 按钮自身没有 `href`，属性在祖先 `<a>` 上，必须向上找（最多 4 层）。
     */
    @Test
    fun `text selector reads the attribute from the nearest ancestor`() {
        val html = """
            <html><body>
              <div class="page"><a href="/p2.html"><button>下一页</button></a></div>
            </body></html>
        """.trimIndent()

        assertEquals("/p2.html", value("text.下一页@href", html))
    }

    /** 文本就在 `<a>` 自身时照旧直取，不受祖先查找影响。 */
    @Test
    fun `text selector uses own attribute when present`() {
        val html = """<html><body><div class="page"><a href="/p3.html">下一页</a></div></body></html>"""

        assertEquals("/p3.html", value("text.下一页@href", html))
    }

    private fun value(rule: String, html: String): String? = NodeValue.document(html).value(rule)
}
