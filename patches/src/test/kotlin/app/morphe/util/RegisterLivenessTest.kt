package app.morphe.util

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21ih
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterLivenessTest {
    @Test
    fun `a register read on one arm of a branch is live before the branch and dead after its write`() {
        val method = smali(
            registers = 3, params = listOf("Z"),
            body = """
                if-eqz p0, :skip
                invoke-static {v0}, Lcom/example/Log;->note(Ljava/lang/Object;)V
                :skip
                const/4 v0, 0x0
                invoke-static {v0}, Lcom/example/Log;->note(Ljava/lang/Object;)V
                return-void
            """,
        )
        val liveness = RegisterLiveness.of(method)
        assertEquals(setOf(0, 2), liveness.liveInto(0))   // v0 on the arm that reads it, p0 for the test
        assertEquals(setOf(0), liveness.liveInto(1))
        assertEquals(emptySet<Int>(), liveness.liveInto(2))   // the const writes v0 before anything reads it
        assertEquals(setOf(0), liveness.liveInto(3))
    }

    @Test
    fun `a backward goto carries a read back around the loop`() {
        val method = smali(
            registers = 3, params = listOf("I"),
            body = """
                const/4 v1, 0x0
                :top
                invoke-static {v0}, Lcom/example/Log;->note(Ljava/lang/Object;)V
                add-int/lit8 v1, v1, 0x1
                if-eqz p0, :top
                return-void
            """,
        )
        val liveness = RegisterLiveness.of(method)
        // v0 is never written, so it is live at the top of the loop and before the const; v1
        // is read by the two-address add, so it is live once written and again around the loop.
        assertEquals(setOf(0, 2), liveness.liveInto(0))
        assertEquals(setOf(0, 1, 2), liveness.liveInto(1))
        assertEquals(setOf(0, 1, 2), liveness.liveInto(3))
    }

    @Test
    fun `every case of a packed switch is followed`() {
        // packed-switch v0 with two cases that each read a different register, and a
        // fall-through that reads a third. All three are live before the switch.
        val note = ImmutableMethodReference("Lcom/example/Log;", "note", listOf("Ljava/lang/Object;"), "V")
        fun call(register: Int) = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, register, 0, 0, 0, 0, note)
        val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)
        // Code units: packed-switch 3, invoke 3, return 1, invoke 3, return 1, invoke 3, return 1,
        // then the payload. The switch is at 0, case 0 at 3+3+1 = 7, case 1 at 7+3+1 = 11,
        // the payload at 11+3+1 = 15 (even, as a payload must be).
        val payload = ImmutablePackedSwitchPayload(listOf(
            ImmutableSwitchElement(0, 7),
            ImmutableSwitchElement(1, 11),
        ))
        val instructions = listOf(
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, 0, 15),
            call(1), returnVoid,      // fall-through reads v1
            call(2), returnVoid,      // case 0 reads v2
            call(3), returnVoid,      // case 1 reads v3
            payload,
        )
        val method = MutableMethod(ImmutableMethod(
            "Lcom/example/Host;", "run", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(4, instructions, null, null),
        ))
        val liveness = RegisterLiveness.of(method)
        assertEquals(setOf(0, 1, 2, 3), liveness.liveInto(0))
        assertEquals(setOf(1), liveness.liveInto(1))
        assertEquals(setOf(2), liveness.liveInto(3))
        assertEquals(setOf(3), liveness.liveInto(5))
    }

    @Test
    fun `a register the exception handler reads is live everywhere inside the try`() {
        val risky = ImmutableMethodReference("Lcom/example/Io;", "risky", emptyList<String>(), "V")
        val note = ImmutableMethodReference("Lcom/example/Log;", "note", listOf("Ljava/lang/Object;"), "V")
        // 0: const/4 v1, 0        (1 code unit)   try starts at address 1
        // 1: invoke-static {} risky  (3)           in the try
        // 2: const/4 v2, 0        (1)              in the try, writes v2
        // 3: return-void          (1)              try ends at address 6
        // 4: move-exception v0    (1)              handler at address 6
        // 5: invoke-static {v2} note (3)           the handler reads v2
        // 6: return-void
        val instructions = listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, risky),
            ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, note),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val tryBlock = ImmutableTryBlock(1, 5, listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", 6)))
        val method = MutableMethod(ImmutableMethod(
            "Lcom/example/Host;", "run", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(3, instructions, listOf(tryBlock), null),
        ))
        val liveness = RegisterLiveness.of(method)
        // The handler can be entered from the risky call before v2 is written, so v2 is live
        // there and before the try. In front of the const that writes it, it is live as well: a
        // hook put there runs code that can throw into the handler before v2 is written (this
        // said empty until the refutation review of 4fe7b503). After the write it is dead on the
        // straight path, but the return inside the try can still throw into the handler.
        assertEquals(setOf(2), liveness.liveInto(0))
        assertEquals(setOf(2), liveness.liveInto(1))
        assertEquals(setOf(2), liveness.liveInto(2))
        assertEquals(setOf(2), liveness.liveInto(3))
        assertEquals(setOf(2), liveness.liveInto(4))
    }

    @Test
    fun `a throwing write keeps the old value live for the handler that reads it`() {
        val box = ImmutableFieldReference("Lcom/example/Box;", "value", "I")
        val note = ImmutableMethodReference("Lcom/example/Log;", "note", listOf("I"), "V")
        // 0: const/4 v1, 0          (1 code unit)  address 0
        // 1: iget v2, v1, Box.value (2)            address 1, in the try: can throw, writes v2
        // 2: return-void            (1)            address 3, in the try
        // 3: move-exception v0      (1)            address 4, the handler
        // 4: invoke-static {v2} note (3)           address 5, the handler reads v2
        // 5: return-void                           address 8
        val instructions = listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction22c(Opcode.IGET, 2, 1, box),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, note),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val tryBlock = ImmutableTryBlock(1, 3, listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", 4)))
        val method = MutableMethod(ImmutableMethod(
            "Lcom/example/Host;", "run", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(3, instructions, listOf(tryBlock), null),
        ))
        val liveness = RegisterLiveness.of(method)
        // When the iget throws, v2 was never written and the handler reads what it held before,
        // so a hook must not take v2 as scratch in front of the iget.
        assertEquals(setOf(1, 2), liveness.liveInto(1))
        assertEquals(setOf(2), liveness.liveInto(0))
    }

    @Test
    fun `a write that can't throw keeps the handler's value live for a hook put in front of it`() {
        val note = ImmutableMethodReference("Lcom/example/Log;", "note", listOf("I"), "V")
        // 0: const/4 v2, 1          address 0
        // 1: const/4 v2, 0          address 1, in the try: can't throw, overwrites v2
        // 2: return-void            address 2, in the try
        // 3: move-exception v0      address 3, the handler
        // 4: invoke-static {v2} note address 4, the handler reads v2
        // 5: return-void            address 7
        val instructions = listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 2, 1),
            ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, note),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val tryBlock = ImmutableTryBlock(1, 2, listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", 3)))
        val method = MutableMethod(ImmutableMethod(
            "Lcom/example/Host;", "run", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(3, instructions, listOf(tryBlock), null),
        ))
        // The const itself can't throw, but a hook in front of it (LruCache.remove, sput-object)
        // can, and the handler then reads v2 as it was: the hook must not take v2 as scratch
        // (refutation review of 4fe7b503).
        assertEquals(setOf(2), RegisterLiveness.of(method).liveInto(1))
    }

    @Test
    fun `wide values occupy the register above as well`() {
        val method = smali(
            registers = 4, params = emptyList(),
            body = """
                const-wide/16 v0, 0x0
                move-wide v2, v0
                return-wide v2
            """,
        )
        val liveness = RegisterLiveness.of(method)
        assertEquals(emptySet<Int>(), liveness.liveInto(0))
        assertEquals(setOf(0, 1), liveness.liveInto(1))
        assertEquals(setOf(2, 3), liveness.liveInto(2))
    }

    @Test
    fun `a literal's reads follow both arms of a branch and stop where the register is written again`() {
        val method = smali(
            registers = 3, params = listOf("Z"),
            body = """
                const/high16 v0, 0x40000000
                if-eqz p0, :other
                invoke-static {v0}, Lcom/example/Speed;->set(F)V
                return-void
                :other
                cmpg-float v1, v0, v0
                const/4 v0, 0x0
                invoke-static {v0}, Lcom/example/Log;->note(I)V
                return-void
            """,
        )
        // The call on one arm and the compare on the other read the 2.0; the int after the
        // rewrite on the second arm is another value.
        assertEquals(listOf(2, 4), method.literalReads(0))
        assertTrue(method.readsRegisterAsFloat(2, 0))
        assertTrue(method.readsRegisterAsFloat(4, 0))
    }

    @Test
    fun `a literal passed where an int is expected is not a float read`() {
        val method = smali(
            registers = 1, params = emptyList(),
            body = """
                const/high16 v0, 0x40000000
                invoke-static {v0}, Lcom/example/Log;->note(I)V
                return-void
            """,
        )
        assertEquals(listOf(1), method.literalReads(0))
        assertFalse(method.readsRegisterAsFloat(1, 0))
    }

    @Test
    fun `a float argument is found past a wide one, and the wide slot is not a float`() {
        val method = smali(
            registers = 4, params = emptyList(),
            body = """
                const-wide/16 v0, 0x0
                const/high16 v2, 0x40000000
                invoke-static {v0, v1, v2}, Lcom/example/Speed;->at(JF)V
                return-void
            """,
        )
        assertEquals(listOf(2), method.literalReads(1))
        assertTrue(method.readsRegisterAsFloat(2, 2))
        assertFalse(method.readsRegisterAsFloat(2, 1))
    }

    @Test
    fun `a handler reached from a throwing write still sees the literal`() {
        val risky = ImmutableMethodReference("Lcom/example/Io;", "risky", emptyList<String>(), "V")
        val note = ImmutableMethodReference("Lcom/example/Log;", "note", listOf("F"), "V")
        // 0: const/high16 v1, 2.0f  (2 code units)   try starts at address 2
        // 1: invoke-static {} risky (3)             in the try
        // 2: const/4 v1, 0          (1)             in the try, writes v1
        // 3: return-void            (1)             try ends at address 7
        // 4: move-exception v0      (1)             handler at address 7
        // 5: invoke-static {v1} note(F) (3)         the handler reads v1
        // 6: return-void
        val instructions = listOf(
            ImmutableInstruction21ih(Opcode.CONST_HIGH16, 1, 0x40000000),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, risky),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, note),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val tryBlock = ImmutableTryBlock(2, 5, listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", 7)))
        val method = MutableMethod(ImmutableMethod(
            "Lcom/example/Host;", "run", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, instructions, listOf(tryBlock), null),
        ))
        assertEquals(listOf(5), method.literalReads(0))
        assertTrue(method.readsRegisterAsFloat(5, 1))
    }

    @Test
    fun `reads after an instruction end at the next write, and a later read keeps the register busy`() {
        val method = smali(
            registers = 3, params = emptyList(),
            body = """
                const v0, 0x7f117539
                invoke-virtual {v1, v0}, Lcom/example/Toast;->text(I)V
                const v0, 0x7f040000
                invoke-virtual {v1, v0}, Lcom/example/Toast;->icon(I)V
                invoke-virtual {v1, v2}, Lcom/example/Toast;->icon(I)V
                return-void
            """,
        )
        assertEquals(emptyList<Int>(), method.readsAfter(1, 0))   // rewritten before anything reads it
        assertEquals(listOf(4), method.readsAfter(1, 2))          // v2 is read further down
    }

    private fun smali(registers: Int, params: List<String>, body: String) = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Host;", "run", params.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }
}
