package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files

/**
 * 内置 WebView 登录：HTML/CSS 重写、域名白名单与 Cookie 解析的回归测试。
 */
class WebViewProxyTest {

    private val base = URI("https://example.com/dir/page.html")

    private fun tempDatabase(): Database {
        val path = Files.createTempFile("legado-webview-test", ".sqlite").toString()
        return Database(path).also { it.initialize("admin123") }
    }

    @Test
    fun `rewriteHtml rewrites links forms and subresources onto proxy paths`() {
        val html = """
            <html><head><title>t</title></head><body>
              <a href="/login">登录</a>
              <a href="javascript:void(0)">js</a>
              <a href="#anchor">锚点</a>
              <form action="/do/login" method="post"><input name="u"></form>
              <img src="cover.png">
              <script src="/static/app.js"></script>
              <link rel="stylesheet" href="/static/app.css">
            </body></html>
        """.trimIndent()

        val out = WebViewProxy.rewriteHtml(html, base, "src-1", "tok")

        // 导航与表单 -> page / submit
        assertTrue(out.contains("/api/sources/src-1/browser/page"))
        assertTrue(out.contains("/api/sources/src-1/browser/submit"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Flogin"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fdo%2Flogin"))
        // 子资源 -> res
        assertTrue(out.contains("/api/sources/src-1/browser/res"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fdir%2Fcover.png"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fstatic%2Fapp.js"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fstatic%2Fapp.css"))
        // 非 http(s) 协议必须原样保留
        assertTrue(out.contains("javascript:void(0)"))
        assertTrue(out.contains("#anchor"))
    }

    @Test
    fun `rewriteHtml strips CSP integrity and base tags`() {
        val html = """
            <html><head>
              <base href="https://cdn.other.com/">
              <meta http-equiv="Content-Security-Policy" content="default-src 'self'">
              <script src="/a.js" integrity="sha384-abc" crossorigin="anonymous"></script>
            </head><body><iframe src="/frame" sandbox="allow-scripts"></iframe></body></html>
        """.trimIndent()

        val out = WebViewProxy.rewriteHtml(html, base, "src-1", "tok")

        assertFalse(out.contains("Content-Security-Policy"))
        assertFalse(out.contains("integrity="))
        assertFalse(out.contains("crossorigin"))
        assertFalse(out.contains("<base"))
        // 嵌套 iframe 的 sandbox 必须摘掉，否则内层页面脚本无法运行
        assertFalse(out.contains("allow-scripts"))
    }

    @Test
    fun `rewriteHtml injects bootstrap that neutralizes frame busting and reports navigation`() {
        val out = WebViewProxy.rewriteHtml("<html><body>hi</body></html>", base, "src-1", "tok")

        assertTrue(out.contains("data-legado-proxy"))
        assertTrue(out.contains("Object.defineProperty(window,'top'"))
        assertTrue(out.contains("Object.defineProperty(window,'parent'"))
        assertTrue(out.contains("postMessage"))
        assertTrue(out.contains("legado-webview"))
        // BASE 必须是目标站点地址，而不是代理地址，否则页面内相对路径会解析错
        assertTrue(out.contains("https://example.com/dir/page.html"))
        // 引导脚本同样是 raw text，& 不能被实体化
        assertTrue(out.contains("?t='+encodeURIComponent(TOKEN)+'&u="))
    }

    @Test
    fun `rewriteHtml rewrites srcset and inline styles`() {
        val html = """
            <html><body>
              <img srcset="a.png 1x, b.png 2x" src="c.png">
              <div style="background-image:url('/bg/x.png')"></div>
              <style>.hero{background:url(hero.png)}</style>
            </body></html>
        """.trimIndent()

        val out = WebViewProxy.rewriteHtml(html, base, "src-1", "tok")

        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fdir%2Fa.png"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fdir%2Fb.png"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fbg%2Fx.png"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fdir%2Fhero.png"))
        assertTrue(out.contains("1x"))
        assertTrue(out.contains("2x"))
        // <style> 是 raw text 节点：代理 URL 里的 & 必须原样保留，否则整条地址失效
        //（内联 style 属性走正常 HTML 转义，浏览器会解码，不受此约束）
        val styleBlock = out.substringAfter("<style>").substringBefore("</style>")
        assertTrue(styleBlock.contains("res?t=tok&u="))
        assertFalse(styleBlock.contains("&amp;"))
    }

    @Test
    fun `rewriteCss rewrites url and import references`() {
        val css = "body{background:url('/img/a.png')} @import \"theme.css\"; .x{background:url(\"https://cdn.example.net/z.png\")}"

        val out = WebViewProxy.rewriteCss(css, base, "src-1", "tok")

        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fimg%2Fa.png"))
        assertTrue(out.contains("https%3A%2F%2Fexample.com%2Fdir%2Ftheme.css"))
        assertTrue(out.contains("https%3A%2F%2Fcdn.example.net%2Fz.png"))
        assertTrue(out.contains("/api/sources/src-1/browser/res"))
    }

    @Test
    fun `registrableDomain keeps multi part TLDs intact`() {
        assertEquals("example.com", WebViewProxy.registrableDomain("www.example.com"))
        assertEquals("example.com", WebViewProxy.registrableDomain("a.b.example.com"))
        assertEquals("example.com", WebViewProxy.registrableDomain("example.com"))
        assertEquals("example.com.cn", WebViewProxy.registrableDomain("www.example.com.cn"))
        assertEquals("example.co.jp", WebViewProxy.registrableDomain("shop.example.co.jp"))
        assertEquals("localhost", WebViewProxy.registrableDomain("localhost"))
        assertEquals("example.com", WebViewProxy.registrableDomain("WWW.Example.COM."))
    }

