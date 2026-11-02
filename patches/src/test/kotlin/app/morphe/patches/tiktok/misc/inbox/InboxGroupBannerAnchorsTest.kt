/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GROUP_BANNER_SUFFIX = "/InboxRecommendGroupBannerAssem;"
private const val BANNER_BASE_SUFFIX = "/InboxBannerAssem;"
private const val NOTICE = "Lcom/ss/android/ugc/aweme/im/common/model/IMNoticeMsgStruct;"
private const val CONTROLS = "Lapp/morphe/extension/tiktok/inbox/InboxControls;"

/**
 * What the group chat prompt switch hooks, held to each declared build: the update of the
 * Inbox's "start a group chat" banner, and the state a banner that is never updated stays in.
 * The guard goes onto the real method, so the answer lands in a local register and TikTok's own
 * update follows it untouched.
 */
class InboxGroupBannerAnchorsTest {
    @Test
    fun `each declared build has one group banner update and the fingerprint takes exactly it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val banners = classesEndingWith(apk, GROUP_BANNER_SUFFIX)
            assertEquals("$version: group banner classes ${banners.map { it.type }}", 1, banners.size)
            val classDef = banners.single()
            val taken = classDef.methods.filter { InboxGroupBannerUpdateFingerprint.takes(it, classDef) }
            assertEquals("$version: updates matched ${taken.map { it.name }}", 1, taken.size)
            val update = taken.single()
            assertFalse("$version: the update is static", AccessFlags.STATIC.isSet(update.accessFlags))
            assertEquals(listOf(NOTICE), update.parameterTypes.map(CharSequence::toString))
            // this and the notice are two registers; the guard's v0 is a local when the frame is bigger.
            assertTrue(
                "$version: ${update.implementation!!.registerCount} registers leave no local for the guard",
                update.implementation!!.registerCount >= 3,
            )
            assertTrue(
                "$version: the banner is no longer an InboxBannerCustomAssem",
                classDef.superclass.orEmpty().endsWith("/InboxBannerCustomAssem;"),
            )
        }
    }

    /**
     * The hook returns before the update posts anything, so what the Inbox shows is what the
     * base banner holds before any update: a pair whose first member is the enum's INIT. The
     * update itself posts TOP_SHOW to show and DISMISS to clear.
     */
    @Test
    fun `a banner that is never updated holds the INIT state and the update posts the others`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val group = classesEndingWith(apk, GROUP_BANNER_SUFFIX).single()
            val base = classesEndingWith(apk, BANNER_BASE_SUFFIX).single()
            val constructor = base.methods.single { it.name == "<init>" }.implementation!!.instructions.toList()

            val initialState = constructor.mapNotNull { it.getReference<FieldReference>() }
                .singleOrNull { it.name == "INIT" }
            assertTrue("$version: the base banner no longer starts from INIT", initialState != null)
            assertTrue(
                "$version: the initial state is not paired up",
                constructor.any { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == "Lkotlin/Pair;" },
            )

            val update = group.methods.single { InboxGroupBannerUpdateFingerprint.takes(it, group) }
            val posted = update.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
                .filter { it.definingClass == initialState!!.definingClass }.map { it.name }.toSet()
            assertTrue("$version: the update posts $posted", posted.containsAll(listOf("TOP_SHOW", "DISMISS")))
            assertFalse("$version: the update posts INIT itself", "INIT" in posted)
        }
    }

    @Test
    fun `the guard goes in front of the real update and leaves its body as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = classesEndingWith(apk, GROUP_BANNER_SUFFIX).single()
            val native = classDef.methods.single { InboxGroupBannerUpdateFingerprint.takes(it, classDef) }
            val before = native.implementation!!.instructions.toList()

            val guarded = MutableMethod(native)
            guarded.hideGroupChatBanner("Hide inbox items")
            val after = guarded.implementation!!.instructions.toList()

            assertEquals(
                "$version: the guard",
                listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                after.take(4).map { it.opcode },
            )
            val call = after[0].getReference<MethodReference>()!!
            assertEquals(CONTROLS, call.definingClass)
            assertEquals("shouldHideGroupChatBanner", call.name)
            assertEquals("Z", call.returnType)
            assertTrue(call.parameterTypes.isEmpty())
            assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
            assertEquals(0, (after[2] as OneRegisterInstruction).registerA)
            assertEquals(before.map { it.opcode }, after.takeLast(before.size).map { it.opcode })
        }
    }

    @Test
    fun `an update with no local register for the answer stops the patch`() {
        val tight = MutableMethod(
            ImmutableMethod(
                "Lfixture/Banner;", "update", listOf(ImmutableMethodParameter(NOTICE, null, null)), "V",
                AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
            ),
        )
        val before = tight.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { tight.hideGroupChatBanner("Hide inbox items") }
        assertEquals(before, tight.implementation!!.instructions.toList())
    }

    /** The classes of one build whose type ends as given, found by walking the dex once. */
    private fun classesEndingWith(apk: File, suffix: String): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type.endsWith(suffix)) found += classDef
            }
        }
        return found
    }
}
