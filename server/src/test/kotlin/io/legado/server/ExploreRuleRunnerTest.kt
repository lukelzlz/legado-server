package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreRuleRunnerTest {

    @Test
    fun `exploreCategories parses multiline text with double colons and ampersands`() {
        val source = """{
            "bookSourceUrl": "https://source.example",
            "bookSourceName": "测试文本发现源",
            "exploreUrl": "全本小说::/finish/{{page}}\n热榜小说&&/hot/{{page}}\n男生频道\n  玄幻::/sort/1_{{page}}\n  仙侠::/sort/2_{{page}}"
        }"""

        val categories = RuleRunner().exploreCategories(source)

        assertEquals(3, categories.size)
        assertEquals("全本小说", categories[0].title)
        assertEquals("/finish/{{page}}", categories[0].url)

        assertEquals("热榜小说", categories[1].title)
        assertEquals("/hot/{{page}}", categories[1].url)

        assertEquals("男生频道", categories[2].title)
        assertEquals(2, categories[2].subCategories.size)
        assertEquals("玄幻", categories[2].subCategories[0].title)
        assertEquals("/sort/1_{{page}}", categories[2].subCategories[0].url)
        assertEquals("仙侠", categories[2].subCategories[1].title)
        assertEquals("/sort/2_{{page}}", categories[2].subCategories[1].url)
    }

    @Test
    fun `exploreCategories parses JSON array with nested categories`() {
        val source = """{
            "bookSourceUrl": "https://source.example",
            "bookSourceName": "测试JSON发现源",
            "exploreUrl": "[{\"title\":\"总榜\",\"url\":\"https://source.example/rank/all/{{page}}\"},{\"title\":\"分类推荐\",\"url\":[{\"title\":\"都市\",\"url\":\"/sort/dushi/{{page}}\"},{\"title\":\"科幻\",\"url\":\"/sort/kehuan/{{page}}\"}]}]"
        }"""

        val categories = RuleRunner().exploreCategories(source)

        assertEquals(2, categories.size)
        assertEquals("总榜", categories[0].title)
        assertEquals("https://source.example/rank/all/{{page}}", categories[0].url)

        assertEquals("分类推荐", categories[1].title)
        assertEquals(2, categories[1].subCategories.size)
        assertEquals("都市", categories[1].subCategories[0].title)
        assertEquals("/sort/dushi/{{page}}", categories[1].subCategories[0].url)
        assertEquals("科幻", categories[1].subCategories[1].title)
        assertEquals("/sort/kehuan/{{page}}", categories[1].subCategories[1].url)
    }

    @Test
    fun `exploreCategories evaluates js script inside exploreUrl`() {
        val source = """{
            "bookSourceUrl": "https://source.example",
            "bookSourceName": "测试JS动态发现源",
            "exploreUrl": "<js>'热门::/hot/' + 1 + '\\n完本&&/finish/' + 2</js>"
        }"""

        val categories = RuleRunner().exploreCategories(source)

        assertEquals(2, categories.size)
        assertEquals("热门", categories[0].title)
        assertEquals("/hot/1", categories[0].url)
        assertEquals("完本", categories[1].title)
        assertEquals("/finish/2", categories[1].url)
    }

    @Test
    fun `exploreCategories returns empty list when exploreUrl is absent or blank`() {
        val sourceNoExplore = """{ "bookSourceUrl": "https://source.example", "bookSourceName": "无发现源" }"""
        assertTrue(RuleRunner().exploreCategories(sourceNoExplore).isEmpty())

        val sourceBlankExplore = """{ "bookSourceUrl": "https://source.example", "bookSourceName": "空发现源", "exploreUrl": "   " }"""
        assertTrue(RuleRunner().exploreCategories(sourceBlankExplore).isEmpty())
    }

    @Test
    fun `exploreBooks parses books using ruleExplore when configured`() {
        val html = """
            <html>
                <body>
                    <div class="explore-item">
                        <a class="book-name" href="/book/101">剑来</a>
                        <span class="book-author">烽火戏诸侯</span>
                        <img class="book-cover" src="/cover/101.jpg" />
                        <p class="book-intro">大千世界，无奇不有。</p>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val source = """{
            "bookSourceUrl": "https://source.example",
            "bookSourceName": "测试源",
            "exploreUrl": "热门::/explore/hot_{{page}}.html",
            "ruleExplore": {
                "bookList": ".explore-item",
                "name": ".book-name@text",
                "author": ".book-author@text",
                "bookUrl": ".book-name@href",
                "coverUrl": ".book-cover@src",
                "intro": ".book-intro@text"
            }
        }"""

        var requestedUrl = ""
        val runner = RuleRunner(responseFetcher = { url ->
            requestedUrl = url
            html
        })

        val books = runner.exploreBooks(source, "https://source.example/explore/hot_{{page}}.html", page = 2)

        assertEquals("https://source.example/explore/hot_2.html", requestedUrl)
        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("剑来", book.name)
        assertEquals("烽火戏诸侯", book.author)
        assertEquals("https://source.example/book/101", book.bookUrl)
        assertEquals("https://source.example/cover/101.jpg", book.coverUrl)
        assertEquals("大千世界，无奇不有。", book.intro)
    }

    @Test
    fun `exploreBooks falls back to ruleSearch when ruleExplore is missing`() {
        val html = """
            <html>
                <body>
                    <div class="search-item">
                        <a class="title" href="/book/202">雪中悍刀行</a>
                        <span class="author">烽火戏诸侯</span>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val source = """{
            "bookSourceUrl": "https://source.example",
            "bookSourceName": "测试无ruleExplore源",
            "exploreUrl": "热榜::/top",
            "ruleSearch": {
                "bookList": ".search-item",
                "name": ".title@text",
                "author": ".author@text",
                "bookUrl": ".title@href"
            }
        }"""

        val runner = RuleRunner(responseFetcher = { html })
        val books = runner.exploreBooks(source, "https://source.example/top", page = 1)

        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("雪中悍刀行", book.name)
        assertEquals("烽火戏诸侯", book.author)
        assertEquals("https://source.example/book/202", book.bookUrl)
    }
}
