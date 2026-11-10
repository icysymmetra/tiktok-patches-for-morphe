/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Whether a new search may join the search history TikTok saves on the phone.
 *
 * <p>Asked at the entry of TikTok's two history writers: the history manager's record method,
 * which every search path reaches, and ManualSearchPvStore's per-query search log. Neither is
 * reached for reading, deleting or clearing, so history already saved keeps showing and can
 * still be removed. Read on every call, so a change applies to the next search, and while
 * Hushfeed is paused the switch answers off and TikTok records as it ships.
 */
@SuppressWarnings("unused")
public final class SearchHistoryRecording {
    private SearchHistoryRecording() {
    }

    public static boolean shouldSkip() {
        boolean skip = SettingsStatus.searchHistoryEnabled && Settings.STOP_SEARCH_HISTORY.get();
        if (skip) Logger.printDebug(() -> "Search history: a new entry was not saved");
        return skip;
    }
}
