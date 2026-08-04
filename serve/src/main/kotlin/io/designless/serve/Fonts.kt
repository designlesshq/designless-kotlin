package io.designless.serve

/**
 * Which font file to fetch, what to call it once registered, and getting it
 * into the platform before anything asks for it.
 *
 * ── THE TWO RULES ───────────────────────────────────────────────────────────
 *
 * **A face is reached by its PostScript name.** On Android that name is the
 * key you give `Typeface.createFromFile` and the one a `FontFamily` entry
 * carries in Compose. The family name is a label for humans.
 *
 * Measured on Apple, where the same rule holds and the failure is easiest to
 * see: with two Inter faces registered, asking for the family name `Inter`
 * returns `Inter-Regular` — always, whichever weight you meant. Not an error,
 * not a fallback anyone notices. A heading asking for SemiBold gets a real
 * font, correctly rendered, quietly the wrong one.
 *
 * **Register before you render.** A `TextView` that has already measured does
 * not re-measure because a typeface arrived afterwards, so a view laid out
 * during the download keeps the platform font for its whole life. So
 * [FontStaging] does not offer a "register these" call a caller can forget to
 * await: a face comes out only after its file is staged, and
 * [FaceOutcome.NOT_REGISTERED] comes out before then. Getting the order wrong
 * means ignoring a returned value rather than forgetting a step.
 *
 * The registration itself is injected, because `Typeface` lives in the Android
 * framework and this module has no framework. The rule lives here; the one
 * line that does the install is supplied by the Android artifact or by a test.
 */

/** Upright or italic. The wire carries these two and nothing else. */
public enum class FaceStyle(public val wire: String) {
    NORMAL("normal"),
    ITALIC("italic"),
    ;

    public companion object {
        public fun fromWire(value: String?): FaceStyle =
            if (value == "italic") ITALIC else NORMAL
    }
}

/** One file: one weight, one style, one name to reach it by. */
public data class FontFace(
    /** The family this face belongs to. A label, not an identity. */
    public val family: String,
    public val weight: Int,
    public val style: FaceStyle,
    /**
     * What the platform will know this face as once it is registered. The only
     * string that reaches a face.
     */
    public val postscriptName: String,
    /** Format to url. `ttf` for Android, `woff2` for the web. */
    public val sources: Map<String, String>,
) {
    public fun sourceFor(format: String): String? = sources[format]
}

/** A family, and the roles the brand fills with it. */
public data class FontFamily(
    public val name: String,
    /**
     * `body`, `display`, `mono`. A family with no roles is published but
     * unused, which is legal.
     */
    public val roles: List<String>,
    public val faces: List<FontFace>,
) {
    /**
     * The distinct weights and styles this family publishes, as a sentence.
     * These strings are read by a person at the moment something looks wrong.
     */
    internal val publishedWeights: String
        get() = sentence(
            faces.map { if (it.style == FaceStyle.ITALIC) "${it.weight} italic" else "${it.weight}" }
                .distinct().sorted(),
        )
}

internal fun sentence(items: List<String>): String = when (items.size) {
    0 -> "none"
    1 -> "\"${items[0]}\""
    else -> items.dropLast(1).joinToString(", ") { "\"$it\"" } + " and \"${items.last()}\""
}

