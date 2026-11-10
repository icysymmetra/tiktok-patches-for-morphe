package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTypingStickerSuggestionGateTest {
    @Test
    fun `enabled setting keeps the dedicated typing row on its hidden path`() {
        val method = visibilityMethod()

        method.forceCommentTypingStickerSuggestionsHidden()

        val instructions = method.implementation!!.instructions.toList()
        val settingIndex = instructions.indexOfFirst { instruction ->
            instruction.getReference<MethodReference>()?.let { target ->
                target.definingClass == COMMENT_TYPING_FEATURE_CONTROLS_DESCRIPTOR &&
                    target.name == "hideQuickCommentReactions"
            } == true
        }
        assertTrue(settingIndex >= 0)
        assertEquals(Opcode.MOVE_RESULT, instructions[settingIndex + 1].opcode)
        assertEquals(Opcode.IF_EQZ, instructions[settingIndex + 2].opcode)
        assertEquals(Opcode.CONST_4, instructions[settingIndex + 3].opcode)
        assertEquals(
            method.implementation!!.registerCount - 1,
            (instructions[settingIndex + 3] as OneRegisterInstruction).registerA,
        )
        assertEquals(Opcode.RETURN_VOID, instructions.last().opcode)
    }

    private fun visibilityMethod() = MutableMethod(
        ImmutableMethod(
            COMMENT_TYPING_STICKER_RECOMMEND_ASSEM,
            "updateVisibility",
            listOf(ImmutableMethodParameter("Z", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(
                3,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ),
    )
}
