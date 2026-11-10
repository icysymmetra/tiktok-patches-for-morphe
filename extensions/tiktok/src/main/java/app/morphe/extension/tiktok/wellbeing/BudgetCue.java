/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;

/**
 * A small label on the feed saying what is left of today's budget.
 *
 * <p>Both budgets are otherwise only visible at their own edges: a reminder partway through, a
 * notice when the day is spent, a hold if one is set. Between those the reader is guessing, and
 * the research this whole area is built on is about the gap between intended and actual use
 * rather than about the moment the intention is already broken.
 *
 * <p>Off by default, and quiet on purpose. It is not a live region and nothing about it is
 * announced: the value moves at most once a minute, and a label that spoke every time it
 * changed would be a timer reading itself out over the audio. A reader who wants it lands on it
 * in traversal and hears the value then.
 */
public final class BudgetCue {
    /**
     * How often the budget is asked, in milliseconds.
     *
     * <p>The player's progress callback fires several times a second and this runs off it. The
     * text only moves at a whole minute or a whole video, so asking once a second is already far
     * more often than the answer can change; the gate below stops the rest.
     */
    private static final long ASK_EVERY_MS = 1_000L;

    /** Inset from the top and start edges, clear of TikTok's own feed tabs and search. */
    private static final int MARGIN_DP = 12;

    /** Fallback inset for the status bar when the platform will not say how tall it is. */
    private static final int STATUS_BAR_FALLBACK_DP = 24;

    private static WeakReference<TextView> cueReference = new WeakReference<>(null);
    private static long lastAskedAt;

    /**
     * What the label last said. The gate that keeps this quiet: setting the same text again
     * still posts a content-changed event, so a reader using TalkBack would hear the cue
     * interrupt once a second while nothing about it had moved.
     */
    private static String shownText;

    private BudgetCue() {
    }

    /**
     * The label for the budget as it stands, or null when there is nothing to say.
     *
     * <p>With both budgets set, the one closer to its limit is the one that will actually stop
     * the feed, so that is the one shown. With neither, or with the day already spent, there is
     * no number left to report: the notice and the hold own that moment and say so themselves.
     *
     * @param videosSeen  videos counted today
     * @param watchedMs   milliseconds watched today
     * @param videoBudget the video budget, or zero when none is set
     * @param minuteBudget the minute budget, or zero when none is set
     */
    static String labelFor(int videosSeen, long watchedMs, int videoBudget, int minuteBudget) {
        boolean countingVideos = videoBudget > 0;
        boolean countingMinutes = minuteBudget > 0;
        if (!countingVideos && !countingMinutes) return null;

        int videosLeft = countingVideos ? Math.max(0, videoBudget - videosSeen) : 0;
        long minuteBudgetMs = minuteBudget * 60_000L;
        long remainingMs = countingMinutes ? Math.max(0, minuteBudgetMs - watchedMs) : 0;
        // Rounded up, so the last part minute reads as one rather than as none left on a feed
        // that is still running.
        long minutesLeft = (remainingMs + 59_999L) / 60_000L;

        if (countingVideos && videosLeft <= 0) return null;
        if (countingMinutes && remainingMs <= 0) return null;

        boolean showVideos = countingVideos;
        if (countingVideos && countingMinutes) {
            // Whichever has less of itself left. Compared as fractions because the two are
            // counted in different units and a raw number would make 3 videos look further
            // along than 4 minutes.
            float videoFraction = (float) videosLeft / (float) videoBudget;
            float minuteFraction = (float) remainingMs / (float) minuteBudgetMs;
            showVideos = videoFraction <= minuteFraction;
        }

        if (showVideos) {
            return L10n.quantity(Utils.getContext(), videosLeft, "1 video left", "%1$s videos left");
        }
        return L10n.quantity(Utils.getContext(), minutesLeft, "1 min left", "%1$s min left");
    }

    /**
     * Puts the label where it belongs for the moment.
     *
     * <p>Called from the player's progress callback, which fires several times a second, so the
     * switched-off case has to answer before anything else happens.
     */
    public static void sync() {
        if (!Settings.SESSION_BUDGET_CUE.get()) {
            if (cueReference.get() != null) Utils.runOnMainThread(BudgetCue::detach);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastAskedAt < ASK_EVERY_MS) return;
        lastAskedAt = now;
        // Every sibling on this callback hops to the main thread before it touches a view.
        // addView from the player's own thread throws.
        Utils.runOnMainThread(BudgetCue::syncOnMainThread);
    }

