/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.Patcher
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.shared.misc.extension.localBundleFile
import app.morphe.patches.tiktok.misc.optimizer.StripSummary
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarFile

internal const val BUILD_DETAILS_ASSET = "assets/hushfeed-build-v1.txt"

/** A raw dependency also works when no bytecode or runtime extension is selected. */
internal val buildDetailsPatch = rawResourcePatch {
    execute {
        refuseBuildAssetCollision(listApkEntries(BUILD_DETAILS_ASSET))
        val output = get(BUILD_DETAILS_ASSET)
        BuildDetails.begin(output, packageMetadata, bundleManifest(), applyingPatcherVersion())
    }
}

internal fun refuseBuildAssetCollision(entries: List<String>) {
    if (BUILD_DETAILS_ASSET in entries) throw PatchException("Build details asset already exists in the input APK; use an unpatched input")
}

internal enum class BuildChoice(val field: String) {
    AMOLED("amoled"), LANGUAGES("language_packs"), P2P("tool_p2p_relay"),
    CORE("tool_core_assets"), CREATION("tool_creation"), LIVE("tool_live_extras"),
    VERSION_CODE("version_code_override"),
}

/** Runs before every other dependency so a failed dependency cannot look like an unticked patch. */
internal fun buildChoicePatch(choice: BuildChoice) = rawResourcePatch {
    dependsOn(buildDetailsPatch)
    execute { BuildDetails.selected(get(BUILD_DETAILS_ASSET), choice) }
}

/** Run state lives only in the patcher's output asset. There is no global collector to leak on cancellation. */
internal object BuildDetails {
    private val fields = ("schema bundle_version source_commit source_clean source_start source_end target_package " +
        "target_version target_version_code patcher_bundle_compat patcher_applying_engine amoled amoled_color " +
        "language_packs native_locales_retained tool_p2p_relay tool_core_assets tool_creation tool_live_extras " +
        "version_code_override resource_patches").split(' ')
    internal val nativeLocales = ("af ar az bg bn ca ceb cs da de el en es et fa fi fil fr ga gu he hi hr hu " +
        "id in is it iw ja jv kk km kn ko lt lv ml mr ms my nb nl or pa pl pt ro ru sk sl sq sv sw ta te th " +
        "tr uk ur uz vi zh zu").split(' ').toSet()
    private val version = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
    private val commit = Regex("[0-9A-Fa-f]{40}")
    private val hash = Regex("[0-9A-Fa-f]{64}")
    private fun String?.validated(pattern: Regex) = this?.takeIf { it.length <= 80 && pattern.matches(it) } ?: "unknown"

    fun begin(output: File, context: PackageMetadata, manifest: Attributes = Attributes(), applyingVersion: String? = null) {
        check(output.parentFile.isDirectory || output.parentFile.mkdirs()) { "Cannot create build details asset" }
        output.writeText(encode(base(context), manifest, applyingVersion), Charsets.UTF_8)
    }

    private fun base(context: PackageMetadata): Map<String, String> {
        val target = AppCompatibilities.tiktokAndPineDrama().any { compatibility ->
            context.packageName == compatibility.packageName && compatibility.targets.any {
                it.version == context.versionName && it.versionCodes.orEmpty().values.any { code -> code.toString() == context.versionCode }
            }
        }
        return linkedMapOf(
            "schema" to "1", "bundle_version" to "unknown", "source_commit" to "unknown",
            "source_clean" to "unknown", "source_start" to "unknown", "source_end" to "unknown",
            "target_package" to if (target) context.packageName else "unknown",
            "target_version" to if (target) context.versionName else "unknown",
            "target_version_code" to if (target) context.versionCode else "unknown",
            "patcher_bundle_compat" to "unknown", "patcher_applying_engine" to "unknown",
            "amoled" to "not_selected", "amoled_color" to "not_selected",
            "language_packs" to "not_selected", "native_locales_retained" to "unknown",
            "tool_p2p_relay" to "not_selected", "tool_core_assets" to "not_selected",
            "tool_creation" to "not_selected", "tool_live_extras" to "not_selected",
            "version_code_override" to "not_selected", "resource_patches" to "none",
        )
    }

    fun selected(output: File, choice: BuildChoice) = update(output) {
        this[choice.field] = "unverified"
        if (choice == BuildChoice.AMOLED) this["amoled_color"] = "unknown"
    }

