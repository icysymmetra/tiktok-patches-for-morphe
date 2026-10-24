/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.interaction;

import app.morphe.extension.tiktok.settings.Settings;

public final class ResumeVideoAfterScrollPatch {
    private ResumeVideoAfterScrollPatch() {
    }

    public static boolean shouldResumeVideoAfterScroll() {
        return Settings.RESUME_VIDEO_AFTER_SCROLL.get();
    }

    /**
     * TikTok stores a position for every feed it plays and then refuses to hand one back unless
     * the feed's event type is in its own allow list, which on 46.2.3 covers For You and the
     * profile and history pages but not Following or Friends. With the setting on, every feed
     * that has a stored position gets it.
     *
     * @param appAllowsThisFeed what TikTok's own event type check answered.
     */
    public static boolean allowResumeInThisFeed(boolean appAllowsThisFeed) {
        return appAllowsThisFeed || Settings.RESUME_VIDEO_AFTER_SCROLL.get();
    }

    public static boolean shouldClearCompletedProgress(long position, long duration) {
        return Settings.RESUME_VIDEO_AFTER_SCROLL.get() && duration > 0 && position >= duration;
    }
}
