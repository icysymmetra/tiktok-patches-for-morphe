package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import app.morphe.extension.shared.Utils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * Saving media runs on a pool with a hard cap on what may run and what may wait. Without one a
 * long press held down a few times too often would start a download per press. Nothing tested
 * the cap, so a queue quietly resized would have shown up as a phone running out of memory.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class MediaJobSchedulerTest {
    private final List<CountDownLatch> released = new ArrayList<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The pool is process-wide, so an earlier test's jobs would eat this one's capacity.
        drain();
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        for (CountDownLatch latch : released) latch.countDown();
        released.clear();
        drain();
    }

    private static void drain() throws Exception {
        for (int wait = 0; wait < 250; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("the media pool never emptied: running="
                + MediaJobScheduler.runningJobs() + " queued=" + MediaJobScheduler.queuedJobs());
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Holds every worker until the returned latch opens, and waits until all three are held. */
    private CountDownLatch holdWorkers() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        released.add(hold);
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            assertTrue(MediaJobScheduler.submit("hold " + index, () -> {
                started.countDown();
                await(hold);
            }));
        }
        assertTrue("the media workers never started", started.await(5, TimeUnit.SECONDS));
        return hold;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    private static void waitFor(AtomicInteger counter, int value) throws Exception {
        for (int wait = 0; wait < 500 && counter.get() < value; wait++) Thread.sleep(10);
        assertEquals(value, counter.get());
    }

    /**
     * Offers more work than the pool can hold and counts what it takes. The pool belongs to the
     * process and every test shares it, and a job finishing frees a slot at any moment, so how
     * deep the queue is at a given instant is not something to assert on. What holds either way
     * is that the pool stops saying yes somewhere at or below its cap, and that everything it did
     * say yes to runs.
     */
    @Test public void theQueueStopsAcceptingAtItsCap() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        released.add(hold);
        AtomicInteger ran = new AtomicInteger();

        int capacity = MediaJobScheduler.MAX_RUNNING_JOBS + MediaJobScheduler.MAX_QUEUED_JOBS;
        int accepted = 0;
        boolean refused = false;
        for (int job = 0; job <= capacity; job++) {
            boolean submitted = MediaJobScheduler.submit("job-" + job, () -> {
                ran.incrementAndGet();
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            if (!submitted) {
                refused = true;
                break;
            }
            accepted++;
        }

        assertTrue("the pool refused the very first job", accepted > 0);
        assertTrue("the pool took " + accepted + " jobs, past its cap of " + capacity,
                accepted <= capacity);
        assertTrue("the pool never refused anything, so nothing caps it", refused);

        hold.countDown();
        int expected = accepted;
        for (int wait = 0; wait < 250 && ran.get() < expected; wait++) Thread.sleep(20);
        assertEquals("a job the pool accepted never ran", expected, ran.get());
    }

    /**
     * A full line: every waiting save knows how far back it is, the one past the cap is refused
     * with its cleanup run once and its name let go, and the same save asked for again once
     * there is room is taken and runs.
     */
    @Test public void aSaturatedLineCountsWhatIsAheadAndARefusalLeavesNothingBehind() throws Exception {
        CountDownLatch hold = holdWorkers();
        AtomicInteger ended = new AtomicInteger();
        List<MediaJobScheduler.Job> waiting = new ArrayList<>();
        for (int index = 0; index < MediaJobScheduler.MAX_QUEUED_JOBS; index++) {
            MediaJobScheduler.Job job = MediaJobScheduler.submit("wait " + index, "wait " + index,
                    () -> { }, ended::incrementAndGet);
            assertNotNull("a free queue slot refused a save", job);
            assertTrue(job.waiting());
            assertEquals("the saves this one has to wait for", index + 1, job.ahead);
            waiting.add(job);
        }

        AtomicInteger refusedEnded = new AtomicInteger();
        AtomicInteger refusedRan = new AtomicInteger();
        assertNull("the save past the cap was taken", MediaJobScheduler.submit("refused", "refused",
                refusedRan::incrementAndGet, refusedEnded::incrementAndGet));
        idle();
        assertEquals("the refusal's cleanup did not run exactly once", 1, refusedEnded.get());
        assertEquals("Too many media saves are already running. Try again in a moment.",
                ShadowToast.getTextOfLatestToast());
        assertNull("the refused save kept its name", MediaJobScheduler.job("refused"));
        assertEquals("Still saving the last one", MediaJobScheduler.busyMessage("refused"));
        assertEquals("The last one is still waiting to start", MediaJobScheduler.busyMessage("wait 0"));

        hold.countDown();
        waitFor(ended, MediaJobScheduler.MAX_QUEUED_JOBS);
        for (MediaJobScheduler.Job job : waiting) assertFalse(job.waiting());
        drain();

        MediaJobScheduler.Job retry = MediaJobScheduler.submit("refused", "refused",
                refusedRan::incrementAndGet, refusedEnded::incrementAndGet);
        assertNotNull("the retry was refused with the line empty", retry);
        assertEquals("a free worker has nothing ahead of it", 0, retry.ahead);
        waitFor(refusedEnded, 2);
        assertEquals("the retry did not run", 1, refusedRan.get());
    }

    /** Cancel takes a waiting save out of line once; after that, and once a worker has it, it can't. */
    @Test public void cancellingAWaitingSaveEndsItOnceAndItNeverRuns() throws Exception {
        CountDownLatch hold = holdWorkers();
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.Job job = MediaJobScheduler.submit("cancel me", "cancel me",
                ran::incrementAndGet, ended::incrementAndGet);
        assertNotNull(job);
        assertSame(job, MediaJobScheduler.job("cancel me"));
        assertEquals(1, MediaJobScheduler.queuedJobs());

        assertTrue(job.cancel());
        assertFalse("a second cancel ended the job again", job.cancel());
        assertEquals(1, ended.get());
        assertEquals("the cancelled save still holds a queue slot", 0, MediaJobScheduler.queuedJobs());
        assertNull(MediaJobScheduler.job("cancel me"));

        AtomicInteger laterRan = new AtomicInteger();
        AtomicInteger laterEnded = new AtomicInteger();
        MediaJobScheduler.Job later = MediaJobScheduler.submit("later", null,
                laterRan::incrementAndGet, laterEnded::incrementAndGet);
        assertEquals("a cancelled save still counted as ahead", 1, later.ahead);
        hold.countDown();
        waitFor(laterEnded, 1);
        drain();
        assertEquals("a cancelled save ran anyway", 0, ran.get());
        assertEquals(1, ended.get());
        assertEquals(1, laterRan.get());
        assertFalse("a save that already ran was cancelled", later.cancel());
        assertEquals(1, laterEnded.get());
    }

    /** A save that throws is still over: its cleanup runs once and the worker takes the next one. */
    @Test public void aSaveThatThrowsStillEndsOnceAndTheNextOneRuns() throws Exception {
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.Job failing = MediaJobScheduler.submit("throws", "throws", () -> {
            throw new IllegalStateException("a save that went wrong in a way nobody expected");
        }, ended::incrementAndGet);
        assertNotNull(failing);
        waitFor(ended, 1);
        drain();
        assertNull(MediaJobScheduler.job("throws"));

        AtomicInteger next = new AtomicInteger();
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS + 1; index++) {
            assertTrue(MediaJobScheduler.submit("after " + index, next::incrementAndGet));
        }
        waitFor(next, MediaJobScheduler.MAX_RUNNING_JOBS + 1);
        assertEquals("the failed save's cleanup ran again", 1, ended.get());
    }

    /**
     * Cancel and a worker reach the same waiting save at the same moment, over and over. Exactly
     * one of them wins each time: the save either runs or it doesn't, and its cleanup runs once.
     */
    @Test public void cancelAndStartRacingEndEachSaveExactlyOnce() throws Exception {
        int rounds = 150;
        int cancelledRounds = 0;
        for (int round = 0; round < rounds; round++) {
            CountDownLatch hold = holdWorkers();
            AtomicInteger ran = new AtomicInteger();
            AtomicInteger ended = new AtomicInteger();
            MediaJobScheduler.Job job = MediaJobScheduler.submit("race " + round, null,
                    ran::incrementAndGet, ended::incrementAndGet);
            assertNotNull(job);
            boolean[] cancelled = {false};
            CountDownLatch go = new CountDownLatch(1);
            // A few microseconds' lead to one side or the other, so both of them get to win.
            long lead = (round % 10) * 20_000L;
            boolean cancelWaits = round % 2 == 0;
            Thread canceller = new Thread(() -> {
                await(go);
                if (cancelWaits) spin(lead);
                cancelled[0] = job.cancel();
            });
            canceller.start();
            go.countDown();
            if (!cancelWaits) spin(lead);
            hold.countDown();
            canceller.join(5000);
            drain();
            for (int wait = 0; wait < 200 && ended.get() == 0; wait++) Thread.sleep(5);
            assertEquals("round " + round + " ended " + ended.get() + " times", 1, ended.get());
            assertEquals("round " + round + ": ran and cancelled must not agree",
                    cancelled[0] ? 0 : 1, ran.get());
            if (cancelled[0]) cancelledRounds++;
        }
        // Not asserted, since the scheduler decides the race: just kept visible for the record.
        System.out.println("cancel won " + cancelledRounds + " of " + rounds + " races");
    }

    /** The brief word a save without a row gets: its own when it starts now, the wait when it doesn't. */
    @Test public void theAcknowledgementNamesTheWaitOnlyWhenThereIsOne() throws Exception {
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.Job now = MediaJobScheduler.submit("now", null, () -> { }, ended::incrementAndGet);
        MediaJobScheduler.acknowledge(now, "Saving the sound", "Saving the sound");
        idle();
        assertEquals("Saving the sound", ShadowToast.getTextOfLatestToast());
        waitFor(ended, 1);
        drain();

        CountDownLatch hold = holdWorkers();
        ShadowToast.reset();
        MediaJobScheduler.Job first = MediaJobScheduler.submit("first", null, () -> { }, ended::incrementAndGet);
        MediaJobScheduler.acknowledge(first, "Saving the sound", "Saving the sound");
        idle();
        assertEquals("Saving the sound\nStarts after one other save", ShadowToast.getTextOfLatestToast());
        MediaJobScheduler.Job second = MediaJobScheduler.submit("second", null, () -> { }, ended::incrementAndGet);
        MediaJobScheduler.acknowledge(second, null, "Waiting to save video");
        idle();
        assertEquals("Waiting to save video\nStarts after 2 other saves", ShadowToast.getTextOfLatestToast());

        ShadowToast.reset();
        MediaJobScheduler.acknowledge(null, "Saving the sound", "Saving the sound");
        idle();
        assertEquals("a refused save was acknowledged as taken", 0, ShadowToast.shownToastCount());
        hold.countDown();
        waitFor(ended, 3);
    }
}
