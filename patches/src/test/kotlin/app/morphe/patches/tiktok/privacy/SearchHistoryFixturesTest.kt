package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two search-history writers on every declared host, and the guard put in front of them. */
class SearchHistoryFixturesTest {
    @Test
    fun `each declared host has exactly one history recorder and one manual search log`() {
        val expected = mapOf(
            "47.4.3" to "LX/0CzL;",
        )
        assertEquals(Fixtures.declaredVersions().toSet(), expected.keys)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val methods = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { it.methods.asSequence() }
                .toList()

            val recorders = methods.filter(::isSearchHistoryRecorder)
            assertEquals("$version recorder", 1, recorders.size)
            val recorder = recorders.single()
            assertEquals(version, expected.getValue(version), recorder.definingClass)

            // Every caller reaches the manager through its interface, so a second concrete
            // (SearchHistory, String)V class would be a second manager this patch misses. The
            // one this guards also has its delete method, which must stay unguarded. A writer whose
            // body is a lone return-void records nothing (47.4.3's empty stand-in for the manager).
            val historyWriters = methods.filter {
                it.implementation != null && it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(SEARCH_HISTORY, "Ljava/lang/String;") &&
                    it.implementation!!.instructions.any { instruction -> instruction.opcode != Opcode.RETURN_VOID }
            }
            assertEquals("$version: every concrete history writer is on the manager",
                setOf(recorder.definingClass), historyWriters.map { it.definingClass }.toSet())
            assertEquals("$version: record and delete", 2, historyWriters.size)

            val manual = methods.filter(::isManualSearchRecorder)
            assertEquals("$version manual search log", 1, manual.size)
            assertEquals(MANUAL_SEARCH_PV_STORE, manual.single().definingClass)

            for (method in recorders + manual) {
                assertTrue("$version ${method.name}: the guard needs a local",
                    method.implementation!!.registerCount - parameterRegisters(method) >= 1)
                assertGuarded(method)
            }
        }
    }

    @Test
    fun `the delete method and a changed recorder are not taken for the record method`() {
        val delete = method("LX/0D1m;", listOf(SEARCH_HISTORY, "Ljava/lang/String;"), static = false,
            "const-string v0, \"deleteSearchHistory, repo = \"\nreturn-void")
        assertFalse(isSearchHistoryRecorder(delete))
        val renamedTag = method("LX/0D1m;", listOf(SEARCH_HISTORY, "Ljava/lang/String;"), static = false,
            "const-string v0, \"recordSearchHistoryV2, repo:\"\nreturn-void")
        assertFalse("the tag is matched whole", isSearchHistoryRecorder(renamedTag))
        // The tag on its own is not what the host loads: every line opens with it and a comma.
        val bareTag = method("LX/0D1m;", listOf(SEARCH_HISTORY, "Ljava/lang/String;"), static = false,
            "const-string v0, \"recordSearchHistory\"\nreturn-void")
        assertFalse(isSearchHistoryRecorder(bareTag))
        val record = method("LX/0D1m;", listOf(SEARCH_HISTORY, "Ljava/lang/String;"), static = false,
            "const-string v0, \"recordSearchHistory, repo:\"\nreturn-void")
        assertTrue(isSearchHistoryRecorder(record))
        val wrongStore = method("LX/0Ijz;", listOf("Ljava/lang/String;", "Ljava/lang/String;"), static = true,
            "iput-object v0, v0, $MANUAL_SEARCH_USER_STATE->lastHistoryChannelSearchMs:Ljava/lang/Long;\nreturn-void")
        assertFalse("the log is on ManualSearchPvStore only", isManualSearchRecorder(wrongStore))
    }

    @Test
    fun `a writer without a free local refuses the guard before writing it`() {
        val full = MutableMethod(ImmutableMethod(
            "LX/0D1m;", "LIZ", listOf(ImmutableMethodParameter(SEARCH_HISTORY, null, null),
                ImmutableMethodParameter("Ljava/lang/String;", null, null)), "V",
            AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        ))
        assertThrows(PatchException::class.java) { full.skipWhenHistoryOff() }
        assertEquals("nothing was written", 1, full.implementation!!.instructions.count())
    }

    /** The guard asks first, leaves through return-void, and the native body follows untouched. */
    private fun assertGuarded(native: Method) {
        val mutable = MutableMethod(native)
        val before = mutable.implementation!!.instructions.toList()
        mutable.skipWhenHistoryOff()
        val after = mutable.implementation!!.instructions.toList()
        val ask = after[0].getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/privacy/SearchHistoryRecording;", ask.definingClass)
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

    private fun parameterRegisters(method: Method) =
        method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum() +
            if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1

    private fun method(owner: String, parameters: List<String>, static: Boolean, body: String): Method {
        val mutable = MutableMethod(ImmutableMethod(
            owner, "LIZ", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, null, null,
            ImmutableMethodImplementation(4, emptyList(), null, null),
        ))
        mutable.addInstructionsWithLabels(0, body)
        return mutable
    }
}
