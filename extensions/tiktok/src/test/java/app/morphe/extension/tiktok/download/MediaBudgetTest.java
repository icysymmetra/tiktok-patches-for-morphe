package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/** Resource limits that must hold when media work is retried or nested. */
public class MediaBudgetTest {
    @Test public void retryAfterPreservesServerDelaysWithoutOverflowingMilliseconds() {
        assertEquals(Long.MAX_VALUE,
                MediaBudget.retryAfterMillis("9223372036854775807", 0));
        assertEquals(Long.MAX_VALUE, MediaBudget.retryAfterMillis("99999999999999999999999999999", 0));
        assertEquals(1_000L, MediaBudget.retryAfterMillis("1", 0));
        assertEquals(60_000L, MediaBudget.retryAfterMillis("60", 0));
        assertEquals(0L, MediaBudget.retryAfterMillis("0", 0));
    }

    @Test public void allHttpDateFormsAndLeapSecondsUseTheSameDelay() {
        long now = 784111777000L;
        for (String header : new String[]{"Sun, 06 Nov 1994 08:50:37 GMT",
                "Sunday, 06-Nov-94 08:50:37 GMT", "Sun Nov  6 08:50:37 1994"}) {
            assertEquals(header, 60_000L, MediaBudget.retryAfterMillis(header, 0, now));
            assertEquals("a past date waited again", 0L, MediaBudget.retryAfterMillis(header, 0, now + 120_000L));
        }
        assertEquals(0L, MediaBudget.retryAfterMillis("Sunday, 06-Nov-94 08:50:37 GMT", 0,
                java.time.Instant.parse("2026-10-04T00:00:00Z").toEpochMilli()));
        assertEquals(60_000L, MediaBudget.retryAfterMillis("Sat, 31 Dec 2016 23:59:60 GMT", 0,
                java.time.Instant.parse("2016-12-31T23:59:00Z").toEpochMilli()));
        for (String header : new String[]{null, "", "-1", "+1", "1.5", "tomorrow",
                "Sun, 31 Nov 1994 08:50:37 GMT", "Sun, 06 Nov 1994 08:50:37 GMT junk"}) {
            assertEquals(String.valueOf(header), 250L, MediaBudget.retryAfterMillis(header, 0, now));
        }
    }

