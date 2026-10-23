/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

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
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Enable voice comments' switch: TikTok's own gate answers until the reader turns it on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceCommentsTest {
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
        Settings.ENABLE_VOICE_COMMENTS.save(false);
        saveInTheFile(Settings.ENABLE_VOICE_COMMENTS.key, false);
        SettingsStatus.voiceCommentsEnabled = false;
        SettingsStatus.lengthLimitsEnabled = false;
    }

    /**
     * The early read opens the application's own preferences file, which a save through the
     * setting doesn't reach once another class has loaded Setting first, so write it as well.
     */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    @Test public void theSwitchStartsOffAndPauseHandsTheGateBack() {
        assertEquals(Boolean.FALSE, Settings.ENABLE_VOICE_COMMENTS.defaultValue);
        assertEquals(VoiceComments.SWITCH_KEY, Settings.ENABLE_VOICE_COMMENTS.key);
        assertFalse(VoiceComments.isOn());

        Settings.ENABLE_VOICE_COMMENTS.save(true);
        assertTrue(VoiceComments.isOn());

        assertTrue("TikTok may keep the answer for a comment box it already built",
                Settings.ENABLE_VOICE_COMMENTS.rebootApp);

        PausedProcess.set(true);
        assertFalse(VoiceComments.isOn());
    }

    @Test public void beforeTheSettingsContextTheSavedSwitchIsRead() {
        Context application = RuntimeEnvironment.getApplication();
        Settings.ENABLE_VOICE_COMMENTS.save(true);
        saveInTheFile(Settings.ENABLE_VOICE_COMMENTS.key, true);
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertTrue(VoiceComments.isOn());
            EarlyApplication.set(null);
            assertFalse("no application yet answers off", VoiceComments.isOn());
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void theRowIsUnderWritingOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.ENABLE_VOICE_COMMENTS.key));

            // On its own, without Lift text length limits, it still gets the Writing heading.
            SettingsStatus.voiceCommentsEnabled = true;
            assertTrue(CommentsPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.ENABLE_VOICE_COMMENTS.key));
            assertNull(with.findPreference(Settings.LIFT_LENGTH_LIMITS.key));
        }
    }
}
