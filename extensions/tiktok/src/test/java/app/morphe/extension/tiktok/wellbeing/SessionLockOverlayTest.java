package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The hold covers the feed. What it must not cover is the row of tabs along the bottom, because
 * the whole promise of holding the feed rather than emptying it is that messages, a profile and
 * search are still there. A panel over the tab bar is a panel that shuts the app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
// Every other class that captures a view declares this; this one did not, and its published
// capture was a black rectangle. Without it a draw into a bitmap is a no-op that throws nothing.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class SessionLockOverlayTest {
    private final AtomicLong now = new AtomicLong();

    public static class HostActivity extends Activity {
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 12, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        // With no hold this detaches and hands back the audio focus, which is what clears the
        // overlay's own static state between cases.
        SessionLockOverlay.sync();
    }

    @After public void tearDown() throws Exception {
        // Robolectric reuses its sandbox classloader across test classes, so a seeded tab view
        // would answer for every later test that asks whether there is an Inbox.
        seedInboxTab(null);
        // The override is a static Boolean on the shared library and Robolectric reuses its
        // sandbox classloader across test classes, so leaving it set answers for every later
        // test that expects the system configuration.
        org.robolectric.util.ReflectionHelpers.setStaticField(
                Utils.class, "isDarkModeEnabled", null);
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        // Finish the timer before Robolectric clears its queue but keeps the static flag.
        Runnable tick = org.robolectric.util.ReflectionHelpers.getStaticField(SessionLockOverlay.class, "TICK");
        android.os.Handler handler = org.robolectric.util.ReflectionHelpers.getStaticField(SessionLockOverlay.class, "MAIN");
        handler.removeCallbacks(tick);
        tick.run();
        // That last tick asks the budget whether the hold is still on, and asking can queue a
        // write. It used to be the final statement here, so the write landed after the state had
        // been reset and the next class in the sandbox inherited a locked day: the feed controls
        // hide themselves while the budget is spent, so FeedOverlaySettingsTest found every one
        // of them gone. Everything this class changes is put back after the tick, not before.
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
    }

    @Test public void theHoldStopsAboveTheTabBar() throws Exception {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNotNull(root);

            // A tab bar the height of a real one, laid out across the bottom.
            FrameLayout bar = new FrameLayout(activity);
            View homeTab = new View(activity);
            bar.addView(homeTab);
            root.addView(bar);
            layout(root, 480, 960);
            bar.layout(0, 860, 480, 960);
            homeTab.layout(0, 0, 96, 100);
            // The id the Home tab is known by does not exist outside TikTok, so the view the
            // lookup would have found is put straight into its cache.
            seedHomeTab(homeTab);

            int height = (int) navigationHeight().invoke(null, activity, root);

            assertTrue("the panel would have covered the tab bar, height was " + height,
                    height > 0);
            assertEquals(100, height);
        }
    }

    @Test public void aBuildWithNoTabBarIsCoveredCompletely() throws Exception {
        // Better a hold that covers everything than one with a gap along the bottom that turns
        // out to be the feed.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            layout(root, 480, 960);

            assertEquals(0, (int) navigationHeight().invoke(null, activity, root));
        }
    }

    // On S22, the tab row and system inset occupy a small bottom strip. Match the virtual
    // display to the fixture scale so automatic window layouts preserve that proportion.
    @Test @Config(qualifiers = "w480dp-h960dp-mdpi")
    public void aTabRowMeasuredAfterHoldAttachmentRemainsTappable() {
        assertProfileRemainsTappableAfterNavigationLayout(false, false);
    }

    @Test @Config(qualifiers = "w480dp-h960dp-mdpi")
    public void aRetainedHoldTracksLaterNavigationHeightChanges() {
        assertProfileRemainsTappableAfterNavigationLayout(true, false);
    }

    @Test @Config(qualifiers = "w480dp-h960dp-mdpi")
    public void aMeasuredTabRowAboveTheSystemInsetRemainsTappableFromAnOffsetRoot() {
        assertProfileRemainsTappableAfterNavigationLayout(true, true);
    }

    private void assertProfileRemainsTappableAfterNavigationLayout(
            boolean initiallyMeasured, boolean withSystemInset) {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("held-navigation-video");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            layoutHoldRoot(root, withSystemInset);

            FrameLayout bar = new FrameLayout(activity);
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM);
            barParams.bottomMargin = withSystemInset ? 100 : 0;
            root.addView(bar, barParams);
            View home = new View(activity);
            home.setSelected(true);
            bar.addView(home, new FrameLayout.LayoutParams(96, -1, Gravity.LEFT));
            View profile = new View(activity);
            AtomicInteger profileTaps = new AtomicInteger();
            profile.setOnClickListener(view -> profileTaps.incrementAndGet());
            bar.addView(profile, new FrameLayout.LayoutParams(96, -1, Gravity.RIGHT));
            seedHomeTab(home);
            if (initiallyMeasured) layoutHoldRoot(root, withSystemInset);
            else assertEquals("the regression needs an unmeasured native row", 0, bar.getHeight());

            SessionLockOverlay.sync();
            View panel = root.getChildAt(root.getChildCount() - 1);
            AtomicInteger panelDowns = new AtomicInteger();
            panel.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) panelDowns.incrementAndGet();
                return false;
            });
            if (initiallyMeasured && !withSystemInset) {
                assertEquals(80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);
                // A navigation-mode or inset change gives the retained tab row a new height.
                ViewGroup.LayoutParams params = bar.getLayoutParams();
                params.height = 180;
                bar.setLayoutParams(params);
            }

            layoutHoldRoot(root, withSystemInset);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            layoutHoldRoot(root, withSystemInset);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layoutHoldRoot(root, withSystemInset);
            assertTrue("the native tab was never laid out", profile.getHeight() > 0);
            assertTrue("the regression must reuse the existing hold", panel.getParent() == root);
            if (withSystemInset) {
                int[] rootPosition = new int[2];
                root.getLocationOnScreen(rootPosition);
                assertTrue("the regression needs a nonzero root origin",
                        rootPosition[0] != 0 && rootPosition[1] != 0);
                assertEquals(100, root.getHeight() - bar.getBottom());
            }

            // The top of the enlarged tab lies under the old margin. Dispatch through the root
            // so a stale hold must actually intercept the tap rather than just report bad bounds.
            tapRoot(root, bar.getLeft() + profile.getLeft() + profile.getWidth() / 2f,
                    bar.getTop() + profile.getTop() + 16);
            assertEquals("the retained hold intercepted the native Profile tab", 1, profileTaps.get());
            assertEquals("the native Profile tap reached the hold", 0, panelDowns.get());
            // Also protect the last pixel of feed above the tab. Mixing screen and root
            // coordinates can move the hold too high and expose this strip instead.
            tapRoot(root, 240, bar.getTop() - 1);
            assertEquals("repairing navigation uncovered the held feed", 1, panelDowns.get());
            assertTrue(SessionBudget.isLocked());
        } finally {
            seedHomeTab(null);
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    /**
     * TikTok's clear display sets its tab bar GONE (the S22, 2026-09-23). The hold read that as
     * "not the feed", took its panel down and gave the sound back, so with a spent budget the feed
     * went on playing in clear display, TikTok's own or the automatic one. The panel stays up and
     * keeps the sound now, and it still stops where the bar was: that strip is TikTok's own clear
     * display bar, whose X brings the tabs back. Covering it, as the first version of this fix
     * did, left the reader no way to the messages and profiles the panel says still work.
     */
    @Test public void clearDisplayKeepsTheHoldUpAndTikToksClearBarReachable() {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("cleared-video");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout bar = new FrameLayout(activity);
            root.addView(bar, new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM));
            View home = new View(activity);
            home.setSelected(true);
            bar.addView(home, new FrameLayout.LayoutParams(96, -1, Gravity.LEFT));
            seedHomeTab(home);
            layoutHoldRoot(root, false);

            // Cleared when the hold goes up: the panel brings TikTok's controls back, since the
            // tabs its text points to are gone in clear display.
            org.robolectric.util.ReflectionHelpers.setStaticField(
                    app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.class, "clearNow", true);
            SessionLockOverlay.sync();
            View panel = root.getChildAt(root.getChildCount() - 1);
            assertEquals(View.VISIBLE, panel.getVisibility());
            assertFalse("the hold went up and left the controls cleared",
                    app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.isClearDisplayNow());
            assertEquals("the fixture's hold does not stop above the bar",
                    80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);

            // Clear display: the bar goes, the page stays.
            bar.setVisibility(View.GONE);
            SessionLockOverlay.sync();
            assertEquals("the hold came down in clear display", View.VISIBLE, panel.getVisibility());
            assertTrue("the hold gave the sound back in clear display", quietened());
            assertEquals("the panel covered TikTok's clear display bar and its way out",
                    80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);

            // The controls back: the row is reachable again.
            bar.setVisibility(View.VISIBLE);
            layoutHoldRoot(root, false);
            SessionLockOverlay.sync();
            assertEquals(80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);
        } finally {
            org.robolectric.util.ReflectionHelpers.callStaticMethod(
                    app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.class, "resetForTests");
            seedHomeTab(null);
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    private static void layoutHoldRoot(View root, boolean withSystemInset) {
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        int left = withSystemInset ? 24 : 0;
        int top = withSystemInset ? 45 : 0;
        root.layout(left, top, left + 480, top + 960);
    }

    private static void tapRoot(ViewGroup root, float x, float y) {
        MotionEvent down = MotionEvent.obtain(10, 10, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(10, 30, MotionEvent.ACTION_UP, x, y, 0);
        try {
            assertTrue(root.dispatchTouchEvent(down));
            assertTrue(root.dispatchTouchEvent(up));
        } finally {
            down.recycle();
            up.recycle();
        }
        // View posts its click after ACTION_UP, as it does when a real tab receives a touch.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @Test public void aRowTallEnoughToBeTheFeedIsNotMistakenForNavigation() throws Exception {
        // A lookup that walked too far up would find the whole page and leave the panel with a
        // margin the height of the screen, which is a hold covering nothing at all.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);

            FrameLayout tall = new FrameLayout(activity);
            View homeTab = new View(activity);
            tall.addView(homeTab);
            root.addView(tall);
            layout(root, 480, 960);
            tall.layout(0, 0, 480, 900);
            homeTab.layout(0, 800, 96, 900);
            seedHomeTab(homeTab);

            assertEquals(0, (int) navigationHeight().invoke(null, activity, root));
        }
    }

    @Test public void nothingIsDrawnWithoutAHold() {
        assertNull("a hold nobody set", holdOrNull());
    }

    @Test public void theCountdownStopsWhileTheAppIsAwayAndComesBackWithIt() throws Exception {
        // A stopped activity is neither finishing nor destroyed, so the tick used to run its
        // whole body once a second for as long as the hold lasted, up to the reset hour, while
        // TikTok sat in recents. The app's own foreground callbacks say when to stop and start.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        Settings.SESSION_BUDGET_LOCK.save(true);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            SessionLockOverlay.onForeground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("a hold on screen has no countdown", ticking());

            SessionLockOverlay.onBackground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofSeconds(3));
            assertFalse("the countdown kept ticking with the app in the background", ticking());
            assertTrue("the hold was lifted by the app going away", SessionBudget.isLocked());

            SessionLockOverlay.onForeground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("the countdown did not come back with the app", ticking());
        } finally {
            SessionLockOverlay.onForeground();
        }
    }

    /**
     * A hold that ends while the app is away takes its panel down on return. The tick needs a
     * running hold to start and the player never syncs an ended one, so the panel used to stay,
     * still naming the time the feed opens again.
     */
    @Test public void aHoldThatEndedWhileAwayIsGoneOnReturn() throws Exception {
        java.util.concurrent.atomic.AtomicLong now =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        SessionBudget.setClockForTests(now::get);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            int bare = root.getChildCount();
            SessionLockOverlay.sync();
            assertEquals("no panel went up", bare + 1, root.getChildCount());

            SessionLockOverlay.onBackground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofSeconds(3));
            now.addAndGet(6 * 60_000L);
            assertFalse(SessionBudget.isLocked());

            SessionLockOverlay.onForeground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("the ended hold's panel stayed on the feed", bare, root.getChildCount());
        } finally {
            SessionBudget.setClockForTests(null);
            SessionLockOverlay.onForeground();
        }
    }

    private static boolean ticking() {
        return org.robolectric.util.ReflectionHelpers.getStaticField(SessionLockOverlay.class, "ticking");
    }

    @Test public void aLockedDayLeavesThePanelWithNoWayOut() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        Settings.SESSION_BUDGET_LOCK.save(true);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            View release = panel.getChildAt(3);
            assertEquals("Open the feed anyway is still on a locked panel",
                    View.GONE, release.getVisibility());

            // And tapping where it used to be does nothing, so a stale panel cannot be used
            // as a way out either.
            release.performClick();
            assertTrue("a tap lifted a locked hold", SessionBudget.isLocked());

            android.widget.TextView hint = (android.widget.TextView) panel.getChildAt(2);
            assertTrue("the panel does not say when the feed comes back: " + hint.getText(),
                    hint.getText().toString().contains(SessionLockOverlay.resetTimeLabel()));
        }
    }

    @Test public void aSpentCapCountsDownAndThenTakesTheWayOutAway() throws Exception {
        // Between the way out always being there and Lock today, which removes it. The control
        // says how many are left, and once they are gone it is as absent as it is on a locked
        // day. The hint below still says what does work, so the panel is not a dead end.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        Settings.SESSION_BUDGET_PASSES_PER_DAY.save(2);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            // Looked up again after every sync: a hold that ends detaches the panel, so the
            // next one is a different view and a held reference reads the old text for ever.
            assertEquals(View.VISIBLE, wayOut(activity).getVisibility());
            assertEquals("Open the feed anyway, 2 left today",
                    wayOut(activity).getText().toString());
            assertEquals("a screen reader would not hear the count",
                    wayOut(activity).getText().toString(),
                    wayOut(activity).getContentDescription().toString());

            // Spend the first. The next hold is a raised budget away, which is what a reader
            // who carries on scrolling does.
            wayOut(activity).performClick();
            assertTrue("the way out did not open the feed", !SessionBudget.isLocked());
            reachTheHoldAgain("b", 2);
            SessionLockOverlay.sync();
            assertEquals("Open the feed anyway, the last time today",
                    wayOut(activity).getText().toString());

            // Spend the last, and it is gone for the rest of the day.
            wayOut(activity).performClick();
            assertTrue(!SessionBudget.isLocked());
            reachTheHoldAgain("c", 3);
            SessionLockOverlay.sync();
            assertEquals("a spent cap left the way out on the panel",
                    View.GONE, wayOut(activity).getVisibility());

            wayOut(activity).performClick();
            assertTrue("a tap got through a spent cap", SessionBudget.isLocked());
        }
    }

    /** The way out on whichever panel is up now. */
    private static android.widget.TextView wayOut(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
        return (android.widget.TextView) panel.getChildAt(3);
    }

    /** Raises the budget past today's count so the notice arms and the next hold starts. */
    private static void reachTheHoldAgain(String awemeId, int budget) {
        Settings.SESSION_BUDGET_VIDEOS.save(budget);
        SessionBudget.claimNotice();
        SessionBudget.noteVideo(awemeId);
        assertTrue("the hold did not come back", SessionBudget.claimNotice());
    }

    @Test public void theHoldIsAnnouncedAndTakesTheFeedOutOfTheReadingOrder() throws Exception {
        // The panel swallows touches, which is the whole hold for anyone looking at the screen
        // and nothing at all for anyone swiping through it with a screen reader: the like,
        // comment and share controls behind it stayed reachable.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            View behind = new View(activity);
            root.addView(behind);
            int before = behind.getImportantForAccessibility();

            SessionLockOverlay.sync();
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            assertNotNull(panel);

            assertEquals("the hold goes up without saying so",
                    SessionBudgetNotice.spentMessage(),
                    String.valueOf(panel.getAccessibilityPaneTitle()));
            // The pane title is what announces the arrival. The panel itself must not be a live
            // region: a live region announces for anything that changes anywhere beneath it, and
            // the countdown under this one changes once a second for the length of the hold.
            assertEquals("the whole hold is a live region, so every tick re-reads all of it",
                    View.ACCESSIBILITY_LIVE_REGION_NONE, panel.getAccessibilityLiveRegion());
            assertEquals("the feed behind the hold can still be swiped through",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    behind.getImportantForAccessibility());

            View release = panel.getChildAt(3);
            android.view.accessibility.AccessibilityNodeInfo info =
                    android.view.accessibility.AccessibilityNodeInfo.obtain();
            release.onInitializeAccessibilityNodeInfo(info);
            assertEquals("the only way out of the hold is read as plain text",
                    android.widget.Button.class.getName(), String.valueOf(info.getClassName()));

            // And the feed is handed back exactly as it was found.
            Method detach = SessionLockOverlay.class.getDeclaredMethod("detach");
            detach.setAccessible(true);
            detach.invoke(null);
            assertEquals("the feed was left out of the reading order after the hold ended",
                    before, behind.getImportantForAccessibility());
        }
    }

    /** Counts every write to a label, whether or not the words changed. */
    private static AtomicInteger countWrites(android.widget.TextView view) {
        AtomicInteger writes = new AtomicInteger();
        view.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                writes.incrementAndGet();
            }
        });
        return writes;
    }

    /**
     * Two minutes of a hold is two announcements, not a hundred and twenty.
     *
     * <p>The panel used to be the live region and its three labels were rewritten on every tick,
     * so a reader using TalkBack heard the whole hold read out again once a second for as long as
     * it ran. The countdown is the only thing that changes, so it is the only live region, and
     * nothing is written unless the words moved.
     */
    /**
     * The two ways out of the hold answer a press and show their focus, like every other control
     * this bundle draws. They were flat, and unreachable by a d-pad below API 26. The pixel proof
     * that the shared overlay helper changes on focus lives in OverlayControlsTest; this checks
     * the hold's own two controls carry that background and can be focused at all.
     */
    @Test public void theHoldControlsAnswerAPressAndShowTheirFocus() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            View release = panel.getChildAt(3);
            View messages = panel.getChildAt(4);

            for (View control : new View[]{release, messages}) {
                assertTrue("a hold control cannot be reached by a keyboard or d-pad",
                        control.isFocusable());
                android.graphics.drawable.Drawable background = control.getBackground();
                assertTrue("a hold control has no ripple: " + (background == null ? "null"
                                : background.getClass().getSimpleName()),
                        background instanceof android.graphics.drawable.RippleDrawable);
            }
        }
    }

    @Test public void theHoldIsNotReadOutAgainOnEveryTick() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            android.widget.TextView remaining = (android.widget.TextView) panel.getChildAt(1);
            android.widget.TextView hint = (android.widget.TextView) panel.getChildAt(2);
            android.widget.TextView release = (android.widget.TextView) panel.getChildAt(3);

            assertEquals("the countdown, the one thing that moves, does not announce itself",
                    View.ACCESSIBILITY_LIVE_REGION_POLITE, remaining.getAccessibilityLiveRegion());
            assertEquals("the hint announces itself, and it never changes",
                    View.ACCESSIBILITY_LIVE_REGION_NONE, hint.getAccessibilityLiveRegion());

            AtomicInteger countdown = countWrites(remaining);
            AtomicInteger hints = countWrites(hint);
            AtomicInteger wayOut = countWrites(release);

            String first = remaining.getText().toString();
            for (int second = 0; second < 120; second++) {
                now.addAndGet(1_000L);
                SessionLockOverlay.sync();
            }

            assertEquals("the hint was rewritten while nothing about it had changed",
                    0, hints.get());
            assertEquals("the way out was rewritten while nothing about it had changed",
                    0, wayOut.get());
            // Five minutes left, so the label reads 5, then 4 at a minute gone and 3 at two.
            assertEquals("the countdown was written more often than the minute it shows changed",
                    2, countdown.get());
            assertFalse("the countdown never moved, so this proves nothing",
                    first.equals(remaining.getText().toString()));
        }
    }

    @Test public void anUnlockedDayKeepsItsWayOut() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            assertEquals("the way out went missing on a day nobody locked",
                    View.VISIBLE, panel.getChildAt(3).getVisibility());
            panel.getChildAt(3).performClick();
            assertTrue("Open the feed anyway did not open the feed", !SessionBudget.isLocked());
        }
    }

    @Test public void theWayToMessagesIsOfferedOnlyWhileThereIsAnInboxTabToOpen() throws Exception {
        // The panel says messages still work and then covers everything, so without this the
        // reader has to already know the Inbox tab is under it.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            // No Inbox tab, which is what a build that renamed it and a reader who hid it in
            // Feed navigation both look like: the filter drops the tab and no view is built.
            seedInboxTab(null);
            SessionLockOverlay.sync();
            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            android.widget.TextView messages = (android.widget.TextView) panel.getChildAt(4);
            assertEquals("a way to an Inbox that is not there", View.GONE,
                    messages.getVisibility());

            // With one, it is offered, reads as a button, and a tap is the tab's own click. The
            // view has to be in the window: the cache re-resolves anything detached by name, and
            // the resource this build looks for does not exist in a test application.
            var inbox = new FrameLayout(activity);
            var taps = new java.util.concurrent.atomic.AtomicInteger();
            inbox.setOnClickListener(view -> taps.incrementAndGet());
            root.addView(inbox, 0);
            seedInboxTab(inbox);

            // Asked as the panel goes up rather than on every tick, so the answer moves when
            // the hold does. Ending this one and taking the next is what a reader who changed
            // the setting and came back to the feed does.
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
            Settings.SESSION_BUDGET_VIDEOS.save(2);
            SessionBudget.claimNotice();
            SessionBudget.noteVideo("b");
            assertTrue(SessionBudget.claimNotice());
            SessionLockOverlay.sync();

            panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            messages = (android.widget.TextView) panel.getChildAt(4);
            assertEquals(View.VISIBLE, messages.getVisibility());
            assertEquals("Open messages", messages.getText().toString());
            assertEquals("a screen reader would not hear it as a button",
                    android.widget.Button.class.getName(), roleOf(messages));

            messages.performClick();
            assertEquals("the Inbox tab was not clicked", 1, taps.get());
            assertTrue("the hold ended when the reader went to messages",
                    SessionBudget.isLocked());
        }
    }

    @Test public void theQuietReminderIsABannerThatAnnouncesItselfAndGoesOnItsOwn() throws Exception {
        // A toast takes no focus and goes on its own too, but TalkBack does not read it as a
        // live region, and it is outside the decor tree so it cannot be captured. This is the
        // banner the block button's undo already uses, without anything to press.
        Settings.SESSION_BUDGET_NOTICE_MINUTES.save(5);
        SessionBudget.noteWatching();
        for (int tick = 0; tick < 5 * 60; tick++) {
            now.addAndGet(1_000L);
            SessionBudget.noteWatching();
        }

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
            int before = root.getChildCount();

            app.morphe.extension.tiktok.wellbeing.SessionBudgetNotice.showIntervalNoticeIfDue();
            looper.idle();
            assertEquals("the reminder was not drawn", before + 1, root.getChildCount());

            View banner = root.getChildAt(root.getChildCount() - 1);
            assertEquals("a screen reader would never hear it",
                    View.ACCESSIBILITY_LIVE_REGION_POLITE, banner.getAccessibilityLiveRegion());
            assertTrue("the reminder took the focus", !banner.isFocusable());

            layout(root, 480, 960);

            // Measured and laid out here exactly as the capture is about to do it, so what is
            // asserted below is what will be drawn. Asserting against the parent's earlier pass
            // proves nothing: a child that has been hidden or sized to nothing keeps its last
            // measurement until something re-measures it, so a size assertion on stale numbers
            // passes happily while the picture comes out empty.
            banner.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY));
            banner.layout(0, 0, 480, 96);

            // What the picture is supposed to show, asserted rather than assumed. The capture
            // used to be taken without anyone checking that the banner had a label, a size or
            // any text in it, so a blank one looked exactly like a good one.
            assertTrue("the banner has no content to draw",
                    ((ViewGroup) banner).getChildCount() > 0);
            android.widget.TextView label = (android.widget.TextView) ((ViewGroup) banner).getChildAt(0);
            assertEquals("the words are not in the picture at all",
                    View.VISIBLE, label.getVisibility());
            String shown = label.getText().toString();
            assertTrue("the reminder is not one of the three wordings: " + shown,
                    shown.equals(SessionBudgetNotice.intervalMessage(0))
                            || shown.equals(SessionBudgetNotice.intervalMessage(1))
                            || shown.equals(SessionBudgetNotice.intervalMessage(2)));
            assertTrue("the banner measured to nothing, so the capture would be empty",
                    banner.getMeasuredWidth() > 0 && banner.getMeasuredHeight() > 0);
            assertTrue("the label measured to nothing, so the words would not be in the picture",
                    label.getMeasuredWidth() > 0 && label.getMeasuredHeight() > 0);

            app.morphe.extension.tiktok.UiCapture.save(banner, "session-reminder.png", 480, 96);

            // And it takes itself away rather than waiting to be dismissed.
            looper.idleFor(java.time.Duration.ofSeconds(7));
            assertEquals("the reminder stayed on the feed", before, root.getChildCount());
        }
    }

    /** What TalkBack would call the view. */
    private static String roleOf(View view) {
        var info = android.view.accessibility.AccessibilityNodeInfo.obtain();
        view.onInitializeAccessibilityNodeInfo(info);
        return String.valueOf(info.getClassName());
    }

    private static void seedInboxTab(View inboxTab) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "inboxTabReference", new java.lang.ref.WeakReference<>(inboxTab));
    }

    private static Object holdOrNull() {
        return SessionBudget.isLocked() ? Boolean.TRUE : null;
    }

    private static void seedHomeTab(View homeTab) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "homeTabReference", new java.lang.ref.WeakReference<>(homeTab));
    }

    private static Method navigationHeight() throws Exception {
        Method method = SessionLockOverlay.class.getDeclaredMethod(
                "navigationHeight", Activity.class, ViewGroup.class);
        method.setAccessible(true);
        return method;
    }

    private static void layout(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    @Test public void theCountdownIsReadableOnTheScrimInEitherTheme() throws Exception {
        // The panel is always the same near-black scrim, so its colours cannot follow the
        // settings theme. The settings accent is a dark crimson in the light theme, which is
        // about 3:1 on black, and the flag it reads is a cached one the settings screen sets,
        // so away from that screen it answers for the system theme instead of for this panel.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        for (boolean darkSettings : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(darkSettings);
            try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setActivity(activity);
                SessionLockOverlay.sync();

                ViewGroup root = activity.findViewById(android.R.id.content);
                View panel = root.getChildAt(root.getChildCount() - 1);
                android.widget.TextView countdown =
                        (android.widget.TextView) ((ViewGroup) panel).getChildAt(1);
                assertEquals("the countdown followed the settings theme onto a black panel",
                        app.morphe.extension.tiktok.settings.preference.SettingsUi.OVERLAY_ACCENT,
                        countdown.getCurrentTextColor());
            }
        }
    }

    @Test public void theHoldAsksTheFeedToStopPlaying() throws Exception {
        // Focus remains held alongside the native pause. This also covers a restored hold
        // before the first native player instance has reported progress.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        android.media.AudioManager audio = (android.media.AudioManager)
                RuntimeEnvironment.getApplication().getSystemService(
                        android.content.Context.AUDIO_SERVICE);
        var shadow = org.robolectric.Shadows.shadowOf(audio);
        assertNull("something already held the focus", shadow.getLastAudioFocusRequest());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            SessionLockOverlay.sync();

            var request = shadow.getLastAudioFocusRequest();
            assertNotNull("the feed kept playing behind the hold", request);
            assertEquals("a permanent grab would leave the feed silent afterwards",
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.durationHint);

            // Handing it back is what lets the feed play again, so a hold that ends must not
            // leave the app quiet.
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
            assertNotNull("the focus was never handed back", shadow.getLastAbandonedAudioFocusListener());
        }
    }

    @Test public void theHoldDoesNotFightWhoeverTookTheSoundAway() throws Exception {
        // A call takes the focus for good. The flag has to follow, or nothing would ever ask
        // again for the rest of the hold. What must not happen is asking again a second later:
        // the tick runs every second, and that would be taking the sound off the call.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();
            assertTrue("the hold never took the focus", quietened());

            android.media.AudioManager.OnAudioFocusChangeListener listener =
                    org.robolectric.util.ReflectionHelpers.getStaticField(
                            SessionLockOverlay.class, "QUIET");
            listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS);
            assertTrue("losing the focus left the hold thinking it still had it", !quietened());

            SessionLockOverlay.sync();
            assertTrue("the hold asked a call to give the sound back a second later", !quietened());

            // The panel going up again is a thing the reader did, so that is when it is fair to
            // ask. This is the state the off-feed branch leaves the panel in.
            ViewGroup root = activity.findViewById(android.R.id.content);
            root.getChildAt(root.getChildCount() - 1).setVisibility(View.GONE);
            SessionLockOverlay.sync();
            assertTrue("coming back to the feed did not quiet it again", quietened());
        }
    }

    private static boolean quietened() {
        return org.robolectric.util.ReflectionHelpers.getStaticField(
                SessionLockOverlay.class, "quietened");
    }

    @Test public void aHoldRestoredFromDiskReattachesOnTheFirstPlayerReport() throws Exception {
        // What makes a hold survive the app being killed: the player reports progress, that asks
        // whether a hold is running, and the answer comes off the persisted record. Every other
        // case here starts from a hold already in memory, so a break anywhere along this path
        // passed the suite. The fast path in isLocked() is exactly the shape that would.
        long until = now.get() + 5L * 60_000L;
        Settings.SESSION_BUDGET_STATE.save(SessionBudget.dayOf(now.get())
                + "|3|0|" + until + "|1");
        SessionBudget.resetForTests();
        assertTrue("the record did not carry the hold back", SessionBudget.isLocked());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            int before = root.getChildCount();

            SessionLockOverlay.ensureRunning();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertTrue("a hold that survived the process being killed never came back",
                    root.getChildCount() > before);
        }
    }

    /**
     * Android 17 replaced the queue behind the main Looper. The countdown is a runnable that
     * posts itself back a second ahead, so on a queue that delivered nothing it would run once
     * and never again, with nothing failing. The tick is started here and left to the queue: a
     * minute goes by on the budget's clock with nothing calling sync, and only a delivered tick
     * can move the label.
     */
    @Test @Config(sdk = 37)
    public void onAndroidSeventeenTheTickStillComesRoundOnItsOwn() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
            SessionLockOverlay.ensureRunning();
            looper.idle();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            android.widget.TextView remaining = (android.widget.TextView) panel.getChildAt(1);
            AtomicInteger countdown = countWrites(remaining);
            String first = remaining.getText().toString();

            now.addAndGet(60_000L);
            assertEquals("the countdown moved before a tick was delivered",
                    first, remaining.getText().toString());

            looper.idleFor(java.time.Duration.ofSeconds(1));
            assertEquals("the tick did not come round", 1, countdown.get());
            assertFalse("the countdown never moved, so this proves nothing",
                    first.equals(remaining.getText().toString()));
        }
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
