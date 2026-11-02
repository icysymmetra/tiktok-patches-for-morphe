/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.diagnostics;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded main-thread evidence during an explicitly requested timed capture. */
public final class MainThreadStallProbe {
    // Keep the injected code usable on API 23, before java.util.function was added.
    interface Clock { long getAsLong(); }
    interface Active { boolean getAsBoolean(); }
    interface Output<T> { void accept(T value); }
    static final long INTERVAL_MS = 100, STALL_MS = 500, SAMPLE_MS = 500;
    static final int MAX_SAMPLES = 12, MAX_PER_STALL = 3, MAX_FRAMES = 24;

    private MainThreadStallProbe() {}

    private static volatile Session current;
    private static volatile Trace retained;

    static Message heartbeatMessage(Handler handler, Runnable callback, Object token) {
        Message message = Message.obtain(handler, callback);
        message.obj = token;
        // API 22+: measure thread availability rather than the presence of a rendering barrier.
        message.setAsynchronous(true);
        return message;
    }

    public static void shareClickStarted() { mark("click:start"); }
    public static void shareClickFinished() { mark("click:end"); }
    public static void shareModelStarted() { mark("model:start"); }
    public static void shareModelFinished() { mark("model:end"); }
    public static void shareActionsStarted() { mark("actions-panel:start"); }
    public static void shareActionsFinished() { mark("actions-panel:end"); }
    public static void shareAppsStarted() { mark("apps-panel:start"); }
    public static void shareAppsFinished() { mark("apps-panel:end"); }

    private static synchronized void mark(String phase) {
        Session session = current;
        if (session == null || !DebugCapture.isRunning() || session.probe.stopped) return;
        try {
            session.trace.mark(phase, Thread.currentThread() == session.probe.main,
                    Thread.currentThread().getId(), SystemClock.uptimeMillis());
        } catch (RuntimeException unavailable) { /* Never interrupt a native share callback. */ }
    }

    public static List<String> lines() {
        Trace trace = retained;
        return trace == null ? Collections.emptyList() : trace.lines();
    }

    public static final class Snapshot {
        final Trace before, slot;
        Snapshot(Trace before, Trace slot) { this.before = before; this.slot = slot; }
        public boolean isEmpty() { return before == null || !before.hasEvidence(); }
    }

    public static synchronized Snapshot snapshotAndClear() {
        Trace before = retained;
        Trace slot = current == null ? null : new Trace(SystemClock.uptimeMillis());
        retained = slot;
        if (current != null) current.trace = slot;
        return new Snapshot(before, slot);
    }

    public static synchronized void restore(Snapshot saved) {
        if (saved == null || saved.isEmpty() || retained != saved.slot
                || (retained != null && retained.hasEvidence())) return;
        retained = saved.before;
        if (current != null && current.trace == saved.slot) current.trace = saved.before;
    }

    /** Separate bounded evidence from the ordinary logger's filters and generation/disk locks. */
    static final class Trace {
        static final int MAX_PHASES = 32, MAX_SAMPLE_CHARS = 56_000;
        final long start;
        final ArrayDeque<String> phases = new ArrayDeque<>(), samples = new ArrayDeque<>();
        volatile String mainPhase = "none";
        int chars;
        boolean evidence;
        Trace(long start) { this.start = start; }
        synchronized boolean hasEvidence() { return evidence; }
        synchronized void mark(String phase, boolean main, long thread, long at) {
            evidence = true;
            if (main) mainPhase = phase;
            phases.addLast("at_ms=" + (at - start) + " " + (main ? "main" : "worker")
                    + " thread=" + thread + " " + phase);
            while (phases.size() > MAX_PHASES) phases.removeFirst();
        }
        synchronized String record(String message, long at) {
            if (message.contains("sample=") || message.contains("late acknowledgement")) evidence = true;
            String text = "at_ms=" + (at - start) + " main_phase=" + mainPhase + " " + message;
            if (text.length() > 4096) text = text.substring(0, 4070) + "\n[trace truncated]";
            samples.addLast(text); chars += text.length();
            while (chars > MAX_SAMPLE_CHARS || samples.size() > 40) chars -= samples.removeFirst().length();
            return text;
        }
        synchronized List<String> lines() {
            if (!evidence) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            out.add("capture_uptime_ms=" + start);
            out.add("recent share phases:");
            out.addAll(phases);
            out.add("main-thread samples and timing:");
            for (String sample : samples) Collections.addAll(out, sample.split("\n"));
            return out;
        }
    }

    static synchronized void start() {
        stop();
        Looper main = Looper.getMainLooper();
        if (main == null || !DebugCapture.isRunning()) return;
        try {
            current = new Session(main);
            retained = current.trace;
            current.worker.postDelayed(current, INTERVAL_MS);
        } catch (RuntimeException unavailable) {
            // Diagnostic sampling must not prevent a capture or the app from starting.
            stop();
        }
    }

    static synchronized void stop() {
        Session previous = current;
        current = null;
        if (previous != null) previous.stop();
    }

