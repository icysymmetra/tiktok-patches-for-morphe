/**
 * Which host capture supplies each current product screenshot.
 *
 * <p>Captures stay under the configured directory until refreshScreenshots is explicitly run.
 * Metra merges Playback, Privacy and Screen time into App behavior, and Inbox into Interface.
 * Their old reference pictures are retained but are no longer refresh destinations. The map
 * names the actual Metra page captures, including the device and player scroll positions.
 */
val trackedScreenshots = mapOf(
    "auto-advance-settings.png" to "auto-advance-settings.png",
    "caption-settings.png" to "caption-settings.png",
    "capture-settings.png" to "capture-settings.png",
    "clear-display-captions.png" to "clear-display-captions.png",
    "clear-display-settings.png" to "clear-display-settings.png",
    "comment-search-no-results.png" to "comment-search-no-results.png",
    "confirmation-control.png" to "confirmation-control.png",
    "download-settings.png" to "download-settings.png",
    "foldable-settings.png" to "foldable-settings.png",
    "gate-recording.png" to "gate-recording.png",
    "interface-settings.png" to "interface-settings.png",
    "overlay-controls.png" to "overlay-controls.png",
    "playback-settings.png" to "playback-settings.png",
    "playback-speed-settings.png" to "playback-speed-settings.png",
    "region-settings.png" to "region-settings.png",
    "settings/behavior.png" to "pages/dark/behavior.png",
    "settings/backup-german-large.png" to "pages/dark/backup-german-large.png",
    "settings/backup.png" to "pages/dark/backup.png",
    "settings/comments-spanish-large.png" to "pages/dark/comments-spanish-large.png",
    "settings/comments.png" to "pages/dark/comments.png",
    "settings/creator-list.png" to "pages/dark/creator-list.png",
    "settings/diagnostics-light.png" to "pages/light/diagnostics.png",
    "settings/diagnostics.png" to "pages/dark/diagnostics.png",
    "settings/diagnostics-end.png" to "pages/dark/diagnostics-end.png",
    "settings/dialog-multi-dark.png" to "dialogs/dark/multi-choice.png",
    "settings/dialog-multi-light.png" to "dialogs/light/multi-choice.png",
    "settings/dialog-single-dark.png" to "dialogs/dark/single-choice.png",
    "settings/dialog-single-light.png" to "dialogs/light/single-choice.png",
    "settings/downloads.png" to "pages/dark/downloads.png",
    "settings/single-save-progress.png" to "pages/downloads/single-save-progress.png",
    "settings/already-saved.png" to "pages/downloads/already-saved-dark.png",
    "settings/already-saved-light.png" to "pages/downloads/already-saved-light.png",
    "settings/feed_filter.png" to "pages/dark/feed_filter.png",
    "settings/feed_navigation.png" to "pages/dark/feed_navigation-end.png",
    "settings/gate_details.png" to "pages/dark/gate_details.png",
    "settings/gate_recording.png" to "gate-recording.png",
    "settings/interface.png" to "pages/dark/interface.png",
    "settings/lab.png" to "pages/dark/lab.png",
    "settings/lab-preset.png" to "pages/dark/lab-preset.png",
    "settings/behavior-light.png" to "pages/light/behavior.png",
    "settings/behavior-device.png" to "pages/dark/behavior-device.png",
    "settings/behavior-sound-light.png" to "pages/light/behavior-sound.png",
    "settings/behavior-sound.png" to "pages/dark/behavior-sound.png",
    "settings/region.png" to "pages/dark/region.png",
    "settings/rtl-large-light.png" to "pages/light/rtl-large.png",
    "settings/rtl-large.png" to "pages/dark/rtl-large.png",
    "settings/search.png" to "pages/dark/search.png",
    "settings/settings-error.png" to "settings-initialization-error.png",
    "settings/session-reminder.png" to "session-reminder.png",
    "settings/hub-about.png" to "pages/dark/hub-about.png",
    "settings/hub-about-light.png" to "pages/light/hub-about.png",
    "settings/settings-end.png" to "pages/dark/settings-end.png",
    "settings/settings-end-light.png" to "pages/light/settings-end.png",
    "settings/settings-light.png" to "pages/light/settings-quiet-index.png",
    "settings/settings.png" to "pages/dark/settings.png",
    "settings/share.png" to "pages/dark/share.png",
    "settings/two-times-text-light.png" to "pages/light/two-times-text.png",
    "settings/two-times-text.png" to "pages/dark/two-times-text.png",
    "subtitle-download-settings.png" to "subtitle-download-settings.png",
    "block-unblock-chip.png" to "block-unblock-chip.png",
)

/** Read at configuration time so the test task can ask for the capture it is about to copy. */
val refreshingScreenshots = gradle.startParameter.taskNames.any {
    it == "refreshScreenshots" || it.endsWith(":refreshScreenshots")
}

