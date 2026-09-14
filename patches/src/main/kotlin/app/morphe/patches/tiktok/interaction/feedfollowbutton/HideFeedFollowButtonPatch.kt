/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.feedfollowbutton

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val VIEW_GROUP_DESCRIPTOR = "Landroid/view/ViewGroup;"
private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private object FeedFollowVisibilityFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/avatar/FeedAvatarDefaultAssem;",
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.size == 3 &&
            method.parameterTypes[0] == VIEW_GROUP_DESCRIPTOR &&
            method.parameterTypes[1] == "I" &&
            method.implementation?.instructions?.let { instructions ->
                instructions.firstOrNull()?.getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes.size == 3 &&
                        reference.parameterTypes[0] == "Landroid/view/View;" &&
                        reference.parameterTypes[1] == "I" &&
                        reference.parameterTypes[2] == method.parameterTypes[2] &&
                        reference.returnType == "V"
                } == true && instructions.any { instruction ->
                    instruction.getReference<MethodReference>()?.let { reference ->
                        reference.definingClass == "Landroid/view/View;" &&
                            reference.name == "setClickable" &&
                            reference.parameterTypes == listOf("Z") &&
                            reference.returnType == "V"
                    } == true
                }
            } == true
    },
)

@Suppress("unused")
val hideFeedFollowButtonPatch = bytecodePatch(
    name = "Hide feed follow button",
    description = "Adds an option to hide the + follow button below creator avatars in video feeds.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedFollowButton()V",
        )

        FeedFollowVisibilityFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p2}, $FEATURE_CONTROLS_DESCRIPTOR->hideFeedFollowButtonVisibility(I)I
                move-result p2
            """,
        )
    }
}
