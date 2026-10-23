/*
 * Resource-removal behavior adapted from kveld9/kveld-morphe-patches at
 * fcb1768620b8f98a6dd31e801074589ce9a63356 (GPL-3.0).
 * https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.patch.PatchException
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class ResourceFileContract(val path: String, val sha256: String)

/**
 * A reviewed set of files. A profile with [onlyVersion] describes that TikTok build alone. An empty
 * profile needs one: without it, any build that renamed or dropped the files would match it and the
 * strip would pass with nothing done.
 */
internal data class ResourceProfile(
    val label: String,
    val files: List<ResourceFileContract>,
    val onlyVersion: String? = null,
) {
    init {
        require(files.isNotEmpty() || onlyVersion != null) { "$label: an empty profile has to name the build it describes" }
    }

    fun describes(versionName: String?) = onlyVersion == null || onlyVersion == versionName

    /**
     * This profile, then, when it ships native libraries for more than one ABI, the same files
     * with only one ABI's libraries kept: what a universal APK split by ABI carries (#43). The
     * files kept still have to match their reviewed digests; only the absence of a whole ABI is
     * forgiven, never part of one.
     */
    fun withAbiSplits(): List<ResourceProfile> {
        val abis = files.mapNotNull { abiOf(it.path) }.distinct()
        if (abis.size < 2) return listOf(this)
        return listOf(this) + abis.map { kept ->
            ResourceProfile("$label, $kept only", files.filter { abiOf(it.path).let { abi -> abi == null || abi == kept } }, onlyVersion)
        }
    }

    private companion object {
        private val NATIVE_LIBRARY = Regex("^lib/([^/]+)/")

        fun abiOf(path: String) = NATIVE_LIBRARY.find(path)?.groupValues?.get(1)
    }
}

/** A language result without [retainedLocales] kept an unreviewed inventory untouched. */
internal data class StripSummary(
    val files: Int,
    val bytes: Long,
    val alreadyStripped: Boolean,
    val retainedLocales: Set<String>? = null,
    val missingLocales: Set<String> = emptySet(),
)

internal data class LanguageInventoryContract(
    val directories: Set<String>,
    val pathManifestSha256: String,
    val contentManifestSha256: Set<String>,
)

/**
 * Empty a reviewed group of resources only after its complete path and digest profile matches.
 *
 * Kveld's original patches changed files as they found them. A renamed, missing, or partially
 * modified group could therefore produce an APK carrying only part of the intended change. This
 * validates the entire group first. A completely empty exact group is accepted so reapplying the
 * Hushfeed patch remains idempotent.
 */
internal fun stripVerifiedResources(
    root: File,
    patchName: String,
    scanDirectories: List<String>,
    standaloneFiles: List<String>,
    profiles: List<ResourceProfile>,
    resolveStandaloneFile: ((String) -> File)? = null,
    versionName: String? = null,
): StripSummary {
    if (profiles.isEmpty()) throw PatchException("$patchName: no reviewed resource profiles were supplied.")
    (standaloneFiles + profiles.flatMap { profile -> profile.files.map(ResourceFileContract::path) })
        .firstOrNull(::isStartupDex)?.let { path ->
            throw PatchException("$patchName: $path is a feature dex TikTok loads at start-up, so it can't be emptied.")
        }

    val actual = linkedMapOf<String, File>()
    scanDirectories.forEach { path ->
        val directory = root.resolveChecked(path, patchName)
        if (!directory.exists()) return@forEach
        if (!directory.isDirectory) {
            throw PatchException("$patchName: $path is present but is not a directory.")
        }
        directory.walkTopDown().filter(File::isFile).forEach { file ->
            actual[file.relativePathFrom(root, patchName)] = file
        }
    }
    standaloneFiles.forEach { path ->
        val expectedFile = root.resolveChecked(path, patchName)
        val file = resolveStandaloneFile?.invoke(path) ?: expectedFile
        if (file.canonicalFile != expectedFile.canonicalFile) {
            throw PatchException("$patchName: the resource resolver redirected $path outside its reviewed location.")
        }
        if (!file.exists()) return@forEach
        if (!file.isFile) throw PatchException("$patchName: $path is present but is not a file.")
        actual[file.relativePathFrom(root, patchName)] = file
    }

    val matchingPathProfiles = profiles.flatMap(ResourceProfile::withAbiSplits).filter { profile ->
        profile.describes(versionName) && profile.files.map(ResourceFileContract::path).toSet() == actual.keys
    }
    if (matchingPathProfiles.isEmpty()) {
        val expectedCounts = profiles.map { it.files.size }.distinct().sorted().joinToString()
        throw PatchException(
            "$patchName: found ${actual.size} target files, but no reviewed path set matched " +
                "(expected file counts: $expectedCounts).$UNREVIEWED_BUILD_HINT",
        )
    }

    val emptyFiles = actual.values.count { it.length() == 0L }
    if (emptyFiles == actual.size) {
        return StripSummary(actual.size, 0L, alreadyStripped = true)
    }
    if (emptyFiles != 0) {
        throw PatchException(
            "$patchName: found a partially stripped resource group ($emptyFiles of ${actual.size} files empty).",
        )
    }

    val digests = actual.mapValues { (_, file) -> file.sha256() }
    val matchingContentProfiles = matchingPathProfiles.filter { profile ->
        profile.files.all { contract -> digests[contract.path] == contract.sha256 }
    }
    if (matchingContentProfiles.isEmpty()) {
        throw PatchException("$patchName: target resources do not match a reviewed TikTok build.$UNREVIEWED_BUILD_HINT")
    }

    val originalBytes = actual.values.sumOf(File::length)
    actual.values.forEach { file -> file.writeBytes(byteArrayOf()) }
    return StripSummary(actual.size, originalBytes, alreadyStripped = false)
}

