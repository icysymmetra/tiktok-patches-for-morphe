package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikTok's reasons for keeping the seek bar off a video, held to every fixture: one method logs
 * them, its two experiment lines each sit under a static gate that takes the video, the gates
 * are two different methods, and the gate the patch guards at its entry has a local to guard
 * with. 47.0.3 is also held to the shape the fix was written from: the inverse gate excepts paid
 * content, and the can-drag gate reads the draggable flag exactly once.
 */
class SeekbarGatesAnchorsTest {
    @Test
    fun `the reasons method is one per fixture and both experiment gates resolve from their log lines`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> SeekbarGateLogFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val reasons = taken.single().second
            val inverse = reasons.gateBefore(INVERSE_EXPERIMENT_LOG)
            val drag = reasons.gateBefore(CANNOT_DRAG_LOG)
            assertNotEquals("${apk.name}: the two lines name one gate", key(inverse), key(drag))

            val inverseGate = build.method(inverse)
            assertTrue("${apk.name}: the inverse gate is not static", AccessFlags.STATIC.isSet(inverseGate.accessFlags))
            val locals = inverseGate.implementation!!.registerCount - inverseGate.parameterTypes.size
            assertTrue("${apk.name}: the inverse gate has $locals local registers, the guard writes one", locals >= 1)

            val dragGate = build.method(drag)
            val reads = dragGate.implementation!!.instructions.count {
                it.opcode == Opcode.IGET && it.getReference<FieldReference>()?.name == DRAFT_PROGRESS_BAR
            }
            assertTrue("${apk.name}: the can-drag gate reads the draggable flag $reads times", reads <= 1)
            if (Fixtures.versionOf(apk) in Fixtures.declaredVersions()) {
                assertEquals("${apk.name}: the can-drag gate no longer reads the draggable flag", 1, reads)
                val exceptsPaidContent = inverseGate.implementation!!.instructions.any {
                    it.opcode == Opcode.IGET_BOOLEAN && it.getReference<FieldReference>()?.name == "isPaidContent"
                }
                assertTrue("${apk.name}: the inverse gate no longer excepts paid content", exceptsPaidContent)
            }
        }
    }

    @Test
    fun `the patch answers both gates`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt").readText()
        assertTrue("the inverse experiment is not answered", source.contains(".named(inverse).answerInverseExperiment()"))
        assertTrue("the draggable flag is not answered", source.contains(".named(drag).answerDraggable()"))
    }

    private fun key(reference: MethodReference) =
        "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }

        fun method(reference: MethodReference): Method {
            val classDef = byType[reference.definingClass] ?: error("${reference.definingClass} is not in this build")
            return classDef.methods.firstOrNull {
                it.name == reference.name && it.returnType == reference.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
            } ?: error("${reference.definingClass} has no ${reference.name}")
        }
    }
}
