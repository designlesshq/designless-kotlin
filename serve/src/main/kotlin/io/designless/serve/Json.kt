package io.designless.serve

/**
 * A JSON reader, because this module has no dependencies and the JVM has no
 * JSON.
 *
 * That is a deliberate trade and worth stating. `org.json` exists on Android
 * and nowhere else; `kotlinx-serialization` is the idiomatic choice and would
 * pin a version on every consumer. Dependency conflicts are the loudest
 * complaint about Android libraries, and an SDK whose pitch is "thin client"
 * that drags in a serialization runtime is not one.
 *
 * So: about a hundred and fifty lines, reading exactly the subset RFC 8259
 * defines, tested against the real payloads this package is for and against
 * the malformed input it will eventually be handed. It is not a general
 * purpose parser and does not try to be — no comments, no trailing commas, no
 * NaN. A payload that is not JSON is refused rather than guessed at.
 */
public sealed interface JsonValue {
    public data class Str(val value: String) : JsonValue
    public data class Num(val value: Double) : JsonValue
    public data class Bool(val value: Boolean) : JsonValue
    public data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    public data class Arr(val items: List<JsonValue>) : JsonValue
    public data object Null : JsonValue

    /** True for anything that is not a leaf. */
    public val isBranch: Boolean
        get() = this is Obj || this is Arr

    public val asString: String?
        get() = when (this) {
            is Str -> value
            is Num -> if (value == Math.floor(value) && !value.isInfinite()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
            is Bool -> value.toString()
            else -> null
        }

    public val asDouble: Double?
        get() = when (this) {
            is Num -> value
            is Str -> value.toDoubleOrNull()
            else -> null
        }

    public val asObject: Map<String, JsonValue>?
        get() = (this as? Obj)?.entries

    public val asArray: List<JsonValue>?
        get() = (this as? Arr)?.items
}

/** A payload that is not JSON. Refused rather than guessed at. */
public class JsonParseException(message: String, public val offset: Int) :
    IllegalArgumentException("$message (at offset $offset)")

public object Json {
    public fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd) reader.fail("trailing content after the top-level value")
        return value
    }

    /** Parse, or null when the text is not JSON. For a payload off a network. */
    public fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (_: JsonParseException) {
        null
    }

    private class Reader(private val text: String) {
        private var i = 0

        val atEnd: Boolean get() = i >= text.length

        fun fail(why: String): Nothing = throw JsonParseException(why, i)

        fun skipWhitespace() {
            while (i < text.length && text[i].isJsonWhitespace()) i++
        }

        fun readValue(): JsonValue {
            if (atEnd) fail("expected a value")
            return when (val c = text[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else ->
                    if (c == '-' || c in '0'..'9') readNumber()
                    else fail("unexpected character '$c'")
            }
        }

        private fun readLiteral(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, i)) fail("expected $word")
            i += word.length
            return value
        }

        private fun readObject(): JsonValue {
            i++ // {
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd && text[i] == '}') { i++; return JsonValue.Obj(entries) }

            while (true) {
                skipWhitespace()
                if (atEnd || text[i] != '"') fail("expected a key")
                val key = readString()
                skipWhitespace()
                if (atEnd || text[i] != ':') fail("expected ':' after a key")
                i++
                skipWhitespace()
                // A duplicate key takes the last value, which is what every
                // mainstream parser does. Not silently: a payload with one is
                // malformed in spirit, but refusing it would break a client
                // over something the server would have to send deliberately.
                entries[key] = readValue()
                skipWhitespace()
                when {
                    atEnd -> fail("unterminated object")
                    text[i] == ',' -> i++
                    text[i] == '}' -> { i++; return JsonValue.Obj(entries) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): JsonValue {
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd && text[i] == ']') { i++; return JsonValue.Arr(items) }

            while (true) {
                skipWhitespace()
                items.add(readValue())
                skipWhitespace()
                when {
                    atEnd -> fail("unterminated array")
                    text[i] == ',' -> i++
                    text[i] == ']' -> { i++; return JsonValue.Arr(items) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd) fail("unterminated string")
                when (val c = text[i]) {
                    '"' -> { i++; return out.toString() }
                    '\\' -> {
                        i++
                        if (atEnd) fail("unterminated escape")
                        when (val e = text[i]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 >= text.length) fail("truncated \\u escape")
                                val hex = text.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '$hex'")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> fail("unknown escape '\\$e'")
                        }
                        i++
                    }
                    else -> {
                        // Control characters are not legal unescaped in a JSON
                        // string. Letting them through would mean a payload
                        // and its re-serialisation differ.
                        if (c < ' ') fail("unescaped control character")
                        out.append(c)
                        i++
                    }
                }
            }
        }

        private fun readNumber(): JsonValue {
            val start = i
            if (!atEnd && text[i] == '-') i++
            while (!atEnd && text[i] in '0'..'9') i++
            if (!atEnd && text[i] == '.') {
                i++
                while (!atEnd && text[i] in '0'..'9') i++
            }
            if (!atEnd && (text[i] == 'e' || text[i] == 'E')) {
                i++
                if (!atEnd && (text[i] == '+' || text[i] == '-')) i++
                while (!atEnd && text[i] in '0'..'9') i++
            }
            val slice = text.substring(start, i)
            val value = slice.toDoubleOrNull() ?: run { i = start; fail("bad number '$slice'") }
            return JsonValue.Num(value)
        }

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\t' || this == '\n' || this == '\r'
    }
}
