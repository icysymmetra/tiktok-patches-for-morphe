package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.SwitchLabelElement
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderPackedSwitchPayload
import com.android.tools.smali.dexlib2.builder.instruction.BuilderSparseSwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The profile card bio's shape checks on hand-written methods (#159): the 47.4.3 shape is found
 * and hooked, and each way the color or view could be wrong at the hook, or the hook skipped (a
 * write, a jump, switch case or handler landing in between or on the instruction after the color,
 * a span taking another register) is refused rather than written. The real build is covered by LiveDarkPanelsAnchorsTest.
 */
class LiveBioLinkHookTest {

    @Test
    fun `the bio's shape is found and hooked`() {
        val bio = smali(bio())
        val link = bioLinkColor(bio)!!
        assertEquals(listOf(4, 3, 2), listOf(link.call, link.color, link.view))

        hookBioLink(bio)
        val code = bio.implementation!!.instructions.toList()
        assertEquals("color", ((code[4] as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(Opcode.MOVE_RESULT, code[5].opcode)
        val hook = code[6] as FiveRegisterInstruction
        assertEquals("bioLinkColor", ((hook as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(listOf(3, 2), listOf(hook.registerC, hook.registerD))
        assertEquals("<init>", ((code[7] as ReferenceInstruction).reference as MethodReference).name)
    }

    @Test
    fun `a view register written between the color call and the link text is refused`() {
        assertNull(bioLinkColor(smali(bio(afterColor = "const/4 v2, 0x0"))))
    }

    @Test
    fun `a color landing in the view register is refused`() {
        val init = "invoke-direct {v0, v2}, Landroid/text/style/ForegroundColorSpan;-><init>(I)V"
        assertNull(bioLinkColor(smali(bio(result = "move-result v2", init = init))))
    }

    @Test
    fun `a color call without its result is refused`() {
        assertNull(bioLinkColor(smali(bio(result = "nop"))))
    }

    @Test
    fun `a color above v15 is refused`() {
        // The span's init has to name the color, and a non-range init can't name v16, so a color
        // above v15 only reaches a range init, which is refused. The hook's call then always fits.
        // With 18 registers p0 is v17, which an iget can't name, so the view is read through v1.
        val view = "iget-object v2, v1, Lcom/example/Host;->bio:Lcom/example/Bio;"
        val init = "invoke-direct/range {v15 .. v16}, Landroid/text/style/ForegroundColorSpan;-><init>(I)V"
        val bio = smali(bio(view = view, result = "move-result v16", init = init), registers = 18)
        assertNull(bioLinkColor(bio))
        assertThrows(PatchException::class.java) { hookBioLink(bio) }
    }

    @Test
    fun `a view that isn't read from the view's field is refused`() {
        assertNull(bioLinkColor(smali(bio(view = "move-object v2, p0"))))
    }

    @Test
    fun `a branch landing between the view's read and the color call is refused`() {
        assertNull(bioLinkColor(smali(bio(beforeView = "if-eqz v1, :join", afterView = ":join"))))
    }

    @Test
    fun `a branch landing on the instruction the hook goes before is refused`() {
        // The label stays on the span's init when the hook goes in ahead of it, so the jump would skip the hook.
        assertNull(bioLinkColor(smali(bio(beforeView = "if-eqz v1, :skip", beforeInit = ":skip"))))
    }

    @Test
    fun `a switch case landing between the view's read and the color call is refused`() {
        // The switch's own offset points at its payload, past return-void, and its cases are offsets
        // from the switch, which sits after the new-instance so that offset isn't zero.
        for (sparse in listOf(false, true)) {
            assertNull(bioLinkColor(switchTo(smali(bio()), case = 3, sparse)))
            assertEquals(listOf(5, 3, 2), bioLinkColor(switchTo(smali(bio()), case = 9, sparse))!!.let { listOf(it.call, it.color, it.view) })
        }
    }

    @Test
    fun `a handler landing between the view's read and the color call is refused`() {
        assertNull(bioLinkColor(catchAt(smali(bio()), handler = 3)))
        assertEquals(4, bioLinkColor(catchAt(smali(bio()), handler = 9))!!.call)
    }

    @Test
    fun `a color span taking another register than the color is refused`() {
        assertNull(bioLinkColor(smali(bio(init = "invoke-direct {v0, v4}, Landroid/text/style/ForegroundColorSpan;-><init>(I)V"))))
        assertNull(bioLinkColor(smali(bio(init = "invoke-direct {v0, v3, v4}, Landroid/text/style/ForegroundColorSpan;-><init>(II)V"))))
    }

    @Test
    fun `a color overwritten before the color span takes it is refused`() {
        assertNull(bioLinkColor(smali(bio(beforeInit = "const v3, 0x0"))))
    }

    @Test
    fun `range invokes are refused`() {
        assertNull(
            bioLinkColor(
                smali(bio(color = "invoke-static/range {v3 .. v4}, Lcom/example/Colors;->color(ILandroid/content/Context;)I", context = "v4")),
            ),
        )
        assertNull(
            bioLinkColor(smali(bio(suffix = "invoke-virtual/range {v2 .. v3}, Lcom/example/Bio;->setSuffixText(Ljava/lang/CharSequence;)V"))),
        )
    }

    @Test
    fun `a method without the bio's shape fails the hook`() {
        assertThrows(PatchException::class.java) { hookBioLink(smali("return-void")) }
    }

    /** The bio's 47.4.3 shape, with one piece swapped in or added at a time. */
    private fun bio(
        context: String = "v1",
        beforeView: String = "",
        view: String = "iget-object v2, p0, Lcom/example/Host;->bio:Lcom/example/Bio;",
        afterView: String = "",
        color: String = "invoke-static {v3, v1}, Lcom/example/Colors;->color(ILandroid/content/Context;)I",
        result: String = "move-result v3",
        beforeInit: String = "",
        init: String = "invoke-direct {v0, v3}, Landroid/text/style/ForegroundColorSpan;-><init>(I)V",
        afterColor: String = "",
        suffix: String = "invoke-virtual {v2, v3}, Lcom/example/Bio;->setSuffixText(Ljava/lang/CharSequence;)V",
    ) = """
        new-instance v0, Landroid/text/style/ForegroundColorSpan;
        sget-object $context, Lcom/example/Host;->card:Landroid/content/Context;
        $beforeView
        $view
        $afterView
        const v3, 0x7f061ec4
        $color
        $result
        $beforeInit
        $init
        $afterColor
        const-string v3, "See original"
        $suffix
        return-void
    """.lines().filter { it.isNotBlank() }.joinToString("\n")

    private fun smali(body: String, registers: Int = 6) = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Host;", "bio", listOf(ImmutableMethodParameter("Lcom/example/Host;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /** Adds a switch after the new-instance whose one case is the instruction at [case] (counted before the switch). */
    private fun switchTo(bio: MutableMethod, case: Int, sparse: Boolean) = bio.apply {
        val code = implementation!!
        val payload = code.instructions.size
        val target = code.newLabelForIndex(case)
        code.addInstruction(
            if (sparse) BuilderSparseSwitchPayload(listOf(SwitchLabelElement(7, target)))
            else BuilderPackedSwitchPayload(0, listOf(target)),
        )
        // dexlib2 drops a payload no switch refers to the next time the code is read, so the
        // switch goes in before anything reads it.
        val opcode = if (sparse) Opcode.SPARSE_SWITCH else Opcode.PACKED_SWITCH
        code.addInstruction(1, BuilderInstruction31t(opcode, 4, code.newLabelForIndex(payload)))
    }

    /** Puts the sget at index 1 in a catch-all whose handler is the instruction at [handler]. */
    private fun catchAt(bio: MutableMethod, handler: Int) = bio.apply {
        val code = implementation!!
        code.addCatch(code.newLabelForIndex(1), code.newLabelForIndex(2), code.newLabelForIndex(handler))
    }
}
