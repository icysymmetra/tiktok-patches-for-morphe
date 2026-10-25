package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

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

/**
 * The hold used to land on the feed with no warning. Switched on, the ramp brings it in over
 * the last three quarters of a minute, which is what the field study behind the item measured
 * people responding to. It is a cover drawn from the budget, not an animation, and it has to
 * stay out of the way of everything the hold itself is careful about: touches, screen readers,
 * and a reader who has asked the system for no animation at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class HoldRampTest {
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
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 12, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        HoldRamp.resetForTests();
        animationScale(1f);
    }

    @After public void tearDown() throws Exception {
        HoldRamp.resetForTests();
        SessionBudget.setClockForTests(null);
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_RAMP.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        animationScale(1f);
        SessionBudget.awaitWritesForTests();
    }

    /**
     * The shape of the ramp, at the three points the item names. Squared rather than straight,
     * so about a tenth of the cover is there with half the ramp still to run and the rest
     * arrives in the last thirty seconds.
     */
    @Test public void theCoverFollowsWhatIsLeftOfTheBudget() {
        assertEquals("a minute out, the feed is untouched", 0, HoldRamp.alphaFor(60_000L));
        assertEquals("the ramp started before it should", 0, HoldRamp.alphaFor(HoldRamp.RAMP_MS));
        assertEquals("thirty seconds out it should be barely there",
                26, HoldRamp.alphaFor(30_000L));
        assertEquals("fifteen seconds out it should be about half",
                106, HoldRamp.alphaFor(15_000L));
        assertEquals("the ramp does not reach the hold's own shade",
                HoldRamp.FULL_ALPHA, HoldRamp.alphaFor(0L));
        // The ramp's target and the panel it hands over to are a number in two files now. A ramp
        // that overshoots its own panel lightens at the handoff instead of meeting it.
        assertEquals("the ramp's target and the hold panel's scrim have drifted apart",
                HoldRamp.FULL_ALPHA,
                android.graphics.Color.alpha(
                        app.morphe.extension.tiktok.settings.preference.SettingsUi
                                .OVERLAY_SCRIM_SOLID));
        assertEquals("a budget counted in videos has nothing to ramp from",
                0, HoldRamp.alphaFor(-1L));

        // Nine tenths of it in the final half minute is the shape the study measured, and the
        // reason this is not a straight line.
        assertTrue("more than a tenth of the cover arrives in the first quarter minute",
                HoldRamp.alphaFor(30_000L) * 10 < HoldRamp.FULL_ALPHA * 1.2f);
    }

    /** What the budget hands the ramp, which is what makes the numbers above mean anything. */
    @Test public void whatIsLeftOfTheBudgetIsMeasuredInTime() {
        Settings.SESSION_BUDGET_MINUTES.save(1);
        assertEquals("a fresh minute budget is not a whole minute",
                60_000L, SessionBudget.budgetRemainingMs());
        watch(30_000L);
        assertEquals(30_000L, SessionBudget.budgetRemainingMs());
        watch(30_000L);
        assertEquals("a spent budget has something left", 0L, SessionBudget.budgetRemainingMs());
    }

    /**
     * And a budget counted only in videos has no time left, which is a different answer from no
     * time left at all. Answering zero told the ramp it had reached the end of a clock nobody
     * set, and 0 is the darkest frame there is.
     */
    @Test public void aBudgetCountedInVideosHasNoTimeToReport() {
        Settings.SESSION_BUDGET_MINUTES.save(0);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        assertTrue("a videos budget reported time left",
                SessionBudget.budgetRemainingMs() < 0);
        SessionBudget.noteVideo("only-one");
        assertTrue("the budget did not run out", SessionBudget.reachedLimit());
        assertTrue("a spent videos budget reported no time left rather than no clock",
                SessionBudget.budgetRemainingMs() < 0);
        assertEquals("and the ramp drew something for it",
                0, HoldRamp.alphaFor(SessionBudget.budgetRemainingMs()));
    }

    /**
     * The live path, entered the way the player enters it. The cover appears part way through
     * the last three quarters of a minute and deepens as the budget runs down.
     */
    @Test public void withThirtySecondsLeftTheFeedIsPartlyCovered() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            sync();
            assertNull("the feed was covered with a whole minute left", HoldRamp.coverForTests());

            watch(30_000L);
            sync();
            View cover = HoldRamp.coverForTests();
            assertNotNull("nothing covered the feed with thirty seconds left", cover);
            assertEquals(26, alphaOf(cover));
            assertSame(activity, cover);

            // Ten seconds left, which is 144 of the 238 the hold itself is.
            watch(20_000L);
            sync();
            assertEquals("the cover did not deepen as the budget ran down",
                    144, alphaOf(HoldRamp.coverForTests()));
        }
    }

    /**
     * The cover leaves the tab row readable, and goes when the reader takes it to Profile or
     * Inbox. Leaving stops the player, whose callback draws the cover, so a layout pass is what
     * takes it down; before, a nearly black cover stayed over those tabs.
     */
    @Test public void theCoverStopsAboveTheTabsAndLeavesWithTheFeed() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            android.widget.LinearLayout bar = new android.widget.LinearLayout(activity);
            View home = new View(activity);
            bar.addView(home, new android.widget.LinearLayout.LayoutParams(80, 60));
            root.addView(bar, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 60, android.view.Gravity.BOTTOM));
            home.setSelected(true);
            seedHomeTab(home);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            watch(30_000L);
            sync();
            View cover = HoldRamp.coverForTests();
            assertNotNull("nothing covered the feed with thirty seconds left", cover);
            int tabs = SessionLockOverlay.navigationHeight(activity, root);
            assertTrue("the fixture has no tab row to keep clear: " + tabs, tabs > 0);
            assertEquals("the cover ran over the tabs", tabs,
                    ((ViewGroup.MarginLayoutParams) cover.getLayoutParams()).bottomMargin);

            home.setSelected(false);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("the cover stayed over Inbox", HoldRamp.coverForTests());
        } finally {
            seedHomeTab(null);
        }
    }

    private static void seedHomeTab(View homeTab) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "homeTabReference", new java.lang.ref.WeakReference<>(homeTab));
    }

    /** And the ramp gets out of the way once the hold itself is up. */
    @Test public void theCoverGoesWhenTheHoldTakesOver() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            watch(45_000L);
            sync();
            assertNotNull("the ramp never started", HoldRamp.coverForTests());

            watch(15_000L);
            assertTrue("the budget did not run out", SessionBudget.claimNotice());
            assertTrue("no hold started", SessionBudget.isLocked());
            sync();
            assertNull("the ramp stayed under the hold", HoldRamp.coverForTests());
        }
    }

    /**
     * And it does not come back once the hold has run its course. A hold that lasts ten minutes
     * lets go by itself, and the budget is still spent, so a ramp that only stood down while
     * the hold was up covered the feed at nearly full black until four in the morning.
     */
    @Test public void theCoverDoesNotComeBackAfterTheHoldHasRunItsCourse() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            watch(60_000L);
            assertTrue(SessionBudget.claimNotice());
            assertTrue("no hold started", SessionBudget.isLocked());

            // The hold lets go on its own, and the day has not turned over.
            now.addAndGet(11 * 60_000L);
            assertFalse("the hold never ended", SessionBudget.isLocked());

            sync();
            assertNull("the ramp covered the feed for the rest of the day after the hold ended",
                    HoldRamp.coverForTests());
        }
    }

    /**
     * With no hold set, the row that says "shows the notice and leaves the feed alone" means it.
     * A ramp leading to nothing is a feed that goes dark and stays dark with nothing on screen
     * to say why.
     */
    @Test public void withNoHoldToArriveAtNothingFades() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(0);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            watch(45_000L);
            sync();
            assertNull("the feed faded towards a hold that is switched off",
                    HoldRamp.coverForTests());

            watch(15_000L);
            sync();
            assertNull("the feed was left covered with no hold and nothing to say why",
                    HoldRamp.coverForTests());
        }
    }

    /**
     * A zero animator duration scale snaps every duration-based animation to its end frame. A
     * reader who has turned animation off has asked not to be shown a slow fade, so they get
     * the plain hold and nothing before it.
     */
    @Test public void removeAnimationsLeavesTheFeedAloneUntilTheHold() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        animationScale(0f);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            assertTrue("the fixture did not take the setting", HoldRamp.animationIsOff(activity));
            watch(45_000L);
            sync();
            assertNull("the feed was faded for a reader who asked for no animation",
                    HoldRamp.coverForTests());

            // And the same reader still gets the hold, which is not an animation.
            animationScale(1f);
            assertFalse(HoldRamp.animationIsOff(activity));
        }
    }

    /** Off, which is the default, nothing is added to the feed at all. */
    @Test public void withTheSwitchOffTheFeedIsNeverTouched() {
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        assertFalse("the ramp is on by default", Settings.SESSION_BUDGET_RAMP.get());
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            int before = root.getChildCount();
            watch(45_000L);
            sync();
            assertNull(HoldRamp.coverForTests());
            assertEquals("something was added to the feed with the switch off",
                    before, root.getChildCount());
        }
    }

    /**
     * The cover takes nothing away from the feed underneath it. It refuses touches, so the feed
     * still scrolls and the buttons still work, and a screen reader is told nothing about it:
     * the hold has its own announcement and this is the half minute before that.
     */
    @Test public void theCoverTakesNoTouchesAndIsNotAnnounced() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            watch(40_000L);
            sync();
            View cover = HoldRamp.coverForTests();
            assertNotNull(cover);
            assertFalse("the cover swallowed touches", cover.isClickable());
            assertFalse("the cover took focus", cover.isFocusable());
            assertEquals("a screen reader was told about the cover",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO, cover.getImportantForAccessibility());
        }
    }

    /**
     * The throttle, on the path the player really uses. It used to stand aside whenever a cover
     * was up, which is the whole forty-five seconds of the ramp: exactly when the callbacks
     * arrive several times a second, every one of them took a lock, asked a content provider
     * and walked the view tree.
     */
    @Test public void theBudgetIsAskedFourTimesASecondRatherThanOnEveryCallback() {
        Settings.SESSION_BUDGET_RAMP.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            watch(30_000L);
            sync();
            int first = alphaOf(HoldRamp.coverForTests());

            // Time moves for the budget but not for the clock the throttle reads, which is what
            // a handful of callbacks inside the same quarter second look like.
            watch(20_000L);
            HoldRamp.sync();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            HoldRamp.sync();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("the cover was redrawn for callbacks inside one quarter second",
                    first, alphaOf(HoldRamp.coverForTests()));

            // And the next quarter second does get through.
            sync();
            assertTrue("the ramp stopped following the budget altogether",
                    alphaOf(HoldRamp.coverForTests()) > first);
        }
    }

    // ------------------------------------------------------------------------------- fixture

    private static void assertSame(Activity activity, View cover) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        assertTrue("the cover is not on the feed's own root", cover.getParent() == root);
    }

    /**
     * One pass of the ramp, the way the player's callback drives it.
     *
     * <p>It hops to the main thread, because addView from the player's own thread throws, and
     * it only asks the budget every quarter second, so the fixture has to let that go by
     * between steps rather than pretending a whole ramp happens inside one millisecond.
     */
    private static void sync() {
        HoldRamp.syncNowForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static int alphaOf(View cover) {
        assertNotNull("there is no cover to read", cover);
        assertTrue("the cover is not a flat colour",
                cover.getBackground() instanceof ColorDrawable);
        return Color.alpha(((ColorDrawable) cover.getBackground()).getColor());
    }

    /**
     * Watching, the way the player reports it. The callback fires several times a second and
     * anything longer than {@link SessionBudget#MAX_TICK_MS} between two reports is the app
     * having been away, so the time has to arrive in ticks the player could really have sent.
     */
    private void watch(long millis) {
        SessionBudget.noteWatching();
        for (long sent = 0; sent < millis; sent += 1_000L) {
            now.addAndGet(1_000L);
            SessionBudget.noteWatching();
        }
    }

    private static void animationScale(float scale) {
        android.provider.Settings.Global.putFloat(
                RuntimeEnvironment.getApplication().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
