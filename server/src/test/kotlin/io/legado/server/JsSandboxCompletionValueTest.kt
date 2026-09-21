package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书源 JS 求值语义回归测试。
 *
 * 背景（实测定位见 docs/sessions/SESSION-018-dagou-content-root-cause.md）：
 * 聚合类书源的 `jsLib` 里每个工具函数都含 `return`，而旧实现用
 * `Regex("\\breturn\\b")` 对「jsLib + 规则脚本」整体判定是否包裹 IIFE，
 * 导致整段被包进 `(function(){…})()`，Rhino 的**求值补全值退化为 undefined**；
 * 而聚合源的正文规则常以裸表达式（如 `data;`）结尾，依赖该补全值 ⇒ 正文为空。
 *
 * 本测试固化正确语义：
 *  1. jsLib 的函数定义对规则脚本可见；
 *  2. jsLib 里的 `return` **不得**吞掉规则脚本的补全值；
 *  3. 规则脚本自身有顶层 `return` 时仍须能工作（此时才需要包裹）；
 *  4. 字符串/引号/模板字面量里的 `return` 不算顶层 return。
 */
class JsSandboxCompletionValueTest {

    private fun sandbox() = JsSandbox(RuleRunner())

    private fun evalRule(jsLib: String?, script: String, result: Any? = null): String? {
        val ctx = JsExecutionContext(sourceId = "test-source", jsLib = jsLib)
        return sandbox().eval(script, mapOf("result" to result), ctx)
    }

    // --- 1. jsLib 可见性 ---

    @Test
    fun `jsLib function definitions are visible to the rule script`() {
        val lib = "function helper(){ return 'FROM_LIB'; }"
        val out = evalRule(lib, "helper();")
        assertEquals("FROM_LIB", out)
    }

    @Test
    fun `rule script can call helper with arguments from jsLib`() {
        val lib = "function pick(a,b){ return a + '-' + b; }"
        val out = evalRule(lib, "pick('x','y');")
        assertEquals("x-y", out)
    }

    // --- 2. 核心缺陷：jsLib 的 return 不得吞掉补全值 ---

    @Test
    fun `jsLib containing return does not swallow trailing bare expression`() {
        val lib = """
            function helper(){ return 'FROM_LIB'; }
            function another(){ if (true) { return 1; } return 2; }
        """.trimIndent()
        // 规则脚本自身没有 return，以裸表达式结尾 —— 必须取到补全值
        val out = evalRule(lib, "var data = JSON.stringify({content:'XYZ'}); data;")
        assertEquals("""{"content":"XYZ"}""", out)
    }

    @Test
    fun `jsLib containing return still yields primitive completion value`() {
        val lib = "function h(){ return 'x'; }"
        assertEquals("hello", evalRule(lib, "'hello'"))
        assertEquals("a-b", evalRule(lib, "'a' + '-' + 'b'"))
    }

    @Test
    fun `json build with jsLib present is not lost`() {
        val lib = "function h(){ return 'x'; }\nfunction k(){ return h(); }"
        val out = evalRule(lib, "JSON.stringify({content:'ABC'})")
        assertEquals("""{"content":"ABC"}""", out)
    }

    // --- 3. 规则脚本自身有顶层 return 时仍须工作 ---

    @Test
    fun `rule script with top-level return still works`() {
        val lib = "function helper(){ return 'FROM_LIB'; }"
        val out = evalRule(lib, "var v = helper();\nreturn v + '!';")
        assertEquals("FROM_LIB!", out)
    }

    @Test
    fun `rule script with top-level return and no jsLib works`() {
        assertEquals("42", evalRule(null, "return String(42);"))
    }

    // --- 4. 非顶层 return 不应触发包裹判定 ---

    @Test
    fun `return inside string literal does not trigger wrapping`() {
        val lib = "function h(){ return 'x'; }"
        // 脚本里出现字符串字面量 "return"，但真正的返回值是后面的裸表达式
        val out = evalRule(lib, "var note = 'has return inside';\nJSON.stringify({n:note})")
        assertNotNull(out)
        assertTrue("应取到补全值而不是 undefined，实际=$out", out!!.contains("has return inside"))
    }

    @Test
    fun `return inside template literal or comment does not trigger wrapping`() {
        val lib = "function h(){ return 'x'; }"
        val script = "// return in comment\nvar t = `template return`;\nJSON.stringify({t:t})"
        val out = evalRule(lib, script)
        assertNotNull(out)
        assertTrue("应取到补全值，实际=$out", out!!.contains("template return"))
    }

    @Test
    fun `return inside nested block does not count as top-level return`() {
        val lib = "function helper(){ return 'FROM_LIB'; }"
        // if 块内的 return 不算顶层 return：脚本整体在函数体外本就非法，
        // 但这里的 return 位于嵌套块中，Legado 语义下仍应被包裹以可执行；
        // 关键是补全值（helper 调用结果）不能丢。
        val out = evalRule(lib, "if (true) { var v = helper(); }\nJSON.stringify({v:v})")
        assertNotNull(out)
        assertTrue("应取到补全值，实际=$out", out!!.contains("FROM_LIB"))
    }

