/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.publishdate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The publish date options sit under its switch on Feed screen, and only when it's patched. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PublishDateRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        SettingsStatus.alwaysShowPublishDateEnabled = false;
    }

    @Test public void theOptionsAreOnTheFeedScreenPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.alwaysShowPublishDateEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.PUBLISH_DATE_EXACT_TIME.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_ON_GRID.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_TIME_ZONE.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_SHORT_DATE.key));
            assertNull(without.findPreference(Settings.VIDEO_LENGTH_ON_GRID.key));

            SettingsStatus.alwaysShowPublishDateEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.ALWAYS_SHOW_PUBLISH_DATE.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_EXACT_TIME.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_ON_GRID.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_TIME_ZONE.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_SHORT_DATE.key));
            assertNotNull(with.findPreference(Settings.VIDEO_LENGTH_ON_GRID.key));
            // They apply to the next video or grid shown, so none asks for a restart.
            assertFalse(Settings.PUBLISH_DATE_EXACT_TIME.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_ON_GRID.rebootApp);
            assertFalse(Settings.VIDEO_LENGTH_ON_GRID.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_TIME_ZONE.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_SHORT_DATE.rebootApp);
            // Both only shape the time to the second, so they follow its switch.
            try {
                Settings.PUBLISH_DATE_EXACT_TIME.save(false);
                assertFalse(Settings.PUBLISH_DATE_TIME_ZONE.isAvailable());
                assertFalse(Settings.PUBLISH_DATE_SHORT_DATE.isAvailable());
                Settings.PUBLISH_DATE_EXACT_TIME.save(true);
                assertTrue(Settings.PUBLISH_DATE_TIME_ZONE.isAvailable());
                assertTrue(Settings.PUBLISH_DATE_SHORT_DATE.isAvailable());
            } finally {
                Settings.PUBLISH_DATE_EXACT_TIME.resetToDefault();
            }
        }
    }
}
