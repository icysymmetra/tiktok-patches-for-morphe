/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val QUICK_COMMENT_ASSEM =
    "Lcom/ss/android/ugc/aweme/feed/assem/quickcomment/VideoQuickCommentAssem;"
internal const val QUICK_COMMENT_ROW = "Landroid/widget/LinearLayout;"
internal const val EXPOSED_EMOJI_PANEL_TRIGGER =
    "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/ExposedEmojiPanelTrigger;"
internal const val COMMENT_CONTEXT_SOURCE =
    "Lcom/ss/android/ugc/aweme/comment/model/CommentContextSource;"

private val VISIBILITY_PARAMETERS = listOf("I", "Landroid/view/View;")
private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

internal data class QuickCommentVisibilityWrite(
    val invokeIndex: Int,
    val visibilityRegister: Int,
)

/**
 * Finds writes to the exposed-reaction row's visibility without naming TikTok's view utility.
 *
 * TikTok 46.2.3 and 46.7.3 decided this row through the
 * `comment_hide_quick_emoji_research` follow-status gate. TikTok 46.8.3 removed that setting and
 * calls an Aweme predicate instead. All three builds still load the class's only LinearLayout,
 * null-check it, then pass `(visibility, row)` to a static `(int, View)void` utility. That final
 * display boundary is the shared contract.
 */
internal fun Method.quickCommentVisibilityWrites(
    rowField: FieldReference,
): List<QuickCommentVisibilityWrite> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.mapIndexedNotNull { fieldIndex, instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT ||
            instruction.getReference<FieldReference>()?.sameFieldAs(rowField) != true
        ) {
            return@mapIndexedNotNull null
        }
        val rowRegister = (instruction as TwoRegisterInstruction).registerA
        val invokeIndex = ((fieldIndex + 1)..minOf(fieldIndex + 3, instructions.lastIndex))
            .firstOrNull { candidateIndex ->
                val candidate = instructions[candidateIndex]
                val target = candidate.getReference<MethodReference>()
                candidate.opcode == Opcode.INVOKE_STATIC &&
                    candidate is FiveRegisterInstruction &&
                    candidate.registerCount == 2 &&
                    candidate.registerD == rowRegister &&
                    target?.returnType == "V" &&
                    target.parameterTypes.map(CharSequence::toString) == VISIBILITY_PARAMETERS
            } ?: return@mapIndexedNotNull null
        val invoke = instructions[invokeIndex] as FiveRegisterInstruction
        QuickCommentVisibilityWrite(invokeIndex, invoke.registerC)
    }
}

/** Forces every native show attempt for the reaction row through the Hushfeed setting. */
internal fun MutableMethod.hookQuickCommentVisibility(rowField: FieldReference): Int {
    val writes = quickCommentVisibilityWrites(rowField)
    if (writes.size < 2) {
        throw PatchException(
            "Hide quick comment reactions: expected at least two visibility writes to " +
                "${rowField.definingClass}->${rowField.name}, found ${writes.size} in " +
                "$definingClass->$name.",
        )
    }
    writes.asReversed().forEach { write ->
        val register = write.visibilityRegister
        addInstructionsAtControlFlowLabel(
            write.invokeIndex,
            """
                invoke-static { v$register }, $FEATURE_CONTROLS_DESCRIPTOR->hideQuickCommentReactionsVisibility(I)I
                move-result v$register
            """,
        )
    }
    return writes.size
}

private fun FieldReference.sameFieldAs(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type
