/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EXPIRY = "Lapp/morphe/extension/tiktok/offline/OfflineVideoExpiry;"

/** TikTok's two fallback lifetimes, as the method loads them. */
private val TWO_DAYS = TimeUnit.HOURS.toMillis(48)
private val NINETY_DAYS = TimeUnit.DAYS.toMillis(90)

/** The query that lists the rows past their lifetime, which the start-up task deletes with their files. */
private const val EXPIRED_ROWS = "(insert_time + ?) <= ?"

private val WIDE_CONSTANTS = setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16)

/**
 * What Keep offline videos hooks (#123), held to each declared build: the one static ()J that
 * works out how long an offline video lives, known here by its two fallback lifetimes rather than
 * by the strings the fingerprint uses, so the two have to agree. Its one caller is the getter that
 * caches it per account, the table still lists rows by `insert_time` plus that lifetime, and the
 * guard applied to the real method answers a long out of locals and leaves TikTok's body as it was.
 */
class OfflineVideoLifetimeAnchorsTest {
    @Test
    fun `each declared build has one offline lifetime, the fingerprint takes it and the guard fits it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byShape = mutableListOf<Pair<ClassDef, Method>>()
            val byFingerprint = mutableListOf<Pair<ClassDef, Method>>()
            val calls = mutableListOf<Pair<Method, MethodReference>>()
            var expiredQuery = false
            walk(apk) { classDef, method ->
                if (isOfflineLifetime(method)) byShape += classDef to method
                if (OfflineVideoLifetimeFingerprint.takes(method, classDef)) byFingerprint += classDef to method
                method.implementation?.instructions?.forEach { instruction ->
                    if (instruction.opcode == Opcode.INVOKE_STATIC) {
                        instruction.getReference<MethodReference>()
                            ?.takeIf { it.returnType == "J" && it.parameterTypes.isEmpty() }
                            ?.let { calls += method to it }
                    }
                    if (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) {
                        if (instruction.getReference<StringReference>()?.string?.contains(EXPIRED_ROWS) == true) {
                            expiredQuery = true
                        }
                    }
                }
            }

            assertEquals("$version: lifetimes ${byShape.map { it.first.type + "->" + it.second.name }}", 1, byShape.size)
            assertEquals(
                "$version: the fingerprint took ${byFingerprint.map { it.first.type + "->" + it.second.name }}",
                byShape.map { it.first.type to it.second.name },
                byFingerprint.map { it.first.type to it.second.name },
            )
            val native = byShape.single().second
            assertTrue("$version: the lifetime is not static", AccessFlags.STATIC.isSet(native.accessFlags))
            assertTrue(
                "$version: ${native.implementation!!.registerCount} registers leave no pair of locals for the guard",
                native.implementation!!.registerCount >= 2,
            )

            // One caller, the per-account getter every offline query asks.
            val callers = calls.filter { (_, call) ->
                call.definingClass == native.definingClass && call.name == native.name
            }.map { it.first }.distinctBy { it.definingClass + "->" + it.name }
            assertEquals("$version: callers ${callers.map { it.definingClass + "->" + it.name }}", 1, callers.size)
            val getter = callers.single()
            assertTrue("$version: the getter is not static", AccessFlags.STATIC.isSet(getter.accessFlags))
            assertEquals("J", getter.returnType)
            assertTrue(getter.parameterTypes.isEmpty())

            assertTrue("$version: no query lists offline rows past insert_time plus the lifetime", expiredQuery)

            val before = native.implementation!!.instructions.toList()
            val guarded = MutableMethod(native)
            guarded.keepOfflineVideos("Custom offline videos limit")
            val after = guarded.implementation!!.instructions.toList()
            assertEquals(
                "$version: the guard",
                listOf(
                    Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                    Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_WIDE, Opcode.RETURN_WIDE,
                ),
                after.take(6).map { it.opcode },
            )
            val ask = after[0].getReference<MethodReference>()!!
            assertEquals(EXPIRY, ask.definingClass)
            assertEquals("keepOfflineVideos", ask.name)
            assertEquals("Z", ask.returnType)
            assertTrue(ask.parameterTypes.isEmpty())
            val kept = after[3].getReference<MethodReference>()!!
            assertEquals(EXPIRY, kept.definingClass)
            assertEquals("keptLifetimeMs", kept.name)
            assertEquals("J", kept.returnType)
            assertTrue(kept.parameterTypes.isEmpty())
            // The answer and the lifetime sit in locals, v0 and the pair v0 v1.
            assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
            assertEquals(0, (after[4] as OneRegisterInstruction).registerA)
            assertEquals(0, (after[5] as OneRegisterInstruction).registerA)
            // TikTok's own instructions follow the guard, in their own order.
            assertEquals(before.map { it.opcode }, after.takeLast(before.size).map { it.opcode })
        }
    }

    @Test
    fun `a lifetime with no pair of locals stops the patch and is left as it was`() {
        val tight = MutableMethod(
            ImmutableMethod(
                "Lfixture/Lifetime;", "lifetime", emptyList(), "J",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    1, listOf(ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)), null, null,
                ),
            ),
        )
        val before = tight.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { tight.keepOfflineVideos("Custom offline videos limit") }
        assertEquals(before, tight.implementation!!.instructions.toList())
    }

    /** A static ()J that loads both of TikTok's fallback lifetimes as wide constants. */
    private fun isOfflineLifetime(method: Method): Boolean {
        if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "J" || method.parameterTypes.isNotEmpty()) {
            return false
        }
        val wide = method.implementation?.instructions
            ?.filter { it.opcode in WIDE_CONSTANTS }
            ?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }
            ?.toSet() ?: return false
        return TWO_DAYS in wide && NINETY_DAYS in wide
    }

    /** Every method of one build, walked once and never held (the dex has millions). */
    private fun walk(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) visit(classDef, method)
            }
        }
    }
}
