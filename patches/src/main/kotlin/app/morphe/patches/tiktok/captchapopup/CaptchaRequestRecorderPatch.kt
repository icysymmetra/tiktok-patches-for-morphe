/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.captchapopup

import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val CAPTCHA_GATE_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/CaptchaGate;"

private const val CALL_SERVER_INTERCEPTOR_DESCRIPTOR = "Lcom/bytedance/retrofit2/CallServerInterceptor;"
private const val NETWORK_EXECUTE_CALL_METHOD =
    "com_bytedance_retrofit2_CallServerInterceptor_com_ss_android_ugc_aweme_feed_lancet_NetworkUtilsLancet_executeCall"

/**
 * Tells the gate the path of every call TikTok makes, so a puzzle that arrives while a write
 * is in flight can be recognised as belonging to it.
 *
 * Both CAPTCHA patches depend on this. Without it the gate cannot tell a browsing puzzle from
 * one the server raised over a follow, and would hide either, which is the failure the gate
 * exists to prevent. A missing anchor fails the build rather than shipping that quietly.
 */
internal val captchaRequestRecorderPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        val interceptor = mutableClassDefBy(CALL_SERVER_INTERCEPTOR_DESCRIPTOR)
        val method = interceptor.methods
            .firstOrNull { it.name == NETWORK_EXECUTE_CALL_METHOD && it.implementation != null }
            ?: throw PatchException("CAPTCHA request recorder: $NETWORK_EXECUTE_CALL_METHOD is missing.")

        val instructions = method.implementation!!.instructions.toList()
        val requestIndex = instructions.indexOfFirst {
            it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.name == "mOriginalRequest"
        }
        if (requestIndex < 0) {
            throw PatchException(
                "CAPTCHA request recorder: mOriginalRequest is not read in $NETWORK_EXECUTE_CALL_METHOD.",
            )
        }

        val requestRegister = (instructions[requestIndex] as OneRegisterInstruction).registerA
        method.addInstructions(
            requestIndex + 1,
            "invoke-static/range {v$requestRegister .. v$requestRegister}, " +
                "$CAPTCHA_GATE_CLASS_DESCRIPTOR->recordRequest(Ljava/lang/Object;)V",
        )
    }
}
