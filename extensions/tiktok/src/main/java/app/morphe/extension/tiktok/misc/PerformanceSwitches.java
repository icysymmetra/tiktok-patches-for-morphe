/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The switches in front of the four Performance patches: Skip the splash ad, Skip update checks,
 * Limit background traffic and Drop the animated image cache.
 *
 * <p>Each hook asks here at TikTok's own call and runs TikTok's own code when the answer is no,
 * so the patches sit in the default selection without changing anything until a switch is turned
 * on. All four start off, and a paused build answers off, since a paused setting reads its
 * default. The splash, update and push hooks run among TikTok's startup tasks, so a call before
 * the settings context exists reads the switch from the saved file instead. The keys below are
 * compile-time constants for that read, which therefore loads no Settings class.
 */
@SuppressWarnings("unused")
public final class PerformanceSwitches {
    /** {@link Settings#SKIP_SPLASH_AD}'s key, for the read before the settings context. */
    static final String SPLASH_AD_KEY = "skip_splash_ad";
    /** {@link Settings#SKIP_UPDATE_CHECKS}'s key. */
    static final String UPDATE_CHECKS_KEY = "skip_update_checks";
    /** {@link Settings#LIMIT_BACKGROUND_TRAFFIC}'s key. */
    static final String BACKGROUND_TRAFFIC_KEY = "limit_background_traffic";
    /** {@link Settings#DROP_ANIMATED_IMAGE_CACHE}'s key. */
    static final String ANIMATED_IMAGE_CACHE_KEY = "drop_animated_image_cache";

    private PerformanceSwitches() {
    }

    /** Asked by the splash ad preload tasks and the splash service's gates. */
    public static boolean skipSplashAd() {
        return Utils.getContext() != null ? Settings.SKIP_SPLASH_AD.get() : EarlySwitch.isOn(SPLASH_AD_KEY);
    }

    /** Asked by the two update check tasks. */
    public static boolean skipUpdateChecks() {
        return Utils.getContext() != null
                ? Settings.SKIP_UPDATE_CHECKS.get() : EarlySwitch.isOn(UPDATE_CHECKS_KEY);
    }

    /**
     * Asked by the buffer preload gate, and by the push setup task when the patch was applied
     * with Skip notification setup.
     */
    public static boolean limitBackgroundTraffic() {
        return Utils.getContext() != null
                ? Settings.LIMIT_BACKGROUND_TRAFFIC.get() : EarlySwitch.isOn(BACKGROUND_TRAFFIC_KEY);
    }

    /**
     * The number of frames Fresco decodes ahead of the one on screen, read just before the
     * animation backend decides whether to build its frame preparer. None with the switch on, so
     * each frame is decoded as it's drawn and kept in TikTok's own frame cache; TikTok's own
     * count otherwise.
     */
    public static int framesToPrepare(int frames) {
        return dropAnimatedImageCache() ? 0 : frames;
    }

    private static boolean dropAnimatedImageCache() {
        return Utils.getContext() != null
                ? Settings.DROP_ANIMATED_IMAGE_CACHE.get() : EarlySwitch.isOn(ANIMATED_IMAGE_CACHE_KEY);
    }
}
