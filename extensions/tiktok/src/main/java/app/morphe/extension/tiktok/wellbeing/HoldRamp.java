/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.content.ContentResolver;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;

/**
 * A cover that comes in over the last three quarters of a minute of the budget, so the hold is
 * something the feed arrives at rather than something that lands on it.
 *
 * <p>A field study of short-form feeds (IMWUT 2026, N=104, seven days, within-subject) measured
 * people leaving 56 seconds after a gradual visual occlusion against 7 seconds after a pop-up,
 * and the gradual version did not wear off across the week the way the pop-up did. It also
 * found the abrupt version worked better for the most impulsive readers, which is why this is a
 * switch beside the hold rather than a replacement for it.
 *
 * <p>Off by default. It only has anything to follow when a minutes budget is set: a budget
 * counted in videos has no "how long is left" to draw.
 */
public final class HoldRamp {
    /** How much of the budget the cover takes to arrive. */
    static final long RAMP_MS = 45_000L;

    /**
     * Where the ramp finishes. The hold's own panel is {@code SettingsUi.OVERLAY_SCRIM_SOLID},
     * so the two meet without a step: the last frame of the ramp and the first frame of the hold
     * are the same shade. {@code HoldRampTest} pins the two together, because they are now a
     * number in two files and a ramp that overshoots its own panel flashes at the handoff.
     */
    static final int FULL_ALPHA = 238;

    /**
     * How often the budget is asked, in milliseconds.
     *
     * <p>The player's progress callback fires several times a second and this runs off it. At
     * a quarter of a second the cover still moves smoothly, in about 180 steps across the
     * ramp, and the other nine callbacks in that second cost a setting read.
     */
    private static final long ASK_EVERY_MS = 250L;

    private static WeakReference<View> coverReference = new WeakReference<>(null);
    private static long lastAskedAt;

    private HoldRamp() {
    }

    /**
     * How dark the cover is with this much of the budget left, from 0 to {@link #FULL_ALPHA}.
     *
     * <p>Squared rather than straight so nine tenths of the cover arrives in the last thirty
     * seconds, which is the shape the study measured. A straight line spends the first fifteen
     * seconds visibly greying a feed nobody has been warned about yet.
     *
     * @param remainingMs how much budget is left, or a negative number when nothing is being
     *                    counted in time and there is therefore nothing to ramp
     */
    static int alphaFor(long remainingMs) {
        if (remainingMs < 0) return 0;
        if (remainingMs >= RAMP_MS) return 0;
        float travelled = 1f - (float) remainingMs / (float) RAMP_MS;
        return Math.round(FULL_ALPHA * travelled * travelled);
    }

