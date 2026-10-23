package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ROW = "Landroid/widget/LinearLayout;"
private const val VIEW = "Landroid/view/View;"
private const val VISIBILITY = "LX/ViewUtils;->setVisibility(ILandroid/view/View;)V"
private const val EXTENSION =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

class QuickCommentReactionRowTest {
    private val rowField = ImmutableFieldReference(QUICK_COMMENT_ASSEM, "obfuscated", ROW)

    @Test
    fun `the row selector follows both native visibility writes without naming the utility`() {
        val method = bindMethod()

        val writes = method.quickCommentVisibilityWrites(rowField)

        assertEquals(2, writes.size)
        assertEquals(listOf(1, 4), writes.map { it.visibilityRegister })
    }

    @Test
    fun `every selected native write is routed through the setting`() {
        val method = bindMethod()

        assertEquals(2, method.hookQuickCommentVisibility(rowField))

        val instructions = method.implementation!!.instructions.toList()
        val overrides = instructions.withIndex().filter { indexed ->
            indexed.value.getReference<MethodReference>()?.let { target ->
                target.definingClass == EXTENSION &&
                    target.name == "hideQuickCommentReactionsVisibility"
            } == true
        }
        assertEquals(2, overrides.size)
        overrides.forEach { override ->
            val moveResult = instructions[override.index + 1]
            val native = instructions[override.index + 2]
            val target = override.value.getReference<MethodReference>()
            assertEquals(Opcode.INVOKE_STATIC, override.value.opcode)
            assertEquals(EXTENSION, target?.definingClass)
            assertEquals("hideQuickCommentReactionsVisibility", target?.name)
            assertEquals(listOf("I"), target?.parameterTypes?.map(CharSequence::toString))
            assertEquals("I", target?.returnType)
            assertEquals(VISIBILITY, native.getReference<MethodReference>()?.toString())
            assertEquals(
                (native as FiveRegisterInstruction).registerC,
                (override.value as FiveRegisterInstruction).registerC,
            )
            assertEquals(Opcode.MOVE_RESULT, moveResult.opcode)
            assertEquals(
                native.registerC,
                (moveResult as OneRegisterInstruction).registerA,
            )
        }
    }

    @Test
    fun `a partial row contract fails instead of reporting a silent patch`() {
        val method = methodWithBody(
            """
                iget-object v2, p0, $QUICK_COMMENT_ASSEM->obfuscated:$ROW
                nop
                invoke-static { v1, v2 }, $VISIBILITY
            """,
        )

        val error = assertThrows(PatchException::class.java) {
            method.hookQuickCommentVisibility(rowField)
        }

        assertTrue(error.message!!.contains("expected at least two visibility writes"))
    }

    private fun bindMethod() = methodWithBody(
        """
            iget-object v2, p0, $QUICK_COMMENT_ASSEM->obfuscated:$ROW
            nop
            invoke-static { v1, v2 }, $VISIBILITY
            iget-object v3, p0, $QUICK_COMMENT_ASSEM->other:$VIEW
            nop
            invoke-static { v0, v3 }, $VISIBILITY
            iget-object v2, p0, $QUICK_COMMENT_ASSEM->obfuscated:$ROW
            nop
            invoke-static { v4, v2 }, $VISIBILITY
        """,
    )

    private fun methodWithBody(body: String) = MutableMethod(
        ImmutableMethod(
            QUICK_COMMENT_ASSEM,
            "onBind",
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(
                6,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ),
    ).apply {
        addInstructions(0, body)
    }
}
