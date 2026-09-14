/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.feedbookmark

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val VIDEO_FAVORITE_ASSEM_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/feed/favorite/VideoFavoriteAssem;"
private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private fun MethodReference.isFavoriteRootVisibilityCall() =
    parameterTypes == listOf("I", "Landroid/widget/LinearLayout;") && returnType == "V"

private object FeedFavoriteStateFingerprint : Fingerprint(
    definingClass = VIDEO_FAVORITE_ASSEM_DESCRIPTOR,
    returnType = "V",
    strings = listOf("VideoFavoriteAssem showFavoriteState "),
    custom = { method, _ ->
        method.parameterTypes.size == 1 &&
            method.implementation?.instructions?.count { instruction ->
                instruction.opcode == Opcode.INVOKE_STATIC &&
                    instruction.getReference<MethodReference>()?.isFavoriteRootVisibilityCall() == true
            } == 1
    },
)

@Suppress("unused")
val hideFeedSaveButtonPatch = bytecodePatch(
    name = "Hide feed save button",
    description = "Adds an option to hide the save/favourites button from video feeds.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedSaveButton()V",
        )

        val visibilityCallIndex = FeedFavoriteStateFingerprint.method.implementation!!.instructions
            .indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_STATIC &&
                    instruction.getReference<MethodReference>()?.isFavoriteRootVisibilityCall() == true
            }
        val visibilityRegister = FeedFavoriteStateFingerprint.method
            .getInstruction<FiveRegisterInstruction>(visibilityCallIndex)
            .registerC

        FeedFavoriteStateFingerprint.method.addInstructions(
            visibilityCallIndex,
            """
                invoke-static {v$visibilityRegister}, $FEATURE_CONTROLS_DESCRIPTOR->hideFeedSaveButtonVisibility(I)I
                move-result v$visibilityRegister
            """,
        )
    }
}
