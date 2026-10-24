/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/login/disablerequirement/DisableLoginRequirementPatch.kt
 */
package app.morphe.patches.tiktok.misc.login.disablerequirement

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.returnEarly

@Suppress("unused")
val disableLoginRequirementPatch = bytecodePatch(
    name = "Disable login requirement",
    description = "Stops TikTok from forcing you to sign in before you can keep browsing. " +
        "Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Settings")
    // Only so Build details can list it: the patch itself runs no extension code.
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        listOf(
            MandatoryLoginServiceFingerprint,
            MandatoryLoginService2Fingerprint,
        ).forEach { fp ->
            fp.method.returnEarly(false)
        }
        registerForBuildDetails("enableNoLoginRequirement")
    }
}

