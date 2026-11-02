/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.captchapopup

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patches.tiktok.shared.valueIn

/**
 * Every entry point below is named by a class and a method TikTok wrote. What was written here
 * as well is the callback each one hands the request to, and that class is renamed by every
 * build: the browsing callback was `LX/13fZ;` on 46.2.3 and is `LX/12ib;` on 46.8.3, the LIVE one
 * `LX/1Cc3;` and `LX/19Kc;`, the Turing one `LX/13eU;` and `LX/12jk;`. Its own method names have
 * not moved, so the type comes off the match and only the method name stays written down, checked
 * against the class before anything is assembled against it.
 */
/**
 * The first four parameters, not the whole list. 46.9.3 adds a fifth (a string) and moves the
 * body, its log line included, into that overload; the four-parameter one left behind is an
 * eight-instruction forwarder that carries no string, so the `strings` filter keeps the two
 * apart and the hook below lands on the one that does the work. p1 to p3 mean the same thing
 * on both.
 */
private object CaptchaPopupFingerprint : Fingerprint(
    definingClass = "/sec/SecApiImpl;",
    name = "popCaptchaV2",
    returnType = "V",
    strings = listOf("popCaptchaV2 - riskInfo =").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        parameters.size >= 4 &&
            parameters[0] == "Landroid/app/Activity;" &&
            parameters[1] == "Ljava/lang/String;" &&
            parameters[2].startsWith("L") &&
            parameters[3] == "Landroidx/fragment/app/Fragment;"
    },
)

private object LegacyCaptchaPopupFingerprint : Fingerprint(
    definingClass = "/sec/SecApiImpl;",
    name = "popCaptcha",
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", "I", "L"),
    strings = listOf("popCaptcha - errorcode = ").map { string(it, StringComparisonType.CONTAINS) },
)

private object OecCaptchaPopupFingerprint : Fingerprint(
    definingClass = "Lcom/tts/oecverify/verify/RiskControlService;",
    name = "execute",
    returnType = "Z",
    parameters = listOf("L", "Lcom/tts/oecverify/BdTuringCallback;"),
)

private object LiveHostCaptchaPopupFingerprint : Fingerprint(
    definingClass = "/live/livehostimpl/LiveHostUser;",
    name = "popCaptchaV2",
    returnType = "V",
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "L",
        "Landroidx/fragment/app/Fragment;",
    ),
)

private object BdTuringCaptchaPopupFingerprint : Fingerprint(
    definingClass = "Lcom/tts/oecverify/BdTuring;",
    name = "showVerifyDialog",
    returnType = "V",
    parameters = listOf(
        "Landroid/app/Activity;",
        "L",
        "Lcom/tts/oecverify/BdTuringCallback;",
    ),
)

/** What each callback is told when its request is dropped, checked before it is called. */
private const val SEC_DISMISS = "LIZJ"
// LIZIZ until 47.4.3, whose LIVE callback (`LX/1Uei`) dismisses through LIZLLL.
private const val LIVE_DISMISS = "LIZLLL"

/**
 * The whole `invoke-... {p3 .. p3}, type->name()V`, having checked that the class in this build
 * declares it. A callback whose dismiss notice moved would otherwise assemble into a call to
 * nothing and fail on a phone.
 *
 * <p>The invoke kind comes from what the callback is rather than being written down beside it.
 * These types change kind between builds: the name `LX/1Cc3;` is the LIVE callback interface on
 * 46.2.3 and an unrelated final class on the two builds since, and invoke-interface against a
 * class is the same class-load failure the name check exists to prevent.
 */
private fun BytecodePatchContext.dismissCall(type: String, name: String): String {
    return captchaDismissCall(type, name) { classDefByOrNull(it) }
}

