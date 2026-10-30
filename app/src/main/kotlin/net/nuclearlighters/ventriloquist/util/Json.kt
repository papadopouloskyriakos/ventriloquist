package net.nuclearlighters.ventriloquist.util

/**
 * A small JSON reader and writer, enough for the control endpoint, the configuration and the
 * Music Assistant client. Objects become [Map] (insertion ordered), arrays [List], integers
 * without fraction or exponent [Long], other numbers [Double]. Kept free of Android classes so
 * that it runs in plain JVM unit tests (the platform's org.json is a stub there).
 */
object Json {

    class ParseException(message: String) : IllegalArgumentException(message)

    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.value(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw parser.error("trailing characters")
        return value
    }

    /** Parses [text] and requires a JSON object at the top. */
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        if (value !is Map<*, *>) throw ParseException("expected a JSON object")
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> if (value.isFinite()) out.append(value.toString()) else out.append("null")
            is Float -> append(out, value.toDouble())
            is Number -> out.append(value.toString())
            is Enum<*> -> quote(out, value.name.lowercase())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, key.toString())
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, item)
                }
                out.append(']')
            }
            is Array<*> -> append(out, value.asList())
            is IntArray -> append(out, value.asList())
            is LongArray -> append(out, value.asList())
            is DoubleArray -> append(out, value.asList())
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (c < ' ') {
                    out.append("\\u")
                    out.append(Integer.toHexString(c.code).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun error(message: String): ParseException = ParseException("$message at offset $i")

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw error("nesting too deep")
            skipWhitespace()
            if (atEnd()) throw error("unexpected end of input")
            val c = s[i]
            return when {
                c == '{' -> obj(depth)
                c == '[' -> array(depth)
                c == '"' -> string()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> number()
                else -> throw error("unexpected character '$c'")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) throw error("invalid literal")
            i += word.length
            return result
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return result
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') throw error("expected a string key")
                val key = string()
                skipWhitespace()
                if (atEnd() || s[i] != ':') throw error("expected ':'")
                i++
                result[key] = value(depth + 1)
                skipWhitespace()
                if (atEnd()) throw error("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return result
                    }
                    else -> throw error("expected ',' or '}'")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            i++
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return result
            }
            while (true) {
                result.add(value(depth + 1))
                skipWhitespace()
                if (atEnd()) throw error("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return result
                    }
                    else -> throw error("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw error("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) throw error("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw error("short unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw error("bad unicode escape")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> throw error("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw error("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            if (atEnd()) throw error("bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                throw error("bad number")
            }
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                val digits = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == digits) throw error("bad fraction")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val digits = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == digits) throw error("bad exponent")
            }
            val text = s.substring(start, i)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }
    }
}
