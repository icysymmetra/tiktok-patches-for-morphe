package app.morphe.patches.tiktok.interaction.searchsuggestions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchSuggestionsNativeHooksTest {
    @Test
    fun `both native first-screen branches pass their actual result through the hook`() {
        val method = method("LX/0OiL;", "LIZ", emptyList(), "Z", 3, true, """
            sget v2, LX/0OiL;->LIZIZ:I
            const/4 v1, 0x1
            if-eq v2, v1, :result
            const/4 v0, 0x2
            if-eq v2, v0, :result
            const/4 v1, 0x0
            :result
            return v1
        """)
        method.hookFirstScreenCache()
        for (setting in 0..3) for (hide in listOf(false, true)) {
            val expectedNative = if (setting == 1 || setting == 2) 1 else 0
            var hookCalls = 0
            val result = run(method, cacheSetting = setting) { name, args ->
                assertEquals("filterCachedSuggestions", name)
                assertEquals(listOf(expectedNative), args)
                hookCalls++
                if (hide) 0 else args.single()
            }
            assertEquals(if (hide) 0 else expectedNative, result)
            assertEquals("A branch to the native return must not skip the hook", 1, hookCalls)
        }
    }

    @Test
    fun `initial parameter hook receives the original key and object without losing native work`() {
        val method = method("LX/0Bav;", "LIZ", listOf("Ljava/lang/String;"), "Ljava/lang/Object;", 3, true, """
            invoke-static {}, LX/0BYX;->LJ()LX/0BYX;
            move-result-object v1
            const/4 v0, 0x0
            invoke-virtual {v1, p0, v0}, LX/0BYX;->LJIIJJI(Ljava/lang/String;Z)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """)
        method.hookInitialLynxParameter()
        for (key in listOf("show_suggest_search_words", "is_lynx_request_suggest", "history_setting")) {
            for (original in listOf(null, true, 9, "native", Any())) for (override in listOf(false, true)) {
                val replacement = Any()
                var hookCalls = 0
                val result = run(method, nativeValue = original, initial = mapOf(2 to key)) { name, args ->
                    assertEquals("filterLynxParameter", name)
                    assertEquals(key, args[0])
                    assertSame(original, args[1])
                    hookCalls++
                    if (override) replacement else args[1]
                }
                assertSame(if (override) replacement else original, result)
                assertEquals(1, hookCalls)
            }
        }
    }

    @Test
    fun `reentry filters the returned integer before the native payload write`() {
        // Exact 46.2.3 GS boundary registers, including the 21-register native frame.
        // Earlier native setup owns v2 (payload), v5 (true), and v13 (false).
        val method = method(
            "Lcom/ss/android/ugc/aweme/search/middle/DynamicSingleIntermediateFragmentNew;", "GS",
            listOf("Lcom/ss/android/ugc/aweme/search/middle/SearchStartViewModel;", "Ljava/lang/Boolean;"),
            "V", 21, false, """
                invoke-static {}, LX/0BZD;->LJIIIZ()LX/0BZD;
                move-result-object v8
                const/16 v0, 0x7c00
                const-string v1, "show_suggest_search_words"
                invoke-virtual {v8, v0, v13, v1, v5}, LX/0BZD;->LJIIJJI(IILjava/lang/String;Z)I
                move-result v0
                invoke-virtual {v2, v1, v0}, Lorg/json/JSONObject;->put(Ljava/lang/String;I)Lorg/json/JSONObject;
                const-string v1, "is_lynx_request_suggest"
                invoke-virtual {v2, v1, v13}, Lorg/json/JSONObject;->put(Ljava/lang/String;Z)Lorg/json/JSONObject;
                return-void
            """,
        )
        method.hookReentryVisibility()
        for (native in listOf(0, 1, 7)) for (hide in listOf(false, true)) {
            val position = "homepage_hot"
            val payload = linkedMapOf<String, Any?>("search_position" to position)
            var hookCalls = 0
            run(method, nativeValue = native, initial = mapOf(2 to payload, 5 to 1, 13 to 0)) { name, args ->
                assertEquals("filterReentryVisibility", name)
                assertEquals(listOf(native), args)
                hookCalls++
                if (hide) 0 else args.single()
            }
            assertEquals(if (hide) 0 else native, payload["show_suggest_search_words"])
            assertEquals(0, payload["is_lynx_request_suggest"])
            assertSame(position, payload["search_position"])
            assertEquals(1, hookCalls)
            assertEquals(21, method.implementation!!.registerCount)
        }
    }

    private fun method(
        owner: String, name: String, parameters: List<String>, returns: String,
        registers: Int, static: Boolean, body: String,
    ) = MutableMethod(ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
        AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null),
    )).apply { addInstructionsWithLabels(0, body) }

    /** Executes the emitted caller segments. Extension return values are supplied separately. */
    private fun run(
        method: MutableMethod, cacheSetting: Int = 0, nativeValue: Any? = null,
        initial: Map<Int, Any?> = emptyMap(), filter: (String, List<Any?>) -> Any?,
    ): Any? {
        val registers = arrayOfNulls<Any>(method.implementation!!.registerCount)
        initial.forEach { (register, value) -> registers[register] = value }
        var address = 0
        val instructions = method.implementation!!.instructions.associate {
            val here = address
            address += it.codeUnits
            here to it
        }
        var pc = 0
        var pending: Any? = null
        var nativeReads = 0
        repeat(instructions.size * 3) {
            val instruction = instructions.getValue(pc)
            when (instruction.opcode) {
                Opcode.SGET -> registers[(instruction as OneRegisterInstruction).registerA] = cacheSetting
                Opcode.CONST_4, Opcode.CONST_16 -> registers[(instruction as OneRegisterInstruction).registerA] =
                    (instruction as NarrowLiteralInstruction).narrowLiteral
                Opcode.CONST_STRING -> registers[(instruction as OneRegisterInstruction).registerA] =
                    ((instruction as ReferenceInstruction).reference as StringReference).string
                Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT ->
                    registers[(instruction as OneRegisterInstruction).registerA] = pending
                Opcode.IF_EQ -> {
                    val condition = instruction as TwoRegisterInstruction
                    if (registers[condition.registerA] == registers[condition.registerB]) {
                        pc += (instruction as OffsetInstruction).codeOffset
                        return@repeat
                    }
                }
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL -> {
                    val target = (instruction as ReferenceInstruction).reference as MethodReference
                    val indices = if (instruction is RegisterRangeInstruction) {
                        (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
                    } else {
                        val call = instruction as FiveRegisterInstruction
                        listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
                            .take(call.registerCount)
                    }
                    val args = indices.map { registers[it] }
                    pending = when (target.definingClass) {
                        "Lapp/morphe/extension/tiktok/search/SearchSuggestions;" -> filter(target.name, args)
                        "LX/0BYX;", "LX/0BZD;" -> if (target.name == "LJIIJJI") {
                            assertEquals(1, ++nativeReads)
                            assertEquals(if (target.definingClass == "LX/0BYX;") initial[2]
                                else "show_suggest_search_words", args[args.size - 2])
                            nativeValue
                        } else Any()
                        "Lorg/json/JSONObject;" -> {
                            @Suppress("UNCHECKED_CAST")
                            val payload = args[0] as MutableMap<String, Any?>
                            payload[args[1] as String] = args[2]
                            payload
                        }
                        else -> error("Unexpected native call: $target")
                    }
                }
                Opcode.RETURN, Opcode.RETURN_OBJECT -> {
                    if (method.returnType != "Z") assertEquals(1, nativeReads)
                    return registers[(instruction as OneRegisterInstruction).registerA]
                }
                Opcode.RETURN_VOID -> { assertEquals(1, nativeReads); return null }
                else -> error("Unexpected instruction: ${instruction.opcode}")
            }
            pc += instruction.codeUnits
        }
        error("Native caller did not return")
    }
}