// Robolectric 4.17 asks for Bouncy Castle 1.85 twice: by name, through the bc-jdk18on-bom it
// imports, and with no version of its own for the bcprov module that BOM governs. Every request
// in this module is rewritten to the reviewed release so related test libraries cannot resolve
// at mixed versions. No production configuration contains this group, so none of it reaches
// the MPE payload.
//
// Checking the resolved graph afterwards would prove nothing: the rewrite above guarantees the
// answer, so a "wrong resolved version" branch could never run. What the rewrite hides, and what
// is worth failing on, is the request underneath it. When Robolectric moves to a version nobody
// has looked at, this build stops instead of quietly rewriting it away.
val safeBouncyCastleVersion = libs.versions.bouncycastle.get()
// 1.85 was checked on 2026-09-15 against six of the advisories the catalog names: CVE-2025-8916
// ends at 1.78, CVE-2026-5588 at 1.83, CVE-2025-14813 and CVE-2026-0636 at 1.84, and
// CVE-2026-8763 and CVE-2026-13506 at 1.85. CVE-2026-97873 covers everything before 1.86, 1.85
// included, so the request is allowed only because it is rewritten to the reviewed release.
val reviewedBouncyCastleRequests = setOf("1.85", safeBouncyCastleVersion)
// Modules Robolectric declares with no version of its own, because the BOM it imports carries
// the version for them. Reviewing that BOM is what covers these, so they are named here rather
// than by a version: the BOM's own request is reviewed above, and a module that turns up here
// without one is a request nothing in this file chose the version for.
//
// Reading the constraint behind such a request instead was tried on 2026-09-15 and is worthless:
// the rewrite has already moved the BOM to the reviewed release by then, so every constraint it
// contributes names that release and the check can never fail. Same trap as the resolved-version
// check described above.
val reviewedVersionlessBouncyCastleModules = setOf("bcprov-jdk18on")
// Guarded by hand rather than by a synchronized wrapper: in a Kotlin build script `java` is the
// Java extension, so the java.util package cannot be named here.
val requestedBouncyCastleVersions = sortedSetOf<String>()
// Module names, kept apart from the versions above so a name can never end up in the reviewed
// version set by someone pasting it in.
val unversionedBouncyCastleRequests = sortedSetOf<String>()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            // Recorded whatever it is, including a request that carries no version of its own.
            // A ?.let dropped those: one arriving through a platform or a BOM was rewritten to
            // the reviewed release like any other and then counted nowhere, so the unreviewed
            // set stayed empty for it and the gate below had nothing to fail on. A request with
            // no version is the one most worth reading, because nothing in this file chose
            // what it would otherwise have resolved to.
            // Blank as well as null. A declaration with no version at all reports "" rather
            // than null, so a plain null check counted it as a version and the failure read
            // "asks for Bouncy Castle , which nobody has reviewed": it stopped the build, which
            // is the point, but said nothing a reader could act on.
            val asked = requested.version?.takeIf { it.isNotBlank() }
            if (asked != null) {
                synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
            } else {
                synchronized(unversionedBouncyCastleRequests) { unversionedBouncyCastleRequests.add(requested.name) }
            }
            useVersion(safeBouncyCastleVersion)
            because("The Robolectric test graph must use the reviewed security release.")
        }
    }
}

val verifyBouncyCastleTestGraph = tasks.register("verifyBouncyCastleTestGraph") {
    group = "verification"
    description = "Checks the unit-test graphs for unreviewed Bouncy Castle requests."

    doLast {
        // Resolving is what runs the rewrite above, so the requests are collected here rather
        // than being read from a set that nothing has filled yet.
        val classpaths = configurations
            .filter { it.name.endsWith("UnitTestRuntimeClasspath") && it.isCanBeResolved }
            .sortedBy { it.name }
        if (classpaths.isEmpty()) {
            throw GradleException("This module has no unit-test runtime classpath to inspect.")
        }

        for (classpath in classpaths) {
            val modules = classpath.incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    component.moduleVersion?.takeIf { it.group == "org.bouncycastle" }
                }
                .distinctBy { "${it.group}:${it.name}:${it.version}" }
                .sortedBy { it.name }

            if (modules.isEmpty()) {
                throw GradleException("${classpath.name} contains no Bouncy Castle module.")
            }
            logger.lifecycle(
                "Bouncy Castle in ${classpath.name}: " +
                    modules.joinToString(", ") { "${it.name}:${it.version}" }
            )
        }

        val requested = synchronized(requestedBouncyCastleVersions) {
            requestedBouncyCastleVersions.toSet()
        }
        val unversioned = synchronized(unversionedBouncyCastleRequests) {
            unversionedBouncyCastleRequests.toSet()
        }
        val unreviewed = (requested - reviewedBouncyCastleRequests).sorted() +
            (unversioned - reviewedVersionlessBouncyCastleModules).sorted()
                .map { "$it with no version of its own" }
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "The test graph now asks for Bouncy Castle " + unreviewed.joinToString(", ") +
                    ", which nobody has reviewed. It is being rewritten to $safeBouncyCastleVersion. " +
                    "Check the advisory for the requested release, then add a version to " +
                    "reviewedBouncyCastleRequests, or a module asking for no version of its own " +
                    "to reviewedVersionlessBouncyCastleModules once you have read what carries " +
                    "its version, or move the pin."
            )
        }
    }
}

