package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

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
 * Both budgets were only visible at their own edges: a reminder partway through, a notice at the
 * end, a hold if one was set. Between those a reader is guessing. The cue is the quiet middle,
 * and quiet is most of what it has to get right: it is off by default, it only appears on the
 * feed, its value moves at most once a minute, and it never says anything out loud.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class BudgetCueTest {
    private final AtomicLong now = new AtomicLong();

    public static class HostActivity extends Activity {
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_CUE.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 12, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        BudgetCue.resetForTests();
    }

    @After public void tearDown() throws Exception {
        // Robolectric reuses its sandbox classloader across classes, so a seeded tab or
        // sheet would answer for every later test that asks where the reader is.
        seedHomeTab(null);
        seedCommentSheet(null, null);
        BudgetCue.resetForTests();
        SessionBudget.setClockForTests(null);
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_CUE.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        SessionBudget.awaitWritesForTests();
    }

    /** What it says, before any of the machinery that puts it on a screen. */
    @Test public void theLabelReportsWhicheverBudgetIsCloserToRunningOut() {
        assertNull("a reader with no budget was given a number",
                BudgetCue.labelFor(0, 0L, 0, 0));

        assertEquals("10 min left", BudgetCue.labelFor(0, 0L, 0, 10));
        assertEquals("1 min left", BudgetCue.labelFor(0, 9 * 60_000L, 0, 10));
        assertEquals("30 videos left", BudgetCue.labelFor(0, 0L, 30, 0));
        assertEquals("1 video left", BudgetCue.labelFor(29, 0L, 30, 0));

        // Part of a minute reads as one, not as none, while the feed is still running.
        assertEquals("a part minute was rounded away",
                "1 min left", BudgetCue.labelFor(0, 9 * 60_000L + 30_000L, 0, 10));

        // Both set: the one with less of itself left is the one that will stop the feed.
        assertEquals("half the videos are gone and none of the minutes",
                "15 videos left", BudgetCue.labelFor(15, 0L, 30, 10));
        assertEquals("the minutes are nearly gone and the videos barely touched",
                "1 min left", BudgetCue.labelFor(1, 9 * 60_000L, 30, 10));
        // And the comparison is by fraction, not by raw number. Nine videos of thirty is
        // three tenths left; two minutes of four is half left. The videos are closer to
        // stopping the feed even though nine is the larger number.
        assertEquals("the raw numbers were compared instead of the fractions",
                "9 videos left", BudgetCue.labelFor(21, 2 * 60_000L, 30, 4));

        // Spent is the notice's moment and the hold's, not this label's.
        assertNull("a spent minute budget still showed a number",
                BudgetCue.labelFor(0, 10 * 60_000L, 0, 10));
        assertNull("a spent video budget still showed a number",
                BudgetCue.labelFor(30, 0L, 30, 0));
    }

    /** Off by default, and off means nothing is added to the feed at all. */
    @Test public void switchedOffItAddsNothingToTheFeed() {
        assertFalse("the cue is on by default", Settings.SESSION_BUDGET_CUE.get());
        Settings.SESSION_BUDGET_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            standOnTheFeed(activity);
            android.view.ViewGroup root = activity.findViewById(android.R.id.content);
            int before = root.getChildCount();

            sync();
            assertNull("a switched-off cue drew a label", BudgetCue.cueForTests());
            assertEquals("a switched-off cue added a view", before, root.getChildCount());
        }
    }

    /** And switching it off while it is up takes it away again. */
    @Test public void switchingItOffTakesTheLabelAway() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            standOnTheFeed(owner.get());
            sync();
            assertNotNull("the cue never appeared", BudgetCue.cueForTests());

            Settings.SESSION_BUDGET_CUE.save(false);
            sync();
            assertNull("the cue stayed after being switched off", BudgetCue.cueForTests());
        }
    }

    @Test public void onTheFeedItShowsWhatIsLeftAndMovesWithTheBudget() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            standOnTheFeed(activity);

            sync();
            TextView cue = BudgetCue.cueForTests();
            assertNotNull("nothing appeared on the feed", cue);
            assertEquals("10 min left", cue.getText().toString());
            assertSame("the cue was not added to the content root",
                    activity.findViewById(android.R.id.content), cue.getParent());

            watch(60_000L);
            sync();
            assertEquals("the label did not follow the budget down",
                    "9 min left", BudgetCue.cueForTests().getText().toString());
        }
    }

    /**
     * The quiet part. A second of watching does not move a label counted in minutes, and
     * re-setting the same text still posts a content-changed event, so a reader using TalkBack
     * would hear the cue interrupt once a second with nothing to report.
     */
    @Test public void aSecondOfWatchingDoesNotDisturbALabelCountedInMinutes() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            standOnTheFeed(owner.get());
            sync();
            TextView cue = BudgetCue.cueForTests();
            assertNotNull(cue);

            int[] textChanges = {0};
            cue.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable s) {
                    textChanges[0]++;
                }
            });

            for (int second = 0; second < 30; second++) {
                watch(1_000L);
                sync();
            }
            assertEquals("the label was rewritten while its value had not changed",
                    0, textChanges[0]);
            assertEquals("10 min left", cue.getText().toString());

            watch(30_000L);
            sync();
            assertEquals("the label did not move at the minute boundary", 1, textChanges[0]);
            assertEquals("9 min left", cue.getText().toString());
        }
    }

    /**
     * Android 17 replaced the queue behind the main Looper, and the shadow that came before it
     * answered every question about the new one with an empty queue. The cue never reads the
     * queue, but it lives on it: the player's callback posts the pass that moves the label, and
     * nothing else runs it. So the pass is posted here and left alone, and the label is read
     * before the queue delivers it and again after.
     */
    @Test @Config(sdk = 37)
    public void onAndroidSeventeenThePostedPassStillReachesTheLabel() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            standOnTheFeed(owner.get());
            sync();
            TextView cue = BudgetCue.cueForTests();
            assertNotNull("nothing appeared on the feed", cue);
            assertEquals("10 min left", cue.getText().toString());

            watch(60_000L);
            BudgetCue.syncNowForTests();
            assertEquals("the pass ran before the queue had delivered it",
                    "10 min left", cue.getText().toString());

            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the queue never delivered the pass",
                    "9 min left", BudgetCue.cueForTests().getText().toString());
        }
    }

    /** Nothing about it is announced, and nothing about it is in the way. */
    @Test public void theLabelIsSilentUntilARenderReachesIt() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(30);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            standOnTheFeed(owner.get());
            sync();
            TextView cue = BudgetCue.cueForTests();
            assertNotNull(cue);

            assertEquals("the cue announces itself over the feed",
                    View.ACCESSIBILITY_LIVE_REGION_NONE, cue.getAccessibilityLiveRegion());
            assertEquals("a reader cannot reach the cue at all",
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES, cue.getImportantForAccessibility());
            assertFalse("the cue takes touches away from the feed", cue.isClickable());
            assertFalse(cue.hasOnClickListeners());
            assertFalse("the cue takes focus off the feed", cue.isFocusable());

            // The value is what a reader gets on landing on it, with nothing masking the text.
            AccessibilityNodeInfo node = cue.createAccessibilityNodeInfo();
            assertNull("a content description replaced the value", node.getContentDescription());
            assertEquals("30 videos left", node.getText().toString());
        }
    }

    /** It belongs to the feed, so it goes when the feed does. */
    @Test public void itLeavesWithTheFeedAndWithTheHold() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            standOnTheFeed(owner.get());
            sync();
            assertNotNull("the cue never appeared", BudgetCue.cueForTests());

            watch(60_000L);
            assertTrue("the budget did not run out", SessionBudget.claimNotice());
            assertTrue("no hold started", SessionBudget.isLocked());
            sync();
            assertNull("the cue stayed under the hold", BudgetCue.cueForTests());
        }
    }

    /**
     * Leaving the feed for Profile or Inbox stops the player, and with it the callback that
     * drives the label, so the next layout pass is what takes it down (S22, 2026-09-28: "1 min
     * left" stayed over Profile's edit button).
     */
    @Test public void itLeavesTheFeedWithoutThePlayerSayingSo() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(2);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            View home = standOnTheFeed(activity);
            sync();
            assertNotNull("the cue never appeared", BudgetCue.cueForTests());

            // Profile: the Home tab loses its selection, and the player says nothing more.
            home.setSelected(false);
            activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("the label stayed over Profile", BudgetCue.cueForTests());
        }
    }

    /** A new activity gets its own label rather than a handle to a screen that is gone. */
    @Test public void itFollowsTheActivityTheHostRecreated() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        TextView first;
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            standOnTheFeed(owner.get());
            sync();
            first = BudgetCue.cueForTests();
            assertNotNull(first);
        }
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity second = owner.get();
            Utils.setActivity(second);
            // The recreated screen has its own tab bar; the old one is detached and is
            // no longer shown, which is exactly what the cue now asks about.
            standOnTheFeed(second);
            sync();
            TextView replacement = BudgetCue.cueForTests();
            assertNotNull("the cue did not come back after recreation", replacement);
            org.junit.Assert.assertNotSame("the cue stayed on the old activity",
                    first, replacement);
            assertSame(second.findViewById(android.R.id.content), replacement.getParent());
            assertEquals("the value was lost across recreation",
                    "10 min left", replacement.getText().toString());
        }
    }

    /** With a budget set but nothing counting it, there is no number and so no label. */
    @Test public void withNoBudgetThereIsNothingToShow() {
        Settings.SESSION_BUDGET_CUE.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            standOnTheFeed(owner.get());
            sync();
            assertNull("a cue appeared with no budget to report", BudgetCue.cueForTests());
        }
    }

    private static void sync() {
        BudgetCue.syncNowForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * Puts the reader on the recommendation feed: a Home tab that exists, is shown and is
     * selected. TikTok's tab resource is absent from the test app, so the lookup is seeded the
     * way the other feed tests seed it.
     *
     * <p>The cue used to ask {@link app.morphe.extension.tiktok.blockauthor.FeedVisibility#isOnFeed},
     * which answers "assume the feed" when it cannot find the tab, so every case here ran the
     * unknown branch and none of them would have noticed the cue drawing somewhere it should
     * not.
     */
    private static View standOnTheFeed(Activity activity) {
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);
        View home = new View(activity);
        root.addView(home, new android.widget.FrameLayout.LayoutParams(96, 100,
                android.view.Gravity.BOTTOM));
        home.setSelected(true);
        seedHomeTab(home);
        return home;
    }

    private static void seedHomeTab(View homeTab) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "homeTabReference", new java.lang.ref.WeakReference<>(homeTab));
    }

    private static void seedCommentSheet(View sheet, View title) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "commentSheetReference", new java.lang.ref.WeakReference<>(sheet));
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "commentTitleReference", new java.lang.ref.WeakReference<>(title));
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
    /**
     * The cue is on the recommendation feed or it is nowhere.
     *
     * <p>It asked the same question the block button asks, which answers "assume the feed" for a
     * build whose Home tab it cannot find and counts a detail page opened from a profile grid or
     * a search result. That bargain is right for a button the reader has to press and wrong for a
     * label that just sits there: every one of these used to leave the cue on screen.
     */
    @Test public void itIsOnlyOnTheRecommendationFeed() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            View home = standOnTheFeed(activity);
            sync();
            assertNotNull("the cue never appeared on the feed", BudgetCue.cueForTests());

            // A profile or a search result: the Home tab is still there and no longer chosen.
            home.setSelected(false);
            sync();
            assertNull("the cue stayed on a page that is not the feed", BudgetCue.cueForTests());

            home.setSelected(true);
            sync();
            assertNotNull("the cue did not come back with the feed", BudgetCue.cueForTests());

            // A build this one does not know: no tab at all rather than a tab to ask about.
            seedHomeTab(null);
            sync();
            assertNull("the cue drew itself on a build whose feed it cannot recognise",
                    BudgetCue.cueForTests());
        }
    }

    /**
     * It is gone on a video detail page opened from a profile grid or a search result.
     *
     * <p>Those pages leave the Home tab in the tree but off screen and run a resumed detail
     * fragment over the feed. The looser {@code isOnFeed} the block button uses counts that as
     * the feed, which is the right bargain for a button worth keeping and the wrong one for a
     * label that just sits there: it should be on the recommendation feed or absent.
     */
    @Test public void itIsGoneOnADetailPageOpenedFromTheFeed() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        Object page = new Object();
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            // The Home tab is still in the tree, just no longer on screen: a detached view is
            // not shown.
            seedHomeTab(new View(activity));

            // ...and a detail page is up, resumed and visible.
            android.view.ViewGroup root = activity.findViewById(android.R.id.content);
            View detailView = new View(activity);
            root.addView(detailView, new android.widget.FrameLayout.LayoutParams(96, 100));
            app.morphe.extension.tiktok.blockauthor.FeedVisibility.onDetailView(page, detailView);
            app.morphe.extension.tiktok.blockauthor.FeedVisibility.onDetailResume(page);
            app.morphe.extension.tiktok.blockauthor.FeedVisibility.onDetailVisibility(page, true);

            sync();
            assertNull("the cue drew over a detail page opened from the feed",
                    BudgetCue.cueForTests());
        } finally {
            app.morphe.extension.tiktok.blockauthor.FeedVisibility.onDetailDestroyed(page);
        }
    }

    /**
     * And it goes away with the comment sheet, the way the four feed controls do.
     *
     * <p>Opening the comments took the controls away and left the cue sitting over the top of
     * the sheet, because it is the last child added to the content root and is therefore drawn
     * above it.
     */
    @Test public void itLeavesWhenTheCommentSheetCoversTheFeed() {
        Settings.SESSION_BUDGET_CUE.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            standOnTheFeed(activity);
            sync();
            assertNotNull("the cue never appeared on the feed", BudgetCue.cueForTests());

            android.view.ViewGroup root = activity.findViewById(android.R.id.content);
            View sheet = new View(activity);
            View title = new View(activity);
            root.addView(sheet);
            root.addView(title);
            seedCommentSheet(sheet, title);
            sync();
            assertNull("the cue drew over the open comment sheet", BudgetCue.cueForTests());

            // Closing the sheet brings it back.
            sheet.setVisibility(View.GONE);
            title.setVisibility(View.GONE);
            sync();
            assertNotNull("the cue never came back after the comments closed",
                    BudgetCue.cueForTests());
        }
    }
}
