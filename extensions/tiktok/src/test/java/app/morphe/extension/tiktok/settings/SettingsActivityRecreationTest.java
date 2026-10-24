package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import java.util.Arrays;
import java.util.List;
import app.morphe.extension.shared.diagnostics.BuildDetails;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, qualifiers = "en-w480dp-h960dp-night-mdpi", fontScale = 1f)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class SettingsActivityRecreationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean advancedDownloads, feedFilter;

    @Before public void enablePages() {
        advancedDownloads = SettingsStatus.advancedDownloadsEnabled;
        feedFilter = SettingsStatus.feedFilterEnabled;
        SettingsStatus.advancedDownloadsEnabled = true;
        SettingsStatus.feedFilterEnabled = true;
    }

    @After public void restorePages() {
        SettingsStatus.advancedDownloadsEnabled = advancedDownloads;
        SettingsStatus.feedFilterEnabled = feedFilter;
    }

    /** Matches the settings patch's initialize call immediately after the native super.onCreate. */
    public static class SettingsActivity extends AdPersonalizationActivity {
        boolean initialized, receivedSavedState;

        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
            receivedSavedState = state != null;
            initialized = TikTokActivityHook.initialize(this);
        }

        @Override public void onBackPressed() {
            if (!TikTokActivityHook.handleBackPressed(this)) super.onBackPressed();
        }
    }

    @Test public void fontScaleRecreationPreservesSectionAndBackStack() {
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, settingsIntent()).setup().visible()) {
            SettingsActivity before = owner.get();
            settle(before);
            assertTrue(before.initialized);
            int containerId = currentPage(before).getId();
            clickMenu(before, "Downloads");
            TikTokPreferenceFragment section = currentPage(before);
            assertEquals("DOWNLOADS", requestedSection(section));
            assertNotNull(section.findPreference("download_video_quality"));
            assertEquals(1, before.getFragmentManager().getBackStackEntryCount());

            Configuration largerText = new Configuration(before.getResources().getConfiguration());
            largerText.fontScale = 1.5f;
            owner.configurationChange(largerText);
            SettingsActivity after = owner.get();
            settle(after);
            assertNotSame("the fixture did not recreate the activity", before, after);
            assertTrue("Android's saved fragment state never reached onCreate", after.receivedSavedState);
            assertEquals(1.5f, after.getResources().getConfiguration().fontScale, 0f);
            TikTokPreferenceFragment restored = currentPage(after);
            assertEquals("recreation replaced the deeper page with the home menu", "DOWNLOADS", requestedSection(restored));
            assertEquals("restored fragments lost their container", containerId, restored.getId());
            assertNotSame(section, restored);
            assertNotNull(restored.findPreference("download_video_quality"));
            assertSame(container(after), restored.getView().getParent());
            assertEquals(1, after.getFragmentManager().getBackStackEntryCount());
            assertEquals("DOWNLOADS", after.getFragmentManager().getBackStackEntryAt(0).getName());

            after.onBackPressed();
            settle(after);
            assertFalse("Back should return to the restored home menu first", after.isFinishing());
            assertEquals(0, after.getFragmentManager().getBackStackEntryCount());
            assertNull(requestedSection(currentPage(after)));
            assertNull(requestedHub(currentPage(after)));
            assertTrue(hasMenu(currentPage(after), "Downloads"));
            after.onBackPressed();
            assertTrue(after.isFinishing());
        }
    }

    @Test public void openingSettingsHandsTheRegisteredSwitchesToBuildDetails() {
        try {
            BuildDetails.setRegisteredSwitches(null);
            try (var owner = Robolectric.buildActivity(SettingsActivity.class, settingsIntent()).setup().visible()) {
                settle(owner.get());
                assertTrue(owner.get().initialized);
                String report = BuildDetails.report("");
                assertFalse(report, report.contains("registered_switches: unknown"));
                String line = report.substring(report.indexOf("registered_switches: "));
                line = line.substring(0, line.indexOf('\n'));
                List<String> names = Arrays.asList(line.substring("registered_switches: ".length()).split(","));
                assertTrue(line, names.contains("advancedDownloads") && names.contains("feedFilter"));
            }
        } finally {
            BuildDetails.setRegisteredSwitches(null);
        }
    }

    @Test public void coldStartOpensOneHomePageAndBackClosesIt() {
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, settingsIntent()).setup().visible()) {
            SettingsActivity activity = owner.get();
            settle(activity);
            assertTrue(activity.initialized);
            assertFalse(activity.receivedSavedState);
            TikTokPreferenceFragment home = currentPage(activity);
            assertNull(requestedSection(home));
            assertNull(requestedHub(home));
            assertTrue(hasMenu(home, "Downloads"));
            assertSame(container(activity), home.getView().getParent());
            assertEquals(0, activity.getFragmentManager().getBackStackEntryCount());
            activity.onBackPressed();
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void directSectionIntentOpensItsRequestedPageWithoutAnExtraHomePage() {
        Intent intent = settingsIntent().putExtra("morphe_settings_section", "FEED_FILTER");
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, intent).setup().visible()) {
            SettingsActivity activity = owner.get();
            settle(activity);
            assertTrue(activity.initialized);
            TikTokPreferenceFragment page = currentPage(activity);
            assertEquals("FEED_FILTER", requestedSection(page));
            assertFalse(hasMenu(page, "Downloads"));
            assertSame(container(activity), page.getView().getParent());
            assertEquals(0, activity.getFragmentManager().getBackStackEntryCount());
            activity.onBackPressed();
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void actionBannerStaysAboveASectionAndTheRestoredHomePage() {
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, settingsIntent()).setup().visible()) {
            SettingsActivity activity = owner.get();
            settle(activity);
            ViewGroup content = activity.findViewById(android.R.id.content);
            ViewGroup settingsSurface = (ViewGroup) content.findViewWithTag(
                    SettingsActionBanner.CONTENT_ROOT_TAG);
            ViewGroup pageContainer = container(activity);
            assertNotNull(settingsSurface);
            assertNotSame("feedback was mounted inside the fragment container",
                    pageContainer, settingsSurface);

            SettingsActionBanner.showUndo(activity, "Today starts again", () -> { });
            settle(activity);
            View banner = settingsSurface.getChildAt(settingsSurface.getChildCount() - 1);
            assertNotSame(pageContainer, banner);

            clickMenu(activity, "Downloads");
            assertSame("the opened section covered the live Undo action", banner,
                    settingsSurface.getChildAt(settingsSurface.getChildCount() - 1));

            activity.onBackPressed();
            settle(activity);
            assertSame("the restored home page covered the live Undo action", banner,
                    settingsSurface.getChildAt(settingsSurface.getChildCount() - 1));

            TextView undo = banner.findViewWithTag("hushfeed_settings_action_button");
            assertNotNull(undo);
            assertTrue(undo.performClick());
            assertNull(banner.getParent());
        }
    }

    /**
     * A page TikTok opens settings straight onto, the way filter feedback opens Feed filter at a
     * row, is the root with nothing under it. Its Back arrow leaves settings like the phone's Back
     * does; it used to pop an empty stack and do nothing. Opened from home, the arrow still goes
     * back one page, after a recreation too.
     */
    @Test public void theBackArrowLeavesAPageOpenedStraightOntoAndPopsOneOpenedFromHome() {
        Intent direct = settingsIntent().putExtra("morphe_settings_section", "FEED_FILTER");
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, direct).setup().visible()) {
            SettingsActivity activity = owner.get();
            settle(activity);
            assertEquals("FEED_FILTER", requestedSection(currentPage(activity)));
            assertEquals(0, activity.getFragmentManager().getBackStackEntryCount());
            assertTrue(backArrow(currentPage(activity)).performClick());
            assertTrue("the Back arrow on the page settings opened onto did nothing", activity.isFinishing());
        }
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, settingsIntent()).setup().visible()) {
            SettingsActivity before = owner.get();
            settle(before);
            clickMenu(before, "Downloads");
            Configuration largerText = new Configuration(before.getResources().getConfiguration());
            largerText.fontScale = 1.5f;
            owner.configurationChange(largerText);
            SettingsActivity after = owner.get();
            settle(after);
            assertEquals("DOWNLOADS", requestedSection(currentPage(after)));
            assertTrue(backArrow(currentPage(after)).performClick());
            settle(after);
            assertFalse("the Back arrow left settings from a page home opened", after.isFinishing());
            assertEquals(0, after.getFragmentManager().getBackStackEntryCount());
            assertNull(requestedSection(currentPage(after)));
            assertTrue(hasMenu(currentPage(after), "Downloads"));
        }
    }

    private static View backArrow(TikTokPreferenceFragment page) {
        ViewGroup toolbar = page.getView().findViewWithTag("hushfeed_toolbar");
        assertNotNull("the page has no toolbar", toolbar);
        return toolbar.getChildAt(0);
    }

    private static Intent settingsIntent() {
        return new Intent().setAction("morphe_settings").putExtra("morphe", true);
    }

    private static ViewGroup container(SettingsActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        ViewGroup background = (ViewGroup) content.getChildAt(0);
        return (ViewGroup) background.getChildAt(0);
    }

    private static TikTokPreferenceFragment currentPage(SettingsActivity activity) {
        var fragment = activity.getFragmentManager().findFragmentById(container(activity).getId());
        assertTrue("the current settings container has no preference page", fragment instanceof TikTokPreferenceFragment);
        return (TikTokPreferenceFragment) fragment;
    }

    private static String requestedSection(TikTokPreferenceFragment page) {
        return page.getArguments() == null ? null : page.getArguments().getString("morphe_settings_section");
    }

    private static String requestedHub(TikTokPreferenceFragment page) {
        return page.getArguments() == null ? null : page.getArguments().getString("morphe_settings_hub");
    }

    private static boolean hasMenu(TikTokPreferenceFragment page, String title) {
        for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
            if (title.equals(String.valueOf(page.getPreferenceScreen().getPreference(i).getTitle()))) return true;
        }
        return false;
    }

    private static void clickMenu(SettingsActivity activity, String title) {
        ListView list = currentPage(activity).getView().findViewById(android.R.id.list);
        for (int position = 0; position < list.getAdapter().getCount(); position++) {
            Object item = list.getAdapter().getItem(position);
            if (item instanceof Preference && title.equals(String.valueOf(((Preference) item).getTitle()))) {
                list.setSelection(position);
                settle(activity);
                View row = list.getChildAt(position - list.getFirstVisiblePosition());
                assertNotNull("the native preference row was not laid out", row);
                assertTrue(list.performItemClick(row, position, list.getAdapter().getItemId(position)));
                settle(activity);
                return;
            }
        }
        fail("the installed home menu has no " + title + " row");
    }

    private static void settle(SettingsActivity activity) {
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