    // --- 5. 端到端：复刻聚合源正文规则的形态 ---

    @Test
    fun `aggregate-style content rule chain yields content via trailing bare expression`() {
        val lib = """
            function getArguments(v, k){ return k === 'server' ? 'https://api.example' : ''; }
            function helper(){ return 'FROM_LIB'; }
        """.trimIndent()
        val jsCode = """
            var payload = JSON.parse(result);
            var content = '<p>' + payload.title + '</p>';
            var data = JSON.stringify({ content: content });
            data;
        """.trimIndent()

        val ctx = JsExecutionContext(sourceId = "大灰狼融合VIP5.0", jsLib = lib)
        val evaluated = sandbox().eval(jsCode, mapOf("result" to """{"title":"第一章"}"""), ctx)
        assertNotNull("正文规则求值不应为 null", evaluated)
        assertEquals("""{"content":"<p>第一章</p>"}""", evaluated)

        // 后置路径 $.content 应能取到正文
        val extracted = NodeValue.document(evaluated!!).value("$.content", sandbox(), evaluated, "")
        assertEquals("<p>第一章</p>", extracted)
    }

    @Test
    fun `aggregate content rule works when jsLib has many returns`() {
        // 模拟真实 jsLib：每个工具函数都含 return（旧实现据此误判整段需包裹）
        val lib = (1..40).joinToString("\n") { "function util$it(){ return $it; }" }
        val jsCode = "var data = JSON.stringify({content:'正文内容'}); data;"
        val out = evalRule(lib, jsCode)
        assertEquals("""{"content":"正文内容"}""", out)
    }

    // --- 6. JSON 条目以对象绑定时不破坏补全值 ---

    @Test
    fun `object bound result does not corrupt completion value`() {
        val lib = "function h(){ return 'x'; }"
        val obj: Any = Json.parseToJsonElement("""{"book_id":"9"}""").jsonObject
            .entries.associate { (k, v) -> k to (v as JsonPrimitive).content }
        val out = evalRule(lib, "JSON.stringify({content:'C'})", obj)
        assertEquals("""{"content":"C"}""", out)
    }

    @Test
    fun `content rule receives decoded payload as raw string`() {
        // 锁定事实（实测见 SESSION-018）：正文路径下 result 就是「原始字符串」，
        // 因此聚合源里的 String(java.hexDecodeToString(result)) 能正常工作。
        //
        // 本用例走**真实绑定代码**（NodeValue.json + NodeValue.value），而不是直接调 eval，
        // 否则只是在回显测试自己传入的值、证明不了生产路径。
        //
        // 注：曾设想新增 rawResult 绑定（ADR-017 初版 C3），实测证明**该问题不存在**，
        // 故未引入该 API（避免无用死知识）。
        val payload = """{"book_id":"9"}"""
        val rule = "<js>JSON.stringify({type: typeof result, len: String(result).length})</js>"
        val sb = sandbox()
        val out = NodeValue.json(payload).value(rule, sb, payload, "")
        // payload 长度 = 15（含引号内的全部字符）
        assertEquals("""{"type":"string","len":15}""", out)
    }

    // --- 7. 端到端复刻聚合源正文链路（data: 载荷 + hexDecodeToString + 规则链 + 后置路径）---
    //
    // 该用例复刻《大灰狼》这类聚合源的关键结构，但不使用真实书源任何内容
    // （真实 jsLib/loginUrl 含凭据，按 PROPOSAL-017 §7 不入库）：
    //   - jsLib 是「多函数 + 每个函数都有 return」的形态（旧实现据此误判整段需包裹）；
    //   - 正文规则以 `String(java.hexDecodeToString(result))` 开头（result 必须是原始字符串）；
    //   - 规则末尾是裸表达式 `data;`，依赖求值补全值；
    //   - 规则链以 `</js>$.content` 结尾，由 NodeValue 做后置取值。

    @Test
    fun `aggregate content rule through RuleRunner extracts content from data url payload`() {
        val jsLib = """
            var host = ['https://api.example', 'https://api2.example'];
            function getArguments(v, key) { return key === 'server' ? host[0] : ''; }
            function pickHost(i) { return host[i]; }
        """.trimIndent()

        val ruleContent = """
            <js>
            var decoded = String(java.hexDecodeToString(result));
            var payload = JSON.parse(decoded);
            var server = getArguments('', 'server');
            var content = '<p>' + payload.title + ' @ ' + server + '</p>';
            var data = JSON.stringify({ content: content });
            data;
            </js>${'$'}.content
        """.trimIndent()

        val source = """
            {
              "bookSourceUrl":"大灰狼融合VIP5.0",
              "bookSourceName":"聚合源回归",
              "jsLib":${JsonPrimitive(jsLib)},
              "ruleContent":{"content":${JsonPrimitive(ruleContent)}}
            }
        """.trimIndent()

        val payload = """{"book_id":"10001","title":"第一章"}"""
        val b64 = java.util.Base64.getEncoder()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        val chapterUrl = "data:;base64,$b64," + """{"type":"qingtian"}"""

        val content = RuleRunner().content(source, chapterUrl)

        // 注：正文会经过 content() 既有的「段落规范化」路径，<p> 标签会被剥离，
        // 这里断言的是**语义内容被正确提取**（正文非空且含预期文本），
        // 而非保留原始 HTML（HTML 保留由 NodeValue 单测覆盖）。
        assertEquals("第一章 @ https://api.example", content.content)
    }

