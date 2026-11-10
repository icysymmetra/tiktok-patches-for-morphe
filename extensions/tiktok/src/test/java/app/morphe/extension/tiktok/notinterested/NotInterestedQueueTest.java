/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.notinterested;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Looper;

import app.morphe.extension.shared.BackgroundPoolSaturation;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Method;

/**
 * The shared worker pool is bounded and refuses work when it is full. A refused task never runs,
 * so nothing releases the in-flight flag the button sets before handing the work over, and the
 * button is dead for the rest of the process: one "Sending feedback" that never resolves, then
 * silence on every later tap.
 *
 * <p>The same refusal was already handled in the settings backup and the Lab; this is the third
 * caller of the same contract and the one that was missed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NotInterestedQueueTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REFUSED = "Couldn't send the feedback. Try again in a moment.";

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        reset();
        showVideo("aweme-queue-1");
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        // The recovery tap starts a real worker. Finish its posted response before resetting
        // the sandbox, or the next test can count that response as one of its own toasts.
        Utils.awaitBackgroundTasksForTests();
        idle();
        reset();
        ShadowToast.reset();
    }

    @Test public void aTapRefusedByAFullQueueSaysSoAndLeavesTheButtonUsable() throws Exception {
        try (BackgroundPoolSaturation saturation = BackgroundPoolSaturation.fill()) {
            NotInterested.submit();
            idle();

            assertEquals("a tap the worker pool refused said nothing at all",
                    REFUSED, ShadowToast.getTextOfLatestToast());
        }

        Utils.awaitBackgroundTasksForTests();
        ShadowToast.reset();

        // The point of the whole item. Before the unwind this second tap returned at the
        // in-flight check with nothing on screen, for the rest of the process.
        NotInterested.submit();
        idle();

        assertNotEquals("the button stayed dead after a refused tap",
                null, ShadowToast.getTextOfLatestToast());
        assertNotEquals("the second tap was refused again with an empty queue",
                REFUSED, ShadowToast.getTextOfLatestToast());
    }

    /**
     * The positive control. Without a full queue the same call reaches the worker, so the
     * refusal above is the queue talking and not the button failing for some other reason.
     */
    @Test public void anOrdinaryTapReachesTheWorker() throws Exception {
        NotInterested.submit();
        idle();

        assertNotEquals("an ordinary tap was refused as though the queue were full",
                REFUSED, ShadowToast.getTextOfLatestToast());
        assertNotEquals("nothing told the reader the feedback was on its way",
                null, ShadowToast.getTextOfLatestToast());
        Utils.awaitBackgroundTasksForTests();
        idle();
    }

    @Test public void theRefusalIsSaidAsWellAsTheStartRatherThanInsteadOfIt() throws Exception {
        try (BackgroundPoolSaturation saturation = BackgroundPoolSaturation.fill()) {
            NotInterested.submit();
            idle();

            // Two messages: the attempt beginning, then it being turned down. One would mean
            // either the reader never saw it start, or the refusal never arrived.
            assertEquals("the reader saw one message where both the start and the refusal belong",
                    2, ShadowToast.shownToastCount());
            assertEquals(REFUSED, ShadowToast.getTextOfLatestToast());
        }
        Utils.awaitBackgroundTasksForTests();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void reset() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        java.lang.reflect.Field submitted = NotInterested.class.getDeclaredField("lastSubmittedId");
        submitted.setAccessible(true);
        submitted.set(null, null);
        java.lang.reflect.Field inFlight = NotInterested.class.getDeclaredField("IN_FLIGHT");
        inFlight.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) inFlight.get(null)).set(false);
    }

    /** Bind, then play: the bind records the video and the play makes it the current one. */
    private static void showVideo(String id) throws Exception {
        Method update = CurrentVideoAuthor.class.getDeclaredMethod("update", Object.class);
        update.setAccessible(true);
        update.invoke(null, new Params(new Clip(id)));
        Method playing = CurrentVideoAuthor.class.getDeclaredMethod("onPlaying", String.class);
        playing.setAccessible(true);
        playing.invoke(null, id);
    }

    /** Stands in for VideoItemParams. */
    public static final class Params {
        public final Clip aweme;
        Params(Clip aweme) { this.aweme = aweme; }
    }

    public static final class Clip {
        public final String aid;
        public final Author author;
        Clip(String aid) {
            this.aid = aid;
            this.author = new Author("creator_" + aid);
        }
    }

    public static final class Author {
        public final String uid;
        public final String uniqueId;
        Author(String uid) {
            this.uid = uid;
            this.uniqueId = uid;
        }
    }
}
