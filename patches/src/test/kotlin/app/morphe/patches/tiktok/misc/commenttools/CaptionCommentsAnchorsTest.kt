package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where "more" opens a video's comments with its caption on top, held to every fixture.
 *
 * <p>The feed caption's expand and collapse is one method, reached from the "more" button and a
 * tap on the caption text. The patch asks the extension at its first instruction, so nothing may
 * jump there past the ask. TikTok's comment list decides whether to start with the caption as a
 * row by reading its opener's shouldShowVideoDesc through one null-safe check, and the patch puts
 * the extension's word on that answer right where it's moved, so neither path may join later.
 */
class CaptionCommentsAnchorsTest {
    @Test
    fun `the caption's expand is one method on every fixture, its arguments in p0 to p3 and nothing jumping to its start`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> CaptionExpandFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val method = taken.single().second
            assertFalse("${apk.name}: the expand is static", AccessFlags.STATIC.isSet(method.accessFlags))
            assertEquals("${apk.name}: p0 to p3 are the caption, the state, the name and the way in", 4, method.parameterRegisters())
            val implementation = method.implementation!!
            assertTrue("${apk.name}: no local for the answer", implementation.registerCount - 4 >= 1)
            assertFalse("${apk.name}: something jumps to the first instruction, past the ask", 0 in implementation.targetIndexes())
        }
    }

    @Test
    fun `the comment list's caption row check is one method on every fixture, answered where both paths have joined`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> CaptionRowFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val method = taken.single().second
            assertFalse("${apk.name}: the check is static, so p1 isn't the video", AccessFlags.STATIC.isSet(method.accessFlags))
            val move = method.captionRowAnswer()
            assertNotNull("${apk.name}: the answer's move is gone", move)
            assertFalse("${apk.name}: something jumps in after the answer, past the extension's word",
                move!! + 1 in method.implementation!!.targetIndexes())
        }
    }

    @Test
    fun `Comment tools hands both over and brings Double-tap controls for the comment press`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/commenttools/CommentToolsPatch.kt").readText()
        assertTrue("the caption's expand is not handed over", source.contains("CaptionExpandFingerprint.method.resolveCaptionOpensComments()"))
        assertTrue("the caption row is not answered", source.contains("{ CaptionRowFingerprint.method.resolveCaptionRow() }"))
        val dependencies = Regex("""dependsOn\(([^)]*)\)""").find(source)?.groupValues?.get(1).orEmpty()
            .split(',').map { it.trim() }
        assertTrue("the comment press comes from Double-tap controls: $dependencies", "doubleTapPatch" in dependencies)
    }

    /** Registers the method's parameters take, its receiver included. */
    private fun Method.parameterRegisters(): Int =
        (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
            parameterTypes.sumOf { type -> if (type.toString() == "J" || type.toString() == "D") 2 else 1 }

    /** Every instruction index a branch, a switch case or a catch handler lands on. */
    private fun MethodImplementation.targetIndexes(): Set<Int> {
        val instructions = instructions.toList()
        val addresses = IntArray(instructions.size + 1)
        for (index in instructions.indices) addresses[index + 1] = addresses[index] + instructions[index].codeUnits
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
