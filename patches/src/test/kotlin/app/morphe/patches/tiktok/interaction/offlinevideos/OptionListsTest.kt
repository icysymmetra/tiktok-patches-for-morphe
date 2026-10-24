package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Every offline option list the holder builds gets the custom limit, however many it builds. */
class OptionListsTest {
    @Test
    fun `every list the holder freezes from boxed counts is found, two on 46_2_3 and four on 46_8_3`() {
        val two = holderInit(
            "LX/0sIr;",
            listOf("LJ" to listOf(50, 100, 150, 200), "LJFF" to listOf(60, 120, 240, 480)),
        )
        assertResults(two, "LX/0sIr;", 2)

        val four = holderInit(
            "LX/18A3;",
            listOf(
                "LJFF" to listOf(50, 100, 150, 200),
                "LJI" to listOf(60, 120, 240, 480),
                "LJII" to listOf(60, 240, 480),
                "LJIIIIZZ" to listOf(50, 150, 200),
            ),
        )
        assertResults(four, "LX/18A3;", 4)
    }

    @Test
    fun `a list stored any other way is refused rather than left without the custom entry`() {
        val init = holderInit("LX/0sIr;", listOf("LJ" to listOf(50, 100)), copiedTo = "LJFF")
        try {
            optionListResults(init, "LX/0sIr;")
            fail("expected a refusal")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), "stores LJFF without freezing it" in refused.message.orEmpty())
        }
    }

    /** Each result is the move-result right before a store of the holder's own List field. */
    private fun assertResults(init: MutableMethod, holder: String, expected: Int) {
        val results = optionListResults(init, holder)
        assertEquals(expected, results.size)
        val instructions = init.implementation!!.instructions.toList()
        for (index in results) {
            assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[index].opcode)
            val store = instructions[index + 1]
            assertEquals(Opcode.SPUT_OBJECT, store.opcode)
            assertEquals(
                (instructions[index] as OneRegisterInstruction).registerA,
                (store as OneRegisterInstruction).registerA,
            )
        }
    }

    /**
     * The holder's initialiser as both builds write it: each list boxed into an Integer array,
     * frozen into a List by a static call, and stored. [copiedTo] adds a list stored by copying
     * another static instead.
     */
    private fun holderInit(holder: String, lists: List<Pair<String, List<Int>>>, copiedTo: String? = null): MutableMethod {
        val body = StringBuilder()
        for ((field, counts) in lists) {
            body.append("const/4 v1, ${counts.size}\n")
            body.append("new-array v1, v1, [Ljava/lang/Integer;\n")
            counts.forEachIndexed { index, count ->
                body.append("const/16 v0, $count\n")
                body.append("invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\n")
                body.append("move-result-object v2\n")
                body.append("const/4 v3, $index\n")
                body.append("aput-object v2, v1, v3\n")
            }
            body.append("invoke-static {v1}, LX/0n7t;->LJIIJJI([Ljava/lang/Object;)Ljava/util/List;\n")
            body.append("move-result-object v0\n")
            body.append("sput-object v0, $holder->$field:Ljava/util/List;\n")
        }
        if (copiedTo != null) {
            body.append("sget-object v0, $holder->${lists.first().first}:Ljava/util/List;\n")
            body.append("sput-object v0, $holder->$copiedTo:Ljava/util/List;\n")
        }
        body.append("return-void\n")
        return MutableMethod(
            ImmutableMethod(
                holder, "<clinit>", emptyList(), "V",
                AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply { appendFixtureInstructions(body.toString()) }
    }
}
