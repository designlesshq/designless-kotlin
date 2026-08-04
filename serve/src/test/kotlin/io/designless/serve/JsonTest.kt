package io.designless.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A hand-written parser earns its keep or it does not ship. These run it
 * against the real payloads and against the malformed input it will eventually
 * be handed off a network.
 */
class JsonTest {
    private fun fixture(name: String) =
        File("src/test/resources/fixtures/$name").readText()

    @Test
    fun `every captured payload parses`() {
        for (name in listOf(
            "context.json", "fonts.json", "protocol.v1.json",
            "tokens.dark.json", "tokens.light.json", "tokens.android.json",
        )) {
            val parsed = Json.parse(fixture(name))
            assertTrue(parsed is JsonValue.Obj, "$name did not parse to an object")
            assertTrue(parsed.entries.isNotEmpty(), "$name parsed to an empty object")
        }
    }

    @Test
    fun `values come out as the types they were written as`() {
        val v = Json.parse("""{"s":"x","n":1.5,"i":4,"t":true,"f":false,"z":null,"a":[1,2],"o":{"k":"v"}}""")
        val o = v.asObject!!
        assertEquals("x", o["s"]!!.asString)
        assertEquals(1.5, o["n"]!!.asDouble)
        assertEquals("4", o["i"]!!.asString, "a whole number should not read as 4.0")
        assertEquals(JsonValue.Bool(true), o["t"])
        assertEquals(JsonValue.Bool(false), o["f"])
        assertEquals(JsonValue.Null, o["z"])
        assertEquals(2, o["a"]!!.asArray!!.size)
        assertEquals("v", o["o"]!!.asObject!!["k"]!!.asString)
    }

    @Test
    fun `a boolean does not read as a number`() {
        // The bug every hand-rolled reader has: true bridging to 1. A brand
        // that publishes a flag would come back as a quantity.
        val o = Json.parse("""{"t":true}""").asObject!!
        assertNull(o["t"]!!.asDouble)
        assertEquals("true", o["t"]!!.asString)
    }

    @Test
    fun `escapes are unescaped, including unicode`() {
        val o = Json.parse("""{"q":"a\"b","n":"a\nb","u":"\u00e9","s":"a\/b"}""").asObject!!
        assertEquals("a\"b", o["q"]!!.asString)
        assertEquals("a\nb", o["n"]!!.asString)
        assertEquals("é", o["u"]!!.asString)
        assertEquals("a/b", o["s"]!!.asString)
    }

    @Test
    fun `whitespace anywhere legal is ignored`() {
        val o = Json.parse("  {\n\t\"a\" :\r\n 1 ,  \"b\" : [ 2 , 3 ]  }  ").asObject!!
        assertEquals(1.0, o["a"]!!.asDouble)
        assertEquals(2, o["b"]!!.asArray!!.size)
    }

    @Test
    fun `empty containers are values, not failures`() {
        assertEquals(emptyMap(), Json.parse("{}").asObject)
        assertEquals(emptyList(), Json.parse("[]").asArray)
    }

    @Test
    fun `numbers in every legal shape`() {
        for ((text, expected) in listOf(
            "0" to 0.0, "-1" to -1.0, "1.5" to 1.5,
            "1e3" to 1000.0, "1E3" to 1000.0, "1e+3" to 1000.0, "1.5e-2" to 0.015,
        )) {
            assertEquals(expected, Json.parse("""{"n":$text}""").asObject!!["n"]!!.asDouble, text)
        }
    }

    @Test
    fun `malformed input is refused rather than guessed at`() {
        // Everything here is something a network can hand you: a truncated
        // response, a proxy error page, a half-written cache file.
        for (bad in listOf(
            "", "   ", "{", "}", "[", "{\"a\"}", "{\"a\":}", "{\"a\":1,}",
            "[1,]", "{'a':1}", "{\"a\":1}{\"b\":2}", "\"unterminated",
            "{\"a\":tru}", "nul", "{\"a\":1", "[1,2", "{\"a\":\"\\q\"}",
            "{\"a\":\"\\u00\"}", "<html>error</html>",
        )) {
            assertFailsWith<JsonParseException>("should have refused: $bad") { Json.parse(bad) }
            assertNull(Json.parseOrNull(bad), "parseOrNull should be null for: $bad")
        }
    }

    @Test
    fun `an unescaped control character is refused`() {
        // Letting one through means a payload and its re-serialisation differ.
        assertFailsWith<JsonParseException> { Json.parse("{\"a\":\"x\ny\"}") }
    }

    @Test
    fun `a failure says where`() {
        val e = assertFailsWith<JsonParseException> { Json.parse("""{"a":1,"b":}""") }
        assertTrue(e.offset > 0, "an offset of 0 tells a reader nothing")
        assertTrue(e.message!!.contains("offset"))
    }

    @Test
    fun `key order is kept, because a document is easier to read in its own order`() {
        val o = Json.parse("""{"z":1,"a":2,"m":3}""").asObject!!
        assertEquals(listOf("z", "a", "m"), o.keys.toList())
    }

    @Test
    fun `deep nesting does not lose anything`() {
        val deep = Json.parse("""{"a":{"b":{"c":{"d":{"e":"bottom"}}}}}""")
        assertEquals(
            "bottom",
            deep.asObject!!["a"]!!.asObject!!["b"]!!.asObject!!["c"]!!
                .asObject!!["d"]!!.asObject!!["e"]!!.asString,
        )
    }
}
