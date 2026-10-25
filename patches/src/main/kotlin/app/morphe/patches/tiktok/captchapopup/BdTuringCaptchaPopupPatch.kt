/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows hxreborn/hxreborn-tiktok-patches (GPL-3.0).
 */
package app.morphe.patches.tiktok.captchapopup

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.patcher.util.numberOfParameterRegisters

internal object RiskControlServiceExecuteFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/bdturing/verify/RiskControlService;",
    name = "execute",
    returnType = "Z",
    // The injection reads the first two declared parameters from p1 and p2. A static method
    // starts them at p0, so accepting one would call the gate with the wrong objects.
    custom = { method, _ -> method.hasInstanceReceiver() },
)

/**
 * BdTuring has a separate entry point from SecApiImpl and oecverify. Its hook uses the
 * same gate and diagnostic record, so it hides only what Hide CAPTCHA popups hides, and SMS
 * and two-factor verification always show.
 */
@Suppress("unused")
val bdTuringCaptchaPopupPatch = bytecodePatch(
    // The name is what Morphe Manager lists. BdTuring is ByteDance's own SDK name and means
    // nothing to anyone reading that list or the description, so neither uses it.
    name = "Hide the risk control CAPTCHA",
    description = "Lets Hide CAPTCHA popups also skip the puzzle TikTok's account safety check " +
        "raises while you're just browsing. SMS and two-step checks always show. It sits on " +
        "TikTok's account checks, which is why it isn't picked for you.",
    default = false,
) {
    // The request attribution and the Hide CAPTCHA popups switch this hook answers to.
    dependsOn(sharedExtensionPatch, captchaRequestRecorderPatch, hideCaptchaPopupsPatch)
    // Beside Hide CAPTCHA popups, whose switch it answers.
    category("Feed")
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        RiskControlServiceExecuteFingerprint.method.apply {
            // The fingerprint names no parameters, so the two it is written against are asked
            // for here, and each type is read for what the injection calls on it. A callback
            // that became a class takes invoke-virtual; a request without its two getters, or
            // a callback without onFail(I), is a build this cannot patch, and says so.
            check(parameterTypes.size >= 2) {
                "Hide the risk control CAPTCHA: execute takes ${parameterTypes.size} parameter(s), " +
                    "not the request and callback it is patched around."
            }
            val requestType = parameterTypes[0].toString()
            val callbackType = parameterTypes[1].toString()
            val callback = requireRiskControlMembers(requestType, callbackType) { classDefByOrNull(it) }
            val onFailInvoke = callback.instanceInvokeKind()

            // v0 to v2 are used as scratch. If the frame has fewer than three locals they
            // would be parameter registers instead, and writing v2 would destroy p2 (the
            // callback) before it is read. Fail the build rather than ship that.
            val localRegisters = implementation!!.registerCount - numberOfParameterRegisters
            if (localRegisters < 3) {
                throw PatchException(
                    "Hide the risk control CAPTCHA: execute has only $localRegisters local " +
                        "registers, so v0 to v2 overlap its parameters.",
                )
            }

            addInstructions(
                0,
                """
                    move-object/from16 v2, p1
                    invoke-virtual {v2}, $requestType->getActivity()Landroid/app/Activity;
                    move-result-object v0
                    invoke-virtual {v2}, $requestType->getServiceType()Ljava/lang/String;
                    move-result-object v1
                    invoke-static {v0, v1}, $CAPTCHA_GATE_CLASS_DESCRIPTOR->shouldHideTuringCaptchaPopup(Landroid/app/Activity;Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :morphe_show_bdturing_captcha_popup
                    const/4 v0, 0x3
                    move-object/from16 v1, p2
                    $onFailInvoke {v1, v0}, $callbackType->onFail(I)V
                    const/4 v0, 0x1
                    return v0
                    :morphe_show_bdturing_captcha_popup
                    nop
                """,
            )
        }
        registerForBuildDetails("enableRiskControlCaptcha")
    }
}
