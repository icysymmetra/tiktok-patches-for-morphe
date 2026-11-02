/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.update

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildChoice
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildChoicePatch

private const val MAX_VERSION_CODE = Int.MAX_VALUE

@Suppress("unused")
val hidePlayStoreUpdatePatch = resourcePatch(
    name = "Hide Play Store update offer",
    description = "Stops the Play Store offering to swap the patched TikTok for the store " +
        "version. The catch: a build with a lower version number won't install over it, and " +
        "uninstalling to go back can erase TikTok's data on your phone.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(buildChoicePatch(BuildChoice.VERSION_CODE))

    execute {
        document("AndroidManifest.xml").use { xml ->
            val manifest = xml.documentElement
            val attributes = manifest.attributes
            val versionCode = (0 until attributes.length)
                .map { attributes.item(it) }
                .singleOrNull { it.nodeName.substringAfterLast(':') == "versionCode" }
                ?: throw PatchException("TikTok manifest has no version code")
            if (AppCompatibilities.DECLARED_VERSION_CODES.none { it.toString() == versionCode.nodeValue }) {
                throw PatchException("Unexpected TikTok version code ${versionCode.nodeValue}; refusing to change it")
            }
            versionCode.nodeValue = MAX_VERSION_CODE.toString()
        }
        document("AndroidManifest.xml").use { xml ->
            val attributes = xml.documentElement.attributes
            check((0 until attributes.length).map { attributes.item(it) }
                .singleOrNull { it.nodeName.substringAfterLast(':') == "versionCode" }
                ?.nodeValue == MAX_VERSION_CODE.toString()) { "Raised TikTok version code was not saved" }
        }
        BuildDetails.raisedVersionCode(get(BUILD_DETAILS_ASSET))
    }
}