    /**
     * Whether the reader has asked the system for no animation.
     *
     * <p>A zero animator duration scale snaps every duration-based animation to its end frame,
     * so a ramp built on an Animator would appear as an instant black screen. This one is drawn
     * from the budget rather than from an Animator, which means the scale cannot break it, but
     * a reader who has turned animation off has asked not to be shown a slow fade at all. They
     * get the plain hold, unchanged.
     */
    static boolean animationIsOff(Activity activity) {
        if (activity == null) return false;
        try {
            ContentResolver resolver = activity.getContentResolver();
            if (resolver == null) return false;
            return android.provider.Settings.Global.getFloat(resolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable unreadable) {
            // A setting that cannot be read is not a request for anything.
            return false;
        }
    }

    /**
     * Puts the cover where it belongs for the moment.
     *
     * <p>Called from the player's progress callback, which fires several times a second, so the
     * switched-off case has to answer before anything else happens.
     */
    public static void sync() {
        if (!Settings.SESSION_BUDGET_RAMP.get()) {
            if (coverReference.get() != null) Utils.runOnMainThread(HoldRamp::detach);
            return;
        }
        // The ramp is the approach to the hold. With no hold to arrive at there is nothing to
        // lead into, and the row that turns the hold off says in as many words that it leaves
        // the feed alone, so a cover here would be one screen's promise breaking another's.
        if (!aHoldWillFollow()) {
            if (coverReference.get() != null) Utils.runOnMainThread(HoldRamp::detach);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        // The clause that used to be here, "and there is no cover yet", turned the throttle
        // off for the whole ramp: the cover is up for all forty-five seconds of it, which is
        // exactly when the callbacks are arriving several times a second.
        if (now - lastAskedAt < ASK_EVERY_MS) return;
        lastAskedAt = now;
        // Every sibling on this callback hops to the main thread before it touches a view.
        // addView from the player's own thread throws, and the catch below would swallow it
        // into a log line several times a second while the ramp never appeared.
        Utils.runOnMainThread(HoldRamp::syncOnMainThread);
    }

    /** Whether a hold starts when the budget runs out, which is what the ramp leads into. */
    private static boolean aHoldWillFollow() {
        return Settings.SESSION_BUDGET_LOCK.get()
                || Settings.SESSION_BUDGET_LOCK_MINUTES.get() > 0;
    }

    private static void syncOnMainThread() {
        try {
            // The hold covers the feed itself and swallows the touches; a ramp under it would
            // be two covers doing one job.
            if (SessionBudget.isLocked()) {
                detach();
                return;
            }
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                detach();
                return;
            }
            if (!FeedVisibility.isOnFeed(activity) || animationIsOff(activity)) {
                detach();
                return;
            }
            long remaining = SessionBudget.budgetRemainingMs();
            // Nothing left to ramp towards. The hold is either about to go up or has already
            // run its course, and a cover that stays past the end of the budget is a feed
            // nobody can see with nothing on screen to say why.
            if (remaining <= 0) {
                detach();
                return;
            }
            int alpha = alphaFor(remaining);
            if (alpha <= 0) {
                detach();
                return;
            }
            View cover = attach(activity);
            if (cover != null) {
                cover.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
                placeAboveTabs(activity, cover);
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not update the budget ramp", error);
        }
    }

    /**
     * The progress callback that draws the cover stops with the player, and leaving the feed for
     * Profile or Inbox stops the player, so a nearly black cover stayed over those tabs. A tab
     * change or a screen over the feed lays the window out, and each pass asks again.
     */
    private static final GlobalLayoutHook LAYOUT = new GlobalLayoutHook();

    private static void recheck() {
        View cover = coverReference.get();
        if (cover == null) {
            LAYOUT.detach();
            return;
        }
        Activity activity = Utils.getActivity();
        if (activity == null || SessionBudget.isLocked() || !FeedVisibility.isOnFeed(activity)) {
            detach();
            return;
        }
        placeAboveTabs(activity, cover);
    }

    /**
     * The cover stops at TikTok's tab row, so the way to Profile or Inbox stays readable while
     * the feed fades, the way the hold's own panel leaves it.
     */
    private static void placeAboveTabs(Activity activity, View cover) {
        if (!(cover.getParent() instanceof ViewGroup)
                || !(cover.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        int bottom = SessionLockOverlay.navigationHeight(activity, (ViewGroup) cover.getParent());
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) cover.getLayoutParams();
        if (params.bottomMargin == bottom) return;
        params.bottomMargin = bottom;
        cover.setLayoutParams(params);
    }

    private static View attach(Activity activity) {
        View existing = coverReference.get();
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        if (existing != null && existing.getParent() == root) {
            LAYOUT.install(root, HoldRamp::recheck);
            return existing;
        }
        // The activity moved. Take the old cover off the screen it is on rather than dropping
        // the only handle to it and building a second one.
        detach();

        View cover = new View(activity);
        // Nothing about this is interactive. A plain View with no click listener refuses the
        // touch, so the feed underneath scrolls, likes and comments exactly as before.
        cover.setClickable(false);
        cover.setFocusable(false);
        // And a screen reader is told nothing until the hold itself, which has its own
        // announcement. A cover that says something once a second would be unusable.
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        coverReference = new WeakReference<>(cover);
        LAYOUT.install(root, HoldRamp::recheck);
        return cover;
    }

    private static void detach() {
        LAYOUT.detach();
        View cover = coverReference.get();
        coverReference = new WeakReference<>(null);
        if (cover == null) return;
        ViewGroup parent = cover.getParent() instanceof ViewGroup
                ? (ViewGroup) cover.getParent()
                : null;
        if (parent != null) parent.removeView(cover);
    }

    /**
     * One pass with the throttle stood aside, so a test can drive the ramp step by step rather
     * than pretending a quarter of a second goes by between two lines of a fixture.
     */
    static void syncNowForTests() {
        // Far enough back that the throttle lets this pass. Zero would not: the clock starts
        // near zero under a test runner, so "nothing has been asked yet" and "asked a moment
        // ago" are the same number there.
        lastAskedAt = android.os.SystemClock.uptimeMillis() - ASK_EVERY_MS;
        sync();
    }

    /** Drops the cover and forgets it, between tests. */
    static void resetForTests() {
        detach();
        lastAskedAt = 0;
    }

    /** The cover on screen, or null when there is none. Test seam. */
    static View coverForTests() {
        return coverReference.get();
    }
}