    @Test public void retryWaitUsesElapsedTimeEvenWhenWallTimeChanges() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(120_000)) {
            clock.onSleep = () -> clock.wall += 86_400_000L;
            MediaBudget.waitBeforeRetry("Sun, 06 Nov 1994 08:50:37 GMT", 0, clock.deadline);
            assertEquals(60_000_000_000L, clock.nanos);
            assertEquals(600, clock.sleeps);
            assertEquals(30_000, MediaBudget.timeoutMillis(clock.deadline, 30_000));
            assertEquals(60_000, MediaBudget.timeoutMillis(clock.deadline, 90_000));
        }
    }

    @Test public void anUnfinishableServerWaitStopsImmediatelyAndRemainsTerminal() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(50_000)) {
            var refusal = assertThrows(MediaBudget.StopException.class,
                    () -> MediaBudget.waitBeforeRetry("60", 0, clock.deadline));
            assertEquals(MediaBudget.StopException.Reason.SERVER_WAIT, refusal.reason);
            assertEquals(0, clock.sleeps);
            assertSame(refusal, assertThrows(MediaBudget.StopException.class,
                    () -> MediaBudget.waitBeforeRetry("0", 0, clock.deadline)));
            // Already-downloaded files can still be published within the remaining disk/time budget.
            MediaBudget.check(clock.deadline);
        }
    }

    @Test public void cancellationDuringAServerWaitDoesNotConsumeTheAdvertisedMinute() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(120_000)) {
            clock.deadline.cancellation = new java.util.concurrent.atomic.AtomicBoolean();
            clock.onSleep = () -> clock.deadline.cancellation.set(true);
            var refusal = assertThrows(MediaBudget.StopException.class,
                    () -> MediaBudget.waitBeforeRetry("60", 0, clock.deadline));
            assertEquals(MediaBudget.StopException.Reason.CANCELLED, refusal.reason);
            assertEquals(100_000_000L, clock.nanos);
        }
    }

    @Test public void socketTimeoutsRetry() {
        SocketTimeoutException timeout = new SocketTimeoutException("read timed out");
        assertTrue(MediaBudget.isRetryableTransport(timeout));
    }

    /**
     * Android 17's {@code ENABLE_DEFAULT_ENCRYPTED_CLIENT_HELLO}, and the Certificate
     * Transparency change beside it, both arrive as a handshake the platform refused. Retrying
     * one would hammer a host that is going to refuse every time, so the transport retries a
     * timeout and a refused connection and nothing else, and names what it got. This is the
     * device-independent half of that verdict; {@code AndroidTargetChangesGuardTest} carries the
     * rest of the target 37 audit.
     */
    @Test public void aRefusedHandshakeIsNotRetried() {
        assertFalse(MediaBudget.isRetryableTransport(
                new SSLHandshakeException("Certificate Transparency or ECH refused")));
        assertFalse(MediaBudget.isRetryableTransport(
                new IOException("wrapped", new SSLHandshakeException("refused"))));
        assertTrue(MediaBudget.isRetryableTransport(
                new IOException("wrapped", new SocketTimeoutException("slow"))));
        assertTrue(MediaBudget.isRetryableTransport(
                new IOException("wrapped", new ConnectException("refused connection"))));
    }

    @Test public void nestedMediaOperationsReuseTheRunningJobDeadline() {
        MediaBudget.runWithJobDeadline(() -> {
            MediaBudget.Deadline first = MediaBudget.deadline();
            MediaBudget.runWithJobDeadline(() -> assertSame(first, MediaBudget.deadline()));
        });
    }

    @Test public void knownLengthReservationStillIncludesPublishAndFreeSpace() throws Exception {
        long transfer = 5L * 1024 * 1024;
        long required = transfer + MediaBudget.PUBLISH_OVERHEAD_BYTES
                + MediaBudget.MIN_FREE_BYTES;
        AtomicLong free = new AtomicLong(required);
        File volume = new File("known-length-volume") {
            @Override public boolean exists() {
                return true;
            }

            @Override public long getUsableSpace() {
                return free.get();
            }
        };

        MediaBudget.checkDiskSpace(volume, transfer);
        free.decrementAndGet();
        assertThrows(IOException.class, () -> MediaBudget.checkDiskSpace(volume, transfer));
    }

    @Test public void transferHeadersKeepTheExact512MbCap() throws Exception {
        MediaBudget.checkTransferLength(MediaBudget.MAX_TRANSFER_BYTES);
        IOException failure = assertThrows(IOException.class, () ->
                MediaBudget.checkTransferLength(MediaBudget.MAX_TRANSFER_BYTES + 1));
        assertEquals("Media file is larger than the 512 MB limit", failure.getMessage());
    }

    @Test public void streamedCopyChecksTheDeadlineBeforeReading() throws Exception {
        var constructor = MediaBudget.Deadline.class.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        MediaBudget.Deadline expired = constructor.newInstance(System.nanoTime() - 1);
        AtomicInteger reads = new AtomicInteger();
        InputStream input = new InputStream() {
            @Override public int read() {
                reads.incrementAndGet();
                return 1;
            }
        };

        IOException failure = assertThrows(IOException.class, () -> MediaFileWriter.copy(
                input, new ByteArrayOutputStream(), MediaBudget.MAX_TRANSFER_BYTES,
                expired, null));
        assertEquals("Media job deadline exceeded", failure.getMessage());
        assertEquals("the expired copy read from its source", 0, reads.get());
    }

    /** A source that hands out {@code chunk} bytes per read, each read taking {@code gapMs}. */
    private static InputStream slowSource(MediaTransportFixtures.RetryClock clock, int reads,
            int chunk, long gapMs) {
        return new InputStream() {
            int left = reads;

            @Override public int read() {
                throw new UnsupportedOperationException();
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                if (left-- <= 0) return -1;
                clock.nanos += gapMs * 1_000_000L;
                return Math.min(chunk, length);
            }
        };
    }

    @Test public void aDownloadStillMovingOutlastsTheTwoMinutes() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(MediaBudget.JOB_DEADLINE_MS)) {
            // Five minutes of a slow connection (#179): a chunk every ten seconds.
            long copied = MediaFileWriter.copy(slowSource(clock, 30, 1000, 10_000),
                    new ByteArrayOutputStream(), MediaBudget.MAX_TRANSFER_BYTES, clock.deadline, null);
            assertEquals(30_000L, copied);
            assertEquals(300_000_000_000L, clock.nanos);
        }
    }

    @Test public void aDownloadThatStallsPastTheStallWindowStillStops() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(MediaBudget.JOB_DEADLINE_MS)) {
            var refusal = assertThrows(MediaBudget.StopException.class, () -> MediaFileWriter.copy(
                    slowSource(clock, 100, 1000, MediaBudget.STALL_MS + 1_000),
                    new ByteArrayOutputStream(), MediaBudget.MAX_TRANSFER_BYTES, clock.deadline, null));
            assertEquals(MediaBudget.StopException.Reason.TIME, refusal.reason);
            assertTrue("stopped before the two minutes were up", clock.nanos >= MediaBudget.JOB_DEADLINE_MS * 1_000_000L);
            assertTrue("ran on past one stall", clock.nanos <= (MediaBudget.JOB_DEADLINE_MS + MediaBudget.STALL_MS + 1_000) * 1_000_000L);
        }
    }

    @Test public void aTrickleStopsAtTheCeilingAndTheNextFileGetsItsOwn() throws Exception {
        try (var clock = new MediaTransportFixtures.RetryClock(MediaBudget.JOB_DEADLINE_MS)) {
            var refusal = assertThrows(MediaBudget.StopException.class, () -> MediaFileWriter.copy(
                    slowSource(clock, 1_000, 1, 10_000), new ByteArrayOutputStream(),
                    MediaBudget.MAX_TRANSFER_BYTES, clock.deadline, null));
            assertEquals(MediaBudget.StopException.Reason.TIME, refusal.reason);
            assertEquals(MediaBudget.MOVING_CEILING_MS * 1_000_000L, clock.nanos);

            // The next file of a save starts its own time (#27) and its own ceiling.
            clock.deadline.allowAtLeast(MediaBudget.JOB_DEADLINE_MS);
            assertEquals(300L, MediaFileWriter.copy(slowSource(clock, 30, 10, 10_000),
                    new ByteArrayOutputStream(), MediaBudget.MAX_TRANSFER_BYTES, clock.deadline, null));
        }
    }
}
