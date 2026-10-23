/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.feedfollowbutton

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val VIEW_GROUP_DESCRIPTOR = "Landroid/view/ViewGroup;"
private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private object FeedFollowVisibilityFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/avatar/FeedAvatarDefaultAssem;",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Landroid/view/View;",
            name = "setClickable",
            parameters = listOf("Z"),
            returnType = "V",
        ),
    ),
    custom = { method, _ ->
        // An instance method, or p2 below is the third parameter rather than the second: on
        // a static one the visibility sits in p1 and p2 holds an object the int is written over.
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
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
    description = "Removes the + follow button under creators' pictures on the feed, so you don't follow someone by accident. Starts off. Turn it on in Metra settings > Interface.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedFollowButton()V",
        )

        FeedFollowVisibilityFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p2 .. p2 }, $FEATURE_CONTROLS_DESCRIPTOR->hideFeedFollowButtonVisibility(I)I
                move-result p2
            """,
        )
    }
}
