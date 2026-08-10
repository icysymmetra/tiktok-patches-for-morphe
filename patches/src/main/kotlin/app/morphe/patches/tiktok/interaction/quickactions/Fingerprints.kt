/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.Fingerprint

internal object QuickCommentReactionGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("I"),
    custom = { method, classDef ->
        classDef.type == "LX/0BIZ;" &&
            method.name == "LIZ"
    },
)

internal object LongPressQuickShareGateFingerprint : Fingerprint(
    returnType = "I",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type == "LX/0BJV;" &&
            method.name == "LIZ"
    },
)

internal object LongPressRepostGateFingerprint : Fingerprint(
    returnType = "I",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type == "LX/0oDq;" &&
            method.name == "LIZ"
    },
)
