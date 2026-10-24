/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** These are method-result guards, not replacements for ordinary file, process or Build reads. */
class RootSignalAnchorsTest {
    private data class Target(val dex: String, val owner: ClassDef, val method: Method)
    private data class Found(val targets: Map<RootSignal, Target>, val collector: Method)

    companion object {
        private val cache = mutableMapOf<String, Found>()
        private const val MAP = "Ljava/util/Map;"
        private const val STRING = "Ljava/lang/String;"
    }

    @Test fun `the reviewed fingerprints select exactly five public static helpers on the declared build`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val expected = mapOf(
                RootSignal.STATUS to "classes4.dex:LX/06f6;->LJIL()Z",
                RootSignal.PACKAGES to "classes21.dex:LX/0jSc;->LJ(Landroid/content/Context;)$MAP",
                RootSignal.PATHS to "classes21.dex:LX/0jSc;->LJFF()$MAP",
                RootSignal.WHICH_SU to "classes21.dex:LX/0jSc;->LJIILIIL()$MAP",
                RootSignal.SU_COMMAND to "classes21.dex:LX/0jSc;->LJI()$MAP",
            )
            assertEquals("47.4.3", Fixtures.versionOf(apk))
            assertEquals(expected, found.targets.mapValues { (_, target) -> "${target.dex}:${signature(target.method)}" })
            assertEquals(1, found.targets.filterKeys { it != RootSignal.STATUS }.values.map { it.owner.type }.toSet().size)
            for ((signal, target) in found.targets) {
                assertTrue(AccessFlags.PUBLIC.isSet(target.method.accessFlags))
                assertTrue(AccessFlags.STATIC.isSet(target.method.accessFlags))
                assertTrue(RootSignalFingerprint(signal).takes(target.method, target.owner))
            }
        }
    }

    @Test fun `each false guard reaches the intact original body without moving parameters or try blocks`() {
        Fixtures.forEachDeclared { apk ->
            for ((signal, target) in find(apk).targets) {
                val patched = MutableMethod(target.method)
                val body = patched.implementation!!
                val before = body.instructions.toList()
                val registers = body.registerCount
                val tries = body.tryBlocks.size
                patched.guardRootSignal(signal)
                val after = patched.implementation!!.instructions.toList()
                val prefix = if (signal == RootSignal.STATUS) 6 else 7
                assertEquals(registers, patched.implementation!!.registerCount)
                assertEquals(tries, patched.implementation!!.tryBlocks.size)
                assertEquals(before.size + prefix, after.size)
                before.forEachIndexed { index, instruction -> assertSame(instruction, after[index + prefix]) }
                assertEquals(Opcode.IF_EQZ, after[2].opcode)
                val branch = after[2] as OffsetInstruction
                assertSame(after[prefix - 1], after.single {
                    it.location.codeAddress == after[2].location.codeAddress + branch.codeOffset
                })
                assertEquals(Opcode.NOP, after[prefix - 1].opcode)
                val question = after[0].getReference<MethodReference>()!!
                assertEquals(ROOT_GUARD, question.definingClass)
                assertEquals("hideRoot", question.name)
                assertEquals("Z", question.returnType)
                assertTrue(question.parameterTypes.isEmpty())
                assertEquals(0, (after[0] as FiveRegisterInstruction).registerCount)
                if (signal == RootSignal.STATUS) {
                    assertEquals(Opcode.CONST_4, after[3].opcode)
                    assertEquals(0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals(Opcode.RETURN, after[4].opcode)
                } else {
                    val answer = after[3].getReference<MethodReference>()!!
                    assertEquals(ROOT_GUARD, answer.definingClass)
                    assertEquals(signal.hiddenMember, answer.name)
                    assertEquals(MAP, answer.returnType)
                    assertTrue(answer.parameterTypes.isEmpty())
                    assertEquals(0, (after[3] as FiveRegisterInstruction).registerCount)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, after[4].opcode)
                    assertEquals(Opcode.RETURN_OBJECT, after[5].opcode)
                }
            }
        }
    }

    @Test fun `native root summaries carry Integer counts Boolean flags and summary with items`() {
        Fixtures.forEachDeclared { apk ->
            for (signal in listOf(RootSignal.PACKAGES, RootSignal.PATHS)) {
                val method = find(apk).targets.getValue(signal).method
                val strings = strings(method)
                assertTrue(strings.containsAll(listOf("summary", "items", "hit", "unknown",
                    if (signal == RootSignal.PACKAGES) "rootPackage" else "rootPath")))
                val calls = method.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
                for ((owner, type) in listOf("Ljava/lang/Integer;" to "I", "Ljava/lang/Boolean;" to "Z")) {
                    assertTrue("${signature(method)} no longer boxes $owner", calls.any {
                        it.definingClass == owner && it.name == "valueOf" && it.parameterTypes.map(CharSequence::toString) == listOf(type)
                    })
                }
                assertTrue(calls.any { it.returnType == MAP && it.parameterTypes.map(CharSequence::toString) == listOf("[Lkotlin/Pair;") })
            }
        }
    }

    @Test fun `the command schemas retain not detected and uidZero is a String rather than Boolean`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val which = found.targets.getValue(RootSignal.WHICH_SU).method
            assertTrue(strings(which).containsAll(listOf("state", "path", "not_detected", "")))
            val command = found.targets.getValue(RootSignal.SU_COMMAND).method
            assertTrue(strings(command).containsAll(listOf("state", "uidZero", "not_detected")))
            assertTrue(command.implementation!!.instructions.any {
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == STRING && ref.name == "valueOf" && ref.returnType == STRING &&
                        ref.parameterTypes.map(CharSequence::toString) == listOf("Z")
                } == true
            })
        }
    }

    @Test fun `AppLog consumes the four root maps as JSON and the status as a boxed Boolean`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val code = found.collector.implementation!!.instructions.toList()
            assertEquals("LX/0Cts;->LIZ(${STRING}LX/0qih;)LX/0qii;", signature(found.collector))
            for ((key, signal) in mapOf("root_packages" to RootSignal.PACKAGES, "root_paths" to RootSignal.PATHS,
                "which_su" to RootSignal.WHICH_SU, "su_command" to RootSignal.SU_COMMAND, "is_root" to RootSignal.STATUS)) {
                assertTrue(strings(found.collector).contains(key))
                val original = found.targets.getValue(signal).method
                val index = code.indices.single { at -> code[at].getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == original.definingClass && ref.name == original.name && ref.returnType == original.returnType
                } == true }
                assertEquals(if (signal == RootSignal.STATUS) Opcode.MOVE_RESULT else Opcode.MOVE_RESULT_OBJECT, code[index + 1].opcode)
                val consumer = code.drop(index + 2).take(4).mapNotNull { it.getReference<MethodReference>() }
                if (signal == RootSignal.STATUS) {
                    assertTrue(consumer.any { it.definingClass == "Ljava/lang/Boolean;" && it.name == "valueOf" })
                } else {
                    assertTrue(consumer.any { it.definingClass == "Lorg/json/JSONObject;" && it.name == "<init>" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf(MAP) })
                }
            }
        }
    }

    @Test fun `wrong ABIs missing owner catalog and missing status probes never match`() {
        Fixtures.forEachDeclared { apk ->
            for ((signal, target) in find(apk).targets) {
                for (wrong in listOf(
                    copy(target.method, flags = AccessFlags.PUBLIC.value),
                    copy(target.method, flags = AccessFlags.STATIC.value),
                    copy(target.method, parameters = signal.parameters + "I"),
                    copy(target.method, result = "Ljava/lang/Object;"),
                )) assertFalse(RootSignalFingerprint(signal).takes(wrong, target.owner))
                if (signal != RootSignal.STATUS) {
                    val withoutCatalog = ImmutableClassDef(target.owner.type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
                        null, null, null, null, target.owner.methods.filter { it.name != "<clinit>" })
                    assertFalse(RootSignalFingerprint(signal).takes(target.method, withoutCatalog))
                } else {
                    for (missing in listOf("TAGS", "exists")) {
                        val stripped = target.method.implementation!!.instructions.filter {
                            it.getReference<FieldReference>()?.name != missing && it.getReference<MethodReference>()?.name != missing
                        }
                        assertFalse(RootSignalFingerprint(signal).takes(copy(target.method, code = stripped), target.owner))
                    }
                }
            }
        }
    }

    @Test fun `duplicate and missing candidates fail closed before a helper can be selected`() {
        Fixtures.forEachDeclared { apk ->
            for ((signal, target) in find(apk).targets) {
                val duplicate = copy(target.method)
                assertTrue(RootSignalFingerprint(signal).takes(duplicate, target.owner))
                assertThrows(PatchException::class.java) { singleRootSignal(signal, listOf(target.method, duplicate)) }
                assertThrows(PatchException::class.java) { singleRootSignal<Method>(signal, emptyList()) }
                assertSame(target.method, singleRootSignal(signal, listOf(target.method)))
            }
        }
    }

    @Test fun `wrong result types and frames with no local are refused without changing instructions`() {
        Fixtures.forEachDeclared { apk ->
            for ((signal, target) in find(apk).targets) {
                val wrong = MutableMethod(copy(target.method, result = "Ljava/lang/Object;"))
                val before = wrong.implementation!!.instructions.toList()
                assertThrows(IllegalStateException::class.java) { wrong.guardRootSignal(signal) }
                assertEquals(before, wrong.implementation!!.instructions.toList())
                val noLocal = MutableMethod(copy(target.method, registers = signal.parameters.size, code = emptyList()))
                assertThrows(PatchException::class.java) { noLocal.guardRootSignal(signal) }
                assertTrue(noLocal.implementation!!.instructions.isEmpty())
            }
        }
    }

    private fun find(apk: File): Found = synchronized(cache) {
        cache.getOrPut(apk.absolutePath) {
            val candidates = RootSignal.entries.associateWith { mutableListOf<Target>() }
            val collectors = mutableListOf<Method>()
            val fingerprints = RootSignal.entries.associateWith(::RootSignalFingerprint)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) for (owner in container.getEntry(entry)!!.dexFile.classes) {
                for (method in owner.methods) {
                    val loaded = strings(method)
                    if (loaded.containsAll(listOf("root_packages", "root_paths", "which_su", "su_command", "is_root"))) collectors += method
                    for (signal in RootSignal.entries) {
                        if (loaded.containsAll(signal.markers) && fingerprints.getValue(signal).takes(method, owner)) {
                            candidates.getValue(signal) += Target(entry, owner, method)
                        }
                    }
                }
            }
            for ((signal, all) in candidates) assertEquals("${apk.name}: $signal candidates", 1, all.size)
            assertEquals("AppLog root collector", 1, collectors.size)
            Found(candidates.mapValues { it.value.single() }, collectors.single())
        }
    }

    private fun strings(method: Method) = method.implementation?.instructions
        ?.mapNotNull { it.getReference<StringReference>()?.string }?.toSet().orEmpty()

    private fun signature(method: Method) = "${method.definingClass}->${method.name}" +
        "(${method.parameterTypes.joinToString("")})${method.returnType}"

    private fun copy(
        method: Method,
        flags: Int = method.accessFlags,
        parameters: List<String> = method.parameterTypes.map(CharSequence::toString),
        result: String = method.returnType,
        registers: Int = method.implementation!!.registerCount,
        code: Iterable<Instruction> = method.implementation!!.instructions,
    ): Method = ImmutableMethod(method.definingClass, method.name,
        parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
        ImmutableMethodImplementation(registers, code, null, null))
}
