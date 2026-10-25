/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.sensitivewarnings

import app.morphe.util.addInstruction
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.VideoAuthorInfoParamsFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.registerOfParameter
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.guardAtEntry

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/feed/SensitiveWarnings;"
private const val VIDEO_ITEM_PARAMS_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"

/**
 * The getter every reader of a video's risk model goes through: the Check sources banner
 * (TnsBannerAssemTrigger), the warning label, the bottom notice, the share panel's warning flag
 * and the share request's server extra. The field itself is only read here and where a video is
 * copied or merged, which carry it over as it is. Named on every build.
 */
internal object AwemeRiskModelFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
    name = "getAwemeRiskModel",
    returnType = "Lcom/ss/android/ugc/aweme/feed/model/AwemeRiskModel;",
    parameters = emptyList(),
)

/**
 * Hooks the item bind rather than the overlay itself: the masks live on the Aweme, and
 * clearing them there happens before anything has read the model to build the interstitial.
 * This is the same anchor the block button uses for its tracking, so the injection is a
 * second prepend on that method.
 */
@Suppress("unused")
val hideSensitiveWarningsPatch = bytecodePatch(
    name = "Skip content warnings",
    description = "Plays videos without the warning screen you'd have to tap through first, " +
        "and can hide the Check sources and Check your sources banners. Starts off. Turn it on in " +
        "Metra settings > Interface.",
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSensitiveWarnings()V",
        )

        val method = VideoAuthorInfoParamsFingerprint.method
        val paramsRegister = method.registerOfParameter(VIDEO_ITEM_PARAMS_DESCRIPTOR)
            ?: throw PatchException(
                "Skip content warnings: ${method.definingClass}->${method.name} no longer has a " +
                    "$VIDEO_ITEM_PARAMS_DESCRIPTOR parameter to clear.",
            )

        // The unverified notices switch matters less than the warnings themselves: on a declared
        // build AwemeRiskModelAnchorsTest holds the getter, elsewhere a miss leaves that switch out.
        val riskModel = AwemeRiskModelFingerprint.methodOrNull
        if (riskModel == null && packageMetadata.versionName in declaredVersions()) {
            throw PatchException("Skip content warnings: Aweme's getAwemeRiskModel() wasn't found.")
        }
        if (riskModel != null) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableUnverifiedNotices()V",
            )
        } else {
            println("[Skip content warnings] Left out the unverified notices switch on ${packageMetadata.versionName}: no getAwemeRiskModel().")
        }

        // Check your sources is a model of its own (KtfInfo), so it gets its own switch. The banner
        // gate and the share prompt are both needed: one without the other would leave a switch
        // that hides half of what it says. KnowTheFactsAnchorsTest holds both on declared builds.
        val ktfGate = KnowTheFactsBannerGateFingerprint.methodOrNull
        val ktfPrompt = KnowTheFactsSharePromptFingerprint.methodOrNull
        if ((ktfGate == null || ktfPrompt == null) && packageMetadata.versionName in declaredVersions()) {
            throw PatchException(
                "Skip content warnings: the Know the facts " +
                    (if (ktfGate == null) "banner gate" else "share prompt") + " wasn't found.",
            )
        }
        if (ktfGate != null && ktfPrompt != null) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCheckYourSources()V",
            )
            ktfGate.hideKnowTheFacts("Skip content warnings")
            ktfPrompt.hideKnowTheFacts("Skip content warnings")
        } else {
            println("[Skip content warnings] Left out the Check your sources switch on ${packageMetadata.versionName}: no Know the facts gate or share prompt.")
        }

        // /range: a parameter register on a method this size sits well above v15.
        method.addInstruction(
            0,
            "invoke-static/range { $paramsRegister .. $paramsRegister }, " +
                "$EXTENSION_CLASS_DESCRIPTOR->clear(Ljava/lang/Object;)V",
        )

        // No risk model is what most videos have, and every reader checks for one.
        riskModel?.guardAtEntry(
            "Skip content warnings",
            "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideUnverifiedNotices()Z",
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }
}