    private static void syncOnMainThread() {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                detach();
                return;
            }
            // The hold covers the feed and carries its own words. A number underneath it is
            // either invisible or arguing with the panel on top.
            //
            // The stricter gate rather than isOnFeed: the cue is a label that just sits there,
            // so it should be on the recommendation feed or gone. isOnFeed answers "assume the
            // feed" for a build whose Home tab it cannot find and counts a detail page opened
            // from a profile grid or a search result, and it says nothing about the comment
            // sheet, so the cue went on drawing over the top of one while the four feed controls
            // took themselves away.
            if (SessionBudget.isLocked() || !FeedVisibility.onRecommendationFeed(activity)) {
                detach();
                return;
            }
            String text = labelFor(SessionBudget.videosSeen(), SessionBudget.watchedMs(),
                    Settings.SESSION_BUDGET_VIDEOS.get(), Settings.SESSION_BUDGET_MINUTES.get());
            if (text == null) {
                detach();
                return;
            }
            TextView cue = attach(activity);
            if (cue == null) return;
            if (!text.equals(shownText)) {
                cue.setText(text);
                shownText = text;
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not update the budget cue", error);
        }
    }

    /**
     * The progress callback that puts the label up stops with the player, and leaving the feed
     * for Profile or Inbox stops the player, so it can't be what takes the label down. A tab
     * change or a screen over the feed lays the window out, and each pass asks again.
     */
    private static final GlobalLayoutHook LAYOUT = new GlobalLayoutHook();

    private static void recheck() {
        if (cueReference.get() == null) {
            LAYOUT.detach();
            return;
        }
        Activity activity = Utils.getActivity();
        if (activity == null || SessionBudget.isLocked()
                || !FeedVisibility.onRecommendationFeed(activity)) {
            detach();
        }
    }

    private static TextView attach(Activity activity) {
        TextView existing = cueReference.get();
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        if (existing != null && existing.getParent() == root) {
            LAYOUT.install(root, BudgetCue::recheck);
            return existing;
        }
        // The activity moved. Take the old label off the screen it is on rather than dropping
        // the only handle to it and building a second one.
        detach();

        TextView cue = new TextView(activity);
        cue.setTextColor(SettingsUi.OVERLAY_TEXT);
        cue.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_CAPTION);
        cue.setMaxLines(1);
        int paddingX = SettingsUi.dp(activity, 10);
        int paddingY = SettingsUi.dp(activity, 4);
        cue.setPadding(paddingX, paddingY, paddingX, paddingY);

        // The same backdrop the feed's own controls use, from the one helper that builds it, so
        // the two read as one set rather than as something the app grew overnight.
        cue.setBackground(SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY));

        // Nothing about it is interactive: no listener, so the feed underneath scrolls, likes
        // and comments exactly as before.
        cue.setClickable(false);
        cue.setFocusable(false);
        // Reachable in traversal, which is how its value is exposed, and silent otherwise.
        cue.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cue.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        int margin = SettingsUi.dp(activity, MARGIN_DP);
        params.setMarginStart(margin);
        params.topMargin = margin + statusBarHeight(activity);
        root.addView(cue, params);
        cueReference = new WeakReference<>(cue);
        shownText = null;
        LAYOUT.install(root, BudgetCue::recheck);
        return cue;
    }

    /**
     * How far down the status bar reaches, so the label does not sit under it.
     *
     * <p>The feed draws edge to edge, so the content root starts at the top of the screen.
     */
    private static int statusBarHeight(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (id > 0) return activity.getResources().getDimensionPixelSize(id);
        } catch (Throwable unreadable) {
            // Fall through to the fallback below.
        }
        return SettingsUi.dp(activity, STATUS_BAR_FALLBACK_DP);
    }

    private static void detach() {
        LAYOUT.detach();
        TextView cue = cueReference.get();
        cueReference = new WeakReference<>(null);
        shownText = null;
        if (cue == null) return;
        ViewGroup parent = cue.getParent() instanceof ViewGroup ? (ViewGroup) cue.getParent() : null;
        if (parent != null) parent.removeView(cue);
    }

    /**
     * One pass with the throttle stood aside, so a test can drive the cue step by step rather
     * than pretending a second goes by between two lines of a fixture.
     */
    static void syncNowForTests() {
        // Far enough back that the throttle lets this pass. Zero would not: the clock starts
        // near zero under a test runner, so "nothing has been asked yet" and "asked a moment
        // ago" are the same number there.
        lastAskedAt = android.os.SystemClock.uptimeMillis() - ASK_EVERY_MS;
        sync();
    }

    /** Drops the label and forgets it, between tests. */
    static void resetForTests() {
        detach();
        lastAskedAt = 0;
    }

    /** The label on screen, or null when there is none. Test seam. */
    static TextView cueForTests() {
        return cueReference.get();
    }
}
