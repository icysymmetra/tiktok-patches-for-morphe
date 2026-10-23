package app.morphe.patches.tiktok.misc.branding

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run beside the store app swaps one call in AppLog's package header loader: the write of the
 * running package into `package`, the field TikTok's servers register a device under. On each
 * declared build the loader is found by three of its header strings alone, it writes `package`
 * twice (the running package, and a configured override TikTok never sets, which comes from a
 * field) and only the first is counted. The write is `put` on three low registers whose result
 * goes unread, so a static call on the same registers takes its place.
 */
class RegistrationPackageAnchorsTest {
    @Test
    fun `each declared build has one package header loader with one running package write`() {
        val shapes = mutableMapOf<String, String>()
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val loaders = mutableListOf<Method>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (RegistrationPackageHeaderFingerprint.takes(method, classDef)) loaders += method
                    }
                }
            }

            assertEquals("loaders: ${loaders.map { "${it.definingClass}->${it.name}" }}", 1, loaders.size)
            val loader = loaders.single()
            assertFalse("the loader reads its context off this", AccessFlags.STATIC.isSet(loader.accessFlags))
            val instructions = loader.implementation!!.instructions.toList()

            val writes = registrationPackageWrites(loader)
            assertEquals("running package writes", 1, writes.size)
            val index = writes.single()
            val put = instructions[index] as FiveRegisterInstruction
            assertEquals(JSON_PUT, (put as ReferenceInstruction).reference.toString())
            // The header is the loader's one parameter, p1.
            assertEquals("the header register", loader.implementation!!.registerCount - 1, put.registerC)
            assertTrue("registers a static call can name", maxOf(put.registerC, put.registerD, put.registerE) <= 15)
            assertNotEquals(
                "the write's result goes unread",
                Opcode.MOVE_RESULT_OBJECT,
                instructions.getOrNull(index + 1)?.opcode,
            )

            // Fail closed: the loader's other `package` write is the override, whose value is a
            // field read, and the counted one is the only write of getPackageName's result there.
            // The override's key is the same const-string, loaded before the branch between them,
            // so this looks back as far as the key's last write, wherever it is.
            val packageKeyed = instructions.indices.filter { at ->
                val call = instructions[at]
                if (call.opcode != Opcode.INVOKE_VIRTUAL || (call as ReferenceInstruction).reference.toString() != JSON_PUT) {
                    return@filter false
                }
                val key = lastWrite(instructions, (call as FiveRegisterInstruction).registerD, at)?.let { instructions[it] }
                key?.opcode == Opcode.CONST_STRING &&
                    ((key as ReferenceInstruction).reference as StringReference).string == PACKAGE_KEY
            }
            assertEquals("writes under the package key", 2, packageKeyed.size)
            assertEquals("the counted write is the first", index, packageKeyed.first())
            val override = packageKeyed.last()
            val overrideValue = lastWrite(instructions, (instructions[override] as FiveRegisterInstruction).registerE, override)
            assertEquals("the override's value is a field read", Opcode.IGET_OBJECT, overrideValue?.let { instructions[it].opcode })

            shapes[apk.name] = "${instructions.size} instructions, write at $index on " +
                "v${put.registerC} v${put.registerD} v${put.registerE}"
        }
        assertEquals("the same loader shape on every declared build: $shapes", 1, shapes.values.toSet().size)
    }

    /** Only TikTok's shape counts: the package key and the running package in the put's own registers. */
    @Test
    fun `a write counts only when the key is package and the value is the running package`() {
        assertEquals("TikTok's shape", listOf(9), registrationPackageWrites(loader()))
        assertEquals("the override, read from a field", emptyList<Int>(),
            registrationPackageWrites(loader(value = overrideRead())))
        assertEquals("another key", emptyList<Int>(),
            registrationPackageWrites(loader(key = "real_package_name")))
        assertEquals("the key register overwritten", emptyList<Int>(),
            registrationPackageWrites(loader(beforePut = listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("x"))))))
        assertEquals("the value register overwritten", emptyList<Int>(),
            registrationPackageWrites(loader(beforeKey = listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("x"))))))
        assertEquals("the running package read too far back", emptyList<Int>(),
            registrationPackageWrites(loader(beforeKey = List(9) { ImmutableInstruction11x(Opcode.MOVE_RESULT, 0) })))
        assertEquals("a name that isn't the context's own package", emptyList<Int>(),
            registrationPackageWrites(loader(value = listOf(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                    ImmutableMethodReference("Landroid/content/Context;", "getOpPackageName", emptyList(), STRING)),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ))))
        assertEquals("a put of a long", emptyList<Int>(), registrationPackageWrites(loader(
            put = ImmutableMethodReference(JSON, "put", listOf(STRING, "J"), JSON),
        )))
    }

    private fun loader(
        key: String = PACKAGE_KEY,
        value: List<Instruction> = runningPackageRead(),
        beforeKey: List<Instruction> = listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 5, CONFIG),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 0, OVERRIDE),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference("Landroid/text/TextUtils;", "isEmpty", listOf("Ljava/lang/CharSequence;"), "Z")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        ),
        beforePut: List<Instruction> = listOf(ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4)),
        put: ImmutableMethodReference = ImmutableMethodReference(JSON, "put", listOf(STRING, "Ljava/lang/Object;"), JSON),
    ): Method {
        // 47.x: getPackageName into v2, the override check, "package" into v1, put {v6, v1, v2}.
        val body = listOf<Instruction>(ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 5, CONTEXT)) + value + beforeKey +
            ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(key)) + beforePut +
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 6, 1, 2, 0, 0, put) +
            ImmutableInstruction11x(Opcode.RETURN, 3)
        return ImmutableMethod(
            "LFixture;", "LIZ", emptyList(), "Z", AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(7, body, emptyList(), emptyList()),
        )
    }

    /** The last instruction before [before] that writes [register], read straight back up the method. */
    private fun lastWrite(instructions: List<Instruction>, register: Int, before: Int): Int? =
        (before - 1 downTo 0).firstOrNull {
            instructions[it].opcode.setsRegister() && (instructions[it] as? OneRegisterInstruction)?.registerA == register
        }

    private fun runningPackageRead(): List<Instruction> = listOf(
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Landroid/content/Context;", "getPackageName", emptyList(), STRING)),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
    )

    private fun overrideRead(): List<Instruction> = listOf(
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 5, CONFIG),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 2, OVERRIDE),
    )

    private companion object {
        const val JSON = "Lorg/json/JSONObject;"
        const val STRING = "Ljava/lang/String;"
        val CONTEXT = ImmutableFieldReference("LFixture;", "LJ", "Landroid/content/Context;")
        val CONFIG = ImmutableFieldReference("LFixture;", "LJFF", "LConfig;")
        val OVERRIDE = ImmutableFieldReference("LConfig;", "LJ", STRING)
    }
}
