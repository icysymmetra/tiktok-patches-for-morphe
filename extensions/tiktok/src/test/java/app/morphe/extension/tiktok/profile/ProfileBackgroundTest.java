/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
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
 * Profile background's switch (#80). It starts off, a paused build answers off, and with it on
 * TikTok's gate answers yes for the editor and for profiles alike, and the profile requests carry
 * the allow-list value the server returns a background for. That value is snapshotted once per
 * process, so the switch asks for a restart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileBackgroundTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PROFILE_BACKGROUND.resetToDefault();
        SettingsStatus.profileBackgroundEnabled = false;
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOffAndOnlyItOpensTheGate() {
        assertEquals("profile_background", Settings.PROFILE_BACKGROUND.key);
        assertEquals(Boolean.FALSE, Settings.PROFILE_BACKGROUND.defaultValue);
        assertTrue("the allow-list snapshot is taken once per process", Settings.PROFILE_BACKGROUND.rebootApp);

        assertFalse("off, TikTok decides the editor", ProfileBackground.openGate(true));
        assertFalse("off, TikTok decides profiles", ProfileBackground.openGate(false));

        Settings.PROFILE_BACKGROUND.save(true);
        assertTrue(ProfileBackground.openGate(true));
        assertTrue(ProfileBackground.openGate(false));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("profile background"));

        PausedProcess.set(true);
        assertFalse("paused, TikTok decides", ProfileBackground.openGate(true));
    }

    /**
     * 0.72.0 opened the editor but sent TikTok's own allow-list value, 0 outside the rollout, with
     * the profile requests, so the server left a saved background out after a restart (#80).
     */
    @Test public void theSwitchSendsTheAllowListValueTheServerWants() {
        assertEquals("off, TikTok's value goes out", 0, ProfileBackground.allowList(0));
        Settings.PROFILE_BACKGROUND.save(true);
        assertEquals(1, ProfileBackground.allowList(0));
        assertEquals("an account already in the rollout keeps its value", 1, ProfileBackground.allowList(1));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("profile background: 1 found"));

        PausedProcess.set(true);
        assertEquals("paused, TikTok's value goes out", 0, ProfileBackground.allowList(0));
        PausedProcess.set(false);
        Utils.setContext(null);
        try {
            assertEquals(0, ProfileBackground.allowList(0));
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
    }

    @Test public void withoutAContextTheGateIsTikToks() {
        Settings.PROFILE_BACKGROUND.save(true);
        Utils.setContext(null);
        try {
            assertFalse(ProfileBackground.openGate(true));
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
        assertTrue(ProfileBackground.openGate(true));
    }

    @Test public void theRowIsOnTheAppPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            assertNull(build(activity).findPreference(Settings.PROFILE_BACKGROUND.key));

            SettingsStatus.profileBackgroundEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            PreferenceScreen screen = build(activity);
            Preference row = screen.findPreference(Settings.PROFILE_BACKGROUND.key);
            assertNotNull(row);
            assertEquals("Profile background", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("may still refuse to save"));
            assertTrue("the allow-list value counts from the next start", summary.endsWith(
                    "hasn't offered it to yet. " + app.morphe.extension.tiktok.settings.preference.TogglePreference.RESTART_SENTENCE));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}
