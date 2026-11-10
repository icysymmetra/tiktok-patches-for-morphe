/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;

/**
 * Brings the reader back to the settings page they restarted TikTok from.
 *
 * <p>The restart row and the banner's Restart relaunch TikTok, which landed on the feed: whoever
 * flipped three switches on Privacy had to find their way back to Privacy to carry on. Before the
 * relaunch the page goes into Hushfeed's preferences with the time, and the next start opens that
 * page again when it comes within 90 seconds. The marker is removed before the page opens, so a
 * start that dies while opening it can't open it again, and one left by a relaunch that never
 * happened is dropped once it's stale.
 */
public final class SettingsReturn {
    public static final String PAGE_KEY = "hushfeed_return_page";
    public static final String AT_KEY = "hushfeed_return_at";
    /** How long after the relaunch the page still opens. */
    public static final long WINDOW_MS = 90_000L;

    /** What opens the page; the real one starts the settings screen. */
    public interface Opener {
        void open(String page);
    }

    private SettingsReturn() {
    }

    /**
     * Records {@code page} for the next start. Committed rather than applied: the relaunch ends the
     * process straight after, and an apply still on its way to disk is lost with it.
     */
    @SuppressLint("ApplySharedPref")
    public static boolean remember(SharedPreferences preferences, String page, long now) {
        if (page == null || page.isEmpty()) return false;
        return preferences.edit().putString(PAGE_KEY, page).putLong(AT_KEY, now).commit();
    }

    /**
     * Opens the page a restart left, once. The marker is removed first, and the page opens only
     * when that removal reached the disk: a page that came back on every start would be a loop the
     * reader couldn't leave. Returns the page it opened, or null.
     */
    @SuppressLint("ApplySharedPref")
    public static String consume(SharedPreferences preferences, long now, Opener opener) {
        if (!preferences.contains(PAGE_KEY) && !preferences.contains(AT_KEY)) return null;
        String page = null;
        long at = -1;
        boolean readable = true;
        try {
            page = preferences.getString(PAGE_KEY, null);
            at = preferences.getLong(AT_KEY, -1);
        } catch (ClassCastException unreadable) {
            readable = false;
        }
        if (!preferences.edit().remove(PAGE_KEY).remove(AT_KEY).commit()) return null;
        if (!readable || page == null || page.isEmpty() || at < 0) return null;
        // The clock counts from boot, so a marker from before the phone restarted reads as ahead.
        long age = now - at;
        if (age < 0 || age > WINDOW_MS) return null;
        opener.open(page);
        return page;
    }

    /** Before a relaunch: the page open now, on the clock that outlives the process. */
    public static void rememberBeforeRestart(String page) {
        try {
            remember(Setting.preferences.preferences, page, SystemClock.elapsedRealtime());
        } catch (Throwable error) {
            Logger.printException(() -> "Couldn't note the settings page for the restart", error);
        }
    }

    /**
     * On each start of the main activity. The page opens once the activity's start has run, through
     * the same screen the Home tab's long press opens, never from inside onCreate, where nothing may
     * touch the window yet.
     */
    public static void onMainActivityCreated() {
        try {
            consume(Setting.preferences.preferences, SystemClock.elapsedRealtime(),
                    page -> new Handler(Looper.getMainLooper()).post(
                            () -> TikTokActivityHook.openSettingsPage(page)));
        } catch (Throwable error) {
            Logger.printException(() -> "Couldn't reopen the settings page after the restart", error);
        }
    }
}
