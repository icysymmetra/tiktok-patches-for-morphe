package app.morphe.patches.tiktok.misc.commentsort

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/commentsort/CommentSortControls;"

@Suppress("unused")
val commentSortControlsPatch = bytecodePatch(
    name = "Comment sort controls",
    description = "Exposes TikTok's native full comment-sort sheet, including its hot, time, media, " +
        "and creator modes, instead of relying on rollout gates.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentSortControls()V",
        )

        CommentSortOptionStyleFingerprint.method.apply {
            val styleSettingStringIndex = indexOfFirstStringInstructionOrThrow("comment_sort_opt_style")
            val styleResultIndex = indexOfFirstInstructionOrThrow(
                styleSettingStringIndex + 1,
                Opcode.MOVE_RESULT,
            )
            val styleRegister = getInstruction<OneRegisterInstruction>(styleResultIndex).registerA

            addInstructions(
                styleResultIndex + 1,
                """
                    invoke-static {v$styleRegister}, $EXTENSION_CLASS_DESCRIPTOR->forceOptionStyle(I)I
                    move-result v$styleRegister
                """,
            )
        }

        CommentSortEligibilityFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldForceSortEligibility()Z
                    move-result v0
                    if-eqz v0, :morphe_stock_comment_sort_eligibility
                    return v0
                """,
                ExternalLabel("morphe_stock_comment_sort_eligibility", getInstruction(0)),
            )
        }
    }
}
