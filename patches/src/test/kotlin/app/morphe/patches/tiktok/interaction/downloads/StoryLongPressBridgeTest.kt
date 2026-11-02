package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StoryLongPressBridgeTest {
    @Test
    fun nativeTimerUsesItsViewAndKeepsTheOriginalPausePathWhenUnhandled() {
        // Clean 46.2.3 LX/0Qet.LIZ, including its monitor/interceptor branches.
        val method = MutableMethod(ImmutableMethod(
            "LX/0Qet;", "LIZ", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(4, emptyList(), null, null),
        )).apply {
            addInstructionsWithLabels(0, """
                iget-object v1, p0, LX/0Qet;->LL:LX/0R9T;
                iget-object v0, v1, LX/0R9T;->LLJIJIL:LX/0Qeu;
                const/4 v2, 0x1
                if-eqz v0, :listener
                iput-boolean v2, v1, LX/0R9T;->LLJJ:Z
                invoke-interface { v0 }, LX/0Qeu;->Mf()V
                iget-object v0, p0, LX/0Qet;->LL:LX/0R9T;
                invoke-virtual { v0 }, Landroid/view/View;->getParent()Landroid/view/ViewParent;
                move-result-object v0
                if-eqz v0, :listener
                invoke-interface { v0, v2 }, Landroid/view/ViewParent;->requestDisallowInterceptTouchEvent(Z)V
                :listener
                iget-object v1, p0, LX/0Qet;->LL:LX/0R9T;
                iget-object v0, v1, LX/0R9T;->LLIZLLLIL:LX/0QQk;
                if-eqz v0, :done
                iget-object v0, v1, LX/0R9T;->LLJILJIL:LX/0QS2;
                if-eqz v0, :notify
                invoke-interface { v0 }, LX/0QS2;->gi()Z
                move-result v0
                if-eqz v0, :notify
                return-void
                :notify
                iget-object v0, p0, LX/0Qet;->LL:LX/0R9T;
                invoke-virtual { v0 }, Landroid/view/View;->getParent()Landroid/view/ViewParent;
                move-result-object v0
                if-eqz v0, :coordinates
                invoke-interface { v0, v2 }, Landroid/view/ViewParent;->requestDisallowInterceptTouchEvent(Z)V
                :coordinates
                iget-object v0, p0, LX/0Qet;->LL:LX/0R9T;
                iget-object v2, v0, LX/0R9T;->LLIZLLLIL:LX/0QQk;
                iget v1, v0, LX/0R9T;->LL:F
                iget v0, v0, LX/0R9T;->LLILLL:F
                invoke-interface { v2, v1, v0 }, LX/0QQk;->LIZ(FF)V
                :done
                return-void
            """)
        }
        val original = method.implementation!!.instructions.toList()
        val targets = original.mapIndexedNotNull { index, instruction ->
            if (instruction !is OffsetInstruction) null else {
                val address = original.take(index).sumOf { it.codeUnits } + instruction.codeOffset
                index to original.indices.first { original.take(it).sumOf { ins -> ins.codeUnits } == address }
            }
        }

        method.interceptStoryLongPress()

        val instructions = method.implementation!!.instructions.toList()
        assertEquals(4, method.implementation!!.registerCount)
        assertEquals(original.size + 4, instructions.size)
        assertSame(original[0], instructions[0])
        assertEquals(Opcode.INVOKE_STATIC, instructions[1].opcode)
        val call = instructions[1] as FiveRegisterInstruction
        assertEquals(1, call.registerCount)
        assertEquals(1, call.registerC)
        val reference = (instructions[1] as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/download/StoryDownloads;", reference.definingClass)
        assertEquals("onNativeLongPress", reference.name)
        assertEquals(listOf("Landroid/view/View;"), reference.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", reference.returnType)
        assertEquals(Opcode.MOVE_RESULT, instructions[2].opcode)
        assertEquals(0, (instructions[2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, instructions[3].opcode)
        assertEquals(0, (instructions[3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_VOID, instructions[4].opcode)
        val branchAddress = instructions.take(3).sumOf { it.codeUnits }
        assertEquals(instructions.take(5).sumOf { it.codeUnits },
            branchAddress + (instructions[3] as OffsetInstruction).codeOffset)
        original.drop(1).forEachIndexed { index, instruction ->
            assertSame(instruction, instructions[index + 5])
        }
        targets.forEach { (from, to) ->
            val index = from + 4
            assertEquals(instructions.take(to + 4).sumOf { it.codeUnits },
                instructions.take(index).sumOf { it.codeUnits } +
                    (instructions[index] as OffsetInstruction).codeOffset)
        }
    }
}
