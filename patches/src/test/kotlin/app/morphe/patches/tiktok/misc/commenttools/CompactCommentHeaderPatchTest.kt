package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.*
import org.junit.Test

class CompactCommentHeaderPatchTest {
    @Test fun `only the three exact dedicated roots match`() {
        compactCommentHeaderComponents.keys.forEach { assertTrue(isCompactCommentHeaderBind(bind(it))) }
        assertFalse(isCompactCommentHeaderBind(bind("Lfixture/CommentSheet;")))
        assertFalse(isCompactCommentHeaderBind(bind(nativeParent = "Lfixture/OtherParent;")))
        assertFalse(isCompactCommentHeaderBind(bind(name = "onBindItemView")))
        assertFalse(isCompactCommentHeaderBind(bind(flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))
    }

    @Test fun `high register root is passed before native aliases and native body stays unchanged`() {
        val method = bind(registers = 25)
        val original = method.implementation!!.instructions.toList()
        val write = method.resolveCompactCommentHeader()
        assertEquals(original, method.implementation!!.instructions.toList())
        write()
        val after = method.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after.first().opcode)
        assertEquals(24, (after.first() as RegisterRangeInstruction).startRegister)
        assertEquals(1, (after.first() as RegisterRangeInstruction).registerCount)
        assertEquals(original, after.drop(1))
    }

    @Test fun `last incompatible header leaves all earlier writes untouched`() {
        val good = bind()
        val original = good.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) {
            applyAfterCommentToolsPreflight(
                { good.resolveCompactCommentHeader() },
                { bind(nativeParent = "Lfixture/Changed;").resolveCompactCommentHeader() },
            )
        }
        assertEquals(original, good.implementation!!.instructions.toList())
    }

    private fun bind(
        owner: String = compactCommentHeaderComponents.keys.first(),
        nativeParent: String = "Lcom/bytedance/assem/arch/core/UIAssem;",
        name: String = "onViewCreated",
        flags: Int = AccessFlags.PUBLIC.value,
        registers: Int = 2,
    ) = MutableMethod(ImmutableMethod(owner, name,
        listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)), "V", flags, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null),
    )).apply {
        addInstructions(0, """
            invoke-super/range { p0 .. p1 }, $nativeParent->onViewCreated(Landroid/view/View;)V
            return-void
        """)
    }
}
