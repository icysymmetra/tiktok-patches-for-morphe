/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val FOOTNOTE_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val FOOTNOTE_INFO = "Lcom/ss/android/ugc/aweme/feed/model/FootNoteInfo;"
private const val FOOTNOTE_SERVICE = "Lcom/ss/android/ugc/aweme/foonote/service/IFootNoteService;"

/**
 * Whether a video gets TikTok's Footnotes banner: one static `(Aweme)Z` that asks the video for
 * its FootNoteInfo, reads that note's banner and asks the Footnotes service whether to show it.
 * Both banner components, TnSBannerAssem and its trigger, call it before they build anything, so
 * answering false there is what a video with no note already gets. Its class is renamed on every
 * build (0ALT, 0ALh, 0ALl), so it is found by what it reads: the model classes keep their real
 * names, and no other method reads FootNoteInfo.banner and the service together.
 */
internal object FootnoteBannerGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(FOOTNOTE_AWEME),
    custom = { method, _ -> isFootnoteBannerGate(method) },
)

internal fun isFootnoteBannerGate(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf(FOOTNOTE_AWEME)) return false
    val code = method.implementation ?: return false
    var asksVideo = false
    var readsBanner = false
    var namesService = false
    for (instruction in code.instructions) {
        instruction.getReference<MethodReference>()?.let {
            if (it.definingClass == FOOTNOTE_AWEME && it.name == "getFootNoteInfo" &&
                it.returnType == FOOTNOTE_INFO && it.parameterTypes.isEmpty()
            ) asksVideo = true
        }
        instruction.getReference<FieldReference>()?.let {
            if (it.definingClass == FOOTNOTE_INFO && it.name == "banner") readsBanner = true
        }
        instruction.getReference<TypeReference>()?.let {
            if (it.type == FOOTNOTE_SERVICE) namesService = true
        }
    }
    return asksVideo && readsBanner && namesService
}

/**
 * Puts the Footnotes switch in front of the gate. With the switch on the gate answers false at
 * once, which both banner components take for a video that has no note, and with it off the
 * gate runs from its own first instruction. The answer lands in v0, a local on every build
 * (the gate has six), and guardAtEntry checks that before it writes.
 */
internal fun MutableMethod.hideFootnoteBanner(patch: String) = guardAtEntry(
    patch,
    "invoke-static {}, Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->shouldHideFootnotes()Z",
    """
        const/4 v0, 0x0
        return v0
    """,
)
