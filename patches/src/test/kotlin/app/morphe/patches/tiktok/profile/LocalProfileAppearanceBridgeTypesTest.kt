/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.profile

import app.morphe.BridgeTypeFlow
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's verifier is the only thing that type-checks an owned bridge, and #165 (0.72.0 to
 * 0.74.0) was a bridge it rejected: the profile tab crashed with VerifyError on the navbar
 * nickname class, or came up blank where TikTok swallowed it (#140). These run the same checks
 * on every bridge without a device.
 */
class LocalProfileAppearanceBridgeTypesTest {
    private companion object {
        const val STRING = "Ljava/lang/String;"
        const val OBJECT = "Ljava/lang/Object;"
        val getter: Method = ImmutableMethod(PROFILE_PROTOCOL, "LJIJI", emptyList(), PROFILE_COMMON_INFO,
            AccessFlags.PUBLIC.value, null, null, null)
        val legacyGetter: Method = ImmutableMethod(BASE_UI_COMPONENT, "uq", emptyList(), PROFILE_COMMON_INFO,
            AccessFlags.PUBLIC.value, null, null, null)
        val original = ImmutableMethodReference("Lkotlin/jvm/internal/Intrinsics;", "LJI", listOf(OBJECT, OBJECT), "Z")
    }

    @Test fun `every owned bridge passes the verifier's argument and return typing`() {
        val bridges = listOf(legacyAppearanceBridge(legacyGetter, original), nicknameAddNameBridge(), nicknameCachedBridge(),
            noticeAppearanceBridge(getter)) +
            listOf(NICKNAME_OWNER, NAVBAR_NICKNAME_OWNER).flatMap { owner ->
                listOf(nicknameAppearanceBridge(getter, owner), nicknameRenderBridge(owner), nicknameBeginBridge(getter, owner),
                    nicknameAppendBridge(getter, owner))
            } +
            listOf(NICKNAME_REPAINT, NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT).map { nicknameCurrentBridge(getter, it) }
        assertEquals(15, bridges.size)
        for (bridge in bridges) {
            assertEquals("${bridge.definingClass}->${bridge.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
        }
    }

    @Test fun `every uid read leaves the uid register null or a String on the fallback path`() {
        for (owner in listOf(NICKNAME_OWNER, NAVBAR_NICKNAME_OWNER)) {
            val begin = nicknameBeginBridge(getter, owner).implementation!!.instructions.toList()
            // The holder walk writes its own register; the uid register is written once, from getUid.
            val uidWrites = begin.withIndex().filter { (_, it) -> it.opcode.name == "move-result-object" &&
                (it as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA == 0 }
            assertEquals(1, uidWrites.size)
            assertEquals("getUid", (begin[uidWrites.single().index - 1] as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                .reference.let { (it as com.android.tools.smali.dexlib2.iface.reference.MethodReference).name })
        }
    }

    @Test fun `the 0 74 0 begin bridge is what the phone rejected`() {
        // hushfeedBeginNicknameBind as 0.72.0 to 0.74.0 built it: the holders walked through v0, so
        // :identity_ready met v0 as ProfileCommonInfo, UserProfileInfo and String, which is Object.
        val shipped = MutableMethod(ImmutableMethod(NAVBAR_NICKNAME_OWNER, APPEARANCE_BEGIN, emptyList(), "V",
            AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value, null, null,
            ImmutableMethodImplementation(3, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, """
                const/4 v0, 0x0
                invoke-virtual { p0 }, $PROFILE_PROTOCOL->LJIJI()$PROFILE_COMMON_INFO
                move-result-object v0
                if-eqz v0, :identity_ready
                invoke-virtual { v0 }, $PROFILE_COMMON_INFO->getUserProfileInfo()$PROFILE_INFO
                move-result-object v0
                if-eqz v0, :identity_ready
                invoke-virtual { v0 }, $PROFILE_INFO->getUid()$STRING
                move-result-object v0
                :identity_ready
                const-string v1, "nav_bar_center_nickname"
                invoke-static { p0, v0, v1 }, $LOCAL_APPEARANCE->beginNicknameBind($OBJECT$STRING$STRING)V
                return-void
            """)
        }
        val problems = BridgeTypeFlow.check(shipped)
        assertEquals(problems.toString(), 1, problems.size)
        assertTrue(problems.single().message, problems.single().message.contains("register v0 has type $OBJECT but expected $STRING"))
    }

    @Test fun `a merged return is caught the way a merged argument is`() {
        val merged = MutableMethod(ImmutableMethod(NICKNAME_REPAINT, "hushfeedExample", emptyList(), STRING,
            AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, """
                invoke-virtual { p0 }, $PROFILE_PROTOCOL->LJIJI()$PROFILE_COMMON_INFO
                move-result-object v0
                if-eqz v0, :none
                invoke-virtual { v0 }, $PROFILE_COMMON_INFO->getUserProfileInfo()$PROFILE_INFO
                move-result-object v0
                if-eqz v0, :none
                invoke-virtual { v0 }, $PROFILE_INFO->getUid()$STRING
                move-result-object v0
                :none
                return-object v0
            """)
        }
        assertEquals(1, BridgeTypeFlow.check(merged).size)
        val fixed = MutableMethod(ImmutableMethod(NICKNAME_REPAINT, "hushfeedExample", emptyList(), STRING,
            AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value, null, null,
            ImmutableMethodImplementation(3, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, """
                const/4 v0, 0x0
                invoke-virtual { p0 }, $PROFILE_PROTOCOL->LJIJI()$PROFILE_COMMON_INFO
                move-result-object v1
                if-eqz v1, :none
                invoke-virtual { v1 }, $PROFILE_COMMON_INFO->getUserProfileInfo()$PROFILE_INFO
                move-result-object v1
                if-eqz v1, :none
                invoke-virtual { v1 }, $PROFILE_INFO->getUid()$STRING
                move-result-object v0
                :none
                return-object v0
            """)
        }
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(fixed))
    }
}
