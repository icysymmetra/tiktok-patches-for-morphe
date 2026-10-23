package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedResourceStripperTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `an empty profile passes only the build it names`() {
        val root = temporary.newFolder("no-files")
        val profiles = listOf(
            ResourceProfile("fixture", listOf(ResourceFileContract("lib/arm64-v8a/librelay.so", sha256("relay")))),
            ResourceProfile("fixture 2.0", emptyList(), onlyVersion = "2.0"),
        )
        val strip = { version: String? ->
            stripVerifiedResources(root, "Test resources", emptyList(), listOf("lib/arm64-v8a/librelay.so"), profiles,
                versionName = version)
        }

        assertEquals(0, strip("2.0").files)
        // Another build without the file renamed or moved it, which is not the same as not having it.
        assertThrows(PatchException::class.java) { strip("2.1") }
        assertThrows(PatchException::class.java) { strip(null) }
        assertThrows(IllegalArgumentException::class.java) { ResourceProfile("unpinned", emptyList()) }
    }

    @Test
    fun `the relay strip passes every declared PineDrama build, which ships no relay library`() {
        // The 47.4.3 retarget renamed the only 47.1.4 empty profile and PineDrama 47.1.4 stopped patching.
        val versions = AppCompatibilities.pineDrama().flatMap { it.targets }.map { it.version }
        assertTrue(versions.isNotEmpty())
        for (version in versions) {
            val root = temporary.newFolder("pinedrama-$version")
            val result = stripVerifiedResources(root, "P2P Relay Blocker", emptyList(),
                p2pRelayProfiles.flatMap { it.files }.map { it.path }.distinct(), p2pRelayProfiles, versionName = version)
            assertEquals(version, 0, result.files)
        }
    }

    @Test
    fun `a bad digest stops the whole group before any file is changed`() {
        val root = temporary.newFolder("bad-digest")
        val first = root.write("assets/group/first.bin", "first")
        val second = root.write("assets/group/second.bin", "changed")
        val profile = ResourceProfile(
            "fixture",
            listOf(
                ResourceFileContract("assets/group/first.bin", sha256("first")),
                ResourceFileContract("assets/group/second.bin", sha256("second")),
            ),
        )

        val error = assertThrows(PatchException::class.java) {
            stripVerifiedResources(root, "Test resources", listOf("assets/group"), emptyList(), listOf(profile))
        }

        assertTrue(error.message.orEmpty().contains("Test resources"))
        assertTrue(error.message.orEmpty(), error.message.orEmpty().endsWith(UNREVIEWED_BUILD_HINT))
        assertArrayEquals("first".toByteArray(), first.readBytes())
        assertArrayEquals("changed".toByteArray(), second.readBytes())
    }

    @Test
    fun `missing and extra files are rejected before mutation`() {
        val missingRoot = temporary.newFolder("missing")
        val only = missingRoot.write("assets/group/first.bin", "first")
        val profile = ResourceProfile(
            "fixture",
            listOf(
                ResourceFileContract("assets/group/first.bin", sha256("first")),
                ResourceFileContract("assets/group/second.bin", sha256("second")),
            ),
        )
        assertThrows(PatchException::class.java) {
            stripVerifiedResources(missingRoot, "Test resources", listOf("assets/group"), emptyList(), listOf(profile))
        }
        assertArrayEquals("first".toByteArray(), only.readBytes())

        val extraRoot = temporary.newFolder("extra")
        val first = extraRoot.write("assets/group/first.bin", "first")
        extraRoot.write("assets/group/second.bin", "second")
        extraRoot.write("assets/group/unreviewed.bin", "extra")
        assertThrows(PatchException::class.java) {
            stripVerifiedResources(extraRoot, "Test resources", listOf("assets/group"), emptyList(), listOf(profile))
        }
        assertArrayEquals("first".toByteArray(), first.readBytes())
    }

    @Test
    fun `a verified set is emptied and reapplication is idempotent`() {
        val root = temporary.newFolder("repeat")
        root.write("assets/group/first.bin", "first")
        root.write("lib/arm64-v8a/second.so", "second")
        val profile = ResourceProfile(
            "fixture",
            listOf(
                ResourceFileContract("assets/group/first.bin", sha256("first")),
                ResourceFileContract("lib/arm64-v8a/second.so", sha256("second")),
            ),
        )

        val first = stripVerifiedResources(
            root,
            "Test resources",
            listOf("assets/group"),
            listOf("lib/arm64-v8a/second.so"),
            listOf(profile),
        )
        val second = stripVerifiedResources(
            root,
            "Test resources",
            listOf("assets/group"),
            listOf("lib/arm64-v8a/second.so"),
            listOf(profile),
        )

        assertEquals(2, first.files)
        assertEquals(11L, first.bytes)
        assertFalse(first.alreadyStripped)
        assertTrue(second.alreadyStripped)
        assertTrue(root.resolve("assets/group/first.bin").readBytes().isEmpty())
        assertTrue(root.resolve("lib/arm64-v8a/second.so").readBytes().isEmpty())
    }

    @Test
    fun `standalone files are staged lazily before the complete group is verified`() {
        val root = temporary.newFolder("lazy-standalone")
        val path = "lib/arm64-v8a/lazy.so"
        val staged = root.resolve(path)
        var resolverCalls = 0
        val profile = ResourceProfile(
            "fixture",
            listOf(ResourceFileContract(path, sha256("archive bytes"))),
        )

        val result = stripVerifiedResources(
            root,
            "Test resources",
            emptyList(),
            listOf(path),
            listOf(profile),
            resolveStandaloneFile = { requested ->
                assertEquals(path, requested)
                resolverCalls++
                staged.also {
                    it.parentFile.mkdirs()
                    it.writeText("archive bytes")
                }
            },
        )

        assertEquals(1, resolverCalls)
        assertEquals(1, result.files)
        assertEquals(13L, result.bytes)
        assertTrue(staged.readBytes().isEmpty())
    }

    @Test
    fun `a standalone resolver cannot redirect a reviewed path outside the APK root`() {
        val root = temporary.newFolder("resolver-root")
        val outside = temporary.newFile("outside.so").also { it.writeText("archive bytes") }
        val path = "lib/arm64-v8a/lazy.so"
        val profile = ResourceProfile(
            "fixture",
            listOf(ResourceFileContract(path, sha256("archive bytes"))),
        )

        assertThrows(PatchException::class.java) {
            stripVerifiedResources(
                root,
                "Test resources",
                emptyList(),
                listOf(path),
                listOf(profile),
                resolveStandaloneFile = { outside },
            )
        }
        assertArrayEquals("archive bytes".toByteArray(), outside.readBytes())
    }

    private val bothAbis = ResourceProfile(
        "fixture",
        listOf(
            ResourceFileContract("assets/group/model.bin", sha256("model")),
            ResourceFileContract("lib/arm64-v8a/first.so", sha256("arm64 first")),
            ResourceFileContract("lib/arm64-v8a/second.so", sha256("arm64 second")),
            ResourceFileContract("lib/armeabi-v7a/first.so", sha256("armeabi first")),
            ResourceFileContract("lib/armeabi-v7a/second.so", sha256("armeabi second")),
        ),
    )
    private val bothAbiLibraries = bothAbis.files.map { it.path }.filter { it.startsWith("lib/") }

    @Test
    fun `an APK split down to one ABI is verified against what it kept and emptied`() {
        // #43: a universal APK with the 32-bit libraries taken out matched no reviewed set.
        for ((abi, kept) in listOf("arm64-v8a" to "arm64", "armeabi-v7a" to "armeabi")) {
            val root = temporary.newFolder("only-$abi")
            root.write("assets/group/model.bin", "model")
            root.write("lib/$abi/first.so", "$kept first")
            root.write("lib/$abi/second.so", "$kept second")

            val result = stripVerifiedResources(root, "Test resources", listOf("assets/group"), bothAbiLibraries,
                listOf(bothAbis))

            assertEquals(3, result.files)
            assertFalse(result.alreadyStripped)
            assertTrue(root.resolve("lib/$abi/first.so").readBytes().isEmpty())
            assertTrue(root.resolve("assets/group/model.bin").readBytes().isEmpty())
        }
    }

    @Test
    fun `a one-ABI APK still has its digests checked and cannot drop part of an ABI`() {
        val altered = temporary.newFolder("altered-split")
        altered.write("assets/group/model.bin", "model")
        altered.write("lib/arm64-v8a/first.so", "arm64 first")
        val changed = altered.write("lib/arm64-v8a/second.so", "patched elsewhere")
        assertThrows(PatchException::class.java) {
            stripVerifiedResources(altered, "Test resources", listOf("assets/group"), bothAbiLibraries, listOf(bothAbis))
        }
        assertArrayEquals("patched elsewhere".toByteArray(), changed.readBytes())

        // Half of the other ABI gone is not a split, it's a set nobody reviewed.
        val partial = temporary.newFolder("partial-abi")
        partial.write("assets/group/model.bin", "model")
        val first = partial.write("lib/arm64-v8a/first.so", "arm64 first")
        partial.write("lib/arm64-v8a/second.so", "arm64 second")
        partial.write("lib/armeabi-v7a/first.so", "armeabi first")
        assertThrows(PatchException::class.java) {
            stripVerifiedResources(partial, "Test resources", listOf("assets/group"), bothAbiLibraries, listOf(bothAbis))
        }
        assertArrayEquals("arm64 first".toByteArray(), first.readBytes())

        // Nor does dropping every library leave a set the libraries' absence explains.
        val noLibraries = temporary.newFolder("no-libraries")
        noLibraries.write("assets/group/model.bin", "model")
        assertThrows(PatchException::class.java) {
            stripVerifiedResources(noLibraries, "Test resources", listOf("assets/group"), bothAbiLibraries, listOf(bothAbis))
        }
    }

    @Test
    fun `a partly emptied group is rejected`() {
        val root = temporary.newFolder("partial")
        root.write("assets/group/first.bin", "")
        root.write("assets/group/second.bin", "second")
        val profile = ResourceProfile(
            "fixture",
            listOf(
                ResourceFileContract("assets/group/first.bin", sha256("first")),
                ResourceFileContract("assets/group/second.bin", sha256("second")),
            ),
        )

        assertThrows(PatchException::class.java) {
            stripVerifiedResources(root, "Test resources", listOf("assets/group"), emptyList(), listOf(profile))
        }
        assertArrayEquals("second".toByteArray(), root.resolve("assets/group/second.bin").readBytes())
    }

    @Test
    fun `a feature dex TikTok loads at start-up is never emptied (#125)`() {
        val root = temporary.newFolder("feature-dex")
        val dex = root.write("lib/arm64-v8a/libdex_df_live_cast.so", "dex")
        val library = root.write("lib/arm64-v8a/libother.so", "library")
        val profile = ResourceProfile(
            "fixture",
            listOf(
                ResourceFileContract("lib/arm64-v8a/libdex_df_live_cast.so", sha256("dex")),
                ResourceFileContract("lib/arm64-v8a/libother.so", sha256("library")),
            ),
        )

        val error = assertThrows(PatchException::class.java) {
            stripVerifiedResources(root, "Test resources", emptyList(),
                listOf("lib/arm64-v8a/libdex_df_live_cast.so", "lib/arm64-v8a/libother.so"), listOf(profile))
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("libdex_df_live_cast.so"))
        assertArrayEquals("dex".toByteArray(), dex.readBytes())
        assertArrayEquals("library".toByteArray(), library.readBytes())
        assertTrue(isStartupDex("lib/armeabi-v7a/libdex_df_camera_biz.so"))
        assertFalse(isStartupDex("lib/arm64-v8a/libdexguard.so"))
        assertFalse(isStartupDex("assets/libdex_notes.so"))
    }

    @Test
    fun `blank or all selects the full reviewed language inventory`() {
        val available = setOf("en", "tr", "es")
        listOf(null, "", " , ", "all", "ALL", "en, all").forEach { raw ->
            assertEquals("'$raw' keeps every language", available, parseLanguageSelection(raw, available))
        }
        assertEquals(setOf("en"), parseLanguageSelection("en", available))
        assertEquals(setOf("en", "tr"), parseLanguageSelection("tr", available))
        assertEquals(setOf("en", "tr"), parseLanguageSelection("en, tr", available))
        assertThrows(PatchException::class.java) { parseLanguageSelection("all, xx", available) }
        assertThrows(PatchException::class.java) { parseLanguageSelection("all", setOf("tr")) }
    }

    @Test
    fun `preserve all checks paths and bytes without changing any language`() {
        val root = temporary.newFolder("preserved-languages")
        val english = root.write("assets/strings#lang_en/en.xrsc", "english")
        val turkish = root.write("assets/strings#lang_tr/tr.xrsc", "turkish")
        val contract = languageContract(root, setOf("en", "tr"))
        listOf(null, "", "all").forEach { selection ->
            val result = stripVerifiedLanguagePacks(root, selection, listOf(contract))
            assertEquals(0, result.files)
            assertEquals(0L, result.bytes)
            assertEquals(setOf("en", "tr"), result.retainedLocales)
            assertArrayEquals("english".toByteArray(), english.readBytes())
            assertArrayEquals("turkish".toByteArray(), turkish.readBytes())
        }

        assertThrows(PatchException::class.java) { stripVerifiedLanguagePacks(root, "all, xx", listOf(contract)) }
        turkish.writeText("changed")
        assertThrows(PatchException::class.java) { stripVerifiedLanguagePacks(root, "all", listOf(contract)) }
        assertArrayEquals("english".toByteArray(), english.readBytes())
        turkish.writeText("turkish")
        root.write("assets/strings#lang_tr/extra.xrsc", "extra")
        assertThrows(PatchException::class.java) { stripVerifiedLanguagePacks(root, "all", listOf(contract)) }
        assertArrayEquals("turkish".toByteArray(), turkish.readBytes())
    }

    @Test
    fun `explicit language removal keeps English and cannot be undone without a clean source`() {
        val root = temporary.newFolder("explicit-languages")
        val english = root.write("assets/strings#lang_en/en.xrsc", "english")
        val turkish = root.write("assets/strings#lang_tr/tr.xrsc", "turkish")
        val spanish = root.write("assets/strings#lang_es/es.xrsc", "spanish")
        val contract = languageContract(root, setOf("en", "tr", "es"))

        assertEquals(1, stripVerifiedLanguagePacks(root, "en, tr", listOf(contract)).files)
        assertArrayEquals("english".toByteArray(), english.readBytes())
        assertArrayEquals("turkish".toByteArray(), turkish.readBytes())
        assertTrue(spanish.readBytes().isEmpty())
        assertThrows(PatchException::class.java) { stripVerifiedLanguagePacks(root, "all", listOf(contract)) }

        spanish.writeText("spanish")
        assertEquals(2, stripVerifiedLanguagePacks(root, "en", listOf(contract)).files)
        assertArrayEquals("english".toByteArray(), english.readBytes())
        assertTrue(turkish.readBytes().isEmpty())
        assertTrue(spanish.readBytes().isEmpty())
    }

    @Test
    fun `language selection keeps English and both Android aliases`() {
        val available = setOf("en", "es", "he", "iw", "id", "in")

        assertEquals(setOf("en", "he", "iw"), parseLanguageSelection("he", available))
        assertEquals(setOf("en", "id", "in"), parseLanguageSelection("in", available))
        assertThrows(PatchException::class.java) {
            parseLanguageSelection("xx", available)
        }
    }

    @Test
    fun `a real language TikTok has no pack for is skipped while a typo still refuses`() {
        // #196: "en, sr" stopped the whole run because no TikTok build carries Serbian.
        val available = setOf("en", "es", "tr")
        assertEquals(setOf("en"), parseLanguageSelection("en, sr", available))
        assertEquals("sr alone still removes the rest", setOf("en"), parseLanguageSelection("sr", available))
        assertEquals(setOf("en", "tr"), parseLanguageSelection("SR, tr", available))
        assertEquals(available, parseLanguageSelection("all, sr", available))
        assertEquals(setOf("sr"), languagesNotShipped("en, sr, tr, xx", available))
        val typo = assertThrows(PatchException::class.java) { parseLanguageSelection("sr, xx", available) }
        assertTrue(typo.message.orEmpty(), typo.message.orEmpty().contains("xx isn't a language code"))
        assertTrue(typo.message.orEmpty(), typo.message.orEmpty().contains("This TikTok has: en, es, tr."))
        assertFalse(typo.message.orEmpty(), typo.message.orEmpty().contains("sr"))

        val root = temporary.newFolder("missing-language")
        val english = root.write("assets/strings#lang_en/en.xrsc", "english")
        val spanish = root.write("assets/strings#lang_es/es.xrsc", "spanish")
        val result = stripVerifiedLanguagePacks(root, "en, sr", listOf(languageContract(root, setOf("en", "es"))))
        assertEquals(setOf("sr"), result.missingLocales)
        assertEquals(setOf("en"), result.retainedLocales)
        assertArrayEquals("english".toByteArray(), english.readBytes())
        assertTrue(spanish.readBytes().isEmpty())
    }

    @Test
    fun `language inventory is verified before selected packs are emptied`() {
        val root = temporary.newFolder("languages")
        root.write("assets/strings#lang_en/en.xrsc", "english")
        root.write("assets/strings#lang_es/es.xrsc", "spanish")
        val contract = languageContract(root, setOf("en", "es"))

        val first = stripVerifiedLanguagePacks(root, "en", listOf(contract))
        val second = stripVerifiedLanguagePacks(root, "en", listOf(contract))

        assertEquals(1, first.files)
        assertEquals(7L, first.bytes)
        assertFalse(first.alreadyStripped)
        assertTrue(second.alreadyStripped)
        assertEquals(setOf("en"), first.retainedLocales)
        assertEquals(setOf("en"), second.retainedLocales)
        assertArrayEquals("english".toByteArray(), root.resolve("assets/strings#lang_en/en.xrsc").readBytes())
        assertTrue(root.resolve("assets/strings#lang_es/es.xrsc").readBytes().isEmpty())
    }

    @Test
    fun `an altered language inventory does not empty another pack`() {
        val root = temporary.newFolder("altered-languages")
        root.write("assets/strings#lang_en/en.xrsc", "english")
        root.write("assets/strings#lang_es/es.xrsc", "spanish")
        val contract = languageContract(root, setOf("en", "es"))
        root.resolve("assets/strings#lang_en/en.xrsc").writeText("altered")

        assertThrows(PatchException::class.java) {
            stripVerifiedLanguagePacks(root, "en", listOf(contract))
        }
        assertArrayEquals("spanish".toByteArray(), root.resolve("assets/strings#lang_es/es.xrsc").readBytes())
    }

    @Test
    fun `a bundle merged with only some languages is refused, says why, and empties nothing`() {
        // Issue #9: a split bundle merged on the phone kept 25 of the 64 #lang_ directories.
        val root = temporary.newFolder("partial-languages")
        root.write("assets/strings#lang_en/en.xrsc", "english")
        root.write("assets/strings#lang_es/es.xrsc", "spanish")
        val contract = languageContract(root, setOf("en", "es", "fr"))

        val error = assertThrows(PatchException::class.java) {
            stripVerifiedLanguagePacks(root, "en", listOf(contract))
        }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("found 2 language directories, but the reviewed set has 3."))
        assertTrue(message, message.endsWith(UNREVIEWED_BUILD_HINT))
        assertArrayEquals("spanish".toByteArray(), root.resolve("assets/strings#lang_es/es.xrsc").readBytes())
    }

    @Test
    fun `keeping every language passes an unreviewed bundle untouched instead of failing the run`() {
        // Issue #96: a bundle merged with one language failed the default keep-all choice, and
        // Morphe Manager stops the whole patch run on that.
        val root = temporary.newFolder("single-language")
        val french = root.write("assets/strings#lang_fr/fr.xrsc", "french")
        val contract = languageContract(temporary.newFolder("reviewed").apply {
            write("assets/strings#lang_en/en.xrsc", "english")
            write("assets/strings#lang_fr/fr.xrsc", "french")
        }, setOf("en", "fr"))

        listOf(null, "", " , ", "all", "ALL, all").forEach { selection ->
            val result = stripVerifiedLanguagePacks(root, selection, listOf(contract))
            assertEquals("'$selection' removes nothing", 0, result.files)
            assertNull(result.retainedLocales)
            assertArrayEquals("french".toByteArray(), french.readBytes())
        }
        assertEquals(0, stripVerifiedLanguagePacks(temporary.newFolder("no-languages").apply {
            resolve("assets").mkdirs()
        }, "all", listOf(contract)).files)

        // Asking for a removal still needs a reviewed inventory, and so does an unknown code.
        listOf("en", "fr", "all, xx").forEach { selection ->
            val error = assertThrows(PatchException::class.java) { stripVerifiedLanguagePacks(root, selection, listOf(contract)) }
            assertTrue(error.message, error.message.orEmpty().endsWith(UNREVIEWED_BUILD_HINT))
        }
        french.writeBytes(byteArrayOf())
        assertThrows(PatchException::class.java) { stripVerifiedLanguagePacks(root, "all", listOf(contract)) }
    }

    @Test
    fun `the inventory whose directory set matches is the one that is checked`() {
        // A merged split bundle carries fewer language directories than the universal APK, so a
        // build has one reviewed inventory per shape and the directory set picks between them.
        val full = temporary.newFolder("full-languages")
        full.write("assets/strings#lang_en/en.xrsc", "english")
        full.write("assets/strings#lang_es/es.xrsc", "spanish")
        full.write("assets/strings#lang_fr/fr.xrsc", "french")
        val partial = temporary.newFolder("bundle-languages")
        partial.write("assets/strings#lang_en/en.xrsc", "english")
        partial.write("assets/strings#lang_es/es.xrsc", "spanish")
        val contracts = listOf(
            languageContract(full, setOf("en", "es", "fr")),
            languageContract(partial, setOf("en", "es")),
        )

        assertEquals(2, stripVerifiedLanguagePacks(full, "en", contracts).files)
        assertEquals(1, stripVerifiedLanguagePacks(partial, "en", contracts).files)
        assertTrue(partial.resolve("assets/strings#lang_es/es.xrsc").readBytes().isEmpty())
        assertArrayEquals("english".toByteArray(), partial.resolve("assets/strings#lang_en/en.xrsc").readBytes())

        val other = temporary.newFolder("other-languages")
        other.write("assets/strings#lang_en/en.xrsc", "english")
        val error = assertThrows(PatchException::class.java) {
            stripVerifiedLanguagePacks(other, "en", contracts)
        }
        assertTrue(error.message, error.message.orEmpty().contains("found 1 language directories, but the reviewed sets have 2 or 3."))
    }

    private fun languageContract(root: File, directories: Set<String>): LanguageInventoryContract {
        val files = root.resolve("assets").walkTopDown().filter(File::isFile).toList()
        return LanguageInventoryContract(
            directories,
            sha256(files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .joinToString("") { it.relativeTo(root).invariantSeparatorsPath + "\n" }),
            setOf(manifestDigest(root, files)),
        )
    }

    private fun manifestDigest(root: File, files: List<File>): String = sha256(
        files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.joinToString("") { file ->
            file.relativeTo(root).invariantSeparatorsPath + "\u0000" + sha256(file.readBytes()) + "\n"
        },
    )

    private fun File.write(path: String, text: String): File = resolve(path).also {
        it.parentFile.mkdirs()
        it.writeText(text)
    }

    private fun sha256(text: String): String = sha256(text.toByteArray())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
