/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check a patch hooking two gates runs on both before it hooks either (Translate comments'
 * button): it refuses what returnBooleanWhenOn would refuse, and changes nothing either way.
 */
class SwitchedReturnCheckTest {
    @Test
    fun `a gate that answers no boolean is refused and left as it was`() {
        val gate = method(returnType = "V", registers = 3, parameters = listOf("I"))
        val refused = assertThrows(PatchException::class.java) { gate.checkBooleanWhenOn("Test patch") }
        assertTrue(refused.message, refused.message.orEmpty().startsWith("Test patch: "))
        assertTrue(refused.message, refused.message.orEmpty().contains("not a boolean"))
        assertEquals(1, gate.implementation!!.instructions.count())
    }

    @Test
    fun `a frame of parameters only is refused once its clone's register is past move-result's reach`() {
        // this and 255 ints fill v0 to v255, so the clone's scratch register would be v256.
        val wide = method(returnType = "Z", registers = 256, parameters = List(255) { "I" })
        val refused = assertThrows(PatchException::class.java) { wide.checkBooleanWhenOn("Test patch") }
        assertTrue(refused.message, refused.message.orEmpty().contains("move-result can reach"))
        assertEquals(1, wide.implementation!!.instructions.count())

        method(returnType = "Z", registers = 255, parameters = List(254) { "I" }).checkBooleanWhenOn("Test patch")
        method(returnType = "Z", registers = 300, parameters = List(254) { "I" }).checkBooleanWhenOn("Test patch")
    }

    private fun method(returnType: String, registers: Int, parameters: List<String>) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Gate;",
            "enabled",
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(registers, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        ),
    )
}
