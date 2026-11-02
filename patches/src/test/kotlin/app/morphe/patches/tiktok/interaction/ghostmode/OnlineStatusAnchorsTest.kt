/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Ghost mode finds TikTok's activity status report by, held to each declared build: the
 * regular poll (doReport$1) and the irregular report (doReport$2) are each one public final
 * invokeSuspend(Object)Object that opens with its trace tag, and each calls the report service,
 * an interface method taking two Strings and a continuation and answering Object. Two methods
 * would leave the guard on whichever the patch picked, and a body with no such call would be a
 * guard on something that doesn't send.
 */
class OnlineStatusAnchorsTest {
    private val senders = listOf(
        "regular (doReport\$1)" to OnlineStatusRegularReportFingerprint,
        "irregular (doReport\$2)" to OnlineStatusIrregularReportFingerprint,
    )

    private fun callsReportService(method: Method): Boolean =
        method.implementation?.instructions?.any {
            if (it.opcode != Opcode.INVOKE_INTERFACE) return@any false
            val target = (it as ReferenceInstruction).reference as MethodReference
            target.returnType == "Ljava/lang/Object;" &&
                target.parameterTypes.size == 3 &&
                target.parameterTypes[0] == "Ljava/lang/String;" &&
                target.parameterTypes[1] == "Ljava/lang/String;"
        } ?: false

    private fun taken(fingerprint: Fingerprint, apkName: String, container: MultiDexContainer<out DexBackedDexFile>): List<Method> {
        val found = mutableListOf<Method>()
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) {
                    if (fingerprint.takes(method, classDef)) found += method
                }
            }
        }
        return found
    }

    @Test
    fun `each declared build has one regular and one irregular activity status sender that calls the report service`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for ((label, fingerprint) in senders) {
                val found = taken(fingerprint, apk.name, container)
                assertEquals("$label senders: ${found.map { it.definingClass }}", 1, found.size)
                assertTrue("$label sender does not call the report service", callsReportService(found.single()))
            }
        }
    }

    @Test
    fun `each sender leaves a local register for the guard on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for ((label, fingerprint) in senders) {
                val method = taken(fingerprint, apk.name, container).single()
                val body = method.implementation!!
                // this and the one Object parameter take two registers; the guard writes v0.
                assertTrue("$label has ${body.registerCount} registers, no local for the guard", body.registerCount >= 3)
            }
        }
    }

    @Test
    fun `the two senders are different methods and the fingerprints do not match each other`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val regular = taken(OnlineStatusRegularReportFingerprint, apk.name, container).single()
            val irregular = taken(OnlineStatusIrregularReportFingerprint, apk.name, container).single()
            assertTrue("one method answers to both tags", regular.definingClass != irregular.definingClass)
        }
    }

    @Test
    fun `a method with another trace tag is not taken`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val other = Fingerprint(
                name = "invokeSuspend",
                returnType = "Ljava/lang/Object;",
                parameters = listOf("Ljava/lang/Object;"),
                strings = listOf("ActivityStatusReporter@261e.cancelPolling\$1"),
            )
            val found = taken(other, apk.name, container)
            assertTrue("cancelPolling is not a sender and must not match a sender fingerprint", found.none { method ->
                senders.any { (_, fingerprint) -> taken(fingerprint, apk.name, container).any { it == method } }
            })
        }
    }
}
