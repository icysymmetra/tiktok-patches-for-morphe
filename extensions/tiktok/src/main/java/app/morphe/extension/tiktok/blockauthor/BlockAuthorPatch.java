/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import app.morphe.extension.shared.Logger;

/**
 * Entry points called from patched TikTok code.
 *
 * Everything here must stay cheap and must never throw: it runs on the feed's hot path.
 *
 * None of this is gated on a setting. The hooks only exist when a patch that needs to know
 * which video is on screen was applied, and several do: Not interested, the follow and like
 * confirmations, double tap to open comments and subtitle tracking all depend on this patch
 * for it. Gating on the block button's own switch left every one of them blind whenever
 * that button was turned off. Each consumer decides for itself whether to act.
 */
public final class BlockAuthorPatch {
    private BlockAuthorPatch() {
    }

    /**
     * Called from {@code VideoAuthorInfoVM.paramSync2StateAccept} each time a feed item
     * becomes the current video.
     *
     * @param videoItemParams a {@code com.ss.android.ugc.aweme.feed.model.VideoItemParams}
     */
    public static void setCurrentVideoParams(Object videoItemParams) {
        try {
            CurrentVideoAuthor.update(videoItemParams);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not track the current video author", ex);
        }
    }

    /**
     * Called from {@code PlayerController.onPlayProgressChange} with the id of the video
     * that is playing. This is what decides which of the bound items is on screen; the
     * bind callback above runs for items the user has not reached yet.
     *
     * @param controller the reporting native PlayerController
     * @param awemeId the playing video's id
     */
    public static void setPlayingAweme(Object controller, String awemeId) {
        try {
            app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold.onPlayerProgress(controller, awemeId);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not track the session hold player", ex);
        }
        setPlayingAweme(awemeId);
    }

    /**
     * Called from {@code PlayerController.onRenderFirstFrame}, so a pause switch that wants the
     * feed stopped can stop a start's first video before its first progress report.
     *
     * @param controller the native PlayerController drawing its first frame
     */
    public static void onFirstFrame(Object controller) {
        try {
            app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold.onFirstFrame(controller);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not stop the first frame for the session hold", ex);
        }
    }

    /** Compatibility entry for callers that only report the current video ID. */
    public static void setPlayingAweme(String awemeId) {
        try {
            CurrentVideoAuthor.onPlaying(awemeId);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not track the playing video", ex);
        }
    }
}
