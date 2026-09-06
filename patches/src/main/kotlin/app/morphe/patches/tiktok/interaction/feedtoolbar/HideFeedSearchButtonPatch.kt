/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val HOME_PAGE_UI_FRAME_SERVICE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/homepage/api/ui/HomePageUIFrameService;"

private fun BytecodePatchContext.resolveSearchEnabledBoundary(): MutableMethod {
    val controllerSuperclasses = mutableSetOf<String>()
    classDefForEach { classDef ->
        val inflatesSearchIcon = classDef.methods.any { method ->
            method.parameterTypes == listOf("Landroid/content/Context;") &&
                method.returnType == "Landroid/view/View;" &&
                method.implementation?.instructions?.any { instruction ->
                    instruction.getReference<MethodReference>()?.let { reference ->
                        reference.definingClass == HOME_PAGE_UI_FRAME_SERVICE_DESCRIPTOR &&
                            reference.name == "getInflatedSearchIcon" &&
                            reference.returnType == "Landroid/view/View;"
                    } == true
                } == true
        }
        val hasVisibilityProtocol = classDef.methods.any { method ->
            method.parameterTypes == listOf("I") && method.returnType == "V"
        }
        if (inflatesSearchIcon && hasVisibilityProtocol) {
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
    description = "Adds an option to hide the search button at the top right of video feeds.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())

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
