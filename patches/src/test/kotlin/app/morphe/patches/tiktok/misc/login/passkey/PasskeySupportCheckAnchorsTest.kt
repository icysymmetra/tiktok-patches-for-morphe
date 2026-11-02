/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.login.passkey

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Skip passkey sign-in answers no from, held to each declared build (#102).
 *
 * <p>The fingerprint takes one method of TikTok's passkey service, the shape check the patch
 * makes picks the same one, and its frame has a local for the switch's answer, so the guard goes
 * in place in front of TikTok's own code. Nothing outside the service calls it: every caller is
 * one of the service's own yes or no gates, which tests the answer straight away, and at least
 * one of those gates is on the interface the login screens reach the service through. That is
 * what makes a no here reach the sign-in screens as "this phone has no passkeys".
 */
class PasskeySupportCheckAnchorsTest {
    @Test
    fun `the passkey support check resolves on each build and only the service's gates ask it`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            val service = checkNotNull(classes[CREDENTIAL_MANAGER_SERVICE]) { "$version: no $CREDENTIAL_MANAGER_SERVICE" }
            assertTrue("$version: the service no longer implements $CREDENTIAL_MANAGER_INTERFACE",
                CREDENTIAL_MANAGER_INTERFACE in service.interfaces)

            val taken = classes.values.flatMap { classDef ->
                classDef.methods.filter { PasskeyDeviceSupportFingerprint.takes(it, classDef) }
            }
            assertEquals("$version: the fingerprint takes ${taken.map { "${it.definingClass}->${it.name}" }}",
                1, taken.size)
            val check = taken.single()
            assertEquals("$version: the patch's shape check picks another method",
                listOf(check.name), service.methods.filter(::isPasskeyDeviceSupportCheck).map { it.name })
            assertTrue("$version: no local for the switch's answer",
                check.implementation!!.registerCount - check.numberOfParameterRegisters >= 1)

            val gates = sortedSetOf<String>()
            for (classDef in classes.values) {
                for (method in classDef.methods) {
                    val body = method.implementation?.instructions?.toList() ?: continue
                    val at = "${method.definingClass}->${method.name}"
                    for ((index, instruction) in body.withIndex()) {
                        val call = instruction.getReference<MethodReference>() ?: continue
                        if (call.definingClass != CREDENTIAL_MANAGER_SERVICE || call.name != check.name ||
                            call.returnType != "Z" || call.parameterTypes.isNotEmpty()
                        ) continue
                        assertEquals("$version: $at asks the check from outside the service",
                            CREDENTIAL_MANAGER_SERVICE, method.definingClass)
                        assertTrue("$version: $at is not a yes or no gate",
                            method.returnType == "Z" && method.parameterTypes.isEmpty())
                        val result = body.getOrNull(index + 1)
                        val test = body.getOrNull(index + 2)
                        assertTrue("$version: $at keeps no answer", result?.opcode == Opcode.MOVE_RESULT)
                        assertTrue("$version: $at doesn't test the answer straight away",
                            (test?.opcode == Opcode.IF_EQZ || test?.opcode == Opcode.IF_NEZ) &&
                                (test as OneRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA)
                        gates += method.name
                    }
                }
            }
            val onInterface = classes[CREDENTIAL_MANAGER_INTERFACE]?.methods
                ?.filter { it.returnType == "Z" && it.parameterTypes.isEmpty() }?.map { it.name }?.toSet().orEmpty()
            assertTrue("$version: no gate asks the check", gates.isNotEmpty())
            assertTrue("$version: none of the gates $gates is on $CREDENTIAL_MANAGER_INTERFACE",
                gates.any { it in onInterface })
        }
    }
}
