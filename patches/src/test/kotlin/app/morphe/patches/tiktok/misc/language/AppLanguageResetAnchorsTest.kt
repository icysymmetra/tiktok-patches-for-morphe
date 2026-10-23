/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.language

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Keep the app language returns early from, held to each declared build (#61).
 *
 * <p>The fingerprint takes one method of TikTok's language service, the shape check the patch
 * makes picks the same one, its frame has a local for the switch's answer, and nothing jumps to
 * its first instruction, so the guard in front of it can't be stepped over. Every call to it
 * comes from one class, TikTok's start-up language check, through the service's API or the
 * service itself, so skipping it touches nothing else, the language page included.
 */
class AppLanguageResetAnchorsTest {
    @Test
    fun `the language reset resolves on each build and only the start-up check calls it`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            val service = checkNotNull(classes[I18N_MANAGER_SERVICE]) { "$version: no $I18N_MANAGER_SERVICE" }
            assertTrue("$version: the service no longer implements $I18N_MANAGER_API", I18N_MANAGER_API in service.interfaces)

            val taken = classes.values.flatMap { classDef ->
                classDef.methods.filter { AppLanguageResetFingerprint.takes(it, classDef) }
            }
            assertEquals("$version: the fingerprint takes ${taken.map { "${it.definingClass}->${it.name}" }}",
                1, taken.size)
            val reset = taken.single()
            assertEquals("$version: the patch's shape check picks another method",
                listOf(reset.name), service.methods.filter { it.isAppLanguageReset() }.map { it.name })
            assertFalse("$version: the reset is static, so p1 isn't its context",
                AccessFlags.STATIC.isSet(reset.accessFlags))
            val body = reset.implementation!!
            assertTrue("$version: no local for the switch's answer",
                body.registerCount - reset.numberOfParameterRegisters >= 1)
            val instructions = body.instructions.toList()
            val addresses = instructions.runningFold(0) { at, instruction -> at + instruction.codeUnits }
            assertFalse("$version: something jumps back to the reset's first instruction",
                instructions.indices.any { i ->
                    val branch = instructions[i] as? OffsetInstruction
                    branch != null && addresses[i] + branch.codeOffset == 0
                })
            assertFalse("$version: an exception handler lands on the reset's first instruction",
                body.tryBlocks.any { block -> block.exceptionHandlers.any { it.handlerCodeAddress == 0 } })

            val callers = sortedSetOf<String>()
            for (classDef in classes.values) {
                for (method in classDef.methods) {
                    val calls = method.implementation?.instructions ?: continue
                    for (instruction in calls) {
                        val call = instruction.getReference<MethodReference>() ?: continue
                        if (call.definingClass != I18N_MANAGER_SERVICE && call.definingClass != I18N_MANAGER_API) continue
                        if (call.name != reset.name || call.returnType != "V" ||
                            call.parameterTypes.map(CharSequence::toString) != listOf("Landroid/content/Context;")
                        ) continue
                        callers += method.definingClass
                    }
                }
            }
            assertEquals("$version: the reset is called from $callers", 1, callers.size)
        }
    }
}
