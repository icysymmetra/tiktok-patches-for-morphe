package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** TikTok's two view report senders on every declared host, and the guard put in front of them. */
class WatchHistoryFixturesTest {
    @Test
    fun `each declared host has exactly one view report and one batch send`() {
        val items = mapOf(
            "47.4.3" to "LX/09aI;",
        )
        assertEquals(Fixtures.declaredVersions().toSet(), items.keys)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val methods = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { it.methods.asSequence() }
                .toList()

            val reports = methods.filter(::isViewReport)
            assertEquals("$version view report", 1, reports.size)
            assertEquals(version, items.getValue(version), reports.single().parameterTypes.single().toString())
            val flushes = methods.filter(::isViewReportFlush)
            assertEquals("$version batch send", 1, flushes.size)

            // The class holds these two, its static initializer and, on 47.0.3, LIZJ(List)Map, which
            // only folds the batch's maps into one. Nothing else on it could send.
            val onApi = methods.filter { it.definingClass == AWEME_STATS_API && it.name != "<clinit>" }.toSet()
            assertTrue("$version: both senders are on AwemeStatsApi", onApi.containsAll(reports + flushes))
            val others = onApi - (reports + flushes).toSet()
            assertTrue("$version: every other AwemeStatsApi method only builds data: ${others.map { it.name }}",
                others.all(::onlyBuildsData))

            for (method in reports + flushes) {
                assertTrue("$version ${method.name}: TikTok already leaves it early",
                    method.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID } > 1)
                assertTrue("$version ${method.name}: the guard needs a local",
                    method.implementation!!.registerCount - method.parameterTypes.size >= 1)
                assertGuarded(method)
            }
        }
    }

    @Test
    fun `a sender without its own strings, or off the stats class, is not taken`() {
        val report = method(AWEME_STATS_API, "LX/09bN;", static = true,
            "const-string v0, \"basic_vv_batch_size\"\nconst-string v0, \"homepage_hot\"\nreturn-void")
        assertTrue(isViewReport(report))
        assertFalse("the batch list is the flush's", isViewReport(
            method(AWEME_STATS_API, "Ljava/util/List;", static = true,
                "const-string v0, \"basic_vv_batch_size\"\nconst-string v0, \"homepage_hot\"\nreturn-void")))
        assertFalse("both strings are needed", isViewReport(
            method(AWEME_STATS_API, "LX/09bN;", static = true, "const-string v0, \"basic_vv_batch_size\"\nreturn-void")))
        assertFalse("an instance method is not the sender", isViewReport(
            method(AWEME_STATS_API, "LX/09bN;", static = false,
                "const-string v0, \"basic_vv_batch_size\"\nconst-string v0, \"homepage_hot\"\nreturn-void")))
        assertFalse("another class reading the same setting", isViewReport(
            method("LX/0P2X;", "LX/09bN;", static = true,
                "const-string v0, \"basic_vv_batch_size\"\nconst-string v0, \"homepage_hot\"\nreturn-void")))

        val flush = method(AWEME_STATS_API, "Ljava/util/List;", static = true,
            "const-string v0, \"first_install_time\"\nreturn-void")
        assertTrue(isViewReportFlush(flush))
        assertFalse("a void method is a sender, not a helper", onlyBuildsData(flush))
        assertFalse("the one-item report also loads it", isViewReportFlush(
            method(AWEME_STATS_API, "LX/09bN;", static = true, "const-string v0, \"first_install_time\"\nreturn-void")))
    }

    /** The guard asks first, leaves through return-void, and the native body follows untouched. */
    private fun assertGuarded(native: Method) {
        val mutable = MutableMethod(native)
        val before = mutable.implementation!!.instructions.toList()
        mutable.skipWhenWatchHistoryOff()
        val after = mutable.implementation!!.instructions.toList()
        val ask = after[0].getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/privacy/WatchHistoryRecording;", ask.definingClass)
        assertEquals("shouldSkip", ask.name)
        assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals(Opcode.RETURN_VOID, after[3].opcode)
        assertEquals(Opcode.NOP, after[4].opcode)
        val through = after[2] as OffsetInstruction
        assertSame("a false answer runs the native first instruction",
            after[4], after.single { it.location.codeAddress == after[2].location.codeAddress + through.codeOffset })
        assertEquals(before.size + 5, after.size)
        before.forEachIndexed { index, instruction -> assertSame(instruction, after[index + 5]) }
    }

    /** A method that returns what it builds and calls nothing past the JDK, org.json and TikTok's list guard. */
    private fun onlyBuildsData(method: Method) = method.returnType != "V" &&
        method.implementation!!.instructions.all { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@all true
            reference.definingClass.startsWith("Ljava/") || reference.definingClass.startsWith("Lorg/json/") ||
                reference.definingClass == "Lcom/bytedance/mt/protector/impl/collections/ListProtector;"
        }

    private fun method(owner: String, parameter: String, static: Boolean, body: String): Method {
        val mutable = MutableMethod(ImmutableMethod(
            owner, "LIZIZ", listOf(ImmutableMethodParameter(parameter, null, null)), "V",
            AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, null, null,
            ImmutableMethodImplementation(4, emptyList(), null, null),
        ))
        mutable.addInstructionsWithLabels(0, body)
        return mutable
    }
}
