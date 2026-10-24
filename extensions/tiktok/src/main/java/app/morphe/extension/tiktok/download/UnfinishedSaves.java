/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.settings.L10n;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one word a start gets about saves the last TikTok closed on.
 *
 * <p>It runs once per process, after MediaCache's own sweep. What finished is consumed without a
 * word. What didn't, or can't be confirmed, is named on a banner once the feed is in front, and
 * consumed once that banner has been up for its whole time, so it is said once. A banner another
 * one replaced, or that went with the controls, was cut short, and the next start says it again.
 * It names the kind of save and how
 * many of its files are missing, never a file, and it promises nothing: a save isn't picked up
 * again by itself, and the reader can save it again if they still want it.
 */
final class UnfinishedSaves {
    /** How long the feed gets to settle after it comes to the front, before the banner goes up. */
    static final long SETTLE_MS = 2000;
    /** Lines under the heading before the rest are only counted. */
    static final int MAX_LINES = 4;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    /** The watcher waiting for the feed, main thread only; a test's next start takes it down. */
    private static Watcher waiting;
    /** For the tests: every notice this put up, in order. Null keeps it to the phone. */
    static volatile List<String> shownForTests;

    private UnfinishedSaves() {
    }

    /**
     * Reconciles the records an earlier process left open, off the main thread, and waits for
     * the feed when something needs saying. A second call in the same process does nothing.
     */
    static void atStart(Context context) {
        if (context == null || !STARTED.compareAndSet(false, true)) return;
        SaveRecords.Report report = SaveRecords.reconcile(context);
        if (report.saves.isEmpty()) return;
        Utils.runOnMainThread(() -> waitForFeed(context, report));
    }

    /** The heading and one line per save, or two for a save with both kinds of loss. */
    static String message(Context context, SaveRecords.Report report) {
        StringBuilder text = new StringBuilder(L10n.t(context, "TikTok closed during these saves"));
        int lines = 0;
        int more = 0;
        for (SaveRecords.Unfinished save : report.saves) {
            List<String> said = lines(context, save);
            if (more > 0 || lines + said.size() > MAX_LINES) {
                more++;
                continue;
            }
            for (String line : said) text.append('\n').append(line);
            lines += said.size();
        }
        if (more > 0) {
            text.append('\n').append(L10n.quantity(context, more, "And one more save", "And %1$s more saves"));
        }
        return text.toString();
    }

    private static List<String> lines(Context context, SaveRecords.Unfinished save) {
        String kind = kindLabel(context, save.kind);
        String files = String.valueOf(save.files);
        List<String> said = new ArrayList<>();
        // The count picks the form, so the verb agrees with it: Spanish and Portuguese put it in
        // the plural for "3 of 5" and the singular for "one of 5".
        if (save.unfinished > 0) {
            said.add(save.files == 1 ? L10n.f(context, "%1$s: didn't finish", kind)
                    : L10n.quantity(context, save.unfinished, "%1$s: one of %3$s files didn't finish",
                            "%1$s: %2$s of %3$s files didn't finish",
                            kind, String.valueOf(save.unfinished), files));
        }
        if (save.uncertain > 0) {
            said.add(save.files == 1 ? L10n.f(context, "%1$s: might not have finished", kind)
                    : L10n.quantity(context, save.uncertain, "%1$s: one of %3$s files might not have finished",
                            "%1$s: %2$s of %3$s files might not have finished",
                            kind, String.valueOf(save.uncertain), files));
        }
        return said;
    }

    static String kindLabel(Context context, String kind) {
        switch (kind) {
            case "video":
                return L10n.t(context, "Video");
            case "original photos":
                return L10n.t(context, "Original photos");
            case "story":
                return L10n.t(context, "Story");
            case "sound":
                return L10n.t(context, "Sound");
            case "original-sound":
                return L10n.t(context, "Original sound");
            case "profile picture":
                return L10n.t(context, "Profile picture");
            case "sticker":
                return L10n.t(context, "Sticker");
            case "comment live photo":
                return L10n.t(context, "Live photo clip");
            case "photo video":
                return L10n.t(context, "Video of photos");
            case "video frame":
                return L10n.t(context, "Video frame");
            case BatchSave.LABEL:
                return L10n.t(context, "Picked videos");
            default:
                return L10n.t(context, "Media save");
        }
    }

