package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.navigation.StartPage;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.main.MainActivity;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * The feed lock is a second reason for the budget's panel, its stopped playback and its pager
 * block to be there. Off by default and off while Hushfeed is paused; on, it covers the
 * recommendation feed and nothing else, and a link to one video still opens that video.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class FeedLockTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final AtomicLong now = new AtomicLong(1_000L);

    /** TikTok's main activity as the start page's hook sees it: something with an intent. */
    public static class LinkHost extends Activity {
        void start(Intent intent) {
            setIntent(intent);
        }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.blockAuthorEnabled = true;
        reset();
        FeedLock.setClockForTests(now::get);
    }

    @After public void tearDown() {
        reset();
        seedHomeTab(null);
        PausedProcess.set(false);
        SettingsStatus.blockAuthorEnabled = false;
    }

    private static void reset() {
        Settings.FEED_LOCK.resetToDefault();
        Settings.START_PAGE.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        FeedLock.resetForTests();
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
        SessionBudget.resetForTests();
        SessionLockOverlay.resetForTests();
        SessionLockOverlay.sync();
    }

    @Test public void offByDefaultItCoversNothingAndSwipesFreely() {
        assertFalse("the lock is on by default", Settings.FEED_LOCK.get());
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            report("a");
            assertFalse(FeedLock.covers());
            assertNull("a panel went up with the switch off", overlay());
            assertFalse(FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void onItCoversTheFeedWithACalmPanelAndTurnsTheSwipeDown() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible();
             var detail = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            View opened = pagerIn(detail.get());
            Utils.setActivity(main.get());
            report("a");

            View panel = overlay();
            assertNotNull("the feed was not covered", panel);
            assertEquals("the placeholder is calm and says what it is", "The feed is locked",
                    texts(panel).get(0).getText().toString());
            assertTrue("there is a countdown on a panel with no budget behind it",
                    texts(panel).stream().noneMatch(t -> t.getVisibility() == View.VISIBLE
                            && t.getText().toString().contains("left")));
            assertTrue("a way past the lock was offered", texts(panel).stream().noneMatch(
                    t -> t.getVisibility() == View.VISIBLE
                            && t.getText().toString().startsWith("Open the feed anyway")));
            assertTrue("the way to messages is gone", texts(panel).stream().anyMatch(
                    t -> t.getText().toString().equals("Open messages")));
            assertTrue("the swipe was not turned down", FinishLastVideo.holdsSwipe(pager));
            assertFalse("a video opened from messages lost its swipe", FinishLastVideo.holdsSwipe(opened));
        }
    }

    @Test public void turningItOffTakesThePanelAwayAndPausedItNeverCovers() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            Utils.setActivity(main.get());
            report("a");
            assertNotNull(overlay());

            PausedProcess.set(true);
            assertFalse("paused, the lock still covers the feed", FeedLock.covers());
            assertFalse("paused, the feed still can't swipe", FinishLastVideo.holdsSwipe(pager));
            SessionLockOverlay.sync();
            assertNull("paused, the panel stayed up", overlay());

            PausedProcess.set(false);
            SessionLockOverlay.sync();
            assertNotNull("the lock did not come back with Metra", overlay());

            Settings.FEED_LOCK.save(false);
            SessionLockOverlay.onForeground();
            idle();
            assertNull("the panel stayed after the switch went off", overlay());
        }
    }

    @Test public void aMessageOrSearchVideoInTheMainActivityIsNotCovered() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            View home = standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            Utils.setActivity(main.get());
            // Inbox, a profile or a video opened from them: the Home tab is no longer selected.
            home.setSelected(false);
            report("from-a-message");
            assertNull("a video opened from a message was covered", overlay());
            assertFalse(FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void aTabChangeAfterTheFeedWasCoveredTakesThePanelOffInboxAndBack() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            View home = standOnTheFeed(main.get());
            Utils.setActivity(main.get());
            report("a");
            View panel = overlay();
            assertNotNull(panel);
            assertEquals(View.VISIBLE, panel.getVisibility());

            // A tap on Inbox: nothing reports progress, as a paused video doesn't.
            home.setSelected(false);
            idleFor(1_500);
            assertEquals("the panel stayed over Inbox", View.GONE, panel.getVisibility());

            home.setSelected(true);
            idleFor(1_500);
            assertEquals("the panel didn't come back over the feed", View.VISIBLE, panel.getVisibility());

            // Switched off, the timer has nothing left to do and the panel goes.
            Settings.FEED_LOCK.save(false);
            idleFor(1_500);
            assertNull(overlay());
        }
    }

    @Test public void theBudgetsPassIsNotOfferedWhileTheLockKeepsThePanelUp() {
        Settings.FEED_LOCK.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        SessionBudget.noteVideo("only");
        assertTrue(SessionBudget.claimNotice());
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            Utils.setActivity(main.get());
            report("a");
            View panel = overlay();
            assertNotNull(panel);
            assertTrue("a pass was offered under the feed lock", texts(panel).stream().noneMatch(
                    t -> t.getVisibility() == View.VISIBLE
                            && t.getText().toString().startsWith("Open the feed anyway")));

            Settings.FEED_LOCK.save(false);
            SessionLockOverlay.sync();
            assertTrue("the pass went missing without the lock", texts(overlay()).stream().anyMatch(
                    t -> t.getVisibility() == View.VISIBLE
                            && t.getText().toString().startsWith("Open the feed anyway")));
        }
    }

    @Test public void thePanelIsNamedForWhicheverReasonHoldsItNow() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            Utils.setActivity(main.get());
            report("a");
            View panel = overlay();
            assertEquals("The feed is locked", String.valueOf(panel.getAccessibilityPaneTitle()));

            Settings.SESSION_BUDGET_VIDEOS.save(1);
            Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
            SessionBudget.noteVideo("only");
            assertTrue(SessionBudget.claimNotice());
            SessionLockOverlay.sync();
            assertEquals("a budget hold kept the lock's title for a screen reader",
                    SessionBudgetNotice.spentMessage(), String.valueOf(panel.getAccessibilityPaneTitle()));
            assertEquals(SessionBudgetNotice.spentMessage(), texts(panel).get(0).getText().toString());
        }
    }

    @Test public void aLinkToOneVideoOnAColdStartStillOpensIt() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            Utils.setActivity(main.get());
            LinkHost host = new LinkHost();
            host.start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@a/video/1")));
            assertEquals("a link keeps the tab it asked for", "HOME",
                    StartPage.coldStartTag(host, "HOME", null));

            assertTrue("the feed was open before the link's video was known", FeedLock.covers());
            report("linked");
            assertNull("the linked video was covered", overlay());
            assertTrue("the feed swipe stayed on for the linked video", FinishLastVideo.holdsSwipe(pager));

            report("next");
            assertTrue("the video after it was let through", FeedLock.covers());
            assertNotNull("the feed was not covered after the linked video", overlay());
        }
    }

    @Test public void aLinkToOneVideoOnAWarmStartReplacesTheVideoUnderThePanel() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            Utils.setActivity(main.get());
            report("old");
            assertNotNull(overlay());

            FeedLock.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://vm.tiktok.com/ZMabc123/")));
            assertTrue("the panel came down before the linked video did", FeedLock.covers());
            report("old");
            assertTrue("the video that was already there became the link's", FeedLock.covers());

            report("linked");
            idle();
            assertFalse("the linked video was covered", FeedLock.covers());
            assertNull("the panel stayed over the linked video", overlay());

            report("next");
            idle();
            assertTrue(FeedLock.covers());
            assertNotNull("the feed was open after the linked video", overlay());
        }
    }

    @Test public void aNewIntentWithoutALinkAndALinkThatNeverPlaysChangeNothing() {
        Settings.FEED_LOCK.save(true);
        report("old");
        FeedLock.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        FeedLock.onNewIntent(null);
        report("another");
        assertTrue("a plain return to the app opened the feed", FeedLock.covers());

        // A link whose video never arrives is forgotten, so the first feed video isn't taken for it.
        FeedLock.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://vm.tiktok.com/ZMabc123/")));
        now.addAndGet(FeedLock.LINK_WINDOW_MS + 1);
        report("late");
        assertTrue("a late video was let through as the link's", FeedLock.covers());
    }

    @Test public void aSpentBudgetKeepsItsOwnPanelWithTheCountdown() {
        Settings.FEED_LOCK.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        SessionBudget.noteVideo("only");
        assertTrue(SessionBudget.claimNotice());
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            Utils.setActivity(main.get());
            report("a");
            View panel = overlay();
            assertNotNull(panel);
            assertEquals(SessionBudgetNotice.spentMessage(), texts(panel).get(0).getText().toString());
            assertEquals(View.VISIBLE, texts(panel).get(1).getVisibility());
        }
    }

    @Test public void theStartTabMovesOffTheFeedOnlyForAPlainLauncherStart() {
        Settings.FEED_LOCK.save(true);
        LinkHost launcher = new LinkHost();
        launcher.start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals("the app opened on a blank feed", "NOTIFICATION",
                StartPage.coldStartTag(launcher, "HOME", null));
        assertEquals("a tab TikTok picked off the feed was moved", "SHOP_MALL",
                StartPage.coldStartTag(launcher, "SHOP_MALL", null));

        Settings.START_PAGE.save(StartPage.FOR_YOU);
        assertEquals("a chosen feed tab opened", "NOTIFICATION",
                StartPage.coldStartTag(launcher, "HOME", null));
        Settings.START_PAGE.save(StartPage.PROFILE);
        assertEquals("a chosen Profile was changed", "USER", StartPage.coldStartTag(launcher, "HOME", null));

        Settings.START_PAGE.resetToDefault();
        assertEquals("a restored activity", "HOME",
                StartPage.coldStartTag(launcher, "HOME", new Bundle()));
        Settings.FEED_LOCK.save(false);
        assertEquals("off, TikTok keeps its own tab", "HOME",
                StartPage.coldStartTag(launcher, "HOME", null));
    }

    @Test public void withNoInboxToOpenTheStartIsProfile() {
        Settings.FEED_LOCK.save(true);
        Settings.BOTTOM_NAVIGATION.save(true);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
        LinkHost launcher = new LinkHost();
        launcher.start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        try {
            assertEquals("USER", StartPage.coldStartTag(launcher, "HOME", null));
        } finally {
            Settings.BOTTOM_NAVIGATION.resetToDefault();
            Settings.BOTTOM_NAVIGATION_TABS.resetToDefault();
        }
    }

    @Test public void aClearedScreenStaysCoveredAndSwipeLocked() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            ViewGroup root = main.get().findViewById(android.R.id.content);
            // Clear display puts the tab bar away and leaves the tab itself visible and selected.
            android.widget.FrameLayout bar = new android.widget.FrameLayout(main.get());
            View home = new View(main.get());
            bar.addView(home, new android.widget.FrameLayout.LayoutParams(96, 100));
            root.addView(bar, new android.widget.FrameLayout.LayoutParams(300, 100,
                    android.view.Gravity.BOTTOM));
            home.setSelected(true);
            seedHomeTab(home);
            bar.setVisibility(View.GONE);
            View pager = pagerIn(main.get());
            Utils.setActivity(main.get());
            report("cleared");
            assertNotNull("the lock let go of a cleared feed", overlay());
            assertTrue("a cleared feed swipes", FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void theBottomFriendsTabsFeedIsCoveredToo() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            View home = standOnTheFeed(main.get());
            ViewGroup root = main.get().findViewById(android.R.id.content);
            View friends = new View(main.get());
            root.addView(friends, new android.widget.FrameLayout.LayoutParams(96, 100,
                    android.view.Gravity.BOTTOM));
            ReflectionHelpers.setStaticField(FeedVisibility.class, "friendsTabReference",
                    new WeakReference<>(friends));
            View pager = pagerIn(main.get());
            Utils.setActivity(main.get());
            home.setSelected(false);
            friends.setSelected(true);
            report("friends");
            assertNotNull("the Friends tab's feed was open", overlay());
            assertTrue(FinishLastVideo.holdsSwipe(pager));
            // Neither selected: Inbox or Profile, which the lock leaves alone.
            friends.setSelected(false);
            SessionLockOverlay.sync();
            assertEquals(View.GONE, overlay().getVisibility());
            ReflectionHelpers.setStaticField(FeedVisibility.class, "friendsTabReference",
                    new WeakReference<View>(null));
        }
    }

    @Test public void withoutTheHooksItsRowLivesOnTheLockIsOff() {
        Settings.FEED_LOCK.save(true);
        assertTrue(FeedLock.isOn());
        SettingsStatus.blockAuthorEnabled = false;
        assertFalse("a lock nobody can turn off was on", FeedLock.isOn());
        assertFalse(FeedLock.covers());
    }

    @Test public void onlyALinkToOneVideoIsAPass() {
        assertTrue(FeedLock.isVideoLink(view("https://www.tiktok.com/@a.b/video/7123456789")));
        assertTrue(FeedLock.isVideoLink(view("https://www.tiktok.com/share/video/7123456789/")));
        assertTrue("a photo post", FeedLock.isVideoLink(view("https://www.tiktok.com/@a.b/photo/7123456789")));
        assertTrue("the mobile site", FeedLock.isVideoLink(view("https://m.tiktok.com/v/7123456789.html")));
        assertFalse("a profile's video tab", FeedLock.isVideoLink(view("https://www.tiktok.com/@a.b/video")));
        assertTrue(FeedLock.isVideoLink(view("https://vm.tiktok.com/ZMabc123/")));
        assertTrue(FeedLock.isVideoLink(view("https://www.tiktok.com/t/ZTabc123/")));
        assertTrue(FeedLock.isVideoLink(view("aweme://aweme/detail/7123456789")));
        assertFalse("a profile", FeedLock.isVideoLink(view("https://www.tiktok.com/@someone")));
        assertFalse("a tag", FeedLock.isVideoLink(view("https://www.tiktok.com/tag/cats")));
        assertFalse("a search", FeedLock.isVideoLink(view("aweme://search?keyword=cats")));
        assertFalse("another site", FeedLock.isVideoLink(view("https://example.com/@a/video/7123456789")));
        assertFalse("a lookalike host", FeedLock.isVideoLink(view("https://nottiktok.com/@a/video/7123456789")));
        assertFalse("a launcher start with data", FeedLock.isVideoLink(
                new Intent(Intent.ACTION_MAIN, Uri.parse("https://www.tiktok.com/@a/video/7123456789"))));
        assertFalse(FeedLock.isVideoLink(null));
    }

    @Test public void aProfileLinkOnAColdStartDoesNotOpenTheFeed() {
        Settings.FEED_LOCK.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            Utils.setActivity(main.get());
            LinkHost host = new LinkHost();
            host.start(view("https://www.tiktok.com/@someone"));
            StartPage.coldStartTag(host, "HOME", null);
            FeedLock.onNewIntent(view("aweme://search?keyword=cats"));
            assertTrue("a profile or search link uncovered the feed", FeedLock.covers());
            report("first");
            assertNotNull("the feed was open after a profile link", overlay());
        }
    }

    @Test public void anInboxStartWithNoInboxFallsBackToProfile() {
        Settings.FEED_LOCK.save(true);
        Settings.START_PAGE.save(StartPage.INBOX);
        Settings.BOTTOM_NAVIGATION.save(true);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
        LinkHost launcher = new LinkHost();
        launcher.start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        try {
            assertEquals("USER", StartPage.coldStartTag(launcher, "HOME", null));
        } finally {
            Settings.BOTTOM_NAVIGATION.resetToDefault();
            Settings.BOTTOM_NAVIGATION_TABS.resetToDefault();
        }
    }

    private static Intent view(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private static void report(String awemeId) {
        SessionPlaybackHold.onPlayerProgress(new Object(), awemeId);
        idle();
    }

    private static java.util.List<TextView> texts(View panel) {
        java.util.List<TextView> found = new java.util.ArrayList<>();
        ViewGroup group = (ViewGroup) panel;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof TextView) found.add((TextView) group.getChildAt(i));
        }
        return found;
    }

    private static View standOnTheFeed(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        View home = new View(activity);
        root.addView(home, new android.widget.FrameLayout.LayoutParams(96, 100,
                android.view.Gravity.BOTTOM));
        home.setSelected(true);
        seedHomeTab(home);
        return home;
    }

    private static void seedHomeTab(View homeTab) {
        ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference",
                new WeakReference<>(homeTab));
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

    private static void idleFor(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms));
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