@Suppress("unused")
val hideCaptchaPopupsPatch = bytecodePatch(
    name = "Hide CAPTCHA popups",
    description = "Skips the puzzle TikTok shows while you're just browsing. A puzzle right after " +
        "you like, follow, comment or repost still shows, and so do sign-in, SMS and two-step " +
        "checks. Starts off. Turn it on in Metra settings > Interface, then restart TikTok.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch, captchaRequestRecorderPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCaptchaPopupSuppression()V",
        )

        // Every invoke below is /range: the parameter registers of a large native method can
        // sit above v15, which format 35c cannot encode.
        CaptchaPopupFingerprint.method.apply {
            requireLocals("Hide CAPTCHA popups", 1)
            val dismiss = dismissCall(parameterTypes[2].toString(), SEC_DISMISS)
            guardAtEntry(
                "popCaptchaV2",
                "invoke-static/range {p1 .. p2}, $CAPTCHA_GATE_CLASS_DESCRIPTOR->shouldHideCaptchaPopup(Landroid/app/Activity;Ljava/lang/String;)Z",
                """
                    if-eqz p3, :morphe_hide_captcha_popup_return
                    $dismiss
                    :morphe_hide_captcha_popup_return
                    return-void
                """,
            )
        }

        LegacyCaptchaPopupFingerprint.method.apply {
            requireLocals("Hide CAPTCHA popups", 1)
            val dismiss = dismissCall(parameterTypes[2].toString(), SEC_DISMISS)
            guardAtEntry(
                "popCaptchaV2",
                "invoke-static/range {p1 .. p2}, $CAPTCHA_GATE_CLASS_DESCRIPTOR->shouldHideLegacyCaptchaPopup(Landroid/app/Activity;I)Z",
                """
                    if-eqz p3, :morphe_hide_legacy_captcha_popup_return
                    $dismiss
                    :morphe_hide_legacy_captcha_popup_return
                    return-void
                """,
            )
        }

        OecCaptchaPopupFingerprint.method.apply {
            requireLocals("Hide CAPTCHA popups", 2)
            val onFail = callThroughLocals(
                "Hide CAPTCHA popups",
                "invoke-interface",
                "Lcom/tts/oecverify/BdTuringCallback;->onFail(ILorg/json/JSONObject;)V",
                objectIn("p2"),
                valueIn("v0"),
                objectIn("v1"),
            )
            guardAtEntry(
                "popCaptchaV2",
                "invoke-static/range {p1 .. p1}, $CAPTCHA_GATE_CLASS_DESCRIPTOR->shouldHideOecCaptchaPopup(Ljava/lang/Object;)Z",
                """
                    const/4 v0, 0x3
                    const/4 v1, 0x0
                    $onFail
                    const/4 v0, 0x1
                    return v0
                """,
            )
        }

        LiveHostCaptchaPopupFingerprint.method.apply {
            requireLocals("Hide CAPTCHA popups", 1)
            val dismiss = dismissCall(parameterTypes[2].toString(), LIVE_DISMISS)
            guardAtEntry(
                "popCaptchaV2",
                "invoke-static/range {p1 .. p2}, $CAPTCHA_GATE_CLASS_DESCRIPTOR->shouldHideCaptchaPopup(Landroid/app/Activity;Ljava/lang/String;)Z",
                """
                    if-eqz p3, :morphe_hide_live_captcha_popup_return
                    $dismiss
                    :morphe_hide_live_captcha_popup_return
                    return-void
                """,
            )
        }

        // Network verification can present Turing directly without passing through SecApiImpl.
        BdTuringCaptchaPopupFingerprint.method.apply {
            requireLocals("Hide CAPTCHA popups", 2)
            val onFail = callThroughLocals(
                "Hide CAPTCHA popups",
                "invoke-interface",
                "Lcom/tts/oecverify/BdTuringCallback;->onFail(ILorg/json/JSONObject;)V",
                objectIn("p3"),
                valueIn("v0"),
                objectIn("v1"),
            )
            guardAtEntry(
                "popCaptchaV2",
                "invoke-static/range {p1 .. p2}, $CAPTCHA_GATE_CLASS_DESCRIPTOR->shouldHideTuringDialog(Landroid/app/Activity;Ljava/lang/Object;)Z",
                """
                    if-eqz p3, :morphe_hide_turing_captcha_popup_return
                    const/4 v0, 0x3
                    const/4 v1, 0x0
                    $onFail
                    :morphe_hide_turing_captcha_popup_return
                    return-void
                """,
            )
        }
    }
}
