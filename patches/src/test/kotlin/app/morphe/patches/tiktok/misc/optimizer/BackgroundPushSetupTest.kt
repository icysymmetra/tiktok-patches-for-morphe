/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Applies the real hooks. Shape-only optimizer tests did not detect push shutdown under All.
 *
 * <p>The patch itself registers a settings row, which needs the extension this suite runs
 * without, so the probe puts on the same hooks the patch does, through the function the patch
 * calls, with the option resolved the way the patch resolves it.
 */
class BackgroundPushSetupTest {
    @get:Rule val temporary = TemporaryFolder()

    // A fingerprint is an object that keeps its last match. These tests patch several APKs in one
    // JVM, and since patcher 1.15.1 a reused match looks its class up again in the current run, so
    // one left from the previous APK fails there ("Could not find class"). 1.15.0 handed back the
    // previous APK's method instead, so the checks below read the wrong APK after the first.
    private fun forgetMatches() {
        BufferPreloadGateFingerprint.clearMatch()
        InitPushTaskFingerprint.clearMatch()
    }

    @Test
    fun `the push choice stays off under All and the patch joins the default selection`() {
        assertEquals("Limit background traffic", networkTrafficGovernorPatch.name)
        assertTrue("its switch starts off, so simple mode can pick it", networkTrafficGovernorPatch.default)
        val option = networkTrafficGovernorPatch.options["skipPushSetup"]
        assertEquals(false, option.default)
        assertFalse(option.required)
        option.reset()
        assertEquals(false, option.value)
        assertFalse(skipsPushSetup(null))
        assertFalse(skipsPushSetup(false))
        assertTrue(skipsPushSetup(true))
    }

    @Test
    fun `missing false and null options keep complete push setup on every declared host`() {
        val option = networkTrafficGovernorPatch.options.values.singleOrNull { it.name == "skipPushSetup" }
        try {
            for (value in listOf("missing", "false", "null")) {
                option?.reset()
                if (value == "false") networkTrafficGovernorPatch.options.set("skipPushSetup", false)
                if (value == "null") networkTrafficGovernorPatch.options.set<Boolean>("skipPushSetup", null)
                val skips = skipsPushSetup(option?.value as Boolean?)
                assertFalse("$value asked for push setup to be skipped", skips)
                Fixtures.forEachDeclared { apk ->
                    val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
                    val original = container.dexEntryNames.asSequence()
                        .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                        .single { it.type == PUSH_OWNER }
                    val before = encoded(original)
                    var checked = false
                    val probe = bytecodePatch(name = "push setup preservation probe") {
                        execute {
                            val buffer = BufferPreloadGateFingerprint.method.implementation!!.instructions.toList()
                            limitBackgroundTraffic(skips)
                            assertArrayEquals("${apk.name}: push setup changed with $value", before,
                                encoded(classDefBy(PUSH_OWNER)))
                            assertSwitched(apk.name, buffer, BufferPreloadGateFingerprint.method.implementation!!.instructions.toList(),
                                listOf(Opcode.CONST_4, Opcode.RETURN))
                            checked = true
                        }
                    }
                    forgetMatches()
                    Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                        patcher += setOf(probe)
                        runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
                    }
                    assertTrue("${apk.name}: preservation probe never ran", checked)
                }
            }
        } finally {
            option?.reset()
        }
    }

    @Test
    fun `push shutdown requires an explicit true option and goes through the switch on every declared host`() {
        val option = networkTrafficGovernorPatch.options.values.singleOrNull { it.name == "skipPushSetup" }
        try {
            networkTrafficGovernorPatch.options.set("skipPushSetup", true)
            val skips = skipsPushSetup(option?.value as Boolean?)
            assertTrue("an explicit true skips push setup", skips)
            Fixtures.forEachDeclared { apk ->
                var checked = false
                val probe = bytecodePatch(name = "explicit push shutdown probe") {
                    execute {
                        val push = InitPushTaskFingerprint.method.implementation!!.instructions.toList()
                        val buffer = BufferPreloadGateFingerprint.method.implementation!!.instructions.toList()
                        limitBackgroundTraffic(skips)
                        assertSwitched(apk.name, push, InitPushTaskFingerprint.method.implementation!!.instructions.toList(),
                            listOf(Opcode.RETURN_VOID))
                        assertSwitched(apk.name, buffer, BufferPreloadGateFingerprint.method.implementation!!.instructions.toList(),
                            listOf(Opcode.CONST_4, Opcode.RETURN))
                        checked = true
                    }
                }
                forgetMatches()
                Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                    patcher += setOf(probe)
                    runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
                }
                assertTrue("${apk.name}: shutdown probe never ran", checked)
            }
        } finally {
            option?.reset()
        }
    }

    /**
     * The method asks Limit background traffic's switch before anything else, leaves through
     * [answer] when it says yes, and otherwise runs every instruction it had before.
     */
    private fun assertSwitched(where: String, before: List<Instruction>, after: List<Instruction>, answer: List<Opcode>) {
        val call = after[0].getReference<MethodReference>()
        assertEquals("$where: asks the switch first", Opcode.INVOKE_STATIC, after[0].opcode)
        assertEquals("$where: the switch", BACKGROUND_TRAFFIC_SWITCH,
            "${call!!.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
        assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$where: the answer with the switch on", answer, after.subList(3, 3 + answer.size).map { it.opcode })
        if (answer.first() == Opcode.CONST_4) {
            assertEquals("$where: answers no", 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
        }
        // The guard's label sits on a nop, and TikTok's own code follows it unchanged.
        assertEquals(Opcode.NOP, after[3 + answer.size].opcode)
        assertEquals("$where: TikTok's own code is still behind the switch",
            before.map { it.opcode }, after.drop(4 + answer.size).map { it.opcode })
    }

    private fun encoded(classDef: ClassDef): ByteArray {
        val output = MemoryDataStore()
        try {
            DexPool(Opcodes.getDefault()).apply { internClass(classDef) }.writeTo(output)
            return output.data
        } finally {
            output.close()
        }
    }

    private companion object {
        const val PUSH_OWNER = "Lcom/ss/android/ugc/aweme/legoImp/task/InitPushTask;"
    }
}
