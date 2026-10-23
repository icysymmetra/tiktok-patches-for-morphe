package app.morphe.util

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchContractsTest {
    @Test
    fun `a unique selector match is returned`() {
        assertEquals("only", listOf("only").singleOrPatchException("Test patch: selector"))
    }

    @Test
    fun `empty and ambiguous selectors name their contract and count`() {
        val empty = assertThrows(PatchException::class.java) {
            emptyList<String>().singleOrPatchException("Test patch: settings row")
        }
        assertTrue(empty.message.orEmpty().contains("Test patch: settings row"))
        assertTrue(empty.message.orEmpty().contains("found 0"))

        val ambiguous = assertThrows(PatchException::class.java) {
            listOf("first", "second").singleOrPatchException("Test patch: settings row")
        }
        assertTrue(ambiguous.message.orEmpty().contains("Test patch: settings row"))
        assertTrue(ambiguous.message.orEmpty().contains("found 2"))
    }

    /**
     * R8 outlines a lambda body once per call site, so 46.9.3 carries the playback speed menu's
     * list factory twice on one class with identical instructions. Refusing on the count takes
     * the patch down over a copy of the thing it wanted; two bodies that differ are the other
     * case, and still refuse.
     */
    @Test
    fun `two copies of one body are both handed back`() {
        val matches = listOf(
            body("invoke\$328", Opcode.CONST_4, Opcode.RETURN_OBJECT),
            body("invoke\$851", Opcode.CONST_4, Opcode.RETURN_OBJECT),
        ).sameBodiedOrPatchException("Playback speed: menu list factory")

        assertEquals(listOf("invoke\$328", "invoke\$851"), matches.map { it.name })
    }

    @Test
    fun `two different bodies are a refusal that names both`() {
        val refused = assertThrows(PatchException::class.java) {
            listOf(
                body("LJIIJJI", Opcode.CONST_4, Opcode.RETURN_OBJECT),
                body("LJIILIIL", Opcode.CONST_4, Opcode.CONST_4, Opcode.RETURN_OBJECT),
            ).sameBodiedOrPatchException("Test boundary")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("different bodies"))
        assertTrue(refused.message, refused.message.orEmpty().contains("LJIIJJI"))
        assertTrue(refused.message, refused.message.orEmpty().contains("LJIILIIL"))
    }

    @Test
    fun `no match at all is a refusal rather than an empty list`() {
        val refused = assertThrows(PatchException::class.java) {
            emptyList<MutableMethod>().sameBodiedOrPatchException("Test boundary")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("found 0"))
    }

    /**
     * Registers and references differ between two copies of one body in ways that say nothing
     * about whether they do the same thing, which is why only the opcodes are compared. 46.9.3's
     * two copies write different registers.
     */
    @Test
    fun `the same opcodes in different registers are the same body`() {
        val first = MutableMethod(
            ImmutableMethod(
                "Lkotlin/jvm/internal/Lambda;", "invoke\$328", emptyList(), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    4,
                    listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                    null,
                    null,
                ),
            ),
        )
        val second = MutableMethod(
            ImmutableMethod(
                "Lkotlin/jvm/internal/Lambda;", "invoke\$851", emptyList(), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    4,
                    listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 3, 1),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3),
                    ),
                    null,
                    null,
                ),
            ),
        )

        assertEquals(2, listOf(first, second).sameBodiedOrPatchException("Test boundary").size)
    }

    private fun body(name: String, vararg opcodes: Opcode): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lkotlin/jvm/internal/Lambda;",
            name,
            emptyList(),
            "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                4,
                opcodes.map { opcode ->
                    if (opcode == Opcode.CONST_4) ImmutableInstruction11n(opcode, 0, 0)
                    else ImmutableInstruction11x(opcode, 0)
                },
                null,
                null,
            ),
        ),
    )

    @Test
    fun `a missing body names the patch and full method descriptor`() {
        val method = ImmutableMethod(
            "Lcom/example/Host;",
            "read",
            listOf("I", "Ljava/lang/String;").map { ImmutableMethodParameter(it, null, null) },
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value,
            null,
            null,
            null,
        )

        val error = assertThrows(PatchException::class.java) {
            method.implementationOrPatchException("Test patch")
        }
        assertTrue(error.message.orEmpty().contains("Test patch"))
        assertTrue(error.message.orEmpty().contains("Lcom/example/Host;->read(ILjava/lang/String;)Z"))
    }
}
