package app.morphe.extension.tiktok.featurecontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.navigation.NavigationTabsFilter;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * The export says which route decided the corner LIVE button. #28's reporter's export couldn't:
 * three routes can hide it or keep it, and none of them recorded anything.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveButtonReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        LiveButtonReport.resetForTests();
        ReflectionHelpers.setStaticField(NavigationTabsFilter.class, "liveBottomTabHidden", false);
        ReflectionHelpers.setStaticField(NavigationTabsFilter.class, "liveTopTabHidden", false);
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    @After public void tearDown() {
        Settings.HIDE_LIVE_ENTRANCE.resetToDefault();
        Settings.FEED_NAVIGATION.resetToDefault();
        ReflectionHelpers.setStaticField(NavigationTabsFilter.class, "liveBottomTabHidden", false);
        ReflectionHelpers.setStaticField(NavigationTabsFilter.class, "liveTopTabHidden", false);
        LiveButtonReport.resetForTests();
    }

    @Test public void eachRouteThatDecidesTheCornerButtonIsCounted() {
        Settings.HIDE_LIVE_ENTRANCE.save(true);
        FeatureControls.hideFeedLiveButtonEnabled(true);
        Settings.HIDE_LIVE_ENTRANCE.save(false);
        FeatureControls.hideFeedLiveButtonEnabled(true);
        FeatureControls.hideFeedLiveButtonEnabled(false);

        NavigationTabsFilter.liveHasBottomTab(true);
        NavigationTabsFilter.liveHasBottomTab(false);
        ReflectionHelpers.setStaticField(NavigationTabsFilter.class, "liveBottomTabHidden", true);
        NavigationTabsFilter.liveHasBottomTab(true);

        Settings.FEED_NAVIGATION.save(true);
        NavigationTabsFilter.liveTopTabMode("live_tab_single");
        NavigationTabsFilter.liveTopTabMode("live_tab_double");
        NavigationTabsFilter.liveTopTabMode("other");
        ReflectionHelpers.setStaticField(NavigationTabsFilter.class, "liveTopTabHidden", true);
        NavigationTabsFilter.liveTopTabMode("live_tab_single");

        assertEquals(List.of(
                "Hidden by the Hide the LIVE button switch: 1 time",
                "Turned off by TikTok itself: 1 time",
                "Left on: 1 time",
                "Kept, because the bottom LIVE tab was taken off: 1 time",
                "Hidden by TikTok, because LIVE has a bottom tab: 1 time",
                "Kept, because the top LIVE tab was taken off: 1 time",
                "Hidden by TikTok, because LIVE has a top tab: 2 times"),
                LiveButtonReport.sectionForTests().lines());

        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Test", "INFO", "something worth reporting");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("[" + LiveButtonReport.TITLE + "]\n"
                + "Hidden by the Hide the LIVE button switch: 1 time"));
    }

    @Test public void aRouteThatNeverRanSaysNothing() {
        FeatureControls.hideFeedLiveButtonEnabled(true);
        assertEquals(List.of("Left on: 1 time"), LiveButtonReport.sectionForTests().lines());
    }
}
