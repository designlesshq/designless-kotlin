package io.designless.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a change is allowed to land, what a caller reads while it waits, and
 * what the discovery document is trusted for.
 */
class BrandTest {
    private fun fixture(name: String) = File("src/test/resources/fixtures/$name").readText()

    private class Store(private var value: String? = null) : SnapshotStore {
        var writes = 0
            private set

        override fun read(key: String): String? = value
        override fun write(key: String, value: String) {
            this.value = value
            writes++
        }
    }

    /** Serves a named fixture, and can be repointed to simulate a republish. */
    private inner class Server(var body: String) {
        var broken = false
        fun fetch(url: String): String {
            if (broken) error("no")
            return fixture(body)
        }
    }

    // ── A cold start ─────────────────────────────────────────────────────

    @Test
    fun `a persisted snapshot is showing before anything is fetched`() {
        // A launch that waits on a round trip shows an unbranded first frame,
        // and that frame is the one a person judges the app by.
        val brand = Brand("_designless", Server("tokens.dark.json")::fetch, Store(fixture("tokens.dark.json")))
        assertEquals(BrandStatus.FROM_SNAPSHOT, brand.status)
        assertEquals("#060608", brand.tokens?.color("bg.page"))
    }

    @Test
    fun `a snapshot that does not parse is dropped rather than thrown`() {
        val brand = Brand("_designless", Server("tokens.dark.json")::fetch, Store("{ not json"))
        assertEquals(BrandStatus.EMPTY, brand.status)
        assertNull(brand.tokens)
    }

    // ── Fetch then activate ──────────────────────────────────────────────

    @Test
    fun `a refresh holds the new payload instead of swapping it`() {
        val server = Server("tokens.dark.json")
        val brand = Brand("_designless", server::fetch)
        brand.initialize()

        // The brand republishes while someone is reading the screen.
        server.body = "tokens.light.json"
        brand.refresh()

        assertNotNull(brand.pending, "the new payload should be held")
        assertEquals(
            "#060608", brand.tokens?.color("bg.page"),
            "what is on screen must not move until activate()",
        )
    }

    @Test
    fun `activate promotes it once and says whether it did`() {
        val server = Server("tokens.dark.json")
        val brand = Brand("_designless", server::fetch)
        brand.initialize()
        server.body = "tokens.light.json"
        brand.refresh()

        assertTrue(brand.activate())
        assertNull(brand.pending)
        assertEquals("light", brand.tokens?.appearance)
        assertFalse(brand.activate(), "nothing left to promote")
    }

    @Test
    fun `observers hear the promotion and not the arrival`() {
        val server = Server("tokens.dark.json")
        val brand = Brand("_designless", server::fetch)
        brand.initialize()

        val heard = mutableListOf<String?>()
        brand.observe { heard.add(it.appearance) }

        server.body = "tokens.light.json"
        brand.refresh()
        assertTrue(heard.isEmpty(), "nothing a caller can see changed when the payload arrived")

        brand.activate()
        assertEquals(listOf<String?>("light"), heard.toList())
    }

    @Test
    fun `the first payload activates whatever the caller asked for`() {
        // Holding the very first payload would leave the app with nothing to
        // render. "Do not restyle underneath someone" needs something to be
        // styled first.
        val brand = Brand("_designless", Server("tokens.dark.json")::fetch)
        brand.refresh()
        assertNotNull(brand.tokens)
        assertNull(brand.pending)
    }

    // ── The address and the payload never disagree ───────────────────────

    @Test
    fun `a mark uses the appearance that is live, not the one requested`() {
        val server = Server("tokens.dark.json")
        val brand = Brand("_designless", server::fetch)
        brand.initialize()

        // Someone switches to light. The request goes out; nothing has landed.
        brand.appearance = Appearance.LIGHT
        assertTrue(
            "appearance=dark" in brand.assetUrl("logo-symbol"),
            "a light mark on a screen still painted dark is the exact failure " +
                "the appearance rule exists to prevent",
        )

        server.body = "tokens.light.json"
        brand.refresh(activateNow = true)
        assertTrue("appearance=light" in brand.assetUrl("logo-symbol"))
    }

