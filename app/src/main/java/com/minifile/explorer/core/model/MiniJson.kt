package com.minifile.explorer.core.model

/**
 * Lightweight, zero-dependency RFC 8259 JSON parser and formatter.
 * Avoids Android's stubbed org.json in pure-JVM JUnit 4 tests.
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val raw: String) : JsonValue {
        constructor(v: Int) : this(v.toString())
        constructor(v: Long) : this(v.toString())
        constructor(v: Double) : this(v.toString())

        fun toLongOrNull(): Long? = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        fun toIntOrNull(): Int? = raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
        fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
    }

    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val entries: LinkedHashMap<String, JsonValue>) : JsonValue {
        fun str(key: String): String? = (entries[key] as? Str)?.value
        fun bool(key: String, default: Boolean = false): Boolean = (entries[key] as? Bool)?.value ?: default
        fun long(key: String, default: Long = 0L): Long = (entries[key] as? Num)?.toLongOrNull() ?: default
        fun int(key: String, default: Int = 0): Int = (entries[key] as? Num)?.toIntOrNull() ?: default
        fun intOrNull(key: String): Int? = (entries[key] as? Num)?.toIntOrNull()
        fun obj(key: String): Obj? = entries[key] as? Obj
        fun arr(key: String): Arr? = entries[key] as? Arr
    }
}

object MiniJson {
    fun parse(input: String): JsonValue {
        val parser = Parser(input)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isEnd()) {
            throw IllegalArgumentException("Unexpected trailing JSON content at index ${parser.pos}")
        }
        return value
    }

    fun stringify(value: JsonValue, indent: Int = 0): String {
        val sb = StringBuilder()
        writeValue(sb, value, indent, 0)
        return sb.toString()
    }

    fun prettyPrint(rawJson: String, indent: Int = 2): String {
        val parsed = parse(rawJson)
        return stringify(parsed, indent)
    }

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>(pairs.size)
        for ((k, v) in pairs) {
            map[k] = v
        }
        return JsonValue.Obj(map)
    }

    private fun writeValue(sb: StringBuilder, value: JsonValue, indent: Int, depth: Int) {
        when (value) {
            JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Num -> sb.append(value.raw)
            is JsonValue.Str -> writeEscapedString(sb, value.value)
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                for ((i, item) in value.items.withIndex()) {
                    if (i > 0) sb.append(',')
                    if (indent > 0) {
                        sb.append('\n')
                        repeat((depth + 1) * indent) { sb.append(' ') }
                    }
                    writeValue(sb, item, indent, depth + 1)
                }
                if (indent > 0) {
                    sb.append('\n')
                    repeat(depth * indent) { sb.append(' ') }
                }
                sb.append(']')
            }

            is JsonValue.Obj -> {
                if (value.entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    if (indent > 0) {
                        sb.append('\n')
                        repeat((depth + 1) * indent) { sb.append(' ') }
                    }
                    writeEscapedString(sb, k)
                    sb.append(if (indent > 0) ": " else ":")
                    writeValue(sb, v, indent, depth + 1)
                }
                if (indent > 0) {
                    sb.append('\n')
                    repeat(depth * indent) { sb.append(' ') }
                }
                sb.append('}')
            }
        }
    }

    private fun writeEscapedString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        var pos: Int = 0

        fun isEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos] in " \t\r\n") {
                pos++
            }
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (isEnd()) throw IllegalArgumentException("Unexpected end of JSON input")
            return when (val ch = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> {
                    expectLiteral("true")
                    JsonValue.Bool(true)
                }

                'f' -> {
                    expectLiteral("false")
                    JsonValue.Bool(false)
                }

                'n' -> {
                    expectLiteral("null")
                    JsonValue.Null
                }

                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '$ch' at $pos")
            }
        }

        private fun expectLiteral(lit: String) {
            if (!src.startsWith(lit, pos)) {
                throw IllegalArgumentException("Expected '$lit' at $pos")
            }
            pos += lit.length
        }

        private fun parseObject(): JsonValue.Obj {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, JsonValue>()
            if (!isEnd() && src[pos] == '}') {
                pos++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWhitespace()
                if (isEnd() || src[pos] != '"') {
                    throw IllegalArgumentException("Expected object key at $pos")
                }
                val key = parseString()
                skipWhitespace()
                if (isEnd() || src[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at $pos")
                }
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (isEnd()) throw IllegalArgumentException("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(map)
                    }

                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<JsonValue>()
            if (!isEnd() && src[pos] == ']') {
                pos++
                return JsonValue.Arr(list)
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (isEnd()) throw IllegalArgumentException("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(list)
                    }

                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) {
                                    throw IllegalArgumentException("Invalid unicode escape at $pos")
                                }
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }

                            else -> throw IllegalArgumentException("Invalid escape '\\$esc' at $pos")
                        }
                    }

                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            if (pos < src.length && src[pos] == '.') {
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val raw = src.substring(start, pos)
            if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("Invalid number at $start")
            return JsonValue.Num(raw)
        }
    }
}
