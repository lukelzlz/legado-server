package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RSS 规则求值：用**真实源「大灰狼书荒广场」的规则串**（逐字取自真实备份 `rssSources.json`）
 * 配合**录制下来的上游响应**驱动整条链路。
 *
 * ## 为什么用录制响应而不是直连上游
 *
 * 该源的上游 `api.langge.cf` 已不可达（实测 DNS 被解析到 `198.18.0.88`，属 RFC 2544 基准测试网段，
 * 即被黑洞/污染），因此**无法**用活源做验收。这里改用 [RuleRunner] 的 `responseFetcher` 接缝
 * 注入同形响应 —— 这与书源侧 `RuleRunnerTest` / `ExploreRuleRunnerTest` 的做法完全一致，
 * 且能精确覆盖「规则求值」这条本次改动的主路径（网络层不是本次改动范围）。
 *
 * ## 本文件锁死的三件事
 *
 * 1. **`java.log` 必须回显入参**：`ruleArticles` 里是 `java.ajax(java.log(url))` 惯用法。
 *    旧实现返回 `undefined` ⇒ `ajax("undefined")` ⇒ 抛错被吞 ⇒ **0 条且无任何报错**
 *    （这正是本任务开工时定位到的根因，见 [JsSandbox] 里 `java.log` 的注释）。
 * 2. **JS 的 `baseUrl` 是「当前列表地址」**：规则里 `baseUrl.includes('番茄')` 而该源的
 *    `sortUrl` 是 `番茄::番茄`。传成 `sourceUrl`（`https://www.baidu.com/大灰狼番茄书荒广场`
 *    其实也含「番茄」，所以此处用「非番茄」的列表地址来反向验证）会静默走错分支。
 * 3. **`title` 为空 ⇒ 整条丢弃**，且 `ruleLink` 用 `sourceUrl` 作 base 绝对化。
 */
class RssRuleParserTest {

    /** 真实源的 `ruleArticles`（逐字，含 `java.ajax(java.log(...))` 与末段 `$.data…[*]` 取值路径）。 */
    private val ruleArticles = """
        <js>
        let res = {
          'data':{'cell_view':{'topic_data':
            [{'topic_desc':{'topic_title':'第一条书荒','topic_id':'topic-1','topic_cover':'./img/cover1.jpg'}},
             {'topic_desc':{'topic_title':'第二条书荒','topic_id':'topic-2','topic_cover':'https://cdn.example.com/c2.jpg'}},
             {'topic_desc':{'topic_title':'第三条书荒','topic_id':'topic-3'}}]}
          }
        }
        java.ajax(java.log('https://api.langge.cf/book_mall_y'))
        JSON.stringify(res)
        </js>$.data.cell_view.topic_data[*]
    """.trimIndent()

    /** 真实源的 `ruleLink`（`{{…}}` 模板 + 模板字符串 + `baseUrl` 分支）。 */
    private val ruleLink = """
        <js>
        let ruleUrl;
        if (baseUrl.includes('番茄')) {
        	ruleUrl = `https://reading.snssdk.com/wap/topic-share.html?topic_id={{${'$'}.topic_desc.topic_id}}`
        	} else {
        		ruleUrl = '{{${'$'}.topic_desc.topic_id}}';
        		}
        	ruleUrl
        </js>
    """.trimIndent()

    private val ruleTitle = "$.topic_desc.topic_title"
    private val ruleImage = "$.topic_desc.topic_cover"
    /** `路径@js:` 混合形态（真实数据同形：左边 JsonPath 取值作 `result` 交给右侧 JS）。 */
    private val rulePubDate = "$.topic_desc.topic_title\n@js:\n'2026-10-03 | ' + result"

    private fun sourceJson(
        // 列表地址**含「番茄」** ⇒ 规则的 if 分支（这正是真实数据的语义：
        // `baseUrl` 是列表地址，而 `sortUrl` 里带分类名「番茄」）
        sortUrl: String? = "番茄::https://list.example.com/番茄",
        extra: String = "",
    ): String = """
        {
          "sourceUrl": "https://www.baidu.com/大灰狼番茄书荒广场",
          "sourceName": "大灰狼书荒广场",
          "enabled": true,
          "sortUrl": ${if (sortUrl == null) "null" else jsonString(sortUrl)},
          "ruleArticles": ${jsonString(ruleArticles)},
          "ruleLink": ${jsonString(ruleLink)},
          "ruleTitle": ${jsonString(ruleTitle)},
          "ruleImage": ${jsonString(ruleImage)},
          "rulePubDate": ${jsonString(rulePubDate)}
          $extra
        }
    """.trimIndent()

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()

