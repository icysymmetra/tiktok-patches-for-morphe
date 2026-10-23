/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toggle's read is checked with both gates, before any of the four is hooked, so a read the
 * patch can't send through the extension leaves the gates as TikTok had them.
 */
class CommentTranslateButtonCheckTest {
    @Test
    fun `a read with no return to send through the extension is refused`() {
        val refused = assertThrows(PatchException::class.java) {
            read(ImmutableInstruction10x(Opcode.RETURN_VOID)).checkToggleStateRead()
        }
        assertTrue(refused.message, refused.message.orEmpty().startsWith("Translate comments: "))
    }

    @Test
    fun `a read that returns its answer passes`() {
        read(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0))
            .checkToggleStateRead()
    }

    private fun read(vararg instructions: Instruction) = ImmutableMethod(
        "Lfixture/Toggle;",
        "on",
        emptyList(),
        "Z",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        ImmutableMethodImplementation(1, instructions.toList(), null, null),
    )
}