    fun amoled(output: File, color: String) {
        require(Regex("#[0-9a-fA-F]{6}|#[fF]{2}[0-9a-fA-F]{6}").matches(color))
        update(output) {
            this["amoled"] = "applied"
            this["amoled_color"] = "#" + color.takeLast(6).uppercase(Locale.ROOT)
        }
    }

    fun stripped(output: File, choice: BuildChoice, result: StripSummary) {
        require(choice in setOf(BuildChoice.P2P, BuildChoice.CORE, BuildChoice.CREATION, BuildChoice.LIVE))
        update(output) { this[choice.field] = stripOutcome(result) }
    }

    fun languages(output: File, result: StripSummary) {
        val retained = checkNotNull(result.retainedLocales)
        require(retained.isNotEmpty() && "en" in retained && nativeLocales.containsAll(retained))
        update(output) {
            this["language_packs"] = if (result.files == 0) "kept_all" else stripOutcome(result)
            this["native_locales_retained"] = retained.sorted().joinToString(",")
        }
    }

    fun raisedVersionCode(output: File) = update(output) { this["version_code_override"] = Int.MAX_VALUE.toString() }

    /**
     * A resource-only patch with no choice field of its own went in. Each calls this as the last
     * statement of execute with its own name, never an option's value, so the list is sorted
     * constants only.
     */
    fun applied(output: File, patch: String) {
        require(resourcePatchName.matches(patch))
        update(output) {
            val names = getValue("resource_patches").split(',').filter { it.isNotEmpty() && it != "none" }.toSortedSet()
            names += patch
            this["resource_patches"] = names.joinToString(",")
        }
    }

    internal val resourcePatchName = Regex("[a-z][A-Za-z0-9]{0,63}")

    private fun stripOutcome(result: StripSummary) = when {
        result.files == 0 -> "absent"
        result.alreadyStripped -> "already_stripped"
        else -> "stripped"
    }

    private fun update(output: File, change: MutableMap<String, String>.() -> Unit) {
        check(output.isFile && output.length() <= 4096) { "Build details run was not initialized" }
        val lines = output.readLines(Charsets.UTF_8)
        check(lines.map { it.substringBefore('=') } == fields) { "Build details record is incomplete" }
        val facts = lines.associateTo(linkedMapOf()) {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
        check(facts["schema"] == "1") { "Build details record is incomplete" }
        facts.change()
        output.writeText(facts.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }, Charsets.UTF_8)
    }

    private fun encode(record: Map<String, String>, manifest: Attributes, applyingVersion: String?): String {
        val facts = record.toMutableMap()
        facts["bundle_version"] = manifest.getValue("Version").validated(version)
        facts["patcher_bundle_compat"] = manifest.getValue("Patcher-Version").validated(version)
        facts["patcher_applying_engine"] = applyingVersion.validated(version)
        facts["source_commit"] = manifest.getValue("Metra-Source-Commit").validated(commit)
        facts["source_start"] = manifest.getValue("Metra-Source-Start").validated(hash)
        facts["source_end"] = manifest.getValue("Metra-Source-End").validated(hash)
        val clean = manifest.getValue("Metra-Source-Clean")
        facts["source_clean"] = when {
            clean == "false" -> "false"
            clean == "true" && facts["source_commit"] != "unknown" && facts["source_start"] != "unknown" &&
                facts["source_start"] == facts["source_end"] -> "true"
            else -> "unknown"
        }
        return fields.joinToString("\n", postfix = "\n") { key -> "$key=${facts.getValue(key)}" }
    }
}

private fun bundleManifest(): Attributes {
    // The same bundle resource location used by the existing release-version injection.
    val owner = object {}::class.java.enclosingClass.name.replace('.', '/') + ".class"
    return bundleManifest(object {}::class.java.classLoader?.getResource(owner))
}

/** The bundle's manifest, or empty when these classes aren't running from a local bundle JAR. */
internal fun bundleManifest(location: URL?): Attributes {
    if (location?.toExternalForm()?.startsWith("jar:file:") != true) return Attributes()
    return JarFile(localBundleFile(location)).use { it.manifest?.mainAttributes ?: Attributes() }
}

private fun applyingPatcherVersion(): String? = Patcher::class.java.getResourceAsStream("version.properties")?.use {
    Properties().apply { load(it) }.getProperty("version")
}
