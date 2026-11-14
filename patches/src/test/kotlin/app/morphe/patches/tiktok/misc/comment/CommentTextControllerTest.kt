package app.morphe.patches.tiktok.misc.comment

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.commenttools.NativeCommentTextBindFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import org.junit.Assert.*
import org.junit.Test

class CommentTextControllerTest {
    @Test
    fun `the actual declared native constructor cancels before claiming its renderer`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { name ->
                container.getEntry(name)!!.dexFile.classes
            }
            val renderers = classes.filter { owner -> owner.methods.any { NativeCommentTextBindFingerprint.takes(it, owner) } }
            val wrappers = classes.filter { owner -> owner.methods.any { CommentTranslatedTextFingerprint.takes(it, owner) } }
            assertEquals(1, renderers.size)
            assertEquals(1, wrappers.size)
            val renderer = renderers.single()
            val wrapper = wrappers.single()
            val targets = commentTextController(wrapper, renderer)
            val constructor = MutableMethod(targets.constructor)
            val original = constructor.implementation!!.instructions.toList()
            val frame = constructor.implementation!!.registerCount
            constructor.claimCommentTextRenderer(renderer.type, targets.cancel)
            val after = constructor.implementation!!.instructions.toList()
            assertEquals(frame, constructor.implementation!!.registerCount)
            assertEquals(original.dropLast(1), after.take(original.size - 1))
            val added = after.drop(original.size - 1)
            assertEquals(listOf(Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL,
                Opcode.IPUT_OBJECT, Opcode.RETURN_VOID), added.map { it.opcode })
            assertEquals(added.dropLast(1).sumOf { it.codeUnits }, (added[0] as OffsetInstruction).codeOffset)
            assertEquals(added.subList(2, 4).sumOf { it.codeUnits }, (added[2] as OffsetInstruction).codeOffset)
            val read = added[1] as TwoRegisterInstruction
            val write = added[4] as TwoRegisterInstruction
            assertEquals(0, read.registerA)
            assertEquals(frame - 2, read.registerB)
            assertEquals(frame - 4, write.registerA)
            assertEquals(read.registerB, write.registerB)
            assertEquals((added[1] as ReferenceInstruction).reference, (added[4] as ReferenceInstruction).reference)
            assertEquals("${targets.cancel.definingClass}->${targets.cancel.name}()V",
                (added[3] as ReferenceInstruction).reference.toString())

            // Refuse shape changes before editing anything. A second renderer field
            // or cancellation entry would otherwise silently choose the wrong owner.
            val duplicateRenderer = ImmutableField(wrapper.type, "otherRenderer", renderer.type,
                AccessFlags.PUBLIC.value, null, null, null)
            assertThrows(PatchException::class.java) {
                commentTextController(object : ClassDef by wrapper {
                    override fun getFields(): Iterable<Field> = buildList { addAll(wrapper.fields); add(duplicateRenderer) }
                }, renderer)
            }
            assertThrows(PatchException::class.java) {
                commentTextController(object : ClassDef by wrapper {
                    override fun getMethods() = wrapper.methods.filter { it.name != targets.cancel.name }
                }, renderer)
            }
            val collision = ImmutableField(renderer.type, COMMENT_TEXT_CONTROLLER, wrapper.type,
                AccessFlags.PUBLIC.value, null, null, null)
            assertThrows(PatchException::class.java) {
                commentTextController(wrapper, object : ClassDef by renderer {
                    override fun getFields(): Iterable<Field> = buildList { addAll(renderer.fields); add(collision) }
                })
            }
            assertEquals(original.map { it.opcode }, targets.constructor.implementation!!.instructions.map { it.opcode })
        }
    }
}
