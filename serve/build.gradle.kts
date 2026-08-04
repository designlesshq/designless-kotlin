import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.vanniktech.maven.publish")
    kotlin("jvm")
    `java-library`
}

kotlin {
// ── THE TOOLCHAIN IS PINNED, AND THAT IS NOT PEDANTRY ───────────────────────
//
// 21, not "whatever JDK is on PATH". Built on JDK 26 this fails: the Dokka
// that AGP bundles for javadoc generation calls IntelliJ's version parser,
// which throws IllegalArgumentException on "26.0.2" because that JDK is newer
// than it knows about. Central requires a javadoc jar, so the failure is not
// skippable.
//
// The pure-JVM module got through on JDK 26 only because nothing there invokes
// Dokka — which is exactly the kind of luck that makes a build look fine until
// the day it does not. Both modules pin the same toolchain so the JDK doing
// the compiling is a property of this build, not of whoever runs it.
    jvmToolchain(21)

    compilerOptions {
        // The bytecode target, not the JDK doing the compiling. 11 is what
        // every supported Android configuration accepts without desugaring
        // configuration, and nothing in this module needs anything newer.
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }

    // Every public declaration carries an explicit visibility and return type.
    // This is a library: an accidentally-public helper is a support burden
    // that cannot be taken back without a major version. The DSL form applies
    // to main sources only — the raw compiler flag also catches tests, where
    // it is noise.
    explicitApi()
}

java {
    withSourcesJar()
    // `release` rather than source/targetCompatibility, so the compiler checks
    // against the Java 11 API surface instead of only emitting 11 bytecode.
    // Without it a call to something added in a later JDK compiles here and
    // fails at runtime on a device.
    toolchain { }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

dependencies {
    // No runtime dependencies. HTTP and font registration are injected, so
    // nothing here constrains what an app already uses, and the module works
    // on any JVM as well as on Android.
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}


mavenPublishing {
    pom {
        name.set("Designless Serve")
        description.set("Your brand in a Kotlin app. Tokens, marks and fonts, served at runtime, with no dependencies.")
    }
}
