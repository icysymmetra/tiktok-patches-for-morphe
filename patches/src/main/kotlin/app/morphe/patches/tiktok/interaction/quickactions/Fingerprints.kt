/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

internal object QuickCommentBindFingerprint : Fingerprint(
    definingClass = QUICK_COMMENT_ASSEM,
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    custom = { method, classDef ->
        classDef.fields.singleOrNull { field -> field.type == QUICK_COMMENT_ROW }?.let { row ->
            method.quickCommentVisibilityWrites(row).size >= 2
        } == true
    },
)

/**
 * The comment keyboard's exposed emoji trigger: the predicate that decides whether the row of
 * emoji above the comment box is added. Its name is obfuscated on every build (Cp, Vq, nr and
 * vr on 46.2.3 through 46.9.3) but it is the class's only boolean taking the comment context;
 * the base class's default answer has the same shape and is kept out by the defining class.
 */
internal object ExposedEmojiPanelTriggerFingerprint : Fingerprint(
    definingClass = EXPOSED_EMOJI_PANEL_TRIGGER,
    returnType = "Z",
    custom = { method, _ ->
        method.parameterTypes.size == 2 &&
            method.parameterTypes[0].toString() == COMMENT_CONTEXT_SOURCE &&
            method.implementation != null
    },
)

/**
 * The typing sticker recommendation row owned by {@code TypingStickerRecommendAssem}. Its lone
 * {@code void(boolean)} method is the show/hide boundary and binds
 * {@code id/typing_sticker_recommend_view} ({@code 0x7f0a9680}).
 */
internal object TypingStickerRecommendVisibilityFingerprint : Fingerprint(
    definingClass = COMMENT_TYPING_STICKER_RECOMMEND_ASSEM,
    returnType = "V",
    parameters = listOf("Z"),
)

internal object LongPressRepostGateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/digg/VideoDiggAssem;",
    returnType = "Z",
    parameters = listOf("Landroid/view/View;"),
    strings = listOf(
        "Long press detected on digg button for aweme: ",
        "long_press_like_panel",
    ).map { string(it, StringComparisonType.CONTAINS) },
)
