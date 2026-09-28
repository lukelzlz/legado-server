package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：Legado 规则兼容性（`header` 宽容解析 + 选择器翻译）。
 *
 * 全部用例都取自**用户真实书源**（SESSION-026 实测），不是虚构样例：
 * - `http://api.jmlldsc.com`（猫眼看书）：`header` 是单引号伪 JSON
 * - `https://www.yingsx.com`（影搜小说）：`chapterList = id.list@dd!0:1:2:...:8`
 *
 * 这两个缺陷的共同点都是**静默失败**（请求头被丢弃 / 选择器匹配 0 个），
 * 用户只看到「正文提取不到、目录空」，完全没有报错线索。
 */
class LegadoRuleCompatTest {

    // ------------------------------------------------------------------
    // 1. header 宽容解析
    // ------------------------------------------------------------------

    /**
     * 真实书源的 header 用**单引号**，不是合法 JSON。
     * 旧实现 strict parse 失败后被 runCatching 吞掉 ⇒ 所有头丢失 ⇒ 上游 403。
     */
    @Test
    fun `parses single-quoted pseudo-json header from real source`() {
        val raw = """
            {
            'User-Agent': 'okhttp/4.9.2','client-device': '0cdeb38dd0f2a381b06c0a02926ee317','client-brand': 'vivo','client-version': '2.3.0','client-name': 'app.maoyankanshu.novel','client-source': 'android','Authorization': 'bearereyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.abc.def'
            }
        """.trimIndent()

        val headers = RuleRunner(responseFetcher = { "" }).parseHeaderMap(raw)

        assertEquals("应解析出 7 个请求头，实际：$headers", 7, headers.size)
        assertEquals("okhttp/4.9.2", headers["User-Agent"])
        assertEquals("0cdeb38dd0f2a381b06c0a02926ee317", headers["client-device"])
        assertEquals("vivo", headers["client-brand"])
        assertEquals("2.3.0", headers["client-version"])
        assertEquals("app.maoyankanshu.novel", headers["client-name"])
        assertEquals("android", headers["client-source"])
        assertTrue(
            "Authorization 必须完整保留（含 bearer 前缀与点号），实际：${headers["Authorization"]}",
            headers["Authorization"]?.startsWith("bearereyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9") == true,
        )
    }

    /** 标准 JSON 仍走原路径，不能被宽容模式破坏。 */
    @Test
    fun `still parses standard json header`() {
        val raw = """{"User-Agent":"Mozilla/5.0","Referer":"https://example.com/"}"""
        val headers = RuleRunner(responseFetcher = { "" }).parseHeaderMap(raw)
        assertEquals(2, headers.size)
        assertEquals("Mozilla/5.0", headers["User-Agent"])
        assertEquals("https://example.com/", headers["Referer"])
    }

    /** 空 / 畸形输入不得抛异常（否则会导致整条规则链失败）。 */
    @Test
    fun `malformed header does not throw`() {
        val runner = RuleRunner(responseFetcher = { "" })
        assertEquals(0, runner.parseHeaderMap("").size)
        assertEquals(0, runner.parseHeaderMap("{").size)
        assertEquals(0, runner.parseHeaderMap("}{").size)
        assertEquals(0, runner.parseHeaderMap("null").size)
    }

    /** 值里含冒号 / 逗号 / 中文时不能被截断。 */
    @Test
    fun `header values containing separators survive`() {
        val raw = """{ 'Referer': 'https://a.com/x?y=1,z=2', 'X-Note': '中文: 测试, ok' }"""
        val headers = RuleRunner(responseFetcher = { "" }).parseHeaderMap(raw)
        assertEquals("https://a.com/x?y=1,z=2", headers["Referer"])
        assertEquals("中文: 测试, ok", headers["X-Note"])
    }

    // ------------------------------------------------------------------
    // 2. 选择器翻译
    // ------------------------------------------------------------------

    /**
     * `id.xxx` 是 Legado 写法，**不是合法 CSS**。
     * 旧实现直接透传 ⇒ Jsoup 匹配 0 个 ⇒ 目录 0 章。
     */
    @Test
    fun `translates legado id selector to css id selector`() {
        assertEquals("#list", translateSelector("id.list"))
        assertEquals("#chapterlist", translateSelector("id.chapterlist"))
        assertEquals("#content", translateSelector("id.content"))
        assertEquals("#fmimg", translateSelector("id.fmimg"))
    }

    /** `tag.xxx` 的 `tag.` 只是标记，必须剥掉。 */
    @Test
    fun `translates legado tag selector`() {
        assertEquals("p", translateSelector("tag.p"))
        assertEquals("a", translateSelector("tag.a"))
        assertEquals("img", translateSelector("tag.img"))
    }

