/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.os.Process;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.AdAudioSource;
import app.morphe.extension.tiktok.settings.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background audio scanner for feed videos. Prioritizes the active video on screen,
 * pre-scans adjacent feed items, and caches results per video ID and index generation.
 */
final class AdScanner {
    static final long MAX_SCAN_MS = 5L * 60 * 1000;
    /** Early scan window (30 s) for active videos so opening ads skip before full decode completes. */
    private static final int EARLY_SCAN_SAMPLES = Landmarks.SAMPLE_RATE * 30;
    private static final int CACHE = 300;
    private static final int MAX_QUEUE = 8;
    private static final float[] RATES = {1.05f, 0.95f, 1.10f, 0.90f};

    enum State { QUEUED, RUNNING, DONE, FAILED }

    static final class Result {
        final State state;
        final int generation;
        final List<AdMatcher.Match> matches;
        final long scannedMs;
        final long tookMs;
        final String error;

        Result(State state, int generation, List<AdMatcher.Match> matches, long scannedMs, long tookMs, String error) {
            this.state = state;
            this.generation = generation;
            this.matches = matches;
            this.scannedMs = scannedMs;
            this.tookMs = tookMs;
            this.error = error;
        }
    }

    interface Listener {
        void scanned(String aid, Result result);
    }

