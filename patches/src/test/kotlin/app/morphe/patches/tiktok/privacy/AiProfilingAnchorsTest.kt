/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Stop on-device AI profiling returns early from, held to each declared build. Each
 * fingerprint takes one method; every caller of the plugin lookup copes with the null it is
 * made to return; and the provider it stops is the only way a real Pitaya core reaches TikTok.
 * The two fingerprints before these matched nothing on any declared build, and the patch applied
 * anyway with only a printed note.
 */
class AiProfilingAnchorsTest {
    @Test
    fun `the Pitaya doors resolve on each build and every lookup caller copes with no plugin`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            fun taken(fingerprint: Fingerprint): Method {
                val found = classes.values.flatMap { classDef -> classDef.methods.filter { fingerprint.takes(it, classDef) } }
                assertEquals("$version: ${fingerprint.javaClass.simpleName} takes ${found.map { "${it.definingClass}->${it.name}" }}",
                    1, found.size)
                return found.single()
            }

            val lookup = taken(PitayaPluginLookupFingerprint)
            assertTrue("$version: the lookup is not static", lookup.accessFlags and STATIC != 0)
            assertEquals("$version: returnEarly() needs a void method", "V", taken(PitayaRealProviderFingerprint).returnType)
            assertEquals("$version: returnEarly() needs a void method", "V", taken(PitayaLiteStartFingerprint).returnType)

            val methods = classes.values.flatMap { it.methods }.filter { it.implementation != null }
            var lookups = 0
            val doors = sortedSetOf<String>()
            for (method in methods) {
                val body = method.implementation!!.instructions.toList()
                val at = "${method.definingClass}->${method.name}"
                for ((index, instruction) in body.withIndex()) {
                    val call = instruction.getReference<MethodReference>()
                    if (call != null && call.definingClass == lookup.definingClass && call.name == lookup.name &&
                        call.returnType == lookup.returnType && call.parameterTypes.isEmpty()
                    ) {
                        lookups++
                        // The null the patch returns has to be tested before anything uses it.
                        val result = body.getOrNull(index + 1)
                        val check = body.getOrNull(index + 2)
                        assertTrue("$version: $at keeps no plugin lookup result", result?.opcode == Opcode.MOVE_RESULT_OBJECT)
                        assertTrue("$version: $at uses the plugin without a null check",
                            check?.opcode == Opcode.IF_EQZ &&
                                (check as OneRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA)
                    }
                    // Every place that could put a real core behind TikTok's stand-ins.
                    when (instruction.opcode) {
                        Opcode.SPUT_OBJECT -> instruction.getReference<FieldReference>()?.let { field ->
                            if (field.definingClass == PROVIDER && field.name == "realProvider") doors += "realProvider in $at"
                            if (field.definingClass == FACTORY && field.name == "provider") doors += "provider in $at"
                        }
                        else -> if (call?.definingClass == DELEGATE_CORE && call.name.startsWith("setRealCore")) {
                            doors += "setRealCore in $at"
                        }
                    }
                }
            }
            assertTrue("$version: $lookups calls of the plugin lookup, the start-up and the event copies need 3", lookups >= 3)
            assertEquals("$version: a real Pitaya core can attach somewhere else", sortedSetOf(
                "provider in $FACTORY-><clinit>",
                "realProvider in $PROVIDER->setRealProvider",
                "setRealCore in $PROVIDER->setRealProvider",
            ), doors)
        }
    }

    private companion object {
        const val STATIC = 0x8
        const val PROVIDER = "Lcom/bytedance/pitaya/api/mutilinstance/DelegateCoreProvider;"
        const val DELEGATE_CORE = "Lcom/bytedance/pitaya/api/mutilinstance/DelegateCore;"
        const val FACTORY = "Lcom/bytedance/pitaya/api/PitayaCoreFactory;"
    }
}
