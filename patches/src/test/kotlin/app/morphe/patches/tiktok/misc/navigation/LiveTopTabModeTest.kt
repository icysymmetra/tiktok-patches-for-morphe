package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top LIVE mode read must pass through the filter before every corner-button comparison.
 * 47.0.3 compares the mode once, right after reading it; 47.1.3 reads it at the top of the method
 * and compares it twice, so the hook takes the read, not the first comparison.
 */
class LiveTopTabModeTest {
    @Test
    fun `the button mode read has one anchor on every fixture and is answered before every comparison`() {
        for (apk in Fixtures.apks()) {
            val mode = modeMethod(apk)
            val read = mode.liveTopTabModeRead()
            assertTrue("${apk.name}: the mode read has another shape", read != null)
            read!!
            val comparisons = mode.implementation!!.instructions.count {
                it.opcode == Opcode.CONST_STRING && it.getReference<StringReference>()?.string in LIVE_TOP_TAB_MODES
            }
            assertTrue("${apk.name}: $comparisons comparisons", comparisons >= 2)

            val method = MutableMethod(mode)
            method.answerLiveTopTabMode()
            val instructions = method.implementation!!.instructions.toList()
            val call = instructions[read.index + 1]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("liveTopTabMode", call.getReference<MethodReference>()!!.name)
            assertEquals(read.register, (call as RegisterRangeInstruction).startRegister)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[read.index + 2].opcode)
            assertEquals(read.register, (instructions[read.index + 2] as OneRegisterInstruction).registerA)
            // Read again, every comparison of the hooked method reaches the filter's answer.
            assertEquals("${apk.name}: a comparison reads around the hook", read.index + 2, method.liveTopTabModeRead()?.index)
            println("${apk.name}: the mode read at ${read.index} feeds $comparisons comparisons")
        }
    }

    /** The positive control: the shape check refuses a mode written again between two comparisons. */
    @Test
    fun `a mode written again before the last comparison is refused`() {
        for (apk in Fixtures.declared()) {
            val mode = modeMethod(apk)
            val read = mode.liveTopTabModeRead()!!
            val instructions = mode.implementation!!.instructions.toList()
            val firstCheck = instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING && it.getReference<StringReference>()?.string in LIVE_TOP_TAB_MODES
            } + 1
            val rewritten = MutableMethod(mode)
            rewritten.addInstructions(firstCheck + 2, "const/4 v${read.register}, 0x0")
            assertNull("${apk.name}: a rewrite between the checks passed", rewritten.liveTopTabModeRead())
        }
    }

    private fun modeMethod(apk: File): Method {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val button = container.dexEntryNames.asSequence().flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.asSequence()
        }.firstOrNull { it.type == LIVE_ICON_GENERATOR } ?: error("${apk.name}: no LiveIconGenerator")
        val modes = button.methods.filter { LiveTopTabModeFingerprint.takes(it, button) }
        assertEquals("${apk.name}: ${modes.map { it.name }}", 1, modes.size)
        return modes.single()
    }
}
