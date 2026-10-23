/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared transfer, disk and deadline limits for extension-owned media jobs. */
final class MediaBudget {
    static final long MAX_TRANSFER_BYTES = 512L * 1024 * 1024;
    static final long MIN_FREE_BYTES = 32L * 1024 * 1024;
    static final long UNKNOWN_TRANSFER_RESERVATION_BYTES = 64L * 1024 * 1024;
    static final long PUBLISH_OVERHEAD_BYTES = 8L * 1024 * 1024;
    static final long STREAM_SPACE_CHECK_BYTES = 1024L * 1024;
    static final long JOB_DEADLINE_MS = 2L * 60 * 1000;
    /** How long a download may go without a byte once the job's own time is up. */
    static final long STALL_MS = 30_000L;
    /** The most a download that keeps moving may run: 50 MB at about 40 KB a second. */
    static final long MOVING_CEILING_MS = 20L * 60 * 1000;
    static final int MAX_ATTEMPTS_PER_MIRROR = 2;
    static final long DEFAULT_RETRY_DELAY_MS = 250L;
    static final long MAX_RETRY_DELAY_MS = 2_000L;
    private static final ThreadLocal<Deadline> CURRENT_DEADLINE = new ThreadLocal<>();
    interface Clock {
        long nanoTime();
        long wallMillis();
        void sleep(long millis) throws InterruptedException;
    }
    private static final Clock SYSTEM_CLOCK = new Clock() {
        public long nanoTime() { return System.nanoTime(); }
        public long wallMillis() { return System.currentTimeMillis(); }
        public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
    };

    private MediaBudget() {}

    static Deadline deadline() {
        Deadline current = CURRENT_DEADLINE.get();
        if (current != null) return current;
        return new Deadline(SYSTEM_CLOCK.nanoTime() + JOB_DEADLINE_MS * 1_000_000L);
    }

    static void runWithJobDeadline(Runnable work) {
        runWithJobDeadline(null, work);
    }

    /** As above, with {@code fresh} (a new one when null) for a job that has no deadline yet. */
    static void runWithJobDeadline(Deadline fresh, Runnable work) {
        Deadline previous = CURRENT_DEADLINE.get();
        if (previous == null) CURRENT_DEADLINE.set(fresh != null ? fresh : deadline());
        try {
            work.run();
        } finally {
            if (previous == null) CURRENT_DEADLINE.remove();
            else CURRENT_DEADLINE.set(previous);
        }
    }

    /**
     * A refusal that ends a save of several files rather than skipping one: no room on the disk
     * for any of them, or the job's time is up. Still an IOException to every single-file caller.
     */
    static final class StopException extends IOException {
        enum Reason { SPACE, TIME, SERVER_WAIT, CANCELLED }
        final Reason reason;
        final boolean space;

        StopException(String message, boolean space) {
            this(message, space ? Reason.SPACE : Reason.TIME);
        }

        StopException(String message, Reason reason) {
            super(message);
            this.reason = reason;
            this.space = reason == Reason.SPACE;
        }
    }

    static void check(Deadline deadline) throws IOException {
        if (deadline == null) deadline = CURRENT_DEADLINE.get();
        if (deadline != null && deadline.expired()) {
            throw new StopException("Media job deadline exceeded", false);
        }
    }

    /** {@link Deadline#moved} on {@code deadline}, or on the running job's when it is null. */
    static void moved(Deadline deadline) {
        Deadline active = deadline != null ? deadline : CURRENT_DEADLINE.get();
        if (active != null) active.moved();
    }

    static int timeoutMillis(Deadline deadline, int configuredMillis) throws IOException {
        check(deadline);
        Deadline active = deadline == null ? CURRENT_DEADLINE.get() : deadline;
        if (active == null) return configuredMillis;
        long remainingMs = Math.max(1L,
                (active.endNanos - active.clock.nanoTime() + 999_999L) / 1_000_000L);
        return (int) Math.min(configuredMillis, Math.min(Integer.MAX_VALUE, remainingMs));
    }

    static boolean isRetryableTransport(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }

    static void checkTransferLength(long length) throws IOException {
        if (length > MAX_TRANSFER_BYTES) {
            throw new IOException("Media file is larger than the 512 MB limit");
        }
    }

    static void checkDiskSpace(File directory, long transferBytes) throws IOException {
        checkDiskSpace(directory, transferBytes, null);
    }

