package io.designless.serve.android

import io.designless.serve.BrandTokens

/**
 * Turning published values into the types Android draws with.
 *
 * ── WHAT IS NOT HERE, AND WHY ───────────────────────────────────────────────
 *
 * There is no Compose theme and no mark composable in this artifact. Both
 * would pull `androidx.compose.*` into every app that wants a logo, and pin a
 * Compose version while doing it. The whole pitch of this SDK is a thin client
 * that does not pick your frameworks for you, and a `ComposeTheme` that forces
 * a BOM upgrade is not thin.
 *
 * What is here are the conversions — colour strings to ARGB ints, `rem` to dp
 * — which is the part that is easy to get wrong and identical for everyone.
 * Building a `MaterialTheme`, a `ColorScheme` or a `FontFamily` from these is
 * about twenty lines in an app, written once, against whatever Compose version
 * that app already has. The README shows them.
 */

/**
 * `#rrggbb` or `#rrggbbaa` to an ARGB int, or null when it is neither.
 *
 * ── THE TRAP ────────────────────────────────────────────────────────────────
 *
 * CSS writes alpha LAST. Android's int keeps it FIRST. Reading one as the
 * other gives a colour wrong in both hue and opacity — and still a colour, so
 * nothing looks broken enough to investigate. `#11223380` read as ARGB is a
 * near-transparent blue-grey where a solid dark blue was meant.
 *
 * `Color.parseColor` gets this right for `#aarrggbb` and does not accept
 * `#rrggbbaa` at all, which is the form the brand publishes. That is why this
 * exists rather than a one-line delegation.
 *
 * Null rather than black on bad input. Black is a decision nobody made; null
 * lets a caller fall back to something it chose.
 */
public fun parseCssColor(value: String?): Int? {
    val trimmed = value?.trim() ?: return null
    if (!trimmed.startsWith("#")) return null
    var hex = trimmed.substring(1)

    if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")

    // Exclusive, and writing them as two independent `if`s is a real bug I
    // shipped once on another platform: a 6-digit colour became 8 by
    // prepending "ff", then fell into the swap below and came out as an
    // almost-transparent red. Every colour in the theme wrong, each one still
    // a plausible colour.
    val argb = when (hex.length) {
        6 -> "ff$hex"
        8 -> hex.substring(6, 8) + hex.substring(0, 6) // rrggbbaa -> aarrggbb
        else -> return null
    }

    return argb.toLongOrNull(16)?.toInt()
}

/**
 * A published length in dp.
 *
 * `rem` is a web unit and it reaches native as one, because the token tree is
 * one document for every platform. 16 is the browser base it is written
 * against, and the right default here.
 */
public fun BrandTokens.dp(path: String, rootDp: Double = 16.0): Float? =
    length(path, rootDp)?.toFloat()

/** A published colour as an ARGB int, or null. */
public fun BrandTokens.colorInt(path: String): Int? = parseCssColor(color(path))
