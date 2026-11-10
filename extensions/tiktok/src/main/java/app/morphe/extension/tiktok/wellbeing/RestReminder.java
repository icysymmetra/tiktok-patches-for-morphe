/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.os.SystemClock;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Leaves TikTok when TikTok's own screen-time reminder comes up, for the switch under
 * Screen time. The reminder is the full-screen card the feed panel builds once the daily
 * limit under TikTok's Settings and privacy &gt; Time and well-being is spent; its slot is
 * only created to be shown, so "the view exists" is "the reminder is up".
 */
public final class RestReminder {
    /**
     * Coming straight back to the app keeps the reminder on screen instead of leaving again,
     * so the limit and this switch stay reachable while it is tripped; a later show leaves
     * again. Without this, a reminder rebuilt on every return would lock TikTok shut.
     */
    static final long LEAVE_AGAIN_AFTER_MS = 5 * 60_000L;

    /** Boxed: elapsedRealtime starts near zero after a boot, which must not read as recent. */
    private static Long lastLeftAtElapsed;

    private RestReminder() {}

    /** Called by the patch as TikTok builds the reminder into the feed panel. */
    public static void reminderShown() {
        if (!Settings.LEAVE_ON_REST_REMINDER.get()) return;
        Activity owner = Utils.getActivity();
        if (owner == null) return;
        // Posted: this call sits inside the reminder's own view build, and the gate runs in
        // the post too, so a slot rebuilt twice in one frame still leaves only once.
        Utils.runOnMainThread(() -> leaveNow(owner));
    }

    private static void leaveNow(Activity activity) {
        if (!Settings.LEAVE_ON_REST_REMINDER.get() || Utils.getActivity() != activity
                || activity.isFinishing() || activity.isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        if (lastLeftAtElapsed != null && now - lastLeftAtElapsed < LEAVE_AGAIN_AFTER_MS) return;
        // False means the task cannot go back (not the root task, say); leave the guard
        // unset so the next show tries again rather than swallowing the feature for 5 minutes.
        if (activity.moveTaskToBack(true)) lastLeftAtElapsed = now;
    }

    static void resetForTests() {
        lastLeftAtElapsed = null;
    }
}
