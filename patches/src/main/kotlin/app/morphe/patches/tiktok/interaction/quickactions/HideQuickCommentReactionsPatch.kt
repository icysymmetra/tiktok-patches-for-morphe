/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

@Suppress("unused")
val hideQuickCommentReactionsPatch = bytecodePatch(
    name = "Hide comment typing suggestions",
    description = "Hides the emoji and sticker suggestions that pop up above the comment box " +
        "while you type. The emoji and sticker buttons still work. Starts off. Turn it on in " +
        "Metra settings > Comments and translation.",
    default = true,
) {
    category("Comments")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideCommentQuickReactions()V",
        )

        val row = QuickCommentBindFingerprint.originalClassDef.fields.singleOrNull {
            it.type == QUICK_COMMENT_ROW
        } ?: throw PatchException(
            "Hide comment typing suggestions: $QUICK_COMMENT_ASSEM does not hold exactly one " +
                "LinearLayout reaction row.",
        )
        QuickCommentBindFingerprint.method.hookQuickCommentVisibility(row)

        ExposedEmojiPanelTriggerFingerprint.method.guardAtEntry(
            "Hide comment typing suggestions",
            "invoke-static {}, $FEATURE_CONTROLS_DESCRIPTOR->hideQuickCommentReactions()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // TikTok 47.0.3's four-second sticker suggestion row is owned by the real-named
        // TypingStickerRecommendAssem, separate from the emoji row above. Its lone void(boolean)
        // method is the show/hide boundary.
        TypingStickerRecommendVisibilityFingerprint.method
            .forceCommentTypingStickerSuggestionsHidden()
    }
}
