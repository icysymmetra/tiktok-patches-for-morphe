/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Pause Hushfeed, and the safe mode that turns it on without being asked.
 *
 * <p>Whether a process runs paused is decided once, at the first context, before any hook reads
 * a setting. Paused, every setting that changes TikTok answers its unpatched value
 * ({@link Setting#get()}); saved values stay as they are and come back on the next start.
 *
 * <p>Three things pause a process: the Pause switch, safe mode, and a file named
 * {@value #MARKER_FILE_NAME} in TikTok's own folder under {@code Android/data}, for a phone that
 * can't reach the settings at all. Safe mode turns on after three starts in a row that crashed
 * within a minute. A start is recorded when the process begins and cleared once it has run for a
 * minute, so a record that is still there at the next start belonged to a process that died
 * young. Android 11 and newer say why it died, and only a crash, a native crash or a hang counts;
 * being swiped away or force-stopped does not. Older Android counts the crashes that reached the
 * Java uncaught-exception handler.
 */
public final class HushfeedPause {
    /** Why this process runs paused. */
    public enum Reason { NONE, SWITCH, CRASH_LOOP, MARKER_FILE }

    public static final String MARKER_FILE_NAME = "hushfeed-safe-mode";
    static final String START_RECORD_NAME = "hushfeed-start";
    static final String CRASH_STREAK_NAME = "hushfeed-start-crashes";
    static final long START_WINDOW_MS = 60_000L;
    static final int CRASHES_TO_SAFE_MODE = 3;
    private static final String CRASHED = "crashed";

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean CRASH_MARKED = new AtomicBoolean();
    private static volatile Reason reason = Reason.NONE;
    private static volatile long startElapsed;
    @Nullable private static volatile File filesDir;
    @Nullable private static volatile Context appContext;

    /** Whether Android still holds this process in its crash state. Tests put an answer here. */
    interface CrashState {
        boolean stillCrashing();
    }

    static CrashState crashState = HushfeedPause::inErrorState;

    private HushfeedPause() {
    }

    /** Runs at the first context of every process. Later calls do nothing. */
    public static void onProcessStart(Context context) {
        if (context == null || !STARTED.compareAndSet(false, true)) return;
        startElapsed = SystemClock.elapsedRealtime();
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
        try {
            filesDir = context.getFilesDir();
            if (Utils.isMainProcess()) countLastStart(context);
        } catch (RuntimeException failure) {
            // Never let the safety net be what stops TikTok from starting.
            Logger.printException(() -> "Metra pause: could not read the last start", failure);
        }
        reason = decide(context);
        Setting.setPausedForProcess(reason != Reason.NONE);
        if (reason != Reason.NONE) {
            Logger.printInfo(() -> "Metra is paused for this start: " + reason);
        }
    }

    public static Reason reason() {
        return reason;
    }

    /** The same answer every setting gives: this process runs paused. */
    public static boolean isPaused() {
        return Setting.isPaused();
    }

    /**
     * Turns safe mode, the Pause switch and the marker file off, and starts the crash count
     * again. The change applies from the next start.
     *
     * @return false when the marker file is still there and could not be removed.
     */
    public static boolean turnBackOn(Context context) {
        BaseSettings.SAFE_MODE.save(false);
        BaseSettings.PAUSED.save(false);
        File dir = filesDir != null ? filesDir : context.getFilesDir();
        write(new File(dir, CRASH_STREAK_NAME), "0");
        File marker = markerFile(context);
        return marker == null || !marker.exists() || marker.delete();
    }

    /** The marker that pauses Hushfeed from outside the app, or null when there is no storage. */
    @Nullable
    public static File markerFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, MARKER_FILE_NAME);
    }

    /** Whether the next start runs paused, as things stand now. */
    public static boolean pausesNextStart(Context context) {
        return decide(context) != Reason.NONE;
    }

    static Reason decide(Context context) {
        File marker = markerFile(context);
        if (marker != null && marker.exists()) return Reason.MARKER_FILE;
        if (BaseSettings.SAFE_MODE.savedValue()) return Reason.CRASH_LOOP;
        if (BaseSettings.PAUSED.savedValue()) return Reason.SWITCH;
        return Reason.NONE;
    }

    /**
     * Reads the record the last start left, if it died before clearing it, adds it to the streak
     * of young crashes, and leaves a record for this start.
     */
    private static void countLastStart(Context context) {
        File dir = filesDir;
        if (dir == null) return;
        File record = new File(dir, START_RECORD_NAME);
        File streakFile = new File(dir, CRASH_STREAK_NAME);
        String last = read(record);
        if (last != null) {
            int streak = diedYoungFromACrash(context, last) ? parseCount(read(streakFile)) + 1 : 0;
            if (streak >= CRASHES_TO_SAFE_MODE) {
                BaseSettings.SAFE_MODE.save(true);
                streak = 0;
                Logger.printInfo(() -> "Metra safe mode: three starts in a row crashed within a minute");
            }
            write(streakFile, Integer.toString(streak));
        }
        write(record, Process.myPid() + " " + System.currentTimeMillis());
        installCrashMark();
        new Handler(Looper.getMainLooper()).postDelayed(HushfeedPause::survivedTheStart, START_WINDOW_MS);
    }

    /**
     * A minute in, this start did not crash: the record goes and the streak ends.
     *
     * <p>A crash the handler marked can still be ending the process at this point. Android holds
     * a process that crashed again open behind its "keeps stopping" dialog, and a crash off the
     * main thread leaves this looper running meanwhile, so clearing the record here would forget
     * the crash. While Android still has the process in its crash state the record stays for the
     * next start to judge. An exception a later handler swallowed leaves no crash state, and
     * that start did survive.
     */
    static void survivedTheStart() {
        File dir = filesDir;
        if (dir == null) return;
        if (CRASH_MARKED.get() && crashState.stillCrashing()) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir, START_RECORD_NAME).delete();
        write(new File(dir, CRASH_STREAK_NAME), "0");
    }

    private static boolean inErrorState() {
        Context context = appContext;
        if (context == null) return false;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.ProcessErrorStateInfo> errors =
                    manager == null ? null : manager.getProcessesInErrorState();
            if (errors == null) return false;
            int pid = Process.myPid();
            for (ActivityManager.ProcessErrorStateInfo error : errors) {
                if (error.pid == pid) return true;
            }
        } catch (RuntimeException unanswered) {
            Logger.printException(() -> "Metra pause: could not ask for the crash state", unanswered);
        }
        return false;
    }

    static boolean diedYoungFromACrash(Context context, String record) {
        String[] parts = record.trim().split(" ");
        int pid;
        long started;
        try {
            pid = Integer.parseInt(parts[0]);
            started = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
        } catch (RuntimeException unreadable) {
            return false;
        }
        boolean markedByHandler = parts.length > 2 && CRASHED.equals(parts[2]);
        if (Build.VERSION.SDK_INT >= 30) {
            ApplicationExitInfo exit = firstExitSince(context, pid, started);
            if (exit != null) {
                int reason = exit.getReason();
                boolean crash = reason == ApplicationExitInfo.REASON_CRASH
                        || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                        || reason == ApplicationExitInfo.REASON_ANR;
                // Android writes a crash down when it is reported, before any "keeps stopping"
                // dialog holds the process open, so the first record since the start says when
                // the crash happened. A process that lived past the minute by the clock on the
                // wall, the device asleep or not, did not die young.
                return crash && exit.getTimestamp() - started <= START_WINDOW_MS;
            }
        }
        return markedByHandler;
    }

    /**
     * The earliest exit Android recorded for the pid since the start, or null when there is
     * none. A record older than the start belonged to an earlier process that had the same pid.
     */
    @Nullable
    private static ApplicationExitInfo firstExitSince(Context context, int pid, long started) {
        if (Build.VERSION.SDK_INT < 30) return null;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return null;
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, pid, 16);
        ApplicationExitInfo first = null;
        if (exits == null) return null;
        for (ApplicationExitInfo exit : exits) {
            if (exit.getPid() != pid || exit.getTimestamp() < started) continue;
            if (first == null || exit.getTimestamp() < first.getTimestamp()) first = exit;
        }
        return first;
    }

    /**
     * Marks the start record when an uncaught exception ends the process inside the first
     * minute, for Android before 11, which keeps no exit reasons. The handler only writes a
     * word to a file and hands the crash on.
     */
    private static void installCrashMark() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashMark) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashMark(current));
    }

    static void markCrash() {
        File dir = filesDir;
        if (dir == null || SystemClock.elapsedRealtime() - startElapsed > START_WINDOW_MS) return;
        if (!CRASH_MARKED.compareAndSet(false, true)) return;
        File record = new File(dir, START_RECORD_NAME);
        String current = read(record);
        if (current != null && !current.trim().endsWith(CRASHED)) write(record, current.trim() + " " + CRASHED);
    }

    private static final class CrashMark implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler delegate;

        CrashMark(Thread.UncaughtExceptionHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            try {
                markCrash();
            } catch (Throwable ignored) {
                // The crash belongs to the handler after this one.
            }
            if (delegate != null) delegate.uncaughtException(thread, throwable);
        }
    }

    static int parseCount(@Nullable String text) {
        if (text == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    @Nullable
    static String read(File file) {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int length = in.read(buffer);
            return length <= 0 ? "" : new String(buffer, 0, length, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static void write(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "Metra pause: could not write " + file.getName(), failure);
        }
    }

    static void pauseForTests(Reason why) {
        reason = why;
        Setting.setPausedForProcess(why != Reason.NONE);
    }

    static void resetForTests() {
        STARTED.set(false);
        CRASH_MARKED.set(false);
        reason = Reason.NONE;
        filesDir = null;
        appContext = null;
        crashState = HushfeedPause::inErrorState;
        Setting.setPausedForProcess(false);
    }
}
