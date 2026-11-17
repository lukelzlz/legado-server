package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回归测试：正文分页（`ruleContent.nextContentUrl`）。
 *
 * 上游旧实现只 fetch 一次章节页，`nextContentUrl` 字段**读了却从不使用**：
 * 凡是把一章拆成多页的站点（`xx.html` / `xx_2.html` / `?page=2`）都只能读到第一页，
 * 用户表现为「每章正文都断在半截」，而日志里没有任何报错。
 *
 * 这里全部用注入的 `responseFetcher` 离线造页，不打真实站点：
 * - 正常多页拼接
 * - 末页之后串进下一章（分页链接常与「下一章」同构）
 * - 分页链接指回自己 / 指回第一页（死循环）
 * - 章节地址自带 `,{...}` 选项后缀（POST 分页接口）
 * - 无 `nextContentUrl` 的书源行为不变（单次抓取）
 */
class ContentPaginationTest {

    // ------------------------------------------------------------------
    // 1. 多页拼接
    // ------------------------------------------------------------------

    /**
     * 两页正文按顺序拼接；`text.下一页@href` 是书源里最常见的分页写法，
     * 旧实现连这个选择器都不认（`text.XXX` 恒返回空），所以分页规则等于没写。
     */
    @Test
    fun `concatenates every page in order following nextContentUrl`() {
        val source = """
            {
              "bookSourceUrl":"https://paged.example",
              "bookSourceName":"分页源",
              "ruleContent":{
                "content":"@css:#content@text",
                "nextContentUrl":"text.下一页@href",
                "title":"@css:h1@text"
              }
            }
        """.trimIndent()

        val requested = mutableListOf<String>()
        val runner = RuleRunner { url ->
            requested += url
            when (url) {
                "https://paged.example/c/1.html" -> page("第一页开头", "https://paged.example/c/1_2.html")
                "https://paged.example/c/1_2.html" -> page("第二页结尾", null)
                else -> error("Unexpected url: $url")
            }
        }

        val result = runner.content(source, "https://paged.example/c/1.html")

        assertEquals(
            "两页都必须抓到，实际请求：$requested",
            listOf("https://paged.example/c/1.html", "https://paged.example/c/1_2.html"),
            requested,
        )
        assertTrue("缺第一页正文：${result.content}", result.content.contains("第一页开头"))
        assertTrue("缺第二页正文：${result.content}", result.content.contains("第二页结尾"))
        assertTrue(
            "拼接顺序必须是第一页在前：${result.content}",
            result.content.indexOf("第一页开头") < result.content.indexOf("第二页结尾"),
        )
        assertEquals("第1章 起点", result.title)
    }

    // ------------------------------------------------------------------
    // 2. 末页不得串进下一章
    // ------------------------------------------------------------------

    /**
     * 末页的分页区往往和「下一章」在同一块 DOM 里，选择器一命中就会把**下一章正文**拼进来。
     * 所以分页只允许在同一章节系列内推进（`100.html` / `100_2.html`），`101.html` 必须停。
     */
    @Test
    fun `stops at last page instead of swallowing the next chapter`() {
        val source = """
            {
              "bookSourceUrl":"https://paged.example",
              "bookSourceName":"分页源",
              "ruleContent":{"content":"@css:#content@text","nextContentUrl":"text.下一页@href"}
            }
        """.trimIndent()

        val requested = mutableListOf<String>()
        val runner = RuleRunner { url ->
            requested += url
            when (url) {
                "https://paged.example/b/100.html" ->
                    page("本章第一页", "https://paged.example/b/100_2.html")
                "https://paged.example/b/100_2.html" ->
                    page("本章第二页", "https://paged.example/b/101.html")
                "https://paged.example/b/101.html" ->
                    page("下一章正文不该出现", null)
                else -> error("Unexpected url: $url")
            }
        }

        val result = runner.content(source, "https://paged.example/b/100.html")

        assertEquals("只应抓本章的两页，实际：$requested", 2, requested.size)
        assertTrue(result.content.contains("本章第一页"))
        assertTrue(result.content.contains("本章第二页"))
        assertFalse("下一章正文被拼进来了：${result.content}", result.content.contains("下一章正文不该出现"))
    }

    // ------------------------------------------------------------------
    // 3. 死循环防护
    // ------------------------------------------------------------------

    /** 分页链接指回自己：必须只抓一次就收工，不能把上游打到熔断。 */
    @Test
    fun `self referencing next link does not loop`() {
        val source = """
            {
              "bookSourceUrl":"https://paged.example",
              "bookSourceName":"分页源",
              "ruleContent":{"content":"@css:#content@text","nextContentUrl":"text.下一页@href"}
            }
        """.trimIndent()

        val hits = AtomicInteger()
        val runner = RuleRunner { url ->
            hits.incrementAndGet()
            page("唯一一页", url) // 下一页永远指回自己
        }

        val result = runner.content(source, "https://paged.example/c/9.html")

        assertEquals("同一地址只能抓一次，实际：${hits.get()}", 1, hits.get())
        assertTrue(result.content.contains("唯一一页"))
    }

