package app.morphe.patches.tiktok.misc.foldable

import app.morphe.patcher.Fingerprint

// Static helper class X.0oq9 in TikTok 46.2.3, X.08AB in 47.1.4; matched by log strings instead of the name.
internal object CommentSplitLiveCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/app/Activity;", "Landroid/content/res/Configuration;"),
    strings = listOf("isOptCommentSplit"),
)

internal object CommentSplitContainerCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("isOptSplitContainer"),
)
