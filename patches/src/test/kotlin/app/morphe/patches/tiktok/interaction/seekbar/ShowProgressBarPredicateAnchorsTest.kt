package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CANNOT_SHOW_PROGRESS_BAR_LOG = "can not show seekbar, state: 4, can not show progressbar"

/**
 * The check Show the progress bar answers yes, held to every fixture (#134): exactly one method in
 * the build takes its fingerprint, that method reads `VideoControl.showProgressBar` once, it has the
 * local the injection writes, and it is the call the seek bar's reasons method makes right above
 * its "state: 4, can not show progressbar" line. On 47.1.4 the fingerprint's old shape fitted
 * about twenty methods and the patcher took the first, TikTok's ad-traffic check, so every video
 * read as an ad and the seek bar's own check was never answered.
 */
class ShowProgressBarPredicateAnchorsTest {
    @Test
    fun `one method per fixture takes the predicate fingerprint and it is the seek bar's state 4 check`() {
        for (apk in Fixtures.apks()) {
            val predicates = ArrayList<Pair<ClassDef, Method>>()
            val reasons = ArrayList<Method>()
            // One walk of the build for both, since it holds millions of methods.
            for ((classDef, method) in Build(apk).methods) {
                if (ShouldShowProgressBarFingerprint.takes(method, classDef)) predicates += classDef to method
                if (SeekbarGateLogFingerprint.takes(method, classDef)) reasons += method
            }
            assertEquals("${apk.name}: ${predicates.map { "${it.first.type}->${it.second.name}" }}", 1, predicates.size)
            assertEquals("${apk.name}: ${reasons.map { "${it.definingClass}->${it.name}" }}", 1, reasons.size)
            val predicate = predicates.single().second

            val reads = predicate.implementation!!.instructions.count {
                it.opcode == Opcode.IGET && it.getReference<FieldReference>()?.let { field ->
                    field.definingClass == VIDEO_CONTROL_CLASS && field.name == SHOW_PROGRESS_BAR_FIELD
                } == true
            }
            assertEquals("${apk.name}: the predicate reads showProgressBar $reads times", 1, reads)

            val locals = predicate.implementation!!.registerCount - predicate.parameterTypes.size
            assertTrue("${apk.name}: the predicate has $locals local registers, the injection writes one", locals >= 1)

            val gate = reasons.single().gateBefore(CANNOT_SHOW_PROGRESS_BAR_LOG)
            assertEquals("${apk.name}: the seek bar's state 4 check is not the predicate", key(gate), key(predicate))
        }
    }

    @Test
    fun `the patch answers the predicate`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt").readText()
        assertTrue("the predicate is not answered", source.contains("ShouldShowProgressBarFingerprint.method.addInstructions("))
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
    }
}
