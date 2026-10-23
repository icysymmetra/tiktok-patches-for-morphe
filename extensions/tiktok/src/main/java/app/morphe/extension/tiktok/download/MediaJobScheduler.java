/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One bounded queue for every extension-owned media operation. */
final class MediaJobScheduler {
    static final int MAX_RUNNING_JOBS = 3;
    static final int MAX_QUEUED_JOBS = 8;

    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "Metra-MediaJob");
        thread.setDaemon(true);
        return thread;
    };
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            MAX_RUNNING_JOBS,
            MAX_RUNNING_JOBS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_JOBS),
            THREAD_FACTORY,
            new ThreadPoolExecutor.AbortPolicy());
    /** Accepted jobs that are not over yet, waiting or running: how far back a new one starts. */
    private static final AtomicInteger ADMITTED = new AtomicInteger();
    /**
     * The live job under each key a saver named, so a second request can say what the first is
     * doing. Typed to the concrete class, not {@link Map}: its two-argument {@code remove} is a
     * plain method on {@link ConcurrentHashMap} since API 1, but a default method on {@code Map}
     * since API 24, and a call resolves by the reference's declared type, not the object's.
     */
    private static final ConcurrentHashMap<String, Job> BY_KEY = new ConcurrentHashMap<>();

    private MediaJobScheduler() {}

    /**
     * One accepted save, from its place in line to its end. It waits, then runs, then is over,
     * or it is cancelled while it still waits. Whichever way it goes, its {@code done} runs
     * exactly once after: that is where a saver lets go of what it held for the save (its id,
     * its busy flag, its button). It says the job is over, not that it worked; the work reports
     * its own result, since a save that returns normally may still have saved nothing.
     */
    static final class Job implements Runnable {
        private static final int WAITING = 0;
        private static final int RUNNING = 1;
        private static final int OVER = 2;

        final String label;
        final String key;
        /** Saves that had to end before this one could start, counted as it was accepted. */
        final int ahead;
        private final Runnable work;
        private final Runnable done;
        /** What this save leaves on disk for the start after a closed TikTok; null when nothing does. */
        final SaveRecords.Record record;
        private final AtomicInteger state = new AtomicInteger(WAITING);

        private Job(String label, String key, Runnable work, Runnable done, int ahead,
                SaveRecords.Record record) {
            this.label = label;
            this.key = key;
            this.work = work;
            this.done = done;
            this.ahead = ahead;
            this.record = record;
        }

        /** True until a worker takes it, and false for good once it is cancelled. */
        boolean waiting() {
            return state.get() == WAITING;
        }

        /**
         * Takes a job that is still waiting out of the line and ends it. False once a worker has
         * it: a transfer already under way is not stopped from here.
         */
        boolean cancel() {
            if (!state.compareAndSet(WAITING, OVER)) return false;
            EXECUTOR.remove(this);
            end();
            return true;
        }

        @Override public void run() {
            // A cancel that won the race has already ended the job, done included.
            if (!state.compareAndSet(WAITING, RUNNING)) return;
            // A file this save publishes is written down against its record as it lands.
            SaveRecords.Record outer = SaveRecords.enter(record);
            try {
                MediaBudget.runWithJobDeadline(work);
            } catch (RuntimeException failure) {
                // The saver says what went wrong for anything it expects. What it didn't expect
                // must not take TikTok down with the worker thread.
                Logger.printException(() -> "Media job failed for " + label, failure);
            } finally {
                SaveRecords.exit(outer);
                state.set(OVER);
                end();
            }
        }

        private void end() {
            ADMITTED.decrementAndGet();
            if (key != null) BY_KEY.remove(key, this);
            try {
                if (done != null) done.run();
            } catch (RuntimeException failure) {
                Logger.printException(() -> "Media job cleanup failed for " + label, failure);
            } finally {
                // Over, whichever way: the record closes whether the save worked or not.
                SaveRecords.close(record);
            }
        }
    }

    static boolean submit(String label, Runnable work) {
        return submit(label, null, work, null) != null;
    }

    /**
     * Queues {@code work}, or refuses it when the line is full. A refusal says so and runs
     * {@code done} before returning null, so the caller's cleanup is the same either way.
     *
     * @param key names what is being saved, so {@link #busyMessage} can find it; null for none
     */
    static Job submit(String label, String key, Runnable work, Runnable done) {
        return submit(label, key, 1, work, done);
    }

    /**
     * As above, for a save of {@code files} files, which is what the start after a closed TikTok
     * counts it against when some of them never landed.
     */
    static Job submit(String label, String key, int files, Runnable work, Runnable done) {
        if (work == null) throw new NullPointerException("work");
        int before = ADMITTED.getAndIncrement();
        Job job = new Job(label, key, work, done, Math.max(0, before - MAX_RUNNING_JOBS + 1),
                SaveRecords.open(label, files));
        if (key != null) BY_KEY.put(key, job);
        try {
            EXECUTOR.execute(job);
            SaveRecords.accepted(job.record);
            return job;
        } catch (RejectedExecutionException error) {
            job.state.set(Job.OVER);
            job.end();
            Logger.printException(() -> "Media job queue is full for " + label, error);
            Utils.showToastLong(L10n.t("Too many media saves are already running. Try again in a moment."));
            return null;
        }
    }

    /**
     * The word an accepted save gets at once, for a save with no progress row of its own.
     * {@code starting} is said when a worker is free, and null says nothing for a save whose
     * own start speaks a moment later. A save that has to wait says {@code waiting} and how
     * many saves are ahead of it, so a quiet minute doesn't read as a tap that did nothing.
     */
    static void acknowledge(Job job, String starting, String waiting) {
        if (job == null) return;
        if (job.ahead == 0) {
            if (starting != null) Utils.showToastShort(starting);
            return;
        }
        String line = L10n.quantity(Utils.getContext(), job.ahead,
                "Starts after one other save", "Starts after %1$s other saves");
        // Two lines, each translated whole: what the save is, then what it waits for.
        String text = waiting == null ? line : waiting + "\n" + line;
        Utils.showToastShort(text);
    }

    /** What a second request for something already accepted is told: what the first is doing. */
    static String busyMessage(String key) {
        Job job = key == null ? null : BY_KEY.get(key);
        return job != null && job.waiting()
                ? L10n.t("The last one is still waiting to start")
                : L10n.t("Still saving the last one");
    }

    /** The live job saved under {@code key}, or null. */
    static Job job(String key) {
        return BY_KEY.get(key);
    }

    static int queuedJobs() {
        return EXECUTOR.getQueue().size();
    }

    static int runningJobs() {
        return EXECUTOR.getActiveCount();
    }

    /**
     * Every accepted job is over, its cleanup included. The two counts above alone miss a job
     * the executor handed to a worker thread that hasn't started yet: it is in neither.
     */
    static boolean idle() {
        return ADMITTED.get() == 0 && EXECUTOR.getActiveCount() == 0 && EXECUTOR.getQueue().isEmpty();
    }

}