    private static synchronized void finished(Session session) {
        if (current == session) current = null;
    }

    /** One disposable worker per timed capture, with at most one outstanding main callback. */
    private static final class Session implements Runnable {
        final HandlerThread thread = new HandlerThread("MetraStallProbe");
        final Handler main;
        final Handler worker;
        final Object token = new Object();
        final AtomicBoolean closed = new AtomicBoolean();
        final Probe probe;
        volatile Trace trace = new Trace(SystemClock.uptimeMillis());

        Session(Looper mainLooper) {
            main = new Handler(mainLooper);
            thread.setDaemon(true);
            thread.start();
            try { worker = new Handler(thread.getLooper()); }
            catch (RuntimeException unavailable) {
                thread.quitSafely();
                throw unavailable;
            }
            probe = new Probe(SystemClock::uptimeMillis, DebugCapture::isRunning,
                    callback -> main.sendMessageAtTime(heartbeatMessage(main, callback, token), SystemClock.uptimeMillis()),
                    mainLooper.getThread(), this::record);
        }

        void record(String message) {
            // Select and write under the same short lifecycle lock as clear/undo. Never enter
            // the ordinary logger here: crash persistence can hold its lock during disk I/O.
            synchronized (MainThreadStallProbe.class) {
                trace.record(message, SystemClock.uptimeMillis());
            }
        }

        @Override public void run() {
            try {
                if (probe.tick()) worker.postDelayed(this, INTERVAL_MS);
                else close();
            } catch (RuntimeException unavailable) {
                close();
            }
        }

        void stop() {
            probe.stop();
            // Never join a worker or wait for the main thread, particularly during a stall.
            main.removeCallbacksAndMessages(token);
            worker.post(this::close);
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            probe.stop();
            try { probe.finish(); }
            finally {
                main.removeCallbacksAndMessages(token);
                worker.removeCallbacksAndMessages(null);
                thread.quitSafely();
                finished(this);
            }
        }
    }

    /** The same state machine the device timer drives, with a clock/queue seam for replay. */
    static final class Probe {
        final Clock now;
        final Active active;
        final Output<Runnable> postMain;
        final Thread main;
        final Output<String> report;
        volatile boolean stopped;
        Heartbeat pending;
        int samples, perStall, pings, stalls, lateAcknowledgements;
        long lastSample, longest;
        boolean finished;

        final class Heartbeat implements Runnable {
            final long sent = now.getAsLong();
            volatile boolean acknowledged;
            long at;
            @Override public void run() {
                at = now.getAsLong();
                acknowledged = true;
            }
        }

        Probe(Clock now, Active active, Output<Runnable> postMain,
              Thread main, Output<String> report) {
            this.now = now; this.active = active; this.postMain = postMain;
            this.main = main; this.report = report;
        }

        boolean tick() {
            if (stopped || !active.getAsBoolean()) return false;
            long time = now.getAsLong();
            if (pending != null && pending.acknowledged) {
                long delay = Math.max(0, pending.at - pending.sent);
                longest = Math.max(longest, delay);
                if (perStall > 0) emit("recovered after " + delay + "ms; samples=" + perStall);
                else if (delay >= STALL_MS && ++lateAcknowledgements <= MAX_SAMPLES) {
                    emit("late acknowledgement after " + delay + "ms; no in-flight snapshot");
                }
                pending = null;
                perStall = 0;
            }
            if (pending == null) {
                pending = new Heartbeat();
                pings++;
                postMain.accept(pending);
                return true;
            }
            long waiting = time - pending.sent;
            if (waiting < STALL_MS || perStall >= MAX_PER_STALL || samples >= MAX_SAMPLES
                    || (perStall > 0 && time - lastSample < SAMPLE_MS)) return true;
            StackTraceElement[] stack = main.getStackTrace();
            // The main callback may have run while the snapshot was being collected.
            if (stopped || !active.getAsBoolean() || pending.acknowledged) return !stopped;
            if (perStall == 0) stalls++;
            perStall++; samples++; lastSample = time;
            StringBuilder text = new StringBuilder("sample=").append(samples)
                    .append(" waiting=").append(waiting).append("ms state=").append(main.getState());
            for (int i = 0; i < Math.min(MAX_FRAMES, stack.length); i++) {
                // Frame metadata only. Do not read locals, arguments, exception messages or objects.
                text.append("\n  at ").append(stack[i]);
            }
            emit(text.toString());
            return true;
        }

        void emit(String text) {
            try { report.accept("[ShareFreeze-204] " + text); }
            catch (RuntimeException unavailable) { stopped = true; }
        }
        void stop() { stopped = true; }
        void finish() {
            if (finished) return;
            finished = true;
            if (pending != null && pending.acknowledged) {
                longest = Math.max(longest, Math.max(0, pending.at - pending.sent));
            }
            emit("summary pings=" + pings + " stalls=" + stalls + " samples=" + samples
                    + " lateAcknowledgements=" + lateAcknowledgements + " longestAcknowledged=" + longest + "ms");
        }
    }
}
