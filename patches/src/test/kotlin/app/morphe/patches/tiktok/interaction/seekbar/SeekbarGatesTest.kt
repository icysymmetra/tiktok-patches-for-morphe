package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val VIDEO_CONTROL = "Lcom/ss/android/ugc/aweme/feed/model/VideoControl;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/seekbar/SeekbarPatch;"

/**
 * How the two experiment gates are found from TikTok's log lines and what is written into each,
 * on methods shaped like 47.0.3's.
 */
class SeekbarGatesTest {
    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) =
        MutableMethod(ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).apply { addInstructionsWithLabels(0, smali) }

    /** The reasons method, two checks of the shape TikTok writes: call, move-result, branch, log line. */
    private fun reasons(dragOwner: String = "LX/06r8;", inverseCall: String = "invoke-static {p1}, LX/09cI;->LIZ($AWEME)Z") =
        method("LX/06nl;", "LJII", listOf(AWEME), "Z", 4, """
            invoke-static {p1}, $dragOwner->LIZIZ($AWEME)Z
            move-result v0
            if-nez v0, :draggable
            const-string v0, "$CANNOT_DRAG_LOG"
            const/4 v0, 0x0
            return v0
            :draggable
            $inverseCall
            move-result v0
            if-nez v0, :shown
            const-string v0, "$INVERSE_EXPERIMENT_LOG"
            const/4 v0, 0x0
            return v0
            :shown
            const/4 v0, 0x1
            return v0
        """)

    @Test
    fun `each log line names the static (Aweme)Z call above it`() {
        val reasons = reasons()
        val inverse = reasons.gateBefore(INVERSE_EXPERIMENT_LOG)
        assertEquals("LX/09cI;", inverse.definingClass)
        assertEquals("LIZ", inverse.name)
        val drag = reasons.gateBefore(CANNOT_DRAG_LOG)
        assertEquals("LX/06r8;", drag.definingClass)
        assertEquals("LIZIZ", drag.name)
    }

    @Test
    fun `a missing line and a call of another shape are refused by name`() {
        assertThrows(PatchException::class.java) { reasons().gateBefore("can not show seekbar, state: 9, no such line") }
        // The call above the inverse line takes a String: not the video's gate.
        val other = reasons(inverseCall = "invoke-static {p1}, LX/09cI;->LIZ(Ljava/lang/String;)Z")
        assertThrows(PatchException::class.java) { other.gateBefore(INVERSE_EXPERIMENT_LOG) }
    }

    @Test
    fun `the inverse gate is answered at its entry and the host's own body is kept behind the switch`() {
        val gate = method("LX/09cI;", "LIZ", listOf(AWEME), "Z", 3, """
            iget-boolean v0, p0, $AWEME->isPaidContent:Z
            return v0
        """)
        val before = gate.implementation!!.instructions.toList()

        gate.answerInverseExperiment()

        val after = gate.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC, after[0].opcode)
        val call = after[0].getReference<MethodReference>()!!
        assertEquals(EXTENSION, call.definingClass)
        assertEquals("isEnabled", call.name)
        assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals(Opcode.CONST_4, after[3].opcode)
        assertEquals(1, (after[3] as OneRegisterInstruction).run { this as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction }.narrowLiteral)
        assertEquals(Opcode.RETURN, after[4].opcode)
        // The host's own instructions follow, untouched.
        assertEquals(before.map { it.opcode }, after.drop(after.size - before.size).map { it.opcode })
    }

    @Test
    fun `the draggable flag is wrapped right after its read and nothing else in the gate moves`() {
        val gate = method("LX/06r8;", "LIZIZ", listOf(AWEME), "Z", 4, """
            invoke-virtual {p0}, $AWEME->getAwemeRawAd()Ljava/lang/Object;
            move-result-object v0
            if-eqz v0, :organic
            const/4 v2, 0x0
            return v2
            :organic
            invoke-virtual {p0}, $AWEME->getVideoControl()$VIDEO_CONTROL
            move-result-object v0
            if-eqz v0, :none
            iget v0, v0, $VIDEO_CONTROL->draftProgressBar:I
            if-eqz v0, :none
            const/4 v2, 0x1
            return v2
            :none
            const/4 v2, 0x0
            return v2
        """)
        val before = gate.implementation!!.instructions.toList()
        val read = before.indexOfFirst { it.opcode == Opcode.IGET }

        assertTrue(gate.answerDraggable())

        val after = gate.implementation!!.instructions.toList()
        assertEquals(before.size + 2, after.size)
        assertEquals(Opcode.IGET, after[read].opcode)
        val call = after[read + 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val reference = call.getReference<MethodReference>()!!
        assertEquals(EXTENSION, reference.definingClass)
        assertEquals("overrideDraftProgressBar", reference.name)
        assertEquals(listOf("I"), reference.parameterTypes.map(CharSequence::toString))
        assertEquals(0, (call as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.MOVE_RESULT, after[read + 2].opcode)
        assertEquals(0, (after[read + 2] as OneRegisterInstruction).registerA)
        // The branch past the read still lands on the refusal, two instructions further on.
        assertEquals(Opcode.IF_EQZ, after[read + 3].opcode)
    }

    @Test
    fun `a gate without the flag is left alone and reported, two reads are refused`() {
        val flagless = method("LX/06r8;", "LIZIZ", listOf(AWEME), "Z", 2, """
            const/4 v0, 0x1
            return v0
        """)
        assertFalse(flagless.answerDraggable())
        assertEquals(2, flagless.implementation!!.instructions.count())

        val twice = method("LX/06r8;", "LIZIZ", listOf(AWEME), "Z", 3, """
            invoke-virtual {p0}, $AWEME->getVideoControl()$VIDEO_CONTROL
            move-result-object v1
            iget v0, v1, $VIDEO_CONTROL->draftProgressBar:I
            iget v0, v1, $VIDEO_CONTROL->draftProgressBar:I
            return v0
        """)
        assertThrows(PatchException::class.java) { twice.answerDraggable() }
    }
}
