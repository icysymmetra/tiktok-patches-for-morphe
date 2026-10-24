/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.format.DateFormat;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A sleep timer set from the Long press action: when it runs out, the video stops and TikTok
 * closes the way a swipe away from Recents closes it, background play included.
 *
 * <p>Kept in memory only, since TikTok closing any other way leaves nothing to close. The wait
 * runs on the main looper, whose clock stands still while the phone sleeps, so it looks at the
 * end time every half minute rather than waiting out the whole length at once: a sleep part way
 * through then delays the close by half a minute at most. The phone sleeps only when nothing
 * plays (a playing video keeps it awake), so a timer that comes due well past its end found the
 * phone asleep, and it ends without closing rather than closing TikTok on someone who picked the
 * phone up the next morning.
 */
public final class SleepTimer {
    /** The lengths the picker offers. */
    static final int[] MINUTES = {15, 30, 45, 60, 90};
    /** Further past its end than this, the phone slept through the timer. */
    static final long LATE_MS = 2 * 60_000L;
    /** The longest the looper waits between looks at the end time. Well under {@link #LATE_MS}. */
    static final long STEP_MS = 30_000L;
    /** How long TikTok gets to close its screens before its process ends. */
    static final long CLOSE_GRACE_MS = 1_000L;

    interface Closer {
        void close();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable CHECK = () -> check(SystemClock.elapsedRealtime());
    /** Taking the audio focus is how TikTok's player, background play included, is told to stop. */
    private static final AudioManager.OnAudioFocusChangeListener HUSH = change -> {
    };

    private static Closer closer = SleepTimer::closeTikTok;
    /** The elapsed-clock moment the running timer ends, or 0 when none runs. Main thread. */
    private static long endsAt;

    private SleepTimer() {
    }

    /** The long press: picks a length, or turns off the timer that's running. */
    public static void choose(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            SettingsUi.syncDarkMode(activity);
            final boolean running = isRunning();
            List<String> labels = new ArrayList<>();
            if (running) labels.add(L10n.f("Turn off the timer (TikTok closes at %1$s)", endTime(activity)));
            for (int minutes : MINUTES) labels.add(L10n.f("%1$s minutes", minutes));
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Close TikTok after"))
                    .setItems(labels.toArray(new String[0]), (ignored, index) -> {
                        if (running && index == 0) {
                            cancel();
                            Utils.showToastShort(L10n.t("Sleep timer off"));
                        } else {
                            start(MINUTES[running ? index - 1 : index]);
                        }
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not show the sleep timer", failure);
        }
    }

    /** Starts a timer of {@code minutes}, in place of one that's running. Main thread. */
    static void start(int minutes) {
        long length = minutes * 60_000L;
        endsAt = SystemClock.elapsedRealtime() + length;
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, Math.min(length, STEP_MS));
        Logger.printInfo(() -> "Sleep timer set for " + minutes + " minutes");
        Context context = Utils.getContext();
        if (context != null) Utils.showToastShort(L10n.f("TikTok closes at %1$s", endTime(context)));
    }

    static void cancel() {
        endsAt = 0;
        MAIN.removeCallbacks(CHECK);
    }

    public static boolean isRunning() {
        return endsAt != 0;
    }

    /** A look at the end time: closes, waits on, or lets a stale one go. */
    static void check(long now) {
        long end = endsAt;
        if (end == 0) return;
        if (now < end) {
            MAIN.removeCallbacks(CHECK);
            MAIN.postDelayed(CHECK, Math.min(end - now, STEP_MS));
            return;
        }
        endsAt = 0;
        if (now - end > LATE_MS) {
            Logger.printInfo(() -> "Sleep timer: the phone slept through it, TikTok stays open");
            return;
        }
        closer.close();
    }

    private static String endTime(Context context) {
        long wall = System.currentTimeMillis() + Math.max(0, endsAt - SystemClock.elapsedRealtime());
        return DateFormat.getTimeFormat(context).format(new Date(wall));
    }

    private static void closeTikTok() {
        Logger.printInfo(() -> "Sleep timer: closing TikTok");
        SessionPlaybackHold.pauseForSwitch();
        Context context = Utils.getContext();
        if (context == null) return;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // The older call: the request-object form is API 26 and this runs from 23.
            if (audio != null) audio.requestAudioFocus(HUSH, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                for (ActivityManager.AppTask task : manager.getAppTasks()) task.finishAndRemoveTask();
            }
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Sleep timer: could not close TikTok's screens", failure);
        }
        // Background play keeps the process and its player going after the screens close, as a
        // swipe from Recents doesn't, so the process ends too once the screens have had a moment.
        MAIN.postDelayed(() -> Process.killProcess(Process.myPid()), CLOSE_GRACE_MS);
    }

    static void setCloserForTests(Closer testCloser) {
        closer = testCloser;
    }

    static void resetForTests() {
        cancel();
        closer = SleepTimer::closeTikTok;
    }
}
