package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Arrays;
import java.util.List;

import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NavigationTabsFilterTest {
    @Before public void setUp() {
        NavigationTabsFilter.filterTopTabs(null);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.FEED_NAVIGATION.save(true);
        Settings.FEED_NAVIGATION_TABS.save("HOT");
        Settings.FEED_NAVIGATION_OBSERVED_TABS.save("HOT,EXPLORE");
        Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.save(true);
        Settings.BOTTOM_NAVIGATION.save(true);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,PROFILE");
        Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.save(true);
    }

    @After public void tearDown() {
        NavigationTabsFilter.filterTopTabs(null);
        Settings.FEED_NAVIGATION.save(false);
        Settings.FEED_NAVIGATION_TABS.save(NavigationTabOptions.defaultEnabledKeys());
        Settings.FEED_NAVIGATION_OBSERVED_TABS.save(NavigationTabOptions.HOT);
        Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.save(false);
        Settings.BOTTOM_NAVIGATION.save(false);
        Settings.BOTTOM_NAVIGATION_TABS.save(BottomNavigationTabOptions.defaultEnabledKeys());
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,PROFILE");
        Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.save(false);
    }

    @Test public void tagGetterAndFallbackGetterKeepOnlyConfiguredTab() {
        GetterTab hot = new GetterTab("For You");
        GetterTab explore = new GetterTab("Explore");
        FallbackTab following = new FallbackTab("Following");
        List<?> result = NavigationTabsFilter.filterTopTabs(Arrays.asList(hot, explore, following));

        assertEquals(1, result.size());
        assertSame(hot, result.get(0));
    }

    @Test public void optionSerializationNormalizesAliasesAndPreservesUnknownTags() {
        assertEquals(NavigationTabOptions.FOLLOWING,
                NavigationTabOptions.normalizeRuntimeTag(" homepage_following "));
        assertEquals("RAW:creator picks",
                NavigationTabOptions.normalizeRuntimeTag(" Creator, Picks "));
        assertEquals("HOT,RAW:creator picks",
                NavigationTabOptions.serializeEnabledKeys(
                        NavigationTabOptions.parseEnabledKeys("raw:Creator Picks")));

        assertTrue(BottomNavigationTabOptions.parseEnabledKeys("inbox").contains(
                BottomNavigationTabOptions.HOME));
        assertTrue(BottomNavigationTabOptions.parseEnabledKeys("inbox").contains(
                BottomNavigationTabOptions.PROFILE));
        assertEquals(BottomNavigationTabOptions.MALL,
                BottomNavigationTabOptions.normalizeRuntimeTag("shop_mall"));
    }

    @Test public void bottomFilterRetainsRequiredTabsWhenEveryOptionalTabIsDisabled() {
        BottomTab home = new BottomTab("home");
        BottomTab inbox = new BottomTab("inbox");
        BottomTab profile = new BottomTab("me");

        List<?> result = NavigationTabsFilter.filterBottomTabs(Arrays.asList(home, inbox, profile));

        assertEquals(2, result.size());
        assertSame(home, result.get(0));
        assertSame(profile, result.get(1));
    }

    @Test public void theCornerLiveButtonComesBackOnlyWhenTheFilterTookTikToksLiveTab() {
        NavigationTabsFilter.resetLiveBottomTabForTests();
        try {
            BottomTab home = new BottomTab("home");
            BottomTab live = new BottomTab(NavigationTabsFilter.LIVE_BOTTOM_TAB_TAG);
            BottomTab profile = new BottomTab("me");
            // The bar TikTok served had LIVE in it, and the reader's list doesn't keep it.
            Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,PROFILE,RAW:live");
            Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
            NavigationTabsFilter.filterBottomTabs(Arrays.asList(home, live, profile));
            assertFalse("TikTok still hides its corner LIVE button for a tab it no longer shows",
                    NavigationTabsFilter.liveHasBottomTab(true));
            // A false from TikTok stays false: this only ever takes a reason away.
            assertFalse(NavigationTabsFilter.liveHasBottomTab(false));

            // Kept on the bar, the tab is the way in, and TikTok's answer stands.
            Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE,RAW:live");
            NavigationTabsFilter.filterBottomTabs(Arrays.asList(home, live, profile));
            assertTrue(NavigationTabsFilter.liveHasBottomTab(true));

            // No LIVE tab served at all: nothing was taken, so nothing changes.
            Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
            NavigationTabsFilter.filterBottomTabs(Arrays.asList(home, profile));
            assertTrue(NavigationTabsFilter.liveHasBottomTab(true));

            // With the filter off the bar is TikTok's own, LIVE included.
            NavigationTabsFilter.filterBottomTabs(Arrays.asList(home, live, profile));
            assertFalse(NavigationTabsFilter.liveHasBottomTab(true));
            Settings.BOTTOM_NAVIGATION.save(false);
            NavigationTabsFilter.filterBottomTabs(Arrays.asList(home, live, profile));
            assertTrue("the filter was off and the LIVE tab was on the bar", NavigationTabsFilter.liveHasBottomTab(true));
        } finally {
            NavigationTabsFilter.resetLiveBottomTabForTests();
        }
    }

    @Test public void theCornerLiveButtonComesBackOnlyWhenTheTopFilterTookLive() {
        GetterTab hot = new GetterTab("For You");
        GetterTab live = new GetterTab("LIVE");
        Settings.FEED_NAVIGATION_OBSERVED_TABS.save("HOT,LIVE");
        NavigationTabsFilter.filterTopTabs(Arrays.asList(hot, live));
        assertEquals("", NavigationTabsFilter.liveTopTabMode("live_tab_single"));
        assertEquals("", NavigationTabsFilter.liveTopTabMode("live_tab_double"));
        assertEquals("another mode must stay intact", "other", NavigationTabsFilter.liveTopTabMode("other"));
        assertEquals(null, NavigationTabsFilter.liveTopTabMode(null));

        // A child pass doesn't replace the recorded top-level model.
        NavigationTabsFilter.filterTopTabs(Arrays.asList(hot, live), true);
        assertEquals("", NavigationTabsFilter.liveTopTabMode("live_tab_single"));

        Settings.FEED_NAVIGATION_TABS.save("HOT,LIVE");
        NavigationTabsFilter.filterTopTabs(Arrays.asList(hot, live));
        assertEquals("live_tab_single", NavigationTabsFilter.liveTopTabMode("live_tab_single"));

        Settings.FEED_NAVIGATION_TABS.save("HOT");
        NavigationTabsFilter.filterTopTabs(Arrays.asList(hot));
        assertEquals("live_tab_double", NavigationTabsFilter.liveTopTabMode("live_tab_double"));

        NavigationTabsFilter.filterTopTabs(Arrays.asList(hot, live));
        Settings.FEED_NAVIGATION.save(false);
        assertEquals("live_tab_single", NavigationTabsFilter.liveTopTabMode("live_tab_single"));
        NavigationTabsFilter.filterTopTabs(Arrays.asList(hot, live));
        Settings.FEED_NAVIGATION.save(true);
        assertEquals("live_tab_double", NavigationTabsFilter.liveTopTabMode("live_tab_double"));
    }

    @Test public void disabledNavigationReturnsTheOriginalListIdentity() {
        Settings.FEED_NAVIGATION.save(false);
        List<GetterTab> tabs = Arrays.asList(new GetterTab("For You"), new GetterTab("Explore"));

        assertSame(tabs, NavigationTabsFilter.filterTopTabs(tabs));
        assertFalse(NavigationTabOptions.isKnownKey("RAW:unknown"));
    }

    @Test public void forYouHeaderIsRedundantOnlyWhenTheFilteredModelContainsForYou() {
        NavigationTabsFilter.filterTopTabs(Arrays.asList(
                new GetterTab("For You"), new GetterTab("Following")));
        assertTrue(NavigationTabsFilter.shouldHideLoneForYouHeader());

        Settings.FEED_NAVIGATION_TABS.save("HOT,FOLLOWING");
        NavigationTabsFilter.filterTopTabs(Arrays.asList(
                new GetterTab("For You"), new GetterTab("Following")));
        assertFalse(NavigationTabsFilter.shouldHideLoneForYouHeader());

        Settings.FEED_NAVIGATION_TABS.save("HOT");
        Settings.FEED_NAVIGATION.save(false);
        NavigationTabsFilter.filterTopTabs(Arrays.asList(new GetterTab("For You")));
        assertFalse(NavigationTabsFilter.shouldHideLoneForYouHeader());
    }

    @Test public void theTabNamesSwitchHidesTheStripAndPutsItBackWhenItGoesOff() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup()) {
            android.app.Activity activity = controller.get();
            View strip = new View(activity);
            NavigationTabsFilter.installLoneForYouHeaderHider(strip);
            Settings.FEED_NAVIGATION_TABS.save("HOT,FOLLOWING");
            NavigationTabsFilter.filterTopTabs(Arrays.asList(new GetterTab("For You"), new GetterTab("Following")));
            NavigationTabsFilter.refreshTopTabStrips();
            assertEquals(View.VISIBLE, strip.getVisibility());

            Settings.HIDE_FEED_TAB_STRIP.save(true);
            NavigationTabsFilter.refreshTopTabStrips();
            assertEquals(View.GONE, strip.getVisibility());

            Settings.HIDE_FEED_TAB_STRIP.save(false);
            NavigationTabsFilter.refreshTopTabStrips();
            assertEquals("the switch going off did not bring the strip back", View.VISIBLE, strip.getVisibility());

            // A strip TikTok hid itself is not this switch's to bring back.
            View own = new View(activity);
            own.setVisibility(View.GONE);
            NavigationTabsFilter.installLoneForYouHeaderHider(own);
            Settings.HIDE_FEED_TAB_STRIP.save(true);
            NavigationTabsFilter.refreshTopTabStrips();
            Settings.HIDE_FEED_TAB_STRIP.save(false);
            NavigationTabsFilter.refreshTopTabStrips();
            assertEquals(View.GONE, own.getVisibility());

            // The lone For You rule hides on its own, and the switch being off changes nothing there.
            Settings.FEED_NAVIGATION_TABS.save("HOT");
            NavigationTabsFilter.filterTopTabs(Arrays.asList(new GetterTab("For You"), new GetterTab("Following")));
            NavigationTabsFilter.refreshTopTabStrips();
            assertEquals(View.GONE, strip.getVisibility());
        } finally {
            Settings.HIDE_FEED_TAB_STRIP.save(false);
        }
    }

    public static class GetterTab {
        private final String value;

        GetterTab(String value) {
            this.value = value;
        }

        public String tag() {
            return value;
        }
    }

    public static class FallbackTab {
        private final String value;

        FallbackTab(String value) {
            this.value = value;
        }

        public String getTag() {
            return value;
        }
    }

    public static class BottomTab {
        private final String value;

        BottomTab(String value) {
            this.value = value;
        }

        public String getTag() {
            return value;
        }
    }
}
