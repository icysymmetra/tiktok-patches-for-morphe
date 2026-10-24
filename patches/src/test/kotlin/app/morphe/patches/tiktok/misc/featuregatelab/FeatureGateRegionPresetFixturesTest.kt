package app.morphe.patches.tiktok.misc.featuregatelab

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native readers behind the scrolling comments and several-photos presets on TikTok 47.4.3
 * (#98): which key each reads, through which AB read, and which values turn the feature on.
 */
class FeatureGateRegionPresetFixturesTest {
    private val app by lazy {
        val container = Fixtures.dexContainer(Fixtures.apkOf("47.4.3"), Opcodes.getDefault())
        val owners = setOf(
            DANMAKU_READ, DANMAKU_HOLDER, DANMAKU_MASTER, LANDSCAPE_READ, DANMAKU_SERVICE, AWEME,
            PHOTOS_READ, PHOTOS_GATE, PUBLISH, KEYBOARD,
        )
        container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.filter { it.type in owners }
        }.associateBy { it.type }.also { assertEquals(owners, it.keys) }
    }

    @Test
    fun `danmaku_exp is the INT read whose groups one and two turn scrolling comments on`() {
        val read = method(DANMAKU_READ, "invoke")
        assertEquals(listOf("danmaku_exp"), read.strings())
        assertEquals(AB_INT_READ, read.calls().single { it.returnType == "I" }.toString())

        val holder = method(DANMAKU_HOLDER, "<clinit>")
        assertTrue("the holder's lazy group and master", holder.created().containsAll(listOf(DANMAKU_READ, DANMAKU_MASTER)))
        assertEquals("the master is read back as a boolean", listOf("$DANMAKU_HOLDER->LIZLLL:LX/02o5;"),
            method(DANMAKU_HOLDER, "LIZIZ").fields())
        assertEquals("the service asks the master", "$DANMAKU_HOLDER->LIZIZ()Z",
            method(DANMAKU_SERVICE, "LJIIIZ").calls().single().toString())

        // The master keeps TikTok's own strategy switch and ends on group 1 or group 2.
        val master = method(DANMAKU_MASTER, "invoke")
        assertTrue("TikTok's strategy switch still decides first", "strategy_control_enable_danmaku" in master.strings())
        val code = master.implementation!!.instructions.toList()
        val groupReads = code.indices.filter { code[it].getReference<MethodReference>()?.toString() == "$DANMAKU_HOLDER->LIZ()I" }
        assertEquals("the group is read twice at the end", 2, groupReads.size)
        val first = groupReads[0] + 2
        val second = groupReads[1] + 3
        assertEquals(Opcode.IF_EQ, code[first].opcode)
        assertEquals(Opcode.IF_EQ, code[second].opcode)
        // The first comparison's 1 is also what the shared landing passes to Boolean.valueOf.
        assertEquals("the first comparison is with 1", 1, literalIn(code, first, (code[first] as TwoRegisterInstruction).registerB))
        assertEquals("the second comparison is with 2", 2, literalIn(code, second, (code[second] as TwoRegisterInstruction).registerB))
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val landing = { at: Int -> addresses[at] + (code[at] as OffsetInstruction).codeOffset }
        assertEquals("both comparisons land in the same place", landing(first), landing(second))
        assertEquals("Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;",
            code[addresses.indexOf(landing(first))].getReference<MethodReference>().toString())
    }

    @Test
    fun `landscape videos count only after the master and only for value one`() {
        val read = method(LANDSCAPE_READ, LANDSCAPE_METHOD)
        assertEquals(listOf("landscape_support_danmaku"), read.strings())
        assertEquals(AB_INT_READ, read.calls().single { it.returnType == "I" }.toString())
        for (master in listOf(false, true)) for (value in -1..3) {
            assertEquals("master=$master value=$value", master && value == 1, run(read) { call, _ ->
                when (call.toString()) {
                    "LX/08lI;->LJIIIZ()Z" -> if (master) 1 else 0
                    "LX/032O;->LJIIIZ()LX/032O;" -> Any()
                    AB_INT_READ -> value
                    "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;" -> null
                    else -> error("Unexpected call: $call")
                }
            })
        }
    }

    @Test
    fun `each video still carries its own server flag for scrolling comments`() {
        assertEquals(listOf("$AWEME->isSupportDanmaku:Z"), method(AWEME, "isSupportDanmaku").fields())
        val perVideo = method(DANMAKU_SERVICE, "LJIIJ").calls().map { it.toString() }
        assertEquals("the video's flag is read before the master", "$AWEME->isSupportDanmaku()Z", perVideo.first())
        assertTrue("$DANMAKU_HOLDER->LIZIZ()Z" in perVideo)
    }

    @Test
    fun `comment_multi_photo_group turns several photos on for group two only`() {
        val read = method(PHOTOS_READ, "invoke")
        assertEquals(listOf("comment_multi_photo_group"), read.strings())
        assertEquals(AB_INT_READ, read.calls().single { it.returnType == "I" }.toString())
        assertTrue(method(PHOTOS_GATE, "<clinit>").created().contains(PHOTOS_READ))

        val gate = method(PHOTOS_GATE, "LIZ")
        for (group in -1..4) {
            assertEquals("group $group", group == 2, run(gate) { call, _ ->
                when (call.toString()) {
                    "LX/02o5;->getValue()Ljava/lang/Object;" -> group
                    "Ljava/lang/Number;->intValue()I" -> group
                    else -> error("Unexpected call: $call")
                }
            })
        }
        for ((owner, name) in listOf(PUBLISH to "F33", KEYBOARD to "TI", KEYBOARD to "onViewCreated")) {
            assertTrue("$owner $name reads the gate", app.getValue(owner).methods.filter { it.name == name }
                .any { candidate -> candidate.calls().any { it.toString() == "$PHOTOS_GATE->LIZ()Z" } })
        }
    }

    private fun method(owner: String, name: String): Method = app.getValue(owner).methods.single { it.name == name }

    private fun Method.calls() = implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }

    private fun Method.strings() = implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string }

    private fun Method.fields() = implementation!!.instructions.mapNotNull { it.getReference<FieldReference>()?.toString() }

    private fun Method.created() = implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { it.getReference<TypeReference>()?.type }

    /** The literal last written to [register] before [index], in code order. */
    private fun literalIn(code: List<Instruction>, index: Int, register: Int): Int {
        for (i in index - 1 downTo 0) {
            val writer = code[i]
            if (writer is OneRegisterInstruction && writer.registerA == register && writer.opcode.setsRegister()) {
                return (writer as? NarrowLiteralInstruction)?.narrowLiteral ?: error("v$register comes from ${writer.opcode}")
            }
        }
        error("v$register is never written")
    }

    /**
     * Runs a small native gate to its return, with each call answered by [answer] (booleans as
     * 0 and 1). Boolean.valueOf passes its argument through.
     */
    private fun run(method: Method, answer: (MethodReference, List<Any?>) -> Any?): Boolean {
        val implementation = method.implementation!!
        val code = implementation.instructions.toList()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }.dropLast(1)
        val registers = arrayOfNulls<Any>(implementation.registerCount)
        var result: Any? = null
        var index = 0
        repeat(200) {
            val instruction = code[index]
            var branch = false
            when (instruction.opcode) {
                Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST -> registers[(instruction as OneRegisterInstruction).registerA] =
                    (instruction as NarrowLiteralInstruction).narrowLiteral
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                    registers[(instruction as OneRegisterInstruction).registerA] = instruction.getReference<StringReference>()!!.string
                Opcode.SGET_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = Any()
                Opcode.CHECK_CAST -> Unit
                Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE,
                Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE_RANGE -> {
                    val call = instruction.getReference<MethodReference>()!!
                    val args = when (instruction) {
                        is RegisterRangeInstruction -> (0 until instruction.registerCount).map { registers[instruction.startRegister + it] }
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                            instruction.registerF, instruction.registerG).take(instruction.registerCount).map { registers[it] }
                        else -> error("Unexpected invoke shape")
                    }
                    result = if (call.toString() == "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;") args.single()
                    else answer(call, args)
                }
                Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = result
                Opcode.IF_EQ, Opcode.IF_NE -> {
                    instruction as TwoRegisterInstruction
                    val equal = registers[instruction.registerA] == registers[instruction.registerB]
                    branch = if (instruction.opcode == Opcode.IF_EQ) equal else !equal
                }
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val zero = (registers[(instruction as OneRegisterInstruction).registerA] as Int) == 0
                    branch = if (instruction.opcode == Opcode.IF_EQZ) zero else !zero
                }
                Opcode.GOTO, Opcode.GOTO_16 -> branch = true
                Opcode.RETURN, Opcode.RETURN_OBJECT -> return (registers[(instruction as OneRegisterInstruction).registerA] as Int) != 0
                else -> error("Unexpected native gate instruction: ${instruction.opcode}")
            }
            index = if (branch) addresses.indexOf(addresses[index] + (instruction as OffsetInstruction).codeOffset)
                .also { check(it >= 0) { "Native gate branch does not target an instruction" } } else index + 1
        }
        error("Native gate did not return")
    }

    private companion object {
        const val AB_INT_READ = "LX/032O;->LJIIJJI(IILjava/lang/String;Z)I"
        const val DANMAKU_READ = "LX/0IGg;"
        const val DANMAKU_HOLDER = "LX/0IGf;"
        const val DANMAKU_MASTER = "LX/0IGj;"
        const val LANDSCAPE_READ = "Lkotlin/jvm/internal/AFwS170S0000000_30_I1;"
        const val LANDSCAPE_METHOD = "invoke\$550"
        const val DANMAKU_SERVICE = "Lcom/ss/android/ugc/aweme/service/DanmakuServiceImpl;"
        const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        const val PHOTOS_READ = "LX/0HJj;"
        const val PHOTOS_GATE = "LX/0HJi;"
        const val PUBLISH = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/viewmodel/CommentPublishViewModel;"
        const val KEYBOARD = "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/PortraitInputKeyboard;"
    }
}