/**
 * Whether [path] is one of TikTok's feature dexes (lib/<abi>/libdex_*.so, a zip holding a
 * classes.dex). TikTok's native-library dex installer injects every one into its class loader at
 * start-up, and an empty one leaves part of the start undone: with Pitaya's, live cast's or the
 * camera's emptied, TikTok's own strings came up in English on a Spanish phone (emulator,
 * 2026-10-16, #125).
 */
internal fun isStartupDex(path: String) =
    path.startsWith("lib/") && path.substringAfterLast('/').startsWith("libdex_") && path.endsWith(".so")

internal fun parseLanguageSelection(raw: String?, available: Set<String>): Set<String> {
    if ("en" !in available) throw PatchException("Language Pack Purger: the English fallback pack is missing.")
    val selected = requestedLanguages(raw).toMutableSet()
    // A real language this APK has no pack for (Serbian on every build, #196) has nothing to keep,
    // and refusing would end the whole Manager run. Only a code that is no language at all is
    // treated as a typo.
    val missing = languagesNotShipped(raw, available)
    selected.removeAll(missing)
    val unknown = selected - available - "all"
    if (unknown.isNotEmpty()) {
        throw PatchException(
            "Language Pack Purger: ${unknown.sorted().joinToString()} isn't a language code. " +
                "This TikTok has: ${available.sorted().joinToString()}.",
        )
    }
    if (missing.isNotEmpty() && selected.isEmpty()) selected.add("en")

    // Validate even a mixed "all, ..." list before keeping the full reviewed inventory.
    if (selected.isEmpty() || "all" in selected) return available.toSet()
    selected.add("en")

    // TikTok carries both the modern and legacy Android codes for these two languages.
    if ("he" in selected || "iw" in selected) selected.addAll(setOf("he", "iw").filter { it in available })
    if ("id" in selected || "in" in selected) selected.addAll(setOf("id", "in").filter { it in available })
    return selected
}

/** The ISO 639-1 languages in [raw] that [available] has no pack for, so there's nothing to keep. */
internal fun languagesNotShipped(raw: String?, available: Set<String>): Set<String> =
    requestedLanguages(raw).filter { it !in available && it in isoLanguages }.toSet()

private fun requestedLanguages(raw: String?) =
    raw.orEmpty().split(',').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty)

private val isoLanguages = Locale.getISOLanguages().toSet()

/**
 * Verify the complete language inventory before emptying every unselected pack.
 *
 * <p>Each reviewed build has its own inventory, told apart by its directory set: the universal
 * APKs carry 64, a merged split bundle only the languages it shipped splits for.
 */
