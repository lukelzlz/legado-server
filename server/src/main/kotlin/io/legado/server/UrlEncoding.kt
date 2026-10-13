package io.legado.server

import java.net.URLEncoder

/**
 * 对 URL 中 RFC 3986 不允许的**字面字符**做百分号编码（中文、空格、`{ } " < > | \ ^ \`` 等）。
 *
 * ⚠️ 这是全项目**唯一**的实现，三个调用点必须共用：
 *
 * | 调用点 | 场景 |
 * | :--- | :--- |
 * | [RuleRunner] `parseUri` | 书源规则里的 URL |
 * | [WebViewProxy] `absolutize` | 内置浏览器代理改写页面里的链接 |
 * | [NetworkSourceImport] `download` | 网络导入的**重定向 `Location`** |
 *
 * 为什么必须宽容（实测，见 [SESSION-026] 与 [SESSION-037]）：Legado 生态把**未转义的 JSON**
 * 直接拼进 query（`&variable={"custom":""}`）是常态，Android 侧 OkHttp 会自动编码，
 * 而 `java.net.URI` 严格按 RFC 3986 抛 `URISyntaxException` ⇒ 表现为「莫名其妙的请求失败」。
 * 同一地址在不同调用点若口径不一致，就会出现「A 处能跑、B 处解析失败」这类极难排查的问题。
 *
 * 用法上**先按原样解析、失败再编码**（见 `RuleRunner.parseUri`）：对已经合法的 URL 是无操作，
 * 因此不会误伤已正确转义的数据。
 */
internal fun encodeIllegalUrlChars(url: String): String {
    val builder = StringBuilder(url.length + 16)
    for (ch in url) {
        when {
            ch == ' ' -> builder.append("%20")
            ch.code > 127 || ch in "\"<>{}|\\^`" -> builder.append(URLEncoder.encode(ch.toString(), Charsets.UTF_8))
            else -> builder.append(ch)
        }
    }
    return builder.toString()
}
