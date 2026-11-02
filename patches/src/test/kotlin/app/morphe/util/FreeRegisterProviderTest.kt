package app.morphe.util

import app.morphe.util.findFreeRegister
import app.morphe.util.getFreeRegisterProvider

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableArrayPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSparseSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The free register search, on methods built here rather than read out of an APK.
 *
 * <p>Its two call sites in this tree were traced by hand on the 46.2.3 fixture and both happen
 * to be answered correctly, so none of these defects is live. Each of them is a wrong answer
 * waiting for the first patch whose method has the shape it gets wrong, and there was nothing
 * to say so.
 */
class FreeRegisterProviderTest {
    private fun method(registerCount: Int, vararg instructions: Instruction): Method =
        ImmutableMethod(
            "Lcom/example/Host;",
            "run",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(registerCount, instructions.toList(), null, null),
        )

    @Test
    fun `a register an accumulating opcode writes into is not free`() {
        // add-int/2addr v2, v3 is v2 = v2 + v3: it reads its destination as well as writing it.
        // The destination appears once in the register list, so counting occurrences alone read
        // it as write-only and handed v2 out while the host was still accumulating into it.
        val accumulate = method(
            4,
            ImmutableInstruction12x(Opcode.ADD_INT_2ADDR, 2, 3),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(0, accumulate.findFreeRegister(0))
    }

    @Test
    fun `a method whose first instruction is a branch is followed rather than refused`() {
        // if-eqz at index 0, jumping over the const to the return. The bounds check on the
        // branch target read `index > 0`, so the ordinary case of a method that opens with a
        // branch threw "Invalid index: 0" instead of being followed.
        val branchFirst = method(
            3,
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 3),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(1, branchFirst.findFreeRegister(0))
    }

    @Test
    fun `a branch over a payload lands on the right instruction`() {
        // A payload is not an instruction anything branches to, but it does occupy code units.
        // Stepping over it without counting them left every later instruction at an offset
        // short by the length of the payload, so this goto resolved to no instruction at all
        // and the search threw.
        val payload = ImmutableArrayPayload(4, listOf(1L, 2L, 3L, 4L))
        val overPayload = method(
            2,
            ImmutableInstruction10t(Opcode.GOTO, 1 + payload.codeUnits),
            payload,
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(0, overPayload.findFreeRegister(0))
    }

    @Test
    fun `the lowest free register comes back first`() {
        // v3 is written before v1, and the set behind the search keeps discovery order. The
        // comparator answered 0 for any two registers of the same class, so a stable sort left
        // v3 in front, while the documentation and every caller read it as the lowest.
        val writesHighFirst = method(
            4,
            ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        val registers = writesHighFirst.getFreeRegisterProvider(0, 2)
        assertEquals(1, registers.getFreeRegister4Bit())
        assertEquals(3, registers.getFreeRegister4Bit())
    }

    @Test
    fun `the high half of a wide value is not free`() {
        // const-wide/16 v2 writes v2 and v3: a long lives in the register named and the one
        // above it, and dexlib2 names only the low half. The search read the write as freeing
        // one register and never looked at the other, so v2 came back as free while the return
        // below was about to read the pair, and v3 was never counted as used at all.
        val wide = method(
            4,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 2, 0),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 2),
        )

        assertEquals(0, wide.findFreeRegister(0))

        val registers = wide.getFreeRegisterProvider(0, 2)
        assertEquals(0, registers.getFreeRegister4Bit())
        assertEquals(1, registers.getFreeRegister4Bit())
    }

    @Test
    fun `a packed switch is followed instead of ending the search`() {
        // A switch used to be a leaf node: the search stopped there and answered with whatever
        // it had proved free beforehand, which at the top of a method is nothing, so the caller
        // was told there were no free registers and the patch failed to apply. R8 turns a chain
        // of string comparisons into one of these, so the same method is an if-else chain in one
        // TikTok build and a packed switch in the next.
        //
        //   0  packed-switch v0, +7      offset 0, three code units
        //   1  const/4 v1, 0             offset 3, the fall-through
        //   2  return-void               offset 4
        //   3  move v3, v1               offset 5, the one arm
        //   4  return-void               offset 6
        //   5  payload                   offset 7
        val switched = method(
            4,
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, 0, 7),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction12x(Opcode.MOVE, 3, 1),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 5))),
        )

        // v1 is free down the fall-through and read by the arm, so it survives only one of the
        // two paths. An answer that named it would be a union, and a union is not an answer.
        val registers = switched.getFreeRegisterProvider(0, 2)
        assertEquals(2, registers.getFreeRegister4Bit())
        assertEquals(3, registers.getFreeRegister4Bit())
        assertEquals(2, switched.findFreeRegister(0))
    }

    @Test
    fun `a sparse switch intersects every arm and the fall-through`() {
        //   0  sparse-switch v0, +9      offset 0, three code units
        //   1  const/4 v1, 0             offset 3, the fall-through
        //   2  return-void               offset 4
        //   3  const/4 v1, 0             offset 5, first arm
        //   4  return-void               offset 6
        //   5  const/4 v2, 0             offset 7, second arm
        //   6  return-void               offset 8
        //   7  payload                   offset 9
        val switched = method(
            4,
            ImmutableInstruction31t(Opcode.SPARSE_SWITCH, 0, 9),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableSparseSwitchPayload(
                listOf(ImmutableSwitchElement(10, 5), ImmutableSwitchElement(20, 7)),
            ),
        )

        // Each path writes one register and returns, so every register above v0 is free on all
        // three and all three are offered.
        val registers = switched.getFreeRegisterProvider(0, 3)
        assertEquals(1, registers.getFreeRegister4Bit())
        assertEquals(2, registers.getFreeRegister4Bit())
        assertEquals(3, registers.getFreeRegister4Bit())
    }

    @Test
    fun `an index reached twice answers the same both times`() {
        // Two arms of a branch converge on index 5, which is itself a branch. A frame that ends
        // in a branch answers with its own registers plus what its two sides agree on, but only
        // the first half of that was published for the next visitor, so the second arm was told
        // index 5 had nothing to offer. The intersection of the two arms then collapsed to one
        // register where four were free.
        val converging = method(
            6,
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10t(Opcode.GOTO, 3),
            ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
            ImmutableInstruction10t(Opcode.GOTO, 1),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction11n(Opcode.CONST_4, 4, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        val registers = converging.getFreeRegisterProvider(0, 4)
        assertEquals(1, registers.getFreeRegister4Bit())
        assertEquals(3, registers.getFreeRegister4Bit())
        assertEquals(4, registers.getFreeRegister4Bit())
        assertEquals(5, registers.getFreeRegister4Bit())
    }
}