    /**
     * Main thread. The start this runs from is TikTok's application coming up, before any screen,
     * so the notice waits for the main activity to come to the front. A process that never shows
     * one (a push, a background job) says nothing and leaves the records for a start that does.
     */
    private static void waitForFeed(Context context, SaveRecords.Report report) {
        try {
            Application application = application(context);
            if (application == null) {
                Logger.printInfo(() -> "No application to wait on for the unfinished saves notice");
                return;
            }
            Watcher watcher = new Watcher(application, context, report);
            application.registerActivityLifecycleCallbacks(watcher);
            waiting = watcher;
            Activity current = Utils.getActivity();
            if (inFront(current)) watcher.resumed(current);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not wait for the feed to show the unfinished saves", failure);
        }
    }

    private static Application application(Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof Application) return (Application) app;
        if (context instanceof Application) return (Application) context;
        Activity activity = Utils.getActivity();
        return activity == null ? null : activity.getApplication();
    }

    /** Alive and drawn: a stopped activity's window is hidden, so its decor isn't shown. */
    private static boolean inFront(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getWindow() != null && activity.getWindow().getDecorView().isShown();
    }

    /** Follows the activities until the main one has been in front long enough, then says it once. */
    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        private final Application application;
        private final Context context;
        private final SaveRecords.Report report;
        private WeakReference<Activity> front = new WeakReference<>(null);
        /** The main activity the pending check is for; null when none is pending. */
        private WeakReference<Activity> armedFor = new WeakReference<>(null);
        /** Which pending check is the live one. A later arming makes every earlier one stand down. */
        private int generation;
        private boolean said;

        Watcher(Application application, Context context, SaveRecords.Report report) {
            this.application = application;
            this.context = context;
            this.report = report;
        }

        void resumed(Activity activity) {
            front = new WeakReference<>(activity);
            // The main activity only: a splash or a link's screen can be gone inside the wait.
            // A main activity recreated inside the wait (a rotation, a theme change) is a new
            // instance, and it arms a check of its own: the one pending is for a screen that's gone.
            if (said || activity != Utils.getActivity() || armedFor.get() == activity) return;
            armedFor = new WeakReference<>(activity);
            int armed = ++generation;
            Utils.runOnMainThreadDelayed(() -> {
                if (armed != generation) return;
                armedFor = new WeakReference<>(null);
                if (!said && front.get() == activity && activity == Utils.getActivity() && inFront(activity)) {
                    say(activity);
                }
            }, SETTLE_MS);
        }

        /** Stops waiting, without a word. */
        void stop() {
            said = true;
            application.unregisterActivityLifecycleCallbacks(this);
            if (waiting == this) waiting = null;
        }

        private void say(Activity activity) {
            stop();
            String text = message(activity, report);
            List<String> log = shownForTests;
            if (log != null) log.add(text);
            View content = activity.findViewById(android.R.id.content);
            BlockAuthorOverlay.showNoticeBanner(content instanceof ViewGroup ? (ViewGroup) content : null, text,
                    this::seen);
        }

        /** The banner ran its whole time, so what it named has been said. */
        private void seen() {
            SaveRecords.consume(context, report.ids());
            int count = report.saves.size();
            Logger.printInfo(() -> "Said which saves didn't finish: " + count);
        }

        @Override public void onActivityResumed(Activity activity) {
            resumed(activity);
        }

        @Override public void onActivityPaused(Activity activity) {
            if (front.get() == activity) front = new WeakReference<>(null);
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) {
        }

        @Override public void onActivityStarted(Activity activity) {
        }

        @Override public void onActivityStopped(Activity activity) {
        }

        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override public void onActivityDestroyed(Activity activity) {
        }
    }

    /** For the tests, as part of the next start: the process that was waiting is gone. */
    static void resetForTests() {
        STARTED.set(false);
        Utils.runOnMainThreadNowOrLater(() -> {
            Watcher watcher = waiting;
            if (watcher != null) watcher.stop();
        });
    }
}
