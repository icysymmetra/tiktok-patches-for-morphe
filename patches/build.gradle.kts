import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter

/**
 * The moment the bundle says it was built.
 *
 * Two builds of one commit used to differ in exactly one field, the manifest's Timestamp, so a
 * third party could rebuild the bundle and never match the published hash: a checksum then
 * attests to one file rather than to the source it came from. This pins the field to
 * SOURCE_DATE_EPOCH when the environment sets one, otherwise to the commit being built,
 * otherwise to zero. Anything read from the clock would put the difference straight back.
 *
 * This is a reproducibility field, not a source identity. Release eligibility comes from the
 * source snapshots captured before task execution and after the bundle has been produced.
 */
val sourceDateEpoch: Long = run {
    providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.trim()?.toLongOrNull()?.let {
        return@run it
    }
    try {
        val uncommitted = providers.exec {
            commandLine("git", "status", "--porcelain")
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull
        if (!uncommitted.isNullOrBlank()) return@run 0L
        providers.exec {
            commandLine("git", "log", "-1", "--format=%ct")
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull?.trim()?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        // No git, or no repository. Zero is stable, which is the only property that matters.
        0L
    }
}

/** Build-time source facts. Failure to read Git or an input always makes the build ineligible. */
object BundleSource {
    data class Snapshot(val commit: String, val clean: Boolean, val fingerprint: String,
                        val rawFingerprint: String)

    fun git(root: File, vararg arguments: String, input: String? = null): String {
        val process = ProcessBuilder(listOf("git", "-C", root.absolutePath) + arguments)
        // A pre-push hook exports GIT_DIR. -C alone does not select the requested repository.
        process.environment().keys.removeIf { it.startsWith("GIT_") }
        process.redirectError(ProcessBuilder.Redirect.DISCARD)
        val child = process.start()
        val output = ByteArrayOutputStream()
        val ioFailure = AtomicReference<Throwable>()
        val reader = Thread {
            try { child.inputStream.use { it.copyTo(output) } }
            catch (failure: Throwable) { ioFailure.set(failure) }
        }
        reader.isDaemon = true
        reader.start()
        val writer = input?.let { text ->
            Thread {
                try { child.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
                catch (failure: Throwable) { ioFailure.set(failure) }
            }.apply { isDaemon = true; start() }
        }
        if (writer == null) child.outputStream.close()
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            throw IllegalStateException("Git source inspection timed out")
        }
        reader.join(5_000)
        writer?.join(5_000)
        check(!reader.isAlive && writer?.isAlive != true && ioFailure.get() == null && child.exitValue() == 0) {
            "Git source inspection failed"
        }
        return output.toString(Charsets.UTF_8)
    }

    fun capture(root: File, sourceDirectories: List<String> = listOf("src")): Snapshot {
        var commit = "unknown"
        val objectIdPattern = Regex("[0-9a-f]{40}")
        return try {
            check(File(git(root, "rev-parse", "--show-toplevel").trim()).canonicalFile == root.canonicalFile) {
                "The source root is not the requested repository"
            }
            commit = git(root, "rev-parse", "HEAD").trim()
            check(commit.matches(objectIdPattern)) { "No source commit" }
            val cleanAtStart = git(root, "status", "--porcelain", "--untracked-files=all").isBlank()
            val tracked = git(root, "ls-files", "-v", "-z").split('\u0000').filter { it.isNotEmpty() }
            // Assume-unchanged and skip-worktree entries can hide edits from git status.
            val visible = tracked.all { it.startsWith("H ") }
            check(sourceDirectories.isNotEmpty() && sourceDirectories.all {
                it.isNotBlank() && root.resolve(it).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
            }) { "The configured source directories are unavailable or outside the checkout" }
            // Git status omits these files, but the Java/Kotlin and Android source sets still read them.
            val ignoredSources = git(root, "ls-files", "--others", "--ignored", "--exclude-standard", "-z",
                "--", *sourceDirectories.toTypedArray()).split('\u0000').filter { it.isNotEmpty() }
            val names = (git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
                .split('\u0000').filter { it.isNotEmpty() } + ignoredSources).distinct().sorted()
            check(names.isNotEmpty()) { "No readable source inputs" }
            val rawDigest = MessageDigest.getInstance("SHA-256")
            rawDigest.update(commit.toByteArray(Charsets.UTF_8))
            for (name in names) {
                val input = root.resolve(name)
                check(input.isFile && input.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                    "A source input is missing or outside the checkout"
                }
                val contentDigest = MessageDigest.getInstance("SHA-256")
                input.inputStream().use { stream ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        contentDigest.update(buffer, 0, read)
                    }
                }
                rawDigest.update(0.toByte())
                rawDigest.update(name.toByteArray(Charsets.UTF_8))
                rawDigest.update(0.toByte())
                rawDigest.update(contentDigest.digest())
            }
            // Manifest identity uses Git's canonical content, including the committed EOL policy.
            // Keep the raw digest private so any byte change during this build still rejects it.
            val paths = names.joinToString("\n", postfix = "\n") { name ->
                "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
            }
            val objects = git(root, "hash-object", "--stdin-paths", input = paths)
                .lineSequence().filter { it.isNotEmpty() }.toList()
            check(objects.size == names.size && objects.all { it.matches(objectIdPattern) }) {
                "Git could not normalize every source input"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(commit.toByteArray(Charsets.UTF_8))
            for ((name, objectId) in names.zip(objects)) {
                digest.update(0.toByte())
                digest.update(name.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(objectId.toByteArray(Charsets.UTF_8))
            }
            val clean = cleanAtStart && visible && ignoredSources.isEmpty()
                && git(root, "rev-parse", "HEAD").trim() == commit
                && git(root, "status", "--porcelain", "--untracked-files=all").isBlank()
            Snapshot(commit, clean, digest.digest().joinToString("") { "%02X".format(it) },
                rawDigest.digest().joinToString("") { "%02X".format(it) })
        } catch (_: Exception) {
            Snapshot(commit, false, "unavailable", "unavailable")
        }
    }

    fun manifestFacts(start: Snapshot, end: Snapshot): Map<String, String> {
        val clean = start.clean && end.clean && start.commit == end.commit
            && start.commit.matches(Regex("[0-9a-f]{40}"))
            && start.fingerprint.matches(Regex("[0-9A-Fa-f]{64}"))
            && start.fingerprint == end.fingerprint
            && start.rawFingerprint.matches(Regex("[0-9A-Fa-f]{64}"))
            && start.rawFingerprint == end.rawFingerprint
        return mapOf(
            "Metra-Source-Commit" to start.commit,
            "Metra-Source-Clean" to clean.toString(),
            "Metra-Source-Start" to start.fingerprint,
            "Metra-Source-End" to end.fingerprint
        )
    }
}

abstract class CaptureBundleSource : DefaultTask() {
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @get:Internal abstract val sourceDirectories: ListProperty<String>
    @get:OutputFile abstract val snapshotFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun capture() {
        val source = BundleSource.capture(sourceRoot.get().asFile, sourceDirectories.get())
        val output = snapshotFile.get().asFile
        output.parentFile.mkdirs()
        val facts = Properties()
        facts.setProperty("commit", source.commit)
        facts.setProperty("clean", source.clean.toString())
        facts.setProperty("fingerprint", source.fingerprint)
        facts.setProperty("rawFingerprint", source.rawFingerprint)
        output.outputStream().use { facts.store(it, "Build source before compilation") }
        if (!source.clean) logger.lifecycle("Bundle source is dirty or unreadable; a new release receipt will be refused.")
    }
}

/**
 * Rewrites the bundle with the timestamp pinned, leaving everything else as it was.
 *
 * <p>Every entry is written again rather than copied compressed, because the zip API offers no
 * way to move compressed bytes across without decoding them. That makes the output differ from
 * what the plugin first wrote, which does not matter: what matters is that two runs of this
 * produce the same bytes, and they do, because nothing here reads a clock.
 */
fun pinBundleTimestamp(bundle: File, epochSeconds: Long, sourceFacts: Map<String, String>) {
    val stampMillis = epochSeconds * 1000L
    val names = mutableListOf<String>()
    val contents = mutableMapOf<String, ByteArray>()
    val times = mutableMapOf<String, Long>()
    val methods = mutableMapOf<String, Int>()

    ZipFile(bundle).use { zip ->
        for (entry in zip.entries()) {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            names += entry.name
            contents[entry.name] = bytes
            times[entry.name] = entry.time
            methods[entry.name] = entry.method
        }
    }

    val manifestName = "META-INF/MANIFEST.MF"
    val manifestBytes = contents[manifestName]
        ?: throw GradleException("The bundle has no $manifestName: $bundle")
    val manifest = Manifest(manifestBytes.inputStream())
    if (manifest.mainAttributes.getValue("Timestamp") == null) {
        throw GradleException("The bundle manifest has no Timestamp line to pin: $bundle")
    }
    manifest.mainAttributes.putValue("Timestamp", stampMillis.toString())
    for ((name, value) in sourceFacts) manifest.mainAttributes.putValue(name, value)
    contents[manifestName] = ByteArrayOutputStream().also { manifest.write(it) }.toByteArray()

    val rebuilt = ByteArrayOutputStream()
    ZipOutputStream(rebuilt).use { out ->
        for (name in names) {
            val bytes = contents.getValue(name)
            val entry = ZipEntry(name)
            entry.time = times.getValue(name)
            entry.method = methods.getValue(name)
            if (entry.method == ZipEntry.STORED) {
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            out.putNextEntry(entry)
            out.write(bytes)
            out.closeEntry()
        }
    }
    bundle.writeBytes(rebuilt.toByteArray())
}

group = "app.morphe"

patches {
    about {
        name = "Metra"
        description = "Metra patches for TikTok 47.4.3, built for Morphe. Fewer accidental taps, less noise, more control over the feed, inbox, comments and downloads."
        source = "https://github.com/icysymmetra/tiktok-patches-for-morphe"
        author = "icysymmetra"
        contact = "https://github.com/icysymmetra/tiktok-patches-for-morphe/issues"
        website = "https://github.com/icysymmetra/tiktok-patches-for-morphe"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

// Morphe patcher 1.12.0 asks for Bouncy Castle 1.77 and the Android build tools it brings ask
// for 1.79, so this module's graph resolved at 1.79: 1.77 is inside all seven advisories
// (CVE-2025-8916 1.44 to 1.78, CVE-2026-5588 1.67 to 1.83 apart from 1.80.2 and 1.81.1,
// CVE-2025-14813, CVE-2026-0636 fixed in 1.84, CVE-2026-8763, CVE-2026-13506 fixed in 1.85,
// CVE-2026-97873 fixed in 1.86) and 1.79 is inside all but CVE-2025-8916. None of it reaches the
// payload, and the APK a user gets is
// signed by their own Manager with its own patcher, so this is the build and signing classpath
// here rather than anything shipped. The repository's rule is that a known-affected component
// does not stay in a reproducible graph either way. Every request is rewritten to the reviewed
// release the catalog pins, the same treatment :extensions:tiktok gives its test graph.
val safeBouncyCastleVersion = libs.versions.bouncycastle.get()
// What the patcher and the Android build tools are known to ask for. A request for anything
// else is a version nobody has read the advisories for, and the gate below stops the build on
// it rather than rewriting it away in silence.
val reviewedBouncyCastleRequests = setOf("1.77", "1.79", safeBouncyCastleVersion)
// Guarded by hand rather than with a synchronized collection wrapper: in a Kotlin build script
// `java` is the Java extension, so the java.util package cannot be named here.
val requestedBouncyCastleVersions = sortedSetOf<String>()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            // Recorded before the rewrite, and recorded as a name rather than dropped when the
            // request carries no version of its own: a request arriving through a platform or
            // a constraint would otherwise be rewritten and never counted.
            // Blank as well as null: a declaration with no version reports "" rather than null,
            // and a plain ?: leaves the failure message with an empty name in it.
            val asked = requested.version?.takeIf { it.isNotBlank() } ?: "a request with no version of its own"
            synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
            useVersion(safeBouncyCastleVersion)
            because("The build classpath must use the reviewed Bouncy Castle release.")
        }
    }
}

val verifyBouncyCastleBuildGraph = tasks.register("verifyBouncyCastleBuildGraph") {
    group = "verification"
    description = "Checks this module's resolvable graphs for unreviewed Bouncy Castle requests."

    doLast {
        // Resolving is what runs the rewrite above, so the requests are collected here rather
        // than read out of a set nothing has filled yet. Checking the resolved version instead
        // would prove nothing: the rewrite guarantees that answer, so its failure branch could
        // never run. The request underneath it is the live fact.
        // Named rather than "every resolvable configuration": the ones the patcher's graph
        // actually arrives on. Resolution failures are not caught. A swallowed one is how this
        // gate first passed while reporting a single module, because dependency verification
        // was refusing the very artifacts the force had just introduced.
        val inspected = listOf("patcherProvidedClasspath", "compileClasspath", "runtimeClasspath",
            "testCompileClasspath", "testRuntimeClasspath")
        val seen = sortedMapOf<String, String>()
        val missing = mutableListOf<String>()
        for (name in inspected) {
            val configuration = configurations.findByName(name)
            if (configuration == null || !configuration.isCanBeResolved) {
                missing += name
                continue
            }
            val modules = configuration.incoming.resolutionResult.allComponents
                .mapNotNull { it.moduleVersion?.takeIf { module -> module.group == "org.bouncycastle" } }
            for (module in modules) seen[module.name] = module.version
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These configurations were not there to inspect: " + missing.joinToString(", ") +
                    ". The plugin renamed them, so this gate is looking at the wrong graph."
            )
        }
        // Named, not merely non-empty. A module that fails to resolve is absent from
        // allComponents rather than raising, so a force at a version that does not publish a
        // module leaves this reporting the ones that did resolve and passing. That is exactly
        // what 1.85.2 did: it is a bcprov-only release, and bcpkix and bcutil read FAILED in
        // the dependency report while this gate said the graph was clean.
        val expected = setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on")
        val absent = expected - seen.keys
        if (absent.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle " + absent.sorted().joinToString(", ") + " is not on the build " +
                    "graph. Either the force names a version that does not publish it, or the " +
                    "patcher stopped bringing it and this gate is now blind."
            )
        }
        val wrong = seen.filterValues { it != safeBouncyCastleVersion }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle resolved at " + wrong.entries.joinToString(", ") { "${it.key}:${it.value}" } +
                    " rather than the reviewed $safeBouncyCastleVersion."
            )
        }
        logger.lifecycle(
            "Bouncy Castle on the build graph: " + seen.entries.joinToString(", ") { "${it.key}:${it.value}" }
        )

        val requested = synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.toSet() }
        val unreviewed = requested - reviewedBouncyCastleRequests
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "The build graph now asks for Bouncy Castle " + unreviewed.sorted().joinToString(", ") +
                    ", which nobody has reviewed. It is being rewritten to $safeBouncyCastleVersion. " +
                    "Check the advisory for the requested release, then add it to " +
                    "reviewedBouncyCastleRequests or move the pin."
            )
        }
    }
}

