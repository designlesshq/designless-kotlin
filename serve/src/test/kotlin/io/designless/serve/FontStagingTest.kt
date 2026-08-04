package io.designless.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ordering rule, and what it refuses to let a caller do.
 *
 * These are the tests that matter most in this module. Everything else is data
 * mapping; this is the one place where being wrong produces a screen that
 * looks fine and is not, for the life of the view.
 */
class FontStagingTest {
    private fun fixture(name: String) = File("src/test/resources/fixtures/$name").readText()
    private fun manifest() = FontManifest.parse(fixture("fonts.json"))

    /** Records the order things happened in, so a test can assert on sequence. */
    private class Recorder(
        val failFetch: Set<String> = emptySet(),
        val failRegister: Set<String> = emptySet(),
    ) {
        val events = mutableListOf<String>()

        fun fetch(url: String): ByteArray {
            events.add("fetch $url")
            if (url in failFetch) error("network said no")
            return "bytes for $url".toByteArray()
        }

        fun register(name: String, bytes: ByteArray) {
            events.add("register $name")
            if (name in failRegister) error("platform said no")
        }

        val registrations get() = events.filter { it.startsWith("register ") }
    }

    private class MemoryCache : FontCache {
        val entries = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = entries[key]
        override fun write(key: String, bytes: ByteArray) { entries[key] = bytes }
    }

    private fun staged(failing: Set<String> = emptySet()): FontStaging {
        val r = Recorder(failRegister = failing)
        return FontStaging(r::fetch, r::register).apply { stage(manifest()) }
    }

    @Test
    fun `no face comes out before its file is registered`() {
        val r = Recorder()
        val staging = FontStaging(r::fetch, r::register)

        val cold = staging.resolve("body")
        assertEquals(FaceOutcome.NOT_LOADED, cold.outcome)
        assertNull(cold.face, "a face before any load would be a lie")

        staging.stage(manifest())

        val warm = staging.resolve("body")
        assertEquals(FaceOutcome.RESOLVED, warm.outcome)
        assertEquals("Inter-Regular", warm.postscriptName)
    }

    @Test
    fun `register happens after fetch, for every face, without exception`() {
        val r = Recorder()
        FontStaging(r::fetch, r::register).stage(manifest())

        val fetched = mutableSetOf<String>()
        for (event in r.events) {
            if (event.startsWith("fetch ")) {
                fetched.add(event.removePrefix("fetch "))
            } else {
                val name = event.removePrefix("register ")
                val slug = if (name.endsWith("Regular")) "400-normal" else "600-normal"
                assertTrue(
                    fetched.any { slug in it },
                    "$name was registered before its file was fetched",
                )
            }
        }
        assertEquals(6, r.registrations.size)
    }

    @Test
    fun `a face whose file is not staged is reported, not handed out`() {
        val staging = staged(failing = setOf("EBGaramond-Regular"))
        val display = staging.resolve("display")

        assertEquals(FaceOutcome.NOT_REGISTERED, display.outcome)
        assertNull(display.face)
        assertContains(display.detail!!, "platform said no")
        assertContains(display.detail!!, "platform's own type")
    }

    @Test
    fun `one face failing does not stop the others`() {
        val r = Recorder(
            failFetch = setOf("https://cdn.designless.app/fonts/google/eb-garamond/400-normal.ttf"),
        )
        val staging = FontStaging(r::fetch, r::register)
        val report = staging.stage(manifest())

        assertEquals(1, report.failed.size)
        assertEquals(
            FaceOutcome.RESOLVED, staging.resolve("body").outcome,
            "a display face failing must not take body copy with it",
        )
        assertEquals(FaceOutcome.RESOLVED, staging.resolve("mono").outcome)
    }

    @Test
    fun `staging twice does not repeat work`() {
        val r = Recorder()
        val staging = FontStaging(r::fetch, r::register)
        val m = manifest()

        staging.stage(m)
        val firstRun = r.events.size
        staging.stage(m)

        assertEquals(firstRun, r.events.size, "a second stage() re-downloaded installed faces")
    }

