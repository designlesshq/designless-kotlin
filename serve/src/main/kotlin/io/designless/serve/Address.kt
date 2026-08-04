package io.designless.serve

/**
 * Where to ask for a thing.
 *
 * Every address this module builds comes from here, and every one honours the
 * grammar the surface publishes at `/serve/protocol.v1.json` rather than being
 * assembled at the call site. A parameter sent to an address that does not
 * take it is not harmless: the surface answers 400 on a value it refuses, and
 * one that is silently dropped is worse, because the caller believes it asked
 * for something it did not get.
 */

/** The published appearance values. */
public enum class Appearance(public val wire: String) {
    LIGHT("light"),
    DARK("dark"),
    ;

    public companion object {
        public fun fromWire(value: String?): Appearance? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * The published platform values.
 *
 * `WEB` is what the surface applies when the parameter is absent, so asking
 * for it and leaving it off give the same payload.
 */
public enum class ServePlatform(public val wire: String) {
    WEB("web"),
    IOS("ios"),
    ANDROID("android"),
}

/** The published asset formats. */
public enum class AssetFormat(public val wire: String) {
    SVG("svg"),
    PNG("png"),
}

/**
 * The closed size ladder for a raster asset.
 *
 * A value off this ladder is a 400, not a resize: the surface renders at the
 * sizes it has decided are legible and refuses the rest. Modelled as an enum
 * so a caller cannot express the request that fails.
 */
public enum class AssetSize(public val pixels: Int) {
    PX16(16),
    PX32(32),
    PX48(48),
    PX64(64),
    PX128(128),
    PX192(192),
    PX256(256),
    PX512(512),
    PX1024(1024),
    ;

    public companion object {
        /**
         * The nearest published size at or above [wanted], or the largest.
         *
         * Rounds up rather than down: a mark drawn larger than its box and
         * scaled down stays sharp, and one drawn smaller and scaled up does
         * not.
         */
        public fun atLeast(wanted: Int): AssetSize =
            entries.firstOrNull { it.pixels >= wanted } ?: PX1024
    }
}

/** A brand's address space. Holds no state and makes no requests. */
public class ServeAddresses(
    public val publicId: String,
    public val origin: String = DEFAULT_ORIGIN,
) {
    public companion object {
        /**
         * Where the brand path lives. Overridable for a proxy or a test
         * double, not for pointing at a different product.
         */
        public const val DEFAULT_ORIGIN: String = "https://cdn.designless.app"
    }

    private val base: String get() = "$origin/r/$publicId"

    /** What this brand offers and where. The document a client reads first. */
    public fun context(version: String? = null): String =
        build("context.json", emptyMap(), version)

    /** Resolved token values, for mapping onto a platform theme. */
    public fun tokens(
        appearance: Appearance? = null,
        platform: ServePlatform? = null,
        version: String? = null,
    ): String = build(
        "tokens.json",
        buildMap {
            appearance?.let { put("appearance", it.wire) }
            platform?.let { put("platform", it.wire) }
        },
        version,
    )

    /** The font files to download and register. */
    public fun fonts(version: String? = null): String =
        build("fonts.json", emptyMap(), version)

    /**
     * The stream that signals when this brand changes.
     *
     * Takes no version: pinning a stream to a past version would mean
     * subscribing to something that can no longer change.
     */
    public fun events(): String = "$base/events"

    /**
     * A mark, by the role it fills.
     *
     * Throws rather than sending a request the surface will refuse. A 400 from
     * a CDN reaches a caller as a blank ImageView with no explanation
     * attached; this is the same refusal, close enough to the mistake to name
     * it.
     */
    public fun asset(
        role: String,
        format: AssetFormat = AssetFormat.SVG,
        appearance: Appearance? = null,
        size: AssetSize? = null,
    ): String {
        require(!(size != null && format == AssetFormat.SVG)) {
            "An svg has no size to pick. Ask for AssetFormat.PNG to use the " +
                "ladder, or drop the size and let the vector scale."
        }
        return build(
            "assets/$role.${format.wire}",
            buildMap {
                appearance?.let { put("appearance", it.wire) }
                size?.let { put("size", it.pixels.toString()) }
            },
            null,
        )
    }

    /**
     * A composed destination — an app icon, a social card — by its name.
     *
     * These carry their own geometry, so they take neither a size nor an
     * appearance: the destination decides both, and passing either would be
     * asking a question the address does not answer.
     */
    public fun composition(name: String, format: AssetFormat = AssetFormat.PNG): String =
        build("assets/$name.${format.wire}", emptyMap(), null)

    private fun build(file: String, params: Map<String, String>, version: String?): String {
        val all = buildMap {
            putAll(params)
            version?.let { put("version", it) }
        }
        if (all.isEmpty()) return "$base/$file"
        // Sorted, so the same request is the same string every time. A cache
        // key built from the URL is then stable and two callers asking the
        // same thing share one entry.
        val query = all.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        return "$base/$file?$query"
    }

    override fun toString(): String = "ServeAddresses($publicId at $origin)"
}
