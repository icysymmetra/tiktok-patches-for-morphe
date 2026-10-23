package app.morphe.patches.tiktok.interaction.hdupload

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Always upload in HD rewrites the value of every `Keva.getInt` of TikTok's stored HD choice in
 * the class that decides HD uploads. On each declared build that class is found by its decision
 * method alone, it holds exactly three such reads (the decision, the analytics string and the
 * "turned on by hand" check), and nothing else in the app reads the key: its one other use is
 * the post page's switch storing it. Each read is `const-string` of the key, `getInt` on three
 * registers, `move-result`, so the hook rewrites the result register in place and needs no
 * register of its own.
 */
class HdUploadChoiceAnchorsTest {
    private class Use(val classDef: ClassDef, val method: Method)

    private companion object {
        const val KEVA = "Lcom/bytedance/keva/Keva;"
        val BRANCHES = setOf(
            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
        )
    }

    @Test
    fun `each declared build has one HD decision whose class holds every read of the choice`() {
        val shapes = mutableMapOf<String, List<String>>()
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val decisions = mutableListOf<Use>()
            val keyUsers = mutableListOf<Use>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation == null) continue
                        if (loadsKey(method)) keyUsers += Use(classDef, method)
                        if (HdUploadDecisionFingerprint.takes(method, classDef)) decisions += Use(classDef, method)
                    }
                }
            }

            assertEquals("decision methods: ${decisions.map { it.classDef.type }}", 1, decisions.size)
            val decision = decisions.single()
            assertEquals("(J)Z", signature(decision.method))
            assertEquals(1, hdChoiceReads(decision.method).size)

            val readers = keyUsers.filter { hdChoiceReads(it.method).isNotEmpty() }
            assertEquals(
                "every read of the choice sits in the decision's class",
                setOf(decision.classDef.type),
                readers.map { it.classDef.type }.toSet(),
            )
            assertEquals(
                "the decision, the analytics string and the by-hand check",
                listOf("()Z", "(J)Ljava/lang/String;", "(J)Z"),
                readers.map { signature(it.method) }.sorted(),
            )

            // Fail closed: the only other method loading the key is the post page's switch, which
            // stores it. A read in some other shape would be one the hook leaves on TikTok's value.
            val others = keyUsers.filter { hdChoiceReads(it.method).isEmpty() }
            assertEquals("other users of the key: ${others.map { "${it.classDef.type}->${it.method.name}" }}", 1, others.size)
            val writer = others.single().method
            assertTrue(
                "the other user stores the choice and never reads it",
                calls(writer, "Lcom/bytedance/keva/Keva;->storeInt(Ljava/lang/String;I)V") &&
                    !calls(writer, KEVA_GET_INT),
            )

            val shape = readers.sortedBy { signature(it.method) }.map { use ->
                val method = use.method
                val reads = hdChoiceReads(method)
                assertEquals("${method.name}: one read", 1, reads.size)
                val instructions = method.implementation!!.instructions.toList()
                val result = instructions[reads.single()]
                assertEquals(Opcode.MOVE_RESULT, result.opcode)
                val register = (result as OneRegisterInstruction).registerA
                // The inserted call names only that register, through the range form, and writes
                // it back with a plain move-result, so the frame has to hold it and nothing more.
                assertTrue(
                    "${method.name}: v$register inside its frame",
                    register < method.implementation!!.registerCount,
                )
                val call = instructions[reads.single() - 1] as FiveRegisterInstruction
                assertEquals(KEVA_GET_INT, ((call as ReferenceInstruction).reference as MethodReference).toString())
                assertTrue(
                    "${method.name}: the decision tests the value it read",
                    instructions.drop(reads.single() + 1).take(4).any { it.opcode in BRANCHES },
                )
                "${signature(method)} v$register"
            }
            shapes[apk.name] = shape
        }
        assertEquals("the same read shapes on every declared build: $shapes", 1, shapes.values.toSet().size)
    }

    /** Only TikTok's shape is a read: the HD key loaded into the key register just before a getInt with a result. */
    @Test
    fun `a read counts only when the HD key goes into the key register just before`() {
        val getInt = ImmutableMethodReference(KEVA, "getInt", listOf("Ljava/lang/String;", "I"), "I")
        fun key(register: Int, value: String = HD_CHOICE_KEY) =
            ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))
        fun default(register: Int) = ImmutableInstruction11n(Opcode.CONST_4, register, 0)
        fun call(keyRegister: Int, reference: ImmutableMethodReference = getInt) =
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, keyRegister, 2, 0, 0, reference)
        fun result(register: Int) = ImmutableInstruction11x(Opcode.MOVE_RESULT, register)
        val done = ImmutableInstruction11x(Opcode.RETURN, 3)

        assertEquals("TikTok's shape", listOf(3), synthetic(key(1), default(2), call(1), result(3), done).let(::hdChoiceReads))
        assertEquals("key right before", listOf(2), synthetic(key(1), call(1), result(3), done).let(::hdChoiceReads))
        assertEquals("another key", emptyList<Int>(),
            synthetic(key(1, "USER_HD_VIDEO_TIPS"), call(1), result(3), done).let(::hdChoiceReads))
        assertEquals("the key in another register", emptyList<Int>(),
            synthetic(key(4), call(1), result(3), done).let(::hdChoiceReads))
        assertEquals("the key register overwritten", emptyList<Int>(),
            synthetic(key(1), default(1), call(1), result(3), done).let(::hdChoiceReads))
        assertEquals("the key too far back", emptyList<Int>(),
            synthetic(key(1), default(2), default(5), default(6), call(1), result(3), done).let(::hdChoiceReads))
        assertEquals("no result taken", emptyList<Int>(), synthetic(key(1), call(1), done).let(::hdChoiceReads))
        assertEquals("a store", emptyList<Int>(), synthetic(key(1), call(1, ImmutableMethodReference(KEVA, "storeInt",
            listOf("Ljava/lang/String;", "I"), "V")), done).let(::hdChoiceReads))
    }

    private fun synthetic(vararg instructions: Instruction) = ImmutableMethod(
        "LFixture;", "read", emptyList(), "I",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(8, instructions.toList(), emptyList(), emptyList()),
    )

    private fun signature(method: Method) =
        method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun loadsKey(method: Method) = method.implementation!!.instructions.any {
        it.opcode == Opcode.CONST_STRING &&
            ((it as ReferenceInstruction).reference as? StringReference)?.string == HD_CHOICE_KEY
    }

    private fun calls(method: Method, descriptor: String) = method.implementation!!.instructions.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == descriptor
    }
}
