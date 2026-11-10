/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry

internal const val COMMENT_TYPING_FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"
internal const val COMMENT_TYPING_STICKER_RECOMMEND_ASSEM =
    "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/TypingStickerRecommendAssem;"

/**
 * Keeps TikTok's dedicated typing-recommendation row on its native hidden path. This changes
 * only TypingStickerRecommendAssem; the manual sticker picker lives in a separate component and
 * stays available.
 */
internal fun MutableMethod.forceCommentTypingStickerSuggestionsHidden() {
    guardAtEntry(
        "Hide comment typing suggestions",
        "invoke-static {}, " +
            "$COMMENT_TYPING_FEATURE_CONTROLS_DESCRIPTOR->hideQuickCommentReactions()Z",
        "const/4 p1, 0x0",
    )
}
