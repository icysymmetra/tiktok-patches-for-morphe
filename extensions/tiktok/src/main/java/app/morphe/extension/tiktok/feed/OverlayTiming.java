/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.feed;

import android.os.SystemClock;

import app.morphe.extension.shared.settings.preference.LogBufferManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the overlay pass and Burn-in guard cost on this phone, for the diagnostic export (#191).
 *
 * <p>Features over the feed have turned into lag before, and nobody here can run a frame
 * profiler on a reporter's phone. These are plain counters the main thread adds to as it goes:
 * an add per pass, two clock reads around it, the same around each pre-draw fade, and an add per
 * touch or wake. Nothing is logged, allocated or formatted until a report is exported.
 *
 * <p>The export reads on its own thread. Ints are written whole and the longs are volatile, so a
 * report is at worst an event behind and never a torn number. Nothing here is a map the main
 * thread changes: the watchers on the window are counted on the main thread and kept as a number.
 */
final class OverlayTiming implements LogBufferManager.ReportSection {
    static final String TITLE = "OVERLAY TIMING";
    private static final OverlayTiming INSTANCE = new OverlayTiming();

    /** Why the guard brought the controls back, in the order the export lists them. */
    static final int WAKE_CONTROL = 0;
    static final int WAKE_VIDEO = 1;
    static final int WAKE_SCROLL = 2;
    static final int WAKE_RETURN = 3;
    static final int WAKE_HOLD = 4;
    private static final String[] WAKE_NAMES = {"a control", "the video", "a scroll", "coming back", "a hold"};

    /** What a finished gesture was, as BurnInGuard read it. */
    static final int GESTURE_CONTROL = 0;
    static final int GESTURE_VIDEO = 1;
    static final int GESTURE_SCROLL = 2;
    static final int GESTURE_HOLD = 3;
    static final int GESTURE_COVERED = 4;
    private static final String[] GESTURE_NAMES = {"taps on a control", "taps on the video", "swipes",
            "holds", "taps on a sheet or page over the feed"};

    private static volatile boolean registered;
    /** Uptime of the first counted pass, or 0. */
    private static volatile long since;

    private static int passes;
    private static int walks;
    private static int guardPasses;
    private static volatile long passNanos;
    private static volatile long walkNanos;
    private static volatile long slowestPassNanos;
    private static int preDraws;
    private static volatile long slowestPreDrawNanos;
    private static int dims;
    private static int touchEvents;
    /** Dimmed controls brought back, by reason. */
    private static final int[] WAKES = new int[WAKE_NAMES.length];
    /** The wait started again with the controls already up, by reason. */
    private static final int[] RESTARTS = new int[WAKE_NAMES.length];
    private static final int[] GESTURES = new int[GESTURE_NAMES.length];
    /** Touch watchers on the window the guard last watched, or -1 before it watched one. */
    private static volatile int watchers = -1;
    /** The pass running now walked the tree. */
    private static boolean walking;

    private OverlayTiming() {
    }

    static void passed(long startNanos, boolean askedByGuard) {
        long took = System.nanoTime() - startNanos;
        if (!registered) register();
        if (since == 0L) since = SystemClock.uptimeMillis();
        passes++;
        if (askedByGuard) guardPasses++;
        passNanos += took;
        if (walking) {
            walking = false;
            walks++;
            walkNanos += took;
        }
        if (took > slowestPassNanos) slowestPassNanos = took;
    }

    /** The pass running now walks the tree, the part that costs. */
    static void walked() {
        walking = true;
    }

    static void preDrawn(long startNanos) {
        long took = System.nanoTime() - startNanos;
        preDraws++;
        if (took > slowestPreDrawNanos) slowestPreDrawNanos = took;
    }

    static void dimmed() {
        dims++;
    }

    static void woke(int reason) {
        WAKES[reason]++;
    }

    static void restarted(int reason) {
        RESTARTS[reason]++;
    }

    static void touched() {
        touchEvents++;
    }

    static void gesture(int kind) {
        GESTURES[kind]++;
    }

    /** How many touch watchers sit on the window the guard watches, counted on the main thread. */
    static void watchers(int count) {
        watchers = count;
    }

    private static synchronized void register() {
        if (registered) return;
        registered = true;
        LogBufferManager.registerReportSection(INSTANCE);
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        int counted = passes;
        if (counted == 0) return lines;
        long minutes = Math.max(1L, (SystemClock.uptimeMillis() - since) / 60_000L);
        lines.add(String.format(Locale.ROOT,
                "Overlay passes: %d over %d min (%d/min), %d asked by Burn-in guard, %.2f ms average, %.2f ms slowest",
                counted, minutes, counted / minutes, guardPasses,
                passNanos / (double) counted / 1e6, slowestPassNanos / 1e6));
        int walked = walks;
        if (walked > 0) {
            lines.add(String.format(Locale.ROOT, "Passes that walked the view tree: %d (%d/min), %.2f ms average",
                    walked, walked / minutes, walkNanos / (double) walked / 1e6));
        }
        if (preDraws > 0) {
            lines.add(String.format(Locale.ROOT, "Fade before each frame: %d runs, %.3f ms slowest",
                    preDraws, slowestPreDrawNanos / 1e6));
        }
        if (touchEvents > 0 || dims > 0) {
            StringBuilder woke = new StringBuilder();
            for (int i = 0; i < WAKES.length; i++) {
                if (i > 0) woke.append(", ");
                woke.append(WAKE_NAMES[i]).append(' ').append(WAKES[i]);
            }
            StringBuilder restarted = new StringBuilder();
            for (int i = 0; i < RESTARTS.length; i++) {
                if (i > 0) restarted.append(", ");
                restarted.append(WAKE_NAMES[i]).append(' ').append(RESTARTS[i]);
            }
            lines.add("Burn-in guard: dimmed " + dims + " times; brought back by " + woke);
            lines.add("Burn-in guard: wait restarted while up by " + restarted);
            StringBuilder seen = new StringBuilder();
            for (int i = 0; i < GESTURES.length; i++) {
                if (i > 0) seen.append(", ");
                seen.append(GESTURE_NAMES[i]).append(' ').append(GESTURES[i]);
            }
            lines.add("Touch events seen: " + touchEvents + "; " + seen);
        }
        int stacked = watchers;
        if (stacked >= 0) lines.add("Touch watchers on the feed's window: " + stacked);
        return lines;
    }

    static OverlayTiming sectionForTests() {
        return INSTANCE;
    }

    static void resetForTests() {
        since = 0L;
        passes = 0;
        walks = 0;
        walkNanos = 0L;
        walking = false;
        guardPasses = 0;
        passNanos = 0L;
        slowestPassNanos = 0L;
        preDraws = 0;
        slowestPreDrawNanos = 0L;
        dims = 0;
        touchEvents = 0;
        java.util.Arrays.fill(WAKES, 0);
        java.util.Arrays.fill(RESTARTS, 0);
        java.util.Arrays.fill(GESTURES, 0);
        watchers = -1;
    }

    static int passesForTests() {
        return passes;
    }

    static int wakesForTests(int reason) {
        return WAKES[reason];
    }
}
