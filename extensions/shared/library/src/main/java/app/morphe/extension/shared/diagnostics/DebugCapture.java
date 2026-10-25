/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * A timed diagnostic capture: Log diagnostics answers on for {@link #DURATION_MS}, then stops by
 * itself, so a reader recording one bug report can't leave logging running for weeks.
 *
 * <p>Nothing here touches the Log diagnostics switch. {@link BaseSettings#DEBUG} answers on while
 * a capture runs, and its saved value stays what the reader chose, so starting, stopping or
 * running out never turns off logging they asked for separately.
 *
 * <p>The capture is saved as when it started by both clocks, plus the phone's boot count, in
 * {@link BaseSettings#DEBUG_CAPTURE}, which backups leave out. It ends when either clock says
 * its time is up, so moving the phone's clock back can't stretch it, and a restarted phone ends
 * it, since the elapsed clock starts again from zero. Before 7.0, which has no boot count, the
 * restart shows as the boot moment the two clocks give moving. A restarted TikTok reads the
 * same record and keeps the same end.
 */
public final class DebugCapture {
    /** How long one capture logs. A saved record asking for longer is treated as over. */
    public static final long DURATION_MS = 15 * 60_000L;

    /** How far the boot moment may drift by network time corrections before 7.0 reads it as a new boot. */
    private static final long BOOT_DRIFT_MS = 30_000L;

    interface Clock {
        long wall();

        long elapsed();

        /** The phone's boot count, or -1 where Android doesn't say (before 7.0). */
        int boot();
    }

    private static final Clock DEVICE = new Clock() {
        @Override public long wall() {
            return System.currentTimeMillis();
        }

        @Override public long elapsed() {
            return SystemClock.elapsedRealtime();
        }

        @Override public int boot() {
            Context context = Utils.getContext();
            if (context == null || Build.VERSION.SDK_INT < 24) return -1;
            return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
        }
    };

    private static volatile Clock clock = DEVICE;

    /**
     * The elapsed-clock moment the running capture ends, or 0 when none runs. Every debug log
     * line asks {@link #isRunning()}, so the answer is this field and a clock read, no setting.
     */
    private static volatile long endsAt;

    private DebugCapture() {
    }

    /** Whether a capture is logging right now. */
    public static boolean isRunning() {
        long end = endsAt;
        return end != 0 && clock.elapsed() < end;
    }

    /**
     * Picks the saved capture back up when TikTok starts, or clears it when its time ran out
     * while TikTok wasn't running.
     */
    public static synchronized void load() {
        String saved = BaseSettings.DEBUG_CAPTURE.savedValue();
        if (saved.isEmpty()) {
            endsAt = 0;
            return;
        }
        Clock now = clock;
        long elapsed = now.elapsed();
        long left = remaining(saved, now.wall(), elapsed, now.boot());
        if (left <= 0) {
            endsAt = 0;
            BaseSettings.DEBUG_CAPTURE.resetToDefault();
            Logger.printInfo(() -> "Timed diagnostic capture ended while TikTok was closed");
            return;
        }
        endsAt = elapsed + left;
    }

    /**
     * Starts a capture of {@link #DURATION_MS}, or a fresh one in place of a running one.
     *
     * @return the wall-clock time it ends, for the reader.
     */
    public static synchronized long start() {
        Clock now = clock;
        long wall = now.wall();
        long elapsed = now.elapsed();
        BaseSettings.DEBUG_CAPTURE.save(record(wall, elapsed, now.boot(), DURATION_MS));
        endsAt = elapsed + DURATION_MS;
        Logger.printInfo(() -> "Timed diagnostic capture started for " + DURATION_MS / 60_000L + " minutes");
        return wall + DURATION_MS;
    }

    /** Ends a running capture now. The Log diagnostics switch keeps whatever it was set to. */
    public static synchronized void stop() {
        boolean wasRunning = isRunning();
        endsAt = 0;
        BaseSettings.DEBUG_CAPTURE.resetToDefault();
        if (wasRunning) Logger.printInfo(() -> "Timed diagnostic capture stopped");
    }

    /** The wall-clock time the running capture ends, or 0 when none runs. */
    public static long endsAtWallClock() {
        long end = endsAt;
        Clock now = clock;
        long elapsed = now.elapsed();
        if (end == 0 || elapsed >= end) return 0;
        return now.wall() + (end - elapsed);
    }

    static String record(long wall, long elapsed, int boot, long duration) {
        return "1;" + wall + ";" + elapsed + ";" + boot + ";" + duration;
    }

    /**
     * How long the capture in {@code record} has left at this moment, or 0 when it's over or
     * the record can't be read.
     */
    static long remaining(String record, long wall, long elapsed, int boot) {
        String[] parts = record.split(";", -1);
        if (parts.length != 5 || !"1".equals(parts[0])) return 0;
        try {
            long startWall = Long.parseLong(parts[1]);
            long startElapsed = Long.parseLong(parts[2]);
            int startBoot = Integer.parseInt(parts[3]);
            long duration = Long.parseLong(parts[4]);
            if (duration <= 0 || duration > DURATION_MS) return 0;
            // The elapsed clock starts again at every boot, so a capture can't be measured
            // across one.
            if (startBoot >= 0 && boot >= 0 && startBoot != boot) return 0;
            long sinceStart = elapsed - startElapsed;
            if (sinceStart < 0) return 0;
            // Before 7.0 there's no boot count. The moment the elapsed clock started (wall minus
            // elapsed) stays put within one boot and jumps by at least the old uptime at the
            // next. A clock change moves it too and ends the capture early, the safe way.
            if ((startBoot < 0 || boot < 0)
                    && Math.abs((wall - elapsed) - (startWall - startElapsed)) > BOOT_DRIFT_MS) return 0;
            long left = duration - sinceStart;
            // The wall clock can only bring the end closer. Set back, it would stretch the
            // capture, so a negative reading leaves the elapsed clock to decide.
            long sinceWall = wall - startWall;
            if (sinceWall > 0) left = Math.min(left, duration - sinceWall);
            return Math.max(0, left);
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    static void setClockForTests(Clock testClock) {
        clock = testClock;
    }

    static void resetForTests() {
        clock = DEVICE;
        stop();
    }
}
