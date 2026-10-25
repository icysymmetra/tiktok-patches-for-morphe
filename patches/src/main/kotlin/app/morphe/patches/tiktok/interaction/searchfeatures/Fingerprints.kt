/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.searchfeatures

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

internal object NonPersonalizedSearchGateFingerprint : Fingerprint(
    isStatic = true,
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf(string("enable_non_personalized_search")),
    filters = listOf(methodCall(parameters = listOf("I", "I", "Ljava/lang/String;", "Z"), returnType = "I")),
)

internal object NonPersonalizedSearchStateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("non_personalized_search_state_").map { string(it, StringComparisonType.CONTAINS) },
)

internal object LiveSearchGateFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/android/livesdk/livesetting/feed/LiveDrawerSearchEnableSetting;",
    name = "getValue",
    returnType = "Z",
    parameters = emptyList(),
)
