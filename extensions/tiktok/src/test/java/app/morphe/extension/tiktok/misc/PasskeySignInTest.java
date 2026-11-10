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
 * Skip passkey sign-in's switch (#102). It starts off, a paused build answers off, and without
 * the patch's flag TikTok's passkey check always runs as it ships. TikTok's saved-account list
 * can ask before the settings context exists, so that answer is read from the saved file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PasskeySignInTest {
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
        Settings.SKIP_PASSKEY_SIGN_IN.save(false);
        saveInTheFile(Settings.SKIP_PASSKEY_SIGN_IN.key, false);
        SettingsStatus.passkeySignInEnabled = false;
    }

    /**
     * The early read opens the application's own preferences file. A save through the setting
     * reaches it here only because the rule points the settings store at this test's application,
     * so the file is written as well, the way AiProfilingTest does it.
     */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    @Test public void theSwitchStartsOffAndOnlyItHidesPasskeys() {
        assertEquals(Boolean.FALSE, Settings.SKIP_PASSKEY_SIGN_IN.defaultValue);
        assertEquals(PasskeySignIn.SWITCH_KEY, Settings.SKIP_PASSKEY_SIGN_IN.key);
        assertFalse("each sign-in screen asks again, so no restart", Settings.SKIP_PASSKEY_SIGN_IN.rebootApp);

        SettingsStatus.passkeySignInEnabled = true;
        assertFalse("off, TikTok's check runs as it ships", PasskeySignIn.hidePasskeys());

        Settings.SKIP_PASSKEY_SIGN_IN.save(true);
        assertTrue(PasskeySignIn.hidePasskeys());

        PausedProcess.set(true);
        assertFalse("paused, TikTok's check runs as it ships", PasskeySignIn.hidePasskeys());
    }

    @Test public void withoutThePatchFlagTheCheckRunsAsShipped() {
        Settings.SKIP_PASSKEY_SIGN_IN.save(true);
        saveInTheFile(Settings.SKIP_PASSKEY_SIGN_IN.key, true);
        assertFalse(PasskeySignIn.hidePasskeys());

        Context application = RuntimeEnvironment.getApplication();
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertFalse("not even from the saved file", PasskeySignIn.hidePasskeys());
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void beforeTheSettingsContextTheSavedSwitchIsRead() {
        Context application = RuntimeEnvironment.getApplication();
        SettingsStatus.passkeySignInEnabled = true;
        Settings.SKIP_PASSKEY_SIGN_IN.save(true);
        saveInTheFile(Settings.SKIP_PASSKEY_SIGN_IN.key, true);
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertTrue(PasskeySignIn.hidePasskeys());
            EarlyApplication.set(null);
            assertFalse("no application yet answers off", PasskeySignIn.hidePasskeys());
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void theRowIsOnTheAppPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            assertNull(build(activity).findPreference(Settings.SKIP_PASSKEY_SIGN_IN.key));

            SettingsStatus.passkeySignInEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            Preference row = build(activity).findPreference(Settings.SKIP_PASSKEY_SIGN_IN.key);
            assertNotNull(row);
            assertEquals("Sign in without a passkey", String.valueOf(row.getTitle()));
            assertFalse("no restart note", String.valueOf(row.getSummary()).contains("Restart"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}
