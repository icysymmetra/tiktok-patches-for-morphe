/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.os.Process;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.AdAudioSource;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens to feed videos for the marked ads, on two background threads, before and while they
 * play. The video on screen goes to the front of the queue, the ones the feed has bound either
 * side of it go behind, and each result is kept per video id until the list of ads changes.
 */
final class AdScanner {
    /** Long videos are scanned up to here; ads sit in the first minutes. */
    static final long MAX_SCAN_MS = 5L * 60 * 1000;
    private static final int CACHE = 300;
    private static final int MAX_QUEUE = 8;
    /** Retimed copies tried when the first pass finds nothing: sped up and slowed down. */
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
    private static int workers;
    private static final int MAX_WORKERS = 2;
    private static volatile Listener listener;

    private static final class Job {
        final String aid;
        final WeakReference<Object> aweme;
        final AtomicBoolean cancel = new AtomicBoolean();

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
        synchronized (RESULTS) {
            Result r = RESULTS.get(aid);
            if (r != null && r.generation != AdSegmentStore.generation() && r.state == State.DONE) return null;
            return r;
        }
    }

    static void forget(String aid) {
        synchronized (RESULTS) {
            RESULTS.remove(aid);
        }
    }

    /** Asks for a scan. {@code urgent} for the video on screen, which jumps the queue. */
    static void request(Object aweme, boolean urgent) {
        String aid = Reflect.string(aweme, "getAid", "aid");
        if (aid == null) return;
        if (AdSegmentStore.index().isEmpty()) return;
        synchronized (RESULTS) {
            Result r = RESULTS.get(aid);
            if (r != null && (r.state == State.QUEUED || r.state == State.RUNNING)) {
                if (urgent) promote(aid);
                return;
            }
            if (r != null && r.state == State.DONE && r.generation == AdSegmentStore.generation()) return;
            if (r != null && r.state == State.FAILED && r.generation == AdSegmentStore.generation() && !urgent) return;
            RESULTS.put(aid, new Result(State.QUEUED, AdSegmentStore.generation(), Collections.emptyList(), 0, 0, null));
        }
        if (isPhotoPost(aweme)) {
            store(aid, new Result(State.DONE, AdSegmentStore.generation(), Collections.emptyList(), 0, 0, null));
            return;
        }
        Job job = new Job(aid, aweme);
        synchronized (QUEUE) {
            if (urgent) QUEUE.addFirst(job);
            else QUEUE.addLast(job);
            while (QUEUE.size() > MAX_QUEUE) {
                Job dropped = QUEUE.removeLast();
                synchronized (RESULTS) {
                    RESULTS.remove(dropped.aid);
                }
            }
            if (workers < MAX_WORKERS) {
                workers++;
                Utils.runOnOwnThread("metra-adskip", AdScanner::work);
            }
        }
    }

    private static void promote(String aid) {
        synchronized (QUEUE) {
            for (Job job : QUEUE) {
                if (job.aid.equals(aid)) {
                    QUEUE.remove(job);
                    QUEUE.addFirst(job);
                    return;
                }
            }
        }
    }

    private static boolean isPhotoPost(Object aweme) {
        Object images = Reflect.invoke(aweme, "getImageInfos");
        if (images instanceof List<?> && !((List<?>) images).isEmpty()) return true;
        return Reflect.invoke(aweme, "getPhotoModeImageInfo") != null;
    }

    private static void work() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
            // Not fatal.
        }
        while (true) {
            Job job;
            synchronized (QUEUE) {
                job = QUEUE.pollFirst();
                if (job == null) {
                    workers--;
                    return;
                }
            }
            run(job);
        }
    }

    private static void run(Job job) {
        Object aweme = job.aweme.get();
        int generation = AdSegmentStore.generation();
        if (aweme == null) {
            forget(job.aid);
            return;
        }
        store(job.aid, new Result(State.RUNNING, generation, Collections.emptyList(), 0, 0, null));
        long started = System.currentTimeMillis();
        try {
            Context context = Utils.getContext();
            if (context == null) throw new IllegalStateException("No context");
            AdAudioSource.Pcm pcm = AdAudioSource.load(context, aweme, Landmarks.SAMPLE_RATE, MAX_SCAN_MS, job.cancel);
            List<AdMatcher.Match> matches = scan(pcm.samples, pcm.length);
            long took = System.currentTimeMillis() - started;
            Result result = new Result(State.DONE, generation, matches, pcm.durationMs(), took, null);
            store(job.aid, result);
            AdSkipLog.add(job.aid, (matches.isEmpty()
                    ? "no ad in " + pcm.durationMs() / 1000 + "s"
                    : "found " + matches) + ", " + pcm.downloadedBytes / 1024 + " KB " + (AdAudioSource.separateSound(aweme) ? "dash" : "muxed") + " in " + took + "ms");
            Listener l = listener;
            if (l != null) l.scanned(job.aid, result);
            if (!matches.isEmpty()) Utils.runOnMainThread(AdSkipBar::refresh);
        } catch (Throwable failure) {
            long took = System.currentTimeMillis() - started;
            store(job.aid, new Result(State.FAILED, generation, Collections.emptyList(), 0, took,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            AdSkipLog.add(job.aid, "scan failed: " + failure.getMessage());
            Logger.printDebug(() -> "Ad skip: could not scan " + job.aid, failure instanceof Exception ? (Exception) failure : null);
        }
    }

    /** All ads in this PCM: the plain pass, then retimed copies when that found nothing. */
    static List<AdMatcher.Match> scan(short[] pcm, int length) {
        AdMatcher.Index index = AdSegmentStore.index();
        AdMatcher.Strictness strictness = AdMatcher.Strictness.of(Settings.AD_SKIP_STRICTNESS.get());
        Landmarks.Print print = Landmarks.compute(pcm, length);
        List<AdMatcher.Match> found = AdMatcher.match(index, print, strictness);
        if (!found.isEmpty() || !Settings.AD_SKIP_RETIMED.get()) return found;
        for (float rate : RATES) {
            short[] stretched = stretch(pcm, length, rate);
            List<AdMatcher.Match> retimed = AdMatcher.match(index, Landmarks.compute(stretched, stretched.length), strictness);
            if (retimed.isEmpty()) continue;
            List<AdMatcher.Match> mapped = new ArrayList<>();
            for (AdMatcher.Match m : retimed) {
                mapped.add(new AdMatcher.Match(m.segment, Math.round(m.startMs / rate),
                        Math.round(m.endMs / rate), m.votes, m.partial));
            }
            return mapped;
        }
        return found;
    }

    /**
     * The sound made {@code rate} times as long, pitch with it, which undoes a copy that was
     * sped up (or slowed down) by that much: its landmarks line up with the ad's again.
     */
    static short[] stretch(short[] pcm, int length, float rate) {
        int outLength = (int) (length * (double) rate);
        short[] out = new short[Math.max(0, outLength)];
        for (int i = 0; i < out.length; i++) {
            double at = i / (double) rate;
            int a = (int) at;
            if (a + 1 >= length) {
                out[i] = pcm[Math.max(0, Math.min(length - 1, a))];
                continue;
            }
            double t = at - a;
            out[i] = (short) Math.round(pcm[a] * (1 - t) + pcm[a + 1] * t);
        }
        return out;
    }

    private static void store(String aid, Result result) {
        synchronized (RESULTS) {
            RESULTS.put(aid, result);
        }
    }

    static void clearAll() {
        synchronized (QUEUE) {
            for (Job job : QUEUE) job.cancel.set(true);
            QUEUE.clear();
        }
        synchronized (RESULTS) {
            RESULTS.clear();
        }
    }
}
