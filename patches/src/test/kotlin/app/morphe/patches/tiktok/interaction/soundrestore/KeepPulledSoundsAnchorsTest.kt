/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.soundrestore

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONTROLLER = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
private const val SEARCH =
    "Lcom/ss/android/ugc/aweme/search/pages/result/common/copyrightmute/core/communicate/SearchCopyrightMuteServiceImpl;"

/**
 * Keep pulled sounds' two anchors on each declared build: the feed player's mute decision,
 * which reads the sound's status and the post's mute flag once each, and search's copyright
 * mute. Each hook is applied to the real method it would patch.
 */
class KeepPulledSoundsAnchorsTest {
    @Test
    fun `the player has one mute decision on every build and each answer passes through the extension`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = classesOf(apk, setOf(CONTROLLER))[CONTROLLER] ?: error("$version: no PlayerController")
            val taken = classDef.methods.filter { PlayerMuteDecisionFingerprint.takes(it, classDef) }
            assertEquals("$version: mute decisions matched", 1, taken.size)

            val decision = MutableMethod(taken.single())
            val before = decision.implementation!!.instructions.toList()
            val reads = listOf(decision.musicStatusReads().single(), decision.videoMuteReads().single())
            reads.forEach { assertEquals("$version: a read's result", Opcode.MOVE_RESULT, before[it + 1].opcode) }

            decision.passMuteAnswersThroughExtension()
            val after = decision.implementation!!.instructions.toList()
            assertEquals("$version: two calls and two results added", before.size + 4, after.size)
            assertHanded(version, after, decision.musicStatusReads().single(), "keepStatus", "I")
            assertHanded(version, after, decision.videoMuteReads().single(), "keepMuted", "Z")

            // Nothing of TikTok's moved: dropping the four added instructions gives its method back.
            val added = listOf(decision.musicStatusReads().single(), decision.videoMuteReads().single())
                .flatMap { listOf(it + 2, it + 3) }.toSet()
            val kept = after.filterIndexed { index, _ -> index !in added }
            assertEquals(before.map { it.opcode }, kept.map { it.opcode })
        }
    }

    @Test
    fun `search has one copyright mute check on every build and the guard answers not muted`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = classesOf(apk, setOf(SEARCH))[SEARCH] ?: error("$version: no search copyright mute")
            val taken = classDef.methods.filter { SearchCopyrightMuteFingerprint.takes(it, classDef) }
            assertEquals("$version: search mute checks matched", 1, taken.size)

            val check = MutableMethod(taken.single())
            val before = check.implementation!!.instructions.toList()
            check.guardAtEntry(
                "Keep pulled sounds",
                "invoke-static {}, $PULLED_SOUNDS->keepInSearch()Z",
                "const/4 v0, 0x0\nreturn v0",
            )
            val after = check.implementation!!.instructions.toList()
            assertEquals(
                "$version: guard",
                listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN, Opcode.NOP),
                after.take(6).map { it.opcode },
            )
            val call = after[0].getReference<MethodReference>()!!
            assertEquals(PULLED_SOUNDS, call.definingClass)
            assertEquals("keepInSearch", call.name)
            assertEquals(before.map { it.opcode }, after.drop(6).map { it.opcode })
        }
    }

    @Test
    fun `a decision that reads the sound's status twice stops the patch`() {
        val decision = decision(listOf(statusRead(), statusRead(), muteRead()))
        val failure = assertThrows(PatchException::class.java) { decision.passMuteAnswersThroughExtension() }
        assertTrue(failure.message!!.contains("status 2 time(s)"))
    }

    @Test
    fun `a decision that never reads the post's mute stops the patch`() {
        val decision = decision(listOf(statusRead()))
        val failure = assertThrows(PatchException::class.java) { decision.passMuteAnswersThroughExtension() }
        assertTrue(failure.message!!.contains("post's mute 0 time(s)"))
    }

    @Test
    fun `a result in a high register is handed over with a range call`() {
        val decision = decision(listOf(statusRead(register = 20), muteRead(register = 20)), registers = 24)
        decision.passMuteAnswersThroughExtension()
        val code = decision.implementation!!.instructions.toList()
        for (index in listOf(2, 6)) {
            assertEquals(Opcode.INVOKE_STATIC_RANGE, code[index].opcode)
            assertEquals(20, (code[index] as RegisterRangeInstruction).startRegister)
            assertEquals(20, (code[index + 1] as OneRegisterInstruction).registerA)
        }
    }

    private fun assertHanded(version: String, code: List<Instruction>, read: Int, callback: String, type: String) {
        val register = (code[read + 1] as OneRegisterInstruction).registerA
        val call = code[read + 2]
        assertEquals("$version: $callback call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val range = call as RegisterRangeInstruction
        assertEquals(1, range.registerCount)
        assertEquals("$version: $callback reads the result", register, range.startRegister)
        val target = call.getReference<MethodReference>()!!
        assertEquals(PULLED_SOUNDS, target.definingClass)
        assertEquals(callback, target.name)
        assertEquals(listOf(type), target.parameterTypes.map(CharSequence::toString))
        assertEquals(type, target.returnType)
        assertEquals(Opcode.MOVE_RESULT, code[read + 3].opcode)
        assertEquals("$version: $callback writes the result back", register, (code[read + 3] as OneRegisterInstruction).registerA)
    }

    private fun statusRead(register: Int = 0) = listOf(
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(MUSIC, "getMusicStatus", emptyList<String>(), "I")),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, register),
    )

    private fun muteRead(register: Int = 0) = listOf(
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(VIDEO_MUTE_INFO, "isMute", emptyList<String>(), "Z")),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, register),
    )

    private fun decision(reads: List<List<Instruction>>, registers: Int = 8) = MutableMethod(
        ImmutableMethod(
            CONTROLLER, "LLJZ",
            listOf(
                ImmutableMethodParameter("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", null, null),
                ImmutableMethodParameter("LX/037s;", null, null),
                ImmutableMethodParameter("Z", null, null),
            ),
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(registers, reads.flatten() + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null),
        ),
    )

    private fun classesOf(apk: java.io.File, names: Set<String>): Map<String, ClassDef> {
        val found = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type in names) found.putIfAbsent(classDef.type, classDef)
            }
            if (found.size == names.size) break
        }
        return found
    }
}
