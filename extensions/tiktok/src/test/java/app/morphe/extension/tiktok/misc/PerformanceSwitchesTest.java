/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The four Performance patches' switches: what TikTok's hooks get back from them, before and
 * after the settings context exists, and the rows on the App page.
 *
 * <p>The patches joined the default selection on the promise that TikTok runs as it ships until a
 * switch is turned on, so every switch here starts off, and a paused build answers off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PerformanceSwitchesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final BooleanSetting[] SWITCHES = {
            Settings.SKIP_SPLASH_AD, Settings.SKIP_UPDATE_CHECKS,
            Settings.LIMIT_BACKGROUND_TRAFFIC, Settings.DROP_ANIMATED_IMAGE_CACHE,
    };

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
        for (BooleanSetting setting : SWITCHES) {
            setting.save(false);
            saveInTheFile(setting.key, false);
        }
        BaseSettings.PAUSED.save(false);
        saveInTheFile(BaseSettings.PAUSED.key, false);
        SettingsStatus.skipSplashAdEnabled = false;
        SettingsStatus.skipUpdateChecksEnabled = false;
        SettingsStatus.limitBackgroundTrafficEnabled = false;
        SettingsStatus.skipPushSetupEnabled = false;
        SettingsStatus.animatedImageCacheEnabled = false;
    }

    @Test public void everySwitchStartsOffAndIsSavedUnderTheKeyTheEarlyReadUses() {
        for (BooleanSetting setting : SWITCHES) {
            assertEquals(setting.key + " starts off", Boolean.FALSE, setting.defaultValue);
        }
        assertEquals(PerformanceSwitches.SPLASH_AD_KEY, Settings.SKIP_SPLASH_AD.key);
        assertEquals(PerformanceSwitches.UPDATE_CHECKS_KEY, Settings.SKIP_UPDATE_CHECKS.key);
        assertEquals(PerformanceSwitches.BACKGROUND_TRAFFIC_KEY, Settings.LIMIT_BACKGROUND_TRAFFIC.key);
        assertEquals(PerformanceSwitches.ANIMATED_IMAGE_CACHE_KEY, Settings.DROP_ANIMATED_IMAGE_CACHE.key);
        // The three that act on startup tasks say a restart applies them.
        assertTrue(Settings.SKIP_SPLASH_AD.rebootApp);
        assertTrue(Settings.SKIP_UPDATE_CHECKS.rebootApp);
        assertTrue(Settings.LIMIT_BACKGROUND_TRAFFIC.rebootApp);
        assertFalse("read as each sticker is built", Settings.DROP_ANIMATED_IMAGE_CACHE.rebootApp);
    }

    @Test public void withTheSwitchesOffTikTokGetsItsOwnAnswers() {
        assertFalse(PerformanceSwitches.skipSplashAd());
        assertFalse(PerformanceSwitches.skipUpdateChecks());
        assertFalse(PerformanceSwitches.limitBackgroundTraffic());
        for (int frames : new int[]{0, 1, 3, 10}) assertEquals(frames, PerformanceSwitches.framesToPrepare(frames));
    }

    @Test public void eachSwitchAnswersForItsOwnPatchOnly() {
        Settings.SKIP_SPLASH_AD.save(true);
        assertTrue(PerformanceSwitches.skipSplashAd());
        assertFalse(PerformanceSwitches.skipUpdateChecks());
        assertFalse(PerformanceSwitches.limitBackgroundTraffic());
        assertEquals(2, PerformanceSwitches.framesToPrepare(2));

        Settings.SKIP_UPDATE_CHECKS.save(true);
        assertTrue(PerformanceSwitches.skipUpdateChecks());
        Settings.LIMIT_BACKGROUND_TRAFFIC.save(true);
        assertTrue(PerformanceSwitches.limitBackgroundTraffic());
        assertEquals("the cache has its own switch", 4, PerformanceSwitches.framesToPrepare(4));

        Settings.DROP_ANIMATED_IMAGE_CACHE.save(true);
        assertEquals("no frames prepared ahead", 0, PerformanceSwitches.framesToPrepare(4));
        assertEquals(0, PerformanceSwitches.framesToPrepare(0));
    }

    @Test public void pausedEverySwitchAnswersOff() {
        for (BooleanSetting setting : SWITCHES) setting.save(true);
        PausedProcess.set(true);
        assertFalse(PerformanceSwitches.skipSplashAd());
        assertFalse(PerformanceSwitches.skipUpdateChecks());
        assertFalse(PerformanceSwitches.limitBackgroundTraffic());
        assertEquals(5, PerformanceSwitches.framesToPrepare(5));
    }

    /**
     * The splash, update and push tasks run during launch. A switch that's on is read from the
     * saved file before the settings context exists, and a paused start or no file answers off.
     */
    @Test public void beforeTheSettingsContextTheSavedSwitchIsRead() {
        Context application = RuntimeEnvironment.getApplication();
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertFalse(PerformanceSwitches.skipSplashAd());
            assertFalse(PerformanceSwitches.limitBackgroundTraffic());
            assertEquals(2, PerformanceSwitches.framesToPrepare(2));
        } finally {
            Utils.setContext(application);
        }

        for (BooleanSetting setting : SWITCHES) {
            setting.save(true);
            saveInTheFile(setting.key, true);
        }
        Utils.setContext(null);
        try {
            assertTrue(PerformanceSwitches.skipSplashAd());
            assertTrue(PerformanceSwitches.skipUpdateChecks());
            assertTrue(PerformanceSwitches.limitBackgroundTraffic());
            assertEquals(0, PerformanceSwitches.framesToPrepare(2));
        } finally {
            Utils.setContext(application);
        }

        BaseSettings.PAUSED.save(true);
        saveInTheFile(BaseSettings.PAUSED.key, true);
        Utils.setContext(null);
        try {
            assertFalse("a paused start answers off", PerformanceSwitches.skipSplashAd());
            assertFalse(PerformanceSwitches.limitBackgroundTraffic());
        } finally {
            Utils.setContext(application);
        }

        BaseSettings.PAUSED.save(false);
        saveInTheFile(BaseSettings.PAUSED.key, false);
        EarlyApplication.set(null);
        Utils.setContext(null);
        try {
            assertFalse("no application yet answers off", PerformanceSwitches.skipSplashAd());
            assertEquals(2, PerformanceSwitches.framesToPrepare(2));
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void theRowsAreOnTheAppPageOnlyForThePatchesInTheBundle() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            assertFalse("nothing patched, nothing to show", hasAnyRow(activity));

            SettingsStatus.skipSplashAdEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            PreferenceScreen splashOnly = build(activity);
            assertNotNull(splashOnly.findPreference(Settings.SKIP_SPLASH_AD.key));
            assertNull(splashOnly.findPreference(Settings.SKIP_UPDATE_CHECKS.key));

            SettingsStatus.skipSplashAdEnabled = false;
            SettingsStatus.skipUpdateChecksEnabled = true;
            SettingsStatus.limitBackgroundTrafficEnabled = true;
            SettingsStatus.animatedImageCacheEnabled = true;
            PreferenceScreen rest = build(activity);
            assertNull(rest.findPreference(Settings.SKIP_SPLASH_AD.key));
            assertNotNull(rest.findPreference(Settings.SKIP_UPDATE_CHECKS.key));
            assertNotNull(rest.findPreference(Settings.DROP_ANIMATED_IMAGE_CACHE.key));
            Preference traffic = rest.findPreference(Settings.LIMIT_BACKGROUND_TRAFFIC.key);
            assertNotNull(traffic);
            assertFalse("without Skip notification setup the row leaves notifications out",
                    traffic.getSummary().toString().contains("notifications"));
        }
    }

    /** Patched with Skip notification setup, the traffic row says the same switch stops notifications. */
    @Test public void theTrafficRowSaysWhenItAlsoStopsNotifications() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.limitBackgroundTrafficEnabled = true;
            SettingsStatus.skipPushSetupEnabled = true;
            Preference traffic = build(activity).findPreference(Settings.LIMIT_BACKGROUND_TRAFFIC.key);
            assertNotNull(traffic);
            String summary = traffic.getSummary().toString();
            assertTrue(summary, summary.contains("You won't get notifications"));
            assertTrue("the restart note", summary.contains("Restart"));
        }
    }

    /**
     * The early read opens the application's own preferences file. A save through a setting
     * reaches it here only because the rule points the settings store at this test's application,
     * so the file is written as well, the way PrivacySwitchesTest does it. On a phone both are
     * the same file.
     */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }

    private static boolean hasAnyRow(PreferenceActivity activity) {
        PreferenceScreen screen = build(activity);
        for (BooleanSetting setting : SWITCHES) {
            if (screen.findPreference(setting.key) != null) return true;
        }
        return false;
    }
}
