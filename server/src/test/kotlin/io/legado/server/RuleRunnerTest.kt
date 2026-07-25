package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRunnerTest {
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
}
