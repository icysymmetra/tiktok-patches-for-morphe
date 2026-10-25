package app.morphe.util

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlFlowLabelInjectionTest {
    @Test
    fun `a hook at a goto target runs on the branch and fall through paths`() {
        val method = branchTargetReturn()
        val returnIndex = method.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.RETURN_VOID
        }

        method.addInstructionsAtControlFlowLabel(
            returnIndex,
            "invoke-static {}, Lcom/example/Probe;->hit()V",
        )

        val instructions = method.implementation!!.instructions.toList()
        val addresses = codeAddresses(method)
        val gotoIndex = instructions.indexOfFirst { it.opcode == Opcode.GOTO }
        val gotoTarget = addresses[gotoIndex] +
            (instructions[gotoIndex] as OffsetInstruction).codeOffset
        val targetIndex = addresses.indexOf(gotoTarget)

        assertEquals(Opcode.INVOKE_STATIC, instructions[targetIndex].opcode)
        assertEquals(1, execute(method, parameter = 0))
        assertEquals(1, execute(method, parameter = 1))
    }

    private fun branchTargetReturn() = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Host;",
            "finish",
            listOf(ImmutableMethodParameter("Z", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(1, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                if-eqz p0, :fall_through
                goto :shared_return
                :fall_through
                nop
                :shared_return
                return-void
            """,
        )
    }

    private fun execute(method: MutableMethod, parameter: Int): Int {
        val instructions = method.implementation!!.instructions.toList()
        val addresses = codeAddresses(method)
        val indexAtAddress = addresses.withIndex().associate { (index, address) ->
            address to index
        }
        var index = 0
        var calls = 0

        while (true) {
            val instruction = instructions[index]
            when (instruction.opcode) {
                Opcode.IF_EQZ -> {
                    index = if (parameter == 0) {
                        val target = addresses[index] +
                            (instruction as OffsetInstruction).codeOffset
                        indexAtAddress.getValue(target)
                    } else {
                        index + 1
                    }
                }
                Opcode.GOTO -> {
                    val target = addresses[index] +
                        (instruction as OffsetInstruction).codeOffset
                    index = indexAtAddress.getValue(target)
                }
                Opcode.INVOKE_STATIC -> {
                    calls++
                    index++
                }
                Opcode.NOP -> index++
                Opcode.RETURN_VOID -> return calls
                else -> error("Unexpected opcode in fixture: ${instruction.opcode}")
            }
        }
    }

    private fun codeAddresses(method: MutableMethod): List<Int> {
        var address = 0
        return method.implementation!!.instructions.map { instruction ->
            address.also { address += instruction.codeUnits }
        }
    }
}