    @Test
    fun `真实源规则串必须抓出文章列表且逐字段正确`() {
        val requested = mutableListOf<String>()
        val runner = RuleRunner { url ->
            requested.add(url)
            // 真实源「大灰狼书荒广场」的 ruleArticles **自己**再发一次 ajax 去取 topic 列表，
            // 因此这里会被调用两次：① 列表地址 ② 规则内部那一次（其响应体被规则忽略，
            // 因为规则自己造了 res）。第二次能成功本身即证明 java.log 回显了 URL。
            """{"ok":true}"""
        }

        // 列表地址取自 sortUrl 的第一条分类（手机版按分类抓）
        val result = RssRuleParser.parse(sourceJson(), "", 1, runner)

        // ① java.log 回显 ⇒ java.ajax 收到的是**真实 URL** 而不是 "undefined"
        assertEquals("应发两次请求（列表地址 + 规则内 ajax）", 2, requested.size)
        assertEquals("https://list.example.com/番茄", requested[0])
        assertEquals("https://api.langge.cf/book_mall_y", requested[1])

        // ② 三篇文章都取到，且 title/link 非空
        assertEquals(3, result.articles.size)
        val first = result.articles[0]
        assertEquals("第一条书荒", first.title)
        // ③ baseUrl 含「番茄」⇒ 走番茄分支，并用 sourceUrl 作 base 绝对化
        assertEquals("https://reading.snssdk.com/wap/topic-share.html?topic_id=topic-1", first.link)
        // ④ image 是相对路径，用**列表地址**绝对化
        assertEquals("https://list.example.com/img/cover1.jpg", first.image)        // ⑤ 绝对 image 原样保留
        assertEquals("https://cdn.example.com/c2.jpg", result.articles[1].image)
        // ⑥ 无 image 的条目为 null，而不是空串
        assertNull(result.articles[2].image)
        // ⑦ 路径 + 后置 @js: 生效
        assertEquals("2026-10-03 | 第一条书荒", first.pubDate)
        assertFalse("不该走默认 XML 分支", result.fromDefaultXml)
    }

    /**
     * `baseUrl` 必须是**当前列表地址**：列表地址不含「番茄」时应走 else 分支。
     *
     * 这条专门反向验证 [RssRuleParser.parse] 传参。若误传 `sourceUrl`
     * （`…/大灰狼番茄书荒广场` 里含「番茄」），会恒走 if 分支而**看起来也"对"**，
     * 因此用「不含番茄的列表地址」才能把这个错误照出来。
     */
    @Test
    fun `baseUrl 必须取当前列表地址而不是 sourceUrl`() {
        val runner = RuleRunner { """{"ok":true}""" }

        val result = RssRuleParser.parse(sourceJson(sortUrl = "七猫::https://list.example.com/qimao"), "", 1, runner)

        assertEquals(3, result.articles.size)
        // else 分支：link 就是裸模板值，再以 sourceUrl 为 base 绝对化
        assertEquals("https://www.baidu.com/topic-1", result.articles[0].link)
    }

    /** `ruleArticles` 以 `-` 开头 ⇒ 求值后整表反转（手机版 `RssParserByRule.kt:50-53`）。 */
    @Test
    fun `列表规则以减号开头时必须反转`() {
        val runner = RuleRunner { """{"ok":true}""" }
        val reversedSource = sourceJson().replace(
            jsonString(ruleArticles),
            jsonString("-" + ruleArticles),
        )

        val result = RssRuleParser.parse(reversedSource, "", 1, runner)

        assertEquals(3, result.articles.size)
        assertEquals("第三条书荒", result.articles[0].title)
        assertEquals("第一条书荒", result.articles[2].title)
    }

