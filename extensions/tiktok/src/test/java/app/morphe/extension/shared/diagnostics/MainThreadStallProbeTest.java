package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;

public class MainThreadStallProbeTest {
    private static final class Scene {
        volatile long time;
        boolean active = true;
        final List<String> events = new ArrayList<>();
        final List<Runnable> pending = new ArrayList<>();
        final MainThreadStallProbe.Probe probe;
        Scene(Thread main) { this(main, callback -> {}); }
        Scene(Thread main, Consumer<Runnable> backend) {
            probe = new MainThreadStallProbe.Probe(() -> time, () -> active,
                    callback -> { pending.add(callback); backend.accept(callback); }, main, events::add);
        }
        void tick(long at) { time = at; probe.tick(); }
        void acknowledge() {
            List<Runnable> copy = new ArrayList<>(pending);
            pending.clear();
            for (Runnable runnable : copy) runnable.run();
        }
        long samples() { return events.stream().filter(x -> x.contains("sample=")).count(); }
    }

    @Test public void samplesAnActuallyBlockedThreadBeforeItRecovers() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        LinkedBlockingQueue<Runnable> mainQueue = new LinkedBlockingQueue<>();
        Thread victim = new Thread(() -> {
            blocked.countDown();
            try {
                release.await();
                while (!Thread.currentThread().isInterrupted()) mainQueue.take().run();
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "test-ui");
        victim.start();
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (victim.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(Thread.State.WAITING, victim.getState());
            // The victim stays blocked while the same worker-side tick used on a phone samples it.
            Scene s = new Scene(victim, mainQueue::add);
            s.tick(0); s.tick(499);
            assertEquals(0, s.samples());
            s.tick(500);
            assertEquals(1, s.samples());
            assertTrue(s.events.get(0), s.events.get(0).contains("CountDownLatch.await"));
            assertEquals("only one outstanding heartbeat", 1, s.pending.size());
            s.tick(1000); s.tick(1500); s.tick(2000);
            assertEquals(MainThreadStallProbe.MAX_PER_STALL, s.samples());
            s.time = 2000;
            release.countDown();
            CountDownLatch drained = new CountDownLatch(1);
            mainQueue.add(drained::countDown);
            assertTrue("main thread runs its queued heartbeat after recovery", drained.await(2, TimeUnit.SECONDS));
            s.tick(2100);
            assertTrue(s.events.toString(), s.events.stream().anyMatch(x -> x.contains("recovered")));
        } finally {
            release.countDown(); victim.interrupt(); victim.join(2000);
        }
        assertFalse("test victim released", victim.isAlive());
    }

    @Test public void aResponsiveQueueHasNoStallEvenWhenTheTimerRunsLate() {
        Scene s = new Scene(Thread.currentThread());
        s.tick(0); s.time = 5; s.acknowledge();
        s.tick(5000); s.time = 5005; s.acknowledge(); s.tick(9000);
        assertEquals(0, s.samples());
        assertTrue(s.events.isEmpty());
    }

    @Test public void stopAndExpiryPreventSamplesAndFurtherHeartbeats() {
        Scene stopped = new Scene(Thread.currentThread());
        stopped.tick(0); stopped.probe.stop(); stopped.tick(9000);
        assertEquals(0, stopped.samples());
        assertEquals(1, stopped.pending.size());
        Scene expired = new Scene(Thread.currentThread());
        expired.tick(0); expired.active = false; expired.tick(9000);
        assertEquals(0, expired.samples());
        assertEquals(1, expired.pending.size());
    }

    @Test public void samplesAreBoundedAcrossRepeatedStallsAndStackDepthIsCapped() {
        Scene s = new Scene(Thread.currentThread());
        for (int stall = 0; stall < 20; stall++) {
            long start = stall * 3000L;
            s.tick(start); s.tick(start + 500); s.tick(start + 1000); s.tick(start + 1500);
            s.time = start + 2000; s.acknowledge(); s.tick(start + 2100);
        }
        assertEquals(MainThreadStallProbe.MAX_SAMPLES, s.samples());
        for (String event : s.events) if (event.contains("sample=")) {
            assertTrue(event.split("\\n").length <= MainThreadStallProbe.MAX_FRAMES + 1);
        }
        s.probe.finish(); s.probe.finish();
        assertEquals(1, s.events.stream().filter(x -> x.contains("summary")).count());
    }

    @Test public void snapshotRaceWithAcknowledgementDoesNotReportAnExpiredPing() {
        Scene s = new Scene(Thread.currentThread());
        s.tick(0); s.time = 1; s.acknowledge(); s.tick(1000);
        assertEquals(0, s.samples());
        assertEquals(1, s.pending.size());
    }

    @Test public void aSlowAcknowledgementWithoutASnapshotIsReportedWithoutInventingAStack() {
        Scene s = new Scene(Thread.currentThread());
        s.tick(0); s.time = 900; s.acknowledge(); s.tick(1000);
        assertEquals(0, s.samples());
        assertEquals(1, s.events.size());
        assertTrue(s.events.get(0).contains("late acknowledgement after 900ms; no in-flight snapshot"));
    }

    @Test public void anUnavailableLogSinkStopsSamplingWithoutEscapingIntoTheWorker() {
        long[] clock = {0};
        MainThreadStallProbe.Probe p = new MainThreadStallProbe.Probe(() -> clock[0], () -> true,
                callback -> {}, Thread.currentThread(), message -> { throw new IllegalStateException("test sink"); });
        assertTrue(p.tick());
        clock[0] = 500; p.tick();
        assertFalse(p.tick());
        p.finish();
    }

    @Test public void finishingAccountsForTheLastAcknowledgementBeforeTheTimerTicksAgain() {
        Scene s = new Scene(Thread.currentThread());
        s.tick(0); s.time = 900; s.acknowledge(); s.probe.stop(); s.probe.finish();
        assertTrue(s.events.toString(), s.events.get(s.events.size() - 1).contains("longestAcknowledged=900ms"));
    }

    @Test public void sharePhasesAndSamplesHaveSeparateBounds() {
        MainThreadStallProbe.Trace trace = new MainThreadStallProbe.Trace(100);
        trace.record("sample=1 waiting=500ms", 600);
        for (int i = 0; i < 1000; i++) trace.mark("model:start", true, 1, 700 + i);
        assertEquals(32, trace.phases.size());
        assertTrue(trace.lines().toString().contains("sample=1"));
        for (int i = 0; i < 100; i++) trace.record("sample=" + i + " " + "x".repeat(9000), 2000 + i);
        assertTrue(trace.chars <= MainThreadStallProbe.Trace.MAX_SAMPLE_CHARS);
        assertTrue(trace.samples.size() <= 40);
    }
}
