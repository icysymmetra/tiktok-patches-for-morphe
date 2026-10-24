package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.main.MainActivity;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * The hold used to land the moment the budget ran out, halfway through a video on a time budget
 * and on the very video that reached the count on a video budget. Switched on, the video on
 * screen plays to its end first, the feed's own pager turns swipes down meanwhile, and nothing
 * that moves the feed anyway gets the reader past the budget.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FinishLastVideoTest {
    private final AtomicLong now = new AtomicLong();

    public static class HostActivity extends Activity {
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_RAMP.resetToDefault();
        Settings.SESSION_BUDGET_FINISH_VIDEO.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 12, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        FinishLastVideo.resetForTests();
        SessionLockOverlay.sync();
    }

    @After public void tearDown() throws Exception {
        FinishLastVideo.resetForTests();
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        // The same ending SessionLockOverlayTest has: finish the countdown before Robolectric
        // clears its queue, then put back what the last tick may have written.
        Runnable tick = ReflectionHelpers.getStaticField(SessionLockOverlay.class, "TICK");
        android.os.Handler handler = ReflectionHelpers.getStaticField(SessionLockOverlay.class, "MAIN");
        handler.removeCallbacks(tick);
        tick.run();
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_RAMP.resetToDefault();
        Settings.SESSION_BUDGET_FINISH_VIDEO.resetToDefault();
    }

    @Test public void offByDefaultTheHoldLandsAtOnce() {
        assertFalse("the switch is on by default", Settings.SESSION_BUDGET_FINISH_VIDEO.get());
        spendAVideoBudgetOfTwoOn("second");
        assertFalse("the hold waited with the switch off", FinishLastVideo.begin("second"));
        assertFalse(FinishLastVideo.pending());
    }

    /** With no hold after the budget there is only a notice, and nothing to wait for. */
    @Test public void withNoHoldThereIsNothingToWaitFor() {
        Settings.SESSION_BUDGET_FINISH_VIDEO.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        SessionBudget.noteVideo("only");
        assertTrue(SessionBudget.claimNotice());
        assertFalse("the feed's swipe went off with no hold coming",
                FinishLastVideo.begin("only"));
    }

    /**
     * The video that spends a budget of two is the second, and it is watched: the panel stays
     * down until it completes, and comes up the moment it does.
     */
    @Test public void theHoldWaitsForTheVideoOnScreen() {
        Settings.SESSION_BUDGET_FINISH_VIDEO.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            spendAVideoBudgetOfTwoOn("second");
            SessionBudgetNotice.show("second");
            idle();
            assertTrue("the hold did not wait for the last video", FinishLastVideo.pending());

            SessionLockOverlay.sync();
            assertNull("the panel covered the video being finished", overlay());

            FinishLastVideo.onPlayCompleted("someone-else");
            assertTrue("another player's completion ended the wait", FinishLastVideo.pending());

            FinishLastVideo.onPlayCompleted("second");
            idle();
            assertFalse(FinishLastVideo.pending());
            assertNotNull("the panel did not come up when the video ended", overlay());
        }
    }

    /** Anything that moves the feed ends the wait, so the next video arrives under the hold. */
    @Test public void anotherVideoEndsTheWait() {
        Settings.SESSION_BUDGET_FINISH_VIDEO.save(true);
        spendAVideoBudgetOfTwoOn("second");
        assertTrue(FinishLastVideo.begin("second"));

        FinishLastVideo.onVideoChanged(null);
        assertTrue("the player being ahead of the bind ended the wait", FinishLastVideo.pending());
        FinishLastVideo.onVideoChanged("second");
        assertTrue("the same video coming up again ended the wait", FinishLastVideo.pending());
        FinishLastVideo.onVideoChanged("third");
        assertFalse("the feed moved on and the wait went on", FinishLastVideo.pending());
    }

    /** A completion that never arrives costs the limit and no more. */
    @Test public void theWaitHasALimit() {
        Settings.SESSION_BUDGET_FINISH_VIDEO.save(true);
        spendAVideoBudgetOfTwoOn("second");
        assertTrue(FinishLastVideo.begin("second"));
        now.addAndGet(FinishLastVideo.LONGEST_MS - 1);
        assertTrue(FinishLastVideo.pending());
        now.addAndGet(1);
        assertFalse("the wait outlived its limit", FinishLastVideo.pending());
    }

    /** A hold that ends while the video plays leaves nothing to wait for. */
    @Test public void aHoldThatEndsEndsTheWait() {
        Settings.SESSION_BUDGET_FINISH_VIDEO.save(true);
        spendAVideoBudgetOfTwoOn("second");
        assertTrue(FinishLastVideo.begin("second"));
        assertTrue(SessionBudget.clear());
        assertFalse("the swipe stayed off after the day started over", FinishLastVideo.pending());
    }

    /** The fade already took the video away on a time budget; lifting it would undo the fade. */
    @Test public void aTimeBudgetTheFadeDimmedGoesStraightToTheHold() {
        Settings.SESSION_BUDGET_FINISH_VIDEO.save(true);
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        SessionBudget.noteVideo("long");
        watch(60_000L);
        assertTrue(SessionBudget.claimNotice());
        assertFalse("the fade was lifted to finish a video it had dimmed",
                FinishLastVideo.begin("long"));

        // Without the fade the same budget waits for the video.
        SessionBudget.clear();
        Settings.SESSION_BUDGET_RAMP.save(false);
        watch(60_000L);
        assertTrue(SessionBudget.claimNotice());
        assertTrue(FinishLastVideo.begin("long"));
    }

    /**
     * Only the main activity's pager turns swipes down. A video opened from messages, a profile
     * or search plays in the detail activity and keeps its swipe, and nothing is held once the
     * wait is over.
     */
    @Test public void onlyTheMainFeedStopsSwiping() {
        Settings.SESSION_BUDGET_FINISH_VIDEO.save(true);
        spendAVideoBudgetOfTwoOn("second");
        assertTrue(FinishLastVideo.begin("second"));

        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible();
             var detail = Robolectric.buildActivity(DetailActivity.class).setup().visible();
             var other = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            View feed = pagerIn(main.get());
            View opened = pagerIn(detail.get());
            View elsewhere = pagerIn(other.get());

            assertTrue("the main feed kept its swipe", FinishLastVideo.holdsSwipe(feed));
            assertFalse("a video opened from messages lost its swipe",
                    FinishLastVideo.holdsSwipe(opened));
            assertFalse(FinishLastVideo.holdsSwipe(elsewhere));
            assertFalse(FinishLastVideo.holdsSwipe(null));

            FinishLastVideo.onPlayCompleted("second");
            assertFalse("the swipe stayed off after the video ended",
                    FinishLastVideo.holdsSwipe(feed));
        }
    }

    private void spendAVideoBudgetOfTwoOn(String last) {
        Settings.SESSION_BUDGET_VIDEOS.save(2);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        SessionBudget.noteVideo("first");
        assertFalse(SessionBudget.claimNotice());
        SessionBudget.noteVideo(last);
        assertTrue("the budget did not run out on the second video", SessionBudget.claimNotice());
        assertTrue("no hold started", SessionBudget.isLocked());
    }

    private static View pagerIn(Activity activity) {
        VerticalViewPager pager = new VerticalViewPager(activity);
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(pager);
        return pager;
    }

    private static View overlay() {
        WeakReference<View> reference =
                ReflectionHelpers.getStaticField(SessionLockOverlay.class, "overlayReference");
        return reference.get();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void watch(long millis) {
        SessionBudget.noteWatching();
        for (long sent = 0; sent < millis; sent += 1_000L) {
            now.addAndGet(1_000L);
            SessionBudget.noteWatching();
        }
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
