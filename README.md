# designless-kotlin

Your brand in a Kotlin or Android app. Colours, type, spacing and marks come from your published brand, and change when you publish.

| Artifact | What it is |
|---|---|
| `io.designless:serve` | The protocol client. Pure Kotlin/JVM, **zero dependencies**, no Android framework. |
| `io.designless:serve-android` | The Android layer. Typeface registration, colour and length conversion, a snapshot store, foreground activation. Depends only on the core and the framework. |

## Install

```kotlin
dependencies {
    implementation("io.designless:serve-android:0.1.0")   // Android
    // or, on a plain JVM:
    implementation("io.designless:serve:0.1.0")
}
```

## Use

```kotlin
val brand = Brand(
    publicId = "r_XXXX",
    fetch = { url -> client.newCall(Request.Builder().url(url).build()).execute().body!!.string() },
    store = prefsSnapshotStore,      // optional, but shows the brand on the first frame
)

brand.initialize()                   // call off the main thread

brand.tokens?.color("bg.page")                    // "#060608"
brand.tokens?.length("typography.fontSize.md")    // 14.56
brand.assetUrl("logo-symbol", size = AssetSize.PX256)
```

With a `store`, the last payload is already readable before the first view is inflated — the constructor restores it synchronously. Without one, the first frame is unbranded, and that frame is the one a person judges the app by.

## Zero dependencies, including the JSON

`org.json` exists on Android and nowhere else; `kotlinx-serialization` would pin a version on every consumer. Dependency conflicts are the loudest complaint about Android libraries, and an SDK whose pitch is "thin client" that drags in a serialization runtime is not one. So this ships a small reader for exactly the subset RFC 8259 defines — no comments, no trailing commas, no NaN — tested against the real payloads and against the malformed input a network eventually hands you.

## Fonts, and the mistake that costs the most

**A face is reached by its PostScript name.** That is the key you give `Typeface.createFromFile` and the one a Compose `FontFamily` entry carries. The family name is a label for humans.

Measured on Apple, where the same rule holds and the failure is easiest to see: with two Inter faces registered, asking for the family name `Inter` returns `Inter-Regular` — always, whichever weight you meant. Not an error, not a fallback anyone notices. A heading asking for SemiBold gets a real font, correctly rendered, quietly the wrong one.

**Register before you render.** A `TextView` that has already measured does not re-measure because a typeface arrived afterwards, so a view laid out during the download keeps the platform font for its whole life. `FontStaging` will not hand you a face whose file is not staged:

```kotlin
val staging = FontStaging(
    fetch = { url -> client.newCall(Request.Builder().url(url).build()).execute().body!!.bytes() },
    register = ::installTypeface,     // must return only once the platform can resolve it
)

staging.stage(brand.loadFonts())      // download, cache, register

val body = staging.resolve("body", weight = 600)
when {
    body.isUsable -> applyTypeface(body.postscriptName!!)
    else -> Log.w("designless", body.detail!!)   // says which of six things happened
}
```

| outcome | what it means |
|---|---|
| `RESOLVED` | a face was found and the platform can reach it |
| `SUBSTITUTED` | a face was found, but not the weight or style you asked for |
| `NOT_LOADED` | the font list has not been read — a fetch to retry |
| `UNPUBLISHED` | no face for this role, and the roles that do exist are named |
| `TOO_FAR` | the family publishes nothing within 200 of the weight asked for |
| `NOT_REGISTERED` | a face exists but its file is not staged — a build to fix |

Substitution is reported rather than passed off as a match. A family publishing only Light, answering a request for Medium, looks exactly like a font that was applied.

## Changes land when you say so

A theme applies at inflation on Android, so a mid-session swap gets you a half-updated app: the screen in front of the person keeps the old values while anything created afterwards gets the new ones. A fetched change is **held**:

```kotlin
brand.refresh()               // fetches, holds
brand.pending != null         // something is waiting

// on ON_START:
brand.activate()
```

Observers fire on activation, not arrival — nothing a caller can see changed when the payload landed. For an immediate swap, `refresh(activateNow = true)`.

