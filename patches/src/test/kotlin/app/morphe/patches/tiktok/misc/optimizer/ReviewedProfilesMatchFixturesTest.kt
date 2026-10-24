package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.Fixtures
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Every reviewed path set has to describe a build that is actually on the desk.
 *
 * <p>A resource strip refuses a build whose files it has not reviewed: the paths it finds must
 * equal one profile's path set and every byte must hash to what that profile recorded. That
 * check used to run for the first time inside the release receipt, thirty minutes in, and
 * twice on 2026-09-17 it was the receipt that found a hash copied from the wrong build (the
 * core asset set on 46.7.3, then the P2P relay set on 46.9.3). This asks the same question of
 * every fixture APK on the machine in a few seconds, and names the entry that is off. Without
 * a fixture it is skipped rather than passed.
 *
 * <p>An .apkm split bundle is read the way Morphe merges one: every split's entries together.
 * That is the shape issue #9's reporter patched, and all four strips refused it.
 */
class ReviewedProfilesMatchFixturesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val groups = mapOf(
        "core assets" to coreAssetProfiles,
        "creation tools" to studioAssetProfiles,
        "LIVE extras" to liveAssetProfiles,
        "P2P relay" to p2pRelayProfiles,
    )

    @Test
    fun `every fixture matches one reviewed profile of every strip, byte for byte`() {
        val apks = fixtures()
        val wanted = groups.values.flatten().flatMap { it.files }.map { it.path }.toSet()
        val problems = mutableListOf<String>()
        for (apk in apks) {
            val present = digests(apk) { it in wanted }
            val version = Fixtures.versionOf(apk)
            for ((group, reviewed) in groups) {
                val paths = reviewed.flatMap { it.files }.map { it.path }.toSet()
                val found = present.filterKeys { it in paths }
                // The patch only weighs the profiles that describe this build.
                val profiles = reviewed.filter { it.describes(version) }
                // A build that ships none of a group has to be reviewed as that, an empty profile of its own.
                assertTrue(
                    "$group: ${apk.name} carries none of the reviewed paths",
                    found.isNotEmpty() || profiles.any { it.files.isEmpty() },
                )
                val bySet = profiles.filter { profile -> profile.files.map { it.path }.toSet() == found.keys }
                if (bySet.isEmpty()) {
                    problems += "$group on ${apk.name}: the ${found.size} present paths match no profile's path set " +
                        "(${profiles.joinToString { "${it.label}=${it.files.size}" }})"
                    continue
                }
                val exact = bySet.firstOrNull { profile ->
                    profile.files.all { found[it.path].equals(it.sha256, ignoreCase = true) }
                }
                if (exact == null) {
                    val nearest = bySet.first()
                    val wrong = nearest.files.filter { !found[it.path].equals(it.sha256, ignoreCase = true) }
                    problems += "$group on ${apk.name}: ${nearest.label} lists the right paths but these hashes differ: " +
                        wrong.joinToString { "${it.path} reviewed ${it.sha256.take(12)} actual ${found[it.path]?.take(12)}" }
                }
            }
        }
        assertEquals("reviewed sets that do not describe the fixtures on this machine", emptyList<String>(), problems)
    }

    @Test
    fun `no strip empties a feature dex TikTok loads at start-up`() {
        // Emptying Pitaya's, live cast's or the camera's dex left TikTok's own strings in English (#125).
        val dexes = groups.flatMap { (group, reviewed) ->
            reviewed.flatMap { it.files }.map { it.path }.filter(::isStartupDex).map { "$group: $it" }
        }.distinct()
        assertEquals(emptyList<String>(), dexes)
    }

    @Test
    fun `every fixture's language packs match one reviewed inventory`() {
        val apks = fixtures()
        val problems = mutableListOf<String>()
        for (apk in apks) {
            val files = digests(apk) { it.startsWith(LANGUAGE_PREFIX) }
            val directories = files.keys
                .map { it.removePrefix(LANGUAGE_PREFIX).substringBefore('/').lowercase() }
                .toSet()
            val contract = languageInventories.firstOrNull { it.directories == directories }
            if (contract == null) {
                problems += "${apk.name}: ${directories.size} language directories match no reviewed inventory " +
                    "(${languageInventories.joinToString { it.directories.size.toString() }})"
                continue
            }
            // The same two manifests stripVerifiedLanguagePacks builds.
            val paths = files.keys.sorted()
            val pathManifest = sha256(paths.joinToString("") { "$it\n" }.toByteArray())
            val contentManifest = sha256(paths.joinToString("") { "$it\u0000${files[it]}\n" }.toByteArray())
            if (pathManifest != contract.pathManifestSha256) {
                problems += "${apk.name}: language path manifest $pathManifest is not the reviewed one"
            }
            if (contentManifest !in contract.contentManifestSha256) {
                problems += "${apk.name}: language content manifest $contentManifest is not a reviewed one"
            }
        }
        assertEquals("language inventories that do not describe the fixtures", emptyList<String>(), problems)
    }

    @Test
    fun `each declared host keeps every native language by default and trims only an explicit list`() {
        Fixtures.forEachDeclared { apk ->
            val root = temporary.newFolder("languages-${Fixtures.versionOf(apk)}")
            val original = digests(apk) { it.startsWith(LANGUAGE_PREFIX) }
            assertTrue("native Turkish is missing from ${apk.name}", original.keys.any { it.startsWith("${LANGUAGE_PREFIX}tr/") })
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(LANGUAGE_PREFIX) }
                    .forEach { entry ->
                        val file = root.resolve(entry.name)
                        file.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
            }

            for (selection in listOf(null, "", "all")) {
                assertEquals(0, stripVerifiedLanguagePacks(root, selection, languageInventories).files)
                val retained = root.walkTopDown().filter(File::isFile).associate { file ->
                    file.relativeTo(root).invariantSeparatorsPath to file.inputStream().use(::sha256)
                }
                assertEquals("${apk.name}: $selection changed a native language", original, retained)
            }

            assertTrue(stripVerifiedLanguagePacks(root, "en,tr", languageInventories).files > 0)
            for ((path, digest) in original) {
                val file = root.resolve(path)
                val language = path.removePrefix(LANGUAGE_PREFIX).substringBefore('/')
                if (language == "en" || language == "tr") {
                    assertEquals("${apk.name}: $path changed", digest, file.inputStream().use(::sha256))
                } else {
                    assertEquals("${apk.name}: $path wasn't removed", 0L, file.length())
                }
            }
        }
    }

    /** Every TikTok APK and split bundle in the fixture directory. */
    private fun fixtures(): List<File> = Fixtures.files { it.extension == "apk" || it.extension == "apkm" }

    /**
     * Path to lowercase sha256 for every entry [keep] accepts. For an .apkm, the entries of every
     * split inside it, as a merge would lay them out; a path two splits disagree on fails.
     */
    private fun digests(apk: File, keep: (String) -> Boolean): Map<String, String> {
        val result = mutableMapOf<String, String>()
        fun add(name: String, stream: InputStream) {
            val hash = sha256(stream)
            val previous = result.put(name, hash)
            check(previous == null || previous == hash) { "${apk.name}: two splits carry different $name" }
        }
        ZipFile(apk).use { zip ->
            if (apk.extension != "apkm") {
                zip.entries().asSequence().filter { !it.isDirectory && keep(it.name) }
                    .forEach { entry -> zip.getInputStream(entry).use { add(entry.name, it) } }
                return result
            }
            zip.entries().asSequence().filter { it.name.endsWith(".apk") }.forEach { split ->
                ZipInputStream(zip.getInputStream(split)).use { inner ->
                    while (true) {
                        val entry = inner.nextEntry ?: break
                        if (!entry.isDirectory && keep(entry.name)) add(entry.name, inner)
                    }
                }
            }
        }
        return result
    }

    private fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LANGUAGE_PREFIX = "assets/strings#lang_"
    }
}