    /** 已经是合法 CSS 的一律原样保留（避免过度翻译）。 */
    @Test
    fun `leaves valid css untouched`() {
        assertEquals("#list", translateSelector("#list"))
        assertEquals(".booklist", translateSelector(".booklist"))
        assertEquals("dd", translateSelector("dd"))
        assertEquals("div.content", translateSelector("div.content"))
        assertEquals("", translateSelector(""))
    }

    // ------------------------------------------------------------------
    // 3. 端到端：真实目录页结构
    // ------------------------------------------------------------------

    /**
     * 用真实书源的 `chapterList = id.list@dd!0:1:2:...:8` 语义跑一遍
     * 「选择器 → 排除下标 → 取章节」的完整链路。
     *
     * 该页面 `#list` 下有 89 个 `<dd>`，排除 0..8 后应剩 80 章。
     */
    @Test
    fun `real chapterList rule yields chapters after translation and exclusion`() {
        val html = buildString {
            append("<html><body><div id=\"list\">")
            // 前 9 个是「最新章节」列表（规则要求排除）
            for (i in 0 until 9) append("<dd><a href=\"/book/head$i.html\">最新章节$i</a></dd>")
            // 正式目录
            for (i in 1..80) append("<dd><a href=\"/book/$i.html\">第${i}章 正文</a></dd>")
            append("</div></body></html>")
        }

        val runner = RuleRunner(responseFetcher = { html })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://example.com","ruleToc":{"chapterList":"id.list@dd!0:1:2:3:4:5:6:7:8","chapterName":"a@text","chapterUrl":"a@href"}}""",
            "https://example.com/book/1/",
        )

