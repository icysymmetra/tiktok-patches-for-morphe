package app.morphe.patches.tiktok.interaction.offlinevideos

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OPTION_ENUM = "LX/OfflineOption;"

class OfflineConstructorContractTest {
    @Test
    fun `the exact six-register range constructor is accepted`() {
        val instruction = ImmutableInstruction3rc(
            Opcode.INVOKE_DIRECT_RANGE,
            20,
            6,
            constructor(listOf("Ljava/lang/String;", "I", "I", "I", "I")),
        )

        assertTrue(isCustomOfflineOptionConstructor(instruction, OPTION_ENUM))
    }

    @Test
    fun `a plain invoke is rejected before it can be cast to a range`() {
        val instruction = ImmutableInstruction35c(
            Opcode.INVOKE_DIRECT,
            5,
            0,
            1,
            2,
            3,
            4,
            constructor(listOf("Ljava/lang/String;", "I", "I", "I", "I")),
        )

        assertFalse(isCustomOfflineOptionConstructor(instruction, OPTION_ENUM))
    }

    @Test
    fun `changed value parameters or a changed range width are rejected`() {
        val changedType = ImmutableInstruction3rc(
            Opcode.INVOKE_DIRECT_RANGE,
            20,
            7,
            constructor(listOf("Ljava/lang/String;", "I", "I", "I", "J")),
        )
        val shortRange = ImmutableInstruction3rc(
            Opcode.INVOKE_DIRECT_RANGE,
            20,
            5,
            constructor(listOf("Ljava/lang/String;", "I", "I", "I", "I")),
        )

        assertFalse(isCustomOfflineOptionConstructor(changedType, OPTION_ENUM))
        assertFalse(isCustomOfflineOptionConstructor(shortRange, OPTION_ENUM))
    }

    private fun constructor(parameters: List<String>) = ImmutableMethodReference(
        OPTION_ENUM,
        "<init>",
        parameters,
        "V",
    )
}