    /** The most a recorded LIVE may be: a recording runs far past what a post's download does. */
    static final long MAX_LIVE_RECORDING_BYTES = 2L * 1024 * 1024 * 1024;

    static void checkDiskSpace(File directory, long transferBytes, Deadline deadline) throws IOException {
        checkDiskSpace(directory, transferBytes, deadline, MAX_TRANSFER_BYTES);
    }

    static void checkDiskSpace(File directory, long transferBytes, Deadline deadline, long maxBytes)
            throws IOException {
        check(deadline);
        if (transferBytes > maxBytes) {
            throw new IOException("Media file is larger than the " + (maxBytes >> 20) + " MB limit");
        }
        long free = usableSpace(directory);
        if (free <= 0) return;
        long estimate = transferBytes < 0
                ? UNKNOWN_TRANSFER_RESERVATION_BYTES
                : transferBytes;
        long required = estimate + PUBLISH_OVERHEAD_BYTES + MIN_FREE_BYTES;
        if (free < required) {
            throw new StopException("Not enough free space for this media", true);
        }
    }

    /**
     * Grants at most one streaming window while preserving the publish and free-space reserve.
     * The caller must check again before it writes more than {@link #STREAM_SPACE_CHECK_BYTES}.
     */
    static void checkStreamingDiskSpace(File directory, Deadline deadline) throws IOException {
        check(deadline);
        long free = usableSpace(directory);
        if (free <= 0) return;
        long required = STREAM_SPACE_CHECK_BYTES + PUBLISH_OVERHEAD_BYTES + MIN_FREE_BYTES;
        if (free < required) {
            throw new StopException("Not enough free space for this media", true);
        }
    }

    private static long usableSpace(File directory) {
        if (directory == null) return 0;
        // A destination that does not exist yet answers zero, which is not "no quota": the
        // publish step is handed DCIM/TikTok before the first save ever creates it, and that
        // is exactly the copy the reservation is for. The nearest existing ancestor is on the
        // same volume and answers for it.
        File existing = directory;
        while (existing != null && !existing.exists()) existing = existing.getParentFile();
        return existing == null ? 0 : existing.getUsableSpace();
    }