        assertEquals("应解析出 80 章（89 个 dd 排除前 9 个）", 80, chapters.size)
        assertEquals("第1章 正文", chapters.first().title)
        assertEquals("https://example.com/book/1.html", chapters.first().url)
        assertEquals("第80章 正文", chapters.last().title)
    }

    /** 单个 `!0` 排除语法同样要生效。 */
    @Test
    fun `single index exclusion still works`() {
        val html = """<html><body><div class="mulu"><ul>
            <li><a href="/skip.html">跳过</a></li>
            <li><a href="/1.html">第1章</a></li>
            <li><a href="/2.html">第2章</a></li>
        </ul></div></body></html>"""
        val runner = RuleRunner(responseFetcher = { html })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://example.com","ruleToc":{"chapterList":".mulu@ul@li!0","chapterName":"a@text","chapterUrl":"a@href"}}""",
            "https://example.com/book/1/",
        )
        assertEquals(2, chapters.size)
        assertEquals("第1章", chapters.first().title)
    }

    // ------------------------------------------------------------------
    // 4. `取值路径@js:代码` 后置处理（本次最隐蔽的缺陷）
    // ------------------------------------------------------------------

    /**
     * 真实书源 `api.jmlldsc.com` 的 chapterUrl：
     * `$.path@js:java.aesBase64DecodeToString(result,"f041c49714d39908",...)`
     *
     * 旧实现没有后置 JS 分支 ⇒ 整串丢给 JsonPath ⇒ 必然失败 ⇒ 返回 null
     * ⇒ chapters() 的 mapIndexedNotNull 丢掉**每一章** ⇒ 目录 0 章且**毫无报错**。
     */
    @Test
    fun `applies js post processor after json path`() {
        val payload = """{"data":{"list":[
            {"path":"ENC1","chapterName":"第1章"},
            {"path":"ENC2","chapterName":"第2章"}
        ]}}"""
        val runner = RuleRunner(responseFetcher = { payload })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://example.com","ruleToc":{"chapterList":"$.data.list[*]","chapterName":"$.chapterName","chapterUrl":"$.path@js:result + '-decoded'"}}""",
            "https://example.com/book/1/",
        )
        assertEquals("两章都应保留（不再因 JS 未执行而被丢弃）", 2, chapters.size)
        assertEquals("第1章", chapters.first().title)
        assertTrue(
            "chapterUrl 必须经过 @js: 后置处理，实际：${chapters.first().url}",
            chapters.first().url.contains("ENC1-decoded"),
        )
    }

    /** 无 `@js:` 时行为不变（避免过度处理）。 */
    @Test
    fun `plain json path is unaffected by post processor logic`() {
        val payload = """{"data":{"list":[{"path":"/c/1.html","chapterName":"第1章"}]}}"""
        val runner = RuleRunner(responseFetcher = { payload })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://example.com","ruleToc":{"chapterList":"$.data.list[*]","chapterName":"$.chapterName","chapterUrl":"$.path"}}""",
            "https://example.com/book/1/",
        )
        assertEquals(1, chapters.size)
        assertTrue(chapters.first().url.endsWith("/c/1.html"))
    }

    /** `@js:` 出现在字符串字面量里时不能被误切。 */
    @Test
    fun `js marker inside a string literal is not treated as post processor`() {
        val payload = """{"data":{"list":[{"path":"/x.html","chapterName":"第1章"}]}}"""
        val runner = RuleRunner(responseFetcher = { payload })
        val chapters = runner.chapters(
            """{"bookSourceUrl":"https://example.com","ruleToc":{"chapterList":"$.data.list[*]","chapterName":"$.chapterName","chapterUrl":"$.path@js:result.replace('@js:','')"}}""",
            "https://example.com/book/1/",
        )
        assertEquals(1, chapters.size)
        assertTrue("应正确执行后置 JS，实际：${chapters.first().url}", chapters.first().url.endsWith("/x.html"))
    }

    // ------------------------------------------------------------------
    // 5. AES 家族（真实书源用它解密目录地址）
    // ------------------------------------------------------------------

    /**
     * 复现真实密文：`api.jmlldsc.com` 的 `path` 字段经 AES/CBC/PKCS5Padding
     * 解密后应得到真实章节地址。
     *
     * key/iv 按 Legado 语义作为**原始 ASCII 字节**使用。
     */
    @Test
    fun `aesBase64DecodeToString decrypts real source payload`() {
        val sandbox = JsSandbox()
        val script = """
            java.aesBase64DecodeToString(
              "UhQTfQq/qXGCKPd5D+cjxC4sd87pdnKDbxbyPlxw4P6gDuA9w6Zb05a4CxFL+DxF",
              "f041c49714d39908",
              "AES/CBC/PKCS5Padding",
              "0123456789abcdef"
            );
        """.trimIndent()
        val result = sandbox.eval(script, emptyMap(), JsExecutionContext(sourceId = "https://t.example"))
        assertEquals(
            "应解出真实章节地址",
            "http://api.lemiyigou.com/697/697604/75510.json",
            result,
        )
    }

    /** 解密失败必须抛错（不能静默返回空串，否则又会变成「目录 0 章」这种无提示故障）。 */
    @Test
    fun `aes decode failure surfaces an error`() {
        val sandbox = JsSandbox()
        sandbox.eval(
            """java.aesBase64DecodeToString("not-valid-base64!!!","k","AES/CBC/PKCS5Padding","0123456789abcdef");""",
            emptyMap(),
            JsExecutionContext(sourceId = "https://t.example"),
        )
        assertTrue(
            "应记录失败原因，实际：${sandbox.lastError}",
            sandbox.lastError?.contains("aesBase64DecodeToString") == true,
        )
    }

    /** 新增的辅助 API 必须存在于沙箱（缺失会导致依赖它们的书源整条规则失败）。 */
    @Test
    fun `newly added sandbox apis are present`() {
        val sandbox = JsSandbox()
        val script = """
            [
              typeof java.aesBase64DecodeToString,
              typeof java.aesBase64EncodeToString,
              typeof java.createSymmetricCrypto,
              typeof java.toNumChapter,
              typeof java.t2s,
              typeof java.encodeURI,
              typeof java.connect,
              typeof java.getElement,
              typeof java.getElements
            ].join(',');
        """.trimIndent()
        val result = sandbox.eval(script, emptyMap(), JsExecutionContext(sourceId = "https://t.example"))
        assertEquals("function,function,function,function,function,function,function,function,function", result)
    }

    /** `toNumChapter` 要能吃「第123章」「123」「一百二十三」等形式。 */
    @Test
    fun `toNumChapter parses common chapter titles`() {
        val sandbox = JsSandbox()
        fun num(expr: String): String? =
            sandbox.eval("String(java.toNumChapter($expr));", emptyMap(), JsExecutionContext(sourceId = "https://t.example"))
        assertEquals("123", num("\"第123章 空屋\""))
        assertEquals("7", num("\"7\""))
        assertEquals("123", num("\"第一百二十三章\""))
        assertEquals("20", num("\"第二十章\""))
        assertEquals("11", num("\"第十一章\""))
    }

    /** `t2s` 繁体转简体，未收录字符原样保留。 */
    @Test
    fun `t2s converts common traditional characters`() {
        val sandbox = JsSandbox()
        val result = sandbox.eval("java.t2s('這個問題很難');", emptyMap(), JsExecutionContext(sourceId = "https://t.example"))
        assertEquals("这个问题很难", result)
    }
}
