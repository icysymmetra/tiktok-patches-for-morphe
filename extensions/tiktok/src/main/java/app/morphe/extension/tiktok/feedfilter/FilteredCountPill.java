/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.navigation.FrontWindow;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;

/**
 * Show how many were filtered: a small label on the main feed with the number of posts the feed
 * filter has taken out since TikTok started. It sits under TikTok's top tabs, shows only while
 * the Home feed is certainly on screen and something has been taken out, and takes no touches,
 * so a tap on it reaches the video. It's a label nobody can dismiss, so it asks the strict feed
 * check rather than the one that assumes the feed when it can't tell. It reads the running count on the feed window's layout passes, which
 * playback keeps coming, and only sets its text when the number has moved, so it can't start a
 * layout loop of its own.
 *
 * <p>It goes on the main activity that is resumed, not the newest one created: TikTok's launcher
 * entry is a second MainActivity under the feed's, and changing TikTok's appearance recreates it
 * last, which took the label to the buried copy until TikTok was restarted.
 */
public final class FilteredCountPill {
    /** Under TikTok's top tabs, which end about this far below the status bar on 47.x. */
    private static final int BELOW_STATUS_BAR_DP = 56;
    private static final int SIDE_DP = 12;

    /**
     * The main activity in front. The main activity alone holds the feed with its tabs; Settings
     * opens in another, so a switch turned on there takes effect when the feed comes back.
     */
    private static final FrontWindow FRONT = new FrontWindow(false, new FrontWindow.Listener() {
        @Override public void onFront(Activity activity) {
            refresh(activity);
        }

        @Override public void onGone(Activity activity) {
            try {
                detach();
            } catch (Throwable error) {
                Logger.printException(() -> "Could not take the filtered count off the feed", error);
            }
        }
    });
    private static WeakReference<TextView> pillReference = new WeakReference<>(null);
    private static final ViewTreeObserver.OnGlobalLayoutListener SYNC = FilteredCountPill::sync;
    /** The count the label says now, or -1 before it has said one. Main thread only. */
    private static long shown = -1;

    private FilteredCountPill() {
    }

    /** From the main activity's onCreate: follows that activity and puts the label up as it resumes. */
    public static void install(Activity activity) {
        try {
            if (activity == null) return;
            FRONT.add(activity);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not follow the feed for the filtered count", error);
        }
    }

    /** Puts the label on the feed, or takes it off, as the switch now says. Main thread. */
    static void refresh(Activity activity) {
        try {
            if (!wanted()) {
                detach();
                return;
            }
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) content;
            TextView pill = pillReference.get();
            if (pill == null || pill.getParent() != root) {
                detach();
                pill = create(activity);
                root.addView(pill, params(activity, root));
                pillReference = new WeakReference<>(pill);
                // Before the window is attached this is a floating observer that's merged into
                // the window's later, so detach finds the listener through the root again.
                ViewTreeObserver observer = root.getViewTreeObserver();
                observer.removeOnGlobalLayoutListener(SYNC);
                observer.addOnGlobalLayoutListener(SYNC);
            }
            sync();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not show the filtered count", error);
        }
    }

    private static boolean wanted() {
        return SettingsStatus.feedFilterEnabled && Settings.FILTERED_COUNT_PILL.get();
    }

    /** Shows the label with the current count while the feed is on screen, and hides it otherwise. */
    static void sync() {
        TextView pill = pillReference.get();
        Activity activity = FRONT.get();
        if (pill == null || activity == null) return;
        try {
            long count = FeedFilterCounters.sessionRemoved();
            // The Home tab shown and selected with no comment sheet over it; a cleared screen
            // puts the tab bar away, so the label goes with it.
            boolean visible = wanted() && count > 0
                    && !SessionBudget.isLocked()
                    && FeedVisibility.onRecommendationFeed(activity);
            if (visible && count != shown) {
                shown = count;
                pill.setText(text(activity, count));
            }
            int visibility = visible ? View.VISIBLE : View.GONE;
            if (pill.getVisibility() != visibility) pill.setVisibility(visibility);
        } catch (Throwable error) {
            // A layout pass is TikTok's own frame: the label goes rather than the app.
            pill.setVisibility(View.GONE);
            Logger.printException(() -> "Could not update the filtered count", error);
        }
    }

    static String text(Context context, long count) {
        return L10n.quantity(context, count, "1 filtered out", "%1$d filtered out");
    }

    private static void detach() {
        TextView pill = pillReference.get();
        if (pill != null && pill.getParent() instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) pill.getParent();
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(SYNC);
            root.removeView(pill);
        }
        pillReference = new WeakReference<>(null);
        shown = -1;
    }

    private static TextView create(Context context) {
        TextView pill = new TextView(context);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setTextColor(0xFFFFFFFF);
        pill.setSingleLine(true);
        int across = dp(context, 10);
        int down = dp(context, 4);
        pill.setPadding(across, down, across, down);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x99000000);
        background.setCornerRadius(dp(context, 12));
        pill.setBackground(background);
        // Not clickable, so a touch on it falls through to the video under it.
        pill.setClickable(false);
        pill.setFocusable(false);
        pill.setVisibility(View.GONE);
        return pill;
    }

    private static FrameLayout.LayoutParams params(Context context, View root) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        params.topMargin = statusBar(root) + dp(context, BELOW_STATUS_BAR_DP);
        params.setMarginStart(dp(context, SIDE_DP));
        return params;
    }

    /** The status bar's height: TikTok draws the feed under it, so the label starts below it. */
    @SuppressWarnings("deprecation")
    private static int statusBar(View root) {
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) return insets.getSystemWindowInsetTop();
        int id = root.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : root.getResources().getDimensionPixelSize(id);
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    static TextView pillForTests() {
        return pillReference.get();
    }
}