/** The whole font list for a brand. */
public class FontManifest(
    public val families: List<FontFamily>,
    /** The format to download on a platform that registers files. `ttf`. */
    public val nativeFormat: String,
    /** The format a browser wants. `woff2`. */
    public val webFormat: String,
) {
    public companion object {
        /**
         * An empty list is a real answer, not an error: a brand may publish no
         * downloadable face and expect the platform's own type.
         */
        public val EMPTY: FontManifest = FontManifest(emptyList(), "ttf", "woff2")

        /** How far from an asked-for weight a face may sit and still be used. */
        public const val MAX_WEIGHT_GAP: Int = 200

        public fun parse(body: String): FontManifest =
            fromJson(Json.parse(body).asObject ?: emptyMap())

        public fun fromJson(doc: Map<String, JsonValue>): FontManifest {
            val formats = doc["formats"]?.asObject ?: emptyMap()
            val families = mutableListOf<FontFamily>()

            for (raw in doc["families"]?.asArray.orEmpty()) {
                val entry = raw.asObject ?: continue
                val family = entry["family"]?.asString?.takeIf { it.isNotEmpty() } ?: continue

                val faces = mutableListOf<FontFace>()
                for (rf in entry["faces"]?.asArray.orEmpty()) {
                    val faceObj = rf.asObject ?: continue
                    // A face with no PostScript name cannot be reached once
                    // registered, so it is dropped rather than carried as
                    // something that looks usable. Registering it would cost
                    // the download and give nothing back.
                    val ps = faceObj["postscriptName"]?.asString?.takeIf { it.isNotEmpty() } ?: continue

                    val src = buildMap {
                        for ((k, v) in faceObj["src"]?.asObject.orEmpty()) {
                            v.asString?.takeIf { it.isNotEmpty() }?.let { put(k, it) }
                        }
                    }
                    if (src.isEmpty()) continue

                    faces.add(
                        FontFace(
                            family = family,
                            weight = faceObj["weight"]?.asDouble?.toInt() ?: 400,
                            style = FaceStyle.fromWire(faceObj["style"]?.asString),
                            postscriptName = ps,
                            sources = src,
                        ),
                    )
                }
                if (faces.isEmpty()) continue

                families.add(
                    FontFamily(
                        name = family,
                        roles = entry["roles"]?.asArray.orEmpty().mapNotNull { it.asString },
                        faces = faces,
                    ),
                )
            }

            return FontManifest(
                families = families,
                nativeFormat = formats["native"]?.asString ?: "ttf",
                webFormat = formats["web"]?.asString ?: "woff2",
            )
        }

        internal fun closest(faces: List<FontFace>, weight: Int, style: FaceStyle): FontFace? {
            var best: FontFace? = null
            var bestGap = Int.MAX_VALUE
            for (f in faces) {
                if (f.style != style) continue
                val gap = kotlin.math.abs(f.weight - weight)
                if (gap > MAX_WEIGHT_GAP) continue
                // Ties go to the heavier face: at equal distance, the bolder of
                // two is what a caller asking for emphasis meant.
                if (gap < bestGap || (gap == bestGap && best != null && f.weight > best.weight)) {
                    best = f
                    bestGap = gap
                }
            }
            return best
        }
    }

    public val isEmpty: Boolean get() = families.isEmpty()

    /**
     * The roles this brand publishes a family for.
     *
     * Named so a diagnostic can say which roles exist rather than only which
     * one was asked for. A role the brand skipped and a role the caller
     * misspelled are the same event at the point it happens, and the second is
     * the common one.
     */
    public val publishedRoles: List<String> get() = families.flatMap { it.roles }

    public val allFaces: List<FontFace> get() = families.flatMap { it.faces }

    public fun familyForRole(role: String): FontFamily? =
        families.firstOrNull { role in it.roles }

    /** The best face for a role, or null when nothing is close enough. */
    public fun faceFor(
        role: String,
        weight: Int = 400,
        style: FaceStyle = FaceStyle.NORMAL,
    ): FontFace? {
        val family = familyForRole(role) ?: return null
        return closest(family.faces, weight, style)
            ?: closest(
                family.faces,
                weight,
                if (style == FaceStyle.NORMAL) FaceStyle.ITALIC else FaceStyle.NORMAL,
            )
    }
}

/**
 * What happened when a role was asked for a face, and why.
 *
 * Every way of ending up in the platform's own type gets its own value,
 * because what to do about each differs: one is a build to fix, one is a
 * request to retry, one is the brand getting exactly what it published.
 */
public enum class FaceOutcome {
    /** A face was found and the platform can reach it. */
    RESOLVED,

    /** A face was found, but not the weight or style asked for. */
    SUBSTITUTED,

    /** The font list has not been read. A fetch to retry, not a build to fix. */
    NOT_LOADED,

    /** The brand publishes no family for this role. */
    UNPUBLISHED,

    /** The family is published but nothing it carries is near this weight. */
    TOO_FAR,

    /** A face exists and its file is not staged. Always a build to fix. */
    NOT_REGISTERED,
}

/** The answer to "what face fills this role", with the reason attached. */
public data class FaceResolution(
    public val outcome: FaceOutcome,
    /** Null unless the outcome is [FaceOutcome.RESOLVED] or [FaceOutcome.SUBSTITUTED]. */
    public val face: FontFace? = null,
    /** One sentence a developer can act on. Null when there is nothing to say. */
    public val detail: String? = null,
) {
    /** The name to hand the platform, or null when there is none. */
    public val postscriptName: String? get() = face?.postscriptName

    public val isUsable: Boolean get() = face != null
}

/** What a staging run did, for a caller that wants to report it. */
public data class StagingReport(
    /** PostScript names the platform can now resolve. */
    public val registered: List<String>,
    /**
     * PostScript name to the reason it did not land. A face here is not a
     * crash: the role it filled falls back to the platform's own type, and
     * [FontStaging.resolve] says so rather than pretending.
     */
    public val failed: Map<String, String>,
    /** How many faces came from the cache rather than the network. */
    public val fromCache: Int,
) {
    public val isComplete: Boolean get() = failed.isEmpty()
    public val total: Int get() = registered.size + failed.size
}

/**
 * Somewhere to keep a downloaded file between launches, so the second launch
 * does not repeat the first launch's downloads.
 */
public interface FontCache {
    public fun read(key: String): ByteArray?
    public fun write(key: String, bytes: ByteArray)
}

/**
 * Downloads, caches and registers the faces a brand publishes, and answers
 * which of them the platform can actually reach.
 *
 * @param fetch bytes at a url. Injected so this module needs no HTTP
 *   dependency and can be driven by a test with no network.
 * @param register installs a face under its PostScript name. Must return only
 *   once the platform can resolve that name — a registrar that returns early
 *   reintroduces exactly the race this class exists to remove.
 */