// By type rather than by the one name. This module builds unit tests for debug only today, so
// testDebugUnitTest is the whole of it, but a second unit-test variant would otherwise start a
// test JVM on a graph nothing had looked at.
tasks.withType<Test>().configureEach {
    dependsOn(verifyBouncyCastleTestGraph)
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:tiktok:stub"))
    compileOnly(libs.annotation)
    testImplementation(project(":extensions:shared:library"))
    testImplementation(project(":extensions:tiktok:stub"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

extension {
    name = "extensions/tiktok.mpe"
}

android {
    namespace = "app.morphe.extension.tiktok"

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The payload is injected into TikTok, whose own floor is API 23. javac compiles against a
    // modern JDK and Robolectric runs on one, so a library call or a type above that floor is
    // green all the way to a phone, where D8 has left it as a stub that throws. NewApi is the
    // only check here that reads the SDK_INT guards instead of flagging every guarded call.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        // NewApi catches a call above the floor. ObsoleteSdkInt catches an SDK_INT guard at or
        // below it, which is dead code today and a wrong floor the next time minSdk moves.
        checkOnly += "ObsoleteSdkInt"
        error += "ObsoleteSdkInt"
        abortOnError = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1g"
            // The translation tables are read from disk at run time, by the test that compares
            // them against the generated table. Without this Gradle sees no input change when a
            // table is edited, calls the task up to date, and the comparison never runs.
            it.inputs.dir(layout.projectDirectory.dir("src/main/l10n"))
                .withPropertyName("l10nTables")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // Same reason, one file instead of a directory. LicensesRowTest holds NOTICE against
            // the copy generated into the payload, and NOTICE is outside this module, so without
            // this Gradle calls the task up to date after NOTICE changes and the comparison never
            // runs. Editing NOTICE and watching the test still pass is how that was found.
            it.inputs.file(rootProject.layout.projectDirectory.file("NOTICE"))
                .withPropertyName("licenseNotice")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            it.inputs.file(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
                .withPropertyName("releaseNotes")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // ReleaseNotesTest captures What's new for the version this file names.
            it.inputs.file(rootProject.layout.projectDirectory.file("gradle.properties"))
                .withPropertyName("sourceVersion")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // refreshScreenshots asks for the capture itself, so it does not need the property
            // and cannot be pointed at assets/ by accident. Forcing the rerun matters because a
            // capture is not one of the task's declared outputs: an up to date test task writes
            // no screenshots at all, and the copy below would then compare against whatever was
            // left in build/ from a previous run.
            val captureInto = providers.gradleProperty("screenshotDir").orNull
                ?: if (refreshingScreenshots) "build/screenshots" else null
            if (captureInto != null) {
                it.systemProperty("morphe.screenshotDir", captureInto)
            }
            if (refreshingScreenshots) it.outputs.upToDateWhen { false }
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

/**
 * Re-captures the settings screenshots and copies back only the published ones that changed.
 *
 * <p>`./gradlew :extensions:tiktok:refreshScreenshots`. The capture goes to this module's build
 * directory, never to assets/: pointing the screenshotDir property there dropped about ninety
 * untracked files into the repository twice, on 2026-09-06 and again on 2026-09-08. What lands
 * in assets/ is the fixed list above and nothing else, and only where the bytes moved, so the
 * printed names are exactly the screens a change altered.
 */
tasks.register("refreshScreenshots") {
    group = "documentation"
    description = "Re-captures the settings screenshots and updates the ones in assets/settings."
    dependsOn("testDebugUnitTest")

    val captureDirectory = layout.buildDirectory.dir("screenshots")
    val assetsDirectory = rootProject.layout.projectDirectory.dir("assets")
    val tracked = trackedScreenshots

    doLast {
        val capture = captureDirectory.get().asFile
        if (!capture.isDirectory) {
            throw GradleException(
                "No screenshots were captured in $capture. The suite writes them only when " +
                    "morphe.screenshotDir is set, which this task does for itself."
            )
        }

        val copied = mutableListOf<String>()
        val absent = mutableListOf<String>()
        for ((published, source) in tracked.toSortedMap()) {
            val from = capture.resolve(source)
            if (!from.isFile) {
                absent += "$published (no $source)"
                continue
            }
            val to = assetsDirectory.file(published).asFile
            if (to.isFile && from.readBytes().contentEquals(to.readBytes())) continue
            from.copyTo(to, overwrite = true)
            copied += published
        }

        if (absent.isNotEmpty()) {
            // A published picture whose capture has gone is a rename nobody carried through to
            // the map, and silently leaving the old file in place is how it stays wrong.
            throw GradleException(
                "These published screenshots have no capture any more, so the map above is out " +
                    "of date: " + absent.joinToString(", ")
            )
        }
        if (copied.isEmpty()) {
            logger.lifecycle("Screenshots: nothing moved.")
        } else {
            logger.lifecycle("Screenshots updated (${copied.size}): " + copied.joinToString(", "))
        }
    }
}
