/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.diagnostics;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Recovery trial only: what the process looked like in the minutes before it froze (#215).
 *
 * <p>A report taken at the freeze shows the end state, which looks the same whether the heap was
 * really full or the collector stopped working and the heap filled behind it. Readings every few
 * seconds show the way there: the heap, what the collector completed, how much was allocated and
 * freed, threads, memory and whether the main thread still answers. The last saved reading is the
 * last observation the sampler could make, not a proven start of the freeze: the sampler can stop
 * with everything else.
 *
 * <p>Two daemon threads, one small file per session, overwritten whole each time and bounded. They
 * read and never change the host: no hooks, settings, UI or shared executors. The main thread is
 * pinged from its own thread, so a queue lock held during a stall can't stop the readings.
 */
final class RecoveryTimeline {
    static final long PERIOD_MS = 5_000;
    /** Six minutes of readings. */
    static final int MAX_SAMPLES = 72;
    /** What a report may take of the store's budget for one incident. */
    static final int MAX_SECTION = 6 * 1024;
    /** Trial state has its own small allowance, so it never displaces heap/GC readings. */
    static final int MAX_SOFT_REFERENCE = 192;
    static final int MAX_FILE = 16 * 1024;
    static final int KEEP_FILES = 5;
    /** When TikTok's runtime tuning is read: after its startup task, then every two minutes. */
    static final int JATO_FIRST = 4, JATO_EVERY = 24;
    static final int MAX_JATO = 1600;
    static final String HEADING = "[TIMELINE BEFORE EXIT]\n";
    private static final String JATO_LINE = "J ";
    private static final String SOFT_REFERENCE_LINE = "S ";

    /** {@code elapsed} is the clock the lease is kept on: it keeps running while the phone sleeps. */
    interface Clock { long wall(); long elapsed(); }
    interface Probe {
        String sample(); String jato();
        default String softReference() { return ""; }
        default void saved() {}
    }

    private RecoveryTimeline() {}

    /**
     * Clearing diagnostics wipes readings both on disk and in the running sampler, under one
     * lock, so a write in progress can't put back what was cleared (#215 review).
     */
    private static final Object FILES_LOCK = new Object();
    private static long clears;
    private static volatile File lastFilesDir;
    private static final Object READING_WAKE = new Object();
    private static boolean readingRequested;

    /** The first bypass of each installer requests an early reading on the existing sampler. */
    static void requestReading() {
        synchronized (READING_WAKE) { readingRequested = true; READING_WAKE.notifyAll(); }
    }
    static void resetReadingRequest() { synchronized (READING_WAKE) { readingRequested = false; } }
    private static void awaitReading() throws InterruptedException {
        synchronized (READING_WAKE) {
            if (!readingRequested) READING_WAKE.wait(PERIOD_MS);
            readingRequested = false;
        }
    }

    static void clear(File filesDir) {
        File target = filesDir != null ? filesDir : lastFilesDir;
        synchronized (FILES_LOCK) {
            clears++;
            File[] files = target == null ? null : dir(target).listFiles();
            if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    static File dir(File filesDir) { return new File(filesDir, "metra_recovery_timeline"); }
    /** Named for the session as well as the pid, so a reused pid never overwrites an earlier session. */
    static File file(File filesDir, int pid, long sessionAt) {
        return new File(dir(filesDir), pid + "-" + sessionAt + ".txt");
    }

    /** Starts the readings for this session until {@code untilElapsed} on the elapsed-realtime clock. */
    static void start(File filesDir, int pid, long sessionAt, long untilElapsed, ClassLoader loader) {
        start(filesDir, pid, sessionAt, untilElapsed, loader, () -> {});
    }
    static void start(File filesDir, int pid, long sessionAt, long untilElapsed, ClassLoader loader,
            Runnable saved) {
        final Handler main = new Handler(Looper.getMainLooper());
        // When the outstanding ping was sent, or 0 when it was answered. Written before posting,
        // so a post that blocks on the queue's lock still shows as a main thread that's late.
        final long[] pending = {0};
        final Runnable ack = () -> { synchronized (pending) { pending[0] = 0; } };
        Clock clock = new Clock() {
            public long wall() { return System.currentTimeMillis(); }
            public long elapsed() { return SystemClock.elapsedRealtime(); }
        };
        Thread heartbeat = new Thread(() -> {
            try {
                while (SystemClock.elapsedRealtime() < untilElapsed) {
                    boolean send;
                    synchronized (pending) {
                        send = pending[0] == 0;
                        if (send) pending[0] = SystemClock.elapsedRealtime();
                    }
                    if (send) main.post(ack);
                    Thread.sleep(PERIOD_MS);
                }
            } catch (Throwable unavailable) { /* Evidence only. */ }
        }, "MetraRecoveryPing");
        Probe probe = new Probe() {
            public String sample() {
                long sent;
                synchronized (pending) { sent = pending[0]; }
                return reading(sent == 0 ? 0 : SystemClock.elapsedRealtime() - sent);
            }
            public String jato() { return jatoState(loader); }
            public String softReference() {
                return RecoveryCapture.softReferenceTrialEnabled() ? RecoveryCapture.softReferenceState() : "";
            }
            public void saved() { saved.run(); }
        };
        Thread sampler = new Thread(() -> {
            try { run(filesDir, pid, sessionAt, untilElapsed, clock, probe, true); }
            catch (Throwable unavailable) { /* Evidence only; never disturbs the host. */ }
        }, "MetraRecoveryTimeline");
        heartbeat.setDaemon(true); sampler.setDaemon(true);
        heartbeat.start(); sampler.start();
    }

    /** The sampling loop. {@code sleep} is false in tests, which drive the clock themselves. */
    static void run(File filesDir, int pid, long sessionAt, long untilElapsed, Clock clock, Probe probe,
            boolean sleep) throws Exception {
        lastFilesDir = filesDir;
        prune(filesDir, pid, sessionAt);
        File target = file(filesDir, pid, sessionAt);
        ArrayDeque<String> lines = new ArrayDeque<>();
        String header = "pid=" + pid + " session_at=" + sessionAt + "\n";
        // The latest tuning snapshot is kept apart from the readings, so a long session can't
        // push it out; it carries the time it was last confirmed.
        String jatoLine = null, lastJato = null;
        int count = 0;
        long nextJatoElapsed = clock.elapsed() + JATO_FIRST * PERIOD_MS;
        long seenClears;
        synchronized (FILES_LOCK) { seenClears = clears; }
        while (true) {
            if (sleep) awaitReading();
            // Checked after the wait, so a phone asleep past the lease takes no further reading.
            if (clock.elapsed() >= untilElapsed) return;
            count++;
            lines.addLast(clock.wall() + " " + probe.sample());
            // One current process-state record, not repeated in the ring of heap/GC readings.
            // Date it when observed, so Clear can discard a pre-clear observation normally.
            String softReference = probe.softReference();
            String softReferenceLine = softReference.isEmpty() ? "" : SOFT_REFERENCE_LINE + clock.wall()
                    + " " + softReference.substring(0, Math.min(softReference.length(), 128)) + "\n";
            // First-hit wakeups add readings without moving the startup/config observation earlier.
            if (clock.elapsed() >= nextJatoElapsed) {
                String jato = probe.jato();
                if (jato.length() > MAX_JATO) jato = jato.substring(0, MAX_JATO) + " [truncated]";
                if (lastJato != null && !jato.equals(lastJato)) lines.addLast(clock.wall() + " jato_changed");
                lastJato = jato;
                jatoLine = JATO_LINE + clock.wall() + " " + jato + "\n";
                nextJatoElapsed = clock.elapsed() + JATO_EVERY * PERIOD_MS;
            }
            while (lines.size() > MAX_SAMPLES) lines.removeFirst();
            synchronized (FILES_LOCK) {
                if (clears != seenClears) {
                    // Everything observed before the clear goes, including the tuning snapshot;
                    // the newest reading was taken before the clear too.
                    seenClears = clears; lines.clear(); jatoLine = null; lastJato = null;
                    continue;
                }
                write(target, header + (jatoLine == null ? "" : jatoLine) + softReferenceLine, lines);
            }
            probe.saved();
            if (!sleep && count >= MAX_SAMPLES * 4) return;
        }
    }

    private static void write(File target, String head, ArrayDeque<String> lines) throws Exception {
        byte[] bytes;
        while (true) {
            StringBuilder out = new StringBuilder(head);
            for (String line : lines) out.append(line).append('\n');
            bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= MAX_FILE) break;
            // Keep saving the newest readings if unusually wide runtime counters fill the file.
            if (lines.size() <= 1) return;
            lines.removeFirst();
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        AtomicFile file = new AtomicFile(target);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) file.failWrite(stream);
            throw failure;
        }
    }

    /** Keeps this session's file and the newest few others, so a later launch can still read them. */
    static void prune(File filesDir, int pid, long sessionAt) {
        File[] files = dir(filesDir).listFiles();
        if (files == null || files.length < KEEP_FILES) return;
        String own = file(filesDir, pid, sessionAt).getName();
        List<File> others = new ArrayList<>();
        for (File f : files) if (!f.getName().equals(own)) others.add(f);
        java.util.Collections.sort(others, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP_FILES - 1; i < others.size(); i++) //noinspection ResultOfMethodCallIgnored
            others.get(i).delete();
    }

    /**
     * The section for a failed session: the latest tuning snapshot, then its readings, each dated
     * relative to the exit, newest last. The file is found by pid and session start and its header
     * must say the same, so no other process's readings can be lent to this one.
     */
    static String section(File filesDir, int pid, long sessionAt, long exitAt) {
        return section(filesDir, pid, sessionAt, exitAt, 0);
    }

    /** Readings from before {@code clearedThrough}, when diagnostics were last cleared, are left out. */
    static String section(File filesDir, int pid, long sessionAt, long exitAt, long clearedThrough) {
        File source = file(filesDir, pid, sessionAt);
        String text;
        try {
            if (!source.isFile() || source.length() > MAX_FILE) return HEADING + "not_recorded\n";
            AtomicFile file = new AtomicFile(source);
            try (FileInputStream in = file.openRead()) {
                byte[] bytes = new byte[(int) source.length()]; int at = 0;
                while (at < bytes.length) { int n = in.read(bytes, at, bytes.length - at); if (n <= 0) break; at += n; }
                text = new String(bytes, 0, at, StandardCharsets.UTF_8);
            }
        } catch (Exception unavailable) { return HEADING + "unreadable\n"; }
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].equals("pid=" + pid + " session_at=" + sessionAt)) {
            return HEADING + "not_recorded\n";
        }
        String jato = "jato_latest: not_read\n";
        String softReference = "";
        List<String> rendered = new ArrayList<>();
        long last = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            boolean isJato = line.startsWith(JATO_LINE);
            boolean isSoftReference = line.startsWith(SOFT_REFERENCE_LINE);
            if (isJato || isSoftReference) line = line.substring(2);
            int space = line.indexOf(' ');
            if (space <= 0) continue;
            long at;
            try { at = Long.parseLong(line.substring(0, space)); } catch (NumberFormatException bad) { continue; }
            if (at <= clearedThrough) continue;
            String body = line.substring(space + 1);
            if (isSoftReference) {
                softReference = "softref_latest: " + relative(at - exitAt) + " "
                        + body.substring(0, Math.min(body.length(), 128)) + "\n";
                continue;
            }
            if (isJato) {
                String value = body.length() > MAX_JATO + 16 ? body.substring(0, MAX_JATO) + " [truncated]" : body;
                jato = "jato_latest: " + relative(at - exitAt) + " " + value + "\n";
                continue;
            }
            last = Math.max(last, at);
            rendered.add(relative(at - exitAt) + " " + body);
        }
        StringBuilder out = new StringBuilder(HEADING);
        out.append("samples=").append(rendered.size()).append(" period_s=").append(PERIOD_MS / 1000);
        if (last > 0) out.append(" last_saved_reading_before_exit_s=").append(Math.max(0, (exitAt - last) / 1000));
        out.append('\n').append(jato);
        // The newest readings matter most, so the oldest go first when the section is too long.
        // The note saying so is budgeted before any reading is chosen.
        int budget = MAX_SECTION - out.length() - omittedNote(rendered.size()).length();
        int from = rendered.size(), used = 0;
        while (from > 0 && used + rendered.get(from - 1).length() + 1 <= budget) used += rendered.get(--from).length() + 1;
        if (from > 0) out.append(omittedNote(from));
        for (int i = from; i < rendered.size(); i++) out.append(rendered.get(i)).append('\n');
        // This bounded metadata is additional to the unchanged six-KiB readings allowance.
        out.append(softReference);
        return out.toString();
    }

    private static String omittedNote(int count) { return "[" + count + " older readings omitted]\n"; }

    private static String relative(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return "t" + (seconds >= 0 ? "+" : "") + seconds + "s";
    }

    /**
     * One reading, in a fixed compact form. The collector's counters move when a collection
     * completes, so flat counters mean none completed, which is not the same as one hanging.
     * Bytes allocated against bytes freed separate churn from a heap that only keeps growing.
     */
    static String reading(long mainLagMs) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        String[] status = procStatus();
        return "heap=" + mb(used) + "/" + mb(rt.maxMemory()) + "M"
                + " gc=" + stat("art.gc.gc-count") + "/" + stat("art.gc.gc-time") + "ms"
                + " bgc=" + stat("art.gc.blocking-gc-count") + "/" + stat("art.gc.blocking-gc-time") + "ms"
                + " alloc=" + statMb("art.gc.bytes-allocated") + "M freed=" + statMb("art.gc.bytes-freed") + "M"
                + " thr=" + status[0] + " rss=" + status[1]
                + " nat=" + mb(Debug.getNativeHeapAllocatedSize()) + "M"
                + " main=" + (mainLagMs < PERIOD_MS ? "ok" : "late_" + (mainLagMs / 1000) + "s");
    }

    private static String stat(String name) {
        try { String value = Debug.getRuntimeStat(name); return value == null ? "?" : value.replaceAll("[^0-9]", ""); }
        catch (Throwable unavailable) { return "?"; }
    }

    private static String statMb(String name) {
        String value = stat(name);
        if (value.isEmpty() || value.equals("?") || value.length() > 18) return "?";
        return String.valueOf(Long.parseLong(value) / (1024 * 1024));
    }

    private static long mb(long bytes) { return bytes / (1024 * 1024); }

    /** Threads and resident memory from the kernel's view of this process. */
    private static String[] procStatus() {
        String threads = "?", rss = "?";
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("Threads:")) threads = line.substring(8).trim();
                else if (line.startsWith("VmRSS:")) {
                    String kb = line.substring(6).replaceAll("[^0-9]", "");
                    if (!kb.isEmpty() && kb.length() < 18) rss = (Long.parseLong(kb) / 1024) + "M";
                }
            }
        } catch (Throwable unavailable) { /* Leave the unknowns. */ }
        return new String[] {threads, rss};
    }

    /** TikTok's runtime-tuning library: the classes whose state says whether it holds off the collector. */
    static final List<String> JATO_CLASSES = Arrays.asList(
            "com.bytedance.common.jato.JatoXL",
            "com.bytedance.common.jato.memory.gcblocker.GcBlocker",
            "com.bytedance.common.jato.boost.RemoveWaitingForGCOpt");
    static final String JATO_CONFIG_FIELD = "sConfig";

    static String jatoState(ClassLoader loader) { return jatoState(loader, JATO_CLASSES); }

    /**
     * Reads, never writes: each class's static number and flag fields, and the number and flag
     * fields of JatoXL's config object. Names are listed as the app has them; an absent class is
     * reported as absent rather than guessed at. Classes are looked up without initialising them,
     * and none of these has a static initialiser to run.
     */
    static String jatoState(ClassLoader loader, List<String> classes) {
        StringBuilder out = new StringBuilder("jato");
        outer:
        for (String name : classes) {
            String simple = name.substring(name.lastIndexOf('.') + 1);
            Class<?> type;
            try { type = Class.forName(name, false, loader); }
            catch (Throwable absent) { out.append(' ').append(simple).append("=absent"); continue; }
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    if (simple(field.getType())) {
                        out.append(' ').append(simple).append('.').append(field.getName()).append('=').append(value(field.get(null)));
                    } else if (field.getName().equals(JATO_CONFIG_FIELD)) {
                        Object config = field.get(null);
                        if (config == null) { out.append(' ').append(simple).append(".config=null"); continue; }
                        for (Field item : config.getClass().getDeclaredFields()) {
                            if (Modifier.isStatic(item.getModifiers()) || !simple(item.getType())) continue;
                            item.setAccessible(true);
                            out.append(" config.").append(item.getName()).append('=').append(value(item.get(config)));
                        }
                    }
                    if (out.length() > 1500) { out.append(" [jato state truncated]"); break outer; }
                }
            } catch (Throwable unreadable) { out.append(' ').append(simple).append("=unreadable"); }
        }
        return out.toString().replaceAll("[^A-Za-z0-9._=\\[\\] -]", "_");
    }

    /**
     * A tuning value as text. Long numbers are written so the report's redactor, which takes any
     * long run of digits for an ID, leaves them readable: the extremes by name (GcBlocker sets
     * Long.MAX_VALUE to lift its limit), anything else that long in scientific form. None of these
     * is a user's identifier; they are flags, limits and times set by TikTok's code.
     */
    static String value(Object value) {
        if (!(value instanceof Long) && !(value instanceof Integer)) return String.valueOf(value);
        long number = ((Number) value).longValue();
        if (number == Long.MAX_VALUE) return "long_max";
        if (number == Long.MIN_VALUE) return "long_min";
        if (number == Integer.MAX_VALUE) return "int_max";
        if (number == Integer.MIN_VALUE) return "int_min";
        if (Math.abs(number) < 1_000_000) return String.valueOf(number);
        return String.format(java.util.Locale.ROOT, "%.3e", (double) number).replace("+", "");
    }

    private static boolean simple(Class<?> type) {
        return type == boolean.class || type == int.class || type == long.class
                || type == short.class || type == byte.class || type == float.class || type == double.class;
    }
}
