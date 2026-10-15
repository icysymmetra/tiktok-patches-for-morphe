/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * CommentPanelFakeInput decision whether the exposed quick-emoji row may be shown (rt() in TikTok 47.1.4).
 * TikTok 46.2.3 routed the same decision through a dedicated AB gate ("comment_hide_quick_emoji_research"),
 * which no longer exists; the keyboard model's force-disable flag is the stable anchor.
 */
internal object ExposedEmojiRowAllowedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type.endsWith("/CommentPanelFakeInput;") &&
            method.implementation?.instructions?.any { instruction ->
                instruction.getReference<MethodReference>()?.name == "getForceDisableExposedEmoji"
            } == true
    },
)

/**
 * PersonalizedEmojiExperiment "hide exposed emoji" gate (TikTok 47.1.4). Every exposed-emoji surface
 * (comment panel input, keyboard mini panel, its slot trigger, comment service) consults it; it replaced
 * the "comment_hide_quick_emoji_research" gate of 46.2.3.
 */
internal object HideExposedEmojiExperimentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type.endsWith("/PersonalizedEmojiExperiment;") &&
            method.implementation?.instructions?.any { instruction ->
                instruction.getReference<FieldReference>()?.name == "hideExposeEmoji"
            } == true
    },
)

/** HorizontalEmojiMiniPanelAssem.showPanel (js(Z) in TikTok 47.1.4): the exposed quick-emoji row above the input. */
internal object HorizontalEmojiPanelShowFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("showPanel "),
    custom = { _, classDef -> classDef.type.endsWith("/HorizontalEmojiMiniPanelAssem;") },
)

/** DetailBottomFakeInput personalized emoji binding (wt() in TikTok 47.1.4), the quick-reaction row of the detail page input. */
internal object DetailBottomEmojiBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("bindEmoji "),
    custom = { _, classDef -> classDef.type.endsWith("/DetailBottomFakeInput;") },
)

/** Function0 lambda reading the "im_long_press_share_button_to_quick_share" AB key; its holder exposes the ()I gate. */
internal object LongPressQuickShareKeyFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    strings = listOf("im_long_press_share_button_to_quick_share"),
)

internal object LongPressRepostGateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/digg/VideoDiggAssem;",
    returnType = "Z",
    parameters = listOf("Landroid/view/View;"),
    strings = listOf(
        "Long press detected on digg button for aweme: ",
        "long_press_like_panel",
    ),
)
