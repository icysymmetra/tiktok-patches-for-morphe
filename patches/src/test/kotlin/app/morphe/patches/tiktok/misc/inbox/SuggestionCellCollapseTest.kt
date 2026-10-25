package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The hook at a suggestion cell's bind, on the bridge shape TikTok 46.2.3 gives it. */
class SuggestionCellCollapseTest {
    @Test
    fun `the cell is handed to the extension as TikTok's bind returns`() {
        val method = bindBridge()
        val original = method.implementation!!.instructions.toList()

        method.collapseSuggestionCellAtBind()

        val code = method.implementation!!.instructions.toList()
        assertEquals(original.size + 1, code.size)
        // TikTok's own bind first, untouched: the Rect cells write a margin inside it, and a
        // call ahead of that would have been undone.
        original.dropLast(1).forEachIndexed { index, instruction -> assertSame(instruction, code[index]) }
        val call = code[code.size - 2]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val range = call as RegisterRangeInstruction
        // p0 is v0 on a two-register method with one parameter: the cell itself.
        assertEquals(1, range.registerCount)
        assertEquals(0, range.startRegister)
        val target = (call as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/inbox/SuggestedAccountCells;", target.definingClass)
        assertEquals("onBind", target.name)
        assertEquals(listOf("Ljava/lang/Object;"), target.parameterTypes.map(CharSequence::toString))
        assertEquals("V", target.returnType)
        assertSame(original.last(), code.last())
        assertEquals(Opcode.RETURN_VOID, code.last().opcode)
    }

    /** `AbsRecUserCell.onBindItemView(LX/0lOS;)V` on 46.2.3: a check-cast, the typed bind, return. */
    private fun bindBridge() = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/aweme/relation/usercard/impl/cell/AbsRecUserCell;",
            "onBindItemView",
            listOf(ImmutableMethodParameter("LX/0lOS;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference("LX/0l3W;")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        ),
    )
}