    // ── A failed fetch ───────────────────────────────────────────────────

    @Test
    fun `a failed fetch leaves what is live exactly as it was`() {
        val server = Server("tokens.dark.json")
        val brand = Brand("_designless", server::fetch)
        brand.initialize()

        server.broken = true
        assertFailsWith<IllegalStateException> { brand.refresh() }

        assertEquals(BrandStatus.STALE, brand.status)
        assertEquals(
            "#060608", brand.tokens?.color("bg.page"),
            "an app showing the brand goes on showing it",
        )
    }

    @Test
    fun `a malformed body never replaces a snapshot that works`() {
        val store = Store(fixture("tokens.dark.json"))
        val brand = Brand("_designless", { "not json at all" }, store)
        assertFailsWith<JsonParseException> { brand.initialize() }
        assertEquals(0, store.writes)
    }

    // ── Reading tokens ───────────────────────────────────────────────────

    @Test
    fun `reading tokens`() {
        val brand = Brand("_designless", Server("tokens.dark.json")::fetch)
        brand.initialize()
        val tokens = brand.tokens!!

        assertEquals("#060608", tokens.color("bg.page"))
        assertTrue(tokens.string("typography.fontFamily.body")!!.startsWith("Inter,"))

        assertNull(tokens["color.bg.nonesuch"], "a missing token is null, not a crash")
        assertNull(tokens["color.bg"], "a branch is not a value")
        assertTrue(tokens.branch("color.bg").isNotEmpty())

        val md = tokens.string("typography.fontSize.md")!!
        val rem = md.removeSuffix("rem").toDouble()
        assertEquals(rem * 16, tokens.length("typography.fontSize.md")!!, 0.001)
        assertEquals(rem * 10, tokens.length("typography.fontSize.md", rootDp = 10.0)!!, 0.001)

        // The reason tokens are not flattened into named properties: a brand
        // adding a branch must not need an SDK release.
        assertTrue("color" in tokens.branches)
        assertTrue("component" in tokens.branches)
    }

    // ── The discovery document ───────────────────────────────────────────

    @Test
    fun `every capability offered has an address in the document`() {
        // The client half of a guard that also exists on the server. A
        // capability is a promise that something works now, so the document
        // making the promise has to say where.
        //
        // This is the test that catches the failure it was written for:
        // context.json advertised a `compose` capability with auth "api-key"
        // and no address anywhere, while POST answered 405 and GET answered
        // 404. A client author reads the list and builds against it.
        val context = BrandContext.parse(fixture("context.json"))

        val addressed = mapOf(
            "tokens" to (context.fetch["tokens"] != null),
            "assets" to (context.assets.isNotEmpty() || context.compositions.isNotEmpty()),
            "fonts" to (context.fetch["fonts"] != null),
            "events" to (context.fetch["events"] != null),
            "styles" to (context.fetch["styles"] != null),
        )

        for (capability in context.capabilities) {
            assertEquals(
                true, addressed[capability.name],
                "this brand advertises a \"${capability.name}\" capability " +
                    "(auth: ${capability.auth}) and this document gives no address for it",
            )
        }
    }

    @Test
    fun `a reserved entry is an address claim and nothing more`() {
        val context = BrandContext.parse(fixture("context.json"))
        val served = context.fetch.values.map { it.substringAfterLast('/') }.toSet()

        for (name in context.reserved) {
            assertTrue(Regex("^[a-z0-9.-]+$").matches(name), "$name is not a bare filename")
            assertFalse(name in served, "$name is reserved and served at the same time")
        }
    }

    @Test
    fun `a composition carries its geometry and its reason`() {
        val context = BrandContext.parse(fixture("context.json"))
        assertTrue(context.compositions.isNotEmpty())
        for (c in context.compositions) {
            assertTrue(c.width > 0)
            assertTrue(c.height > 0)
            assertTrue(
                c.rationale.isNotEmpty(),
                "a destination with no stated reason cannot be chosen between " +
                    "by anyone reading the list",
            )
        }
    }
}
