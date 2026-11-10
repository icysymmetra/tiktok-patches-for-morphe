/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The switch in front of the gates that take TikTok's translation away from some accounts (#143).
 *
 * <p>The Settings and privacy > Language page draws its translation group (the Translation
 * heading, Always translate, Translate into and Don't translate) only when two App AB values allow
 * it: {@code feed_translation_reverse} must not be 1 and {@code pure_feed_reverse} must be 0. The
 * server sets those per account, so a logged-out phone can get the rows and the same phone loses
 * them once an account that is in either experiment signs in. Nothing Hushfeed hooks decides it.
 * The patch sends both answers through {@link #hidden} as the page reads them.
 *
 * <p>{@code feed_translation_reverse} at 1 also takes translation off the videos: See translation
 * under a description, translated captions, photo titles and text stickers all check it first and
 * give up. TikTok reads it once per process and keeps the answer, so the patch sends that one read
 * through {@link #feedReverse} and the switch needs a restart for the videos. {@code
 * pure_feed_reverse} gates much more than translation and stays TikTok's everywhere but the
 * Language page. With the switch off, and while Hushfeed is paused, TikTok's answers stand.
 */
@SuppressWarnings("unused")
public final class LanguagePageTranslations {
    private static final String HOOK_FAMILY = "language page translations";
    private static final String FEED_HOOK_FAMILY = "video translations";
    /** The {@code feed_translation_reverse} value that takes translation off the videos. */
    private static final int REVERSED = 1;

    private LanguagePageTranslations() {
    }

    /** False while the switch is on; TikTok's own answer otherwise. Called with each gate's answer. */
    public static boolean hidden(boolean tikToks) {
        HookStatus.bound(HOOK_FAMILY, tikToks ? "TikTok hid the rows" : "TikTok showed the rows");
        if (!tikToks) return false;
        // The page opens long after the application context exists, but loading Settings without
        // one throws inside TikTok, so this never assumes it.
        if (Utils.getContext() == null || !Settings.SHOW_TRANSLATION_SETTINGS.get()) return true;
        Logger.printDebug(() -> "[LanguagePageTranslations] showed the translation rows TikTok hid");
        return false;
    }

    /**
     * TikTok's {@code feed_translation_reverse}, or 0 in place of a 1 while the switch is on.
     * Any other value already leaves translation on the videos and passes through.
     */
    public static int feedReverse(int tikToks) {
        HookStatus.bound(FEED_HOOK_FAMILY, tikToks == REVERSED
                ? "TikTok took translation off videos" : "TikTok left translation on videos");
        if (tikToks != REVERSED) return tikToks;
        if (Utils.getContext() == null || !Settings.SHOW_TRANSLATION_SETTINGS.get()) return tikToks;
        Logger.printDebug(() -> "[LanguagePageTranslations] kept translation on videos TikTok took it off");
        return 0;
    }
}
