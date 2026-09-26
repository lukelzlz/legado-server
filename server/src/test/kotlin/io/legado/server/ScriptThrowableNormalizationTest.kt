package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * 回归测试：JS 桥接函数抛出受检异常时，不得让 Rhino 抛
 * `class javax.net.ssl.SSLHandshakeException cannot be cast to class java.lang.Error`。
 *
 * ## 用户可复现现象
 * 大灰狼聚合书源点击登录按钮 → 前端红色 toast：
 * `书源脚本执行失败：class javax.net.ssl.SSLHandshakeException cannot be cast to
 *  class java.lang.Error (… are in module java.base of loader 'bootstrap')`
 *
 * ## 根因
 * Rhino 假定逃逸出脚本的 Java 异常是 `java.lang.Error` 系，对受检异常执行
 * `(Error)` 强转。`SSLHandshakeException extends SSLException extends IOException`
 * 是受检异常，被 `RuleRunner.fetchUrl` 捕获后原样重抛，冒泡到 Rhino 触发 CCE。
 *
 * ## 关键不变量
 * 1. **不抛 CCE**：异常以脚本可见形态出现，而非 Rhino 类型转换崩溃；
 * 2. **保留真实原因**：错误消息含原始异常类型名（否则用户无法排障）；
 * 3. **书源 catch 可捕获**（最重要）：聚合源靠 `try{...}catch` 做多节点轮询与
 *    主备线路回退；catch 抓不到则容错逻辑被整体击穿——本 bug 最隐蔽的危害。
 * 4. **RuleExecutionException 不被吞**：业务语义异常必须继续冒泡。
 */
class ScriptThrowableNormalizationTest {

    /**
     * 构造「`java.ajax` 必定抛指定异常」的沙箱 + 上下文。
     *
     * 注意：`JsSandbox` 必须持有 runner，否则 `java.ajax` 会走
     * `runner?.fetch(...) ?: ""` 的兜底分支返回空串而**根本不抛异常**。
     * 首版测试正是踩了这个坑，误报为 `no-throw`。
     */
    private fun sandboxThatThrows(error: Throwable): Pair<JsSandbox, JsExecutionContext> {
        val runner = RuleRunner(responseFetcher = { throw error })
        val sandbox = JsSandbox(runner)
        val context = JsExecutionContext(
            sourceId = "https://test-source.example",
            sourceName = "测试源",
            database = null,
        )
        return sandbox to context
    }

    private fun sslFailure(): Throwable =
        SSLHandshakeException("PKIX path building failed: unable to find valid certification path")

    // ---------------------------------------------------------------------
    // 1. 核心修复验证
    // ---------------------------------------------------------------------

    @Test
    fun `checked SSL exception is catchable by the source and no longer leaks as ClassCastException`() {
        val (sandbox, context) = sandboxThatThrows(sslFailure())
        val script = """
            var outcome = 'LEAKED';
            try {
              java.ajax('https://expired.badssl.com/');
              outcome = 'no-throw';
            } catch (e) {
              outcome = 'CAUGHT';
            }
            outcome;
        """.trimIndent()

        val result = sandbox.eval(script, emptyMap(), context)

        val error = sandbox.lastError
        if (error != null) {
            assertTrue(
                "不得再出现 Rhino 的类型转换崩溃，实际：$error",
                !error.contains("cannot be cast to class java.lang.Error"),
            )
        }
        assertEquals("书源的 try/catch 必须能捕获桥接异常", "CAUGHT", result)
    }

    @Test
    fun `normalized error message preserves the real underlying cause`() {
        val (sandbox, context) = sandboxThatThrows(sslFailure())
        val script = """
            var captured = '';
            try {
              java.ajax('https://expired.badssl.com/');
            } catch (e) {
              captured = String(e);
            }
            captured;
        """.trimIndent()

        val result = sandbox.eval(script, emptyMap(), context)

        assertNotNull("应当捕获到错误对象", result)
        assertTrue(
            "错误消息应包含原始异常类型名，实际：$result",
            result!!.contains("SSLHandshakeException"),
        )
    }