// By type rather than by the one name, so a second test task cannot start on a graph nothing
// has looked at. :patches:test is what scripts/pre-push.ps1 runs when a patch source changes.
tasks.withType<Test>().configureEach {
    dependsOn(verifyBouncyCastleBuildGraph)
    dependsOn("verifySourceProvenance")
}

dependencies {
    compileOnly(libs.morphe.patcher)

    // Used by JsonGenerator.
    implementation(libs.gson)

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))

    // The register and instruction helpers under app.morphe.util are the only thing tested
    // here. They take dexlib2 methods, so the patcher has to be on the test runtime classpath
    // as well as the compile one.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.morphe.patcher)
    // Reads the signing certificate of every retained fixture. The patcher already brings this
    // exact version at run time; this puts it on the test compile classpath as well.
    testImplementation("com.android.tools.build:apksig:9.1.1")
    // Runs the patcher over a fixture (ClassesCallingTest). The patcher brings this exact version
    // at run time; this puts it on the test compile classpath as well.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

val documentationClasses = listOf("app/morphe/ReadmePatchNamesTest.class", "app/morphe/MarketingHeroTest.class",
    "app/morphe/DefaultSelectionPolicyTest.class")
// The patch tests that read extension sources or the extension bundle. They stay in :patches:test,
// which a runtime Java edit reruns. Every other patch test runs in nativeTest, whose inputs leave
// the extensions out, so that edit reuses the long fixture fingerprint scans. nativeTest refuses
// to run a test outside this list that reads extensions.
val extensionClasses = listOf(
    "app/morphe/ExtensionBridgeLookupTest.class",
    "app/morphe/ExtensionHostsTest.class",
    "app/morphe/ObfuscatedIdentityTest.class",
    "app/morphe/OriginNoticeMirrorsTest.class",
    "app/morphe/RuntimeViewIdAnchorsTest.class",
    "app/morphe/gatecatalog/GateCatalogFixturesTest.class",
    "app/morphe/patches/tiktok/ExtensionReferencesResolveTest.class",
    "app/morphe/patches/tiktok/TikTokPatchAnchorsMatchFixturesTest.class",
    "app/morphe/patches/tiktok/interaction/downloads/StickerSourceFixturesTest.class",
    "app/morphe/patches/tiktok/interaction/downloads/StoryHoldFixturesTest.class",
    "app/morphe/patches/tiktok/interaction/gesture/LikePressAnchorsTest.class",
    "app/morphe/patches/tiktok/interaction/videooverlays/MusicDiscSpinAnchorsTest.class",
    "app/morphe/patches/tiktok/misc/diagnostics/BuildDetailsPatchTest.class",
    "app/morphe/patches/tiktok/misc/featuregatelab/FeatureGateLabFramesTest.class",
    "app/morphe/patches/tiktok/misc/onboarding/FirstLaunchSkipListTest.class",
    "app/morphe/patches/tiktok/misc/translation/ReceivedDmTranslationIconTest.class",
    "app/morphe/patches/tiktok/profile/LocalProfileAppearanceAnchorsTest.class",
)
// The plugin copies extensions/*.mpe into the main resources. nativeTest reads this copy instead,
// so rebuilding the extension doesn't change its classpath.
val nativeTestResources = tasks.register<Sync>("nativeTestResources") {
    from(tasks.processResources)
    exclude("extensions/**")
    into(layout.buildDirectory.dir("resources/nativeTest"))
}
val nativeTest = tasks.register<Test>("nativeTest") {
    group = "verification"
    description = "Runs the patch tests that read neither extension sources nor the extension bundle."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    val mainResources = sourceSets.main.get().output.resourcesDir
    classpath = sourceSets.test.get().runtimeClasspath.filter { it != mainResources } + files(nativeTestResources)
    exclude(documentationClasses + extensionClasses)
    inputs.property("suiteSelection", provider {
        (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns
    })
    failOnNoDiscoveredTests.set(provider {
        (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns.isEmpty()
    })
    val testRoot = file("src/test/kotlin")
    val testSources = fileTree(testRoot) { include("**/*.kt") }
    // By package path, the way include and exclude match: a listed test moved to another
    // package would otherwise still pass here by its name while test no longer ran it.
    val listed = extensionClasses.map { it.removeSuffix(".class") }.toSet()
    doFirst {
        val readsExtensions = Regex("""["/\\]extensions[/\\"]""")
        val unlisted = testSources.files
            .filter {
                it.relativeTo(testRoot).invariantSeparatorsPath.removeSuffix(".kt") !in listed &&
                    readsExtensions.containsMatchIn(it.readText())
            }
            .map { it.relativeTo(projectDir).invariantSeparatorsPath }.sorted()
        check(unlisted.isEmpty()) {
            "These test sources read extensions, but nativeTest ignores extension changes. " +
                "Add their test classes to extensionClasses in patches/build.gradle.kts, or fix " +
                "an entry whose test moved: $unlisted"
        }
        val selected = (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns
        if (selected.isNotEmpty()) {
            setTestNameIncludePatterns(selected.toList())
            filter.isFailOnNoMatchingTests = false
        }
    }
}
val documentationTest = tasks.register<Test>("documentationTest") {
    group = "verification"
    description = "Checks the README, source catalog and approved artwork."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxHeapSize = "512m"
    include(documentationClasses)
    // TestFilter has no public getter for CLI patterns in the pinned Gradle 9.7.1 API.
    // Record forwarded filters before input snapshotting, then apply them before execution.
    inputs.property("suiteSelection", provider {
        (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns
    })
    failOnNoDiscoveredTests.set(provider {
        (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns.isEmpty()
    })
    doFirst {
        val selected = (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns
        if (selected.isNotEmpty()) {
            setTestNameIncludePatterns(selected.toList())
            filter.isFailOnNoMatchingTests = false
        }
    }
}

// A finalizer also runs when the native partition is UP-TO-DATE or NO-SOURCE.
val verifyPatchTestSelection = tasks.register("verifyPatchTestSelection") {
    mustRunAfter(tasks.test, nativeTest, documentationTest)
    doLast {
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val suites = mutableSetOf<String>()
        var total = 0
        for (partition in listOf("test", "nativeTest", "documentationTest")) {
            val reports = layout.buildDirectory.dir("test-results/$partition").get().asFile
            for (report in reports.listFiles().orEmpty().filter { it.name.startsWith("TEST-") && it.extension == "xml" }) {
                val suite = parser.parse(report).documentElement
                val name = suite.getAttribute("name")
                check(suites.add(name)) { "Duplicate patch test suite: $name" }
                total += suite.getElementsByTagName("testcase").length
            }
        }
        check(total > 0) { "No tests matched the requested selection" }
        val selection = tasks.test.get().filter as DefaultTestFilter
        if (selection.commandLineIncludePatterns.isEmpty() && selection.includePatterns.isEmpty()) {
            val expected = fileTree("src/test/kotlin") { include("**/*Test.kt") }
                .files.map { it.nameWithoutExtension }.toSet()
            val ran = suites.map { it.substringAfterLast('.') }.toSet()
            check(ran == expected) {
                "Incomplete patch test run. Missing: ${expected - ran}; orphaned: ${ran - expected}"
            }
        }
        logger.lifecycle("Patch tests across all three partitions: $total")
    }
}

val patchTestPartitions = setOf("test", "nativeTest", "documentationTest")
tasks.withType<Test>().matching { it.name in patchTestPartitions }.configureEach {
    val contractFiles = when (name) {
        "documentationTest" -> rootProject.files(
            "patches/src", "README.md", "patches-list.json", "patches-bundle.json",
            "assets/readme-header.png", "patches-bundle.png"
        )
        "nativeTest" -> rootProject.files(fileTree("src"), rootProject.file("patches-list.json"))
        else -> rootProject.files(fileTree("src"), rootProject.file("patches-list.json"),
            rootProject.fileTree("extensions") { include("**/src/**"); exclude("**/build/**") })
    }
    // These contracts read raw files beyond the compiled classpath. Hash content directly so
    // equal size/mtime replacements also invalidate Gradle's cached file fingerprints.
    val contractContent = provider {
        contractFiles.asFileTree.files.filter { it.isFile }.associate { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { stream ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            source.relativeTo(rootProject.projectDir).invariantSeparatorsPath to
                digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    inputs.files(contractFiles).withPropertyName("contractFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("contractContent", contractContent)
    // The XML output directory, including this receipt, is retained with cached test results.
    val receipt = layout.buildDirectory.file("test-results/$name/source-inputs.sha256")
    var startedWith = emptyMap<String, String>()
    doFirst { startedWith = contractContent.get() }
    doLast {
        check(startedWith == contractContent.get()) { "Contract inputs changed during $name" }
        receipt.get().asFile.parentFile.mkdirs()
        receipt.get().asFile.writeText(startedWith.toSortedMap().entries.joinToString("\n", postfix = "\n") {
            (path, digest) -> "$digest $path"
        })
    }
}

// The fixture tests skip when this is unset and read the folder when it is set. Blank counts as
// unset, as Fixtures.kt reads it; File("") would be the whole project.
val fixtureDirectory = providers.environmentVariable("HUSHFEED_FIXTURE_DIR")
// Gradle's file-hash cache can reuse a digest after an external file is replaced with the same
// size and timestamp, so the fixture bytes are read independently. Once per build: both
// partitions that open the APKs declare the same digests.
val fixtureDigests by lazy {
    fixtureDirectory.map { directory ->
        val fixtures = if (directory.isBlank()) emptyList() else File(directory).listFiles()
            ?.filter { it.isFile && it.extension in setOf("apk", "apkm") }.orEmpty()
        fixtures.associate { fixture ->
            val digest = MessageDigest.getInstance("SHA-256")
            fixture.inputStream().use { stream ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            fixture.name to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrElse(emptyMap())
}
// Both partitions that open TikTok APKs.
tasks.withType<Test>().matching { it.name == "test" || it.name == "nativeTest" }.configureEach {
    // GateCatalogFixturesTest runs the catalog generator on each declared build, and the
    // generator holds a whole APK's dex (some 430 MB on 47.1.x, feature modules included)
    // while it walks it; the fixture scans in nativeTest hold the same dex. Gradle's default
    // test heap is 512 MB, where that ran out of memory.
    maxHeapSize = "4g"
    // nativeTest is some 600 single-threaded scans over the one declared build's dex. Two forks
    // each decode it once (the [fixtures] line in the report counts decodes) and split the
    // classes, which takes the two workers the build governor allows. 2 GB holds one 47.1.4 dex
    // and a scan, so two forks take no more heap than the single 4 GB JVM did.
    if (name == "nativeTest") {
        maxParallelForks = 2
        maxHeapSize = "2g"
    }
    // What the folder holds is the input, not its name: a run whose APK was swapped, re-signed
    // or deleted under the same path has to run again, not come back up to date or out of the
    // build cache with the last folder's verdict. Relative, so where the folder sits on this
    // machine does not count, and an APK moved into or out of a subfolder does: the tests read
    // only the folder's top level, and name only would call that move no change.
    inputs.files(fixtureDirectory.map { if (it.isBlank()) emptyList() else listOf(File(it)) }.orElse(emptyList()))
        .withPropertyName("fixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("fixtureBytes", provider { fixtureDigests })
}

tasks {
    test {
        dependsOn(documentationTest, nativeTest)
        finalizedBy(verifyPatchTestSelection)
        include(extensionClasses)
        // A --tests selection may belong only to another partition. The finalizer checks every
        // result set so an unmatched selection still fails, as it did before.
        filter.isFailOnNoMatchingTests = false
        failOnNoDiscoveredTests.set(false)
    }
    // The bundle a release publishes lives in build/release, not build/libs. The plugin's
    // buildAndroid merges the DEX payload into the jar task's own output in place, so any later
    // task that reruns jar (test does) put the plain jar back over the finished bundle under the
    // same name: v0.43.0 shipped with no classes.dex that way, and on 2026-09-21 the pre-push
    // test run did it again between the build and the index push. Nothing but buildAndroid
    // writes build/release. scripts/common.ps1 names the same path for every release script.
    val releaseBundleName = "patches-${project.version}.mpp"
    val verifySourceProvenance = register("verifySourceProvenance") {
        group = "verification"
        description = "Checks source snapshots and manifest stamping against real temporary Git files"
        val testDirectory = layout.buildDirectory.dir("source-provenance-tests")
        doLast {
            val boundary = testDirectory.get().asFile.canonicalFile
            boundary.mkdirs()
            val fixture = Files.createTempDirectory(boundary.toPath(), "source-").toFile()
            try {
                val unknown = BundleSource.capture(fixture)
                check(!unknown.clean) { "A directory without its own Git repository was eligible" }
                BundleSource.git(fixture, "init", "--quiet")
                val input = fixture.resolve("input.txt")
                input.writeText("original\n")
                fixture.resolve(".gitattributes").writeText("* text=auto eol=lf\n")
                BundleSource.git(fixture, "add", "input.txt", ".gitattributes")
                fun commit() {
                    BundleSource.git(fixture, "-c", "user.name=SysAdminDoc", "-c",
                        "user.email=matt_parker@outlook.com", "-c", "commit.gpgsign=false",
                        "commit", "--quiet", "-m", "Record source provenance fixture")
                }
                commit()
                val clean = BundleSource.capture(fixture)
                check(clean.clean) { "A clean committed source tree was ineligible" }
                input.writeText("changed\n")
                val dirty = BundleSource.capture(fixture)
                check(!dirty.clean && dirty.fingerprint != clean.fingerprint) { "A changed source input was missed" }
                input.writeText("original\n")
                val restored = BundleSource.capture(fixture)
                check(restored.clean && restored.fingerprint == clean.fingerprint) { "Restored bytes did not match" }
                val extra = fixture.resolve("untracked.txt")
                extra.writeText("untracked\n")
                check(!BundleSource.capture(fixture).clean) { "An untracked input was treated as clean" }
                check(extra.delete())
                val sourceRegressions = mutableListOf<String>()
                for (relative in listOf("src/main/java/Ignored.java", "src/main/resources/ignored.txt")) {
                    fixture.resolve(".git/info/exclude").apply {
                        parentFile.mkdirs()
                        appendText("\n/$relative\n")
                    }
                    val ignored = fixture.resolve(relative)
                    ignored.parentFile.mkdirs()
                    ignored.writeText(if (relative.endsWith(".java")) "class Ignored {}\n" else "ignored resource\n")
                    try {
                        if (BundleSource.capture(fixture).clean) sourceRegressions += "ignored $relative was eligible"
                    } finally { check(ignored.delete()) }
                }
                input.writeText("original\r\n")
                // Refresh Git's index using the committed normalization policy before checking it.
                BundleSource.git(fixture, "add", "--renormalize", "--", "input.txt")
                val crlf = BundleSource.capture(fixture)
                check(crlf.clean) { "The CRLF control is not Git-equivalent to its LF commit" }
                // checkout-index trusts matching index metadata, so remove this owned test input first.
                check(input.delete()) { "Could not remove the fresh-checkout control input" }
                BundleSource.git(fixture, "checkout-index", "--force", "--index", "--", "input.txt")
                check(input.readBytes().contentEquals("original\n".toByteArray())) {
                    "The fresh-checkout control did not apply the committed LF policy"
                }
                val fresh = BundleSource.capture(fixture)
                check(fresh.clean) { "The fresh-checkout control is not clean" }
                if (crlf.fingerprint != fresh.fingerprint) {
                    sourceRegressions += "Git-equivalent line endings changed the source fingerprint"
                }
                if (BundleSource.manifestFacts(crlf, crlf) != BundleSource.manifestFacts(fresh, fresh)) {
                    sourceRegressions += "a fresh checkout changed the manifest source facts"
                }
                check(BundleSource.manifestFacts(crlf, fresh)["Metra-Source-Clean"] == "false") {
                    "A byte change during one build must stay ineligible even when Git normalizes it"
                }
                check(sourceRegressions.isEmpty()) { sourceRegressions.joinToString("; ") }
                for (flag in listOf("assume-unchanged", "skip-worktree")) {
                    BundleSource.git(fixture, "update-index", "--$flag", "input.txt")
                    input.writeText("hidden change\n")
                    check(!BundleSource.capture(fixture).clean) { "A $flag input hid a source edit" }
                    BundleSource.git(fixture, "update-index", "--no-$flag", "input.txt")
                    input.writeText("original\n")
                }
                input.writeText("new committed input\n")
                BundleSource.git(fixture, "add", "input.txt")
                commit()
                val moved = BundleSource.capture(fixture)
                check(moved.clean && moved.commit != clean.commit) { "The source commit did not move" }
                val cases = listOf(
                    Triple(clean, clean, true), Triple(dirty, dirty, false),
                    Triple(clean, dirty, false), Triple(clean, moved, false),
                    Triple(unknown, clean, false)
                )
                for ((index, test) in cases.withIndex()) {
                    val bundle = boundary.resolve("${fixture.name}-snapshot-$index.mpp")
                    try {
                        ZipOutputStream(bundle.outputStream()).use { zip ->
                            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                            zip.write("Manifest-Version: 1.0\r\nTimestamp: 0\r\n\r\n".toByteArray())
                            zip.closeEntry()
                        }
                        // Every case gets the same reproducible stamp, including dirty/restored.
                        pinBundleTimestamp(bundle, 1_700_000_000L, BundleSource.manifestFacts(test.first, test.second))
                        ZipFile(bundle).use { zip ->
                            val manifest = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))
                                .use { Manifest(it).mainAttributes }
                            check(manifest.getValue("Timestamp") == "1700000000000")
                            check(manifest.getValue("Metra-Source-Clean") == test.third.toString()) {
                                "Source eligibility changed while stamping case $index"
                            }
                            check(manifest.getValue("Metra-Source-Commit") == test.first.commit)
                            check(manifest.getValue("Metra-Source-Start") == test.first.fingerprint)
                            check(manifest.getValue("Metra-Source-End") == test.second.fingerprint)
                        }
                    } finally { check(bundle.delete()) { "Could not remove source test bundle" } }
                }
                logger.lifecycle("Source provenance: clean, dirty/restored, changed, hidden and unreadable input checks passed.")
            } finally {
                check(fixture.canonicalFile.parentFile == boundary) { "Source test directory escaped its build folder" }
                check(fixture.deleteRecursively()) { "Could not remove source test repository" }
            }
        }
    }
    val verifyBundle = register<JavaExec>("verifyBundle") {
        group = "verification"
        description = "Check the Android bundle and its published patch list without rebuilding it"
        dependsOn(classes, verifySourceProvenance)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.BundleVerifier")
        args(
            providers.gradleProperty("patchBundle").getOrElse(
                layout.buildDirectory.file("release/$releaseBundleName").get().asFile.absolutePath
            ),
            rootProject.file("patches-list.json").absolutePath,
            project.version.toString(),
            layout.buildDirectory.file("release/bundle.sha256").get().asFile.absolutePath
        )
    }
    // Every configured module uses its standard src tree; generated build directories stay excluded.
    val bundleSourceDirectories = rootProject.allprojects.map {
        it.projectDir.resolve("src").relativeTo(rootProject.projectDir).invariantSeparatorsPath
    }.distinct().sorted()
    val captureSource = register<CaptureBundleSource>("captureBundleSource") {
        sourceRoot.set(rootProject.layout.projectDirectory)
        sourceDirectories.set(bundleSourceDirectories)
        snapshotFile.set(layout.buildDirectory.file("source-provenance/start.properties"))
    }
    // A doFirst on buildAndroid is too late: its compiler dependencies have already run.
    // Ordering does not add these tasks to the graph. Requested clean tasks precede the snapshot.
    rootProject.allprojects {
        val cleaning = tasks.matching { it is Delete || it.name == "clean" }
        captureSource.configure { mustRunAfter(cleaning) }
        tasks.configureEach {
            if (path == ":patches:captureBundleSource") return@configureEach
            if (this !is Delete && name != "clean") mustRunAfter(captureSource)
        }
    }
    named("buildAndroid") {
        // Publishing changes the version before building. Regenerate the catalog first so
        // verifyBundle never compares the new bundle with the previous release's metadata.
        dependsOn(captureSource, "generatePatchesList")
        // Resolved at configuration time. Reaching for project inside doLast is what the
        // configuration cache refuses, and Gradle 10 turns that refusal into an error.
        val bundleFile = layout.buildDirectory.file("libs/$releaseBundleName")
        val releaseDirectory = layout.buildDirectory.dir("release")
        val pinnedEpoch = sourceDateEpoch
        val sourceRoot = rootProject.layout.projectDirectory.asFile
        val sourceDirectories = bundleSourceDirectories
        val startFile = layout.buildDirectory.file("source-provenance/start.properties")
        doLast {
            val saved = Properties().apply { startFile.get().asFile.inputStream().use { load(it) } }
            val start = BundleSource.Snapshot(saved.getProperty("commit", "unknown"),
                saved.getProperty("clean") == "true", saved.getProperty("fingerprint", "unavailable"),
                saved.getProperty("rawFingerprint", "unavailable"))
            val end = BundleSource.capture(sourceRoot, sourceDirectories)
            // Emptied first, so the directory never holds a bundle of another version or a
            // checksum of another build: the release scripts take the one file they find.
            val directory = releaseDirectory.get().asFile
            directory.mkdirs()
            directory.listFiles()?.filter { it.isFile }?.forEach { stale ->
                if (!stale.delete()) throw GradleException("Could not clear the old release file $stale")
            }
            val releaseBundle = directory.resolve(releaseBundleName)
            bundleFile.get().asFile.copyTo(releaseBundle)
            // Before the checksum, so what is recorded is what a rebuild will produce.
            pinBundleTimestamp(releaseBundle, pinnedEpoch, BundleSource.manifestFacts(start, end))
            // Record only at the producer boundary. Standalone verification must not
            // bless a modified bundle by generating its own expected checksum.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(releaseBundle.readBytes())
                .joinToString("") { "%02x".format(it) }
            directory.resolve("bundle.sha256").writeText(digest)
        }
        finalizedBy(verifyBundle)
    }
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        // jar, not build. build runs check, which runs test, and ReadmePatchNamesTest asserts
        // that the checked-in patches-list.json names exactly the patches the Kotlin sources
        // declare. Adding or renaming a patch therefore failed the test before the task that
        // regenerates the list could run, and the only ways through were -x test or editing the
        // generated JSON by hand. The generator reads build/libs/patches-<version>.mpp, which
        // jar produces; the test and verifyBundle are still the gate afterwards, which is the
        // order that can actually pass.
        dependsOn(jar)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
        args(project.version.toString())
    }
    // The patch list has to be regenerated before anything publishes the bundle.
    publish {
        dependsOn("generatePatchesList")
    }
    // Rebuilds the Feature Gate Lab's offline catalogs from every declared TikTok build:
    // ./gradlew :patches:generateGateCatalog reads their APKs from HUSHFEED_FIXTURE_DIR, and
    // -Papk=<a.apk>;<b.apk> names them instead. The generator is a test-source tool because the
    // test classpath is the one that carries dexlib2 and apksig.
    register<JavaExec>("generateGateCatalog") {
        description = "Rebuild the Feature Gate Lab catalogs from the declared TikTok builds"
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.morphe.gatecatalog.GateCatalogGenerator")
        maxHeapSize = "8g"
        args(
            rootProject.file("extensions/tiktok/src/main/java/app/morphe/extension/tiktok/featuregatelab").absolutePath,
            file("src/test/resources/gate-catalog-curated.tsv").absolutePath,
            providers.gradleProperty("apk").getOrElse("")
        )
    }
}
