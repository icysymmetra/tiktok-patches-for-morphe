/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/0e4a6e1d
 */
package app.morphe.patches.tiktok.misc.voicecomments

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.isLazyAbRead
import app.morphe.patches.tiktok.shared.resolveLazyAbGate

/** The setting the voice comment entry points are built behind. */
private const val VOICE_COMMENT_KEY = "audio_comment_publish"

/**
 * The gate that decides whether the voice comment entry points are built.
 *
 * <p>It was `LX/0AkX;`, which is the 46.2.3 name and nothing else, and the class holds no string
 * to recognise it by: it is one of hundreds TikTok generates around a lazily read AB value. The
 * key that value reads is a name TikTok wrote, and that is the anchor.
 */
internal fun BytecodePatchContext.resolveVoiceCommentPublishGate(): MutableMethod =
    resolveLazyAbGate("Enable voice comments", VOICE_COMMENT_KEY) { method, _ ->
        method.returnType == "Z" && method.parameterTypes.none() && method.isLazyAbRead()
    }
