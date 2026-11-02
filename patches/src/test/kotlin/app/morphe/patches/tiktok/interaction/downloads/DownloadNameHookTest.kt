/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The download name hook reads two fields off p0 at index 0, whatever size the frame is.
 *
 * <p>It used to refuse any coroutine with more than fifteen locals, because iget-object cannot
 * name a register above v15 and p0 sits just above the locals. A reporter's 46.8.3 had eighteen
 * (issue #9) and lost Downloads for it. Large frames now copy p0 down first.
 */
class DownloadNameHookTest {
    private val coroutine = "LX/0Dl1;"
    private val path = ImmutableFieldReference(coroutine, "LIZIZ", "Ljava/lang/String;")
    private val aweme = ImmutableFieldReference(coroutine, "LIZJ", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;")

    @Test
    fun `a small frame reads straight off p0`() {
        val hook = injected(locals = 4)
        assertIget(hook[0], into = 0, from = 4)
        assertIget(hook[1], into = 1, from = 4)
        assertCall(hook[2])
    }

    @Test
    fun `fifteen locals is the last frame p0 can be named in directly`() {
        val hook = injected(locals = 15)
        assertIget(hook[0], into = 0, from = 15)
        assertIget(hook[1], into = 1, from = 15)
        assertCall(hook[2])
    }

    @Test
    fun `sixteen locals copies p0 down before reading it`() {
        val hook = injected(locals = 16)
        assertCopy(hook[0], from = 16)
        assertIget(hook[1], into = 0, from = 1)
        assertIget(hook[2], into = 1, from = 1)
        assertCall(hook[3])
    }

    @Test
    fun `the eighteen-local frame from issue 9 takes the hook`() {
        val hook = injected(locals = 18)
        assertCopy(hook[0], from = 18)
        assertIget(hook[1], into = 0, from = 1)
        assertIget(hook[2], into = 1, from = 1)
        assertCall(hook[3])
    }

    @Test
    fun `two locals is enough`() {
        val hook = injected(locals = 2)
        assertIget(hook[0], into = 0, from = 2)
        assertCall(hook[2])
    }

    @Test
    fun `one local is refused, because v1 would be p0`() {
        try {
            injected(locals = 1)
            fail("expected a refusal")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains("has 1 local register"))
        }
    }

    /** Builds invokeSuspend(Object)Object with [locals] locals, injects the hook, returns what it put first. */
    private fun injected(locals: Int): List<Instruction> {
        val method = MutableMethod(
            ImmutableMethod(
                coroutine,
                "invokeSuspend",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                // this and the one Object parameter sit above the locals.
                ImmutableMethodImplementation(locals + 2, emptyList(), null, null),
            ),
        ).apply { addInstructions(0, "return-object p1") }
        method.injectDownloadNameHook(path, aweme)
        val instructions = method.implementation!!.instructions.toList()
        assertEquals("the host's own return is still last", Opcode.RETURN_OBJECT, instructions.last().opcode)
        return instructions.dropLast(1)
    }

    private fun assertCopy(instruction: Instruction, from: Int) {
        assertEquals(Opcode.MOVE_OBJECT_FROM16, instruction.opcode)
        instruction as TwoRegisterInstruction
        assertEquals("copied into", 1, instruction.registerA)
        assertEquals("copied from p0", from, instruction.registerB)
    }

    private fun assertIget(instruction: Instruction, into: Int, from: Int) {
        assertEquals(Opcode.IGET_OBJECT, instruction.opcode)
        instruction as TwoRegisterInstruction
        assertEquals("read into", into, instruction.registerA)
        assertEquals("read off", from, instruction.registerB)
    }

    private fun assertCall(instruction: Instruction) {
        assertEquals(Opcode.INVOKE_STATIC, instruction.opcode)
        instruction as FiveRegisterInstruction
        assertEquals(2, instruction.registerCount)
        assertEquals(0, instruction.registerC)
        assertEquals(1, instruction.registerD)
    }
}
