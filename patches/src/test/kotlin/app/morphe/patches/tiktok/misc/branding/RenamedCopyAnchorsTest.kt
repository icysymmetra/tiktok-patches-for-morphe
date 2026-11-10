package app.morphe.patches.tiktok.misc.branding

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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Run beside the store app's rewrites of a renamed copy's leftover package uses rest on,
 * held to each declared TikTok build.
 *
 * The provider shell fills its authority and permission templates in one method whose every
 * return the patch hooks; the shell stores that method's answer as a ProviderInfo's authority,
 * and the multiprocess settings provider keeps the authority it is attached with, so the names
 * the extension answers are the ones TikTok's own lookups then carry. The live wallpaper data
 * provider loads its authority once into a register nothing else writes. And exactly three
 * methods ask Kotlin's `contains` whether some text holds the store package, each once, right
 * after loading it: the media path resolver and the two Family Pairing activity rules.
 */
class RenamedCopyAnchorsTest {
    @Test
    fun `the provider shell fills its templates in one method whose answer becomes an authority`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val fills = mutableListOf<Pair<ClassDef, Method>>()
            var provider: ClassDef? = null
            forEachClass(apk) { classDef ->
                if (classDef.type == MULTIPROCESS_PROVIDER) provider = classDef
                classDef.methods.forEach { method ->
                    if (ProviderNameTemplateFingerprint.takes(method, classDef)) fills += classDef to method
                }
            }

            assertEquals("$version: fills ${fills.map { (owner, method) -> "${owner.type}->${method.name}" }}", 1, fills.size)
            val (shell, fill) = fills.single()
            assertFalse("$version: the fill is static", AccessFlags.STATIC.isSet(fill.accessFlags))
            val instructions = fill.implementation!!.instructions.toList()
            assertTrue("$version: the fill doesn't read the running package", instructions.any { calls(it, GET_PACKAGE_NAME) })
            assertTrue("$version: the fill has no return to hook", instructions.any { it.opcode == Opcode.RETURN_OBJECT })

            // The shell stores what the fill answers as a ProviderInfo's authority.
            val fillReference = "${fill.definingClass}->${fill.name}(${fill.parameterTypes.joinToString("")})${fill.returnType}"
            val storesAuthority = shell.methods.any { method ->
                val body = method.implementation?.instructions?.toList() ?: return@any false
                body.indices.any { at ->
                    (body[at] as? ReferenceInstruction)?.reference?.toString() == fillReference &&
                        body.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
                        (body.getOrNull(at + 2) as? ReferenceInstruction)?.reference?.let { it is FieldReference && it.name == "authority" } == true
                }
            }
            assertTrue("$version: nothing in ${shell.type} stores the fill's answer as an authority", storesAuthority)

