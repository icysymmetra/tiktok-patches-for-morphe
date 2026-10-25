package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.commenttools.CaptionExpandFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #162, held to every declared build: the gate the caption's expand asks before it hands a
 * tap to TikTok's search panel ("Discover more with Tako", "Ask Tako").
 *
 * <p>The patch puts the Tako filter between the gate's move and its branch, so nothing may jump
 * onto that branch, and the gate's no has to land on the same in-place expand the panel's own no
 * lands on, with nothing but constants and moves in between, or a skipped panel would leave the
 * tap doing something else (or nothing).
 */
class CaptionSearchPanelAnchorsTest {
    @Test
    fun `the caption's search panel gate is one method on every declared build, answered before its branch`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> CaptionSearchPanelFingerprint.takes(method, classDef) }.toList()
            assertEquals("${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val method = taken.single().second
            val expand = build.methods.filter { (classDef, candidate) -> CaptionExpandFingerprint.takes(candidate, classDef) }.toList()
            assertEquals("the caption Comment tools hands over", 1, expand.size)
            assertEquals("the gate sits in the caption's expand", expand.single().second.toString(), method.toString())
            assertFalse("the expand is static", AccessFlags.STATIC.isSet(method.accessFlags))

            val move = method.captionSearchPanelGate()
            assertNotNull("the gate before the search panel is gone", move)
            val instructions = method.implementation!!.instructions.toList()
            val gate = (instructions[move!! - 1] as ReferenceInstruction).reference as MethodReference
            assertEquals("a static no-argument boolean", "Z", gate.returnType)
            assertTrue(gate.parameterTypes.isEmpty())
            assertFalse("something jumps onto the branch, past the filter",
                move + 1 in method.implementation!!.targetIndexes())

            val open = instructions.indices.single { instructions[it].opensCaptionSearchPanel() }
            val panelMove = instructions[open + 1]
            val panelBranch = instructions[open + 2]
            assertEquals("the panel's answer is moved", Opcode.MOVE_RESULT, panelMove.opcode)
            assertEquals("and tested for no", Opcode.IF_EQZ, panelBranch.opcode)
            assertEquals((panelMove as OneRegisterInstruction).registerA, (panelBranch as OneRegisterInstruction).registerA)
            val addresses = instructions.codeAddresses()
            val gateNo = instructions.branchTarget(move + 1, addresses)!!
            val panelNo = instructions.branchTarget(open + 2, addresses)!!
            assertTrue("the gate's no lands before the panel call", gateNo > open)
            assertTrue("the gate's no lands past the panel's", gateNo <= panelNo)
            for (index in gateNo until panelNo) {
                val step = instructions[index]
                assertFalse("the gate's no runs $step before the in-place expand",
                    (step as? ReferenceInstruction)?.reference is MethodReference || step is OffsetInstruction)
            }
        }
    }

    @Test
    fun `Feed filter answers the gate through the Tako filter`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/feedfilter/FeedFilterPatch.kt").readText()
        assertTrue("the gate is not answered", source.contains("CaptionSearchPanelFingerprint.method.apply"))
        assertTrue("the answer is not the Tako filter's", source.contains("allowCaptionSearchPanel(Z)Z"))
    }

    /** Every instruction index a branch, a switch case or a catch handler lands on. */
    private fun MethodImplementation.targetIndexes(): Set<Int> {
        val instructions = instructions.toList()
        val addresses = instructions.codeAddresses()
        fun indexAt(address: Int) = addresses.indexOfFirst { it == address }.takeIf { it in instructions.indices }
        val targets = mutableSetOf<Int>()
        instructions.forEachIndexed { index, instruction ->
            if (instruction !is OffsetInstruction) return@forEachIndexed
            val target = addresses[index] + instruction.codeOffset
            if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                val payload = indexAt(target)?.let { instructions[it] } as? SwitchPayload ?: return@forEachIndexed
                payload.switchElements.forEach { element -> indexAt(addresses[index] + element.offset)?.let(targets::add) }
            } else {
                indexAt(target)?.let(targets::add)
            }
        }
        tryBlocks.forEach { block -> block.exceptionHandlers.forEach { handler -> indexAt(handler.handlerCodeAddress)?.let(targets::add) } }
        return targets
    }

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