    @Test
    fun `allowedDomains collects every host referenced by the source`() {
        val sourceJson = """
            {"bookSourceUrl":"https://www.example.com","loginUrl":"https://passport.example.org/login",
             "searchUrl":"https://cdn.other.net/search?q={{key}}","bookSourceName":"测试源"}
        """.trimIndent()

        val domains = WebViewProxy.allowedDomains(sourceJson)

        assertTrue(domains.contains("example.com"))
        assertTrue(domains.contains("example.org"))
        assertTrue(domains.contains("other.net"))
    }

    @Test
    fun `allowedDomains tolerates custom non url bookSourceUrl`() {
        // 书源生态中存在以自定义中文标识充当 bookSourceUrl 的聚合源，不能因此崩溃
        val sourceJson = """{"bookSourceUrl":"大灰狼融合VIP5.0","loginUrl":"https://www.reader.com/login"}"""

        val domains = WebViewProxy.allowedDomains(sourceJson)

        assertEquals(setOf("reader.com"), domains)
    }

    @Test
    fun `extractCookiePair drops Set-Cookie attributes`() {
        assertEquals("session=abc", tempDatabase().extractCookiePair("session=abc; Path=/; HttpOnly; SameSite=Lax"))
        assertEquals("token=xyz", tempDatabase().extractCookiePair("token=xyz"))
        assertNull(tempDatabase().extractCookiePair("justtext"))
        assertNull(tempDatabase().extractCookiePair(""))
    }

    @Test
    fun `setSourceCookieFromSetCookie stores only the name value pair`() {
        val db = tempDatabase()

        db.setSourceCookieFromSetCookie("s1", "https://example.com/chapter/1", "sid=42; Path=/; HttpOnly; Max-Age=3600")

        val jar = db.getSourceCookieJar("s1")
        assertEquals("sid=42", jar["example.com"])
        // 属性绝不能被当成 Cookie 发送出去
        val header = db.getSourceCookie("s1", "https://example.com/chapter/2")
        assertEquals("sid=42", header)
    }

    @Test
    fun `defaultStartUrl picks the first real url so aggregate sources still open`() {
        // bookSourceUrl 是自定义标识、loginUrl 是整段 JS 时，仍要能给出一个可用入口
        val sourceJson = """{"bookSourceUrl":"大灰狼融合VIP5.0","loginUrl":"function jc(){java.startBrowserAwait('data:text/html;base64,x')}"}"""

        assertEquals("https://fanqienovel.com/", WebViewProxy.defaultStartUrl("""{"loginUrl":"see https://fanqienovel.com/ here"}"""))
        val fallback = WebViewProxy.defaultStartUrl(sourceJson)
        assertTrue(fallback == null || fallback.startsWith("http"))
    }

    @Test
    fun `registerInlinePage decodes data url and serves a rewritten page`() {
        val db = tempDatabase()
        val proxy = WebViewProxy(db)
        val token = proxy.issueTicket("s1", """{"bookSourceUrl":"https://example.com"}""", "https://example.com/")
        val html = """<html><head><title>使用教程</title></head><body><a href="https://example.com/x">l</a></body></html>"""
        val base64 = java.util.Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))

        assertTrue(proxy.isInlineDataUrl("data:text/html;base64,$base64"))
        assertFalse(proxy.isInlineDataUrl("https://example.com/"))

        val key = proxy.registerInlinePage("s1", token, "data:text/html;base64,$base64")
        val payload = proxy.openInlinePage("s1", token, key)
        val out = String(payload.body, Charsets.UTF_8)

        assertEquals(200, payload.status)
        assertTrue(out.contains("使用教程"))
        // 页面中的绝对地址必须被改写到代理路径上
        assertTrue(out.contains("/api/sources/s1/browser/page"))
        assertTrue(out.contains("data-legado-proxy"))
    }

    @Test
    fun `registerInlinePage rejects non data urls`() {
        val proxy = WebViewProxy(tempDatabase())
        val token = proxy.issueTicket("s1", """{"bookSourceUrl":"https://example.com"}""")

        val error = runCatching { proxy.registerInlinePage("s1", token, "https://example.com/") }.exceptionOrNull()
        assertTrue(error is WebViewException)
    }

    @Test
    fun `getSourceCookie prefers exact host and falls back to parent domain`() {
        val db = tempDatabase()
        db.setSourceCookie("s1", "https://example.com/", "root=1")
        db.setSourceCookie("s1", "https://www.example.com/", "leaf=2")

        // 精确域名命中，同时合并父域 Cookie，同名时以更精确的为准
        val wwwHeader = db.getSourceCookie("s1", "https://www.example.com/book/1")
        assertTrue(wwwHeader!!.contains("root=1"))
        assertTrue(wwwHeader.contains("leaf=2"))

        // 父域本身只拿到自己的 Cookie
        assertEquals("root=1", db.getSourceCookie("s1", "https://example.com/book/1"))

        // 无关域名不得命中（旧实现用子串模糊匹配，会把 www.example.com 误配给 example.com.evil.net）
        assertNull(db.getSourceCookie("s1", "https://example.com.evil.net/book/1"))
        assertNull(db.getSourceCookie("s1", "https://notexample.com/book/1"))
    }
}
