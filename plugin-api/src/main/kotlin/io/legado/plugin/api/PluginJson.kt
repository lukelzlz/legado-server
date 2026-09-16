package io.legado.plugin.api

import java.util.Base64

/**
 * Minimal JSON codec so plugin jars stay dependency-free.
 *
 * The host speaks JSON on every boundary (route responses, storage, settings, events), and forcing
 * every plugin author to bundle a JSON library — with its own transitive dependency conflicts
 * inside the shared plugin classloader — would be hostile. [write] and [parse] therefore cover the
 * full JSON grammar with no third-party code.
 *
 * Values map to plain Kotlin types: object → [Map], array → [List], string → [String],
 * number → [Long] or [Double], boolean → [Boolean], null → `null`.
 */
object PluginJson {

    fun write(value: Any?): String = StringBuilder(64).also { append(it, value) }.toString()

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        if (parser.atEnd()) throw IllegalArgumentException("JSON 解析失败: 内容为空")
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("JSON 解析失败: 位置 ${parser.index} 处存在多余内容")
        return value
    }

    /** Parses [text] and requires the result to be a JSON object. */
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw IllegalArgumentException("JSON 解析失败: 期望对象，实际为 ${value?.javaClass?.simpleName ?: "null"}")
    }

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is String -> writeString(sb, value)
            is ByteArray -> writeString(sb, Base64.getEncoder().encodeToString(value))
            is Number -> writeNumber(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((key, item) in value) {
                    if (key == null) continue
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key.toString())
                    sb.append(':')
                    append(sb, item)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> append(sb, value.toList())
            is IntArray -> append(sb, value.toList())
            is LongArray -> append(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeNumber(sb: StringBuilder, value: Number) {
        when (value) {
            is Double -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value.toString())
            is Float -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value.toString())
            else -> sb.append(value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (char in value) {
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (char < ' ') sb.append("\\u%04x".format(char.code)) else sb.append(char)
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        var index = 0
            private set

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isJsonWhitespace()) index++
        }

        fun readValue(): Any? {
            if (atEnd()) throw IllegalArgumentException("JSON 解析失败: 内容意外结束")
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) readNumber() else
                    throw IllegalArgumentException("JSON 解析失败: 位置 $index 处出现非法字符 '$char'")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) {
                throw IllegalArgumentException("JSON 解析失败: 位置 $index 处期望 '$literal'")
            }
            index += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            index++ // consume '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[index] == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[index] != '"') {
                    throw IllegalArgumentException("JSON 解析失败: 位置 $index 处期望对象的键")
                }
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[index] != ':') {
                    throw IllegalArgumentException("JSON 解析失败: 位置 $index 处期望 ':'")
                }
                index++
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("JSON 解析失败: 对象未闭合")
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("JSON 解析失败: 位置 $index 处期望 ',' 或 '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++ // consume '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[index] == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(readValue())
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("JSON 解析失败: 数组未闭合")
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("JSON 解析失败: 位置 $index 处期望 ',' 或 ']'")
                }
            }
        }

        private fun readString(): String {
            index++ // consume opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("JSON 解析失败: 字符串未闭合")
                when (val char = text[index]) {
                    '"' -> {
                        index++
                        return sb.toString()
                    }
                    '\\' -> {
                        index++
                        if (atEnd()) throw IllegalArgumentException("JSON 解析失败: 转义序列未完成")
                        when (val escape = text[index]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 >= text.length) {
                                    throw IllegalArgumentException("JSON 解析失败: \\u 转义不完整")
                                }
                                val hex = text.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("JSON 解析失败: 非法 \\u 转义 '$hex'")
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("JSON 解析失败: 非法转义 '\\$escape'")
                        }
                        index++
                    }
                    else -> {
                        sb.append(char)
                        index++
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = index
            if (!atEnd() && text[index] == '-') index++
            while (!atEnd() && text[index].isDigit()) index++
            var isFloating = false
            if (!atEnd() && text[index] == '.') {
                isFloating = true
                index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                isFloating = true
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("JSON 解析失败: 位置 $start 处数字格式错误")
            return if (isFloating) {
                raw.toDoubleOrNull() ?: throw IllegalArgumentException("JSON 解析失败: 非法数字 '$raw'")
            } else {
                raw.toLongOrNull() ?: raw.toDouble()
            }
        }
    }
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
