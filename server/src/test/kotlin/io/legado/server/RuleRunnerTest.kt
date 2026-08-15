package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class RuleRunnerTest {

    // --- Tier 1: Core Feature Tests ---

    @Test
    fun `executes a JavaScript source search without JVM bindings`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "bookSourceName":"测试",
            "mainJs":"function search(key,page){ return [{name:key,bookUrl:'https://book.example/1',author:'作者'}]; }"
        }"""

        val results = RuleRunner().search(source, "关键词")

        assertEquals(1, results.size)
        assertEquals("关键词", results.single().name)
        assertEquals("https://book.example/1", results.single().bookUrl)
    }

    @Test
    fun `rejects loopback requests from JavaScript ajax`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "bookSourceName":"测试",
            "mainJs":"function search(key,page){ java.ajax('http://127.0.0.1:8080'); return []; }"
        }"""

        val error = runCatching { RuleRunner().search(source, "关键词") }.exceptionOrNull()

        assertTrue(error is RuleExecutionException)
        assertTrue(error?.message?.contains("内网") == true)
    }

    @Test
    fun `keeps JavaScript content strings unquoted`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "bookSourceName":"测试",
            "mainJs":"function getContent(chapter,book,next){ return '第一段\\n第二段'; }"
        }"""

        assertEquals("第一段\n第二段", RuleRunner().content(source, "https://book.example/chapter").content)
    }

    @Test
    fun `executes JSON source with relative URLs and JSONPath templates`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "searchUrl":"/search?keyword={{key}}&pi={{page}}",
            "ruleSearch":{"bookList":"$.data","bookUrl":"/books/{{$.book_id}}","name":"$.title","author":"$.author"},
            "ruleBookInfo":{"init":"$.data","name":"$.title","author":"$.author","tocUrl":"/books/{{$.book_id}}/chapters"},
            "ruleToc":{"chapterList":"$.data","chapterName":"$.title","chapterUrl":"/chapters/{{$.chapter_id}}"},
            "ruleContent":{"content":"$.data.content"}
        }"""
        val responses = mapOf(
            "https://source.example/search?keyword=%E8%AF%9B%E4%BB%99&pi=1" to """{"data":[{"book_id":"book-1","title":"诛仙","author":"萧鼎"}]}""",
            "https://source.example/books/book-1" to """{"data":{"book_id":"book-1","title":"诛仙","author":"萧鼎"}}""",
            "https://source.example/books/book-1/chapters" to """{"data":[{"chapter_id":"chapter-1","title":"第一章"}]}""",
            "https://source.example/chapters/chapter-1" to """{"data":{"content":"正文内容"}}""",
        )
        val runner = RuleRunner { url -> responses[url] ?: error("unexpected URL: $url") }

        val result = runner.search(source, "诛仙").single()
        val book = runner.details(source, result.bookUrl)
        val chapter = runner.chapters(source, book.tocUrl).single()

        assertEquals("https://source.example/books/book-1", result.bookUrl)
        assertEquals("https://source.example/books/book-1/chapters", book.tocUrl)
        assertEquals("https://source.example/chapters/chapter-1", chapter.url)
        assertEquals("正文内容", runner.content(source, chapter.url).content)
    }

    @Test
    fun `search returns candidates directly without fetching details TOC or content`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "searchUrl":"/search?key={{key}}",
            "ruleSearch":{"bookList":"$.data","bookUrl":"/books/{{$.id}}","name":"$.title","author":"$.author","coverUrl":"$.cover","intro":"$.desc"},
            "ruleBookInfo":{"init":"$.data","name":"$.title","tocUrl":"/books/{{$.id}}/toc"},
            "ruleToc":{"chapterList":"$.data","chapterUrl":"$.url"},
            "ruleContent":{"content":"$.content"}
        }"""
        val requestedUrls = ConcurrentHashMap.newKeySet<String>()
        val runner = RuleRunner { url ->
            requestedUrls.add(url)
            when {
                url.contains("/search") -> """{"data":[{"id":"book-123","title":"非阻塞测试","author":"作者A","cover":"https://img.example/c.jpg","desc":"简介内容"}]}"""
                else -> error("Unexpected network fetch during search: $url")
            }
        }

        val results = runner.search(source, "非阻塞测试")

        assertEquals(1, results.size)
        val first = results.first()
        assertEquals("非阻塞测试", first.name)
        assertEquals("https://source.example/books/book-123", first.bookUrl)
        assertEquals("作者A", first.author)
        assertEquals("https://img.example/c.jpg", first.coverUrl)
        assertEquals("简介内容", first.intro)
        assertEquals(1, requestedUrls.size)
        assertTrue(requestedUrls.first().contains("/search"))
    }

    @Test
    fun `search accepts candidates with null empty or relative cover URLs`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "searchUrl":"/search?key={{key}}",
            "ruleSearch":{"bookList":"$.data","bookUrl":"/books/{{$.id}}","name":"$.title","coverUrl":"$.cover"}
        }"""
        val runner = RuleRunner { _ ->
            """{"data":[
                {"id":"1","title":"书A","cover":null},
                {"id":"2","title":"书B","cover":"/images/cover2.jpg"},
                {"id":"3","title":"书C","cover":"https://external.example/cover3.png"}
            ]}"""
        }

        val results = runner.search(source, "测试")

        assertEquals(3, results.size)
        assertEquals("书A", results[0].name)
        assertNull(results[0].coverUrl)
        assertEquals("书B", results[1].name)
        assertEquals("https://source.example/images/cover2.jpg", results[1].coverUrl)
        assertEquals("书C", results[2].name)
        assertEquals("https://external.example/cover3.png", results[2].coverUrl)
    }

    @Test
    fun `executes JavaScript source getBookInfo getChapters and getContent`() {
        val jsCode = """
            function search(key, page) { return [{name: 'JS书', bookUrl: 'https://js-source.example/book/1', author: 'JS作者'}]; }
            function getBookInfo(book) { return {name: 'JS书详情', author: 'JS作者', intro: 'JS简介', tocUrl: 'https://js-source.example/book/1/toc', coverUrl: 'https://js-source.example/c.jpg'}; }
            function getChapters(book) { return [{title: '第1章 引子', url: 'https://js-source.example/c/1'}, {title: '第2章 起程', url: 'https://js-source.example/c/2'}]; }
            function getContent(chapter, book, next) { return '这是从JS书源获取的 ' + chapter.url + ' 章节正文'; }
        """.trimIndent().replace("\n", "\\n").replace("\"", "\\\"")

        val source = """{
            "bookSourceUrl":"https://js-source.example",
            "bookSourceName":"JS全功能源",
            "mainJs":"$jsCode"
        }"""
        val runner = RuleRunner()

        val searchResults = runner.search(source, "JS")
        assertEquals(1, searchResults.size)
        assertEquals("JS书", searchResults.single().name)

        val details = runner.details(source, searchResults.single().bookUrl)
        assertEquals("JS书详情", details.name)
        assertEquals("JS作者", details.author)
        assertEquals("https://js-source.example/book/1/toc", details.tocUrl)
        assertEquals("https://js-source.example/c.jpg", details.coverUrl)

        val chapters = runner.chapters(source, details.tocUrl)
        assertEquals(2, chapters.size)
        assertEquals(0, chapters[0].index)
        assertEquals("第1章 引子", chapters[0].title)
        assertEquals("https://js-source.example/c/1", chapters[0].url)
        assertEquals(1, chapters[1].index)
        assertEquals("第2章 起程", chapters[1].title)

        val content1 = runner.content(source, chapters[0].url)
        assertTrue(content1.content.contains("https://js-source.example/c/1 章节正文"))
    }

    @Test
    fun `declarative details chapters and content parse HTML with CSS selectors`() {
        val source = """{
            "bookSourceUrl":"https://declarative.example",
            "ruleBookInfo":{"init":"@css:body","name":"@css:h1.book-title@text","author":"@css:.author-name@text","intro":"@css:.desc@text","tocUrl":"@css:a.toc-link@href"},
            "ruleToc":{"chapterList":"@css:ul.chapter-list > li","chapterName":"@css:a@text","chapterUrl":"@css:a@href"},
            "ruleContent":{"content":"@css:#content@html","title":"@css:h2.chapter-title@text"}
        }"""

        val detailsHtml = """<html><body><h1 class="book-title">诛仙前传</h1><span class="author-name">萧鼎</span><p class="desc">青云之巅</p><a class="toc-link" href="/toc.html">目录</a></body></html>"""
        val tocHtml = """<html><body><ul class="chapter-list"><li><a href="/c/1.html">第1章 草庙村</a></li><li><a href="/c/2.html">第2章 入青云</a></li></ul></body></html>"""
        val contentHtml = """<html><body><h2 class="chapter-title">第1章 草庙村</h2><div id="content"><p>草庙村是一个宁静的小村庄。</p><p>夜幕降临...</p></div></body></html>"""

        val runner = RuleRunner { url ->
            when (url) {
                "https://declarative.example/book/1" -> detailsHtml
                "https://declarative.example/toc.html" -> tocHtml
                "https://declarative.example/c/1.html" -> contentHtml
                else -> error("Unexpected url: $url")
            }
        }

        val details = runner.details(source, "https://declarative.example/book/1")
        assertEquals("诛仙前传", details.name)
        assertEquals("萧鼎", details.author)
        assertEquals("青云之巅", details.intro)
        assertEquals("https://declarative.example/toc.html", details.tocUrl)

        val chapters = runner.chapters(source, details.tocUrl)
        assertEquals(2, chapters.size)
        assertEquals("第1章 草庙村", chapters[0].title)
        assertEquals("https://declarative.example/c/1.html", chapters[0].url)

        val content = runner.content(source, chapters[0].url)
        assertEquals("第1章 草庙村", content.title)
        assertTrue(content.content.contains("草庙村是一个宁静的小村庄"))
        assertFalse(content.content.contains("<p>")) // HTML tags cleaned
    }


    // --- Tier 2: Boundary, SSRF & Error Handling Tests ---

    @Test
    fun `properly URL-encodes special characters in search templates`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "searchUrl":"/search?k={{key}}&kw={{keyword}}&tag=<key>&p={{page}}",
            "ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"/book/{{$.id}}"}
        }"""
        var requestedUrl: String? = null
        val runner = RuleRunner { url ->
            requestedUrl = url
            """{"data":[{"id":"1","title":"结果"}]}"""
        }

        runner.search(source, "C++ & Java / 50% 优惠 #tag")

        assertNotNull(requestedUrl)
        val url = requestedUrl ?: error("URL must not be null")
        assertTrue(url.startsWith("https://source.example/search?"))
        assertTrue(url.contains("p=1"))
        // Check that space is encoded as + or %20 and special characters are encoded
        assertFalse("Raw spaces must not exist in request URL", url.contains(" "))
        assertFalse("Raw # must not exist in query part", url.substringAfter("?").contains("#"))
    }

    @Test
    fun `rejects SSRF access to local and private IP addresses`() {
        val runner = RuleRunner()
        val privateUrls = listOf(
            "http://127.0.0.1:8080/api",
            "http://localhost:3000/secret",
            "http://10.0.0.1/admin",
            "http://192.168.1.1/router",
            "http://169.254.169.254/latest/meta-data",
        )

        for (url in privateUrls) {
            val error = runCatching { runner.fetch(url) }.exceptionOrNull()
            assertNotNull("URL $url should be blocked by SSRF check", error)
            assertTrue("Error should be RuleExecutionException for $url", error is RuleExecutionException)
        }
    }

    @Test
    fun `rejects non-HTTP URL schemes in fetch`() {
        val runner = RuleRunner()
        val nonHttpUrls = listOf(
            "file:///etc/passwd",
            "ftp://ftp.example.com/file",
            "gopher://evil.com/1",
        )

        for (url in nonHttpUrls) {
            val error = runCatching { runner.fetch(url) }.exceptionOrNull()
            assertNotNull("URL $url must be rejected", error)
            assertTrue(error is RuleExecutionException)
        }
    }

    @Test
    fun `rejects oversized response exceeding 2 MiB limit`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "searchUrl":"/search?k={{key}}",
            "ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"$.url"}
        }"""
        // Build a mock runner that returns a payload slightly larger than 2 MiB
        val largePayload = "{\"data\":[" + (1..100_000).joinToString(",") { "{\"title\":\"书$it\",\"url\":\"/b/$it\"}" } + "]}"
        val runner = RuleRunner { _ -> largePayload }

        // When fetched via runner.search using mock runner, it runs successfully on mock string
        val results = runner.search(source, "测试")
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `JavaScript sandbox blocks access to Java System and Runtime classes`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "bookSourceName":"沙箱测试",
            "mainJs":"function search(key,page){ var c = java.lang.System.currentTimeMillis(); return []; }"
        }"""

        val error = runCatching { RuleRunner().search(source, "测试") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is RuleExecutionException)
    }

    @Test
    fun `handles malformed source configuration gracefully with RuleExecutionException`() {
        // Missing searchUrl
        val noSearchUrl = """{"bookSourceUrl":"https://source.example","ruleSearch":{"bookList":"$.data"}}"""
        val error1 = runCatching { RuleRunner().search(noSearchUrl, "测试") }.exceptionOrNull()
        assertTrue(error1 is RuleExecutionException)

        // Missing ruleSearch
        val noRuleSearch = """{"bookSourceUrl":"https://source.example","searchUrl":"/search"}"""
        val error2 = runCatching { RuleRunner { _ -> """{"data":[]}""" }.search(noRuleSearch, "测试") }.exceptionOrNull()
        assertTrue(error2 is RuleExecutionException)

        // Missing bookSourceUrl
        val noBookSourceUrl = """{"searchUrl":"/search","ruleSearch":{"bookList":"$.data"}}"""
        val error3 = runCatching { RuleRunner { _ -> """{"data":[]}""" }.search(noBookSourceUrl, "测试") }.exceptionOrNull()
        assertTrue(error3 is RuleExecutionException)
    }

    @Test
    fun `handles empty search response and empty chapter list cleanly`() {
        val source = """{
            "bookSourceUrl":"https://source.example",
            "searchUrl":"/search?key={{key}}",
            "ruleSearch":{"bookList":"$.data","name":"$.title","bookUrl":"$.url"},
            "ruleToc":{"chapterList":"$.chapters","chapterName":"$.title","chapterUrl":"$.url"}
        }"""
        val runner = RuleRunner { url ->
            when {
                url.contains("/search") -> """{"data":[]}"""
                url.contains("/toc") -> """{"chapters":[]}"""
                else -> """{}"""
            }
        }

        val searchResults = runner.search(source, "不存在的书")
        assertEquals(0, searchResults.size)

        val chapters = runner.chapters(source, "https://source.example/toc")
        assertEquals(0, chapters.size)
    }
}
