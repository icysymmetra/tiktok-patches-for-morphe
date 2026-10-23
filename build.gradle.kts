plugins {
    alias(libs.plugins.android.library) apply false
}

// One Bouncy Castle version across every module, because the request arrives in more places
// than any single module can see. The patcher pins 1.77 and the Android build tools ask for
// 1.79. 1.77 is inside CVE-2025-8916 (1.44 to 1.78), both are inside CVE-2026-5588 (1.67 to
// 1.83, apart from the 1.80.2 and 1.81.1 maintenance releases) and CVE-2026-97873 (everything
// before 1.86); the reviewed release is the one gradle/libs.versions.toml pins, which says why
// it is that one. None of this reaches the payload injected into TikTok. It is the build and signing
// classpath, and the repository's rule is that a known-affected component does not stay in a
// reproducible graph.
//
// Per module this was applied twice and still missed three graphs: :extensions:shared's release
// runtime classpath, which has no force of its own, and two configurations the Android plugin
// makes for itself. The settings classpath is forced separately in settings.gradle.kts, which
// resolves before this file exists.
val reviewedBouncyCastle = libs.versions.bouncycastle.get()
// Guava the same way, for the same reason: the patcher's own graph, the Robolectric test graph
// and the Android test platform each ask for a release inside GHSA-xxph-c9ww-hj94, and the
// catalog's pin reaches only :patches. The catalog says which release and why.
val reviewedGuava = libs.versions.guava.get()
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion(reviewedBouncyCastle)
                because("The build classpath must use the reviewed Bouncy Castle release.")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                useVersion(reviewedGuava)
                because("The build classpath must use the Guava release outside GHSA-xxph-c9ww-hj94.")
            }
        }
    }
}
