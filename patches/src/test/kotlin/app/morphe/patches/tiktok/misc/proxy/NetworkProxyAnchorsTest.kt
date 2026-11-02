/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.proxy

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.extension.HostApplicationAttachBaseContextFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOST_APPLICATION = "Lcom/ss/android/ugc/aweme/app/host/AwemeHostApplication;"
private const val JSON = "Lorg/json/JSONObject;"
private const val OPT_STRING = "Lorg/json/JSONObject;->optString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

/**
 * What "Network proxy" rests on, held to each declared TikTok build.
 *
 * TTNet's engine builder, org.chromium.CronetClient.tryCreateCronetEngine, reads its debug
 * config with a static `(Context)String` call, checks it isn't empty, parses it with
 * `new JSONObject(config)` and hands the config's ttnet_proxy string to the builder's proxy
 * setter. The hook rewrites the read's result right after its `move-result-object`, so the
 * emptiness check, the parse and the setter all see the proxy. The install goes just past the
 * host application's one framework attachBaseContext call, where the shared extension hook puts
 * the context setter ahead of it in its finalize.
 */
class NetworkProxyAnchorsTest {
    @Test
    fun `each declared build reads TTNet's config once, in the shape the hook rewrites`() {
        val shapes = mutableMapOf<String, String>()
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val engines = mutableListOf<Method>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (CronetEngineCreateFingerprint.takes(method, classDef)) engines += method
                    }
                }
            }
            assertEquals("$version: engine builders matched", 1, engines.size)
            val engine = engines.single()

            val reads = ttnetConfigReads(engine)
            assertEquals("$version: config reads", 1, reads.size)
            val result = reads.single()
            val implementation = engine.implementation!!
            val instructions = implementation.instructions.toList()
            assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[result].opcode)
            val register = (instructions[result] as OneRegisterInstruction).registerA
            // The inserted move-result-object takes an 8-bit register; the call takes any, by range.
            assertTrue("$version: v$register inside its frame", register < implementation.registerCount)
            assertTrue("$version: v$register too high for move-result-object", register < 256)

            val read = (instructions[result - 1] as ReferenceInstruction).reference as MethodReference
            assertEquals(Opcode.INVOKE_STATIC, instructions[result - 1].opcode)
            assertEquals("(Landroid/content/Context;)Ljava/lang/String;", signature(read))

            // Nothing jumps in between the read and the inserted call, so every path into the
            // parse carries the rewritten value.
            val addresses = addresses(instructions)
            val parse = (result + 1 until instructions.size).first {
                ((instructions[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == JSON_FROM_STRING
            }
            val targets = branchTargets(engine)
            (result + 1..parse).forEach { index ->
                assertFalse("$version: a branch lands at ${instructions[index].opcode} between the read and the parse",
                    addresses[index] in targets)
            }

            // The config goes through an emptiness check first: the reason the hook rewrites the
            // read rather than the parse. And TikTok reads ttnet_proxy out of that same object.
            assertTrue("$version: no emptiness check on the config",
                (result + 1 until parse).any { index ->
                    val reference = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.toString() == "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z" &&
                        (instructions[index] as FiveRegisterInstruction).registerC == register
                })
            val json = (instructions[parse] as FiveRegisterInstruction).registerC
            val opt = (parse + 2..minOf(parse + 4, instructions.lastIndex)).firstOrNull { index ->
                ((instructions[index] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == OPT_STRING
            }
            assertTrue("$version: ttnet_proxy isn't read out of the parsed config", opt != null)
            assertEquals("$version: optString reads another object", json,
                (instructions[opt!!] as FiveRegisterInstruction).registerC)

            shapes[version] = "${signature(read)} v$register ${parse - result}"
        }
        assertEquals("the same read shape on every declared build: $shapes", 1, shapes.values.toSet().size)
    }

    @Test
    fun `each declared build takes the install after its one framework call`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    classDef.methods.filter { HostApplicationAttachBaseContextFingerprint.takes(it, classDef) }
                        .forEach { taken += classDef to it }
                }
            }
            assertEquals("$version: attachBaseContext methods matched", 1, taken.size)
            val (owner, method) = taken.single()
            assertEquals(HOST_APPLICATION, owner.type)

            val index = proxyInstallIndex(method)
            val instructions = method.implementation!!.instructions.toList()
            val call = (instructions[index - 1] as ReferenceInstruction).reference as MethodReference
            assertEquals("$version: the install doesn't follow the framework call", FRAMEWORK_ATTACH, call.toString())
            assertTrue("$version: nothing follows the framework call to insert before", index < instructions.size)
            assertNotEquals("$version: the install would split a call from its result",
                Opcode.MOVE_RESULT_OBJECT, instructions[index].opcode)
            // p1 is the Context of an instance method with one parameter: p0, p1 at the frame's end.
            assertTrue("$version: p1 isn't in the frame", method.implementation!!.registerCount >= 2)
        }
    }

    @Test
    fun `a config read counts only in TikTok's shape`() {
        val tiktok = listOf(read(), result(1), isEmpty(1), flag(0), newJson(2), parse(2, 1), key(3))
        assertEquals("TikTok's shape", listOf(1), ttnetConfigReads(synthetic(tiktok)))
        assertEquals("parsed right after", listOf(1),
            ttnetConfigReads(synthetic(listOf(read(), result(1), newJson(2), parse(2, 1), key(3)))))

        assertEquals("another key", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(), result(1), newJson(2), parse(2, 1), key(3, "ttnet_proxy_other")))))
        assertEquals("the key not right after the parse", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(), result(1), newJson(2), parse(2, 1), flag(0), key(3)))))
        assertEquals("the config overwritten before the parse", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(), result(1), flag(1), newJson(2), parse(2, 1), key(3)))))
        assertEquals("the parse takes another register", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(), result(1), newJson(2), parse(2, 4), key(3)))))
        assertEquals("the read too far back", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(), result(1), flag(5), flag(5), flag(5), flag(5), flag(5),
                newJson(2), parse(2, 1), key(3)))))
        assertEquals("a virtual read", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(opcode = Opcode.INVOKE_VIRTUAL), result(1), newJson(2), parse(2, 1), key(3)))))
        assertEquals("a read that takes no Context", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(parameter = "Ljava/lang/String;"), result(1), newJson(2), parse(2, 1), key(3)))))
        assertEquals("a read that returns something else", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(read(returns = "Ljava/lang/Object;"), result(1), newJson(2), parse(2, 1), key(3)))))
        assertEquals("a config built in place, not read", emptyList<Int>(),
            ttnetConfigReads(synthetic(listOf(newJson(1), newJson(2), parse(2, 1), key(3)))))
        assertEquals("two reads", listOf(1, 6), ttnetConfigReads(synthetic(listOf(
            read(), result(1), newJson(2), parse(2, 1), key(3),
            read(), result(1), newJson(2), parse(2, 1), key(3),
        ))))
    }

    @Test
    fun `the install refuses a method it can't place itself in`() {
        val superCall = ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2, 0, 1, 0, 0, 0,
            ImmutableMethodReference("Landroid/app/Application;", "attachBaseContext",
                listOf("Landroid/content/Context;"), "V"))
        val done = ImmutableInstruction10x(Opcode.RETURN_VOID)

        assertEquals(1, proxyInstallIndex(host(listOf(superCall, done))))
        assertThrows(PatchException::class.java) { proxyInstallIndex(host(listOf(done))) }
        assertThrows(PatchException::class.java) { proxyInstallIndex(host(listOf(superCall, superCall, done))) }
        assertThrows(PatchException::class.java) { proxyInstallIndex(host(listOf(superCall, done), isStatic = true)) }
        assertThrows(PatchException::class.java) { proxyInstallIndex(host(null)) }
    }

    private fun read(
        opcode: Opcode = Opcode.INVOKE_STATIC,
        parameter: String = "Landroid/content/Context;",
        returns: String = "Ljava/lang/String;",
    ) = ImmutableInstruction35c(opcode, 1, 0, 0, 0, 0, 0,
        ImmutableMethodReference("LX/Config;", "LIZ", listOf(parameter), returns))

    private fun result(register: Int) = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register)

    private fun isEmpty(register: Int) = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, register, 0, 0, 0, 0,
        ImmutableMethodReference("Landroid/text/TextUtils;", "isEmpty", listOf("Ljava/lang/CharSequence;"), "Z"))

    private fun flag(register: Int) = ImmutableInstruction11n(Opcode.CONST_4, register, 0)

    private fun newJson(register: Int) = ImmutableInstruction21c(Opcode.NEW_INSTANCE, register, ImmutableTypeReference(JSON))

    private fun parse(json: Int, config: Int) = ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 2, json, config, 0, 0, 0,
        ImmutableMethodReference(JSON, "<init>", listOf("Ljava/lang/String;"), "V"))

    private fun key(register: Int, value: String = TTNET_PROXY_KEY) =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    private fun synthetic(instructions: List<Instruction>) = ImmutableMethod(
        "Lorg/chromium/CronetClient;", "tryCreateCronetEngine", emptyList(), "V",
        AccessFlags.PRIVATE.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(8, instructions + ImmutableInstruction10x(Opcode.RETURN_VOID), emptyList(), emptyList()),
    )

    private fun host(instructions: List<Instruction>?, isStatic: Boolean = false): Method = ImmutableMethod(
        HOST_APPLICATION,
        "attachBaseContext",
        listOf(ImmutableMethodParameter("Landroid/content/Context;", emptySet(), null)),
        "V",
        AccessFlags.PROTECTED.value or if (isStatic) AccessFlags.STATIC.value else 0,
        emptySet(),
        emptySet(),
        instructions?.let { ImmutableMethodImplementation(2, it, emptyList(), emptyList()) },
    )

    private fun signature(reference: MethodReference) =
        reference.parameterTypes.joinToString("", "(", ")") + reference.returnType

    /** Each instruction's code address, in 16-bit units from the start of the method. */
    private fun addresses(instructions: List<Instruction>): List<Int> =
        instructions.runningFold(0) { address, instruction -> address + instruction.codeUnits }.dropLast(1)

    /** Every address a branch, a switch case or an exception handler can land on. */
    private fun branchTargets(method: Method): Set<Int> {
        val implementation = method.implementation!!
        val instructions = implementation.instructions.toList()
        val addresses = addresses(instructions)
        val atAddress = instructions.indices.associateBy { addresses[it] }
        val targets = mutableSetOf<Int>()
        instructions.forEachIndexed { index, instruction ->
            if (instruction !is OffsetInstruction) return@forEachIndexed
            val target = addresses[index] + instruction.codeOffset
            when (instruction.opcode) {
                Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                    val payload = instructions[atAddress.getValue(target)] as SwitchPayload
                    payload.switchElements.forEach { targets += addresses[index] + it.offset }
                }
                Opcode.FILL_ARRAY_DATA -> Unit
                else -> targets += target
            }
        }
        implementation.tryBlocks.forEach { block ->
            block.exceptionHandlers.forEach { targets += it.handlerCodeAddress }
        }
        return targets
    }
}
