/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DNT_EXTENSION = "Lapp/morphe/extension/tiktok/translation/DoNotAutoTranslate;"

/**
 * What Don't auto translate hooks (#121), held to each declared build: the translation service
 * reads TikTok's Don't translate list at exactly seven places, each as a no-argument call
 * returning String[] with its result moved into a register a plain invoke can name, and the hook
 * lands right after that move and leaves everything else as TikTok wrote it.
 */
class DoNotAutoTranslateAnchorsTest {
    @Test
    fun `each declared build reads the Don't translate list seven times in the translation service and the hook fits each`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val reads = mutableListOf<Triple<String, Int, Int>>()
            var found = false
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type != TRANSLATION_SERVICE) continue
                    found = true
                    for (method in classDef.methods) {
                        val results = method.doNotTranslateReads()
                        if (results.isEmpty()) continue
                        val before = method.implementation!!.instructions.toList()
                        for (resultIndex in results) {
                            reads += Triple(method.name, resultIndex, resultIndex)
                            val register = (before[resultIndex] as OneRegisterInstruction).registerA
                            assertTrue("$version: ${method.name} keeps the list in v$register", register <= 15)
                            assertEquals(Opcode.MOVE_RESULT_OBJECT, before[resultIndex].opcode)

                            val hooked = MutableMethod(method)
                            hooked.addWithExcluded(resultIndex, register)
                            val after = hooked.implementation!!.instructions.toList()
                            assertEquals("$version: ${method.name} gained two instructions", before.size + 2, after.size)
                            assertEquals(Opcode.INVOKE_STATIC, after[resultIndex + 1].opcode)
                            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[resultIndex + 2].opcode)
                            val call = after[resultIndex + 1].getReference<MethodReference>()!!
                            assertEquals(DNT_EXTENSION, call.definingClass)
                            assertEquals("withExcluded", call.name)
                            assertEquals("[Ljava/lang/String;", call.returnType)
                            assertEquals(listOf("[Ljava/lang/String;"), call.parameterTypes.map { it.toString() })
                            assertEquals(register, (after[resultIndex + 2] as OneRegisterInstruction).registerA)
                            // TikTok's own instructions keep their order on both sides.
                            assertEquals(
                                before.map { it.opcode },
                                after.filterIndexed { i, _ -> i != resultIndex + 1 && i != resultIndex + 2 }.map { it.opcode },
                            )
                        }
                    }
                }
            }
            assertTrue("$version: $TRANSLATION_SERVICE is missing", found)
            assertEquals(
                "$version: reads of the Don't translate list in the service: ${reads.map { it.first }}",
                DO_NOT_TRANSLATE_READS,
                reads.size,
            )
            assertEquals("$version: every read is in a method of its own", reads.size, reads.map { it.first }.toSet().size)
        }
    }

    @Test
    fun `a read of the list with no move after it is refused`() {
        val getter = ImmutableMethodReference(
            "Lfixture/Controller;", "getSelectedDoNotTranslateLanguageCodes", emptyList(), "[Ljava/lang/String;",
        )
        val broken = ImmutableMethod(
            "Lfixture/Service;", "read", emptyList(), "V", 1, null, null,
            ImmutableMethodImplementation(
                1,
                listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, getter),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null, null,
            ),
        )
        assertThrows(PatchException::class.java) { broken.doNotTranslateReads() }
    }
}
