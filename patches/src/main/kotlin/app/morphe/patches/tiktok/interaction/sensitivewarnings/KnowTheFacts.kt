/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sensitivewarnings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val KTF_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val KTF_INFO = "Lcom/ss/android/ugc/aweme/feed/model/KtfInfo;"
internal const val KTF_SHARE_WARNINGS =
    "Lcom/ss/android/ugc/aweme/compliance/business/share/ShareWarningInfoServiceImpl;"
internal val KTF_SHARE_PARAMETERS = listOf(
    "Landroid/content/Context;",
    KTF_AWEME,
    "Landroid/os/Bundle;",
    "Lkotlin/jvm/functions/Function0;",
)
private const val KTF_SHARE_EVENT = "tns_share_warning_popout_ktf"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/feed/SensitiveWarnings;"

/**
 * Whether a video gets TikTok's Know the facts banner, the "Check your sources before sharing"
 * strip across the top of the video (layout common_feed_view_video_caution). One static
 * `(Aweme)Z` reads the upload struct's KtfInfo and answers its showWarning flag. The slot that
 * draws the banner (VideoTopCautionAssem), its trigger and the view model's observer all ask it
 * first, so false there is what every video without the warning already gets. Its class is
 * renamed on every build (08CT on 47.1.4), so it is found by what it reads: KtfInfo keeps its
 * real name and nothing else reads showWarning.
 */
internal object KnowTheFactsBannerGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(KTF_AWEME),
    custom = { method, _ -> isKnowTheFactsBannerGate(method) },
)

internal fun isKnowTheFactsBannerGate(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf(KTF_AWEME)) return false
    val code = method.implementation ?: return false
    return code.instructions.any { instruction ->
        instruction.getReference<FieldReference>()?.let {
            it.definingClass == KTF_INFO && it.name == "showWarning" && it.type == "Z"
        } == true
    }
}

/**
 * The prompt TikTok raises when you share a video carrying a Know the facts warning. The share
 * package asks the compliance service before it shares: true means the service took over and
 * shows its sheet, false lets the share go straight on. Of the service's two `(Context, Aweme,
 * Bundle, Function0)Z` checks this is the one that reads KtfInfo.showMessageOnShare and logs
 * the KTF popout; the other covers election and synthetic media prompts and stays as it is.
 */
internal object KnowTheFactsSharePromptFingerprint : Fingerprint(
    definingClass = KTF_SHARE_WARNINGS,
    returnType = "Z",
    parameters = KTF_SHARE_PARAMETERS,
    custom = { method, _ -> isKnowTheFactsSharePrompt(method) },
)

internal fun isKnowTheFactsSharePrompt(method: Method): Boolean {
    if (method.definingClass != KTF_SHARE_WARNINGS || method.returnType != "Z") return false
    if (method.parameterTypes.map(CharSequence::toString) != KTF_SHARE_PARAMETERS) return false
    val code = method.implementation ?: return false
    var readsShareMessage = false
    var logsPopout = false
    for (instruction in code.instructions) {
        instruction.getReference<FieldReference>()?.let {
            if (it.definingClass == KTF_INFO && it.name == "showMessageOnShare") readsShareMessage = true
        }
        instruction.getReference<StringReference>()?.let {
            if (it.string == KTF_SHARE_EVENT) logsPopout = true
        }
    }
    return readsShareMessage && logsPopout
}

/**
 * Both Know the facts checks answer false at once while the switch is on, which is what a video
 * without the warning gets: no banner, and a share that goes ahead. The answer lands in v0, a
 * local on both (the gate has three registers for one parameter, the prompt fifteen for five),
 * and guardAtEntry checks that before it writes.
 */
internal fun MutableMethod.hideKnowTheFacts(patch: String) = guardAtEntry(
    patch,
    "invoke-static {}, $EXTENSION->hideCheckYourSources()Z",
    """
        const/4 v0, 0x0
        return v0
    """,
)
