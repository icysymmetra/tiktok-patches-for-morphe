/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 */
package app.morphe.extension.tiktok.comment;

import com.ss.android.ugc.aweme.feed.model.Aweme;

/** Ownership check at the native comment response processor, before it changes sheet state. */
public final class CommentResponseGuard {
    private CommentResponseGuard() { }

    /**
     * Cancellation cannot recall a response already posted to the native handler. The processor
     * and its deferred publisher both keep the request's aid, independently of the processor's
     * current Aweme. Reject only a known mismatch, before either path touches the new sheet.
     * No response items, pagination flags, or current-request loading state are changed here.
     */
    public static boolean shouldDiscard(Aweme currentVideo, String requestedAid) {
        if (currentVideo == null || requestedAid == null || requestedAid.isEmpty()) return false;
        String currentAid = currentVideo.getAid();
        return currentAid != null && !currentAid.isEmpty() && !currentAid.equals(requestedAid);
    }
}
