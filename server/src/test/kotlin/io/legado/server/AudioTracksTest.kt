package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案 G2 · G1：`RuleRunner.audioTracks()` 与三种规则返回形态的拆解。
 *
 * 这里锁定的都是**静默失败**类的风险（清单空、多分段顺序乱、相对地址没补全、
 * `url,{"headers":…}` 的每段专属头被吃掉 ⇒ 重拉 403）——它们不会报错，
 * 只会让用户看到"这本书放不出来"或"放到第二段就断"。
 */
class AudioTracksTest {

    // ------------------------------------------------------------------
    // 1. splitAudioTracks / parseAudioTrackItem：四种来源形态
    // ------------------------------------------------------------------

    @Test
    fun `splits newline separated plain text urls`() {
        val raw = "https://a.example/1.mp3\nhttps://a.example/2.mp3\n\n  https://a.example/3.mp3  "

        val tracks = RuleRunner { "" }.splitAudioTracks(raw)

        assertEquals(3, tracks.size)
        assertEquals("https://a.example/1.mp3", tracks[0].url)
        assertEquals("https://a.example/3.mp3", tracks[2].url)
        assertNull("没有扩展头时 headersJson 必须是 null", tracks[0].headersJson)
    }

    @Test
    fun `splits json array keeping segment order`() {
        val raw = """["https://a.example/z.mp3","https://a.example/a.mp3","https://a.example/m.mp3"]"""

        val tracks = RuleRunner { "" }.splitAudioTracks(raw)

        assertEquals(
            "多分段必须**保序**（G-3：前端按 segmentIndex 串播）",
            listOf("https://a.example/z.mp3", "https://a.example/a.mp3", "https://a.example/m.mp3"),
            tracks.map { it.url },
        )
    }

    @Test
    fun `splits json array of objects with headers`() {
        val raw = """[{"url":"https://a.example/1.mp3","headers":{"Referer":"https://a.example/ch/1"}}]"""

        val tracks = RuleRunner { "" }.splitAudioTracks(raw)

        assertEquals(1, tracks.size)
        assertEquals("https://a.example/1.mp3", tracks[0].url)
        assertTrue("对象形态的 headers 必须被提取", tracks[0].headersJson?.contains("Referer") == true)
    }

    @Test
    fun `extracts audio urls from html fragment`() {
        val raw = """
            <div class="player">
              <audio src="/media/1.mp3"></audio>
              <audio><source src="/media/2.mp3"></audio>
            </div>
        """.trimIndent()

        val tracks = RuleRunner { "" }.splitAudioTracks(raw)

        assertEquals(listOf("/media/1.mp3", "/media/2.mp3"), tracks.map { it.url })
    }

    @Test
    fun `returns empty for blank input`() {
        assertTrue(RuleRunner { "" }.splitAudioTracks("").isEmpty())
        assertTrue(RuleRunner { "" }.splitAudioTracks("   \n  ").isEmpty())
        assertTrue(RuleRunner { "" }.splitAudioTracks(null).isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. 每段专属请求头（规格缺口 G-7 的第 2 处）
    // ------------------------------------------------------------------

    @Test
    fun `parses per segment headers in url comma json form`() {
        val item = """https://a.example/1.mp3,{"headers":{"Referer":"https://a.example/ch/1","User-Agent":"okhttp/4.9.2"}}"""

        val spec = RuleRunner { "" }.parseAudioTrackItem(item)

        assertEquals("https://a.example/1.mp3", spec!!.url)
        assertTrue("Referer 必须留在 headers_json 里", spec.headersJson!!.contains("https://a.example/ch/1"))
        assertTrue(spec.headersJson!!.contains("okhttp/4.9.2"))
    }

    @Test
    fun `parses percent encoded comma json form`() {
        // 上游/书源会自行把 `{` 编码成 %7B —— 两种写法都出现过，必须都认
        val item = """https://a.example/2.mp3,%7B%22headers%22:%7B%22Referer%22:%22https://a.example/ch/2%22%7D%7D"""

        val spec = RuleRunner { "" }.parseAudioTrackItem(item)

        assertEquals("https://a.example/2.mp3", spec!!.url)
    }

    @Test
    fun `keeps bare url when options are unparseable`() {
        // 认不出来就当作裸 URL：静默丢整条音频比"少带一个头"危险得多
        val spec = RuleRunner { "" }.parseAudioTrackItem("""https://a.example/3.mp3,{"headers":不是JSON}""")

        assertEquals("https://a.example/3.mp3", spec!!.url)
    }

    @Test
    fun `strips trailing separators from urls`() {
        assertEquals("https://a.example/1.mp3", RuleRunner { "" }.parseAudioTrackItem("https://a.example/1.mp3,")!!.url)
        assertEquals("https://a.example/1.mp3", RuleRunner { "" }.parseAudioTrackItem("  \"https://a.example/1.mp3\"  ")!!.url)
    }

    // ------------------------------------------------------------------
    // 3. audioTracks：端到端（注入响应，不触网）
    // ------------------------------------------------------------------

    private val sourceUrl = "https://audio.example.com"
    private val chapterUrl = "$sourceUrl/chapter/12"

    private fun audioSource(contentRule: String) = """
        {
          "bookSourceName": "测试有声书",
          "bookSourceUrl": "$sourceUrl",
          "bookSourceType": 1,
          "ruleContent": { "content": "$contentRule" }
        }
    """.trimIndent()

    @Test
    fun `resolves relative urls against the chapter page and keeps order`() {
        val html = """
            <html><body>
              <div class="player">
                <audio src="/media/1.mp3"></audio>
                <audio src="//cdn2.example.com/2.mp3"></audio>
                <audio src="https://cdn3.example.com/3.mp3"></audio>
              </div>
            </body></html>
        """.trimIndent()
        val runner = RuleRunner(responseFetcher = { html })

        val tracks = runner.audioTracks(audioSource("@css:.player@html"), chapterUrl)

        assertEquals(3, tracks.size)
        assertEquals("$sourceUrl/media/1.mp3", tracks[0].url)
        assertEquals("协议相对地址必须补全为 https", "https://cdn2.example.com/2.mp3", tracks[1].url)
        assertEquals("https://cdn3.example.com/3.mp3", tracks[2].url)
    }

    @Test
    fun `deduplicates while preserving the first position`() {
        val html = """
            <html><body>
              <div class="player">
                <audio src="/media/1.mp3"></audio>
                <audio src="/media/2.mp3"></audio>
                <audio src="/media/1.mp3"></audio>
              </div>
            </body></html>
        """.trimIndent()
        val runner = RuleRunner(responseFetcher = { html })

        val tracks = runner.audioTracks(audioSource("@css:.player@html"), chapterUrl)

        // 对外 key 是 `sha256(sourceId + url)`（表主键）：同一章出现两条相同地址只会互相覆盖
        assertEquals(listOf("$sourceUrl/media/1.mp3", "$sourceUrl/media/2.mp3"), tracks.map { it.url })
    }

    @Test
    fun `returns empty list when the rule yields nothing`() {
        val runner = RuleRunner(responseFetcher = { "<html><body><p>没有音频</p></body></html>" })

        val tracks = runner.audioTracks(audioSource("@css:.player@html"), chapterUrl)

        // 刻意**不抛异常**：让路由回 `state=empty` 的空清单，由前端给出明确提示
        //（抛 500 会把「规则没配好」伪装成「服务器故障」）。
        assertTrue("空清单必须是空列表而不是异常", tracks.isEmpty())
    }
}
