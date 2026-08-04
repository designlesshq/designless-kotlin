package io.designless.serve

/**
 * The five verbs, and the rule about when a change is allowed to land.
 *
 * ── FETCH THEN ACTIVATE, NOT FETCH THEN SWAP ────────────────────────────────
 *
 * A brand can change while an app is open, and what an app must not do is
 * restyle itself underneath someone mid-task. On Android it largely cannot
 * anyway: a theme is applied at inflation, so a mid-session swap gets you a
 * half-updated app rather than an updated one — the screen in front of the
 * person keeps the old values while anything created afterwards gets the new
 * ones.
 *
 * So a change arriving over the stream is fetched and held, and becomes live
 * when the caller says the moment is right — normally `ON_START`, or an
 * Activity recreation. [activate] is that moment. Until it is called, [tokens]
 * keeps answering with what is on screen and [pending] says something is
 * waiting.
 */

/** What the brand is doing. */
public enum class BrandStatus {
    /** Nothing has been read yet, from disk or the network. */
    EMPTY,

    /** A persisted snapshot is showing while a fetch is in flight. */
    FROM_SNAPSHOT,

    /** Values fetched this session are live. */
    LIVE,

    /** The last fetch failed. Whatever was already live stays live. */
    STALE,
}

/**
 * Somewhere to keep the last payload between launches, so a cold start shows
 * the brand rather than a blank screen while a request is in flight.
 *
 * `SharedPreferences` is the obvious implementation and is the reason this is
 * synchronous: a snapshot has to be readable before the first view is
 * inflated, and anything suspending has already lost that race.
 */
public interface SnapshotStore {
    public fun read(key: String): String?
    public fun write(key: String, value: String)
}

/**
 * One brand, and everything a client asks of it.
 *
 * @param fetch the body at a url. Injected: this module has no HTTP dependency
 *   and no opinion about the one an app already uses. Blocking, and expected
 *   to be called off the main thread — wrapping it in a coroutine is the
 *   caller's choice, not this module's.
 */
public class Brand(
    public val publicId: String,
    private val fetch: (String) -> String,
    private val store: SnapshotStore? = null,
    origin: String = ServeAddresses.DEFAULT_ORIGIN,
    /** The appearance to ask for, or null to take the brand's own default. */
    public var appearance: Appearance? = null,
    /**
     * The platform to resolve for. Defaults to `ANDROID`, which is the point
     * of this module: the payload carries native font stacks and a 48dp
     * minimum touch target rather than their web equivalents.
     */
    public var platform: ServePlatform = ServePlatform.ANDROID,
) {
    public val addresses: ServeAddresses = ServeAddresses(publicId, origin)

    private var liveTokens: BrandTokens? = null
    private var pendingTokens: BrandTokens? = null
    private val observers = mutableListOf<(BrandTokens) -> Unit>()

    /** What the brand is doing. */
    public var status: BrandStatus = BrandStatus.EMPTY
        private set

    /** The font list, or null before it has been read. */
    public var fonts: FontManifest? = null
        private set

    /** What this brand offers, or null before `context.json` has been read. */
    public var context: BrandContext? = null
        private set

    init {
        val raw = store?.read(snapshotKey(publicId))
        if (raw != null) {
            // A snapshot that does not parse is one from an older shape or a
            // half-written file. Neither is worth failing a launch over; the
            // fetch that follows replaces it.
            val restored = runCatching { BrandTokens.parse(raw) }.getOrNull()
            if (restored != null) {
                liveTokens = restored
                status = BrandStatus.FROM_SNAPSHOT
            }
        }
    }

    /**
     * The values a caller should be rendering with, or null before anything
     * has been read.
     */
    public val tokens: BrandTokens? get() = liveTokens

    /**
     * A fetched payload waiting for [activate], or null.
     *
     * Non-null is the honest signal that the brand on screen is one publish
     * behind. A caller can surface it, ignore it, or activate on the spot.
     */
    public val pending: BrandTokens? get() = pendingTokens

    /**
     * The appearance the LIVE payload resolved to.
     *
     * Not the appearance that was asked for. Between asking and landing they
     * differ, and every address this brand builds uses this one, so a mark and
     * the screen it sits on cannot disagree about which appearance is showing.
     */
    public val liveAppearance: String? get() = liveTokens?.appearance

    // ── The five verbs ───────────────────────────────────────────────────

    /**
     * Read the tokens and make them live.
     *
     * A persisted snapshot is already showing by the time this is called,
     * because the constructor restores one synchronously.
     */
    public fun initialize() {
        refresh(activateNow = true)
    }

    /**
     * Fetch the current payload and hold it until [activate], unless
     * [activateNow] is set.
     */
    public fun refresh(activateNow: Boolean = false) {
        val url = addresses.tokens(appearance, platform)
        try {
            val body = fetch(url)
            val next = BrandTokens.parse(body)

            if (activateNow || liveTokens == null) {
                liveTokens = next
                pendingTokens = null
                status = BrandStatus.LIVE
                notifyObservers(next)
            } else {
                pendingTokens = next
            }

            // Written after parsing, so a malformed body never replaces a
            // snapshot that works.
            store?.write(snapshotKey(publicId), body)
        } catch (e: Exception) {
            // A failed fetch leaves whatever is live exactly as it is. An app
            // that was showing the brand goes on showing it.
            status = if (liveTokens == null) BrandStatus.EMPTY else BrandStatus.STALE
            throw e
        }
    }

    /**
     * Promote a held payload to live, and tell observers.
     *
     * Call this when the app is in a state where restyling is acceptable —
     * normally `ON_START`. Returns whether anything moved.
     */
    public fun activate(): Boolean {
        val next = pendingTokens ?: return false
        liveTokens = next
        pendingTokens = null
        status = BrandStatus.LIVE
        notifyObservers(next)
        return true
    }

    /**
     * Read `fonts.json`. Separate from [initialize] because a caller that
     * renders no text this launch should not pay for it.
     */
    public fun loadFonts(): FontManifest =
        FontManifest.parse(fetch(addresses.fonts())).also { fonts = it }

    /** Read `context.json`. */
    public fun loadContext(): BrandContext =
        BrandContext.parse(fetch(addresses.context())).also { context = it }

    /**
     * The address of a mark.
     *
     * Uses [liveAppearance] rather than the requested appearance, for the
     * reason on that property.
     */
    public fun assetUrl(
        role: String,
        format: AssetFormat = AssetFormat.PNG,
        size: AssetSize? = null,
        appearance: Appearance? = null,
    ): String = addresses.asset(
        role,
        format,
        appearance ?: Appearance.fromWire(liveAppearance) ?: this.appearance,
        size,
    )

    /** The address of a composed destination. */
    public fun compositionUrl(name: String, format: AssetFormat = AssetFormat.PNG): String =
        addresses.composition(name, format)

    // ── Observing ────────────────────────────────────────────────────────

    /**
     * Called whenever what a caller would read has changed: after a fetch
     * lands and is activated, and after [activate] promotes a held payload.
     *
     * It does not fire when a payload arrives and is held. Nothing a caller
     * can see changed at that moment, and waking every observer to say so is
     * how a stream turns into a re-inflate storm.
     */
    public fun observe(block: (BrandTokens) -> Unit) {
        observers.add(block)
    }

    public fun removeObserver(block: (BrandTokens) -> Unit) {
        observers.remove(block)
    }

    private fun notifyObservers(tokens: BrandTokens) {
        for (observer in observers.toList()) observer(tokens)
    }

    private companion object {
        fun snapshotKey(publicId: String) = "designless.$publicId"
    }
}
