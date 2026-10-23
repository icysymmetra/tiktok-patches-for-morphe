/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.cleardisplay;

import android.view.View;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.HushfeedPause;

/**
 * The Following stories bubble at the top of the feed, brought back after clear display.
 *
 * <p>TikTok's bubble hides itself when clear display starts and, as clear display ends, only
 * puts back the expanded row it collapsed. The bubble stays gone until TikTok rebuilds the feed
 * (seen with Hushfeed paused on 47.1.3, 2026-10-05). The patch tells this class what the bubble
 * looked like just before TikTok hid it, and asks it at the end whether TikTok should show it
 * again through its own show call.
 */
@SuppressWarnings("unused")
public final class FollowingStoriesBubble {
    /** Each bubble TikTok hid for clear display, and whether it was showing then. */
    private static final Map<View, Boolean> SHOWING_AT_CLEAR = new WeakHashMap<>();

    private FollowingStoriesBubble() {
    }

    /** TikTok is about to hide {@code bubble} because clear display started. */
    public static void hiding(View bubble) {
        if (bubble == null) return;
        // A second start while the bubble is hidden must not erase that it was showing. TikTok
        // 47.1.4 skips its hide once clear display is on, but Remember clear display posts a start
        // for every new video, and a build without that check would land here again.
        if (Boolean.TRUE.equals(SHOWING_AT_CLEAR.get(bubble))) return;
        SHOWING_AT_CLEAR.put(bubble, bubble.getVisibility() == View.VISIBLE);
    }

    /**
     * Whether TikTok should show {@code bubble} again now that clear display ended: it was
     * showing when clear display hid it, it's still hidden, and TikTok's list behind it still
     * holds someone. Paused, TikTok keeps it the way TikTok leaves it.
     */
    public static boolean showAgain(View bubble, List<?> items) {
        try {
            Boolean wasShowing = bubble == null ? null : SHOWING_AT_CLEAR.remove(bubble);
            if (wasShowing == null || !wasShowing || HushfeedPause.isPaused()) return false;
            if (bubble.getVisibility() == View.VISIBLE) return false;
            boolean show = items != null && !items.isEmpty();
            if (show) Logger.printDebug(() -> "[FollowingStoriesBubble] shown again after clear display");
            return show;
        } catch (Exception ex) {
            Logger.printException(() -> "FollowingStoriesBubble.showAgain failed", ex);
            return false;
        }
    }

    static void resetForTests() {
        SHOWING_AT_CLEAR.clear();
    }
}
