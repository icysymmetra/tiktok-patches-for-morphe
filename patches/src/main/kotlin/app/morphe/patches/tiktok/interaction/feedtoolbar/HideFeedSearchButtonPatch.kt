/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val HOME_PAGE_UI_FRAME_SERVICE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/homepage/api/ui/HomePageUIFrameService;"

private object SearchIconInflaterFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(
            definingClass = HOME_PAGE_UI_FRAME_SERVICE_DESCRIPTOR,
            name = "getInflatedSearchIcon",
            returnType = "Landroid/view/View;",
        ),
    ),
)

private fun BytecodePatchContext.resolveSearchEnabledBoundary(): MutableMethod {
    val controllerSuperclasses = mutableSetOf<String>()
    SearchIconInflaterFingerprint.matchAllOrNull().orEmpty().forEach { match ->
        val classDef = match.originalClassDef
        val hasVisibilityProtocol = classDef.methods.any { method ->
            method.parameterTypes == listOf("I") && method.returnType == "V"
        }
        if (hasVisibilityProtocol) {
            classDef.superclass?.let(controllerSuperclasses::add)
        }
    }

    if (controllerSuperclasses.size != 1) {
        throw PatchException(
            "Hide feed search button: expected one shared search protocol base, " +
                "found ${controllerSuperclasses.size}.",
        )
    }

    val superclass = controllerSuperclasses.single()
    val matches = mutableClassDefBy(superclass).methods.filter { method ->
        method.name == "enabled" && method.parameterTypes.isEmpty() && method.returnType == "Z"
    }
    if (matches.size != 1) {
        throw PatchException(
            "Hide feed search button: expected one search enabled boundary, found ${matches.size}.",
        )
    }
    return matches.single()
}

@Suppress("unused")
val hideFeedSearchButtonPatch = bytecodePatch(
    name = "Hide feed search button",
    description = "Removes the search button from the top right of the feed, for a cleaner " +
        "screen. Starts off. Turn it on in Metra settings > Interface.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedSearchButton()V",
        )
        resolveSearchEnabledBoundary().overrideToolbarButtonEnabled(
            "hideFeedSearchButtonEnabled",
        )
    }
}
