/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PrivacyPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Stop on-device AI profiling's switch. Pitaya starts during launch, so the answer is read from
 * the saved file before the settings context exists; it starts off and a paused build answers off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiProfilingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        EarlyApplication.reset();
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.STOP_AI_PROFILING.save(false);
        saveInTheFile(Settings.STOP_AI_PROFILING.key, false);
        SettingsStatus.aiProfilingEnabled = false;
    }

    /**
     * The early read opens the application's own preferences file. A save through the setting
     * reaches it here only because the rule points the settings store at this test's application,
     * so the file is written as well, the way PrivacySwitchesTest does it.
     */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    @Test public void theSwitchStartsOffAndOnlyItStopsTheEngine() {
        assertEquals(Boolean.FALSE, Settings.STOP_AI_PROFILING.defaultValue);
        assertEquals(AiProfiling.SWITCH_KEY, Settings.STOP_AI_PROFILING.key);
        assertTrue("Pitaya starts once at launch", Settings.STOP_AI_PROFILING.rebootApp);
        assertFalse(AiProfiling.stopsEngine());

        Settings.STOP_AI_PROFILING.save(true);
        assertTrue(AiProfiling.stopsEngine());

        PausedProcess.set(true);
        assertFalse("paused, TikTok starts its engine as it ships", AiProfiling.stopsEngine());
    }

    @Test public void beforeTheSettingsContextTheSavedSwitchIsRead() {
        Context application = RuntimeEnvironment.getApplication();
        Settings.STOP_AI_PROFILING.save(true);
        saveInTheFile(Settings.STOP_AI_PROFILING.key, true);
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertTrue(AiProfiling.stopsEngine());
            EarlyApplication.set(null);
            assertFalse("no application yet answers off", AiProfiling.stopsEngine());
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void theRowIsUnderTrackingOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PrivacyPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.STOP_AI_PROFILING.key));

            SettingsStatus.aiProfilingEnabled = true;
            assertTrue(PrivacyPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PrivacyPreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.STOP_AI_PROFILING.key));
        }
    }
}
