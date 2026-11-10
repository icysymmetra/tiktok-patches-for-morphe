package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What caption-in-comments writes into TikTok's methods, instruction by instruction, on methods
 * shaped like 47.0.3's: which extension call runs with which registers, where the caption's expand
 * goes on when the answer is no, and that every branch TikTok had still lands where it did.
 */
class CaptionCommentsHookTest {
    private val caption = "LX/09Gd;"
    private val list = "LX/0Gts;"
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    private val contextSource = "Lcom/ss/android/ugc/aweme/comment/model/CommentContextSource;"

    private fun method(owner: String, name: String, parameters: List<String>, registers: Int, smali: String, static: Boolean = false) =
        MutableMethod(ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).apply { addInstructionsWithLabels(0, smali) }

    private fun List<Instruction>.address(index: Int) = take(index).sumOf { it.codeUnits }

    private fun List<Instruction>.targetOf(index: Int): Int {
        val address = address(index) + (this[index] as OffsetInstruction).codeOffset
        return indices.first { address(it) == address }
    }

    private fun branches(before: List<Instruction>) =
        before.indices.filter { before[it] is OffsetInstruction }.associate { before[it] to before[before.targetOf(it)] }

    private fun assertBranchesKept(kept: Map<Instruction, Instruction>, after: List<Instruction>) {
        kept.forEach { (branch, target) ->
            assertSame("a branch moved off its target", target, after[after.targetOf(after.indexOfFirst { it === branch })])
        }
    }

    private fun assertExtensionCall(instruction: Instruction, name: String, parameters: List<String>, returnType: String) {
        val reference = instruction.getReference<MethodReference>()!!
        assertEquals(CAPTION_COMMENTS_CLASS_DESCRIPTOR, reference.definingClass)
        assertEquals(name, reference.name)
        assertEquals(parameters, reference.parameterTypes.map(CharSequence::toString))
        assertEquals(returnType, reference.returnType)
    }

    /** 47.0.3's expand opening: the caption's view model, its item and the video, then more. */
    private fun expand(static: Boolean = false) = method(
        caption, "LJZL", listOf("Z", "Ljava/lang/String;", POST_MODE_MECHANISM), 38, """
            move-object/from16 v0, ${if (static) "v35" else "p0"}
            iget-object v1, v0, $caption->LLJLLL:Ljava/lang/Object;
            if-eqz v1, :done
            move/from16 v1, ${if (static) "v35" else "p1"}
            :done
            return-void
        """, static,
    )

    @Test
    fun `the caption's expand asks first with p0 to p3 and goes on to TikTok's first instruction on no`() {
        val method = expand()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)

        method.resolveCaptionOpensComments()()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size + 4, after.size)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
        assertExtensionCall(after[0], "opensComments", listOf("Ljava/lang/Object;", "Z", "Ljava/lang/String;", "Ljava/lang/Object;"), "Z")
        val call = after[0] as RegisterRangeInstruction
        assertEquals("p0 of a 38-register frame with four in", 34, call.startRegister)
        assertEquals(4, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals(0, (after[2] as OneRegisterInstruction).registerA)
        assertSame("no goes on to TikTok's own first instruction", before[0], after[after.targetOf(2)])
        assertEquals("yes ends the method", Opcode.RETURN_VOID, after[3].opcode)
        before.forEachIndexed { index, instruction -> assertSame(instruction, after[index + 4]) }
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a static expand is refused, since p0 wouldn't be the caption`() {
        assertThrows(PatchException::class.java) { expand(static = true).resolveCaptionOpensComments() }
    }

    /**
     * 47.0.3's list check: the opener's shouldShowVideoDesc boxed into a null-safe check, a path
     * with no model joining at the check, and the title panel's own caption left out after.
     */
    private fun row(titlePanel: Boolean = true) = method(list, "LJJIIZI", listOf(aweme), 8, """
            if-nez p1, :has_video
            return-void
            :has_video
            iget-object v0, p0, $list->LLJJL:$contextSource
            const/4 v3, 0x0
            if-eqz v0, :no_model
            invoke-virtual {v0}, $contextSource->getCommonModel()$COMMENT_COMMON_MODEL
            move-result-object v0
            if-eqz v0, :no_model
            iget-boolean v0, v0, $COMMENT_COMMON_MODEL->shouldShowVideoDesc:Z
            invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v0
            :ask
            invoke-static {v0}, LX/0Gmt;->LIZ(Ljava/lang/Boolean;)Z
            move-result v0
            const/4 v1, 0x0
            if-eqz v0, :leave_out
            iget-object v0, p0, $list->LLJJL:$contextSource
            const/4 v4, 0x1
            if-eqz v0, :add
            ${if (titlePanel) "invoke-static {v0}, $COMMENT_CONTEXT_SOURCE_KT->isFromTitlePanel($contextSource)Z" else "invoke-static {v0}, $COMMENT_CONTEXT_SOURCE_KT->other($contextSource)Z"}
            move-result v0
            if-ne v0, v4, :add
            :leave_out
            return-void
            :no_model
            move-object v0, v3
            goto :ask
            :add
            return-void
        """)

    @Test
    fun `the list's answer is the extension's, the video handed over first, and both paths still reach it`() {
        val method = row()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)
        val move = method.captionRowAnswer()!!
        val ask = before[move - 1]

        method.resolveCaptionRow()()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size + 3, after.size)
        val moved = after.indexOfFirst { it === before[move] }
        assertSame(ask, after[moved - 1])
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[moved + 1].opcode)
        assertExtensionCall(after[moved + 1], "listFor", listOf("Ljava/lang/Object;"), "V")
        val handOver = after[moved + 1] as RegisterRangeInstruction
        assertEquals("p1 of an 8-register frame with two in is the video", 7, handOver.startRegister)
        assertEquals(1, handOver.registerCount)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[moved + 2].opcode)
        assertExtensionCall(after[moved + 2], "showCaption", listOf("Z"), "Z")
        assertEquals("the answer TikTok moved", 0, (after[moved + 2] as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.MOVE_RESULT, after[moved + 3].opcode)
        assertEquals("back where TikTok reads it", 0, (after[moved + 3] as OneRegisterInstruction).registerA)
        assertBranchesKept(kept, after)
        val noModel = after.indexOfFirst { it.opcode == Opcode.GOTO }
        assertSame("the path with no model still joins at the check", ask, after[after.targetOf(noModel)])
    }

    @Test
    fun `a list check that no longer leaves the title panel out is refused`() {
        assertThrows(PatchException::class.java) { row(titlePanel = false).resolveCaptionRow() }
    }
}