    @Test
    fun `source level primary fallback logic works again after normalization`() {
        // 模拟聚合源真实容错写法：主线路失败 → 回退备用线路。
        val (sandbox, context) = sandboxThatThrows(sslFailure())
        val script = """
            var tried = [];
            var body = '';
            try {
              body = java.ajax('https://primary.invalid/');
              tried.push('primary-ok');
            } catch (e) {
              tried.push('primary-failed');
              body = 'fallback-content';
            }
            tried.join(',') + '|' + body;
        """.trimIndent()

        val result = sandbox.eval(script, emptyMap(), context)

        assertEquals("主备线路回退必须恢复工作", "primary-failed|fallback-content", result)
    }

    // ---------------------------------------------------------------------
    // 2. 边界与回归：异常语义不得被改变
    // ---------------------------------------------------------------------

    @Test
    fun `RuleExecutionException still escapes so real config errors are not swallowed`() {
        val (sandbox, context) = sandboxThatThrows(RuleExecutionException("该书源未配置 searchUrl"))
        val script = """
            var outcome = 'NOT-CAUGHT';
            try {
              java.ajax('https://example.com/');
              outcome = 'no-throw';
            } catch (e) {
              outcome = 'SWALLOWED-BY-SOURCE';
            }
            outcome;
        """.trimIndent()

        sandbox.eval(script, emptyMap(), context)

        val error = sandbox.lastError
        assertNotNull("业务异常必须被记录而不是静默丢弃", error)
        assertTrue(
            "业务异常消息应如实上报，实际：$error",
            error!!.contains("未配置 searchUrl") || error.contains("RuleExecutionException"),
        )
    }

    @Test
    fun `plain runtime exception from bridge is catchable and keeps its message`() {
        val (sandbox, context) = sandboxThatThrows(IllegalStateException("上游返回 HTTP 503"))
        val script = """
            var captured = '';
            try {
              java.ajax('https://example.com/');
            } catch (e) {
              captured = String(e);
            }
            captured;
        """.trimIndent()

        val result = sandbox.eval(script, emptyMap(), context)

        assertNotNull("非受检异常也应可被捕获", result)
        assertTrue("应保留原始消息，实际：$result", result!!.contains("503"))
    }

    // ---------------------------------------------------------------------
    // 3. 暴力边界：异常类型矩阵 × 三个 HTTP 桥接方法
    // ---------------------------------------------------------------------

    @Test
    fun `broad matrix of throwable types never produces a ClassCastException`() {
        val throwables: List<Throwable> = listOf(
            SSLHandshakeException("证书链校验失败"),
            IOException("连接被重置"),
            java.net.UnknownHostException("域名不存在"),
            java.net.SocketTimeoutException("读取超时"),
            Exception("通用受检异常"),
            IllegalStateException("非法状态"),
            NullPointerException("空指针"),
            ArrayIndexOutOfBoundsException("数组越界"),
        )

        for (thrown in throwables) {
            val name = thrown.javaClass.simpleName
            val (sandbox, context) = sandboxThatThrows(thrown)

            val caughtAjax = sandbox.eval(
                "var o='LEAKED'; try { java.ajax('https://example.com/'); o='no-throw'; } catch (e) { o='CAUGHT'; } o;",
                emptyMap(),
                context,
            )
            val err = sandbox.lastError
            assertTrue(
                "抛 $name 时出现 Rhino CCE：$err",
                err == null || !err.contains("cannot be cast to class java.lang.Error"),
            )
            assertEquals("java.ajax 应可被捕获（$name）", "CAUGHT", caughtAjax)

            // java.post / java.get 走同一归一化路径，必须同样可捕获。
            val caughtPost = sandbox.eval(
                "var o='LEAKED'; try { java.post('https://example.com/', 'a=1', {Cookie:'k=v'}); o='no-throw'; } catch (e) { o='CAUGHT'; } o;",
                emptyMap(),
                context,
            )
            assertEquals("java.post 应可被捕获（$name）", "CAUGHT", caughtPost)

            val caughtGet = sandbox.eval(
                "var o='LEAKED'; try { java.get('https://example.com/'); o='no-throw'; } catch (e) { o='CAUGHT'; } o;",
                emptyMap(),
                context,
            )
            assertEquals("java.get 应可被捕获（$name）", "CAUGHT", caughtGet)
        }
    }
}
