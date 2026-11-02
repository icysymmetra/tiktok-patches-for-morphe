/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native-only tests: the one scene normalizer every long-press panel lookup goes through (#120). */
class ProfileLongPressPanelAnchorsTest {
    private data class Found(val scenes: List<Method>, val callers: Map<String, List<Method>>)

    companion object {
        private val cache = mutableMapOf<String, Found>()
    }

    @Test fun `one static normalizer sits in the class that holds the panel config`() {
        Fixtures.forEachDeclared { apk ->
            val scene = find(apk).scenes.single()
            assertTrue(AccessFlags.STATIC.isSet(scene.accessFlags))
            assertEquals("the prepend writes p0, which is v1 in two registers", 2, scene.implementation!!.registerCount)
            val strings = scene.implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string }
            assertEquals("it renames one page and keeps the rest", listOf("homepage_ai_series", "homepage_series"), strings)
        }
    }

    @Test fun `the panel gate and the other panel readers all ask the normalizer`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val scene = found.scenes.single()
            val callers = found.callers["${scene.definingClass}->${scene.name}"].orEmpty()
            assertTrue("only ${callers.size} lookups ask the normalizer", callers.map { it.definingClass }.toSet().size >= 4)
            // The gate lives beside it and looks the normalized name up in the config it holds.
            assertTrue("the gate no longer normalizes before its lookup", callers.any { caller ->
                caller.definingClass == scene.definingClass && caller.implementation!!.instructions.any {
                    it.getReference<MethodReference>()?.returnType == LPP_PROTOCOL ||
                        it.getReference<FieldReference>()?.definingClass == LPP_PROTOCOL
                }
            })
        }
    }

    private fun find(apk: File): Found = synchronized(cache) {
        cache.getOrPut(apk.absolutePath) {
            val scenes = mutableListOf<Method>()
            val callers = mutableMapOf<String, MutableList<Method>>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) {
                    if (LongPressSceneFingerprint.takes(method, classDef)) scenes += method
                    for (instruction in method.implementation?.instructions ?: continue) {
                        if (instruction.opcode != Opcode.INVOKE_STATIC) continue
                        val call = instruction.getReference<MethodReference>() ?: continue
                        if (call.returnType != "Ljava/lang/String;" ||
                            call.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/String;")
                        ) continue
                        callers.getOrPut("${call.definingClass}->${call.name}") { mutableListOf() } += method
                    }
                }
            }
            Found(scenes, callers)
        }
    }
}