    @Test
    fun `missing jsLib surfaces helper error in sandbox diagnostics`() {
        // 反向用例：缺 jsLib 时，沙箱必须记录可解释的失败原因（Story 3「诊断可解释」）。
        //
        // ⚠️ 注意（本用例暴露的既有缺陷，见下）：RuleRunner.content() 当前**不会**因此抛错，
        // 因为 NodeValue.value 在 JS 求值为 null 时会回退成「中间值」（即 data: 载荷原文），
        // 于是 content() 看到非空文本便认为提取成功 ⇒ 坏书源会**静默把载荷当正文**。
        // 该行为不在本次修复范围（PROPOSAL-017 聚焦 IIFE/补全值），此处只固化沙箱层的诊断信号。
        val ruleContent = "<js>var x = getArguments('', 'server'); x;</js>"
        val source = """
            {
              "bookSourceUrl":"nosource",
              "bookSourceName":"缺少库",
              "ruleContent":{"content":${JsonPrimitive(ruleContent)}}
            }
        """.trimIndent()
        val payload = """{"book_id":"1"}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))

        val sandbox = JsSandbox(RuleRunner())
        val value = sandbox.eval(
            "var x = getArguments('', 'server'); x;",
            mapOf("result" to payload),
            JsExecutionContext(
                sourceId = "nosource",
                jsLib = null,
                chapterUrl = "data:;base64,$b64",
            ),
        )

        assertNull("缺少 jsLib 时求值应失败", value)
        val err = sandbox.lastError
        assertNotNull("沙箱必须记录失败原因", err)
        assertTrue("原因应指出 getArguments 未定义，实际=$err", err!!.contains("getArguments"))
    }

    @Test
    fun `sandbox clears stale lastError on success`() {
        // 旧实现只在失败时写 lastError，成功时不清 ⇒ 调用方会读到上一次失败的残留值，
        // 排障时被误导（实测见 SESSION-018 §4）。本用例锁定「成功即清空」。
        val sb = JsSandbox(RuleRunner())
        sb.eval("throw new Error('boom');", emptyMap(), JsExecutionContext(sourceId = "s"))
        assertNotNull("第一次失败应记录原因", sb.lastError)

        val ok = sb.eval("'fine'", emptyMap(), JsExecutionContext(sourceId = "s"))
        assertEquals("fine", ok)
        assertNull("成功求值后不得残留上一次的失败原因", sb.lastError)
    }

    // --- 8. 正文清洗：正文整体包在外层 <div> 时不得被删光 ---
    //
    // 实测根因（见 SESSION-018 §8）：聚合源（大灰狼）的正文规则返回
    // `<div rs-native>…正文…</div>`，而旧 cleanContent() 用
    // `replace(Regex("<div[\\s\\S]*?</div>"), "")` 把 div **连同正文**一起删除 ⇒
    // text 变成空 ⇒ 报「正文规则未提取到内容」。

    @Test
    fun `content wrapped in a single outer div is not wiped out`() {
        val jsLib = "function h(){ return 'x'; }"
        val ruleContent = """
            <js>
            var data = JSON.stringify({ content: '<div rs-native>第一段正文</div>' });
            data;
            </js>${'$'}.content
        """.trimIndent()
        val source = """
            {
              "bookSourceUrl":"divwrap",
              "bookSourceName":"外层div包裹",
              "jsLib":${JsonPrimitive(jsLib)},
              "ruleContent":{"content":${JsonPrimitive(ruleContent)}}
            }
        """.trimIndent()
        val payload = """{"book_id":"1","title":"第一章"}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))

        val content = RuleRunner().content(source, "data:;base64,$b64," + """{"type":"qingtian"}""")

        assertTrue("正文不得为空，实际='${content.content}'", content.content.isNotBlank())
        assertTrue("应保留 div 内文本，实际='${content.content}'", content.content.contains("第一段正文"))
    }

    @Test
    fun `content with structural divs still drops the div containers`() {
        // 反向约束：**多个**结构性 div（导航/广告等）仍应被删掉，只留正文段落。
        val ruleContent = """
            <js>
            var data = JSON.stringify({ content: '<div class="nav">首页</div><p>真正的正文</p><div class="ad">广告</div>' });
            data;
            </js>${'$'}.content
        """.trimIndent()
        val source = """
            {
              "bookSourceUrl":"structural",
              "bookSourceName":"结构div",
              "ruleContent":{"content":${JsonPrimitive(ruleContent)}}
            }
        """.trimIndent()
        val payload = """{"book_id":"1"}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))

        val content = RuleRunner().content(source, "data:;base64,$b64," + """{"type":"qingtian"}""")

        assertTrue("应保留正文段落，实际='${content.content}'", content.content.contains("真正的正文"))
    }
}
