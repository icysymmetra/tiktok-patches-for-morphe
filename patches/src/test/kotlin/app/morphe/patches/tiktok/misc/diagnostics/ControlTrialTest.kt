package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.*
import org.junit.Test

class ControlTrialTest {
    private fun classes() = listOf("classes18.dex", "classes2.dex").flatMap {
        DexFileFactory.loadDexEntry(Fixtures.apkOf("47.4.3"), it, true, Opcodes.getDefault()).dexFile.classes
    }
    @Test fun `exact target resolves config key and native contract`() {
        val targets = heapObservationTargets(classes())
        assertEquals(listOf("D", "J", "J"), targets.wrapper.parameterTypes.map { it.toString() })
        assertEquals(listOf("J", "J", "D"), targets.native.parameterTypes.map { it.toString() })
        assertEquals("Z", targets.gate.returnType)
        assertEquals("Ljava/lang/Object;", targets.provider.returnType)
    }
    @Test fun `missing config provider refuses all observations`() {
        val classes = classes(); val targets = heapObservationTargets(classes)
        assertThrows(PatchException::class.java) {
            heapObservationTargets(classes.filter { it.type != targets.provider.definingClass })
        }
    }
    @Test fun `a second native caller prevents partial instrumentation`() {
        val classes = classes(); val targets = heapObservationTargets(classes)
        val duplicate = ImmutableClassDef("Ltest/Duplicate;", 1, "Ljava/lang/Object;", null, null, null, null, listOf(targets.wrapper))
        assertThrows(PatchException::class.java) { heapObservationTargets(classes + duplicate) }
    }
}
