/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

@Suppress("unused")
val hideQuickCommentReactionsPatch = bytecodePatch(
    name = "Hide quick comment reactions",
    description = "Hides TikTok's exposed quick emoji row in supported comment inputs.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideCommentQuickReactions()V",
        )

        // The experiment gate every exposed-emoji surface consults ("hide" polarity).
        HideExposedEmojiExperimentFingerprint.method.apply {
            implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN }
                .map { it.index }
                .asReversed()
                .forEach { returnIndex ->
                    val resultRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                    addInstructions(
                        returnIndex,
                        """
                            invoke-static {v$resultRegister}, $FEATURE_CONTROLS_DESCRIPTOR->overrideHideExposedEmojiGate(Z)Z
                            move-result v$resultRegister
                        """,
                    )
                }
        }

        // The mini panel can still be shown by the keyboard view model; block the show call itself.
        HorizontalEmojiPanelShowFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $FEATURE_CONTROLS_DESCRIPTOR->hideQuickCommentEmoji()Z
                move-result v0
                if-eqz v0, :show_panel
                return-void
                :show_panel
                nop
            """,
        )

        // The detail page input binds its personalized quick-emoji row separately.
        DetailBottomEmojiBindFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $FEATURE_CONTROLS_DESCRIPTOR->hideQuickCommentEmoji()Z
                move-result v0
                if-eqz v0, :bind_emoji
                return-void
                :bind_emoji
                nop
            """,
        )

        ExposedEmojiRowAllowedFingerprint.method.apply {
            implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN }
                .map { it.index }
                .asReversed()
                .forEach { returnIndex ->
                    val resultRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                    addInstructions(
                        returnIndex,
                        """
                            invoke-static {v$resultRegister}, $FEATURE_CONTROLS_DESCRIPTOR->overrideShowQuickCommentEmoji(Z)Z
                            move-result v$resultRegister
                        """,
                    )
                }
        }
    }
}
