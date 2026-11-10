package app.morphe.patches.shared.compat

import app.morphe.Fixtures
import app.morphe.util.BundleVerifier
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing certificate and version code the bundle declares have to be the ones on TikTok's own
 * builds. Morphe Manager warns about a picked APK whose certificate differs and treats a version
 * code it was not told about as unsupported, so a wrong value here would warn every user who
 * downloaded the genuine release.
 */
class AppCompatibilitiesMatchFixturesTest {

    @Test
    fun `the bundle producer description names every declared host`() {
        val buildScript = File("build.gradle.kts").takeIf { it.isFile }
            ?: File("patches/build.gradle.kts")
        assertTrue("patches build script is missing", buildScript.isFile)
        val description = Regex("""description = "(Metra patches for TikTok[^"]+)"""")
            .find(buildScript.readText())?.groupValues?.get(1)
        BundleVerifier.requireTargetDescription(description)
    }

    @Test
    fun `the bundle verifier rejects each omitted host and version lookalikes`() {
        val versions = AppCompatibilities.tiktok().single().targets.map { checkNotNull(it.version) }
        BundleVerifier.requireTargetDescription("TikTok " + versions.joinToString(", "))
        for (missing in versions) {
            val others = versions.filter { it != missing }.joinToString(", ")
            for (description in listOf<String?>(null, "TikTok $others", "TikTok $others, ${missing}0")) {
                val failure = runCatching { BundleVerifier.requireTargetDescription(description) }.exceptionOrNull()
                assertTrue("accepted a description missing $missing: $description", failure is IllegalArgumentException)
                assertTrue("refusal must name $missing", failure!!.message!!.contains(missing))
            }
        }
    }

    @Test
    fun `every retained vendor build is signed by the declared certificate`() {
        val signers = mutableMapOf<String, Set<String>>()
        for (fixture in Fixtures.files { it.extension == "apk" || it.extension == "apkm" }) {
            apksIn(fixture) { name, apk ->
                val result = ApkVerifier.Builder(apk).build().verify()
                assertTrue("$name does not verify: ${result.errors}", result.isVerified)
                signers[name] = result.signerCertificates.map { sha256(it.encoded) }.toSet()
            }
        }
        val declared = AppCompatibilities.tiktok().single().signatures
        assertEquals("declared signatures", setOf(AppCompatibilities.TIKTOK_SIGNER_SHA256), declared)
        val other = signers.filterValues { it != declared }
        assertEquals("fixtures another certificate signed", emptyMap<String, Set<String>>(), other)
    }

    @Test
    fun `each declared target carries the version code of its vendor build`() {
        val targets = AppCompatibilities.tiktok().single().targets
        val expected = mapOf("47.4.3" to AppCompatibilities.TIKTOK_4743_VERSION_CODE)
        assertEquals("declared versions", expected.keys, targets.map { it.version }.toSet())
        assertEquals("declared TikTok codes", expected.values.toSet(), AppCompatibilities.TIKTOK_VERSION_CODES)
        assertEquals(
            "the codes the Play Store patch accepts",
            expected.values.toSet(),
            AppCompatibilities.DECLARED_VERSION_CODES,
        )
        for (target in targets) {
            val version = checkNotNull(target.version)
            val codes = checkNotNull(target.versionCodes) { "the $version target declares no version codes" }
            assertEquals("declared codes for $version", setOf(expected.getValue(version)), codes.values.toSet())

            val fixture = Fixtures.apkOf(version)
            val manifest = RandomAccessFile(fixture, "r").use { file ->
                ApkUtils.getAndroidManifest(DataSources.asDataSource(file))
            }
            assertEquals(
                "${fixture.name} version code",
                expected.getValue(version),
                ApkUtils.getVersionCodeFromBinaryAndroidManifest(manifest),
            )
        }
    }

    /**
     * PineDrama (#156) is declared at its own newest build under its own key. Its 47.1.4 is built
     * from TikTok's 47.1.4 code and carries that build's version code.
     */
    @Test
    fun `the retained PineDrama reference helper does not expand Metra catalog targets`() {
        val pineDrama = AppCompatibilities.pineDrama().single()
        assertEquals("com.ss.android.ttmd.video", pineDrama.packageName)
        assertEquals(setOf(AppCompatibilities.PINEDRAMA_SIGNER_SHA256), pineDrama.signatures)
        assertEquals(
            "PineDrama's targets",
            listOf("47.1.4" to setOf(2024701040)),
            pineDrama.targets.map { it.version to it.versionCodes?.values?.toSet() },
        )
        assertEquals(listOf("TikTok"), AppCompatibilities.tiktokAndPineDrama().map { it.name })
    }

    /** Every APK a fixture holds: itself, or each split inside an .apkm, copied out to a temp file. */
    private fun apksIn(fixture: File, check: (String, File) -> Unit) {
        if (fixture.extension == "apk") return check(fixture.name, fixture)
        ZipFile(fixture).use { zip ->
            val apks = zip.entries().asSequence().filter { it.name.endsWith(".apk") }.toList()
            assertTrue("${fixture.name} holds no APK", apks.isNotEmpty())
            for (entry in apks) {
                val copy = File.createTempFile("fixture-split", ".apk")
                try {
                    zip.getInputStream(entry).use { input -> copy.outputStream().use { input.copyTo(it) } }
                    check("${fixture.name}!${entry.name}", copy)
                } finally {
                    copy.delete()
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
