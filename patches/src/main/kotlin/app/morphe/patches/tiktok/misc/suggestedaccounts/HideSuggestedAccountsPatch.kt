/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.suggestedaccounts

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private val SUGGESTED_ACCOUNT_INJECTOR_DESCRIPTORS = listOf(
    "Lcom/ss/android/ugc/aweme/inbox/v2/container/UserCardWidgetContainerInjector;",
    "Lcom/ss/android/ugc/aweme/inbox/v2/container/InboxUserCardWidgetContainerInjector;",
    "Lcom/ss/android/ugc/aweme/inbox/v2/container/UserCardWidgetVisibleContainerInjector;",
    "Lcom/ss/android/ugc/aweme/inbox/followerv2/FollowerUserCardWidgetContainerInjector;",
    "Lcom/ss/android/ugc/aweme/inbox/widget/multi/FollowerUserCardLoadingWidgetV2Injector;",
    "Lcom/ss/android/ugc/aweme/relation/recuser/inbox/FollowerUserCardWidgetV2Injector;",
    "Lcom/ss/android/ugc/aweme/notification/v2/widget/container/UserCardWidgetContainerInjector;",
    "Lcom/ss/android/ugc/aweme/relation/recuser/inbox/NotificationRecommendUserWidgetV2Injector;",
)

private fun BytecodePatchContext.resolveSuggestedAccountInjectorGates(): List<MutableMethod> =
    SUGGESTED_ACCOUNT_INJECTOR_DESCRIPTORS.map { descriptor ->
        val matches = mutableClassDefBy(descriptor).methods.filter { method ->
            method.name == "enable" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == "Z"
        }
        if (matches.size != 1) {
            throw PatchException(
                "Hide suggested accounts: expected one enable() gate in $descriptor, " +
                    "found ${matches.size}.",
            )
        }
        matches.single()
    }

private fun MutableMethod.returnEarlyWhenSuggestedAccountsHidden(returnInstruction: String) {
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $FEATURE_CONTROLS_DESCRIPTOR->hideSuggestedAccounts()Z
            move-result v0
            if-eqz v0, :show_suggested_accounts
            $returnInstruction
        """,
        ExternalLabel("show_suggested_accounts", getInstruction(0)),
    )
}
@Suppress("unused")
val hideSuggestedAccountsPatch = bytecodePatch(
    name = "Hide suggested accounts",
    description =
        "Removes suggested-account cards from profile and inbox surfaces. " +
            "Thanks to tymmesyde for the original implementation.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->" +
                "enableHideSuggestedAccounts()V",
        )

        ProfileHeaderRecommendComponentFingerprint.method
            .returnEarlyWhenSuggestedAccountsHidden("return-void")

        resolveSuggestedAccountInjectorGates().forEach { method ->
            method.returnEarlyWhenSuggestedAccountsHidden(
                """
                    const/4 v0, 0x0
                    return v0
                """.trimIndent(),
            )
        }
    }
}
