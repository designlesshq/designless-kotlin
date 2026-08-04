package io.designless.serve.android

import android.graphics.Typeface
import io.designless.serve.FontCache
import io.designless.serve.FontStaging
import java.io.File

/**
 * Getting a downloaded face into Android, under the name that will reach it.
 *
 * ── WHY A FILE, AND WHY IT STAYS ────────────────────────────────────────────
 *
 * `Typeface.createFromFile` is the runtime path on Android. There is no
 * register-from-memory call and no process-wide font registry: a `Typeface` is
 * an object you hold and hand to a view, not a name the system resolves later.
 *
 * That has one consequence worth stating plainly, because it differs from
 * every other platform in this family. On Apple and on the web, registration
 * puts a name into a system table and any later lookup by that name finds it.
 * Here, the PostScript name is the key into *this* map — [AndroidFontRegistry]
 * — and nothing outside it knows the name at all. The rule is the same
 * ("reach a face by its PostScript name") but the table is ours.
 *
 * The file stays on disk. `Typeface.createFromFile` reads lazily, and deleting
 * the file underneath a live Typeface is how you get glyphs that render as
 * blanks on some devices and not others.
 */
public interface TypefaceStore {
    /** Where staged font files live. Usually `context.filesDir.resolve("designless-fonts")`. */
    public val directory: File
}

/**
 * The typefaces this process has loaded, by PostScript name.
 *
 * Android has no system-wide runtime font table, so this is it. A caller
 * resolves a role through [FontStaging] and then looks the returned name up
 * here — the two halves of what is one call on other platforms.
 */
public class AndroidFontRegistry(private val directory: File) {
    private val loaded = linkedMapOf<String, Typeface>()

    init {
        directory.mkdirs()
    }

    /**
     * Install a face. Hand this to [FontStaging] as its `register` argument.
     *
     * Throws if Android will not read the file, which is what makes the
     * ordering rule hold here: the core does not release a face until this
     * returns.
     */
    public fun register(postscriptName: String, bytes: ByteArray) {
        val file = File(directory, "$postscriptName.ttf")
        file.writeBytes(bytes)

        // createFromFile returns Typeface.DEFAULT rather than throwing when it
        // cannot parse the file — a silent substitution dressed as success,
        // and exactly the failure this package exists to prevent. Comparing
        // against DEFAULT is the only signal available.
        val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull()
        if (typeface == null || typeface == Typeface.DEFAULT) {
            file.delete()
            error(
                "Android could not read \"$postscriptName\" as a font. " +
                    "createFromFile fell back to the default typeface, which " +
                    "would have rendered as the platform font with no warning.",
            )
        }
        loaded[postscriptName] = typeface
    }

    /** The typeface for a PostScript name, or null when it is not loaded. */
    public operator fun get(postscriptName: String): Typeface? = loaded[postscriptName]

    /** Every name this process has loaded. */
    public val names: Set<String> get() = loaded.keys.toSet()

    /** A cache backed by the same directory, so a second launch skips the network. */
    public fun cache(): FontCache = object : FontCache {
        override fun read(key: String): ByteArray? =
            File(directory, key).takeIf { it.isFile }?.readBytes()

        override fun write(key: String, bytes: ByteArray) {
            File(directory, key).writeBytes(bytes)
        }
    }
}

/**
 * A [FontStaging] wired to Android's typeface loader.
 *
 * ```kotlin
 * val registry = AndroidFontRegistry(context.filesDir.resolve("designless-fonts"))
 * val staging = androidFontStaging(registry) { url -> httpGetBytes(url) }
 * staging.stage(brand.loadFonts())
 *
 * val body = staging.resolve("body", weight = 600)
 * body.postscriptName?.let { textView.typeface = registry[it] }
 * ```
 */
public fun androidFontStaging(
    registry: AndroidFontRegistry,
    fetch: (String) -> ByteArray,
): FontStaging = FontStaging(
    fetch = fetch,
    register = registry::register,
    cache = registry.cache(),
)
