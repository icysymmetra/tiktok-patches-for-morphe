package app.morphe.util

import app.morphe.util.returnEarly
import app.morphe.util.returnLate
import app.morphe.patcher.patch.PatchException

import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helpers under app.morphe.util, which nothing in this repository tested.
 *
 * <p>Each of these paths is unreachable from the patches here today: the first patch to lean on
 * one would have inherited the defect with nothing to say so. The methods are built by hand
 * rather than read out of an APK, so a case says what shape it needs and no more.
 */
class BytecodeUtilsTest {
    @Test
    fun `a return constant dropped by the inline compiler refuses the patch`() {
        val failure = assertThrows(PatchException::class.java) { method("B").returnEarly(100.toByte()) }
        assertTrue(failure.message, failure.message!!.contains("kept 1 of 2"))
    }

    private fun method(
        returnType: String,
        vararg instructions: Instruction = arrayOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
    ): MutableMethod = methodWithRegisters(
        returnType,
        // Room for a few registers: a case that needs two of them should not have to build its
        // own method.
        registers = 4,
        accessFlags = AccessFlags.PUBLIC.value,
        instructions = instructions,
    )

    /** The same method, sized by the case, for the ones that are about the frame itself. */
    private fun methodWithRegisters(
        returnType: String,
        registers: Int,
        accessFlags: Int = AccessFlags.PUBLIC.value,
        vararg instructions: Instruction = arrayOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Host;",
            "value",
            emptyList(),
            returnType,
            accessFlags,
            null,
            null,
            ImmutableMethodImplementation(
                registers,
                instructions.toList(),
                null,
                null,
            ),
        ),
    )

    private val staticFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

    /** A body that hands an object back, which is what returnLate needs to find. */
    private fun returnsAnObject(returnType: String): MutableMethod = method(
        returnType,
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    )

    private fun field(vararg flags: AccessFlags): MutableField = MutableField(
        ImmutableField(
            "Lcom/example/Host;",
            "value",
            "I",
            flags.fold(0) { all, flag -> all or flag.value },
            null,
            null,
            null,
        ),
    )

    /** The opcode at an index, without dragging the patcher's instruction extensions in. */
    private fun opcodeAt(body: MutableMethod, index: Int): Opcode =
        body.implementation!!.instructions.toList()[index].opcode

    @Test
    fun `an argument register is read off either invoke format and off nothing else`() {
        val direct = ImmutableInstruction35c(
            Opcode.INVOKE_STATIC, 3, 4, 5, 6, 0, 0,
            ImmutableMethodReference("Lfixture/Ext;", "ask", listOf("I", "I", "I"), "Z"),
        )
        assertEquals(4, direct.argumentRegister(0))
        assertEquals(6, direct.argumentRegister(2))
        assertEquals(null, direct.argumentRegister(3))
        val range = com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc(
            Opcode.INVOKE_STATIC_RANGE, 16, 3,
            ImmutableMethodReference("Lfixture/Ext;", "ask", listOf("I", "I", "I"), "Z"),
        )
        assertEquals(16, range.argumentRegister(0))
        assertEquals(18, range.argumentRegister(2))
        assertEquals(null, ImmutableInstruction10x(Opcode.RETURN_VOID).argumentRegister(0))
    }

    @Test
    fun `a double returning method takes a double`() {
        // The check read 'J', so it rejected every method that returns a double and accepted
        // every method that returns a long, which is the opposite of what it is for. Its twin
        // returnLate(Double) two hundred lines down reads 'D'.
        val double = method("D")
        double.returnEarly(1.5)
        assertEquals(Opcode.CONST_WIDE, double.implementation!!.instructions.first().opcode)

        val long = method("J")
        val refused = assertThrows(IllegalStateException::class.java) { long.returnEarly(1.5) }
        assertTrue(refused.message!!.contains("return type"))
    }

    @Test
    fun `a null return is allowed for an array and refused for a primitive`() {
        // The check was `returnType == 'L' || returnType != '['`, which is true for I, V and Z
        // and false for the one case the second half exists to allow. Nothing calls this today;
        // the first caller would have been refused on an array and let through on an int, and
        // an int method would have been handed const/4 v0, 0x0 followed by return-object.
        val array = method("[Ljava/lang/String;")
        array.returnEarly(null)
        assertEquals(Opcode.CONST_4, array.implementation!!.instructions.first().opcode)

        val obj = method("Ljava/util/List;")
        obj.returnEarly(null)
        assertEquals(Opcode.CONST_4, obj.implementation!!.instructions.first().opcode)

        // String and CharSequence took a const-string path that is chosen before the first
        // character is looked at, so a null return on either wrote the text "0x0" and the caller
        // got a three character string where it asked for null. returnLate(Void?) did the same.
        for (text in listOf("Ljava/lang/String;", "Ljava/lang/CharSequence;")) {
            val early = method(text)
            early.returnEarly(null)
            assertEquals(
                "returnEarly(null) on a method returning $text",
                Opcode.CONST_4,
                early.implementation!!.instructions.first().opcode,
            )

            val late = returnsAnObject(text)
            late.returnLate(null)
            assertTrue(
                "returnLate(null) on a method returning $text wrote a string",
                late.implementation!!.instructions.none { it.opcode == Opcode.CONST_STRING },
            )
            assertEquals(
                "returnLate(null) on a method returning $text added no null",
                2,
                late.implementation!!.instructions.count { it.opcode == Opcode.CONST_4 },
            )
        }

        for (primitive in listOf("I", "V", "Z", "J")) {
            assertThrows(
                "a null return was allowed on a method returning $primitive",
                IllegalStateException::class.java,
            ) { method(primitive).returnEarly(null) }
        }
    }

    @Test
    fun `an override is refused when the frame has no register to write into`() {
        // Four patches wrote const/4 v0 and return v0 by hand and only one of them asked whether
        // the method had a v0 to write into. A frame with no room takes the instructions without
        // complaint, and what fails is dex verification on the phone, a long way from the patch
        // that caused it.
        val noRoom = assertThrows(IllegalStateException::class.java) {
            methodWithRegisters("Z", 0).returnEarly(false)
        }
        assertTrue(noRoom.message!!.contains("0 registers"))
        assertTrue(noRoom.message!!.contains("Lcom/example/Host;->value"))

        // One is enough, and one is what the gates these patches flip actually have.
        val oneRegister = methodWithRegisters("Z", 1)
        oneRegister.returnEarly(true)
        assertEquals(Opcode.CONST_4, oneRegister.implementation!!.instructions.first().opcode)
    }

    @Test
    fun `a wide override needs the pair of registers it writes`() {
        // const-wide v0 writes v0 and v1, so a one register frame is short by one and the
        // count has to follow the value rather than being a flat "at least one".
        val tooNarrow = assertThrows(IllegalStateException::class.java) {
            methodWithRegisters("J", 1).returnEarly(7L)
        }
        assertTrue(tooNarrow.message!!.contains("1 registers"))

        val wideEnough = methodWithRegisters("J", 2)
        wideEnough.returnEarly(7L)
        assertEquals(Opcode.CONST_WIDE, wideEnough.implementation!!.instructions.first().opcode)
    }

    @Test
    fun `a void override needs no register at all`() {
        // Without this the two cases above would pass just as happily against a check that
        // refused every method. return-void writes into nothing, and a static method with no
        // parameters and no locals really is `.registers 0`: RememberClearDisplayPatch selects
        // static void methods by shape, so this is not a hypothetical frame.
        val none = methodWithRegisters("V", 0, staticFlags)
        none.returnEarly()
        assertEquals(
            "zero register frame held " + none.implementation!!.instructions.map { it.opcode },
            Opcode.RETURN_VOID,
            none.implementation!!.instructions.firstOrNull()?.opcode,
        )
    }

    @Test
    fun `a frame too small for the method's own parameters is named rather than left to the assembler`() {
        // An instance method cannot be `.registers 0`, because `this` is p0. Handed one, the
        // patcher's smali compiler parses nothing and throws "Collection is empty" out of its
        // own first(), naming neither the method nor the reason, and reading that refusal as a
        // property of every override is what briefly put a register floor under the void case.
        val instance = assertThrows(IllegalStateException::class.java) {
            methodWithRegisters("V", 0).returnEarly()
        }
        assertTrue(instance.message!!.contains("its own parameters take 1"))
        assertTrue(instance.message!!.contains("Lcom/example/Host;->value"))
    }

    @Test
    fun `an override of a method with no body says so`() {
        val abstract = MutableMethod(
            ImmutableMethod(
                "Lcom/example/Host;",
                "value",
                emptyList(),
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value,
                null,
                null,
                null,
            ),
        )
        val refused = assertThrows(IllegalStateException::class.java) { abstract.returnEarly(false) }
        assertTrue(refused.message!!.contains("has no body"))
    }

    @Test
    fun `a boolean override on a method that does not answer a boolean is refused`() {
        // The check the four hand-written sites did not have. Both login gates answer Z on
        // 46.2.3, 46.7.3, 46.8.3 and 46.9.3; a build that boxed either would have taken const/4
        // v0 and return v0 and failed verification on the phone rather than at patch time.
        for (notABoolean in listOf("Ljava/lang/Boolean;", "Ljava/lang/Object;", "I", "V")) {
            val refused = assertThrows(
                "returnEarly(false) was allowed on a method returning $notABoolean",
                IllegalStateException::class.java,
            ) { method(notABoolean).returnEarly(false) }
            assertTrue(refused.message!!.contains("return type"))
        }
    }

    @Test
    fun `removing two flags removes both of them`() {
        // The mask was built with `and`, so two flags with no bits in common produced zero and
        // the method removed nothing at all. One flag worked, which is why it reads as fine.
        val two = field(AccessFlags.PRIVATE, AccessFlags.FINAL, AccessFlags.STATIC)
        two.removeFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
        assertEquals(AccessFlags.STATIC.value, two.accessFlags)

        val one = field(AccessFlags.PRIVATE, AccessFlags.STATIC)
        one.removeFlags(AccessFlags.PRIVATE)
        assertEquals(AccessFlags.STATIC.value, one.accessFlags)
    }

    @Test
    fun `the literal override edits the result of the literal's own call`() {
        // A literal is loaded a few instructions before the call it is an argument to. Taking
        // the first move-result anywhere after it took whichever call came first, so a call
        // that lands in that gap had its own result overridden and the literal's was left
        // alone. Here the literal is loaded at index 0, an unrelated call takes its result at
        // index 2, and the literal's call is at index 3.
        val unrelated = ImmutableMethodReference(
            "Lcom/example/Other;", "first", emptyList(), "I",
        )
        val owner = ImmutableMethodReference(
            "Lcom/example/Host;", "second", listOf("I"), "I",
        )
        val body = method(
            "I",
            ImmutableInstruction21s(Opcode.CONST_16, 0, 1234),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, unrelated),
            // Into v1, so the literal in v0 is still the literal when its own call reads it.
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, owner),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )

        body.insertLiteralOverride(0, true)

        // The override goes after the move-result at index 4, not the one at index 2.
        assertEquals(Opcode.MOVE_RESULT, opcodeAt(body, 2))
        assertEquals(Opcode.INVOKE_STATIC, opcodeAt(body, 3))
        assertEquals(Opcode.MOVE_RESULT, opcodeAt(body, 4))
        assertEquals(Opcode.CONST, opcodeAt(body, 5))
    }

    @Test
    fun `a settings key's result is the answer of the call it is handed to, not of a call in between`() {
        // Auto advance and comment sort override the value read with a settings key. A call
        // that lands between the key and the lookup has an answer of its own, and the first
        // move-result after the string, which is what both sites took, is that one.
        val ready = ImmutableMethodReference("Lcom/example/Other;", "ready", emptyList(), "Z")
        val lookup = ImmutableMethodReference(
            "Lcom/example/Settings;", "get", listOf("Ljava/lang/String;"), "Z",
        )
        val body = method(
            "Z",
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("panel_auto_scroll")),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ready),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, lookup),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
            ImmutableInstruction11x(Opcode.RETURN, 2),
        )

        assertEquals(4, body.indexOfLiteralCallResult(0))
    }

    @Test
    fun `a literal overwritten before any call is refused`() {
        // The register is loaded, written again, and only then read by a call. The value the
        // call reads is not the literal, so overriding that call's result would be overriding
        // something else entirely.
        val other = ImmutableMethodReference(
            "Lcom/example/Other;", "take", listOf("I"), "I",
        )
        val body = method(
            "I",
            ImmutableInstruction21s(Opcode.CONST_16, 0, 1234),
            ImmutableInstruction21s(Opcode.CONST_16, 0, 5678),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, other),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )

        assertThrows(IllegalStateException::class.java) {
            body.insertLiteralOverride(0, true)
        }
    }

    @Test
    fun `a literal whose call does not take a result is refused rather than guessed at`() {
        val voidCall = ImmutableMethodReference(
            "Lcom/example/Other;", "consume", listOf("I"), "V",
        )
        val body = method(
            "I",
            ImmutableInstruction21s(Opcode.CONST_16, 0, 1234),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, voidCall),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )

        assertThrows(IllegalStateException::class.java) {
            body.insertLiteralOverride(0, true)
        }
    }

    @Test
    fun `a literal buried under a wide write is refused`() {
        // A wide instruction names only the low half of the pair it writes, so a write into v0
        // lands on v1 as well. The walk read the named register alone, so the literal in v1
        // looked untouched and the call below it looked like the literal's own.
        val other = ImmutableMethodReference(
            "Lcom/example/Other;", "take", listOf("I"), "I",
        )
        val body = method(
            "I",
            ImmutableInstruction21s(Opcode.CONST_16, 1, 1234),
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 5678),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, other),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction11x(Opcode.RETURN, 1),
        )

        assertThrows(IllegalStateException::class.java) {
            body.insertLiteralOverride(0, true)
        }
    }

    @Test
    fun `a conversion away from a wide value does not end the literal's life`() {
        // long-to-int reads a pair and writes one register, but its mnemonic says long, so a
        // check that asked whether the opcode mentions a wide value refused this method. The
        // literal in v2 is untouched: nothing here writes v1 or v2.
        val other = ImmutableMethodReference(
            "Lcom/example/Other;", "take", listOf("I"), "I",
        )
        val body = method(
            "I",
            ImmutableInstruction21s(Opcode.CONST_16, 2, 1234),
            ImmutableInstruction12x(Opcode.LONG_TO_INT, 1, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, other),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
            ImmutableInstruction11x(Opcode.RETURN, 2),
        )

        body.insertLiteralOverride(0, true)

        assertEquals(Opcode.MOVE_RESULT, opcodeAt(body, 3))
        assertEquals(Opcode.CONST, opcodeAt(body, 4))
    }

    @Test
    fun `a comparison that lands on the literal is refused`() {
        // cmp-long reads two pairs and writes one narrow register. It was in none of the write
        // opcodes, so writeRegister answered null and the walk never saw v1 being destroyed.
        val other = ImmutableMethodReference(
            "Lcom/example/Other;", "take", listOf("I"), "I",
        )
        val body = method(
            "I",
            ImmutableInstruction21s(Opcode.CONST_16, 1, 1234),
            ImmutableInstruction23x(Opcode.CMP_LONG, 1, 2, 2),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, other),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction11x(Opcode.RETURN, 1),
        )

        assertThrows(IllegalStateException::class.java) {
            body.insertLiteralOverride(0, true)
        }
    }
}
