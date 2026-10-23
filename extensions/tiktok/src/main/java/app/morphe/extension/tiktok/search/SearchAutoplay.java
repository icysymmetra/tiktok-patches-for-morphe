/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.search;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/** Whether videos in search results start playing by themselves. */
@SuppressWarnings("unused")
public final class SearchAutoplay {
    private SearchAutoplay() {
    }

    /**
     * True while the search list's autoplay check should do nothing. A video opened from the
     * results plays in its own player, which this doesn't reach. Paused, the switch answers off.
     */
    public static boolean shouldSkip() {
        return SettingsStatus.searchAutoplayEnabled && Settings.STOP_SEARCH_AUTOPLAY.get();
    }
}
