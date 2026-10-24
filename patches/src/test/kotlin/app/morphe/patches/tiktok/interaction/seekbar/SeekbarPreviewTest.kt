package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val SEEKBAR = "LX/06rW;"
private const val LOADER = "LX/0F5K;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/seekbar/SeekbarPatch;"

/** How the seek bar's preview check is told apart from its other boolean getters, on 47.1.4's shapes. */
class SeekbarPreviewTest {
    private fun getter(name: String, registers: Int, smali: String) =
        MutableMethod(ImmutableMethod(
            SEEKBAR, name, emptyList(), "Z", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).apply { addInstructionsWithLabels(0, smali) }

    /** The thumbnail gate: reads the video it is about. */
    private fun gate(extraVideoRead: Boolean = false) = getter("LJII", 2, """
        iget-object v0, p0, $SEEKBAR->LLJLL:$AWEME
        ${if (extraVideoRead) "iget-object v0, p0, $SEEKBAR->renderAweme:$AWEME" else ""}
        if-eqz v0, :no
        const/4 v0, 0x1
        return v0
        :no
        const/4 v0, 0x0
        return v0
    """)

    /** 47.1.4's LJIJI: the gate, then the loader's own answer. */
    private fun check(name: String = "LJIJI") = getter(name, 4, """
        invoke-virtual {p0}, $SEEKBAR->LJII()Z
        move-result v0
        const/4 v2, 0x0
        if-eqz v0, :done
        iget-object v0, p0, $SEEKBAR->LLLLII:$LOADER
        const/4 v1, 0x1
        if-eqz v0, :done
        invoke-virtual {v0}, LX/0F5J;->LIZ()Z
        move-result v0
        if-ne v0, v1, :done
        const/4 v2, 0x1
        :done
        return v2
    """)

    /**
     * Other getters of the class: a plain flag, one that asks the gate but no loader, and one that
     * asks the loader without the gate first.
     */
    private val decoys = listOf(
        getter("LJIILJJIL", 2, """
            iget-object v0, p0, $SEEKBAR->LLLLII:$LOADER
            invoke-virtual {v0}, LX/0F5J;->LIZ()Z
            move-result v0
            invoke-virtual {p0}, $SEEKBAR->LJII()Z
            return v0
        """),
        getter("LJIILIIL", 2, """
            iget-boolean v0, p0, $SEEKBAR->LLLLIIIILLL:Z
            return v0
        """),
        getter("LJIILL", 2, """
            invoke-virtual {p0}, $SEEKBAR->LJII()Z
            move-result v0
            return v0
        """),
    )

    private fun seekbar(vararg methods: MutableMethod) =
        ImmutableClassDef(SEEKBAR, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    @Test
    fun `the check is the getter that asks the class's own gate and then the loader`() {
        val found = previewCheckOf(seekbar(gate(), check(), *decoys.toTypedArray()))
        assertEquals("LJIJI", found.method.name)
        assertEquals("LLJLL", found.video.name)
        assertEquals(AWEME, found.video.type)
    }

    @Test
    fun `two checks, none, or a gate reading two videos are refused`() {
        assertThrows(PatchException::class.java) { previewCheckOf(seekbar(gate(), check(), check("LJIJJI"))) }
        assertThrows(PatchException::class.java) { previewCheckOf(seekbar(gate(), *decoys.toTypedArray())) }
        assertThrows(PatchException::class.java) { previewCheckOf(seekbar(gate(extraVideoRead = true), check())) }
    }

    @Test
    fun `the video goes to the extension at entry and TikTok's own check follows untouched`() {
        val method = check()
        val before = method.implementation!!.instructions.toList()
        val video = previewCheckOf(seekbar(gate(), check())).video

        method.reportPreviewCheck(video)

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size + 2, after.size)
        assertEquals(Opcode.IGET_OBJECT, after[0].opcode)
        val read = after[0] as TwoRegisterInstruction
        assertEquals(0, read.registerA)
        assertEquals(3, read.registerB)
        assertEquals("LLJLL", after[0].getReference<FieldReference>()!!.name)
        assertEquals(Opcode.INVOKE_STATIC, after[1].opcode)
        val call = after[1].getReference<MethodReference>()!!
        assertEquals(EXTENSION, call.definingClass)
        assertEquals("onPreviewCheck", call.name)
        assertEquals(0, (after[1] as FiveRegisterInstruction).registerC)
        assertEquals(before.map { it.opcode }, after.drop(2).map { it.opcode })
    }

    @Test
    fun `a check with no local register or this past v15 is refused before anything is written`() {
        val video = previewCheckOf(seekbar(gate(), check())).video
        for (registers in listOf(1, 17)) {
            val method = MutableMethod(ImmutableMethod(
                SEEKBAR, "LJIJI", emptyList(), "Z", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ))
            assertThrows("$registers registers", PatchException::class.java) { method.reportPreviewCheck(video) }
            assertEquals("$registers registers", 0, method.implementation!!.instructions.count())
        }
        // The last register count that still reaches this with iget.
        val reachable = getter("LJIJI", 16, "const/4 v0, 0x0\nreturn v0")
        reachable.reportPreviewCheck(video)
        assertEquals(15, (reachable.implementation!!.instructions.first() as TwoRegisterInstruction).registerB)
    }
}
