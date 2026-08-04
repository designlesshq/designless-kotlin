plugins {
    id("com.vanniktech.maven.publish")
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "io.designless.serve.android"
    compileSdk = 35

    defaultConfig {
        // 24 is where Typeface.createFromFile and the font APIs this uses are
        // all present without a compat path. Nothing here needs anything
        // newer, and an SDK that raises an app's floor for no reason is one
        // that gets vendored instead of depended on.
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // Robolectric-free: the framework classes this module touches are
            // stubbed by the caller in tests, so `isReturnDefaultValues` keeps
            // an unstubbed call loud rather than silently zero.
            isReturnDefaultValues = false
        }
    }

    // No `publishing { singleVariant(...) }` here. The maven-publish plugin
    // declares the release variant itself, and declaring it twice is a hard
    // error rather than a merge — which is the right failure, just an easy one
    // to trip by copying a snippet from the AGP docs.
}

kotlin {
    compilerOptions {
        // Must match compileOptions above. The Android plugin does not infer
        // one from the other, and a mismatch fails the build rather than
        // producing a mixed-target artifact — which is the right failure, but
        // only if both are stated.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // No -Xjdk-release here, unlike the :serve module. The Android plugin
        // does not hand the compiler a JDK_HOME, and the check that flag buys
        // is about the JVM's API surface — which is not the one that matters
        // in an Android library. compileSdk and minSdk are what gate the API
        // this module may call, and AGP enforces those.
    }
    explicitApi()
}

dependencies {
    api(project(":serve"))
    testImplementation(kotlin("test"))
}


mavenPublishing {
    pom {
        name.set("Designless Serve for Android")
        description.set("Your brand in an Android app. Typeface registration, colour and length conversion, and foreground activation.")
    }
}
