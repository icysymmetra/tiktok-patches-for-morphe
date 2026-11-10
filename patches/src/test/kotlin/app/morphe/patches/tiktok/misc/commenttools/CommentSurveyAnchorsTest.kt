package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Hide comment surveys answers, held to every declared build.
 *
 * <p>A comment list asks one static config getter for the survey to show. It reads the survey
 * arrays the server's comment_survey setting filled and answers null when they're empty, which is
 * what every account without a survey gets. The patch answers that same null first while the switch
 * is on, so the callers need nothing they don't already handle.
 */
class CommentSurveyAnchorsTest {
    @Test
    fun `the survey config is one static getter on every declared build, and null is already one of its answers`() {
        Fixtures.forEachDeclared { apk ->
            val taken = methodsOf(apk).filter { (classDef, method) -> CommentSurveyConfigFingerprint.takes(method, classDef) }.toList()
            assertEquals(taken.map { "${it.first.type}->${it.second.name}" }.toString(), 1, taken.size)
            val getter = taken.single().second
            assertTrue("the getter is no longer static", AccessFlags.STATIC.isSet(getter.accessFlags))
            assertTrue("the getter has no register for the answer", getter.implementation!!.registerCount >= 1)

            val instructions = getter.implementation!!.instructions.toList()
            val answersNull = instructions.zipWithNext().any { (load, ret) ->
                load.opcode == Opcode.CONST_4 && (load as NarrowLiteralInstruction).narrowLiteral == 0 &&
                    ret.opcode == Opcode.RETURN_OBJECT &&
                    (ret as OneRegisterInstruction).registerA == (load as OneRegisterInstruction).registerA
            }
            assertTrue("the getter never answers null, so its callers may not expect it", answersNull)
            val readsSurveys = instructions.any { instruction ->
                instruction.opcode == Opcode.SGET_OBJECT &&
                    ((instruction as ReferenceInstruction).reference as FieldReference).type == "[$COMMENT_SURVEY_ITEM"
            }
            assertTrue("the getter no longer reads the survey arrays", readsSurveys)
        }
    }

    @Test
    fun `the patch answers no survey first while the switch is on`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/commenttools/CommentToolsPatch.kt").readText()
        assertTrue("the getter is not hooked", source.contains("val method = CommentSurveyConfigFingerprint.method"))
        assertTrue("the getter does not ask the switch first", source.contains(
            "method.guardAtEntry(\n                        \"Comment tools\",\n" +
                "                        \"invoke-static {}, \$COMMENT_SURVEY_CLASS_DESCRIPTOR->hide()Z\",\n" +
                "                        \"const/4 v0, 0x0\\nreturn-object v0\","))
    }

    /** Every method of one build, walked on each ask and never held (the dex has millions). */
    private fun methodsOf(apk: File): Sequence<Pair<ClassDef, Method>> {
        val byType = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
        }
        return byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
