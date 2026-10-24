/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/e1fb74c7
 */
package app.morphe.patches.tiktok.misc.commentsort

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.util.indexOfLiteralCallResult

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/commentsort/CommentSortControls;"

@Suppress("unused")
val commentSortControlsPatch = bytecodePatch(
    name = "Comment sort controls",
    description = "Shows TikTok's full comment sort menu on every video, with hot, newest, " +
        "media and creator options, even when your account only got a cut-down version. Starts " +
        "off. Turn it on in Metra settings > Comments and translation.",
) {
    category("Comments")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentSortControls()V",
        )

        // The style the rollout hands out decides whether the full sheet or the cut-down row is
        // built, so the value is read on its way out of the settings lookup. The insert lands
        // inside the method's try block, so anything thrown out of the extension is swallowed by
        // the host's own handler and the stock style is used: it fails to the unpatched
        // behaviour rather than taking the comment sheet down, but it fails silently.
        val style = CommentSortOptionStyleFingerprint.method
        val styleInstructions = style.implementation!!.instructions.toList()
        val stringIndex = styleInstructions.indexOfFirst {
            it.opcode == Opcode.CONST_STRING &&
                it.getReference<StringReference>()?.string == "comment_sort_opt_style"
        }
        check(stringIndex >= 0) {
            "comment_sort_opt_style is not in the method the fingerprint matched"
        }
        // The answer of the call the key is handed to, followed through the key's register rather
        // than the first move-result after the string, which any call in between would own.
        val resultIndex = style.indexOfLiteralCallResult(stringIndex)
        val styleRegister = (styleInstructions[resultIndex] as OneRegisterInstruction).registerA
        style.addInstructions(
            resultIndex + 1,
            """
                invoke-static/range {v$styleRegister .. v$styleRegister}, $EXTENSION_CLASS_DESCRIPTOR->forceOptionStyle(I)I
                move-result v$styleRegister
            """,
        )

        // A post also has to pass an eligibility check of its own, so the style alone is not
        // enough. Answering true early leaves the original body in place for the switch-off case.
        resolveCommentSortEligibility().apply {
            // v0 is written and then, with the switch off, the original body runs on. It has to
            // be a local: on a frame with none, v0 is the first parameter the body still reads.
            requireLocals("Comment sort controls", 1)
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
