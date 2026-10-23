package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.branding.changeAppNamePatch
import app.morphe.patches.tiktok.misc.branding.customLauncherIconPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.optimizer.*
import app.morphe.patches.tiktok.misc.theme.accentColorPatch
import app.morphe.patches.tiktok.misc.theme.amoledThemePatch
import app.morphe.patches.tiktok.misc.update.hidePlayStoreUpdatePatch
import app.morphe.patches.tiktok.privacy.trustUserCertificatesPatch
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildDetailsPatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = packageMetadata("com.zhiliaoapp.musically", "47.4.3", "2024704030")
    private val fingerprint = "A1234567890123456789B" + "c".repeat(43)

    @Test fun `bundle manifest paths preserve plus spaces percent and unicode and release the jar`() {
        listOf("bundle.jar", "bundle+fixture.jar", "bundle fixture.jar", "bundle%+caf\u00e9.jar").forEach { name ->
            val bundle = temporary.newFile(name)
            val expected = manifest()
            val contents = Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
                mainAttributes.putAll(expected)
            }
            JarOutputStream(bundle.outputStream(), contents).use { output ->
                output.putNextEntry(JarEntry("probe.class"))
                output.write(0)
                output.closeEntry()
            }
            val actual = bundleManifest(URI("jar:${bundle.toURI().toASCIIString()}!/probe.class").toURL())
            expected.forEach { (key, value) -> assertEquals("$name $key", value, actual[key]) }
            assertTrue("Manifest reader kept the bundle open: $name", bundle.delete())
        }
        assertTrue(bundleManifest(null).isEmpty())
        assertTrue(bundleManifest(URI("file:/unpacked/probe.class").toURL()).isEmpty())
        assertTrue(bundleManifest(URI("jar:https://example.invalid/bundle.jar!/probe.class").toURL()).isEmpty())
    }

    @Test fun `verified choices use the same canonical record the runtime reads`() {
        val output = record("golden")
        BuildDetails.amoled(output, "#ff121212")
        BuildDetails.languages(output, StripSummary(12, 500, false, setOf("tr", "iw", "in", "id", "he", "en")))
        BuildDetails.stripped(output, BuildChoice.P2P, StripSummary(4, 30, false))
        BuildDetails.stripped(output, BuildChoice.CORE, StripSummary(4, 0, true))
        BuildDetails.stripped(output, BuildChoice.CREATION, StripSummary(0, 0, false))
        BuildDetails.raisedVersionCode(output)
        BuildDetails.applied(output, "customLauncherIcon")
        BuildDetails.applied(output, "changeAppName")
        BuildDetails.applied(output, "customLauncherIcon")
        val root = File("../extensions/tiktok/src/test/resources/build-details/complete.txt")
            .takeIf(File::isFile) ?: File("extensions/tiktok/src/test/resources/build-details/complete.txt")
        assertEquals(root.readText().replace("\r\n", "\n"), output.readText())
        assertTrue(output.readText().contains("source_start=$fingerprint\n"))
        assertFalse(output.readText().contains("raw_option"))
    }

    @Test fun `contexts interleave and repeated invocations reset a failed selection`() {
        val first = record("first")
        val second = record("second")
        BuildDetails.selected(first, BuildChoice.LIVE) // A later bytecode dependency can fail here.
        BuildDetails.amoled(second, "#ABCDEF")
        assertEquals("unverified", first.facts()[BuildChoice.LIVE.field])
        assertEquals("not_selected", second.facts()[BuildChoice.LIVE.field])
        assertEquals("not_selected", first.facts()[BuildChoice.AMOLED.field])
        assertEquals("applied", second.facts()[BuildChoice.AMOLED.field])

        // There is no finalize cleanup to rely on. Starting again also clears a run that aborted.
        BuildDetails.begin(first, target, manifest(), "1.14.2")
        assertEquals("not_selected", first.facts()[BuildChoice.LIVE.field])
        BuildDetails.selected(first, BuildChoice.AMOLED)
        assertThrows(IllegalArgumentException::class.java) { BuildDetails.amoled(first, "sessionid=COLOR_SENTINEL") }
        assertEquals("unverified", first.facts()[BuildChoice.AMOLED.field])
        assertFalse(first.readText().contains("COLOR_SENTINEL"))
        BuildDetails.begin(first, target, manifest(), "1.14.2")
        BuildDetails.amoled(first, "#000000")
        assertEquals("#000000", first.facts()["amoled_color"])
        assertEquals("#ABCDEF", second.facts()["amoled_color"])
        BuildDetails.begin(first, target, manifest(), "1.14.2")
        assertEquals("not_selected", first.facts()["amoled"])
        assertEquals("unknown", first.facts()["native_locales_retained"])
    }

    @Test fun `a verified resource failure never records success or changes another file`() {
        val root = temporary.newFolder("bad-resource")
        val file = root.resolve("assets/group/a.bin").apply { parentFile.mkdirs(); writeText("changed") }
        val output = record("failure")
        BuildDetails.selected(output, BuildChoice.CORE)
        val hash = MessageDigest.getInstance("SHA-256").digest("reviewed".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertThrows(PatchException::class.java) {
            val result = stripVerifiedResources(root, "Fixture", listOf("assets/group"), emptyList(),
                listOf(ResourceProfile("Fixture", listOf(ResourceFileContract("assets/group/a.bin", hash)))))
            BuildDetails.stripped(output, BuildChoice.CORE, result)
        }
        assertEquals("changed", file.readText())
        assertEquals("unverified", output.facts()[BuildChoice.CORE.field])
        BuildDetails.begin(output, target, manifest(), "1.14.2")
        assertEquals("not_selected", output.facts()[BuildChoice.CORE.field])
    }

    @Test fun `normalization and completion order are deterministic but choices differ`() {
        val first = record("order-one")
        val second = record("order-two")
        BuildDetails.amoled(first, "#ffabcdef")
        BuildDetails.languages(first, StripSummary(0, 0, false, setOf("tr", "en")))
        BuildDetails.stripped(first, BuildChoice.P2P, StripSummary(0, 0, false))
        BuildDetails.stripped(second, BuildChoice.P2P, StripSummary(0, 0, false))
        BuildDetails.languages(second, StripSummary(0, 0, false, setOf("en", "tr")))
        BuildDetails.amoled(second, "#ABCDEF")
        assertEquals(first.readText(), second.readText())
        assertEquals("kept_all", first.facts()["language_packs"])
        assertEquals("en,tr", first.facts()["native_locales_retained"])
        BuildDetails.amoled(second, "#000000")
        assertNotEquals(first.readText(), second.readText())
        BuildDetails.languages(second, StripSummary(3, 0, true, setOf("en")))
        assertEquals("already_stripped", second.facts()["language_packs"])
    }

    @Test fun `provenance keeps exact hashes and distinguishes dirty and legacy bundles`() {
        val first = record("clean")
        val dirty = manifest().apply { putValue("Metra-Source-Clean", "false"); putValue("Metra-Source-End", "D".repeat(64)) }
        val second = record("dirty", facts = dirty)
        assertNotEquals(first.readText(), second.readText())
        assertEquals("false", second.facts()["source_clean"])
        assertEquals(fingerprint, second.facts()["source_start"])
        assertEquals("D".repeat(64), second.facts()["source_end"])
        assertEquals("1.14.1", second.facts()["patcher_bundle_compat"])
        assertEquals("1.14.2", second.facts()["patcher_applying_engine"])
        val invalid = manifest().apply {
            putValue("Version", "https://SOURCE_SENTINEL.invalid/")
            putValue("Metra-Source-Commit", "account=SOURCE_SENTINEL")
            putValue("Metra-Source-End", "bad")
        }
        val third = record("invalid", facts = invalid)
        assertEquals("unknown", third.facts()["source_clean"])
        assertEquals("unknown", third.facts()["bundle_version"])
        assertFalse(third.readText().contains("SOURCE_SENTINEL"))
        val legacy = record("legacy", facts = Attributes(), engine = null).facts()
        assertEquals("unknown", legacy["source_commit"])
        assertEquals("unknown", legacy["patcher_applying_engine"])
    }

    @Test fun `target facts match all declared builds and never capture unreviewed package names`() {
        AppCompatibilities.tiktokAndPineDrama().forEach { compatibility ->
            compatibility.targets.forEach { app ->
                assertFalse(app.versionCodes.isNullOrEmpty())
                app.versionCodes.orEmpty().values.distinct().forEach { code ->
                    val output = record("target-${compatibility.packageName}-${app.version}-$code",
                        packageMetadata(checkNotNull(compatibility.packageName), checkNotNull(app.version), code.toString()))
                    assertEquals(compatibility.packageName, output.facts()["target_package"])
                    assertEquals(app.version, output.facts()["target_version"])
                    assertEquals(code.toString(), output.facts()["target_version_code"])
                    BuildDetails.selected(output, BuildChoice.VERSION_CODE)
                    assertEquals("unverified", output.facts()["version_code_override"])
                    BuildDetails.raisedVersionCode(output)
                    assertEquals("2147483647", output.facts()["version_code_override"])
                    assertEquals(code.toString(), output.facts()["target_version_code"])
                }
            }
        }
        val unknown = record("unreviewed", packageMetadata("account.TARGET_SENTINEL", "99.0.0", "1"))
        assertEquals("unknown", unknown.facts()["target_package"])
        assertFalse(unknown.readText().contains("TARGET_SENTINEL"))
    }

    /** The extension validates targets from its own copy. This fixture ties that copy to the catalog. */
    @Test fun `runtime target fixture is exactly the declared builds`() {
        val declared = AppCompatibilities.tiktokAndPineDrama().flatMap { compatibility ->
            compatibility.targets.flatMap { app ->
                app.versionCodes.orEmpty().values.distinct().map { "${compatibility.packageName}/${app.version}=$it" }
            }
        }.distinct().sorted()
        val fixture = File("../extensions/tiktok/src/test/resources/build-details/targets.txt")
            .takeIf(File::isFile) ?: File("extensions/tiktok/src/test/resources/build-details/targets.txt")
        assertEquals(declared.joinToString("\n"), fixture.readText().replace("\r\n", "\n").trim())
    }

    @Test fun `native locale allowlist is exactly the reviewed inventory union`() {
        assertEquals(languageInventories.flatMap { it.directories }.toSet(), BuildDetails.nativeLocales)
        val fixture = File("../extensions/tiktok/src/test/resources/build-details/native-locales.txt")
            .takeIf(File::isFile) ?: File("extensions/tiktok/src/test/resources/build-details/native-locales.txt")
        assertEquals(fixture.readText().trim(), BuildDetails.nativeLocales.sorted().joinToString(","))
    }

    @Test fun `input asset collision fails before initializing and incomplete output is not repaired silently`() {
        val file = temporary.newFile("vendor.txt").apply { writeText("VENDOR_SENTINEL") }
        assertThrows(PatchException::class.java) {
            refuseBuildAssetCollision(listOf(BUILD_DETAILS_ASSET))
            BuildDetails.begin(file, target)
        }
        assertEquals("VENDOR_SENTINEL", file.readText())
        refuseBuildAssetCollision(listOf(BUILD_DETAILS_ASSET + ".vendor"))
        assertThrows(IllegalStateException::class.java) { BuildDetails.selected(file, BuildChoice.P2P) }
        assertEquals("VENDOR_SENTINEL", file.readText())
    }

    @Test fun `resource-only patches start as none and list only their own sorted names`() {
        val output = record("resource-patches")
        assertEquals("none", output.facts()["resource_patches"])
        BuildDetails.applied(output, "trustUserCertificates")
        BuildDetails.applied(output, "changeAppName")
        assertEquals("changeAppName,trustUserCertificates", output.facts()["resource_patches"])
        // An option's value, like the app name someone typed, can never take a patch's place.
        listOf("My TikTok", "sessionid=RAW_SENTINEL", "ChangeAppName", "", "a".repeat(65)).forEach { name ->
            assertThrows(name, IllegalArgumentException::class.java) { BuildDetails.applied(output, name) }
        }
        assertFalse(output.readText().contains("RAW_SENTINEL"))
        BuildDetails.begin(output, target, manifest(), "1.14.2")
        assertEquals("none", output.facts()["resource_patches"])
    }

    @Test fun `every choice and every resource patch together stay under the runtime's size cap`() {
        val output = record("everything")
        BuildDetails.amoled(output, "#ff121212")
        BuildDetails.languages(output, StripSummary(1, 1, false, BuildDetails.nativeLocales))
        listOf(BuildChoice.P2P, BuildChoice.CORE, BuildChoice.CREATION, BuildChoice.LIVE)
            .forEach { BuildDetails.stripped(output, it, StripSummary(1, 1, true)) }
        BuildDetails.raisedVersionCode(output)
        listOf("accentColor", "changeAppName", "customLauncherIcon", "trustUserCertificates").forEach { BuildDetails.applied(output, it) }
        assertTrue("the record is ${output.length()} bytes", output.length() <= 4096)
    }

    @Test fun `resource choices add only raw metadata dependencies and live retains its existing bytecode`() {
        listOf(accentColorPatch, p2pRelayBlockerPatch, coreAssetDebloatPatch, languagePackPurgerPatch,
            studioCreationDebloatPatch, hidePlayStoreUpdatePatch, changeAppNamePatch, customLauncherIconPatch,
            trustUserCertificatesPatch).forEach { patch ->
            val closure = dependencies(patch)
            assertTrue("${patch.name} has no metadata dependency", buildDetailsPatch in closure)
            assertTrue("${patch.name} unexpectedly injects runtime hooks", closure.none { it is BytecodePatch })
        }
        // AMOLED repaints one Compose palette builder in place (#152). That is bytecode, but it
        // calls nothing of Hushfeed's, so the extension stays out of a build that only wants AMOLED.
        val amoled = dependencies(amoledThemePatch)
        assertTrue("AMOLED has no metadata dependency", buildDetailsPatch in amoled)
        val amoledBytecode = amoled.filterIsInstance<BytecodePatch>()
        assertEquals("AMOLED's bytecode is the palette repaint alone", 1, amoledBytecode.size)
        assertEquals(null, amoledBytecode.single().name)
        assertTrue("AMOLED unexpectedly injects runtime hooks", sharedExtensionPatch !in amoled)
        assertEquals(setOf(liveGiftEffectOptimizerPatch),
            dependencies(liveStreamSuiteOptimizerPatch).filterIsInstance<BytecodePatch>().toSet())
        assertTrue(buildDetailsPatch in dependencies(sharedExtensionPatch))
        assertEquals(null, buildDetailsPatch.name)
        assertEquals(null, liveStreamSuiteOptimizerPatch.dependencies.first().name)
        assertTrue(buildDetailsPatch in dependencies(liveStreamSuiteOptimizerPatch.dependencies.first()))
    }

    private fun dependencies(patch: Patch<*>): Set<Patch<*>> = linkedSetOf<Patch<*>>().apply {
        fun visit(current: Patch<*>) { if (add(current)) current.dependencies.forEach(::visit) }
        visit(patch)
    }

    private fun record(name: String, context: PackageMetadata = target, facts: Attributes = manifest(), engine: String? = "1.14.2") =
        temporary.newFolder(name).resolve(BUILD_DETAILS_ASSET).apply { BuildDetails.begin(this, context, facts, engine) }

    private fun manifest() = Attributes().apply {
        putValue("Version", "0.66.0"); putValue("Patcher-Version", "1.14.1")
        putValue("Metra-Source-Commit", "a".repeat(40)); putValue("Metra-Source-Clean", "true")
        putValue("Metra-Source-Start", fingerprint); putValue("Metra-Source-End", fingerprint)
    }

    private fun File.facts() = readLines().associate { it.substringBefore('=') to it.substringAfter('=') }

    // Patcher metadata is public at the JVM boundary but its constructor is Kotlin-internal.
    private fun packageMetadata(name: String, version: String, code: String) =
        PackageMetadata::class.java.constructors.single { it.parameterCount == 4 }
            .newInstance(name, version, code, null) as PackageMetadata
}
