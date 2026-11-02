import org.gradle.api.artifacts.repositories.MavenArtifactRepository

rootProject.name = "metra-tiktok-patches"

pluginManagement {
    val allowLocalPlugins = providers.gradleProperty("allowMavenLocal")
        .map(String::toBoolean)
        .getOrElse(false)
    repositories {
        // Local plugin artifacts are useful while developing the plugin, but they must never
        // silently override the reviewed repositories used for a release build.
        if (allowLocalPlugins) mavenLocal()
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
            }
        }
        // baksmali and smali come from a source build, resolved as com.github.MorpheApp.smali
        // rather than from Maven Central. The comment used to name iBotPeaches/smali, which the
        // build stopped using when the patches plugin switched forks at 1.3.2.
        //
        // google/smali 3.0.10 shipped officially on 2026-08-20, so "until official releases come
        // out again" is no longer the reason to keep this. The reason is that the pin has to be
        // whatever the patcher was built against: two copies of dexlib2 on the R8 classpath fail
        // buildAndroid with "Classpath type already present".
        maven { url = uri("https://jitpack.io") }
    }
}

// app.morphe:morphe-patches-gradle-plugin 1.3.4 brings kotlin-gradle-plugin 2.4.10 as a
// runtimeElements dependency, and 2.4.10 is inside CVE-2026-53914 (GHSA-r937-wjx7-w2jp), a
// deserialisation flaw in the Kotlin build cache. 2.4.20 is the first release outside it, and
// 2.4.21 also fixes KT-88453, a scripting stream the compiler left open.
// Nothing in this tree requests a Kotlin plugin by id, so pluginManagement's eachPlugin has
// nothing to rewrite: the version has to be forced on the settings classpath itself. Take this
// out when the plugin moves past 2.4.21 on its own; gradle/verification-metadata.xml is what
// says which version actually resolved. It does not reach :patches:patcherProvidedClasspath,
// where the patcher's own kotlin-stdlib and kotlin-reflect stay at 2.4.10.
//
// The same classpath brings Bouncy Castle 1.79, by way of the patcher's 1.77 pin and the
// Android build tools' own 1.79 request: 1.77 is inside all seven advisories (CVE-2025-8916
// 1.44 to 1.78, CVE-2026-5588 1.67 to 1.83 apart from 1.80.2 and 1.81.1, CVE-2025-14813,
// CVE-2026-0636 fixed in 1.84, CVE-2026-8763, CVE-2026-13506 fixed in 1.85, CVE-2026-97873
// fixed in 1.86) and 1.79 is inside all but CVE-2025-8916.
// :patches and :extensions:tiktok each force their own graphs to
// the reviewed release, but neither reaches this one: a force on a project configuration cannot
// touch the classpath the plugins themselves resolve on, and that classpath is where the
// signing code actually runs. 1.86 is spelled out here rather than read from
// gradle/libs.versions.toml because the version catalog does not exist yet at this point in the
// build; the catalog pins the same value and says why it is that one. Move both together.
//
// Guava arrives here at 33.5.0-jre (the Android build tools, the patcher and the plugin ask for
// 18.0 to 33.5.0), and every one of those is inside GHSA-xxph-c9ww-hj94, fixed in 33.7.2. Same
// reason as Bouncy Castle for spelling the version out; the catalog pins it too.
buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.4.21")
            }
            if (requested.group == "org.bouncycastle") {
                useVersion("1.86")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                useVersion("33.7.2-jre")
            }
        }
    }
}

val allowMavenLocal = providers.gradleProperty("allowMavenLocal")
    .map(String::toBoolean)
    .getOrElse(false)

plugins {
    id("app.morphe.patches") version "1.3.4"
}

settings {
    extensions {
        defaultNamespace = "app.morphe.extension"

        // Must resolve to an absolute path (not relative),
        // otherwise the extensions in subfolders will fail to find the proguard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

if (!allowMavenLocal) {
    val localMavenUrl = file(System.getProperty("user.home"))
        .resolve(".m2/repository")
        .toURI()
        .toString()
        .removeSuffix("/")
    val localRepositories = dependencyResolutionManagement.repositories.filter { repository ->
        repository is MavenArtifactRepository
                && repository.url.toString().removeSuffix("/") == localMavenUrl
    }
    localRepositories.forEach { repository ->
        dependencyResolutionManagement.repositories.remove(repository)
    }
}

include(":patches:stub")
