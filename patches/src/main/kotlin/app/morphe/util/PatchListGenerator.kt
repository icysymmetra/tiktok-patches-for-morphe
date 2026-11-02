/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe patches project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the Morphe patches
 * LICENSE file: https://github.com/MorpheApp/morphe-patches/blob/main/NOTICE
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * File-Specific Exception to Section 7b:
 * -------------------------------------
 * Section 7b (Attribution Requirement) of the Morphe patches LICENSE
 * does not apply to THIS FILE. Use of this file does NOT require any
 * user-facing, in-application, or UI-visible attribution.
 *
 * For this file only, attribution under Section 7b is satisfied by
 * retaining this comment block in the source code of this file.
 *
 * Distribution and Derivative Works:
 * ----------------------------------
 * This comment block MUST be preserved in all copies, distributions,
 * and derivative works of this file, whether in source or modified
 * form.
 *
 * All other terms of the Morphe Patches LICENSE, including Section 7c
 * (Project Name Restriction) and the GPLv3 itself, remain fully
 * applicable to this file.
 */


/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/util/PatchListGenerator.kt
 */
package app.morphe.util

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.net.URLClassLoader
import java.util.jar.Manifest

typealias PackageName = String
typealias VersionName = String

internal fun main(args: Array<String>) {
    require(args.size == 1) { "Expected the current patch bundle version" }
    val patchFile = File("build/libs/patches-${args.single()}.mpp")
    require(patchFile.isFile) { "Current patch bundle not found: ${patchFile.path}" }
    val patchFiles = setOf(patchFile)
    val loadedPatches = loadPatchesFromJar(patchFiles)
    val patchClassLoader = URLClassLoader(patchFiles.map { it.toURI().toURL() }.toTypedArray())
    val manifest = patchClassLoader.getResources("META-INF/MANIFEST.MF")

    while (manifest.hasMoreElements()) {
        Manifest(manifest.nextElement().openStream())
            .mainAttributes
            .getValue("Version")
            ?.let {
                generatePatchList(it, loadedPatches)
            }
    }
}

/**
 * Emits `version`, root-level `appNames` (package to label), and `patches` with
 * `compatiblePackages`, **`compatibility`** (full `Compatibility` metadata for Manager UI),
 * and options. Requires morphe-patcher 1.3.x and `compatibleWith(Compatibility(...))` in patches.
 */
@Suppress("DEPRECATION")
private fun generatePatchList(version: String, patches: Set<Patch<*>>) {
    val listJson = File("../patches-list.json")

    val appNames = patches
        .asSequence()
        .flatMap { it.compatibility.orEmpty().asSequence() }
        .mapNotNull { c ->
            val pkg = c.packageName ?: return@mapNotNull null
            val label = c.name ?: return@mapNotNull null
            pkg to label
        }
        .toMap()

    val patchesMap = patches.sortedBy { it.name }.map {
        JsonPatch(
            it.name!!,
            it.description,
            it.category,
            it.default,
            // The patch's own name, not its Kotlin class. Every entry used to read
            // "BytecodePatch", which made the only machine-readable dependency record say
            // nothing at all. Internal patches with no name keep the class name.
            //
            // Deduplicated and sorted, because this is a set written down as a list. Two patches
            // reached the same unnamed dependency twice and their rows said "BytecodePatch,
            // BytecodePatch", which reads as two things and is one, and the order everywhere else
            // was whatever the traversal happened to produce, so an unrelated edit could move
            // rows about and make the catalog diff say more than the change did.
            it.dependencies
                .map { dependency -> dependency.name ?: dependency.javaClass.simpleName }
                .distinct()
                .sorted(),
            it.compatibility?.mapNotNull { c -> c.toLegacyEntry() }?.toMap(),
            it.compatibility.orEmpty().map { c -> c.toJsonCompatibility() },
            it.options.values.map { option ->
                JsonPatch.Option(
                    option.key,
                    option.title,
                    option.description,
                    option.required,
                    option.type.toString(),
                    option.default,
                    option.values,
                )
            },
        )
    }

    val gsonBuilder = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    val jsonObject = JsonObject()
    jsonObject.addProperty("version", "v$version")
    jsonObject.add("appNames", gsonBuilder.toJsonTree(appNames))
    jsonObject.add("patches", gsonBuilder.toJsonTree(patchesMap))

    listJson.writeText(
        gsonBuilder.toJson(jsonObject)
    )
}

/** Preserve the legacy catalog field while using Patcher 2's public compatibility API. */
private fun Compatibility.toLegacyEntry(): Pair<PackageName, Set<VersionName>?>? {
    val packageName = packageName ?: return null
    val included = targets.filterNot { it.isExperimental }.ifEmpty { targets }
    val versions = included.mapNotNull { it.version }.toSet()
        .takeUnless { it.isEmpty() || included.any { target -> target.version == null } }
    return packageName to versions
}

private fun Compatibility.toJsonCompatibility() = JsonCompatibility(
    name = name ?: "",
    packageName = packageName ?: "",
    description = description?.takeUnless { it.isBlank() },
    appIconColor = appIconColor,
    // The certificates Morphe Manager holds a picked APK to. The patches template's generator
    // writes these and the per-target version codes; this one used to drop both, so the catalog
    // said nothing about what Manager actually checks.
    signatures = signatures?.sorted(),
    targets = targets?.map { t -> t.toJsonAppTarget() },
)

private fun AppTarget.toJsonAppTarget() = JsonAppTarget(
    version = version ?: "",
    experimental = isExperimental,
    versionCodes = versionCodes?.entries?.sortedBy { it.key.name }?.associate { it.key.name to it.value },
)

@Suppress("unused")
private class JsonCompatibility(
    val name: String,
    val packageName: String,
    val description: String? = null,
    val appIconColor: Int?,
    val signatures: List<String>?,
    val targets: List<JsonAppTarget>?,
)

@Suppress("unused")
private class JsonAppTarget(
    val version: String,
    val experimental: Boolean = false,
    val versionCodes: Map<String, Int>? = null,
)

@Suppress("unused")
private class JsonPatch(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val use: Boolean = true,
    val dependencies: List<String>,
    val compatiblePackages: Map<PackageName, Set<VersionName>?>? = null,
    val compatibility: List<JsonCompatibility>,
    val options: List<Option>,
) {
    class Option(
        val key: String,
        val title: String?,
        val description: String?,
        val required: Boolean,
        val type: String,
        val default: Any?,
        val values: Map<String, Any?>?,
    )
}

