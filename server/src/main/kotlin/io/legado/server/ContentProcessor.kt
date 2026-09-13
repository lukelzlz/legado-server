package io.legado.server

import java.util.regex.PatternSyntaxException

object ContentProcessor {

    /**
     * 判断单条替换规则是否匹配当前书籍和书源作用域。
     */
    fun matchesScope(
        rule: ReplaceRule,
        bookName: String?,
        sourceUrl: String?,
        sourceName: String? = null,
    ): Boolean {
        if (!rule.isEnabled) return false

        // 检查排除范围 (excludeScope)
        val exclude = rule.excludeScope?.trim()
        if (!exclude.isNullOrBlank()) {
            val excludeTokens = splitTokens(exclude)
            if (excludeTokens.any { matchToken(it, bookName, sourceUrl, sourceName) }) {
                return false
            }
        }

        // 检查包含范围 (scope)
        val scope = rule.scope?.trim()
        if (scope.isNullOrBlank()) {
            return true // 空则全局生效
        }

        val tokens = splitTokens(scope)
        return tokens.any { matchToken(it, bookName, sourceUrl, sourceName) }
    }

    private fun splitTokens(scopeStr: String): List<String> =
        scopeStr.split(Regex("[\r\n,;+]+")).map { it.trim() }.filter { it.isNotBlank() }

    private fun matchToken(
        token: String,
        bookName: String?,
        sourceUrl: String?,
        sourceName: String?,
    ): Boolean {
        if (bookName != null && (bookName.equals(token, ignoreCase = true) || bookName.contains(token))) return true
        if (sourceUrl != null && (sourceUrl.equals(token, ignoreCase = true) || sourceUrl.contains(token))) return true
        if (sourceName != null && (sourceName.equals(token, ignoreCase = true) || sourceName.contains(token))) return true
        // 尝试作为正则匹配
        return runCatching {
            val regex = Regex(token, RegexOption.IGNORE_CASE)
            (bookName != null && regex.containsMatchIn(bookName)) ||
                (sourceUrl != null && regex.containsMatchIn(sourceUrl)) ||
                (sourceName != null && regex.containsMatchIn(sourceName))
        }.getOrDefault(false)
    }

    /**
     * 对正文执行净化规则链
     */
    fun processContent(
        content: String,
        rules: List<ReplaceRule>,
        jsSandbox: JsSandbox? = null,
        bookName: String? = null,
        chapterTitle: String? = null,
    ): String {
        if (content.isBlank() || rules.isEmpty()) return content
        var text = content

        val activeRules = rules
            .filter { it.isEnabled && it.scopeContent && it.pattern.isNotEmpty() }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))

        for (rule in activeRules) {
            text = applyRule(text, rule, jsSandbox, bookName, chapterTitle)
        }
        return text
    }

    /**
     * 对章节标题执行净化规则
     */
    fun processTitle(
        title: String,
        rules: List<ReplaceRule>,
        jsSandbox: JsSandbox? = null,
        bookName: String? = null,
    ): String {
        if (title.isBlank() || rules.isEmpty()) return title
        var text = title

        val activeRules = rules
            .filter { it.isEnabled && it.scopeTitle && it.pattern.isNotEmpty() }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))

        for (rule in activeRules) {
            text = applyRule(text, rule, jsSandbox, bookName, null)
        }
        return text
    }

    /**
     * 单条规则执行引擎（支持纯文本、正则捕获组、@js: 脚本）
     */
    fun applyRule(
        text: String,
        rule: ReplaceRule,
        jsSandbox: JsSandbox? = null,
        bookName: String? = null,
        chapterTitle: String? = null,
    ): String {
        if (rule.pattern.isEmpty() || text.isEmpty()) return text

        if (!rule.isRegex) {
            return text.replace(rule.pattern, rule.replacement)
        }

        // 正则模式
        return try {
            val regex = Regex(rule.pattern)
            val isJs = rule.replacement.startsWith("@js:") || (rule.replacement.contains("<js>") && rule.replacement.contains("</js>"))

            if (isJs && jsSandbox != null) {
                val jsCode = if (rule.replacement.contains("<js>")) {
                    rule.replacement.substringAfter("<js>").substringBefore("</js>")
                } else {
                    rule.replacement.removePrefix("@js:")
                }

                // 针对正则匹配到的每一个片段分别求值或全量求值
                val matches = regex.findAll(text).toList()
                if (matches.isEmpty()) return text

                val sb = StringBuilder()
                var lastIdx = 0
                for (m in matches) {
                    sb.append(text, lastIdx, m.range.first)
                    val jsResult = runCatching {
                        jsSandbox.eval(
                            jsCode,
                            mapOf(
                                "result" to m.value,
                                "bookName" to (bookName ?: ""),
                                "title" to (chapterTitle ?: ""),
                            )
                        )
                    }.getOrNull() ?: m.value
                    sb.append(jsResult)
                    lastIdx = m.range.last + 1
                }
                sb.append(text, lastIdx, text.length)
                sb.toString()
            } else {
                // 普通正则替换（原生支持 $1, $2 捕获组）
                regex.replace(text, rule.replacement)
            }
        } catch (_: PatternSyntaxException) {
            text // 语法错误直接安全跳过
        } catch (_: Throwable) {
            text
        }
    }
}
