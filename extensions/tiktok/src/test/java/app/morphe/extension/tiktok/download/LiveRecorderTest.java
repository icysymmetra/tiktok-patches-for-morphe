package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * #37: the recorder's states. A recording starts when it is told to and ends one way, with an
 * outcome that says what the viewer was left with: a stop and leaving the room save the whole
 * recording, anything else saves a playable partial file or nothing, and no end claims a save it
 * didn't make.
 */
@RunWith(RobolectricTestRunner.class)
public class LiveRecorderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final LiveRecorder.Target TARGET = new LiveRecorder.Target(
            "https://pull.example.com/room.flv", LiveRecorder.Transport.FLV, "someone", "123", "HD");

    private static final class FakeClock implements LiveRecorder.Clock {
        volatile long now = 1_000_000;
        volatile long free = Long.MAX_VALUE / 4;

        @Override public long now() {
            return now;
        }

        @Override public long freeBytes(File directory) {
            return free;
        }
    }

    /** Writes what it is given, then ends the way the test says. */
    private static final class ScriptedSource implements LiveRecorder.Source {
        final byte[][] chunks;
        final IOException failure;
        final boolean blockUntilStopped;
        final CountDownLatch pumping = new CountDownLatch(1);
        final AtomicInteger closed = new AtomicInteger();

        ScriptedSource(IOException failure, boolean blockUntilStopped, byte[]... chunks) {
            this.chunks = chunks;
            this.failure = failure;
            this.blockUntilStopped = blockUntilStopped;
        }

        @Override public void pump(LiveRecorder.Output out, LiveRecorder.Control control) throws IOException {
            CountDownLatch released = new CountDownLatch(1);
            Closeable unblock = () -> {
                closed.incrementAndGet();
                released.countDown();
            };
            control.onStop(unblock);
            try {
                for (byte[] chunk : chunks) out.write(chunk, 0, chunk.length);
                pumping.countDown();
                if (failure != null) throw failure;
                if (blockUntilStopped) {
                    try {
                        released.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        throw new IOException(interrupted);
                    }
                    throw new IOException("Socket closed");
                }
            } finally {
                control.release(unblock);
            }
        }
    }

    private static final class Finish implements LiveRecorder.Finisher {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<byte[]> spoolBytes = new AtomicReference<>();
        final IOException failure;

        Finish(IOException failure) {
            this.failure = failure;
        }

        @Override public LiveRecorder.Published finish(File spool, LiveRecorder.Target target, long startedAtMs)
                throws IOException {
            calls.incrementAndGet();
            spoolBytes.set(Files.readAllBytes(spool.toPath()));
            if (failure != null) throw failure;
            if (!spool.delete()) throw new IOException("spool stuck");
            return new LiveRecorder.Published("live_someone.mp4", "handle");
        }
    }

    private static byte[] bytes(int count) {
        byte[] data = new byte[count];
        for (int i = 0; i < count; i++) data[i] = (byte) i;
        return data;
    }

    private File spool;
    private FakeClock clock;

    @Before public void setUp() throws IOException {
        Utils.setContext(RuntimeEnvironment.getApplication());
        spool = folder.newFile("live.flv");
        clock = new FakeClock();
    }

    private LiveRecorder.Result run(LiveRecorder.Source source, LiveRecorder.Finisher finisher, long maxBytes)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        AtomicInteger reports = new AtomicInteger();
        LiveRecorder recorder = new LiveRecorder(TARGET, spool, source, finisher, r -> {
            reports.incrementAndGet();
            result.set(r);
            done.countDown();
        }, clock, maxBytes);
        assertFalse(recorder.active());
        recorder.start();
        recorder.start();
        assertTrue("the recording ended", done.await(10, TimeUnit.SECONDS));
        assertTrue(recorder.join(5000));
        assertEquals("one report", 1, reports.get());
        assertFalse(recorder.active());
        assertFalse(recorder.recording());
        return result.get();
    }

    private LiveRecorder.Result stopWith(LiveRecorder.End why, Finish finish) throws Exception {
        ScriptedSource source = new ScriptedSource(null, true, bytes(10_000));
        LiveRecorder[] holder = new LiveRecorder[1];
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        holder[0] = new LiveRecorder(TARGET, spool, source, finish, r -> {
            result.set(r);
            done.countDown();
        }, clock, 1L << 30);
        holder[0].start();
        assertTrue(source.pumping.await(5, TimeUnit.SECONDS));
        holder[0].stop(why);
        // A second stop never changes how it ended.
        holder[0].stop(LiveRecorder.End.ERROR);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("the blocked read was closed", 1, source.closed.get());
        return result.get();
    }

    @Test public void tappingStopSavesTheWholeRecording() throws Exception {
        Finish finish = new Finish(null);
        LiveRecorder.Result result = stopWith(LiveRecorder.End.STOPPED, finish);
        assertEquals(LiveRecorder.Outcome.SAVED, result.outcome);
        assertEquals(LiveRecorder.End.STOPPED, result.end);
        assertEquals("live_someone.mp4", result.fileName);
        assertEquals("handle", result.saved);
        assertEquals(1, finish.calls.get());
        assertEquals(10_000, finish.spoolBytes.get().length);
        assertFalse("the finisher took the spool", spool.exists());
    }

    @Test public void leavingTheRoomSavesWhatWasRecorded() throws Exception {
        LiveRecorder.Result result = stopWith(LiveRecorder.End.LEFT_ROOM, new Finish(null));
        assertEquals(LiveRecorder.Outcome.SAVED, result.outcome);
        assertEquals(LiveRecorder.End.LEFT_ROOM, result.end);
    }

    @Test public void theAppClosingSavesAPartialFileAndSaysWhy() throws Exception {
        LiveRecorder.Result result = stopWith(LiveRecorder.End.APP_EXIT, new Finish(null));
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.outcome);
        assertEquals(LiveRecorder.End.APP_EXIT, result.end);
        assertNotNull(result.fileName);
    }

    @Test public void aDroppedConnectionSavesAPartialFile() throws Exception {
        ScriptedSource source = new ScriptedSource(new IOException("Connection reset"), false, bytes(8_000));
        Finish finish = new Finish(null);
        LiveRecorder.Result result = run(source, finish, 1L << 30);
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.outcome);
        assertEquals(LiveRecorder.End.DISCONNECTED, result.end);
        assertEquals(8_000, finish.spoolBytes.get().length);
    }

    @Test public void aStreamThatEndsByItselfSavesAPartialFile() throws Exception {
        ScriptedSource source = new ScriptedSource(null, false, bytes(5_000), bytes(5_000));
        Finish finish = new Finish(null);
        LiveRecorder.Result result = run(source, finish, 1L << 30);
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.outcome);
        assertEquals(LiveRecorder.End.DISCONNECTED, result.end);
        assertEquals(10_000, finish.spoolBytes.get().length);
    }

    @Test public void aConnectionThatNeverDeliveredAnythingLeavesNothingBehind() throws Exception {
        ScriptedSource source = new ScriptedSource(new IOException("Could not connect"), false);
        Finish finish = new Finish(null);
        LiveRecorder.Result result = run(source, finish, 1L << 30);
        assertEquals(LiveRecorder.Outcome.NOTHING_SAVED, result.outcome);
        assertEquals(LiveRecorder.End.DISCONNECTED, result.end);
        assertNull(result.fileName);
        assertEquals("nothing to publish", 0, finish.calls.get());
        assertFalse("the empty spool was removed", spool.exists());
    }

    @Test public void runningOutOfRoomMidwayKeepsAPlayablePartialFile() throws Exception {
        // Room for the start, then the phone fills up as the recording grows.
        clock.free = 64L * 1024 * 1024 + 2 * 3_000_000;
        byte[] megabyte = bytes(1024 * 1024);
        ScriptedSource source = new ScriptedSource(null, false, megabyte, megabyte, megabyte, megabyte, megabyte);
        Finish finish = new Finish(null);
        LiveRecorder.Result result = run(source, finish, 1L << 30);
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.outcome);
        assertEquals(LiveRecorder.End.STORAGE, result.end);
        assertTrue("it stopped before the whole stream", finish.spoolBytes.get().length < 5 * megabyte.length);
        assertTrue(finish.spoolBytes.get().length >= megabyte.length);
    }

    @Test public void notEnoughRoomToStartSavesNothingAndSaysSo() throws Exception {
        clock.free = 10L * 1024 * 1024;
        ScriptedSource source = new ScriptedSource(null, false, bytes(5_000));
        Finish finish = new Finish(null);
        LiveRecorder.Result result = run(source, finish, 1L << 30);
        assertEquals(LiveRecorder.Outcome.NOTHING_SAVED, result.outcome);
        assertEquals(LiveRecorder.End.STORAGE, result.end);
        assertEquals(0, finish.calls.get());
        assertFalse(spool.exists());
    }

    @Test public void theSizeLimitEndsTheRecordingWithAPlayablePartialFile() throws Exception {
        ScriptedSource source = new ScriptedSource(null, false, bytes(6_000), bytes(6_000), bytes(6_000));
        Finish finish = new Finish(null);
        LiveRecorder.Result result = run(source, finish, 14_000);
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.outcome);
        assertEquals(LiveRecorder.End.SIZE_LIMIT, result.end);
        assertEquals("only whole writes within the limit", 12_000, finish.spoolBytes.get().length);
    }

    @Test public void aStreamThatCantBeSavedEndsAsNothingWithoutPublishing() throws Exception {
        ScriptedSource source = new ScriptedSource(new FlvReader.UnsupportedStream("This LIVE uses a video format that can't be saved"),
                false, bytes(6_000));
        Finish finish = new Finish(null);
        LiveRecorder.Result result = run(source, finish, 1L << 30);
        assertEquals(LiveRecorder.Outcome.NOTHING_SAVED, result.outcome);
        assertEquals(LiveRecorder.End.UNSUPPORTED, result.end);
        assertEquals(0, finish.calls.get());
        assertFalse(spool.exists());
    }

    @Test public void aPublishThatFailsIsReportedAsFailedAndCleanedUp() throws Exception {
        Finish finish = new Finish(new IOException("Not enough free space for this media"));
        LiveRecorder.Result result = stopWith(LiveRecorder.End.STOPPED, finish);
        assertEquals(LiveRecorder.Outcome.FAILED, result.outcome);
        assertNull("no false save", result.fileName);
        assertNull(result.saved);
        assertFalse("the leftovers were removed", spool.exists());
        assertTrue(result.detail.contains("free space"));
    }

    @Test public void aRecordingWithNoPlayableVideoIsNothingNotASave() throws Exception {
        LiveRecorder.Finisher none = (file, target, started) -> {
            throw new LiveRemux.NothingRecorded("No video was recorded");
        };
        ScriptedSource source = new ScriptedSource(null, true, bytes(10_000));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        LiveRecorder recorder = new LiveRecorder(TARGET, spool, source, none, r -> {
            result.set(r);
            done.countDown();
        }, clock, 1L << 30);
        recorder.start();
        assertTrue(source.pumping.await(5, TimeUnit.SECONDS));
        recorder.stop(LiveRecorder.End.STOPPED);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(LiveRecorder.Outcome.NOTHING_SAVED, result.get().outcome);
        assertNull(result.get().fileName);
        assertFalse(spool.exists());
    }

    @Test public void theElapsedTimeRunsFromStartAndFreezesWhenTheStreamStops() throws Exception {
        ScriptedSource source = new ScriptedSource(null, true, bytes(10_000));
        CountDownLatch done = new CountDownLatch(1);
        LiveRecorder recorder = new LiveRecorder(TARGET, spool, source, new Finish(null), r -> done.countDown(),
                clock, 1L << 30);
        assertEquals(0, recorder.elapsedMs());
        recorder.start();
        assertTrue(source.pumping.await(5, TimeUnit.SECONDS));
        clock.now += 65_000;
        assertEquals(65_000, recorder.elapsedMs());
        recorder.stop(LiveRecorder.End.STOPPED);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        long frozen = recorder.elapsedMs();
        clock.now += 30_000;
        assertEquals("the clock stopped with the stream", frozen, recorder.elapsedMs());
        assertTrue(frozen >= 65_000);
    }

    @Test public void stoppingBeforeAnythingWasStartedDoesNothing() {
        LiveRecorder recorder = new LiveRecorder(TARGET, spool,
                (out, control) -> { }, new Finish(null), r -> { }, clock, 1L << 30);
        recorder.stop(LiveRecorder.End.STOPPED);
        assertFalse(recorder.active());
        assertFalse(recorder.recording());
    }
}
