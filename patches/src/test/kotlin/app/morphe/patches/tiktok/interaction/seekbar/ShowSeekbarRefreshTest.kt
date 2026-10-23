package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private const val OWNER = "LX/SeekbarHost;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/seekbar/SeekbarPatch;"

class ShowSeekbarRefreshTest {
    @Test
    fun `each declared build's show type setter keeps the native equality contract`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val methods = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.flatMap { classDef ->
                    classDef.methods.filter { method ->
                        method.returnType == "V" &&
                            method.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                            method.implementation?.instructions?.any { instruction ->
                                (instruction as? ReferenceInstruction)?.reference.let { reference ->
                                    reference is StringReference &&
                                        reference.string == "seekbar show type change, change to:"
                                }
                            } == true
                    }
                }
            }
            assertEquals("unique show-type setter", 1, methods.size)
            val method = MutableMethod(methods.single())
            val typeRegister = method.implementation!!.registerCount - 1
            method.addInstructions(
                0,
                """
                    invoke-static/range {v$typeRegister .. v$typeRegister}, $EXTENSION->overrideSeekbarShowType(I)I
                    move-result v$typeRegister
                """,
            )

            method.hookSeekbarTypeRefresh()

            assertEquals(1, method.implementation!!.instructions.count { instruction ->
                instruction.getReference<MethodReference>()?.let { target ->
                    target.definingClass == EXTENSION && target.name == "forceSeekbarRefresh"
                } == true
            })
        }
    }

    @Test
    fun `refresh hook sits between the stored type read and native equality shortcut`() {
        val method = setter(
            """
                invoke-static {p1}, $EXTENSION->overrideSeekbarShowType(I)I
                move-result p1
                iget v0, p0, $OWNER->showType:I
                if-eq v0, p1, :same
                iput p1, p0, $OWNER->showType:I
                :same
                return-void
            """,
        )

        method.hookSeekbarTypeRefresh()

        val instructions = method.implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT,
                Opcode.IGET,
                Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT,
                Opcode.IF_EQ,
                Opcode.IPUT,
                Opcode.RETURN_VOID,
                Opcode.RETURN_VOID,
            ),
            instructions.map { it.opcode },
        )
        val call = instructions[3]
        val target = call.getReference<MethodReference>()
        assertEquals(EXTENSION, target?.definingClass)
        assertEquals("forceSeekbarRefresh", target?.name)
        assertEquals(listOf("I", "I"), target?.parameterTypes?.map(CharSequence::toString))
        assertEquals("I", target?.returnType)
        assertEquals(0, (call as FiveRegisterInstruction).registerC)
        assertEquals(2, call.registerD)
    }

    @Test
    fun `ambiguous stored type comparisons fail instead of patching the wrong field`() {
        val method = setter(
            """
                iget v0, p0, $OWNER->showType:I
                if-eq v0, p1, :first
                :first
                iget v0, p0, $OWNER->otherType:I
                if-eq v0, p1, :second
                :second
                return-void
            """,
        )

        assertThrows(PatchException::class.java) { method.hookSeekbarTypeRefresh() }
    }

    private fun setter(body: String) = MutableMethod(
        ImmutableMethod(
            OWNER,
            "setSeekBarShowType",
            listOf(ImmutableMethodParameter("I", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(
                3,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ),
    ).apply { addInstructions(0, body) }
}
