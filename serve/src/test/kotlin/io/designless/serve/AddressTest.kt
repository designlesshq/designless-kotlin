package io.designless.serve

import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Addresses, checked against the grammar the surface publishes.
 *
 * The point of these is that they read `protocol.v1.json` rather than
 * restating it. A parameter sent to an address the grammar says it does not
 * apply to is a 400 in production and a passing test here, unless the test
 * asks the grammar.
 */
class AddressTest {
    private val serve = ServeAddresses("_designless")

    private val grammar by lazy {
        Json.parse(File("src/test/resources/fixtures/protocol.v1.json").readText()).asObject!!
    }
    private val params by lazy { grammar["params"]!!.asObject!! }
    private val patterns by lazy {
        grammar["addresses"]!!.asArray!!.mapNotNull { it.asObject?.get("pattern")?.asString }
    }

    private fun query(url: String): Map<String, String> =
        URI(url).query?.split("&").orEmpty().associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }

    private fun patternOf(url: String): String {
        val file = URI(url).path.removePrefix("/r/_designless/")
        return if (file.startsWith("assets/")) "assets/{role}.{format}" else file
    }

    @Test
    fun `every address built is one the grammar publishes`() {
        for (url in listOf(serve.context(), serve.tokens(), serve.fonts(), serve.events())) {
            val file = URI(url).path.removePrefix("/r/_designless/")
            assertTrue(
                patterns.any { it.endsWith("/$file") },
                "$url is not an address the grammar publishes",
            )
        }
        assertTrue("/r/{public_id}/assets/{role}.{format}" in patterns)
    }

    @Test
    fun `no parameter is sent to an address that does not take it`() {
        val built = listOf(
            serve.tokens(appearance = Appearance.DARK),
            serve.tokens(platform = ServePlatform.IOS),
            serve.tokens(version = "1.0.3"),
            serve.context(version = "1.0.3"),
            serve.fonts(version = "1.0.3"),
            serve.asset("logo-symbol", appearance = Appearance.LIGHT),
            serve.asset("logo-symbol", format = AssetFormat.PNG, size = AssetSize.PX256),
        )

        for (url in built) {
            val key = patternOf(url)
            for (name in query(url).keys) {
                val spec = params[name]?.asObject
                assertNotNull(spec, "$name is not a published parameter")
                val appliesTo = spec["appliesTo"]!!.asArray!!.mapNotNull { it.asString }
                assertTrue(
                    key in appliesTo,
                    "this module sent ?$name to $key, and the grammar says $name " +
                        "applies to ${appliesTo.joinToString(", ")}",
                )
            }
        }
    }

    @Test
    fun `every value sent is one the grammar accepts`() {
        val checks = listOf(
            serve.tokens(appearance = Appearance.DARK) to "appearance",
            serve.tokens(platform = ServePlatform.ANDROID) to "platform",
            serve.asset("logo-symbol", format = AssetFormat.PNG, size = AssetSize.PX512) to "size",
        )
        for ((url, name) in checks) {
            val values = params[name]!!.asObject!!["values"]!!.asArray!!.mapNotNull { it.asString }
            assertTrue(query(url)[name] in values, "$name")
        }
    }

    @Test
    fun `the size ladder is exactly the published one`() {
        val published = params["size"]!!.asObject!!["values"]!!.asArray!!
            .mapNotNull { it.asDouble?.toInt() }
        assertEquals(
            published, AssetSize.entries.map { it.pixels },
            "the ladder this module offers has drifted from the one the surface " +
                "renders; a rung that is not published is a 400",
        )
    }

    @Test
    fun `a size on a vector is refused here rather than 400ing there`() {
        // A 400 from a CDN reaches a caller as a blank ImageView with no
        // explanation. This is the same refusal, close enough to the mistake
        // to name it.
        val e = assertFailsWith<IllegalArgumentException> {
            serve.asset("logo-symbol", size = AssetSize.PX256)
        }
        assertTrue("no size to pick" in e.message!!)
    }

    @Test
    fun `a composed destination carries no parameters`() {
        val url = serve.composition("app-icon")
        assertTrue(query(url).isEmpty())
        assertEquals("/r/_designless/assets/app-icon.png", URI(url).path)
    }

    @Test
    fun `atLeast rounds up, because scaling down stays sharp`() {
        assertEquals(AssetSize.PX16, AssetSize.atLeast(1))
        assertEquals(AssetSize.PX16, AssetSize.atLeast(16))
        assertEquals(AssetSize.PX32, AssetSize.atLeast(17))
        assertEquals(AssetSize.PX256, AssetSize.atLeast(200))
        assertEquals(AssetSize.PX1024, AssetSize.atLeast(4096))
    }

    @Test
    fun `the same request is always the same string`() {
        // Query order is sorted, so a cache key built from the URL is stable
        // and two callers asking the same thing share one entry.
        val a = serve.tokens(Appearance.DARK, ServePlatform.IOS, "1.0.3")
        val b = serve.tokens(Appearance.DARK, ServePlatform.IOS, "1.0.3")
        assertEquals(a, b)
        assertEquals("appearance=dark&platform=ios&version=1.0.3", URI(a).query)
    }
}
