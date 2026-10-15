/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.voicecomments

import app.morphe.patcher.Fingerprint

/**
 * Comment keyboard entry check deciding whether the voice-comment entry may show. It consults the
 * "comment_audio_publish_entry_forbidden" setting and then the static publish gate
 * (X.0AkX.LIZ in TikTok 46.2.3, X.0Hvt.LIZ in 47.1.4), which reads the "audio_comment_publish" AB value.
 */
internal object VoiceCommentEntryAllowedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/comment/model/CommentContextSource;"),
    strings = listOf("comment_audio_publish_entry_forbidden"),
)
