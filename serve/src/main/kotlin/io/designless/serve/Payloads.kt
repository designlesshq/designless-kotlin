package io.designless.serve

/**
 * The documents a brand serves, as things a Kotlin program can hold.
 *
 * These keep the payload rather than flattening it into named properties. A
 * brand can add a token branch without this module shipping a release, and a
 * client reading `tokens.color("brand.primary")` keeps working when it does.
 * Naming every branch here would turn every brand-side addition into an SDK
 * upgrade, which is the opposite of the point.
 */

/** The resolved token values for one brand, at one appearance and platform. */
public class BrandTokens internal constructor(
    /** The published version these values came from. `"1.0.3"`. */
    public val version: String,
    /**
     * The appearance the surface resolved to.
     *
     * What the *payload* says, not what the app is currently showing. Those
     * differ for as long as a fetch is in flight, and conflating them is how a
     * light mark ends up on a dark screen.
     */
    public val appearance: String?,
    private val tree: Map<String, JsonValue>,
) {
    public companion object {
        public fun parse(body: String): BrandTokens =
            fromJson(Json.parse(body).asObject ?: emptyMap())

        public fun fromJson(doc: Map<String, JsonValue>): BrandTokens = BrandTokens(
            version = doc["version"]?.asString ?: "",
            appearance = doc["appearance"]?.asString,
            tree = doc["tokens"]?.asObject ?: emptyMap(),
        )
    }

    /**
     * A value by dotted path: `color.bg.page`, `typography.fontSize.md`.
     *
     * Null rather than a throw. A missing token is an ordinary state — brands
     * differ in what they publish — and a client asking for one it did not get
     * should fall back, not crash a screen.
     */
    public operator fun get(path: String): JsonValue? {
        var node: JsonValue? = JsonValue.Obj(tree)
        for (segment in path.split(".")) {
            node = (node?.asObject ?: return null)[segment]
        }
        // A branch is not a value. Returning one would let a caller print a
        // map where it expected a colour.
        return node?.takeUnless { it.isBranch }
    }

    public fun string(path: String): String? = get(path)?.asString

    /**
     * A colour as the `#rrggbb` string the brand published.
     *
     * Deliberately not parsed into an Int here: this module has no Android
     * framework, and inventing a colour type would put a second opinion
     * between the brand and the screen. The Android artefact converts.
     */
    public fun color(path: String): String? = string("color.$path")

    public fun number(path: String): Double? = get(path)?.asDouble

    /**
     * A `rem` length in density-independent pixels, given the root size the
     * platform uses.
     *
     * `rem` is a web unit and it reaches native as one, because the token tree
     * is one document for every platform. 16 is the browser default and the
     * right default here.
     */
    public fun length(path: String, rootDp: Double = 16.0): Double? {
        val raw = string(path)?.trim() ?: return null
        return when {
            raw.endsWith("rem") -> raw.dropLast(3).toDoubleOrNull()?.times(rootDp)
            raw.endsWith("px") -> raw.dropLast(2).toDoubleOrNull()
            else -> raw.toDoubleOrNull()
        }
    }

    /**
     * The branch at [path], for a caller walking a subtree it does not know
     * the shape of. Empty rather than null.
     */
    public fun branch(path: String): Map<String, JsonValue> {
        var node: JsonValue? = JsonValue.Obj(tree)
        for (segment in path.split(".")) {
            node = (node?.asObject ?: return emptyMap())[segment]
        }
        return node?.asObject ?: emptyMap()
    }

    /** The top-level branch names this brand published. */
    public val branches: Set<String> get() = tree.keys
}

/** One thing a brand offers. */
public data class ServeCapability(
    public val name: String,
    /** `none` or `api-key`. */
    public val auth: String,
    public val description: String,
)

/** An addressable mark. */
public data class ServeAsset(
    public val role: String,
    public val formats: List<String>,
    /** The appearances this mark has artwork for. */
    public val variants: List<String>,
    public val url: String,
)

/** A composed destination, with its geometry. */
public data class ServeComposition(
    public val name: String,
    public val url: String,
    public val width: Int,
    public val height: Int,
    /**
     * One sentence on why this destination looks the way it does. Written for
     * a person deciding whether it is the one they want.
     */
    public val rationale: String,
)

/** What a brand offers, per brand, resolved. The document a client reads first. */
public data class BrandContext(
    public val publicId: String,
    public val version: String,
    public val capabilities: List<ServeCapability>,
    /** Named addresses: `tokens`, `fonts`, `events`, `styles`, `tree`. */
    public val fetch: Map<String, String>,
    public val assets: List<ServeAsset>,
    public val compositions: List<ServeComposition>,
    /** The appearances this brand resolves. */
    public val appearances: List<String>,
    /**
     * Filenames that are spoken for but do not answer yet. An address claim
     * and nothing more: no auth, no verb, no promise that anything works.
     */
    public val reserved: List<String>,
) {
    public companion object {
        public fun parse(body: String): BrandContext =
            fromJson(Json.parse(body).asObject ?: emptyMap())

        public fun fromJson(doc: Map<String, JsonValue>): BrandContext = BrandContext(
            publicId = doc["public_id"]?.asString ?: "",
            version = doc["version"]?.asString ?: "",
            capabilities = doc["capabilities"]?.asArray.orEmpty().mapNotNull { entry ->
                val c = entry.asObject ?: return@mapNotNull null
                val name = c["name"]?.asString ?: return@mapNotNull null
                ServeCapability(name, c["auth"]?.asString ?: "none", c["description"]?.asString ?: "")
            },
            fetch = buildMap {
                for ((key, value) in doc["fetch"]?.asObject.orEmpty()) {
                    value.asObject?.get("url")?.asString?.let { put(key, it) }
                }
            },
            assets = doc["assets"]?.asArray.orEmpty().mapNotNull { entry ->
                val a = entry.asObject ?: return@mapNotNull null
                val role = a["role"]?.asString ?: return@mapNotNull null
                ServeAsset(
                    role = role,
                    formats = a["formats"]?.asArray.orEmpty().mapNotNull { it.asString },
                    variants = a["variants"]?.asArray.orEmpty().mapNotNull { it.asString },
                    url = a["url"]?.asString ?: "",
                )
            },
            compositions = doc["compositions"]?.asArray.orEmpty().mapNotNull { entry ->
                val c = entry.asObject ?: return@mapNotNull null
                val name = c["name"]?.asString ?: return@mapNotNull null
                ServeComposition(
                    name = name,
                    url = c["url"]?.asString ?: "",
                    width = c["width"]?.asDouble?.toInt() ?: 0,
                    height = c["height"]?.asDouble?.toInt() ?: 0,
                    rationale = c["rationale"]?.asString ?: "",
                )
            },
            appearances = doc["appearance"]?.asArray.orEmpty().mapNotNull { it.asString },
            reserved = doc["reserved"]?.asArray.orEmpty().mapNotNull { it.asString },
        )
    }

    /**
     * Whether this brand offers [name].
     *
     * Read this rather than assuming. The capability list is per-brand and
     * resolved, so it is the difference between an address that answers for
     * this brand and one that does not.
     */
    public fun offers(name: String): Boolean = capabilities.any { it.name == name }

    public fun assetForRole(role: String): ServeAsset? = assets.firstOrNull { it.role == role }

    public fun compositionNamed(name: String): ServeComposition? =
        compositions.firstOrNull { it.name == name }
}
