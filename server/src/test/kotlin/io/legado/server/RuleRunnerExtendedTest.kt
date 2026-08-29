package io.legado.server

import org.junit.Assert.*
import org.junit.Test

class RuleRunnerExtendedTest {

    @Test
    fun `css selector translation for class and tag ordinals and negations`() {
        val html = """
            <div class="content">
                <div class="header">标题</div>
                <div class="item"><a href="/book/1">项目 1</a></div>
                <div class="item"><a href="/book/2">项目 2</a></div>
                <div class="item"><a href="/book/3">项目 3</a></div>
                <p class="intro">简介文字</p>
                <a class="chapter" href="/ch/1">第一章</a>
                <a class="chapter" href="/ch/2">第二章</a>
            </div>
        """.trimIndent()

        val sourceJson = """{
            "bookSourceUrl": "https://css.example",
            "searchUrl": "/search?k={{key}}",
            "ruleSearch": {
                "bookList": ".content .item",
                "name": "a@text",
                "bookUrl": "a@href"
            },
            "ruleBookInfo": {
                "name": "class.header@text",
                "intro": "p.intro@text",
                "tocUrl": "/toc"
            },
            "ruleToc": {
                "chapterList": "a.chapter",
                "chapterName": "text",
                "chapterUrl": "href"
            },
            "ruleContent": {
                "content": ".content@html"
            }
        }"""

        val runner = RuleRunner { _ -> html }
        val searchResults = runner.search(sourceJson, "测试")
        assertEquals(3, searchResults.size)
        assertEquals("项目 1", searchResults[0].name)
        assertEquals("https://css.example/book/1", searchResults[0].bookUrl)

        val details = runner.details(sourceJson, "https://css.example/book/1")
        assertEquals("标题", details.name)
        assertEquals("简介文字", details.intro)

        val chapters = runner.chapters(sourceJson, "https://css.example/toc")
        assertEquals(2, chapters.size)
        assertEquals("第一章", chapters[0].title)
        assertEquals("https://css.example/ch/1", chapters[0].url)
    }

    @Test
    fun `rule runner content fallback when extracted text is short but paragraph tags exist`() {
        val html = """
            <html>
            <body>
                <div id="content"></div>
                <p>第一段：天地初开，混沌未分。</p>
                <p>第二段：盘古生其中，万八千岁。</p>
                <p>第三段：天地开辟，阳清为天。</p>
                <p>第四段：阴浊为地，盘古在其中。</p>
                <p>第五段：一日九变，神于天，圣于地。</p>
                <p>按←键返回上一页</p>
            </body>
            </html>
        """.trimIndent()

        val sourceJson = """{
            "bookSourceUrl": "https://p.example",
            "ruleContent": {
                "content": "#content@text"
            }
        }"""

        val runner = RuleRunner { _ -> html }
        val content = runner.content(sourceJson, "https://p.example/c1")
        assertTrue(content.content.contains("第一段"))
        assertTrue(content.content.contains("第五段"))
        assertFalse(content.content.contains("按←键返回"))
    }

    @Test
    fun `js source runner with full lifecycle search, details, chapters, content`() {
        val jsSource = """{
            "bookSourceUrl": "https://js.example",
            "mainJs": "function search(k, p) { return [{ name: 'JS书名:' + k, author: 'JS作者', bookUrl: 'https://js.example/book/1', coverUrl: 'https://js.example/c.jpg', intro: 'JS简介' }]; }\nfunction getBookInfo(b) { return { name: 'JS书名', author: 'JS作者', intro: 'JS详细简介', tocUrl: 'https://js.example/toc' }; }\nfunction getChapters(t) { return [{ title: 'JS第一章', url: 'https://js.example/c1' }, { title: 'JS第二章', url: 'https://js.example/c2' }]; }\nfunction getContent(c, b, p) { return 'JS正文内容：大道至简。'; }"
        }"""

        val runner = RuleRunner()
        val results = runner.search(jsSource, "仙侠")
        assertEquals(1, results.size)
        assertEquals("JS书名:仙侠", results[0].name)

        val details = runner.details(jsSource, "https://js.example/book/1")
        assertEquals("JS书名", details.name)
        assertEquals("https://js.example/toc", details.tocUrl)

        val chapters = runner.chapters(jsSource, "https://js.example/toc")
        assertEquals(2, chapters.size)
        assertEquals("JS第一章", chapters[0].title)

        val content = runner.content(jsSource, "https://js.example/c1")
        assertEquals("JS正文内容：大道至简。", content.content)
    }
}
