/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.feedbookmark

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.getReference
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
    strings = listOf("VideoFavoriteAssem showFavoriteState ").map { string(it, StringComparisonType.CONTAINS) },
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
    description = "Removes the save button from the right side of the feed, for a cleaner " +
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
