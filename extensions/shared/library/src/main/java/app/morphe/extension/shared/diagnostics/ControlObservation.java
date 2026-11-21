package app.morphe.extension.shared.diagnostics;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded observations only. Intercepted startup calls never write files or use the logger. */
public final class ControlObservation {
    private static final int SLOTS = 4;
    private static final AtomicInteger ENTRIES = new AtomicInteger(), CONFIG = new AtomicInteger(-1);
    private static final AtomicReferenceArray<Call> CALLS = new AtomicReferenceArray<>(SLOTS);
    private static final Object EXPOSURE = new Object();
    private static final Set<Activity> RESUMED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long resumedAt, resumedMs;
    private static int resumes;
    private static boolean watching, stopped;
    private static Application watchedApplication;
    private static Application.ActivityLifecycleCallbacks callbacks;

    private ControlObservation() {}
    /** Supplied by the patch, not inferred from a user's declared arm. */
    public static String profile() { return ""; }

    public static void configObserved(Object value) {
        try { if (CONFIG.compareAndSet(-1, value == null ? 0 : 1)) RecoveryTimeline.requestReading(); }
        catch (Throwable ignored) { /* Observation must preserve the target result. */ }
    }
    public static int begin(double utilization, long minFree, long maxFree) {
        try {
            int n;
            do { n = ENTRIES.get(); if (n >= 1_000_000) return 0; }
            while (!ENTRIES.compareAndSet(n, n + 1));
            if (n >= SLOTS) return 0;
            CALLS.set(n, new Call(utilization, minFree, maxFree));
            RecoveryTimeline.requestReading();
            return n + 1;
        } catch (Throwable ignored) { return 0; }
    }
    public static void unavailable(int token) { update(token, 3, 0); }
    public static void nativeStarting(int token) { update(token, 1, 0); }
    public static void returned(int token, int result) { update(token, 2, result); }
    private static void update(int token, int phase, int result) {
        try {
            if (token <= 0 || token > SLOTS) return;
            Call call = CALLS.get(token - 1);
            if (call == null) return;
            call.result = result; call.phase = phase;
            RecoveryTimeline.requestReading();
        } catch (Throwable ignored) { /* No effect on target behavior. */ }
    }
    private static final class Call {
        final double utilization;
        final long min, max;
        volatile int phase, result;
        Call(double utilization, long min, long max) {
            this.utilization = utilization; this.min = min; this.max = max;
        }
    }
    static String state() {
        StringBuilder out = new StringBuilder("first_config=")
                .append(CONFIG.get() < 0 ? "unobserved" : CONFIG.get() == 0 ? "null" : "present")
                .append(" entries=").append(ENTRIES.get() > SLOTS ? "over_4" : ENTRIES.get());
        for (int i = 0; i < SLOTS; i++) {
            Call c = CALLS.get(i); if (c == null) continue;
            int phase = c.phase;
            // Grouped raw bits preserve exact primitive values through the report's ID redactor.
            out.append(" call").append(i + 1).append("=u_hex:").append(bits(Double.doubleToRawLongBits(c.utilization)))
                    .append(",min_hex:").append(bits(c.min)).append(",max_hex:").append(bits(c.max))
                    .append(",phase:").append(phase == 0 ? "gate_pending" : phase == 1 ? "native_pending"
                            : phase == 2 ? "returned" : "library_unavailable");
            if (phase == 2) out.append(",result:").append(c.result);
        }
        synchronized (EXPOSURE) {
            out.append(" resumed_s=").append((resumedMs + (RESUMED.isEmpty() ? 0
                    : Math.max(0, SystemClock.uptimeMillis() - resumedAt))) / 1000)
                    .append(" resumes=").append(resumes).append(" lifecycle=").append(watching);
        }
        return out.toString();
    }
    static String bits(long value) {
        String raw = Long.toHexString(value);
        StringBuilder out = new StringBuilder(19);
        for (int i = raw.length(); i < 16; i++) out.append('0');
        out.append(raw);
        for (int i = 12; i > 0; i -= 4) out.insert(i, '_');
        return out.toString();
    }
    static void watch(Application application) {
        synchronized (EXPOSURE) {
            if (watching || stopped) return;
            callbacks = new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity a) { activity(a, true); }
                public void onActivityPaused(Activity a) { activity(a, false); }
                public void onActivityDestroyed(Activity a) { activity(a, false); }
                public void onActivityCreated(Activity a, Bundle b) {}
                public void onActivityStarted(Activity a) {}
                public void onActivityStopped(Activity a) {}
                public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            };
            application.registerActivityLifecycleCallbacks(callbacks);
            watchedApplication = application;
            watching = true;
        }
    }
    static void stopWatching() {
        synchronized (EXPOSURE) {
            if (watchedApplication != null && callbacks != null)
                watchedApplication.unregisterActivityLifecycleCallbacks(callbacks);
            if (!RESUMED.isEmpty()) resumedMs += Math.max(0, SystemClock.uptimeMillis() - resumedAt);
            RESUMED.clear(); watchedApplication = null; callbacks = null; watching = false;
            stopped = true;
        }
    }
    static void activity(Activity a, boolean resumed) {
        synchronized (EXPOSURE) {
            if (resumed) {
                if (RESUMED.size() >= 16 || RESUMED.contains(a)) return;
                if (RESUMED.isEmpty()) resumedAt = SystemClock.uptimeMillis();
                RESUMED.add(a); resumes++;
            } else if (RESUMED.remove(a) && RESUMED.isEmpty()) {
                resumedMs += Math.max(0, SystemClock.uptimeMillis() - resumedAt);
            }
        }
    }
    static void resetForTests() {
        stopWatching();
        ENTRIES.set(0); CONFIG.set(-1);
        for (int i = 0; i < SLOTS; i++) CALLS.set(i, null);
        synchronized (EXPOSURE) { RESUMED.clear(); resumedAt = resumedMs = 0; resumes = 0; watching = false; stopped = false; }
    }
}
