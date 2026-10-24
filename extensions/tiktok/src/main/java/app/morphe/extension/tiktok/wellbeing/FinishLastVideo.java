/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Lets the video on screen play to its end when the budget runs out, and keeps the feed from
 * moving on meanwhile.
 *
 * <p>Without it the hold lands the moment the budget is spent, which is halfway through a video
 * for a time budget and on the very video that reached the count for a video budget, so a budget
 * of five let four be watched. With it the hold waits for the video on screen to finish once,
 * and the feed's pager turns swipes down until then the way TikTok's own disable-scroll switch
 * does: the pager neither intercepts nor handles the gesture, and everything inside it (a tap,
 * a double tap, the seek bar, comments) still gets its touches.
 *
 * <p>The wait ends at the first of: the video completing, a different video coming up by any
 * route (TikTok's auto scroll, an accessibility scroll action, a detail page's own swipe), or
 * {@link #LONGEST_MS}. A missed completion therefore costs at most that long, and nothing that
 * moves the feed gets the reader past the budget: the next video arrives under the hold.
 *
 * <p>Only the main activity's pager is held. A video opened from messages, a profile or search
 * plays in TikTok's detail activity, which keeps its swipe; opening one is a video change like any
 * other, so it ends the wait and the hold treats that page the way it always has.
 */
public final class FinishLastVideo {
    /** The most the hold waits, for a long video or a completion that never arrives. */
    public static final long LONGEST_MS = 3 * 60_000L;

    private static final String MAIN_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity";

    /** The video being finished, or null. Read on every touch the pager sees, so volatile only. */
    private static volatile String finishing;
    private static volatile long startedAt;

    private FinishLastVideo() {
    }

    /**
     * The budget has just run out on {@code awemeId}. Starts the wait when the switch is on and
     * a hold is about to cover the feed; with no hold there is nothing to wait for.
     *
     * @return whether the hold now waits for this video
     */
    public static boolean begin(String awemeId) {
        if (awemeId == null || awemeId.isEmpty() || !Settings.SESSION_BUDGET_FINISH_VIDEO.get()
                || !SessionBudget.isLocked() || fadedOut()) {
            return false;
        }
        startedAt = SessionBudget.now();
        finishing = awemeId;
        return true;
    }

    /**
     * Whether the fade already dimmed the video out on the way to the hold. It follows a time
     * budget, so a time budget that ran out with it on has nothing left on screen to finish;
     * lifting the fade to show the rest of the video would undo the arrival it was built for.
     */
    private static boolean fadedOut() {
        int minuteBudget = Settings.SESSION_BUDGET_MINUTES.get();
        return Settings.SESSION_BUDGET_RAMP.get() && minuteBudget > 0
                && SessionBudget.watchedMs() >= minuteBudget * 60_000L;
    }

    /** Whether the hold is waiting for the last video. */
    public static boolean pending() {
        if (finishing == null) return false;
        long elapsed = SessionBudget.now() - startedAt;
        // A hold that ended (Start today over, a raised budget), the switch turned off, or a
        // clock that went backwards all end the wait: each leaves nothing sensible to wait for.
        if (!Settings.SESSION_BUDGET_FINISH_VIDEO.get() || !SessionBudget.isLocked()
                || elapsed < 0 || elapsed >= LONGEST_MS) {
            finishing = null;
            return false;
        }
        return true;
    }

    /** The video being finished, or null when nothing is. */
    static String finishingId() {
        return pending() ? finishing : null;
    }

    /** Called from {@code PlayerController.onPlayCompleted} with the video that finished. */
    public static void onPlayCompleted(String awemeId) {
        String id = finishing;
        if (id == null || !id.equals(awemeId)) return;
        end();
    }

    /**
     * A different video is current. The one being finished is gone, so the hold comes now. A
     * null is the player being ahead of the bind for a moment, not a video, and the bind that
     * follows names the video.
     */
    public static void onVideoChanged(String awemeId) {
        String id = finishing;
        if (id == null || awemeId == null || id.equals(awemeId)) return;
        end();
    }

    private static void end() {
        finishing = null;
        // The completion arrives on the player's thread and the panel is a view.
        Utils.runOnMainThread(SessionLockOverlay::sync);
    }

    /**
     * Whether the feed's pager should turn this touch down. Called at the start of the pager's
     * {@code onInterceptTouchEvent} and {@code onTouchEvent}, where a true answer returns false
     * from both, which is the answer TikTok's own disable-scroll switch gives there.
     */
    public static boolean holdsSwipe(View pager) {
        // Every touch of every feed pager reaches this, so the idle case is a couple of reads.
        if (pager == null) return false;
        boolean waiting = finishing != null && pending();
        boolean locked = !waiting && FeedLock.isOn();
        // A shared video opened alone, while it is the one playing, and only in the pager it
        // came up in. Another top tab or the Friends tab is a pager of its own, and one that
        // opens on something that isn't a video keeps its swipe.
        boolean alone = !waiting && !locked && FeedLock.linkVideoAlone();
        if (!waiting && !locked && !alone) return false;
        Activity activity = activityOf(pager.getContext());
        if (activity == null || !MAIN_ACTIVITY.equals(activity.getClass().getName())) return false;
        if (alone) return FeedLock.holdsAloneSwipe(pager);
        // The feed lock turns the same swipe down, on the recommendation feed only.
        return waiting ? FeedVisibility.isOnFeed(activity) : FeedLock.holdsSwipe(activity);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    static void resetForTests() {
        finishing = null;
        startedAt = 0;
    }
}