    /**
     * 无列表规则的源走**默认 RSS/XML 解析**。
     *
     * 真实备份 8 条里有 7 条是这种（它们在手机版里只是「打开网页」的链接收藏），
     * 所以这条路径必须存在，但也**绝不能伪造数据**：解析不出条目就如实返回空表。
     */
    @Test
    fun `无规则源走默认 RSS 解析`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>示例订阅</title>
                <item>
                  <title>RSS 第一条</title>
                  <link>https://blog.example.com/posts/1</link>
                  <pubDate>Fri, 03 Oct 2026 10:00:00 GMT</pubDate>
                  <description>摘要一</description>
                  <enclosure url="https://blog.example.com/cover1.png" type="image/png"/>
                </item>
                <item>
                  <title>RSS 第二条</title>
                  <link>/posts/2</link>
                  <pubDate>Sat, 04 Oct 2026 10:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>没有链接的条目应被丢弃</title>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val runner = RuleRunner { rss }
        val source = """
            {"sourceUrl":"https://blog.example.com/feed.xml","sourceName":"示例订阅"}
        """.trimIndent()

        val result = RssRuleParser.parse(source, "", 1, runner)

        assertTrue("应走默认 XML 分支", result.fromDefaultXml)
        // 第 3 条没有 link ⇒ 丢弃，剩下 2 条
        assertEquals(2, result.articles.size)
        assertEquals("RSS 第一条", result.articles[0].title)
        assertEquals("https://blog.example.com/posts/1", result.articles[0].link)
        assertEquals("Fri, 03 Oct 2026 10:00:00 GMT", result.articles[0].pubDate)
        assertEquals("摘要一", result.articles[0].description)
        assertEquals("https://blog.example.com/cover1.png", result.articles[0].image)
        // 相对 link 用 sourceUrl 绝对化
        assertEquals("https://blog.example.com/posts/2", result.articles[1].link)
    }

    /** Atom 也要认（`feed > entry`、`link[href]`、`summary`）。 */
    @Test
    fun `无规则源也要认 Atom`() {
        val atom = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom 示例</title>
              <entry>
                <title>Atom 条目一</title>
                <link href="https://atom.example.com/1"/>
                <updated>2026-10-03T10:00:00Z</updated>
                <summary>摘要</summary>
              </entry>
            </feed>
        """.trimIndent()
        val runner = RuleRunner { atom }
        val source = """{"sourceUrl":"https://atom.example.com/feed","sourceName":"Atom"}"""

        val result = RssRuleParser.parse(source, "", 1, runner)

        assertEquals(1, result.articles.size)
        assertEquals("Atom 条目一", result.articles[0].title)
        assertEquals("https://atom.example.com/1", result.articles[0].link)
        assertEquals("2026-10-03T10:00:00Z", result.articles[0].pubDate)
    }

    /** 无规则 + 非 feed 内容（真实备份里的「使用说明」这类网页）⇒ 空表，且不抛错。 */
    @Test
    fun `无规则且不是 feed 时如实返回空表`() {
        val runner = RuleRunner { "<html><body><h1>使用说明</h1><p>这不是一个 feed</p></body></html>" }
        val source = """{"sourceUrl":"https://www.yuque.com/legado","sourceName":"使用说明"}"""

        val result = RssRuleParser.parse(source, "", 1, runner)

        assertTrue(result.fromDefaultXml)
        assertTrue("不该伪造任何文章", result.articles.isEmpty())
    }

    /**
     * 上游报错必须**向上抛出**，而不是被吞成「0 篇文章」。
     *
     * 这是本项目反复强调的「静默丢数据比抛错危险得多」：用户看到「0 篇」根本无法区分
     * 「源里确实没内容」与「上游 500 / 规则炸了」。
     */
    @Test
    fun `上游失败必须抛错而不是静默返回空表`() {
        val runner = RuleRunner { throw RuleExecutionException("上游返回 HTTP 503") }
        val source = sourceJson()

        val error = runCatching { RssRuleParser.parse(source, "", 1, runner) }.exceptionOrNull()

        assertTrue("应抛出异常，实际：" + error, error is RuleExecutionException)
        assertTrue(error!!.message!!.contains("503"))
    }

    /** `sortUrl` 的第一条分类是默认列表地址（手机版按分类抓）。 */
    @Test
    fun `默认列表地址取 sortUrl 的第一条分类`() {
        val requested = mutableListOf<String>()
        val runner = RuleRunner { url -> requested.add(url); """{"ok":true}""" }
        // sourceUrl 与 sortUrl 都非 http，就走 listUrl 本身（避免把相对地址喂给 parseUri）
        val source = """
            {"sourceUrl":"https://feed.example.com","sourceName":"S",
             "sortUrl":"科技::https://feed.example.com/tech\n财经::https://feed.example.com/fin"}
        """.trimIndent()

        RssRuleParser.parse(source, "", 1, runner)

        assertEquals("https://feed.example.com/tech", requested.single())
    }

    /** 缺 `sourceUrl` 必须明确报错，而不是造一个空主键的源。 */
    @Test
    fun `缺少 sourceUrl 必须报错`() {
        val runner = RuleRunner { "" }
        val error = runCatching { RssRuleParser.parse("""{"sourceName":"无地址"}""", "", 1, runner) }.exceptionOrNull()
        assertTrue(error is RuleExecutionException)
        assertTrue(error!!.message!!.contains("sourceUrl"))
    }

    /** 真实源的 `redirectPolicy` 是字符串枚举，宽容解析不能把它弄丢或当成整数。 */
    @Test
    fun `字符串枚举的 redirectPolicy 被正确解析`() {
        val decoded = RssSourceCodec.decode(
            """{"sourceUrl":"x","sourceName":"X","redirectPolicy":"BLOCK_CROSS_ORIGIN","enabled":"true"}""",
        )
        assertEquals("BLOCK_CROSS_ORIGIN", decoded.redirectPolicy)
        assertEquals(true, decoded.enabled)
        assertFalse(decoded.sourceUrl.isBlank())
    }

    // ==================================================================================
    // 以下两条是**沙箱语义**回归：它们是「真实 RSS 源静默 0 条」的直接根因，
    // 单独锁死，避免以后有人「顺手」把 java.log 改回不返回值。
    // ==================================================================================

    /**
     * `java.log(msg)` 必须**回显入参**（Legado `help/JsExtensions.kt` 的 `return msg`）。
     *
     * 生态惯用法 `java.ajax(java.log(url))` 把 log 当作「打印并透传」的管道。
     * 旧实现返回 `undefined` ⇒ 请求发到字面量 `"undefined"` ⇒ `parseUri` 抛错
     * ⇒ 被 `eval` 的 catch 吞掉 ⇒ 规则整体返回 null ⇒ **0 条且无任何报错**。
     */
    @Test
    fun `java_log 必须回显入参以支撑 ajax_log_url 惯用法`() {
        val requested = mutableListOf<String>()
        val runner = RuleRunner { url -> requested.add(url); "BODY" }

        // 单参形态：把 log 的返回值直接喂给 ajax
        val single = runner.jsSandbox.eval(
            "java.ajax(java.log('https://echo.example.com/one'))",
            emptyMap(),
            JsExecutionContext(sourceId = "t"),
        )
        assertEquals("BODY", single)
        assertEquals("https://echo.example.com/one", requested.last())

        // 双参形态 log(tag, msg)：记录 tag 但回显第二个参数
        requested.clear()
        val double = runner.jsSandbox.eval(
            "java.ajax(java.log('tag', 'https://echo.example.com/two'))",
            emptyMap(),
            JsExecutionContext(sourceId = "t"),
        )
        assertEquals("BODY", double)
        assertEquals("https://echo.example.com/two", requested.last())

        // 非字符串入参也必须原样透传（不能变成 "undefined"）
        assertEquals("42", runner.jsSandbox.eval("String(java.log(42))", emptyMap(), JsExecutionContext(sourceId = "t")))
    }

    /**
     * `cache` 桥必须可用（手机版 `AnalyzeRule.kt` 里 `bindings["cache"] = CacheManager`）。
     *
     * 真实数据直接依赖它：某源的 `sourceUrl` 是
     * `http@js:eval(String(cache.getFromMemory('yckdm')))`，而其 `header` 规则负责
     * `cache.putMemory('yckdm', …)` 预热 —— 缺这一层桥，这类源必然失效。
     */
    @Test
    fun `cache 桥支持内存与落库两种读写`() {
        val runner = RuleRunner { "BODY" }

        // putMemory 回显入参，getFromMemory 取回同样的字符串
        val roundTrip = runner.jsSandbox.eval(
            "cache.putMemory('k1','v1'); String(cache.getFromMemory('k1'))",
            emptyMap(),
            JsExecutionContext(sourceId = "cache-src"),
        )
        assertEquals("v1", roundTrip)

        // 结构化值：存入对象后取出应是**对象**（可继续点取属性）而不是一段文本
        val structured = runner.jsSandbox.eval(
            "cache.putMemory('k2', {a: 1}); String(cache.getFromMemory('k2').a)",
            emptyMap(),
            JsExecutionContext(sourceId = "cache-src"),
        )
        assertEquals("1 with raw=" + runner.jsSandbox.eval(
            "cache.putMemory('k2', {a: 1}); String(cache.getFromMemory('k2'))",
            emptyMap(),
            JsExecutionContext(sourceId = "cache-src"),
        ), "1", structured)

        // 未命中的键返回 undefined（而不是抛错），保证规则里的 try/catch 语义可预期
        val missing = runner.jsSandbox.eval(
            "typeof cache.getFromMemory('nope')",
            emptyMap(),
            JsExecutionContext(sourceId = "cache-src"),
        )
        assertEquals("undefined", missing)

        // 标量数字必须仍是字符串（刻意不做 JSON 还原，避免 typeof 漂移）
        val scalar = runner.jsSandbox.eval(
            "cache.putMemory('k3','42'); typeof cache.getFromMemory('k3')",
            emptyMap(),
            JsExecutionContext(sourceId = "cache-src"),
        )
        assertEquals("string", scalar)
    }

    /**
     * `cache.put`/`cache.get` 走**书源级 KV 落库**（与 `source.put`/`source.get` 同表），
     * 因此跨 eval（乃至跨进程）仍可读到 —— 这正是 `header` 规则预热、`sourceUrl` 消费的用法。
     */
    @Test
    fun `cache 落库读写可以跨 eval 读到`() {
        val dbPath = java.nio.file.Files.createTempFile("legado-rss-cache", ".sqlite").toString()
        var database: Database? = null
        try {
            database = Database(dbPath)
            database.initialize("password-for-test")
            val context = JsExecutionContext(sourceId = "kv-src", database = database)

            // 第一次 eval：写入
            RuleRunner(database).jsSandbox.eval("cache.put('token','abc')", emptyMap(), context)
            // 第二次 eval（全新沙箱实例）：读回 ⇒ 证明是落库而不是进程内内存
            val readBack = RuleRunner(database).jsSandbox.eval("String(cache.get('token'))", emptyMap(), context)
            assertEquals("abc", readBack)

            // 落库层的**结构化**值：回读后必须是**真正的 JS 对象**。
            // 这条锁住两类静默错值：① 只还原成 Java 壳时 `String(o)` 抛
            // `TypeError: 未找到对象默认值`；② 干脆没还原时 `.a` 恒为 undefined。
            RuleRunner(database).jsSandbox.eval("cache.put('obj', {a: {b: 'deep'}})", emptyMap(), context)
            val read = JsExecutionContext(sourceId = "kv-src", database = database)
            val readRunner = RuleRunner(database)

            assertEquals(
                "{\"a\":{\"b\":\"deep\"}}",
                readRunner.jsSandbox.eval("JSON.stringify(cache.get('obj'))", emptyMap(), read),
            )
            assertEquals("object", readRunner.jsSandbox.eval("typeof cache.get('obj')", emptyMap(), read))
            // 属性访问（原先恒 undefined 的那条）
            assertEquals("deep", readRunner.jsSandbox.eval("String(cache.get('obj').a.b)", emptyMap(), read))
            // JS 默认字符串转换（原先抛 TypeError 的那条）
            assertEquals(
                "[object Object]",
                readRunner.jsSandbox.eval("String(cache.get('obj'))", emptyMap(), read),
            )
            // 数组同样要能用
            RuleRunner(database).jsSandbox.eval("cache.put('arr', [1, 2, 3])", emptyMap(), context)
            assertEquals("3", readRunner.jsSandbox.eval("String(cache.get('arr').length)", emptyMap(), read))
            assertEquals("2", readRunner.jsSandbox.eval("String(cache.get('arr')[1])", emptyMap(), read))
        } finally {
            database?.close()
            listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach {
                runCatching { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(it)) }
            }
        }
    }
}
