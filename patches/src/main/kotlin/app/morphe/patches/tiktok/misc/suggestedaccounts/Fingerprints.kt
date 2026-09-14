/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.suggestedaccounts

import app.morphe.patcher.Fingerprint

internal object ProfileHeaderRecommendComponentFingerprint : Fingerprint(
    definingClass =
        "Lcom/ss/android/ugc/profile/platform/business/header/business/recommend/assemble/" +
            "ProfileHeaderRecommendComponent;",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("recommend_user_card"),
)
