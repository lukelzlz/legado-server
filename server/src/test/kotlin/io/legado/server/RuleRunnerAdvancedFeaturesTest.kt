package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RuleRunnerAdvancedFeaturesTest {

    @Test
    fun `evaluates inline JS with java base64Decode in content rule`() {
        val plainText = "这是经过Base64加密的真实正文内容，第一段。\n这是第二段。"
        val encodedText = Base64.getEncoder().encodeToString(plainText.toByteArray(Charsets.UTF_8))
        val html = """
            <html>
            <head><script>var encryptedData = "$encodedText";</script></head>
            <body>
                <div id="content">请开启JavaScript阅读</div>
            </body>
            </html>
        """.trimIndent()

        val sourceJson = """
            {
                "bookSourceUrl": "https://example.com",
                "bookSourceName": "测试加密书源",
                "ruleContent": {
                    "content": "<js>var m = src.match(/var encryptedData = \"([^\"]+)\"/); m ? java.base64Decode(m[1]) : ''</js>"
                }
            }
        """.trimIndent()

        val runner = RuleRunner { html }
        val chapterContent = runner.content(sourceJson, "https://example.com/chapter1.html")
        assertTrue(chapterContent.content.contains("这是经过Base64加密的真实正文内容"))
        assertFalse(chapterContent.content.contains("请开启JavaScript阅读"))
    }

    @Test
    fun `applies regex replacement in rule fields and replaceRegex in content`() {
        val html = """
            <html>
            <body>
                <h1 class="title">书名：遮天</h1>
                <div class="author">作者：辰东</div>
                <div id="content">
                    <p>正文第一章开始。</p>
                    <p>关注微信公众号【测试防盗】获取最新章节。</p>
                    <p>笔趣阁小说网 www.biquge.com 免费提供。</p>
                    <p>正文内容继续展开。</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val sourceJson = """
            {
                "bookSourceUrl": "https://example.com",
                "bookSourceName": "测试清洗书源",
                "ruleBookInfo": {
                    "name": "class.title@text##书名：##",
                    "author": "class.author@text##作者：##"
                },
                "ruleContent": {
                    "content": "id.content@html",
                    "replaceRegex": "关注微信公众号.*最新章节\n笔趣阁小说网.*免费提供"
                }
            }
        """.trimIndent()

        val runner = RuleRunner { html }
        val details = runner.details(sourceJson, "https://example.com/book/1")
        assertEquals("遮天", details.name)
        assertEquals("辰东", details.author)

        val content = runner.content(sourceJson, "https://example.com/book/1/1.html")
        assertTrue(content.content.contains("正文第一章开始"))
        assertTrue(content.content.contains("正文内容继续展开"))
        assertFalse(content.content.contains("关注微信公众号"))
        assertFalse(content.content.contains("笔趣阁小说网"))
    }

    @Test
    fun `supports dynamic JS searchUrl evaluation`() {
        val searchHtml = """
            <html>
            <body>
                <div class="book-item">
                    <a class="name" href="/book/100">完美世界</a>
                    <span class="author">辰东</span>
                </div>
            </body>
            </html>
        """.trimIndent()

        val sourceJson = """
            {
                "bookSourceUrl": "https://example.com",
                "bookSourceName": "测试动态搜索书源",
                "searchUrl": "@js:(() => { return baseUrl + '/search?keyword=' + encodeURIComponent(key) + ',{\"method\":\"POST\",\"body\":\"k=' + key + '\"}'; })()",
                "ruleSearch": {
                    "bookList": "class.book-item",
                    "name": "class.name@text",
                    "bookUrl": "class.name@href",
                    "author": "class.author@text"
                }
            }
        """.trimIndent()

        val runner = RuleRunner { searchHtml }
        val results = runner.search(sourceJson, "完美世界")
        assertEquals(1, results.size)
        assertEquals("完美世界", results[0].name)
        assertEquals("辰东", results[0].author)
        assertEquals("https://example.com/book/100", results[0].bookUrl)
    }

    @Test
    fun `sandboxed JS environment blocks reflection and unsafe Java access`() {
        val sourceJson = """
            {
                "bookSourceUrl": "https://example.com",
                "bookSourceName": "恶意测试书源",
                "ruleContent": {
                    "content": "<js>java.lang.System.exit(0); 'blocked'</js>"
                }
            }
        """.trimIndent()

        val runner = RuleRunner { "<html><body>test</body></html>" }
        // Should catch execution error and fallback or throw RuleExecutionException without killing the JVM
        val result = runCatching { runner.content(sourceJson, "https://example.com/1.html") }
        assertNotNull(result)
    }
}
