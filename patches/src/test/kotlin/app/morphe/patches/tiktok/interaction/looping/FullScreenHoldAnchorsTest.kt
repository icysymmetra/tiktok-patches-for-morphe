package app.morphe.patches.tiktok.interaction.looping

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Stay on the video in full screen hooks, held to each declared build: the full-screen
 * viewer's autoplay hint (a real name) has one method that takes nothing and returns a boolean,
 * that is the gate its video status handler asks before it moves to the next video, and the gate
 * has a register below p0 for the hook to answer with.
 */
class FullScreenHoldAnchorsTest {
    @Test
    fun `the full screen viewer asks one gate before it moves on`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            var hint: ClassDef? = null
            for (entry in container.dexEntryNames) {
                hint = hint ?: container.getEntry(entry)!!.dexFile.classes
                    .firstOrNull { it.type == LANDSCAPE_AUTOPLAY_HINT }
            }
            assertNotNull("no $LANDSCAPE_AUTOPLAY_HINT", hint)
            val gates = hint!!.methods.filter {
                it.returnType == "Z" && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
            }
            assertEquals("gates on the autoplay hint: ${gates.map { it.name }}", 1, gates.size)
            val gate = gates.single()
            val registers = gate.implementation!!.registerCount
            assertTrue("the gate has $registers register(s), none free below p0", registers >= 2)

            val handler = hint.methods.singleOrNull { it.name == "onPlayerControllerVideoStatusEvent" }
            assertNotNull("the autoplay hint has no onPlayerControllerVideoStatusEvent", handler)
            val asks = handler!!.implementation!!.instructions.count { instruction ->
                val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                called != null && called.definingClass == LANDSCAPE_AUTOPLAY_HINT && called.name == gate.name &&
                    called.parameterTypes.isEmpty() && called.returnType == "Z"
            }
            assertTrue("the status handler never asks the gate ${gate.name}", asks > 0)
        }
    }
}
