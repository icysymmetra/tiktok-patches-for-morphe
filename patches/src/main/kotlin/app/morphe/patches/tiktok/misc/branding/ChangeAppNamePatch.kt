/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.branding

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildDetailsPatch
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

private const val LABEL = "android:label"

/** Longer than any launcher shows on one line, and long enough for any name a person picks. */
internal const val MAX_APP_NAME = 50

@Suppress("unused")
val changeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Shows a name you choose under the app icon, so the patched TikTok is easy " +
        "to tell apart from another copy. Type the name in this patch's options. Inside the app " +
        "it still says TikTok.",
    default = false,
) {
    category("Interface")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(buildDetailsPatch)
    val appName by stringOption(
        key = "appName",
        default = "Metra",
        title = "App name",
        description = "The name shown under the app icon, up to $MAX_APP_NAME characters.",
        required = true,
    )

    execute {
        // Checked before the manifest is opened, so a refused name changes nothing.
        val name = checkedAppName(appName)
        document("AndroidManifest.xml").use { xml -> renameApp(xml, name) }
        BuildDetails.applied(get(BUILD_DETAILS_ASSET), "changeAppName")
    }
}

/** The name as it will be written, or a refusal that says what to change. */
internal fun checkedAppName(raw: String?): String {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty()) throw PatchException("Change app name: type the name to show in this patch's options.")
    // Android reads a label that starts with either as a resource reference, not as text.
    if (name.startsWith("@") || name.startsWith("?")) {
        throw PatchException("Change app name: the name can't start with @ or ?.")
    }
    if (name.length > MAX_APP_NAME) {
        throw PatchException("Change app name: the name is ${name.length} characters, and the limit is $MAX_APP_NAME.")
    }
    return name
}

/**
 * Writes [name] as the application's label and as the label of every activity or alias the
 * launcher lists. On 47.x TikTok's only launcher entry is an alias with no label of its own, so it
 * shows the application's, but a build that gave an entry its own label would keep showing that
 * one, which is why each entry is labeled too. Returns how many launcher entries it labeled.
 */
internal fun renameApp(manifest: Document, name: String): Int {
    val application = manifest.getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("Change app name: TikTok's manifest has no application element.")
    var launchers = 0
    val entries = manifest.getElementsByTagName("activity").elements() +
        manifest.getElementsByTagName("activity-alias").elements()
    val listed = entries.filter { it.isLauncherEntry() }
    if (listed.isEmpty()) throw PatchException("Change app name: TikTok's manifest has no launcher entry.")
    application.setAttribute(LABEL, name)
    for (entry in listed) {
        entry.setAttribute(LABEL, name)
        launchers++
    }
    return launchers
}

internal fun Element.isLauncherEntry(): Boolean =
    getElementsByTagName("intent-filter").elements().any { filter ->
        "android.intent.action.MAIN" in filter.getElementsByTagName("action").names() &&
            "android.intent.category.LAUNCHER" in filter.getElementsByTagName("category").names()
    }

internal fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

private fun NodeList.names(): Set<String> = elements().mapTo(mutableSetOf()) { it.getAttribute("android:name") }
