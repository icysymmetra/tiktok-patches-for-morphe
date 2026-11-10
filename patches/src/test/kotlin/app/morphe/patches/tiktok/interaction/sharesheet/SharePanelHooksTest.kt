package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SharePanelHooksTest {
    @Test
    fun `both native inflation paths report their typed row without changing the frame or branch targets`() {
        for (id in SHARE_ACTION_IDS) {
            val method = panel(id)
            assertEquals(method, resolveSharePanel(listOf(owner(method))))
            val before = method.implementation!!.instructions.toList()
            val branches = before.filterIsInstance<OffsetInstruction>().map { branch ->
                val instruction = before.single { it === branch }
                val target = before.single { it.location.codeAddress == instruction.location.codeAddress + branch.codeOffset }
                branch to target
            }
            assertEquals(listOf(5, 0), method.sharePanelRows().map { it.register })

            method.notifyPanelBound()

            val after = method.implementation!!.instructions.toList()
            assertEquals(20, method.implementation!!.registerCount)
            assertEquals(before.size + 2, after.size)
            assertEquals(before, after.filter { it.getReference<MethodReference>()?.name != "panelBound" })
            val hooks = after.filter { it.getReference<MethodReference>()?.name == "panelBound" }
            assertEquals(listOf(5, 0), hooks.map { (it as RegisterRangeInstruction).startRegister })
            hooks.forEach { hook ->
                assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                assertEquals(1, (hook as RegisterRangeInstruction).registerCount)
                assertEquals(listOf("Landroid/view/View;"), hook.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
                assertEquals(Opcode.CHECK_CAST, after[after.indexOf(hook) - 1].opcode)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, after[after.indexOf(hook) - 2].opcode)
            }
            branches.forEach { (branch, target) ->
                val source = after.single { it === branch }
                assertSame(target, after.single { it.location.codeAddress == source.location.codeAddress + branch.codeOffset })
            }
        }
    }

    @Test
    fun `a high result register is passed by range without borrowing a parameter`() {
        val method = panel(0x7f0a013a, high = true)
        method.notifyPanelBound()
        assertEquals(32, method.implementation!!.registerCount)
        val calls = method.implementation!!.instructions.filter { it.getReference<MethodReference>()?.name == "panelBound" }
        assertEquals(listOf(24, 0), calls.map { (it as RegisterRangeInstruction).startRegister })
    }

    @Test
    fun `an ambiguous native panel or wrong native owner fails closed`() {
        val first = panel(0x7f0a013a)
        val second = panel(0x7f0a013a, type = "Lfixture/OtherPanel;")
        assertThrows(Exception::class.java) { resolveSharePanel(listOf(owner(first), owner(second))) }
        assertThrows(Exception::class.java) { resolveSharePanel(listOf(owner(first, "Ljava/lang/Object;"))) }
    }

    @Test
    fun `a branch bypassing the resource constant cannot turn a lookalike into an action row`() {
        val method = panel(0x7f0a013a, entry = "if-eqz v1, :horizontal")
        unchangedFailure(method)
    }

    @Test
    fun `a branch entering the cast without the lookup result prevents every insertion`() {
        val method = panel(0x7f0a013a, beforeLookup = "if-eqz v1, :verticalCast")
        unchangedFailure(method)
    }

    @Test
    fun `a missing path changed type or overwritten action id cannot partially instrument the other path`() {
        unchangedFailure(panel(0x7f0a013a, secondType = "Landroid/view/View;"))
        unchangedFailure(panel(0x7f0a013a, beforeLookup = "const/4 v5, 0x0"))
        unchangedFailure(panel(0x7f0a013b))
    }

    private fun unchangedFailure(method: MutableMethod) {
        val before = method.implementation!!.instructions.toList()
        assertThrows(Exception::class.java) { method.notifyPanelBound() }
        assertEquals(before, method.implementation!!.instructions.toList())
    }

    private fun owner(method: MutableMethod, superclass: String = "Landroid/widget/FrameLayout;"): ClassDef =
        ImmutableClassDef(method.definingClass, AccessFlags.PUBLIC.value, superclass, emptyList(),
            null, emptyList(), emptyList(), listOf(method))

    private fun panel(
        id: Int,
        high: Boolean = false,
        type: String = "Lfixture/NativePanel;",
        entry: String = "",
        beforeLookup: String = "",
        secondType: String = "Landroidx/recyclerview/widget/RecyclerView;",
    ): MutableMethod {
        val receiver = if (high) 22 else 4
        val idRegister = if (high) 23 else 5
        val vertical = if (high) 24 else 5
        val lookup = if (high) "invoke-virtual/range { v22 .. v23 }" else "invoke-virtual { v4, v5 }"
        return MutableMethod(ImmutableMethod(type, "onAttachedToWindow", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(if (high) 32 else 20, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, """
                move-object/from16 v$receiver, p0
                $entry
                const v$idRegister, 0x${id.toString(16)}
                const/4 v1, 0x0
                if-eqz v1, :horizontal
                $beforeLookup
                $lookup, Landroid/view/View;->findViewById(I)Landroid/view/View;
                move-result-object v0
                if-nez v0, :verticalExisting
                const/4 v0, 0x0
                :verticalExisting
                $lookup, Landroid/view/View;->findViewById(I)Landroid/view/View;
                move-result-object v$vertical
                :verticalCast
                check-cast v$vertical, Landroidx/recyclerview/widget/RecyclerView;
                const-string v1, "share_panel_action"
                return-void
                :horizontal
                $lookup, Landroid/view/View;->findViewById(I)Landroid/view/View;
                move-result-object v0
                if-nez v0, :horizontalExisting
                const/4 v0, 0x0
                :horizontalExisting
                $lookup, Landroid/view/View;->findViewById(I)Landroid/view/View;
                move-result-object v0
                check-cast v0, $secondType
                return-void
            """)
        }
    }
}
