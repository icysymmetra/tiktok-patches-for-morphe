package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.*
import org.junit.Test

class CommentSearchSuggestionsPatchTest {
    @Test
    fun `banner selector requires the native service scene model and view result together`() {
        assertTrue(isCommentSearchHeaderFactory(factory()))
        for (unrelated in listOf(
            factory(owner = "Lfixture/UnrelatedSearch;"),
            factory(result = "Ljava/lang/Object;"),
            factory(context = "Ljava/lang/String;"),
            factory(scene = "feed_bottom"),
            factory(getter = "getOtherSuggestions"),
        )) assertFalse(isCommentSearchHeaderFactory(unrelated))
    }

    @Test
    fun `missing local refuses the whole preflight without editing an earlier method`() {
        val earlier = factory()
        val incompatible = factory(registers = 3)
        val before = earlier.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) {
            applyAfterCommentToolsPreflight(
                { earlier.resolveCommentSearchSuggestions() },
                { incompatible.resolveCommentSearchSuggestions() },
            )
        }
        assertEquals(before, earlier.implementation!!.instructions.toList())
    }

    @Test
    fun `disabled guard branches past the null return into the unchanged factory`() {
        val method = factory()
        val original = method.implementation!!.instructions.toList()
        method.resolveCommentSearchSuggestions()()
        val after = method.implementation!!.instructions.toList()
        assertEquals("Lapp/morphe/extension/tiktok/comment/CommentTools;->shouldHideCommentSearchSuggestions()Z",
            (after[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals(after.subList(2, 5).sumOf { it.codeUnits },
            (after[2] as OffsetInstruction).codeOffset)
        assertEquals(Opcode.NOP, after[5].opcode)
        assertEquals(original, after.drop(6))
        assertEquals(4, method.implementation!!.registerCount)
    }

    private fun factory(
        owner: String = COMMENT_SCENE_SERVICE_IMPL,
        result: String = "Landroid/view/View;",
        context: String = "Landroid/content/Context;",
        scene: String = "comment_top",
        getter: String = "getCommentSuggestWordList",
        registers: Int = 4,
    ) = MutableMethod(ImmutableMethod(
        owner, "obfuscatedFactory", listOf(context, "Lcom/ss/android/ugc/aweme/feed/model/Aweme;")
            .map { ImmutableMethodParameter(it, null, null) },
        result, AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null),
    )).apply {
        addInstructions(0, """
            invoke-virtual {p2}, Lcom/ss/android/ugc/aweme/feed/model/Aweme;->$getter()Lcom/ss/android/ugc/aweme/feed/model/search/CommentSuggestWordList;
            move-result-object v0
            const-string v0, "$scene"
            const/4 v0, 0x0
            return-object v0
        """)
    }
}
