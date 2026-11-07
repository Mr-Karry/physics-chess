package dev.whale.crazychess.net

/**
 * 极小的 JSON 读写工具。
 *
 * 为什么不用 `org.json`：那是 Android 才有的类，桌面版（纯 JVM）编不过；
 * 而局域网协议只需要"一层扁平对象 + 字符串/数字/布尔"，没必要引入整套 JSON 库，
 * 也就没必要给桌面版塞依赖。写死成自带的实现，两端编的是同一份代码。
 */
object Json {

    /** 把字符串包成合法的 JSON 字符串字面量（带引号）。 */
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> if (value) "true" else "false"
        is Int, is Long, is Short, is Byte -> value.toString()
        is Float -> formatNumber(value.toDouble())
        is Double -> formatNumber(value)
        else -> quote(value.toString())
    }

    private fun formatNumber(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        val asLong = d.toLong()
        return if (asLong.toDouble() == d) asLong.toString() else d.toString()
    }

    /** 拼一个 JSON 对象：`Json.obj("t" to "shot", "dx" to 1.5f)`。 */
    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append(quote(key)).append(':').append(encode(value))
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * 解析一个 JSON 对象。解析失败返回 null（局域网里遇到半包/乱包就当没收到）。
     * 支持对象/数组/字符串/数字/布尔/null，够协议用了。
     */
    fun parse(text: String): Map<String, Any?>? {
        val parser = Parser(text.trim())
        val value = runCatching { parser.readValue() }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
    }

    fun str(map: Map<String, Any?>, key: String, default: String = ""): String =
        map[key] as? String ?: default

    fun int(map: Map<String, Any?>, key: String, default: Int = 0): Int =
        (map[key] as? Number)?.toInt() ?: default

    fun dbl(map: Map<String, Any?>, key: String, default: Double = 0.0): Double =
        (map[key] as? Number)?.toDouble() ?: default

    fun bool(map: Map<String, Any?>, key: String, default: Boolean = false): Boolean =
        map[key] as? Boolean ?: default

    private class Parser(private val s: String) {
        private var i = 0

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalStateException("eof")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    if (c == '-' || c in '0'..'9') readNumber()
                    else throw IllegalStateException("unexpected $c at $i")
                }
            }
        }

        private fun <T> literal(word: String, value: T): T {
            if (!s.startsWith(word, i)) throw IllegalStateException("bad literal at $i")
            i += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                out[key] = readValue()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect('}')
                return out
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect(']')
                return out
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalStateException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun readNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            return s.substring(start, i).toDouble()
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw IllegalStateException("expected $c at $i")
            i++
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