internal fun stripVerifiedLanguagePacks(
    root: File,
    targetLocales: String?,
    contracts: List<LanguageInventoryContract>,
): StripSummary {
    val patchName = "Language Pack Purger"
    val assets = root.resolveChecked("assets", patchName)
    if (!assets.isDirectory) throw PatchException("$patchName: TikTok's assets directory is missing.")

    val languageDirectories = assets.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith(LANGUAGE_DIRECTORY_PREFIX) }
    val byCode = languageDirectories.associateBy { it.name.removePrefix(LANGUAGE_DIRECTORY_PREFIX).lowercase(Locale.ROOT) }
    val contract = contracts.firstOrNull { it.directories == byCode.keys }
    if (contract == null) {
        // Keeping every language removes nothing, so a set nobody reviewed is no reason to refuse:
        // Morphe Manager abandons the whole run on any patch's exception (#96, a bundle merged
        // with a single language). Without a reviewed inventory there's no retained set to report.
        if (keepsEveryLanguage(targetLocales)) {
            if (languageDirectories.any { directory -> directory.walkTopDown().any { it.isFile && it.length() == 0L } }) {
                throw PatchException("$patchName: an unselected source has already emptied a language that must be retained.")
            }
            return StripSummary(0, 0L, alreadyStripped = false)
        }
        val counts = contracts.map { it.directories.size }.distinct().sorted().joinToString(" or ")
        val reviewed = if (contracts.size == 1) "the reviewed set has" else "the reviewed sets have"
        throw PatchException(
            "$patchName: found ${byCode.size} language directories, but $reviewed " +
                "$counts.$UNREVIEWED_BUILD_HINT",
        )
    }

    val allFiles = languageDirectories.flatMap { directory ->
        directory.walkTopDown().filter(File::isFile).toList()
    }
    val pathManifest = allFiles.sortedPaths(root, patchName)
        .joinToString(separator = "", postfix = "") { path -> "$path\n" }
        .sha256()
    if (pathManifest != contract.pathManifestSha256) {
        throw PatchException("$patchName: language file paths do not match the reviewed inventory.$UNREVIEWED_BUILD_HINT")
    }

    val selected = parseLanguageSelection(targetLocales, contract.directories)
    val missing = languagesNotShipped(targetLocales, contract.directories)
    val targets = byCode.filterKeys { it !in selected }.values.flatMap { directory ->
        directory.walkTopDown().filter(File::isFile).toList()
    }
    val kept = byCode.filterKeys { it in selected }.values.flatMap { directory ->
        directory.walkTopDown().filter(File::isFile).toList()
    }

    if (targets.isNotEmpty() && targets.all { it.length() == 0L }) {
        if (kept.any { it.length() == 0L }) {
            throw PatchException("$patchName: a requested retained language has already been emptied.")
        }
        return StripSummary(targets.size, 0L, alreadyStripped = true, retainedLocales = selected.toSet(), missingLocales = missing)
    }
    if (targets.any { it.length() == 0L }) {
        throw PatchException("$patchName: the selected language resource set is only partly stripped.")
    }
    if (allFiles.any { it.length() == 0L }) {
        throw PatchException("$patchName: an unselected source has already emptied a language that must be retained.")
    }

    val contentManifest = allFiles.sortedBy { it.relativePathFrom(root, patchName) }
        .joinToString(separator = "", postfix = "") { file ->
            file.relativePathFrom(root, patchName) + "\u0000" + file.sha256() + "\n"
        }
        .sha256()
    if (contentManifest !in contract.contentManifestSha256) {
        throw PatchException("$patchName: language resources do not match a reviewed TikTok build.$UNREVIEWED_BUILD_HINT")
    }

    val originalBytes = targets.sumOf(File::length)
    targets.forEach { it.writeBytes(byteArrayOf()) }
    return StripSummary(targets.size, originalBytes, alreadyStripped = false, retainedLocales = selected.toSet(), missingLocales = missing)
}

private const val LANGUAGE_DIRECTORY_PREFIX = "strings#lang_"

private fun keepsEveryLanguage(raw: String?) = requestedLanguages(raw).all { it == "all" }

/**
 * What a person patching needs after any "not a build we know" refusal, which Manager shows as
 * the patch's error. The refusal itself reads like a fault, and on its own it doesn't say that
 * nothing was touched or what to do next. The `#lang_` directories are App Bundle language
 * targeting, so a split bundle merged on the phone keeps only some of them (issue #9: 25 of 64).
 */
internal const val UNREVIEWED_BUILD_HINT =
    " Nothing was removed. This patch only empties files it has checked byte for byte against a " +
        "known TikTok APK, and this one carries a different set. A split bundle (an .apkm file) " +
        "can do this. Untick the patch, or patch the full APK from APKMirror instead."

private fun File.resolveChecked(relativePath: String, patchName: String): File {
    val normalized = relativePath.replace('\\', '/')
    if (normalized.isBlank() || normalized.startsWith('/') ||
        normalized.split('/').any { it.isBlank() || it == "." || it == ".." } ||
        Regex("^[A-Za-z]:").containsMatchIn(normalized)
    ) {
        throw PatchException("$patchName: invalid resource path: $relativePath")
    }
    val base = canonicalFile.toPath()
    val resolved = resolve(normalized).canonicalFile.toPath()
    if (!resolved.startsWith(base)) throw PatchException("$patchName: resource path leaves the APK root: $relativePath")
    return resolved.toFile()
}

private fun File.relativePathFrom(root: File, patchName: String): String {
    val base = root.canonicalFile.toPath()
    val path = canonicalFile.toPath()
    if (!path.startsWith(base)) throw PatchException("$patchName: a resource resolved outside the APK root: $this")
    return base.relativize(path).toString().replace('\\', '/')
}

private fun List<File>.sortedPaths(root: File, patchName: String): List<String> =
    map { it.relativePathFrom(root, patchName) }.sorted()

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
