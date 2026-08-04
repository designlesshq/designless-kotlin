import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("android") version "2.2.20" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

allprojects {
    group = "io.designless"
    version = "0.1.0"
}

// ── NOTHING SECRET LIVES IN THIS REPOSITORY ─────────────────────────────────
//
// Every credential is read from ~/.gradle/gradle.properties or the
// environment, both outside the repo. A signing key or a portal token in a
// build file is one `git push` from being public and one force-push from
// looking like it never happened.
//
// Expected in ~/.gradle/gradle.properties:
//
//   mavenCentralUsername=<portal token username>
//   mavenCentralPassword=<portal token password>
//   signingInMemoryKey=<ASCII-armored secret key, newlines written as \n>
//   signingInMemoryKeyPassword=<the key's passphrase>
//
// Or, for CI, the same names prefixed with ORG_GRADLE_PROJECT_ as environment
// variables.
//
// Applied by reacting to the plugin rather than by applying it everywhere, so
// a module that should not publish cannot start publishing by inheriting a
// block it never asked for.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            // The Central Portal, not the legacy OSSRH staging API. The
            // namespace was verified there, and the old host does not know
            // about it.
            //
            // automaticRelease is false on purpose: an upload lands in a
            // staging state a human releases. A version on Central cannot be
            // replaced or withdrawn, so the last gate before a one-way door is
            // worth keeping manual.
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)

            // Central rejects an unsigned artifact outright. There is no
            // local-only path that skips this, so a release that would be
            // refused fails here rather than after the upload.
            signAllPublications()

            pom {
                url.set("https://github.com/designlesshq/designless-kotlin")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("designless")
                        name.set("Designless")
                        url.set("https://designless.app")
                    }
                }
                scm {
                    url.set("https://github.com/designlesshq/designless-kotlin")
                    connection.set("scm:git:https://github.com/designlesshq/designless-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/designlesshq/designless-kotlin.git")
                }
            }
        }
    }
}
