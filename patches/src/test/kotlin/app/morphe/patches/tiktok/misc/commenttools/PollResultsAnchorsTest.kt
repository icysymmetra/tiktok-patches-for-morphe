package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Show poll results before voting answers, held to every fixture.
 *
 * <p>A creator's comment poll arrives with the comment page, counts included, whether or not the
 * reader has voted (seen on the S22 with 47.0.3). TikTok's poll row asks one static method whether
 * to draw the bars and percentages: yes once its own ended check says the poll is over, or once
 * the reader's vote is zero or more. The patch answers yes at the start of that method while the
 * switch is on. The row wires a tap on each answer without asking it, so voting stays as it was.
 */
class PollResultsAnchorsTest {
    @Test
    fun `the poll row's result check is one method on every fixture, from the ended check and the reader's vote`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> PollShowsResultsFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val check = taken.single().second
            val instructions = check.implementation!!.instructions.toList()
            assertTrue("${apk.name}: the check has no local register for the answer",
                check.implementation!!.registerCount - 1 >= 1)
            assertTrue("${apk.name}: the reader's vote is not compared with zero",
                instructions.any { it.opcode == Opcode.IF_LTZ })

            val row = build.byType.getValue(POLL_CELL)
            val ended = instructions.firstNotNullOf { it.call()?.takeIf { call -> call.definingClass == POLL_CELL } }
            val endedMethod = row.methods.single { it.name == ended.name && it.parameterTypes.map(CharSequence::toString) == listOf(POLL_INFO) }
            assertTrue("${apk.name}: the ended check no longer reads the poll's status",
                endedMethod.implementation!!.instructions.any { it.field()?.let { f -> f.definingClass == POLL_INFO && f.name == "pollStatus" } == true })

            val askers = row.methods.filter { method -> method.implementation?.instructions?.any { it.calls(check) } == true }
            assertTrue("${apk.name}: the row asks the check in ${askers.map { it.name }}", askers.size >= 3)
            val drawsPercent = askers.any { method ->
                method.implementation!!.instructions.any { it.call()?.name == "setPercentAlpha" }
            }
            assertTrue("${apk.name}: no asker draws the percentages with the answer", drawsPercent)
            val wiresTaps = askers.any { method ->
                method.implementation!!.instructions.any { instruction ->
                    instruction.call()?.parameterTypes?.any { it.toString() == "Landroid/view/View\$OnClickListener;" } == true
                }
            }
            assertTrue("${apk.name}: the bind no longer wires the answers' taps", wiresTaps)
        }
    }

    @Test
    fun `an answer's tap votes through a path that never asks the result check, on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val check = build.methods.single { (classDef, method) -> PollShowsResultsFingerprint.takes(method, classDef) }.second
            val row = build.byType.getValue(POLL_CELL)
            // Each answer's click listener hands the row a (item, index) vote. Forcing the result
            // check must leave that path alone, or answering would stop once results show.
            val votes = build.methods.filter { (classDef, method) ->
                // 46.2.3 merges the listeners into one class with static onClick$0 bridges.
                method.name.startsWith("onClick") && "Landroid/view/View\$OnClickListener;" in classDef.interfaces
            }.flatMap { (_, method) ->
                method.implementation!!.instructions.mapNotNull { instruction ->
                    instruction.call()?.takeIf { call ->
                        call.definingClass == POLL_CELL && call.returnType == "V" &&
                            call.parameterTypes.size == 2 && call.parameterTypes[1].toString() == "I"
                    }?.name
                }
            }.toSet()
            assertTrue("${apk.name}: no answer tap reaches the row", votes.isNotEmpty())
            votes.forEach { name ->
                val vote = row.methods.single { it.name == name && it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == "I" }
                assertTrue("${apk.name}: the vote $name no longer checks TikTok's own poll-ended rule",
                    vote.implementation!!.instructions.any { it.call()?.let { call -> call.definingClass == POLL_CELL && call.returnType == "Z" } == true })
                assertTrue("${apk.name}: the vote $name asks the result check the switch answers",
                    vote.implementation!!.instructions.none { it.calls(check) })
            }
        }
    }

    @Test
    fun `the patch answers yes first while the switch is on`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/commenttools/CommentToolsPatch.kt").readText()
        assertTrue("the check is not hooked", source.contains("val method = PollShowsResultsFingerprint.method"))
        assertTrue("the check does not ask the switch first", source.contains(
            "method.guardAtEntry(\n                        \"Comment tools\",\n" +
                "                        \"invoke-static {}, \$POLL_RESULTS_CLASS_DESCRIPTOR->showBeforeVoting()Z\",\n" +
                "                        \"return v0\","))
    }

    private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.calls(target: Method): Boolean = call()?.let {
        it.definingClass == target.definingClass && it.name == target.name &&
            it.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)
    } == true

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
