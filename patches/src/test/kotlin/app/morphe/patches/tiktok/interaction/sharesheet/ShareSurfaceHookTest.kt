/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The share model constructor learns which sheet it is building before its three filters run.
 *
 * <p>The builder is p1. Every retained build (46.2.3 to 46.9.3) gives that constructor eleven
 * registers, nine of them locals, so p1 is v10 and iget-object names it directly.
 */
class ShareSurfaceHookTest {
    private val model = "LX/0oVo;"
    private val builder = "LX/0oVp;"
    private val field = "$builder->LJJIIJ:Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"

    @Test
    fun `the frame every retained build has reads the package straight off p1`() {
        val hook = injected(locals = 9)
        assertIget(hook[0], into = 0, from = 10)
        assertSurfaceCall(hook[1])
        assertEquals("the constructor's own super call still runs next", Opcode.INVOKE_DIRECT, hook[2].opcode)
    }

    @Test
    fun `fourteen locals is the last frame p1 can be named in directly`() {
        val hook = injected(locals = 14)
        assertIget(hook[0], into = 0, from = 15)
        assertSurfaceCall(hook[1])
    }

    @Test
    fun `a larger frame copies p1 down first`() {
        val hook = injected(locals = 15)
        assertEquals(Opcode.MOVE_OBJECT_FROM16, hook[0].opcode)
        val copy = hook[0] as TwoRegisterInstruction
        assertEquals("copied into", 0, copy.registerA)
        assertEquals("copied from p1", 16, copy.registerB)
        assertIget(hook[1], into = 0, from = 0)
        assertSurfaceCall(hook[2])
    }

    @Test
    fun `a frame with no locals is refused rather than written over p0`() {
        try {
            injected(locals = 0)
            fail("expected a refusal")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains("has 0 local register"))
        }
    }

    private fun injected(locals: Int): List<Instruction> {
        val method = MutableMethod(
            ImmutableMethod(
                model,
                "<init>",
                listOf(ImmutableMethodParameter(builder, null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                null,
                null,
                ImmutableMethodImplementation(locals + 2, emptyList(), null, null),
            ),
        ).apply {
            addInstructions(
                0,
                """
                    invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                    return-void
                """,
            )
        }
        method.injectShareSurface(field)
        return method.implementation!!.instructions.toList()
    }

    private fun assertIget(instruction: Instruction, into: Int, from: Int) {
        assertEquals(Opcode.IGET_OBJECT, instruction.opcode)
        instruction as TwoRegisterInstruction
        assertEquals("read into", into, instruction.registerA)
        assertEquals("read off", from, instruction.registerB)
        val reference = (instruction as ReferenceInstruction).reference as FieldReference
        assertEquals("LJJIIJ", reference.name)
        assertEquals(builder, reference.definingClass)
    }

    private fun assertSurfaceCall(instruction: Instruction) {
        assertEquals(Opcode.INVOKE_STATIC, instruction.opcode)
        instruction as FiveRegisterInstruction
        assertEquals(1, instruction.registerCount)
        assertEquals(0, instruction.registerC)
        val reference = (instruction as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/share/ShareModelFilter;", reference.definingClass)
        assertEquals("surface", reference.name)
    }
}