    @Test
    fun `a cached face is not fetched again but is still registered`() {
        val cache = MemoryCache()
        val first = Recorder()
        FontStaging(first::fetch, first::register, cache).stage(manifest())
        assertEquals(6, first.events.count { it.startsWith("fetch") })

        // A second launch: new staging, same cache.
        val second = Recorder()
        val report = FontStaging(second::fetch, second::register, cache).stage(manifest())

        assertEquals(0, second.events.count { it.startsWith("fetch") })
        assertEquals(6, report.fromCache)
        assertEquals(
            6, report.registered.size,
            "a cached file still has to be registered every launch — the cache " +
                "survives the process, the registration does not",
        )
    }

    @Test
    fun `a file is cached before it is registered`() {
        val cache = MemoryCache()
        val r = Recorder(failRegister = setOf("Inter-Regular"))
        FontStaging(r::fetch, r::register, cache).stage(manifest())

        assertTrue(
            "Inter-Regular.ttf" in cache.entries,
            "the download should survive a failed registration",
        )
    }

    @Test
    fun `an unpublished role names the roles that exist`() {
        val r = staged().resolve("caption")
        assertEquals(FaceOutcome.UNPUBLISHED, r.outcome)
        for (named in listOf("\"body\"", "\"display\"", "\"mono\"", "the name is the fix")) {
            assertContains(r.detail!!, named)
        }
    }

    @Test
    fun `a brand with no faces says so differently`() {
        val r = Recorder()
        val staging = FontStaging(r::fetch, r::register)
        staging.stage(FontManifest.EMPTY)

        val res = staging.resolve("body")
        assertEquals(FaceOutcome.UNPUBLISHED, res.outcome)
        assertContains(res.detail!!, "no font faces at all")
    }

    @Test
    fun `a weight nothing is near is named, with what the family does publish`() {
        val r = staged().resolve("body", weight = 900)
        assertEquals(FaceOutcome.TOO_FAR, r.outcome)
        assertNull(r.face)
        assertContains(r.detail!!, "\"400\"")
        assertContains(r.detail!!, "\"600\"")
    }

    @Test
    fun `a substitution is said out loud`() {
        val r = staged().resolve("body", weight = 450)
        assertEquals(FaceOutcome.SUBSTITUTED, r.outcome)
        assertEquals("Inter-Regular", r.postscriptName)
        assertContains(r.detail!!, "asked for weight 450")
        assertContains(r.detail!!, "not thickened")
    }

    @Test
    fun `an equidistant weight takes the heavier face`() {
        // 500 sits exactly between Inter's 400 and 600. At equal distance the
        // bolder of two is what a caller asking for more emphasis meant, and a
        // rule this easy to get backwards should fail a test rather than be
        // rediscovered.
        val r = staged().resolve("body", weight = 500)
        assertEquals(FaceOutcome.SUBSTITUTED, r.outcome)
        assertEquals("Inter-SemiBold", r.postscriptName)
    }

    @Test
    fun `an exact match says nothing`() {
        val r = staged().resolve("body", weight = 600)
        assertEquals(FaceOutcome.RESOLVED, r.outcome)
        assertNull(r.detail, "nothing happened, so nothing is reported")
        assertEquals("Inter-SemiBold", r.postscriptName)
    }

    @Test
    fun `a face with no postscript name is dropped rather than carried`() {
        // It could not be reached once registered, so registering it would cost
        // the download and give nothing back.
        val doc = Json.parse(
            """
            {"formats":{"native":"ttf"},"families":[{"family":"Ghost","roles":["body"],
             "faces":[{"weight":400,"style":"normal","src":{"ttf":"https://x/a.ttf"}}]}]}
            """.trimIndent(),
        ).asObject!!
        assertTrue(FontManifest.fromJson(doc).isEmpty)
    }
}
