package app.morphe.patches.tiktok.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry guard nineteen patches used to write out by hand: ask, and leave when the answer
 * is yes. What the helper adds over the six lines is the local-register check in front, which
 * most of the hand-written copies made and some did not.
 */
class GuardAtEntryTest {
    @Test
    fun `the guard asks first and the host's own body follows the fall-through`() {
        val method = method(registers = 3, parameters = listOf("I"))
        method.guardAtEntry("Test patch", "invoke-static {}, Lfixture/Ext;->shouldSkip()Z", "return-void")

        val opcodes = method.implementation!!.instructions.map { it.opcode }
        assertEquals(
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.NOP, Opcode.RETURN_VOID),
            opcodes,
        )
        assertEquals(0, (method.implementation!!.instructions.elementAt(1) as OneRegisterInstruction).registerA)
    }

    @Test
    fun `a multi-line return leaves through every one of its lines`() {
        val method = method(registers = 3, parameters = listOf("I"))
        method.guardAtEntry("Test patch", "invoke-static {}, Lfixture/Ext;->shouldSkip()Z", """
            const/4 v0, 0x0
            return v0
        """)

        val opcodes = method.implementation!!.instructions.map { it.opcode }
        assertEquals(
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN, Opcode.NOP, Opcode.RETURN_VOID),
            opcodes,
        )
    }

    @Test
    fun `two guards in one method get labels of their own`() {
        val method = method(registers = 3, parameters = listOf("I"))
        method.guardAtEntry("Test patch", "invoke-static {}, Lfixture/Ext;->first()Z", "return-void")
        method.guardAtEntry("Test patch", "invoke-static {}, Lfixture/Ext;->second()Z", "return-void")
        assertEquals(11, method.implementation!!.instructions.count())
    }

    @Test
    fun `a frame with no local is refused by name before anything is assembled`() {
        val method = method(registers = 2, parameters = listOf("I"))
        val refused = assertThrows(PatchException::class.java) {
            method.guardAtEntry("Test patch", "invoke-static {}, Lfixture/Ext;->shouldSkip()Z", "return-void")
        }
        assertTrue(refused.message, refused.message.orEmpty().startsWith("Test patch: "))
        assertTrue(refused.message, refused.message.orEmpty().contains("local register"))
        assertEquals(1, method.implementation!!.instructions.count())
    }

    private fun method(registers: Int, parameters: List<String>) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Host;",
            "act",
            parameters.map { ImmutableMethodParameter(it, null, null) },
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(registers, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        ),
    )
}
