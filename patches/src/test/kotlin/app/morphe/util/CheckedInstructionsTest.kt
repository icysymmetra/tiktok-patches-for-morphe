package app.morphe.util

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patcher's inline smali leaves out an instruction whose register doesn't fit its operand
 * without a word; the checked versions of its add functions turn that into a stopped patch.
 */
class CheckedInstructionsTest {
    @Test
    fun `the append overload keeps the original body before its inserted instructions`() {
        val host = method(4)
        host.addInstructions("const/4 v0, 0x1")
        assertEquals(listOf(Opcode.RETURN_VOID, Opcode.CONST_4), host.implementation!!.instructions.map { it.opcode })
    }

    /** An instance method `hook()V` with [registers] registers, so p0 sits in the last one. */
    private fun method(registers: Int): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lapp/Host;", "hook", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(registers, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        ),
    )

    @Test
    fun `a p register the operand can hold goes in`() {
        val host = method(4)
        host.addInstructions(0, "iget-object v0, p0, Lapp/Host;->field:Ljava/lang/Object;")
        assertEquals(2, host.implementation!!.instructions.size)
    }

    @Test
    fun `a p register above v15 in a four-bit operand stops the patch`() {
        val read = assertThrows(PatchException::class.java) {
            method(20).addInstructions(0, "iget-object v0, p0, Lapp/Host;->field:Ljava/lang/Object;")
        }
        assertTrue(read.message, read.message!!.contains("kept 0 of 1"))

        val call = assertThrows(PatchException::class.java) {
            method(20).addInstructions(
                0,
                """
                    invoke-static { p0 }, Lapp/Hook;->seen(Ljava/lang/Object;)V
                    return-void
                """,
            )
        }
        assertTrue(call.message, call.message!!.contains("kept 1 of 2"))
        assertTrue(call.message, call.message!!.contains("Host;->hook"))
    }

    @Test
    fun `this copied down first goes in whatever the register count`() {
        val host = method(20)
        host.addInstructionsWithLabels(
            0,
            """
                # this, copied into a register every operand can name
                move-object/from16 v0, p0
                :read
                iget-object v0, v0, Lapp/Host;->field:Ljava/lang/Object;
            """,
        )
        assertEquals(3, host.implementation!!.instructions.size)
    }

    /**
     * dexlib2 keeps a switch payload on a four-byte boundary, so an insert that moves it adds or
     * takes away a nop before it. Counting that nop refused four working patches on 47.0.3 and
     * 47.1.3 (Disable telemetry, the browser guard, Playback speed, Region spoof).
     */
    @Test
    fun `a payload's alignment nop is not counted as a kept or lost instruction`() {
        // packed-switch at 0 (three units), return-void at 3, its payload at 4: aligned as built.
        // One unit inserted at the top moves the payload to 5, and dexlib2 pads it with a nop.
        val host = MutableMethod(
            ImmutableMethod(
                "Lapp/Host;", "hook", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(
                    4,
                    listOf(
                        ImmutableInstruction31t(Opcode.PACKED_SWITCH, 0, 4),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 3))),
                    ),
                    null, null,
                ),
            ),
        )
        host.addInstruction(0, "const/4 v0, 0x0")
        val opcodes = host.implementation!!.instructions.map { it.opcode }
        assertTrue("dexlib2 no longer pads the payload here: $opcodes", Opcode.NOP in opcodes)
        assertEquals(Opcode.CONST_4, opcodes.first())
    }

    @Test
    fun `labels comments and annotating directives are not instructions`() {
        assertEquals(
            2,
            smaliInstructionCount(
                """

                    # a comment
                    :start
                    .line 12
                    const/4 v0, 0x1
                    if-eqz v0, :start  # trailing words
                """,
            ),
        )
        assertThrows(PatchException::class.java) { smaliInstructionCount(".packed-switch 0x0\n:a\n.end packed-switch") }
    }

    /** Every patch goes through the checked versions; the patcher's own are imported in one place. */
    @Test
    fun `no patch imports the patcher's unchecked add functions`() {
        val root = listOf("../$SOURCES", SOURCES).map(::File).firstOrNull { it.isDirectory }
        assertTrue("could not find the patch sources from ${File(".").absolutePath}", root != null)
        val unchecked = Regex("""^import app\.morphe\.patcher\.extensions\.InstructionExtensions\.(addInstruction|addInstructions|addInstructionsWithLabels)\b""", RegexOption.MULTILINE)
        val offenders = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "CheckedInstructions.kt" }
            .filter { unchecked.containsMatchIn(it.readText()) || "InstructionExtensions.*" in it.readText() }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals("patch files importing the unchecked add functions", emptyList<String>(), offenders)
    }

    private companion object {
        const val SOURCES = "patches/src/main/kotlin"
    }
}
