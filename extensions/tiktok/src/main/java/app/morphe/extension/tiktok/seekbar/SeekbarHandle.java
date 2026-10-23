/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Bigger progress bar handle (#90): a larger handle and a slightly thicker line at rest.
 *
 * <p>TikTok styles its bar in one method that takes the style it wants: 0 at rest, 1 paused,
 * 2 for a special layout, 100 to 102 while dragging. For each it hands the seek bar's own
 * setters a line height and a handle size, in pixels: at rest a 2dp line and a 4dp handle,
 * paused 4dp and 8dp, dragging 3dp and 13dp. The patch passes those values through here on
 * their way in, so the bar draws itself bigger and nothing replaces it.
 *
 * <p>Only rest and pause change, and only upward, so the drag style keeps TikTok's look and the
 * handle never shrinks under a finger. Turned off, or with Hushfeed paused, every value goes
 * through as TikTok wrote it; the next restyle (the next video, a pause or a drag) puts TikTok's
 * own sizes back.
 */
@SuppressWarnings("unused")
public final class SeekbarHandle {
    static final int STYLE_REST = 0;
    static final int STYLE_PAUSED = 1;
    /** Handle width and height. TikTok's own drag handle is 13dp. */
    static final float HANDLE_DP = 12f;
    /** Half the handle, so it draws round. */
    static final float HANDLE_CORNER_DP = 6f;
    /** The line at rest. TikTok's drag line is 3dp, paused 4dp. */
    static final float LINE_DP = 3f;

    /** Set at the top of TikTok's style method and read by the setters below it, all on the UI thread. */
    private static boolean restyling;
    private static float density = 1f;

    private SeekbarHandle() {
    }

    public static boolean isEnabled() {
        return Settings.SEEKBAR_BIG_HANDLE.get();
    }

    /** The entry of TikTok's style method, with the style it is about to apply. */
    public static void beginStyle(@Nullable LinearLayout bar, int style) {
        restyling = false;
        if (bar == null || (style != STYLE_REST && style != STYLE_PAUSED) || !isEnabled()) return;
        try {
            density = bar.getResources().getDisplayMetrics().density;
            restyling = true;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Bigger progress bar handle: no display metrics", failure);
        }
    }

    /** The handle's width or height on its way to the seek bar. */
    @Nullable
    public static Float handleSide(@Nullable Float side) {
        return atLeast(side, HANDLE_DP);
    }

    /** The handle's corner radius. */
    @Nullable
    public static Float handleCorner(@Nullable Float corner) {
        return atLeast(corner, HANDLE_CORNER_DP);
    }

    /** The line's height, for the line itself and for the chapter marks drawn on it. */
    @Nullable
    public static Float lineHeight(@Nullable Float height) {
        return atLeast(height, LINE_DP);
    }

    @Nullable
    static Float atLeast(@Nullable Float value, float dp) {
        if (!restyling || value == null) return value;
        float least = dp * density;
        return value < least ? Float.valueOf(least) : value;
    }
}
