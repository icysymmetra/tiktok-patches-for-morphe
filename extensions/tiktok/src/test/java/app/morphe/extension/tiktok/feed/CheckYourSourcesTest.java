package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The Check your sources switch: the answer the Know the facts banner gate and share prompt read
 * (a false there takes the banner and the prompt with it), that it honours Pause and stays apart
 * from the risk model's switches, and that its row sits under Skip content warnings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CheckYourSourcesTest {
    private static final String ROW = "Hide Check your sources warnings";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private boolean sensitiveBefore;
    private boolean unverifiedBefore;
    private boolean sourcesBefore;

    @Before public void setUp() {
        sensitiveBefore = SettingsStatus.sensitiveWarningsEnabled;
        unverifiedBefore = SettingsStatus.unverifiedNoticesEnabled;
        sourcesBefore = SettingsStatus.checkYourSourcesEnabled;
        SettingsStatus.checkYourSourcesEnabled = true;
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.sensitiveWarningsEnabled = sensitiveBefore;
        SettingsStatus.unverifiedNoticesEnabled = unverifiedBefore;
        SettingsStatus.checkYourSourcesEnabled = sourcesBefore;
        Settings.HIDE_CHECK_YOUR_SOURCES.resetToDefault();
        Settings.HIDE_UNVERIFIED_NOTICES.resetToDefault();
        Settings.HIDE_SENSITIVE_WARNINGS.resetToDefault();
    }

    @Test public void offByDefaultTheBannerShows() {
        assertFalse(Settings.HIDE_CHECK_YOUR_SOURCES.defaultValue);
        assertFalse(SensitiveWarnings.hideCheckYourSources());
    }

    @Test public void onTheGateAndPromptAnswerFalse() {
        Settings.HIDE_CHECK_YOUR_SOURCES.save(true);
        assertTrue(SensitiveWarnings.hideCheckYourSources());
    }

    /** A different model from the risk model: neither neighboring switch reaches it, and it reaches neither. */
    @Test public void itsOwnSwitch() {
        SettingsStatus.unverifiedNoticesEnabled = true;
        Settings.HIDE_SENSITIVE_WARNINGS.save(true);
        Settings.HIDE_UNVERIFIED_NOTICES.save(true);
        assertFalse(SensitiveWarnings.hideCheckYourSources());

        Settings.HIDE_UNVERIFIED_NOTICES.save(false);
        Settings.HIDE_CHECK_YOUR_SOURCES.save(true);
        assertTrue(SensitiveWarnings.hideCheckYourSources());
        assertFalse(SensitiveWarnings.hideUnverifiedNotices());
    }

    @Test public void pausedTheBannerShows() {
        Settings.HIDE_CHECK_YOUR_SOURCES.save(true);
        PausedProcess.set(true);
        assertFalse(SensitiveWarnings.hideCheckYourSources());
    }

    @Test public void aSavedSwitchDoesNothingWithoutItsHooks() {
        Settings.HIDE_CHECK_YOUR_SOURCES.save(true);
        SettingsStatus.checkYourSourcesEnabled = false;
        assertFalse(SensitiveWarnings.hideCheckYourSources());
    }

    @Test public void theFeedScreenCarriesTheRowUnderSkipContentWarnings() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.sensitiveWarningsEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            Preference row = byTitle(screen, ROW);
            assertNotNull(row);
            assertEquals(Settings.HIDE_CHECK_YOUR_SOURCES.key, row.getKey());
            assertNotNull(row.getSummary());
            String summary = row.getSummary().toString();
            assertFalse(summary, summary.indexOf((char) 0x2014) >= 0 || summary.indexOf((char) 0x2013) >= 0
                    || summary.contains(" - "));
            assertNotNull("the row belongs with Skip content warnings", byTitle(screen, "Skip content warnings"));
        }
    }

    @Test public void aBuildWhereTheHooksWereLeftOutShowsNoRow() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.sensitiveWarningsEnabled = true;
            SettingsStatus.checkYourSourcesEnabled = false;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            assertNull(byTitle(screen, ROW));
            assertNotNull(byTitle(screen, "Skip content warnings"));
        }
    }

    /** The one row with this title, or null when there's none. */
    private static Preference byTitle(PreferenceGroup group, String title) {
        List<Preference> found = new ArrayList<>();
        collect(group, title, found);
        assertTrue("rows titled " + title + ": " + found.size(), found.size() <= 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collect(PreferenceGroup group, String title, List<Preference> found) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child.getTitle() != null && title.contentEquals(child.getTitle())) found.add(child);
            if (child instanceof PreferenceGroup) collect((PreferenceGroup) child, title, found);
        }
    }
}
