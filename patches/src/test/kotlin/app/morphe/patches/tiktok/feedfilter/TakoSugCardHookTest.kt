package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook in the "People also ask Tako" card's onViewCreated, on a three-register method with
 * one parameter (p0 is v1, the view p1 is v2), and the builds it refuses: one that writes over
 * the view and one with no return, loudly where the build is declared and quietly otherwise.
 */
class TakoSugCardHookTest {
    @Test
    fun `the card's view is handed to the extension at every return`() {
        val method = onViewCreated(
            """
                if-eqz p1, :no_view
                const/4 v0, 0x0
                return-void
                :no_view
                return-void
            """,
        )

        method.hideTakoSugCardAtReturns(required = true)

        val code = method.implementation!!.instructions.toList()
        assertEquals(6, code.size)
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertEquals(2, returns.size)
        returns.forEach { index ->
            val call = code[index - 1]
            assertEquals("ahead of the return at $index", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            val range = call as RegisterRangeInstruction
            assertEquals(1, range.registerCount)
            assertEquals("the view, p1", 2, range.startRegister)
            val target = (call as ReferenceInstruction).reference as MethodReference
            assertEquals("Lapp/morphe/extension/tiktok/feedfilter/TakoAiFilter;", target.definingClass)
            assertEquals("hideSearchSugCard", target.name)
            assertEquals(listOf("Landroid/view/View;"), target.parameterTypes.map(CharSequence::toString))
        }
    }

    @Test
    fun `a build that writes over the view goes without the hook`() {
        // A plain write to p1, and a wide one to p0 whose second half is p1.
        listOf("const/4 p1, 0x0", "const-wide p0, 0x0").forEach { write ->
            val body = """
                $write
                return-void
            """
            val refused = assertThrows(PatchException::class.java) {
                onViewCreated(body).hideTakoSugCardAtReturns(required = true)
            }
            assertTrue(refused.message, refused.message!!.contains("writes over its view parameter"))
            assertLeftAlone(onViewCreated(body))
        }
    }

    @Test
    fun `a build with no return to hook goes without it`() {
        val body = """
            const/4 v0, 0x0
            throw v0
        """
        val refused = assertThrows(PatchException::class.java) {
            onViewCreated(body).hideTakoSugCardAtReturns(required = true)
        }
        assertTrue(refused.message, refused.message!!.contains("has no return to hook"))
        assertLeftAlone(onViewCreated(body))
    }

    /** A forced build: the method comes back exactly as it went in. */
    private fun assertLeftAlone(method: MutableMethod) {
        val before = method.implementation!!.instructions.map { it.opcode }
        method.hideTakoSugCardAtReturns(required = false)
        assertEquals(before, method.implementation!!.instructions.map { it.opcode })
    }

    private fun onViewCreated(body: String) = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/tako/SearchTakoSugCardAssem;",
            "onViewCreated",
            listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(3, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body) }
}
