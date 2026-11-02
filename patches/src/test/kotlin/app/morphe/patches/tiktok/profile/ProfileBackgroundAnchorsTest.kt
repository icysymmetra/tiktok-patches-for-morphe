/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.profile

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Profile background answers, held to each declared build (#80).
 *
 * The patch's own shape rule picks one class and one gate in it. The gate reads the account's
 * profile_bg_in_allow_list snapshot, so it is the allow-list decision and not some other boolean
 * of the experiment; Edit profile and the background component both ask it, so answering it is
 * what opens the editor; its frame has a local for the switch's answer, and nothing jumps back to
 * its first instruction, so the guard in front of it can't be stepped over.
 */
class ProfileBackgroundAnchorsTest {
    @Test
    fun `the profile background gate resolves to one class and one method on each build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)

            val holders = classes.values.filter { it.isProfileBackgroundGateHolder() }
            assertEquals("$version: gate holders ${holders.map { it.type }}", 1, holders.size)
            val holder = holders.single()
            val gates = holder.methods.filter { it.isProfileBackgroundGate() }
            assertEquals("$version: gates in ${holder.type}: ${gates.map { it.name }}", 1, gates.size)
            val gate = gates.single()

            val readsAllowList = gate.strings().contains(ALLOW_LIST) || gate.calls().any { call ->
                call.definingClass == holder.type &&
                    holder.methods.any { it.name == call.name && it.strings().contains(ALLOW_LIST) }
            }
            assertTrue("$version: ${holder.type}->${gate.name} doesn't read $ALLOW_LIST", readsAllowList)

            // The switch answers with the avatar layout check: it must be the static boolean
            // the gate asks on both branches, once before each return of true.
            val layoutCheck = gate.profileBackgroundLayoutCheck()
            assertTrue("$version: no single layout check in ${gate.name}", layoutCheck != null)
            assertEquals("$version: the layout check isn't asked on both branches", 2,
                gate.calls().count { "${it.definingClass}->${it.name}()Z" == layoutCheck })

            val body = gate.implementation!!
            assertTrue("$version: no local for the switch's answer",
                body.registerCount - gate.numberOfParameterRegisters >= 1)
            val instructions = body.instructions.toList()
            val addresses = instructions.runningFold(0) { at, instruction -> at + instruction.codeUnits }
            assertFalse("$version: something jumps back to the gate's first instruction",
                instructions.indices.any { i ->
                    val branch = instructions[i] as? OffsetInstruction
                    branch != null && addresses[i] + branch.codeOffset == 0
                })
            assertFalse("$version: an exception handler lands on the gate's first instruction",
                body.tryBlocks.any { block -> block.exceptionHandlers.any { it.handlerCodeAddress == 0 } })

            // The allow-list read the profile requests send (#80): one, an int straight into a
            // move-result, and the snapshot it builds is what the profile requests carry.
            val read = holder.profileBackgroundAllowListRead()
            assertTrue("$version: no single allow-list read in ${holder.type}", read != null)
            val reader = read!!.first
            val requesters = sortedSetOf<String>()
            for (classDef in classes.values) {
                for (method in classDef.methods) {
                    val calls = method.calls()
                    if (calls.any { it.definingClass == holder.type && it.name == reader.name && it.returnType == reader.returnType } &&
                        calls.any { it.name in PROFILE_REQUESTS }
                    ) requesters += classDef.type
                }
            }
            assertTrue("$version: no profile request sends the allow-list snapshot", requesters.isNotEmpty())

            val askers = sortedSetOf<String>()
            for (classDef in classes.values) {
                for (method in classDef.methods) {
                    if (method.calls().any { it.definingClass == holder.type && it.name == gate.name &&
                            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf("Z") }
                    ) askers += classDef.type
                }
            }
            REQUIRED_ASKERS.forEach { asker ->
                assertTrue("$version: $asker no longer asks the gate; askers are $askers", asker in askers)
            }
        }
    }

    private companion object {
        const val ALLOW_LIST = "profile_bg_in_allow_list"

        /** TikTok's own profile and another profile, each fetched with the allow-list value. */
        val PROFILE_REQUESTS = setOf("getSelfUserInfo", "getOtherUserInfo")

        /** Edit profile, which builds the background editor, and the background on a profile. */
        val REQUIRED_ASKERS = listOf(
            "Lcom/ss/android/ugc/profile/business/ur/ui/MusProfileEditFragment;",
            "Lcom/ss/android/ugc/profile/platform/business/background/ProfileBackgroundComponent;",
        )

        fun Method.strings(): List<String> = implementation?.instructions?.mapNotNull {
            it.getReference<StringReference>()?.string
        }.orEmpty()

        fun Method.calls(): List<MethodReference> = implementation?.instructions?.mapNotNull {
            it.getReference<MethodReference>()
        }.orEmpty()
    }
}