    /** A → B → A 的环：靠已访问集合截断，两页各抓一次即止。 */
    @Test
    fun `back and forth next links are visited once`() {
        val source = """
            {
              "bookSourceUrl":"https://paged.example",
              "bookSourceName":"分页源",
              "ruleContent":{"content":"@css:#content@text","nextContentUrl":".page a@href"}
            }
        """.trimIndent()

        val a = "https://paged.example/c/7.html"
        val b = "https://paged.example/c/7_2.html"
        val requested = mutableListOf<String>()
        val runner = RuleRunner { url ->
            requested += url
            when (url) {
                a -> page("A 页", b)
                b -> page("B 页", a)
                else -> error("Unexpected url: $url")
            }
        }

        val result = runner.content(source, a)

        assertEquals("A → B → A，只应各抓一次，实际：$requested", listOf(a, b), requested)
        assertTrue(result.content.contains("A 页"))
        assertTrue(result.content.contains("B 页"))
    }

    // ------------------------------------------------------------------
    // 4. 章节地址的 `,{...}` 选项后缀
    // ------------------------------------------------------------------

    /**
     * 部分书源把章节（以及分页）接口写成 `url,{"method":"POST","body":"..."}`。
     * 整串直接当 URL 用会抛 `Illegal character in path`（真实书源实测），
     * 必须拆出纯 URL 再请求，选项单独参与请求构造。
     */
    @Test
    fun `keeps url options out of the request url`() {
        val source = """
            {
              "bookSourceUrl":"https://paged.example",
              "bookSourceName":"POST 源",
              "ruleContent":{"content":"@css:#content@text"}
            }
        """.trimIndent()

        val requested = mutableListOf<String>()
        val runner = RuleRunner { url ->
            requested += url
            page("POST 接口正文", null)
        }

        val chapterUrl = """https://paged.example/api/chapter,{"method":"POST","body":"{\"id\":1}"}"""
        val result = runner.content(source, chapterUrl)

        assertEquals("请求地址必须是去掉选项的纯 URL，实际：$requested", listOf("https://paged.example/api/chapter"), requested)
        assertTrue(result.content.contains("POST 接口正文"))
    }

    // ------------------------------------------------------------------
    // 5. 无分页规则时行为不变
    // ------------------------------------------------------------------

    /** 没有 `nextContentUrl` 的书源只能抓一次 —— 防止「顺手给所有书源多发请求」。 */
    @Test
    fun `source without next rule still fetches exactly once`() {
        val source = """
            {
              "bookSourceUrl":"https://single.example",
              "bookSourceName":"单页源",
              "ruleContent":{"content":"@css:#content@text","title":"@css:h1@text"}
            }
        """.trimIndent()

        val hits = AtomicInteger()
        val runner = RuleRunner {
            hits.incrementAndGet()
            page("单页正文", "https://single.example/c/2.html")
        }

        val result = runner.content(source, "https://single.example/c/1.html")

        assertEquals(1, hits.get())
        assertTrue(result.content.contains("单页正文"))
        assertFalse(result.content.contains("下一页"))
    }

    // ------------------------------------------------------------------
    // 6. 标题与替换规则仍以第一页为准
    // ------------------------------------------------------------------

    /** `title` 只在首页求值一次，不能被后续页覆盖；`replaceRegex` 作用于拼接后的全文。 */
    @Test
    fun `title and replace regex follow the first page only`() {
        val source = """
            {
              "bookSourceUrl":"https://paged.example",
              "bookSourceName":"分页源",
              "ruleContent":{
                "content":"@css:#content@text",
                "nextContentUrl":"text.下一页@href",
                "title":"@css:h1@text",
                "replaceRegex":"广告位招租"
              }
            }
        """.trimIndent()

        val runner = RuleRunner { url ->
            when (url) {
                "https://paged.example/c/5.html" -> """
                    <html><body><h1>第5章 首页标题</h1>
                    <div id="content"><p>第一页 广告位招租</p></div>
                    <div class="page"><a href="https://paged.example/c/5_2.html">下一页</a></div>
                    </body></html>
                """.trimIndent()
                "https://paged.example/c/5_2.html" -> """
                    <html><body><h1>第5章 第二页标题</h1>
                    <div id="content"><p>第二页 广告位招租</p></div></body></html>
                """.trimIndent()
                else -> error("Unexpected url: $url")
            }
        }

        val result = runner.content(source, "https://paged.example/c/5.html")

        assertEquals("第5章 首页标题", result.title)
        assertTrue(result.content.contains("第一页"))
        assertTrue(result.content.contains("第二页"))
        assertFalse("替换规则应作用于全文：${result.content}", result.content.contains("广告位招租"))
    }

    /** 造一页正文：`#content` 里放两段，`next` 非空时追加「下一页」链接。 */
    private fun page(text: String, next: String?): String {
        val nav = next?.let { """<div class="page"><a href="$it">下一页</a></div>""" } ?: ""
        return """
            <html><body>
            <h1>第1章 起点</h1>
            <div id="content"><p>$text</p><p>这是第二段正文。</p></div>
            $nav
            </body></html>
        """.trimIndent()
    }
}
