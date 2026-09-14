package app.morphe.patches.tiktok.misc.commentsort

import app.morphe.patcher.Fingerprint

internal object CommentSortOptionStyleFingerprint : Fingerprint(
    definingClass = "Lkotlin/jvm/internal/AFwS216S0000000_22;",
    name = "invoke\$200",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lkotlin/jvm/internal/AFwS216S0000000_22;"),
    strings = listOf("comment_sort_opt_style"),
)

internal object CommentSortEligibilityFingerprint : Fingerprint(
    definingClass = "LX/0nmj;",
    name = "LIZ",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;"),
)
