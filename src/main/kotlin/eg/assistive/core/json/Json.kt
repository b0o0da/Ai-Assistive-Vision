package eg.assistive.core.json

class JsonException(message: String) : RuntimeException(message)

/**
 * Minimal JSON parser (no dependencies, so the core stays pure Kotlin).
 * Returns Map<String, Any?>, List<Any?>, String, Double, Boolean or null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) p.fail("unexpected data after the JSON value")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("expected a JSON object")

    @Suppress("UNCHECKED_CAST")
    fun parseArray(text: String): List<Any?> =
        parse(text) as? List<Any?> ?: throw JsonException("expected a JSON array")

    private class Parser(val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw JsonException("$msg at position $pos")

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > 64) fail("nesting too deep")
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected character '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
            return v
        }

        fun num(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("invalid number")
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("expected a string key")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                out[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}'")
                }
            }
        }
    }
}
