/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EXPIRY_CLASS = "Lapp/morphe/extension/tiktok/offline/OfflineVideoExpiry;"

/**
 * What Keep offline videos hooks beyond the lifetime (#123), held to each declared build: the two
 * static void steps of Auto adjust that move or roll back the offline limit at start, and the
 * static boolean that says the default-on experiment has ended and the list should be cleared.
 * Each fingerprint takes exactly one method, the guards fit them, and TikTok's own instructions
 * follow the guard in their own order.
 */
class AutoAdjustKeepAnchorsTest {
    @Test
    fun `each declared build has one Auto adjust boot step, one rollback and one default-on clean-up and the guards fit them`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val boot = taken(apk, AutoAdjustBootFingerprint)
            val rollback = taken(apk, AutoAdjustRollbackFingerprint)
            val cleanup = taken(apk, DefaultEnableStateFingerprint)

            assertEquals("$version: boot steps ${boot.map { it.first.type + "->" + it.second.name }}", 1, boot.size)
            assertEquals("$version: rollbacks ${rollback.map { it.first.type + "->" + it.second.name }}", 1, rollback.size)
            assertEquals("$version: clean-ups ${cleanup.map { it.first.type + "->" + it.second.name }}", 1, cleanup.size)

            // The two Auto adjust steps live together and are told apart by name.
            assertEquals("$version: Auto adjust steps are in different classes", boot.single().first.type, rollback.single().first.type)
            assertTrue("$version: one method took both steps", boot.single().second.name != rollback.single().second.name)

            for ((label, step) in listOf("boot" to boot.single(), "rollback" to rollback.single())) {
                val method = step.second
                assertTrue("$version: the $label step is not static", AccessFlags.STATIC.isSet(method.accessFlags))
                assertEquals("V", method.returnType)
                assertTrue(method.parameterTypes.isEmpty())

                val before = method.implementation!!.instructions.toList()
                val guarded = MutableMethod(method)
                guarded.keepThroughAutoAdjust("Custom offline videos limit")
                val after = guarded.implementation!!.instructions.toList()
                assertEquals(
                    "$version: the $label guard",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    after.take(4).map { it.opcode },
                )
                val ask = after[0].getReference<MethodReference>()!!
                assertEquals(EXPIRY_CLASS, ask.definingClass)
                assertEquals("keepThroughAutoAdjust", ask.name)
                assertEquals("Z", ask.returnType)
                assertTrue(ask.parameterTypes.isEmpty())
                assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
                assertEquals(before.map { it.opcode }, after.takeLast(before.size).map { it.opcode })
            }

            val native = cleanup.single().second
            assertTrue("$version: the clean-up question is not static", AccessFlags.STATIC.isSet(native.accessFlags))
            assertEquals("Z", native.returnType)
            assertTrue(native.parameterTypes.isEmpty())

            val before = native.implementation!!.instructions.toList()
            val guarded = MutableMethod(native)
            val returnIndex = guarded.defaultEnableCleanupReturnIndex("Custom offline videos limit")
            guarded.keepThroughDefaultEnableCleanup("Custom offline videos limit")
            val after = guarded.implementation!!.instructions.toList()
            assertEquals("$version: the guard adds exactly two instructions", before.size + 2, after.size)
            assertEquals(Opcode.INVOKE_STATIC, after[returnIndex].opcode)
            assertEquals(Opcode.MOVE_RESULT, after[returnIndex + 1].opcode)
            assertEquals(Opcode.RETURN, after[returnIndex + 2].opcode)
            val ask = after[returnIndex].getReference<MethodReference>()!!
            assertEquals(EXPIRY_CLASS, ask.definingClass)
            assertEquals("keepThroughDefaultEnableCleanup", ask.name)
            assertEquals("Z", ask.returnType)
            assertEquals(listOf("Z"), ask.parameterTypes.map { it.toString() })
            // The answer goes in and comes out of the register the return reads.
            val answer = (after[returnIndex + 1] as OneRegisterInstruction).registerA
            assertEquals(answer, (after[returnIndex + 2] as OneRegisterInstruction).registerA)
            // TikTok's own instructions keep their order on both sides of the two added.
            assertEquals(
                before.map { it.opcode },
                after.filterIndexed { index, _ -> index != returnIndex && index != returnIndex + 1 }.map { it.opcode },
            )
        }
    }

    @Test
    fun `a step with no local register stops the patch and is left as it was`() {
        val tight = MutableMethod(
            ImmutableMethod(
                "Lfixture/Step;", "step", emptyList(), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(0, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
            ),
        )
        val before = tight.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { tight.keepThroughAutoAdjust("Custom offline videos limit") }
        assertEquals(before, tight.implementation!!.instructions.toList())
    }

    @Test
    fun `a clean-up question without the line is refused and left as it was`() {
        val bare = MutableMethod(
            ImmutableMethod(
                "Lfixture/State;", "state", emptyList(), "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(1, listOf(ImmutableInstruction11x(Opcode.RETURN, 0)), null, null),
            ),
        )
        val before = bare.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { bare.keepThroughDefaultEnableCleanup("Custom offline videos limit") }
        assertEquals(before, bare.implementation!!.instructions.toList())
    }

    private fun taken(apk: File, fingerprint: Fingerprint): List<Pair<ClassDef, Method>> {
        val found = mutableListOf<Pair<ClassDef, Method>>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) {
                    if (fingerprint.takes(method, classDef)) found += classDef to method
                }
            }
        }
        return found
    }
}
