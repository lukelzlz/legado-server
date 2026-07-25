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
}