    private static final Map<String, Result> RESULTS = new LinkedHashMap<String, Result>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > CACHE;
        }
    };
    private static final LinkedList<Job> QUEUE = new LinkedList<>();
    private static final List<Job> RUNNING = new ArrayList<>();
    private static final LinkedList<String> WANTED = new LinkedList<>();
    private static final int WANTED_SIZE = 4;
    private static final Map<String, Job> JOBS = new java.util.HashMap<>();
    private static int workers;
    private static final int MAX_WORKERS = 2;
    private static final int ALGORITHM = 4;
    private static final int MAX_WORKERS_URGENT = 3;
    private static final String CACHE_FILE = "metra-adskip-scans.json";
    private static boolean restored;
    private static volatile Listener listener;

    private static final class Job {
        final String aid;
        final WeakReference<Object> aweme;
        final AtomicBoolean cancel = new AtomicBoolean();
        final long queuedAt = System.nanoTime();
        volatile boolean urgent;
        volatile int tid;

        Job(String aid, Object aweme) {
            this.aid = aid;
            this.aweme = new WeakReference<>(aweme);
        }
    }

    private AdScanner() {}

    static void setListener(Listener l) {
        listener = l;
    }

    static Result result(String aid) {
        if (aid == null) return null;
        restoreOnce();
        synchronized (RESULTS) {
            Result r = RESULTS.get(aid);
            if (r != null && r.generation != stamp() && r.state == State.DONE) return null;
            return r;
        }
    }

    /** Cache generation token combining segment-list generation, strictness, and retimed setting. */
    static int stamp() {
        int config = AdMatcher.Strictness.of(Settings.AD_SKIP_STRICTNESS.get()).ordinal() * 2
                + (Settings.AD_SKIP_RETIMED.get() ? 1 : 0);
        return AdSegmentStore.generation() * 16 + config;
    }

    static void forget(String aid) {
        synchronized (RESULTS) {
            RESULTS.remove(aid);
        }
    }

    /** Immediately records a newly marked segment in its source video's scan cache. */
    static void recordMarked(String aid, AdSegment segment, long startMs, long endMs, int votes, long videoDurationMs) {
        if (aid == null || segment == null) return;
        restoreOnce();
        int generation = stamp();
        AdMatcher.Match added = new AdMatcher.Match(segment, Math.max(0, startMs), Math.max(startMs, endMs), Math.max(1, votes), false);
        Result updated;
        synchronized (RESULTS) {
            Result existing = RESULTS.get(aid);
            List<AdMatcher.Match> combined = new ArrayList<>();
            long scannedMs = Math.max(videoDurationMs, endMs);
            if (existing != null && existing.state == State.DONE && existing.matches != null) {
                combined.addAll(existing.matches);
                scannedMs = Math.max(scannedMs, existing.scannedMs);
            }
            combined.add(added);
            Collections.sort(combined, (a, b) -> Long.compare(a.startMs, b.startMs));
            updated = new Result(State.DONE, generation, AdMatcher.merge(combined), scannedMs, 0, null);
            RESULTS.put(aid, updated);
        }
        Listener l = listener;
        if (l != null) l.scanned(aid, updated);
        Utils.runOnMainThread(AdSkipBar::refresh);
        persist();
    }

    /** Asks for a scan. {@code urgent} for the video on screen, which jumps the queue. */
    static void request(Object aweme, boolean urgent) {
        String aid = Reflect.string(aweme, "getAid", "aid");
        if (aid == null) return;
        if (AdSegmentStore.index().isEmpty()) return;
        restoreOnce();
        int stamp = stamp();
        List<Job> stale = want(aid, urgent);
        if (!stale.isEmpty()) {
            synchronized (RESULTS) {
                for (Job gone : stale) RESULTS.remove(gone.aid);
            }
        }
        // Never hold RESULTS and QUEUE locks simultaneously.
        boolean pending = false;
        synchronized (RESULTS) {
            Result r = RESULTS.get(aid);
            if (r != null && (r.state == State.QUEUED || r.state == State.RUNNING)) {
                pending = true;
            } else {
                if (r != null && r.state == State.DONE && r.generation == stamp) return;
                if (r != null && r.state == State.FAILED && r.generation == stamp && !urgent) return;
                RESULTS.put(aid, new Result(State.QUEUED, stamp, Collections.emptyList(), 0, 0, null));
            }
        }
        if (pending) {
            synchronized (QUEUE) {
                Job current = JOBS.get(aid);
                if (current != null && !current.cancel.get()) {
                    if (urgent) {
                        if (QUEUE.remove(current)) QUEUE.addFirst(current);
                        hurry(current);
                        startWorker(true);
                    }
                    return;
                }
            }
            // Its scan was stopped when the feed moved on, and now the video is back: scan again.
            store(aid, new Result(State.QUEUED, stamp, Collections.emptyList(), 0, 0, null));
        }
        if (isPhotoPost(aweme)) {
            store(aid, new Result(State.DONE, stamp, Collections.emptyList(), 0, 0, null));
            return;
        }
        Job job = new Job(aid, aweme);
        job.urgent = urgent;
        List<Job> dropped = new ArrayList<>();
        synchronized (QUEUE) {
            JOBS.put(aid, job);
            if (urgent) QUEUE.addFirst(job);
            else QUEUE.addLast(job);
            while (QUEUE.size() > MAX_QUEUE) {
                Job gone = QUEUE.removeLast();
                gone.cancel.set(true);
                if (JOBS.get(gone.aid) == gone) JOBS.remove(gone.aid);
                dropped.add(gone);
            }
            startWorker(urgent);
        }
        if (!dropped.isEmpty()) {
            synchronized (RESULTS) {
                for (Job gone : dropped) RESULTS.remove(gone.aid);
            }
        }
    }

    /** Starts a worker thread (up to 2 background, 3 when urgent). */
    private static void startWorker(boolean urgent) {
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) return;
            if (workers < MAX_WORKERS || (urgent && workers < MAX_WORKERS_URGENT)) {
                workers++;
                Utils.runOnOwnThread("metra-adskip", AdScanner::work);
            }
        }
    }

    /** Promotes a running background job to default thread priority when its video comes on screen. */
    private static void hurry(Job job) {
        job.urgent = true;
        int tid = job.tid;
        if (tid == 0) return;
        try {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT);
        } catch (Throwable ignored) {
        }
    }

    /** Tracks the 4 most recent video IDs and cancels jobs outside that window. */
    private static List<Job> want(String aid, boolean urgent) {
        List<Job> dropped = new ArrayList<>();
        synchronized (QUEUE) {
            WANTED.remove(aid);
            if (urgent) WANTED.addFirst(aid);
            else WANTED.add(Math.min(1, WANTED.size()), aid);
            while (WANTED.size() > WANTED_SIZE) WANTED.removeLast();
            for (Job running : RUNNING) {
                if (!WANTED.contains(running.aid)) running.cancel.set(true);
            }
            for (Iterator<Job> it = QUEUE.iterator(); it.hasNext(); ) {
                Job queued = it.next();
                if (WANTED.contains(queued.aid)) continue;
                it.remove();
                queued.cancel.set(true);
                if (JOBS.get(queued.aid) == queued) JOBS.remove(queued.aid);
                dropped.add(queued);
            }
        }
        return dropped;
    }

    /** Whether this job is still the one for its video: an older, stopped one must not overwrite it. */
    private static boolean current(Job job) {
        synchronized (QUEUE) {
            return JOBS.get(job.aid) == job;
        }
    }

    private static boolean isPhotoPost(Object aweme) {
        Object images = Reflect.invoke(aweme, "getImageInfos");
        if (images instanceof List<?> && !((List<?>) images).isEmpty()) return true;
        return Reflect.invoke(aweme, "getPhotoModeImageInfo") != null;
    }

    private static void work() {
        while (true) {
            Job job;
            synchronized (QUEUE) {
                job = QUEUE.pollFirst();
                if (job == null) {
                    workers--;
                    return;
                }
                RUNNING.add(job);
                job.tid = Process.myTid();
            }
            try {
                Process.setThreadPriority(job.urgent ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }
            try {
                run(job);
            } finally {
                synchronized (QUEUE) {
                    RUNNING.remove(job);
                    if (JOBS.get(job.aid) == job) JOBS.remove(job.aid);
                }
            }
        }
    }

    private static void run(Job job) {
        Object aweme = job.aweme.get();
        int generation = stamp();
        if (aweme == null || job.cancel.get() || !AdSkip.active() || !AdSkip.mayDownload()) {
            if (current(job)) forget(job.aid);
            return;
        }
        store(job.aid, new Result(State.RUNNING, generation, Collections.emptyList(), 0, 0, null));
        long started = System.nanoTime();
        long queuedMs = (started - job.queuedAt) / 1_000_000L;
        try {
            Context context = Utils.getContext();
            if (context == null) throw new IllegalStateException("No context");
            AdAudioSource.Pcm pcm = AdAudioSource.load(context, aweme, Landmarks.SAMPLE_RATE, MAX_SCAN_MS, job.cancel,
                    job.urgent ? EARLY_SCAN_SAMPLES : 0,
                    job.urgent ? (earlySamples, earlyLen) -> {
                        if (job.cancel.get() || !current(job)) return;
                        List<AdMatcher.Match> early = plainScan(earlySamples, earlyLen);
                        if (!early.isEmpty() && !job.cancel.get() && current(job)) {
                            long earlyTook = (System.nanoTime() - started) / 1_000_000L;
                            Result interim = new Result(State.DONE, generation, early,
                                    earlyLen * 1000L / Landmarks.SAMPLE_RATE, earlyTook, null);
                            store(job.aid, interim);
                            Listener el = listener;
                            if (el != null) el.scanned(job.aid, interim);
                            Utils.runOnMainThread(AdSkipBar::refresh);
                        }
                    } : null);
            long loaded = (System.nanoTime() - started) / 1_000_000L;
            if (job.cancel.get() || !current(job)) throw new IOException("Cancelled");
            List<AdMatcher.Match> matches = plainScan(pcm.samples, pcm.length);
            long took = (System.nanoTime() - started) / 1_000_000L;
            Result result = new Result(State.DONE, generation, matches, pcm.durationMs(), took, null);
            if (job.cancel.get() || !current(job)) throw new IOException("Cancelled");
            store(job.aid, result);
            AdSkipLog.add(job.aid, (matches.isEmpty()
                    ? "no ad in " + pcm.durationMs() / 1000 + "s"
                    : "found " + matches) + ", " + pcm.downloadedBytes / 1024 + " KB " + (AdAudioSource.separateSound(aweme) ? "dash" : "muxed")
                    + " in " + took + "ms (queue " + queuedMs + ", download " + pcm.downloadMs
                    + ", decode " + pcm.decodeMs + ", match " + (took - loaded) + (job.urgent ? ", on screen)" : ")"));
            Listener l = listener;
            if (l != null) l.scanned(job.aid, result);
            if (!matches.isEmpty()) Utils.runOnMainThread(AdSkipBar::refresh);
            if (matches.isEmpty() && Settings.AD_SKIP_RETIMED.get()) {
                long retimedAt = System.currentTimeMillis();
                List<AdMatcher.Match> retimed = retimedScan(pcm.samples, pcm.length, job.cancel);
                if (!retimed.isEmpty() && !job.cancel.get() && current(job)) {
                    Result better = new Result(State.DONE, generation, retimed, pcm.durationMs(),
                            (System.nanoTime() - started) / 1_000_000L, null);
                    store(job.aid, better);
                    AdSkipLog.add(job.aid, "found " + retimed + " in a retimed copy, " + (System.currentTimeMillis() - retimedAt) + "ms");
                    if (l != null) l.scanned(job.aid, better);
                    Utils.runOnMainThread(AdSkipBar::refresh);
                }
            }
            persist();
        } catch (Throwable failure) {
            if (job.cancel.get()) {
                if (current(job)) forget(job.aid);
                AdSkipLog.add(job.aid, "scan stopped, the feed moved on, elapsed "
                        + (System.nanoTime() - started) / 1_000_000L + "ms (queue " + queuedMs + ")");
                return;
            }
            long took = (System.nanoTime() - started) / 1_000_000L;
            store(job.aid, new Result(State.FAILED, generation, Collections.emptyList(), 0, took,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            AdSkipLog.add(job.aid, "scan failed: " + failure.getMessage());
            Logger.printDebug(() -> "Ad skip: could not scan " + job.aid, failure instanceof Exception ? (Exception) failure : null);
        }
    }

    /** All ads in this PCM: the plain pass, then retimed copies when that found nothing. */
    static List<AdMatcher.Match> scan(short[] pcm, int length) throws IOException {
        return scan(pcm, length, null);
    }

    static List<AdMatcher.Match> scan(short[] pcm, int length, AtomicBoolean cancel) throws IOException {
        List<AdMatcher.Match> found = plainScan(pcm, length);
        if (!found.isEmpty() || !Settings.AD_SKIP_RETIMED.get()) return found;
        return retimedScan(pcm, length, cancel);
    }

    /** The video as it is, read the sparse and the dense way. */
    private static List<AdMatcher.Match> plainScan(short[] pcm, int length) {
        return AdMatcher.find(AdSegmentStore.index(), pcm, length,
                AdMatcher.Strictness.of(Settings.AD_SKIP_STRICTNESS.get()));
    }

    /** The video sped up and slowed down a little, which undoes a retimed copy of an ad. */
    private static List<AdMatcher.Match> retimedScan(short[] pcm, int length, AtomicBoolean cancel) throws IOException {
        AdMatcher.Index index = AdSegmentStore.index();
        AdMatcher.Strictness strictness = AdMatcher.Strictness.of(Settings.AD_SKIP_STRICTNESS.get());
        for (float rate : RATES) {
            if (cancel != null && cancel.get()) throw new IOException("Cancelled");
            short[] stretched = stretch(pcm, length, rate);
            List<AdMatcher.Match> retimed = AdMatcher.match(index, Landmarks.computeQuery(stretched, stretched.length), strictness, true);
            if (retimed.isEmpty()) continue;
            List<AdMatcher.Match> mapped = new ArrayList<>();
            for (AdMatcher.Match m : retimed) {
                mapped.add(new AdMatcher.Match(m.segment, Math.round(m.startMs / rate),
                        Math.round(m.endMs / rate), m.votes, m.partial));
            }
            return mapped;
        }
        return Collections.emptyList();
    }

    /** Linearly resamples PCM by {@code rate} using 12.20 fixed-point arithmetic. */
    static short[] stretch(short[] pcm, int length, float rate) {
        if (length <= 0 || rate <= 0f) return new short[0];
        int outLength = (int) (length * (double) rate);
        short[] out = new short[Math.max(0, outLength)];
        long step = Math.round((1L << 20) / (double) rate);
        long pos = 0;
        int limit = length - 1;
        for (int i = 0; i < out.length; i++, pos += step) {
            int a = (int) (pos >>> 20);
            if (a >= limit) {
                out[i] = pcm[Math.max(0, limit)];
                continue;
            }
            int frac = (int) (pos & 0xFFFFFL);
            int s0 = pcm[a];
            int s1 = pcm[a + 1];
            out[i] = (short) (s0 + (((s1 - s0) * (long) frac + 0x80000L) >> 20));
        }
        return out;
    }

    private static void store(String aid, Result result) {
        synchronized (RESULTS) {
            RESULTS.put(aid, result);
        }
    }

    /** Loads persisted scan results matching the current segment-list fingerprint. */
    private static void restoreOnce() {
        Context context = Utils.getContext();
        if (context == null) return;
        synchronized (RESULTS) {
            if (restored) return;
            restored = true;
        }
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.isFile()) return;
        Map<String, Result> loaded = new LinkedHashMap<>();
        try {
            JSONObject all = new JSONObject(new String(readFile(file), StandardCharsets.UTF_8));
            String fingerprint = fingerprint();
            int generation = stamp();
            Iterator<String> aids = all.keys();
            while (aids.hasNext()) {
                String aid = aids.next();
                JSONObject entry = all.getJSONObject(aid);
                if (!fingerprint.equals(entry.optString("fp"))) continue;
                List<AdMatcher.Match> matches = new ArrayList<>();
                JSONArray found = entry.getJSONArray("m");
                for (int i = 0; i < found.length(); i++) {
                    JSONArray m = found.getJSONArray(i);
                    AdSegment segment = segment(m.getLong(0));
                    if (segment == null) continue;
                    matches.add(new AdMatcher.Match(segment, m.getLong(1), m.getLong(2), m.getInt(3), m.getBoolean(4)));
                }
                loaded.put(aid, new Result(State.DONE, generation, matches, entry.getLong("s"), 0, null));
            }
        } catch (Exception unreadable) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return;
        }
        synchronized (RESULTS) {
            RESULTS.putAll(loaded);
        }
    }

    private static volatile boolean persistDirty;
    private static final Object PERSIST_FILE_LOCK = new Object();

    /** Schedules saving finished scans in the background after a 5-second quiet window. */
    private static void persist() {
        synchronized (RESULTS) {
            if (persistDirty) return;
            persistDirty = true;
        }
        Utils.runOnMainThreadDelayed(AdScanner::flushPersist, 5_000);
    }

    static void flushPersist() {
        synchronized (RESULTS) {
            if (!persistDirty) return;
            persistDirty = false;
        }
        Utils.runOnBackgroundThread(AdScanner::writePersistNow);
    }

    private static void writePersistNow() {
        Context context = Utils.getContext();
        if (context == null) return;
        String fingerprint = fingerprint();
        int generation = stamp();
        try {
            JSONObject all = new JSONObject();
            synchronized (RESULTS) {
                for (Map.Entry<String, Result> entry : RESULTS.entrySet()) {
                    Result result = entry.getValue();
                    if (result.state != State.DONE || result.generation != generation) continue;
                    JSONArray found = new JSONArray();
                    for (AdMatcher.Match m : result.matches) {
                        found.put(new JSONArray().put(m.segment.id).put(m.startMs).put(m.endMs).put(m.votes).put(m.partial));
                    }
                    all.put(entry.getKey(), new JSONObject().put("fp", fingerprint).put("s", result.scannedMs).put("m", found));
                }
            }
            byte[] bytes = all.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (PERSIST_FILE_LOCK) {
                android.util.AtomicFile atomic = new android.util.AtomicFile(new File(context.getFilesDir(), CACHE_FILE));
                FileOutputStream out = atomic.startWrite();
                try {
                    out.write(bytes);
                    out.flush();
                    atomic.finishWrite(out);
                } catch (IOException writeErr) {
                    atomic.failWrite(out);
                    throw writeErr;
                }
            }
        } catch (Exception failure) {
            Logger.printDebug(() -> "Ad skip: could not save scans: " + failure);
        }
    }

    private static String fingerprint() {
        StringBuilder text = new StringBuilder("a").append(ALGORITHM).append('|').append(stamp() % 16).append('|');
        for (AdSegment segment : AdSegmentStore.index().segments) {
            text.append(segment.id).append(':').append(segment.hashes.length).append(';');
        }
        return Integer.toHexString(text.toString().hashCode());
    }

    private static AdSegment segment(long id) {
        for (AdSegment segment : AdSegmentStore.all()) {
            if (segment.id == id) return segment;
        }
        return null;
    }

    private static byte[] readFile(File file) throws IOException {
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        try (InputStream in = atomic.openRead()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    static void clearAll() {
        synchronized (QUEUE) {
            for (Job job : QUEUE) job.cancel.set(true);
            for (Job job : RUNNING) job.cancel.set(true);
            QUEUE.clear();
            JOBS.clear();
        }
        synchronized (RESULTS) {
            RESULTS.clear();
        }
    }
}