The first payload always activates. "Do not restyle underneath someone" needs something to be styled first.

### Appearance

`assetUrl(...)` uses the appearance of the payload that is **live**, not the one you asked for. Between asking for light and light arriving, those differ, and a light mark on a screen still painted dark is the exact failure the rule exists to prevent.

## Addresses

Every address is built from the grammar the surface publishes, and parameters go only where that grammar says they apply. A few refusals happen here rather than as a 400 you would see as a blank `ImageView`:

```kotlin
brand.addresses.asset("logo-symbol", format = AssetFormat.SVG, size = AssetSize.PX256)
// IllegalArgumentException: An svg has no size to pick.
```

Sizes are a closed ladder expressed as an enum, so a request the surface refuses cannot be written. `AssetSize.atLeast(200)` rounds up, because a mark drawn larger and scaled down stays sharp. Composed destinations take neither size nor appearance: the destination decides both.

## On Android

```kotlin
val registry = AndroidFontRegistry(filesDir.resolve("designless-fonts"))
val staging = androidFontStaging(registry) { url -> httpGetBytes(url) }

val brand = Brand(
    publicId = "r_XXXX",
    fetch = ::httpGetText,
    store = PreferencesSnapshotStore(this),
)

brand.initialize()
staging.stage(brand.loadFonts())

// Changes land the next time the app comes to the foreground.
ForegroundActivation(brand) { recreate() }.attach(application)
```

**Android has no system-wide runtime font table.** On Apple and on the web, registering a face puts a name into a system table that any later lookup finds. Here a `Typeface` is an object you hold, so `AndroidFontRegistry` *is* that table — you resolve a role through `FontStaging` and look the returned PostScript name up in the registry. Same rule, our table.

`Typeface.createFromFile` returns `Typeface.DEFAULT` rather than throwing when it cannot read a file — a silent substitution dressed as success, and exactly the failure this package exists to prevent. The registry compares against `DEFAULT` and refuses, because that comparison is the only signal available.

### There is no Compose theme in this artifact, on purpose

A `ComposeTheme` here would pull `androidx.compose.*` into every app that wants a logo and pin a Compose version while doing it. The conversions that are easy to get wrong are provided; building a `ColorScheme` from them is about twenty lines, written once, against whatever Compose version you already have:

```kotlin
val scheme = darkColorScheme(
    background = Color(brand.tokens!!.colorInt("bg.page")!!),
    surface    = Color(brand.tokens!!.colorInt("bg.surface")!!),
    primary    = Color(brand.tokens!!.colorInt("brand.primary")!!),
)

val body = staging.resolve("body")
val family = body.postscriptName
    ?.let { registry[it] }
    ?.let { FontFamily(Typeface(it)) }
    ?: FontFamily.Default
```

`parseCssColor` exists rather than delegating to `Color.parseColor` because the brand publishes `#rrggbbaa` — alpha last, as CSS writes it — and Android's int keeps alpha first. `Color.parseColor` does not accept that form at all. Reading one order as the other gives a colour wrong in both hue and opacity, and still a colour, so nothing looks broken enough to investigate.

## Building this repository

Requires **JDK 21**. There is a `.java-version` for jenv and asdf, and both
modules pin `jvmToolchain(21)`.

Not a preference. On JDK 26 the build fails: the Dokka that AGP bundles for
javadoc generation calls IntelliJ's version parser, which throws on a version
string it does not recognise. Maven Central requires a javadoc jar, so it is
not skippable. The pure-JVM module builds fine on a newer JDK only because
nothing there invokes Dokka — which is the kind of luck that makes a build
look healthy until the day it is not.

The toolchain pin covers compilation. The Dokka worker runs on the Gradle
daemon's own JVM, so `JAVA_HOME` has to point at 21 as well:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew build
```

## Threading

`fetch` is blocking and expected to be called off the main thread. Wrapping it in a coroutine, an `Executor` or `WorkManager` is the caller's choice, not this module's — an SDK that picks a concurrency framework picks it for the whole app.

## Licence

Apache-2.0
