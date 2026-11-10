package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.navigation.StartPage;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory;

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
 * Open shared videos alone: a link to one video plays that video by itself. The feed lock's link
 * entry finds the video, cold and warm, and the main feed's pager turns its swipe down while that
 * video is the one playing. Another video, or a return after a long time away, ends it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@SuppressWarnings("deprecation")
public class SharedVideoAloneTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final AtomicLong now = new AtomicLong(1_000L);

    /** TikTok's main activity as the start page's hook sees it: something with an intent. */
    public static class LinkHost extends Activity {
        void start(Intent intent) {
            setIntent(intent);
        }
    }

    /** A preference fragment only so the framework will hand out a PreferenceScreen. */
    public static class HostFragment extends android.preference.PreferenceFragment {
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.blockAuthorEnabled = true;
        SettingsStatus.feedNavigationEnabled = true;
        reset();
        FeedLock.setClockForTests(now::get);
    }

    @After public void tearDown() {
        reset();
        seedHomeTab(null);
        PausedProcess.set(false);
        SettingsStatus.blockAuthorEnabled = false;
        SettingsStatus.feedNavigationEnabled = false;
    }

    private static void reset() {
        Settings.SHARED_VIDEO_ALONE.resetToDefault();
        Settings.FEED_LOCK.resetToDefault();
        Settings.START_PAGE.resetToDefault();
        FeedLock.resetForTests();
        FinishLastVideo.resetForTests();
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
        SessionBudget.resetForTests();
        SessionLockOverlay.resetForTests();
        SessionLockOverlay.sync();
    }

    @Test public void offByDefaultALinkedVideoSwipesOnAsBefore() {
        assertFalse("the switch is on by default", Settings.SHARED_VIDEO_ALONE.get());
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            coldStartOn(link("https://www.tiktok.com/@a/video/1"));
            report("linked");
            assertFalse("a link held the swipe with the switch off", FinishLastVideo.holdsSwipe(pager));
            assertFalse(FeedLock.linkVideoAlone());
        }
    }

    @Test public void aColdLinkPlaysItsVideoAloneAndTheNextVideoEndsIt() {
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible();
             var detail = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            View opened = pagerIn(detail.get());
            Utils.setActivity(main.get());
            assertEquals("a link keeps the tab it asked for", "HOME",
                    coldStartOn(link("https://www.tiktok.com/@a/video/1")));

            report("linked");
            assertTrue("the linked video's feed still swipes", FinishLastVideo.holdsSwipe(pager));
            assertTrue(FeedLock.linkVideoAlone());
            assertFalse("a profile video lost its swipe", FinishLastVideo.holdsSwipe(opened));
            assertFalse("the lock's panel came up without the lock", FeedLock.covers());
            assertNull("a panel covered the linked video", overlay());

            // Following, a refresh, a profile or search: another video is the reader moving on.
            report("next");
            assertFalse("the feed stayed held after another video", FinishLastVideo.holdsSwipe(pager));
            report("linked");
            assertFalse("going back to the link's video held the feed again",
                    FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void onlyThePagerTheSharedVideoCameUpInIsHeld() {
        // Another top tab, or the Friends tab, is a pager of its own. One that opens on a LIVE
        // or a photo post plays no new video, so nothing would have ended the hold there.
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            ReflectionHelpers.setStaticField(Utils.class, "resumedRef", new WeakReference<>(main.get()));
            standOnTheFeed(main.get());
            View forYou = pagerIn(main.get());
            View following = pagerIn(main.get());
            following.setVisibility(View.GONE);
            layOut(main.get());
            FeedLock.onNewIntent(link("https://www.tiktok.com/@a/video/1"));
            report("linked");

            // A tap on Following, which opens on something that isn't a video.
            forYou.setVisibility(View.GONE);
            following.setVisibility(View.VISIBLE);
            assertTrue(FeedLock.linkVideoAlone());
            assertFalse("another tab's feed couldn't swipe", FinishLastVideo.holdsSwipe(following));
            // Back on For You, the shared video still plays alone.
            forYou.setVisibility(View.VISIBLE);
            assertTrue("the shared video's own feed swiped on", FinishLastVideo.holdsSwipe(forYou));
        } finally {
            ReflectionHelpers.setStaticField(Utils.class, "resumedRef", new WeakReference<Activity>(null));
        }
    }

    @Test public void withNoPagerOnScreenWhenItCameUpTheFirstOneTouchedIsHeld() {
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            ReflectionHelpers.setStaticField(Utils.class, "resumedRef", new WeakReference<>(main.get()));
            standOnTheFeed(main.get());
            FeedLock.onNewIntent(link("https://www.tiktok.com/@a/video/1"));
            report("linked");
            View forYou = pagerIn(main.get());
            View other = pagerIn(main.get());
            assertTrue(FinishLastVideo.holdsSwipe(forYou));
            assertTrue("the held pager changed", FinishLastVideo.holdsSwipe(forYou));
            assertFalse(FinishLastVideo.holdsSwipe(other));
        } finally {
            ReflectionHelpers.setStaticField(Utils.class, "resumedRef", new WeakReference<Activity>(null));
        }
    }

    @Test public void aWarmLinkWaitsForItsOwnVideo() {
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            report("old");
            assertFalse(FinishLastVideo.holdsSwipe(pager));

            FeedLock.onNewIntent(link("https://vm.tiktok.com/ZMabc123/"));
            report("old");
            assertFalse("the video already on screen was taken for the link's",
                    FinishLastVideo.holdsSwipe(pager));
            report("linked");
            assertTrue("a warm link's video swipes on", FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void onlyALinkToOneVideoIsPlayedAlone() {
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            coldStartOn(link("https://www.tiktok.com/@someone"));
            FeedLock.onNewIntent(link("aweme://search?keyword=cats"));
            FeedLock.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
            report("first");
            assertFalse("a profile, search or launcher start held the feed", FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void aShortTimeAwayKeepsItAloneALongOneEndsIt() {
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            FeedLock.onNewIntent(link("https://www.tiktok.com/@a/video/1"));
            report("linked");
            assertTrue(FinishLastVideo.holdsSwipe(pager));

            // The phone locked, or a reply written in the app the link came from.
            FeedLock.onAppLeft();
            now.addAndGet(FeedLock.AWAY_ENDS_ALONE_MS - 1);
            FeedLock.onAppBack();
            assertTrue("a short time away let the feed swipe", FinishLastVideo.holdsSwipe(pager));
            // Screens of the app coming forward with nothing away in between change nothing.
            now.addAndGet(FeedLock.AWAY_ENDS_ALONE_MS * 2);
            FeedLock.onAppBack();
            assertTrue("a resume inside the app ended it", FinishLastVideo.holdsSwipe(pager));

            // Back later from the icon: the link's video may still be the top of For You.
            FeedLock.onAppLeft();
            now.addAndGet(FeedLock.AWAY_ENDS_ALONE_MS);
            FeedLock.onAppBack();
            assertFalse("a return long after kept the feed held", FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void aLinkThatBringsTikTokBackAfterALongTimeAwayStillPlaysAlone() {
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            report("old");
            FeedLock.onAppLeft();
            now.addAndGet(FeedLock.AWAY_ENDS_ALONE_MS * 6);
            // onNewIntent comes before the resume that tells the app it is back.
            FeedLock.onNewIntent(link("https://www.tiktok.com/@a/video/2"));
            FeedLock.onAppBack();
            report("linked");
            assertTrue("the time away ended the link that ended it", FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void pausedSwitchedOffOrWithoutItsHooksItHoldsNothing() {
        Settings.SHARED_VIDEO_ALONE.save(true);
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            standOnTheFeed(main.get());
            View pager = pagerIn(main.get());
            FeedLock.onNewIntent(link("https://www.tiktok.com/@a/video/1"));
            report("linked");
            assertTrue(FinishLastVideo.holdsSwipe(pager));

            PausedProcess.set(true);
            assertFalse("paused, the feed still can't swipe", FinishLastVideo.holdsSwipe(pager));
            PausedProcess.set(false);
            assertTrue("it didn't come back with Metra", FinishLastVideo.holdsSwipe(pager));

            SettingsStatus.feedNavigationEnabled = false;
            assertFalse("without the link hooks a switch nobody can see held the feed",
                    FinishLastVideo.holdsSwipe(pager));
            SettingsStatus.feedNavigationEnabled = true;

            Settings.SHARED_VIDEO_ALONE.save(false);
            assertFalse("switched off, the feed still can't swipe", FinishLastVideo.holdsSwipe(pager));
        }
    }

    @Test public void theFeedLocksOwnLinkEntryIsNotASharedVideoPlayedAlone() {
        // The lock holds the feed's swipe itself; the link entry it records is not a shared
        // video played alone unless that switch is on, so Auto-advance and the rest see no change.
        Settings.FEED_LOCK.save(true);
        FeedLock.onNewIntent(link("https://www.tiktok.com/@a/video/1"));
        report("linked");
        assertFalse(FeedLock.linkVideoAlone());
    }

    @Test public void theRowIsOnScreenTimeOnlyWithTheLinkHooks() {
        PreferenceScreen screen = screenTimeRows();
        Preference row = screen.findPreference(Settings.SHARED_VIDEO_ALONE.key);
        assertNotNull("no Open shared videos alone row", row);
        assertEquals("Open shared videos alone", String.valueOf(row.getTitle()));
        assertTrue("the time away isn't the one the code uses",
                String.valueOf(row.getSummary()).contains(
                        (FeedLock.AWAY_ENDS_ALONE_MS / 60_000L) + " minutes or more away"));

        SettingsStatus.feedNavigationEnabled = false;
        assertNull("the row showed without Feed tab navigation",
                screenTimeRows().findPreference(Settings.SHARED_VIDEO_ALONE.key));
    }

    private static String coldStartOn(Intent intent) {
        LinkHost host = new LinkHost();
        host.start(intent);
        return StartPage.coldStartTag(host, "HOME", null);
    }

    private static Intent link(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private static void report(String awemeId) {
        SessionPlaybackHold.onPlayerProgress(new Object(), awemeId);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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

    /** A layout pass, so the pagers have the size being on screen is judged by. */
    private static void layOut(Activity activity) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
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

    private static PreferenceScreen screenTimeRows() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostFragment fragment = new HostFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        PreferenceScreen screen = fragment.getPreferenceManager().createPreferenceScreen(activity);
        fragment.setPreferenceScreen(screen);
        new ScreenTimePreferenceCategory(activity, screen);
        return screen;
    }
}
