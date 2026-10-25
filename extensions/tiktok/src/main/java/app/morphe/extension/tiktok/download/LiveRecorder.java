/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Records one LIVE, from the moment {@link #start} is called until it is stopped, the stream drops,
 * the storage runs short or the size limit is reached. Whatever was recorded is turned into an MP4
 * and published, and the end is reported once through the {@link Listener} with an outcome that says
 * exactly what the viewer ended up with. A recording never reports a save it didn't make.
 *
 * <p>The bytes come from a {@link Source} into a spool file in the app's cache, which survives a
 * crash and is rewritten into an MP4 by the {@link Finisher} when the recording ends. Everything
 * runs on one thread of its own, never the main thread.
 */
public final class LiveRecorder {
    /** How the stream is pulled. */
    public enum Transport {
        FLV,
        HLS
    }

    /** Why a recording ended. */
    public enum End {
        /** The viewer tapped Stop. */
        STOPPED,
        /** The viewer left the room, or the room changed to another LIVE. */
        LEFT_ROOM,
        /** The stream ended or the connection dropped. */
        DISCONNECTED,
        /** The phone is running out of room. */
        STORAGE,
        /** The recording reached the largest size it may be. */
        SIZE_LIMIT,
        /** The stream is in a format that can't be saved. */
        UNSUPPORTED,
        /** TikTok is closing. */
        APP_EXIT,
        /** Something else went wrong. */
        ERROR
    }

    /** What the viewer ended up with. */
    public enum Outcome {
        /** The whole recording, as far as the viewer took it. */
        SAVED,
        /** A playable file that stops short, because of {@link Result#end}. */
        SAVED_PARTIAL,
        /** Nothing worth keeping; the leftovers were removed. */
        NOTHING_SAVED,
        /** There was a recording but it could not be published; the leftovers were removed. */
        FAILED
    }

    /** What to record. */
    public static final class Target {
        public final String url;
        public final Transport transport;
        /** The creator's handle, for the file name; may be null. */
        public final String creator;
        /** The room, to tell one LIVE from the next; may be null. */
        public final String room;
        /** The quality the file is of, for the notice; may be null. */
        public final String quality;

        public Target(String url, Transport transport, String creator, String room, String quality) {
            this.url = url;
            this.transport = transport;
            this.creator = creator;
            this.room = room;
            this.quality = quality;
        }
    }

    /** How a recording ended. */
    public static final class Result {
        public final Outcome outcome;
        public final End end;
        public final long durationMs;
        /** The name the file was saved under; null unless something was saved. */
        public final String fileName;
        /** What the publisher handed back, for opening the file; null unless something was saved. */
        public final Object saved;
        /** A short English reason when something went wrong; may be null. */
        public final String detail;

        Result(Outcome outcome, End end, long durationMs, String fileName, Object saved, String detail) {
            this.outcome = outcome;
            this.end = end;
            this.durationMs = durationMs;
            this.fileName = fileName;
            this.saved = saved;
            this.detail = detail;
        }
    }

    public interface Listener {
        /** Called once, on the recorder's own thread. */
        void onFinished(Result result);
    }

    /** Where the spool's bytes are written. */
    interface Output {
        void write(byte[] bytes, int offset, int length) throws IOException;
    }

    /** What a {@link Source} can ask of the recorder. */
    interface Control {
        boolean stopRequested();

        /** Registers something to close when the recording is stopped, to unblock a read. */
        void onStop(Closeable closeable);

        /** Forgets something registered with {@link #onStop}, once it is closed. */
        void release(Closeable closeable);

        /** Waits, returning false at once if the recording is stopped meanwhile. */
        boolean pause(long millis);
    }

    /** Pulls the stream and writes it to {@code out} until it ends, fails or is stopped. */
    interface Source {
        void pump(Output out, Control control) throws IOException;
    }

    /** What a finished spool is turned into. */
    interface Finisher {
        /** Publishes the spool; returns the file name and what to open it with. */
        Published finish(File spool, Target target, long startedAtMs) throws IOException;
    }

    static final class Published {
        final String name;
        final Object saved;

        Published(String name, Object saved) {
            this.name = name;
            this.saved = saved;
        }
    }

    interface Clock {
        long now();

        long freeBytes(File directory);
    }

    /** The spool ran out of room or hit its size limit. */
    static final class SpoolFull extends IOException {
        final End reason;

        SpoolFull(End reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private static final Clock SYSTEM = new Clock() {
        @Override public long now() {
            return System.currentTimeMillis();
        }

        @Override public long freeBytes(File directory) {
            return directory.getUsableSpace();
        }
    };

    /** How much room must stay free beyond what the rewrite and publish will need. */
    static final long RESERVE_BYTES = 64L * 1024 * 1024;
    /** The spool is checked for room each time this many bytes have gone in. */
    static final long CHECK_EVERY_BYTES = 1024L * 1024;
    /** A recording shorter than this has nothing worth saving. */
    static final long MIN_USEFUL_BYTES = 4096;

    private final Target target;
    private final File spoolFile;
    private final Source source;
    private final Finisher finisher;
    private final Listener listener;
    private final Clock clock;
    private final long maxBytes;

    private final Object lock = new Object();
    private final List<Closeable> closeOnStop = new ArrayList<>();
    private volatile End requested;
    private volatile boolean started;
    private volatile boolean finished;
    private volatile boolean pumping;
    private volatile long startedAtMs;
    private volatile long endedAtMs;
    private Thread thread;

    LiveRecorder(Target target, File spoolFile, Source source, Finisher finisher, Listener listener,
            Clock clock, long maxBytes) {
        this.target = target;
        this.spoolFile = spoolFile;
        this.source = source;
        this.finisher = finisher;
        this.listener = listener;
        this.clock = clock == null ? SYSTEM : clock;
        this.maxBytes = maxBytes;
    }

    LiveRecorder(Target target, File spoolFile, Source source, Finisher finisher, Listener listener) {
        this(target, spoolFile, source, finisher, listener, SYSTEM, MediaBudget.MAX_LIVE_RECORDING_BYTES);
    }

    public Target target() {
        return target;
    }

    /** True from {@link #start} until the recording has been published, failed or discarded. */
    public boolean active() {
        return started && !finished;
    }

    /** True while the stream is being pulled, which ends as soon as a stop is asked for. */
    public boolean recording() {
        return pumping && requested == null;
    }

    /** Milliseconds recorded so far, frozen when the stream stops coming in. */
    public long elapsedMs() {
        if (!started) return 0;
        long end = endedAtMs != 0 ? endedAtMs : clock.now();
        return Math.max(0, end - startedAtMs);
    }

    /** Begins recording now. Does nothing if it was started already. */
    public void start() {
        synchronized (lock) {
            if (started) return;
            started = true;
            startedAtMs = clock.now();
            pumping = true;
            thread = new Thread(this::run, "hushfeed-live-record");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }
    }

    /**
     * Asks the recording to end for {@code why}. The first reason sticks. What was recorded is still
     * published on the recorder's thread, so this returns at once.
     */
    public void stop(End why) {
        List<Closeable> toClose;
        synchronized (lock) {
            if (requested != null || finished) return;
            requested = why == null ? End.STOPPED : why;
            toClose = new ArrayList<>(closeOnStop);
            closeOnStop.clear();
            lock.notifyAll();
        }
        for (Closeable closeable : toClose) {
            try {
                closeable.close();
            } catch (IOException | RuntimeException ignored) {
                // Closing is only to unblock a read.
            }
        }
    }

    /** Waits for the recorder's thread to finish. For tests and for app exit. */
    boolean join(long millis) throws InterruptedException {
        Thread running = thread;
        if (running == null) return true;
        running.join(millis);
        return !running.isAlive();
    }

    private final Control control = new Control() {
        @Override public boolean stopRequested() {
            return requested != null;
        }

        @Override public void onStop(Closeable closeable) {
            boolean closeNow;
            synchronized (lock) {
                closeNow = requested != null;
                if (!closeNow) closeOnStop.add(closeable);
            }
            if (closeNow) {
                try {
                    closeable.close();
                } catch (IOException | RuntimeException ignored) {
                    // Same as above.
                }
            }
        }

        @Override public void release(Closeable closeable) {
            synchronized (lock) {
                closeOnStop.remove(closeable);
            }
        }

        @Override public boolean pause(long millis) {
            long until = clock.now() + millis;
            synchronized (lock) {
                while (requested == null) {
                    long left = until - clock.now();
                    if (left <= 0) return true;
                    try {
                        lock.wait(left);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        }
    };

    private void run() {
        End end = null;
        String detail = null;
        Spool spool = null;
        try {
            spool = new Spool(spoolFile);
            source.pump(spool, control);
            end = requested != null ? requested : End.DISCONNECTED;
        } catch (SpoolFull full) {
            end = full.reason;
            detail = full.getMessage();
        } catch (FlvReader.UnsupportedStream unsupported) {
            end = End.UNSUPPORTED;
            detail = unsupported.getMessage();
        } catch (IOException | RuntimeException failure) {
            if (requested != null) {
                end = requested;
            } else {
                end = End.DISCONNECTED;
                detail = failure.getMessage();
            }
            if (requested == null) Logger.printDebug(() -> "LIVE recording source ended: " + failure);
        } finally {
            endedAtMs = clock.now();
            pumping = false;
            if (spool != null) {
                try {
                    spool.close();
                } catch (IOException ignored) {
                    // The bytes that made it are what the file is built from.
                }
            }
        }
        // The stream may have ended by itself while a stop was racing it; the stop then explains it.
        if (requested != null && end == End.DISCONNECTED) end = requested;
        Result result = conclude(end, detail, spool == null ? 0 : spool.bytes());
        finished = true;
        try {
            listener.onFinished(result);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not report the end of a LIVE recording", failure);
        }
    }

    private Result conclude(End end, String detail, long bytes) {
        long duration = Math.max(0, endedAtMs - startedAtMs);
        if (bytes < MIN_USEFUL_BYTES || end == End.UNSUPPORTED) {
            discard();
            return new Result(Outcome.NOTHING_SAVED, end, duration, null, null, detail);
        }
        try {
            Published published = finisher.finish(spoolFile, target, startedAtMs);
            boolean whole = end == End.STOPPED || end == End.LEFT_ROOM;
            return new Result(whole ? Outcome.SAVED : Outcome.SAVED_PARTIAL, end, duration,
                    published.name, published.saved, detail);
        } catch (LiveRemux.NothingRecorded nothing) {
            discard();
            return new Result(Outcome.NOTHING_SAVED, end, duration, null, null, nothing.getMessage());
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "Could not save a LIVE recording", failure);
            discard();
            return new Result(Outcome.FAILED, end, duration, null, null, failure.getMessage());
        }
    }

    private void discard() {
        if (spoolFile != null && spoolFile.exists() && !spoolFile.delete()) {
            Logger.printInfo(() -> "Could not remove a LIVE recording's spool");
        }
    }

    /** The spool file, with the limits that keep a recording from filling the phone. */
    private final class Spool implements Output, Closeable {
        private final FileOutputStream stream;
        private final File directory;
        private long bytes;
        private long checkedAt = -CHECK_EVERY_BYTES;

        Spool(File file) throws IOException {
            File parent = file.getAbsoluteFile().getParentFile();
            this.directory = parent == null ? file : parent;
            this.stream = new FileOutputStream(file);
            try {
                checkRoom();
            } catch (IOException noRoom) {
                stream.close();
                throw noRoom;
            }
        }

        long bytes() {
            return bytes;
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (bytes + length > maxBytes) {
                throw new SpoolFull(End.SIZE_LIMIT, "The recording reached its size limit");
            }
            if (bytes - checkedAt >= CHECK_EVERY_BYTES) checkRoom();
            stream.write(data, offset, length);
            bytes += length;
        }

        /** Room for this much again to rewrite it, and for the same again to publish it. */
        private void checkRoom() throws IOException {
            checkedAt = bytes;
            long free = clock.freeBytes(directory);
            if (free <= 0) return;
            if (free < 2 * bytes + RESERVE_BYTES) {
                throw new SpoolFull(End.STORAGE, "The phone is running out of room");
            }
        }

        @Override public void close() throws IOException {
            stream.close();
        }
    }
}
