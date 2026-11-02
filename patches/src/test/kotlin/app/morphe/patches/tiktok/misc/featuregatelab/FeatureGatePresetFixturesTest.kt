package app.morphe.patches.tiktok.misc.featuregatelab

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native value contracts reviewed for the two single-key presets on TikTok 47.4.3. */
class FeatureGatePresetFixturesTest {
    private val app by lazy {
        val container = Fixtures.dexContainer(Fixtures.apkOf("47.4.3"), Opcodes.getDefault())
        val owners = setOf("Ln7j/e;", "LX/0EzL;", "LX/0EzK;", "LX/0CUM;", "LX/17E0;")
        container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.filter { it.type in owners }
        }.associateBy { it.type }.also { assertEquals(owners, it.keys) }
    }

    @Test
    fun `the hashtag switch and maximum use separate typed reads`() {
        val switch = method("Ln7j/e;", "LIZJ")
        val maximum = method("Ln7j/e;", "LIZ")
        assertEquals(listOf("studio_publish_limit_hashtag_switch"), switch.strings())
        assertEquals(listOf("studio_publish_hashtag_max_limit"), maximum.strings())
        val booleanRead = switch.calls().single { it.returnType == "Z" }
        val intRead = maximum.calls().single { it.returnType == "I" }
        assertEquals(listOf("I", "Ljava/lang/String;", "Z", "Z"), booleanRead.parameterTypes)
        assertEquals(listOf("I", "I", "Ljava/lang/String;", "Z"), intRead.parameterTypes)
        assertEquals("both reads reach the existing app AB boundary", intRead.definingClass, booleanRead.definingClass)
    }

    @Test
    fun `the lazy UI reader and independent catalog reader share the same INT boundary`() {
        val lazyRead = method("LX/0EzL;", "invoke")
        assertEquals(listOf("tt_cmt_new_emoji"), lazyRead.strings())
        val intRead = lazyRead.calls().single { it.returnType == "I" }
        assertEquals(listOf("I", "I", "Ljava/lang/String;", "Z"), intRead.parameterTypes)
        val catalogRead = method("LX/0CUM;", "LIZ")
        assertEquals(intRead.toString(), catalogRead.calls().single {
            it.returnType == "I" && it.parameterTypes == intRead.parameterTypes
        }.toString())
        assertTrue(method("LX/0EzK;", "<clinit>").implementation!!.instructions.any {
            it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == "LX/0EzL;"
        })
        assertEquals(listOf("tt_im_new_emoji", "tt_cmt_new_emoji", "tt_repost_new_emoji"),
            method("LX/0CUM;", "LIZIZ").strings())
        assertTrue(method("LX/17E0;", "LIZJ").calls().any {
            it.definingClass == "LX/0CUM;" && it.name == "LIZIZ" && it.returnType == "Z"
        })
        assertTrue("eligible loading retains the native catalog request", method("LX/17E0;", "LJ").calls().any {
            it.definingClass == "Lcom/ss/android/ugc/aweme/ui/adapter/newemoji/INewEmojiApi;" && it.name == "getEmojis"
        })
    }

    @Test
    fun `group two permits sending and viewing while native availability and downgrade still win`() {
        for (group in 0..3) {
            assertEquals("send group $group", group == 2 || group == 3,
                gate("LX/0EzK;", "LIZIZ", group, available = true, downgraded = false))
            assertEquals("view group $group", group != 1,
                gate("LX/0EzK;", "LIZJ", group, available = true, downgraded = false))
        }
        for (available in listOf(false, true)) for (downgraded in listOf(false, true)) {
            for (reader in listOf("LIZIZ", "LIZJ")) {
                assertEquals("$reader availability=$available downgrade=$downgraded", available && !downgraded,
                    gate("LX/0EzK;", reader, 2, available, downgraded))
            }
        }
        // Other experiments stay in their disabled group. The comment override alone must be
        // enough for the catalog loader, and it must still respect service availability.
        assertFalse(gate("LX/0CUM;", "LIZIZ", 1, available = true, downgraded = false))
        assertTrue(gate("LX/0CUM;", "LIZIZ", 2, available = true, downgraded = false))
        assertFalse(gate("LX/0CUM;", "LIZIZ", 2, available = false, downgraded = false))
    }

    private fun method(owner: String, name: String): Method = app.getValue(owner).methods.single { it.name == name }

    private fun Method.calls() = implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }

    private fun Method.strings() = implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string }

    /** Executes the small native boolean gates, substituting only AB answers and service state. */
    private fun gate(owner: String, name: String, group: Int, available: Boolean, downgraded: Boolean): Boolean {
        val implementation = method(owner, name).implementation!!
        val code = implementation.instructions.toList()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }.dropLast(1)
        val registers = arrayOfNulls<Any>(implementation.registerCount)
        var result: Any? = null
        var index = 0
        repeat(100) {
            val instruction = code[index]
            var branch = false
            when (instruction.opcode) {
                Opcode.CONST_4 -> registers[(instruction as OneRegisterInstruction).registerA] =
                    (instruction as NarrowLiteralInstruction).narrowLiteral
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                    registers[(instruction as OneRegisterInstruction).registerA] = instruction.getReference<StringReference>()!!.string
                Opcode.SGET_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = Any()
                Opcode.SGET_BOOLEAN -> {
                    assertEquals("LX/0CUM;->LIZJ:Z", instruction.getReference<FieldReference>().toString())
                    registers[(instruction as OneRegisterInstruction).registerA] = if (downgraded) 1 else 0
                }
                Opcode.INVOKE_VIRTUAL -> {
                    assertEquals("Ljava/lang/Object;->getClass()Ljava/lang/Class;", instruction.getReference<MethodReference>().toString())
                    result = Any::class.java
                }
                Opcode.INVOKE_STATIC -> {
                    val call = instruction.getReference<MethodReference>()!!
                    result = when ("${call.definingClass}->${call.name}") {
                        "LX/0EzK;->LIZ" -> group
                        "LX/0CUM;->LIZJ" -> if (available) 1 else 0
                        "LX/0CUM;->LIZLLL" -> if (gate("LX/0CUM;", "LIZLLL", group, available, downgraded)) 1 else 0
                        "LX/0CUM;->LIZ" -> if (registers[(instruction as FiveRegisterInstruction).registerC] == "tt_cmt_new_emoji") group else 1
                        else -> error("Unexpected native gate call: $call")
                    }
                }
                Opcode.MOVE_RESULT -> registers[(instruction as OneRegisterInstruction).registerA] = result
                Opcode.IF_EQ, Opcode.IF_NE -> {
                    instruction as TwoRegisterInstruction
                    val equal = registers[instruction.registerA] == registers[instruction.registerB]
                    branch = if (instruction.opcode == Opcode.IF_EQ) equal else !equal
                }
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val zero = (registers[(instruction as OneRegisterInstruction).registerA] as Int) == 0
                    branch = if (instruction.opcode == Opcode.IF_EQZ) zero else !zero
                }
                Opcode.RETURN -> return (registers[(instruction as OneRegisterInstruction).registerA] as Int) != 0
                else -> error("Unexpected native gate instruction: ${instruction.opcode}")
            }
            index = if (branch) addresses.indexOf(addresses[index] + (instruction as OffsetInstruction).codeOffset)
                .also { check(it >= 0) { "Native gate branch does not target an instruction" } } else index + 1
        }
        error("Native gate did not return")
    }
}
