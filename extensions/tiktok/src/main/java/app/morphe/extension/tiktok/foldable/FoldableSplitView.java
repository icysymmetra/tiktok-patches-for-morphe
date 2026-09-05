/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.foldable;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public final class FoldableSplitView {
    /**
     * Tracks whether the last evaluation forced split mode. TikTok's own live re-layout for the
     * comment panel is asymmetric: it correctly collapses back to a bottom sheet on any
     * configuration change while already split, but only expands into split mode through a code
     * path gated behind TikTok's own tablet/fold classification, which never fires for real
     * foldable hardware. So a bottom-sheet-to-split transition while the app is already running
     * never gets picked up on its own; the same layout only comes out correct on a fresh bind
     * (app cold start, or a fresh Activity). This flag lets us detect exactly that transition
     * (false -> true) and force a rebuild via that same, already-correct fresh-bind path.
     */
    private static final AtomicBoolean lastForced = new AtomicBoolean(false);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FoldableSplitView() {}

    /**
     * Hooked into X.0oq9->LIZ(Activity, Configuration), TikTok's live "isOptCommentSplit" check.
     */
    public static boolean shouldForceCommentSplit(Activity activity, Configuration configuration) {
        if (!Settings.FOLDABLE_SPLIT_VIEW.get()) {
            return false;
        }

        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())) {
            Logger.printDebug(() -> "[Morphe TikTok FoldableSplitView] shouldForceCommentSplit"
                    + " skipped, activity is in multi-window or picture-in-picture mode");
            return false;
        }

        Activity windowActivity = activity != null ? activity : Utils.getActivity();
        return evaluate("shouldForceCommentSplit", resolveScreenWidthDp(windowActivity, configuration), windowActivity);
    }

    /**
     * Hooked into X.0oq9->LIZJ(), TikTok's cached "isOptSplitContainer" check.
     */
    public static boolean shouldForceCommentSplitContainer() {
        if (!Settings.FOLDABLE_SPLIT_VIEW.get()) {
            return false;
        }

        Activity windowActivity = Utils.getActivity();
        return evaluate("shouldForceCommentSplitContainer", resolveScreenWidthDp(windowActivity, null), windowActivity);
    }

    private static boolean evaluate(String source, int widthDp, Activity windowActivity) {
        int thresholdDp = Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.get();
        boolean force = widthDp > 0 && widthDp >= thresholdDp;
        Logger.printDebug(() -> "[Morphe TikTok FoldableSplitView] " + source
                + " widthDp=" + widthDp + " thresholdDp=" + thresholdDp + " force=" + force);

        if (force) {
            if (lastForced.compareAndSet(false, true)) {
                onWidenTransitionDetected(windowActivity);
            }
        } else {
            lastForced.set(false);
        }

        return force;
    }

    /**
     * Forces a full Activity rebuild so the comment layout gets rebound through the same
     * (already-correct) path used on a fresh app launch, since TikTok's own live re-layout
     * doesn't pick up a bottom-sheet-to-split transition on its own. Deferred to the next main
     * thread loop iteration, since this runs from inside TikTok's own configuration-change
     * handling and recreating the Activity reentrantly from that same call stack is unsafe.
     */
    private static void onWidenTransitionDetected(Activity activity) {
        Activity target = activity != null ? activity : Utils.getActivity();
        if (target == null) {
            Logger.printDebug(() -> "[Morphe TikTok FoldableSplitView] widen transition detected"
                    + " but no activity is available to recreate");
            return;
        }

        Logger.printDebug(() -> "[Morphe TikTok FoldableSplitView] widen transition detected,"
                + " recreating activity to rebuild the comment layout");
        mainHandler.post(() -> {
            try {
                target.recreate();
            } catch (Exception ex) {
                Logger.printDebug(() -> "[Morphe TikTok FoldableSplitView] activity recreate failed", ex);
            }
        });
    }

    /**
     * Prefers a fresh, live measurement of the current window over any passed-in or cached
     * Configuration snapshot. On cold app launch, the first Configuration TikTok (or we) can
     * read is sometimes stale and hasn't yet settled to the device's true current fold posture.
     * Querying WindowManager directly avoids relying on that snapshot having already propagated.
     */
    private static int resolveScreenWidthDp(Activity windowActivity, Configuration configuration) {
        int liveWidthDp = measureLiveWidthDp(windowActivity);
        if (liveWidthDp > 0) {
            return liveWidthDp;
        }

        if (configuration != null) {
            return configuration.screenWidthDp;
        }

        try {
            Context fallbackContext = windowActivity != null ? windowActivity : Utils.getContext();
            if (fallbackContext == null) {
                return 0;
            }
            return fallbackContext.getResources().getConfiguration().screenWidthDp;
        } catch (Exception ex) {
            Logger.printDebug(() -> "[Morphe TikTok FoldableSplitView] resolveScreenWidthDp fallback failed", ex);
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static int measureLiveWidthDp(Activity activity) {
        if (activity == null) {
            return 0;
        }

        try {
            WindowManager windowManager = activity.getWindowManager();
            if (windowManager == null) {
                return 0;
            }

            int widthPx;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                widthPx = windowManager.getCurrentWindowMetrics().getBounds().width();
            } else {
                Point size = new Point();
                windowManager.getDefaultDisplay().getRealSize(size);
                widthPx = size.x;
            }

            float density = activity.getResources().getDisplayMetrics().density;
            if (widthPx <= 0 || density <= 0) {
                return 0;
            }
            return Math.round(widthPx / density);
        } catch (Exception ex) {
            Logger.printDebug(() -> "[Morphe TikTok FoldableSplitView] measureLiveWidthDp failed", ex);
            return 0;
        }
    }
}