public class FontStaging(
    private val fetch: (String) -> ByteArray,
    private val register: (String, ByteArray) -> Unit,
    private val cache: FontCache? = null,
    private val format: String? = null,
) {
    private var manifest: FontManifest? = null
    private val staged = linkedSetOf<String>()
    private val failures = linkedMapOf<String, String>()

    /** PostScript names the platform can resolve right now. */
    public val stagedNames: Set<String> get() = staged.toSet()

    /**
     * Take a font list and stage everything in it.
     *
     * Idempotent and safe to call again: a face already staged is not fetched
     * twice. Synchronised because two screens calling this on launch is the
     * ordinary case, and registering the same face twice is not.
     */
    @Synchronized
    public fun stage(manifest: FontManifest): StagingReport {
        this.manifest = manifest
        val wanted = format ?: manifest.nativeFormat
        val registered = mutableListOf<String>()
        var fromCache = 0

        for (face in manifest.allFaces) {
            if (face.postscriptName in staged) {
                registered.add(face.postscriptName)
                continue
            }

            val url = face.sourceFor(wanted)
            if (url == null) {
                failures[face.postscriptName] =
                    "the brand publishes no $wanted file for this face"
                continue
            }

            try {
                val key = "${face.postscriptName}.$wanted"
                val cached = cache?.read(key)
                val bytes = if (cached != null) {
                    fromCache++
                    cached
                } else {
                    fetch(url).also {
                        // Written after a successful fetch and before
                        // registration, so a launch that dies mid-registration
                        // still has the file next time.
                        cache?.write(key, it)
                    }
                }

                // The ordering, in one place. Nothing below this line runs
                // until the platform reports the face installed.
                register(face.postscriptName, bytes)

                staged.add(face.postscriptName)
                failures.remove(face.postscriptName)
                registered.add(face.postscriptName)
            } catch (e: Exception) {
                // One face failing is not the run failing. The role it filled
                // falls back to the platform's own type and `resolve` reports
                // why.
                failures[face.postscriptName] = e.message ?: e.toString()
            }
        }

        return StagingReport(registered, failures.toMap(), fromCache)
    }

    /**
     * What fills [role] at this weight and style, and whether the platform can
     * reach it.
     *
     * The only way to get a face out of this class, and it will not hand back
     * one whose file is not staged. That is the ordering rule expressed as a
     * return value rather than as advice in a README.
     */
    public fun resolve(
        role: String,
        weight: Int = 400,
        style: FaceStyle = FaceStyle.NORMAL,
    ): FaceResolution {
        val manifest = this.manifest
            ?: return FaceResolution(
                FaceOutcome.NOT_LOADED,
                detail = "The font list has not been read yet, so no face can be " +
                    "resolved. This is a fetch to retry, not a build to fix.",
            )

        val family = manifest.familyForRole(role)
            ?: return FaceResolution(
                FaceOutcome.UNPUBLISHED,
                detail = manifest.publishedRoles.let { published ->
                    if (published.isEmpty()) {
                        "This brand publishes no font faces at all, so every role " +
                            "uses the platform's own type."
                    } else {
                        "This brand publishes no face for the \"$role\" role, so that " +
                            "text uses the platform's own type. The roles it does " +
                            "publish are ${sentence(published)}. If one of those is " +
                            "the one you meant, the name is the fix."
                    }
                },
            )

        val face = manifest.faceFor(role, weight, style)
            ?: return FaceResolution(
                FaceOutcome.TOO_FAR,
                detail = "The \"$role\" role uses \"${family.name}\", which publishes " +
                    "${family.publishedWeights} and nothing within " +
                    "${FontManifest.MAX_WEIGHT_GAP} of $weight. That text uses the " +
                    "platform's own type rather than a face too far from what you " +
                    "asked for.",
            )

        if (face.postscriptName !in staged) {
            val why = failures[face.postscriptName]
            return FaceResolution(
                FaceOutcome.NOT_REGISTERED,
                detail = if (why == null) {
                    "The face \"${face.postscriptName}\" has not been registered yet, " +
                        "so the platform cannot reach it. Call stage() before building " +
                        "a typeface: a view laid out before its font lands keeps the " +
                        "platform font for the life of that view."
                } else {
                    "The face \"${face.postscriptName}\" could not be registered: $why. " +
                        "The \"$role\" role uses the platform's own type."
                },
            )
        }

        if (face.weight != weight || face.style != style) {
            return FaceResolution(
                FaceOutcome.SUBSTITUTED,
                face = face,
                detail = "The \"$role\" role was asked for weight $weight ${style.wire} " +
                    "and \"${family.name}\" publishes ${family.publishedWeights}, so " +
                    "\"${face.postscriptName}\" is being used. It is not thickened, " +
                    "thinned or slanted to match.",
            )
        }

        return FaceResolution(FaceOutcome.RESOLVED, face = face)
    }
}