            // The multiprocess settings provider keeps the authority it is attached with.
            val providerClass = provider ?: error("$version: $MULTIPROCESS_PROVIDER wasn't seen")
            val attach = providerClass.methods.single { it.name == "attachInfo" }
            val body = attach.implementation!!.instructions.toList()
            // The value stored is the one read off the ProviderInfo it's attached with (the last
            // parameter), traced register to register, not just a read and a store side by side.
            val providerInfo = attach.implementation!!.registerCount - 1
            val keeps = body.indices.any { read ->
                val get = body[read] as? TwoRegisterInstruction
                if (body[read].opcode != Opcode.IGET_OBJECT || get == null || get.registerB != providerInfo ||
                    (body[read] as ReferenceInstruction).reference.toString() != PROVIDER_INFO_AUTHORITY
                ) {
                    return@any false
                }
                val value = get.registerA
                val store = (read + 1 until body.size).firstOrNull { body[it].opcode == Opcode.SPUT_OBJECT && (body[it] as OneRegisterInstruction).registerA == value }
                    ?: return@any false
                val field = (body[store] as ReferenceInstruction).reference as FieldReference
                field.definingClass == MULTIPROCESS_PROVIDER && field.type == "Ljava/lang/String;" &&
                    (read + 1 until store).none { (body[it] as? OneRegisterInstruction)?.registerA == value && body[it].opcode.setsRegister() }
            }
            assertTrue("$version: attachInfo doesn't keep the ProviderInfo's authority in a static String field of its own", keeps)
        }
    }

    @Test
    fun `the wallpaper caller's authority is loaded once into a register nothing else writes`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val initializers = mutableListOf<Method>()
            forEachMethod(apk) { classDef, method ->
                if (WallpaperCallerUrisFingerprint.takes(method, classDef)) initializers += method
            }

            assertEquals("$version: initializers ${initializers.map { it.definingClass }}", 1, initializers.size)
            val initializer = initializers.single()
            val loads = stringLoads(initializer, WALLPAPER_AUTHORITY)
            assertEquals("$version: loads of the authority", 1, loads.size)
            val instructions = initializer.implementation!!.instructions.toList()
            val register = (instructions[loads.single()] as OneRegisterInstruction).registerA
            val after = instructions.drop(loads.single() + 1)
            assertTrue("$version: the authority register is written again after its load", after.none { writes(it, register) })
            assertTrue("$version: the authority is never appended to a URI", after.any { appends(it, register) })
        }
    }

    @Test
    fun `each declared build has a path resolver and two activity rules that check for TikTok's own package`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val checks = mutableListOf<Method>()
            forEachMethod(apk) { classDef, method ->
                if (OwnPackageCheckFingerprint.takes(method, classDef)) checks += method
            }

            val names = checks.map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString()})${it.returnType}" }
            assertEquals("$version: checks $names", OWN_PACKAGE_CHECKS, checks.size)
            val rules = checks.filter { it.parameterTypes.map(CharSequence::toString) == listOf(INTENT) && it.returnType == "Z" }
            assertEquals("$version: activity rules among $names", 2, rules.size)
            val resolver = (checks - rules.toSet()).single()
            assertEquals("$version: the resolver answers a path", STRING, resolver.returnType)
            assertEquals("$version: the resolver takes the URI second", URI, resolver.parameterTypes.getOrNull(1)?.toString())
            checks.forEach { check ->
                val sites = storePackageChecks(check)
                assertEquals("$version: ${check.definingClass}->${check.name} checks the store package once", 1, sites.size)
                // The patch puts the extension's check where contains is, so its answer has to be
                // read straight after, as contains' is.
                assertEquals(
                    "$version: ${check.definingClass}->${check.name} reads contains' answer next",
                    Opcode.MOVE_RESULT,
                    check.implementation!!.instructions.elementAt(sites.single() + 2).opcode,
                )
                // TikTok Asia's package is asked about the same way, so the swap leaves a pair.
                assertTrue(
                    "$version: ${check.definingClass}->${check.name} doesn't check TikTok Asia's package too",
                    stringLoads(check, ASIA_PACKAGE).isNotEmpty(),
                )
            }
        }
    }

    /** Only TikTok's shape counts: the load fed straight to Kotlin's `contains` as the text looked for. */
    @Test
    fun `a check counts only when the load is what contains looks for`() {
        assertEquals("TikTok's shape", listOf(1), storePackageChecks(check()))
        assertEquals("a jumbo load", listOf(1), storePackageChecks(check(
            load = ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference(STORE_PACKAGE)),
        )))
        assertEquals("another package", emptyList<Int>(), storePackageChecks(check(string = ASIA_PACKAGE)))
        assertEquals("the load as the text searched, not the text looked for", emptyList<Int>(), storePackageChecks(check(
            call = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 0, 1, 2, 0, 0, CONTAINS),
        )))
        assertEquals("an installed-app check", emptyList<Int>(), storePackageChecks(check(
            call = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 1, 0, 0, 0, 0,
                ImmutableMethodReference("LX/0ILb;", "LIZ", listOf("Landroid/content/Context;", STRING), "Z")),
        )))
        assertEquals("a method that answers text", emptyList<Int>(), storePackageChecks(check(
            call = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 0, 2, 0, 0,
                ImmutableMethodReference("Lkotlin/text/w;", "LJJIII", listOf(CHAR_SEQUENCE, CHAR_SEQUENCE, "Z"), STRING)),
        )))
        assertEquals("an instruction between", emptyList<Int>(), storePackageChecks(check(
            between = listOf(ImmutableInstruction11n(Opcode.CONST_4, 2, 0)),
        )))
        assertEquals("a virtual call", emptyList<Int>(), storePackageChecks(check(
            call = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 1, 0, 2, 0, 0, CONTAINS),
        )))
    }

    /** v1 holds the text searched, v0 the text looked for and v2 the ignore-case flag, as 47.x has them. */
    private fun check(
        string: String = STORE_PACKAGE,
        load: Instruction = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(string)),
        between: List<Instruction> = emptyList(),
        call: Instruction = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 0, 2, 0, 0, CONTAINS),
    ): Method {
        val body = listOf<Instruction>(ImmutableInstruction11n(Opcode.CONST_4, 2, 0)) + load + between + call +
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0) + ImmutableInstruction11x(Opcode.RETURN, 0)
        return ImmutableMethod(
            "LFixture;", "LIZ", emptyList(), "Z", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(4, body, emptyList(), emptyList()),
        )
    }

    private fun forEachClass(apk: File, visit: (ClassDef) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach(visit)
        }
    }

    private fun forEachMethod(apk: File, visit: (ClassDef, Method) -> Unit) {
        forEachClass(apk) { classDef -> classDef.methods.forEach { method -> visit(classDef, method) } }
    }

    private fun calls(instruction: Instruction, reference: String): Boolean =
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

    private fun writes(instruction: Instruction, register: Int): Boolean {
        if (!instruction.opcode.setsRegister()) return false
        val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
        return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
    }

    private fun appends(instruction: Instruction, register: Int): Boolean {
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return reference.definingClass == "Ljava/lang/StringBuilder;" && reference.name == "append" &&
            (instruction as? FiveRegisterInstruction)?.registerD == register
    }

    private companion object {
        const val MULTIPROCESS_PROVIDER = "Lcom/ss/android/common/util/MultiProcessSharedProvider;"
        const val PROVIDER_INFO_AUTHORITY = "Landroid/content/pm/ProviderInfo;->authority:Ljava/lang/String;"
        const val INTENT = "Landroid/content/Intent;"
        const val URI = "Landroid/net/Uri;"
        const val STRING = "Ljava/lang/String;"
        const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
        val CONTAINS = ImmutableMethodReference("Lkotlin/text/c0;", "LJJIJIIJI", listOf(CHAR_SEQUENCE, CHAR_SEQUENCE, "Z"), "Z")
    }
}
