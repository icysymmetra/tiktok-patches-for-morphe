package app.morphe.patches.tiktok.misc.foldable

import app.morphe.patcher.Fingerprint

// Real dex class is X.0oq9; jadx renamed it to C1488600oq9 for decompilation only.
internal object CommentSplitLiveCheckFingerprint : Fingerprint(
    definingClass = "LX/0oq9;",
    name = "LIZ",
    returnType = "Z",
    parameters = listOf("Landroid/app/Activity;", "Landroid/content/res/Configuration;"),
    strings = listOf("isOptCommentSplit"),
)

internal object CommentSplitContainerCheckFingerprint : Fingerprint(
    definingClass = "LX/0oq9;",
    name = "LIZJ",
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("isOptSplitContainer"),
)