    static boolean isTransientStatus(int status) {
        return status == 408 || status == 425 || status == 429
                || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static long retryAfterMillis(String header, int attempt) {
        return retryAfterMillis(header, attempt, SYSTEM_CLOCK.wallMillis());
    }

    static long retryAfterMillis(String header, int attempt, long nowMillis) {
        long fallback = Math.min(MAX_RETRY_DELAY_MS,
                DEFAULT_RETRY_DELAY_MS << Math.min(3, Math.max(0, attempt)));
        long advertised = serverDelay(header, nowMillis);
        return advertised < 0 ? fallback : advertised;
    }

    /** RFC 9110 permits delta-seconds and all three HTTP-date forms. */
    private static long serverDelay(String header, long nowMillis) {
        if (header == null) return -1;
        String value = header.trim();
        if (value.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(value);
                return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
            } catch (NumberFormatException overflow) {
                return Long.MAX_VALUE;
            }
        }
        if (value.length() > 128) return -1;
        // DateFormat rejects leap seconds. Parse the preceding second, then advance once.
        boolean leapSecond = value.matches(".*:[0-5][0-9]:60 (GMT|[0-9]{4})");
        String parsedValue = leapSecond ? value.replace(":60 ", ":59 ") : value;
        TimeZone utc = TimeZone.getTimeZone("GMT");
        for (String pattern : new String[]{"EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", "EEE MMM d HH:mm:ss yyyy"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(utc);
            format.setLenient(false);
            // A two-digit year more than 50 years ahead means the preceding century.
            Calendar century = Calendar.getInstance(utc, Locale.US);
            century.setTimeInMillis(nowMillis);
            century.add(Calendar.YEAR, -50);
            format.set2DigitYearStart(century.getTime());
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(parsedValue, position);
            if (date == null || position.getIndex() != parsedValue.length()) continue;
            long target = date.getTime() + (leapSecond ? 1000 : 0);
            if (target <= nowMillis) return 0;
            long delay = target - nowMillis;
            return delay < 0 ? Long.MAX_VALUE : delay;
        }
        return -1;
    }

    static void waitBeforeRetry(String retryAfter, int attempt, Deadline deadline) throws IOException {
        if (deadline == null) deadline = deadline();
        if (!deadline.retryPending && deadline.networkStop == null) {
            recordRetryAfter(retryAfter, deadline);
            if (!deadline.retryPending && deadline.networkStop == null) {
                scheduleRetry(retryAfterMillis(null, attempt), false, deadline);
            }
        }
        awaitRetry(deadline);
    }

    /** Record once at receipt, including successful responses and redirects. */
    static void recordRetryAfter(String header, Deadline deadline) {
        long delay = serverDelay(header, deadline.clock.wallMillis());
        if (delay >= 0) scheduleRetry(delay, true, deadline);
    }

    private static void scheduleRetry(long delay, boolean advertised, Deadline deadline) {
        long start = deadline.clock.nanoTime();
        if (delay > Long.MAX_VALUE / 1_000_000L || delay * 1_000_000L >= deadline.endNanos - start) {
            deadline.networkStop = new StopException(advertised
                    ? "Server retry delay exceeds the remaining media job time" : "Media job deadline exceeded",
                    advertised ? StopException.Reason.SERVER_WAIT : StopException.Reason.TIME);
            return;
        }
        deadline.retryAtNanos = start + delay * 1_000_000L;
        deadline.retryPending = true;
    }

    /** Called only after the previous response has been released, before another network request. */
    static void awaitRetry(Deadline deadline) throws IOException {
        check(deadline);
        if (deadline.networkStop != null) throw deadline.networkStop;
        if (!deadline.retryPending) return;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()
                        || (deadline.cancellation != null && deadline.cancellation.get())) {
                    deadline.networkStop = new StopException("Media retry cancelled", StopException.Reason.CANCELLED);
                    throw deadline.networkStop;
                }
                check(deadline);
                long remaining = deadline.retryAtNanos - deadline.clock.nanoTime();
                if (remaining <= 0) {
                    deadline.retryPending = false;
                    return;
                }
                deadline.clock.sleep(Math.min(100L, (remaining + 999_999L) / 1_000_000L));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            deadline.networkStop = new StopException("Media retry interrupted", StopException.Reason.CANCELLED);
            deadline.networkStop.initCause(error);
            throw deadline.networkStop;
        }
    }

    static final class Deadline {
        private long endNanos;
        private long ceilingNanos;
        private final Clock clock;
        AtomicBoolean cancellation;
        StopException networkStop;
        boolean retryPending;
        long retryAtNanos;

        private Deadline(long endNanos) {
            this(endNanos, SYSTEM_CLOCK);
        }

        Deadline(long endNanos, Clock clock) {
            this.endNanos = endNanos;
            this.clock = clock;
            this.ceilingNanos = clock.nanoTime() + MOVING_CEILING_MS * 1_000_000L;
        }

        boolean expired() {
            return endNanos - clock.nanoTime() <= 0;
        }

        /**
         * Moves the end out to at least {@code millis} from now, and leaves a later end alone.
         * For a step whose length follows the media rather than the network: writing a caption
         * into a video encodes all of it again, which runs about as long as the video plays,
         * and a long one used up the two minutes the whole save gets. That step still has its
         * own checks for a codec that stops moving. It also starts a fresh
         * {@link MediaBudget#MOVING_CEILING_MS} for {@link #moved}, so each file of a save gets its own.
         */
        void allowAtLeast(long millis) {
            long now = clock.nanoTime();
            long end = now + Math.max(0L, millis) * 1_000_000L;
            if (end - endNanos > 0) endNanos = end;
            long ceiling = now + MOVING_CEILING_MS * 1_000_000L;
            if (ceiling - ceilingNanos > 0) ceilingNanos = ceiling;
        }

        /**
         * Called as a download's bytes arrive: one that is still moving gets {@link MediaBudget#STALL_MS}
         * past its latest bytes, up to {@link MediaBudget#MOVING_CEILING_MS}. The fixed two minutes cut a
         * long video off on a slow connection while it was still coming in (#179). A download
         * that stops for that long, or trickles on past the ceiling, still runs out: bytes that
         * turn up after the end don't bring it back.
         */
        void moved() {
            if (expired()) return;
            long end = clock.nanoTime() + STALL_MS * 1_000_000L;
            if (end - ceilingNanos > 0) end = ceilingNanos;
            if (end - endNanos > 0) endNanos = end;
        }
    }
}
