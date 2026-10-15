package app.morphe.patches.tiktok.misc.commentsort

import app.morphe.patcher.Fingerprint

/**
 * Function0 lambda that reads the "comment_sort_opt_style" AB value.
 * TikTok 46.2.3 compiled it into a shared synthetic class (AFwS216S0000000_22.invoke$200);
 * TikTok 47.1.4 emits a dedicated class with a plain invoke().
 */
internal object CommentSortOptionStyleFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    strings = listOf("comment_sort_opt_style"),
)
