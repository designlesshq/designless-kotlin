package io.designless.serve.android

import io.designless.serve.BrandTokens
import io.designless.serve.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The conversions, and the one that is easy to get wrong in a way nothing
 * looks broken enough to investigate.
 */
class ValuesTest {
    @Test
    fun `rrggbb parses opaque`() {
        assertEquals(0xFF060608.toInt(), parseCssColor("#060608"))
    }

    @Test
    fun `rgb expands`() {
        assertEquals(0xFFAABBCC.toInt(), parseCssColor("#abc"))
    }

    @Test
    fun `alpha goes where Android keeps it, not where CSS wrote it`() {
        // The trap. CSS writes alpha last, Android's int keeps it first, and
        // reading one as the other gives a colour wrong in both hue and
        // opacity while still looking like a colour.
        val c = parseCssColor("#11223380")!!
        assertEquals(0x80112233.toInt(), c)
        assertEquals(0x80, (c ushr 24) and 0xFF, "alpha")
        assertEquals(0x11, (c ushr 16) and 0xFF, "red")
        assertEquals(0x22, (c ushr 8) and 0xFF, "green")
        assertEquals(0x33, c and 0xFF, "the alpha byte was read as blue")
    }

    @Test
    fun `a six digit colour does not fall through the alpha swap`() {
        // The bug this shape prevents, shipped once on another platform: a
        // 6-digit colour becomes 8 by prepending "ff", then gets swapped as
        // if it were rrggbbaa and comes out almost transparent.
        for (hex in listOf("#000000", "#ffffff", "#060608", "#123456")) {
            val c = parseCssColor(hex)!!
            assertEquals(0xFF, (c ushr 24) and 0xFF, "$hex should be fully opaque")
        }
    }

    @Test
    fun `anything that is not a hex colour is null, not black`() {
        // Null lets a caller fall back. Black is a decision nobody made.
        for (bad in listOf("rgb(1,2,3)", "red", "", "#12", "#zzzzzz", "060608", null)) {
            assertNull(parseCssColor(bad), "$bad")
        }
    }

    @Test
    fun `rem converts against the root size the platform passes`() {
        val tokens = BrandTokens.fromJson(
            Json.parse("""{"version":"1","tokens":{"typography":{"fontSize":{"md":"0.910rem"}}}}""")
                .asObject!!,
        )
        assertEquals(14.56f, tokens.dp("typography.fontSize.md")!!, 0.001f)
        assertEquals(9.1f, tokens.dp("typography.fontSize.md", rootDp = 10.0)!!, 0.001f)
    }

    @Test
    fun `a colour reaches the token extension`() {
        val tokens = BrandTokens.fromJson(
            Json.parse("""{"version":"1","tokens":{"color":{"bg":{"page":"#060608"}}}}""")
                .asObject!!,
        )
        assertEquals(0xFF060608.toInt(), tokens.colorInt("bg.page"))
        assertNull(tokens.colorInt("bg.nonesuch"))
    }
}
