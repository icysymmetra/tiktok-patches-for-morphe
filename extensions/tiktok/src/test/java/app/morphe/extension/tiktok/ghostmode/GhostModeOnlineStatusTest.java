/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The activity status report is held back only when Ghost mode and its own Hide online status
 * switch are both on, and the row for that switch shows only where Ghost mode is installed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class GhostModeOnlineStatusTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean installed;
    private boolean telemetryInstalled;

    @Before public void setUp() {
        installed = SettingsStatus.ghostModeEnabled;
        telemetryInstalled = SettingsStatus.disableTelemetryEnabled;
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(false);
        HookStatus.clear();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.ghostModeEnabled = installed;
        SettingsStatus.disableTelemetryEnabled = telemetryInstalled;
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(false);
        HookStatus.clear();
        Utils.setActivity(null);
    }

    @Test public void theSwitchIsOffByDefaultAndTheReportGoesThrough() {
        assertFalse(Settings.GHOST_HIDE_ONLINE_STATUS.defaultValue);
        assertFalse(Settings.GHOST_HIDE_ONLINE_STATUS.savedValue());
        Settings.GHOST_MODE.save(true);
        assertFalse("Ghost mode alone must leave online status visible", GhostMode.shouldBlockOnlineStatus());
    }

    @Test public void bothSwitchesOnHoldsTheReportBack() {
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(true);
        assertTrue(GhostMode.shouldBlockOnlineStatus());
        assertTrue("an observed block moves the Ghost mode status",
                GhostMode.status() == GhostMode.Status.BLOCKED);
    }

    @Test public void turningTheSwitchOffAgainLetsTheNextReportThrough() {
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(true);
        assertTrue(GhostMode.shouldBlockOnlineStatus());
        Settings.GHOST_HIDE_ONLINE_STATUS.save(false);
        assertFalse(GhostMode.shouldBlockOnlineStatus());
    }

    @Test public void theSwitchAloneDoesNothingWhileGhostModeIsOff() {
        Settings.GHOST_HIDE_ONLINE_STATUS.save(true);
        assertFalse(GhostMode.shouldBlockOnlineStatus());
    }

    @Test public void aPausedProcessSendsTheReport() {
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(true);
        PausedProcess.set(true);
        assertFalse(GhostMode.shouldBlockOnlineStatus());
        PausedProcess.set(false);
        assertTrue(GhostMode.shouldBlockOnlineStatus());
    }

    @Test public void theRowShowsBesideGhostModeWhenItIsInstalled() {
        SettingsStatus.ghostModeEnabled = true;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment page = privacy(owner.get());
            Preference row = page.findPreference(Settings.GHOST_HIDE_ONLINE_STATUS.key);
            assertNotNull("no Hide online status row", row);
            assertEquals("Hide online status", row.getTitle().toString());
            assertNotNull(page.findPreference(Settings.GHOST_MODE.key));
        }
    }

    @Test public void theRowIsAbsentWhenGhostModeIsNotInTheBuild() {
        SettingsStatus.ghostModeEnabled = false;
        SettingsStatus.disableTelemetryEnabled = true;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment page = privacy(owner.get());
            assertNull(page.findPreference(Settings.GHOST_HIDE_ONLINE_STATUS.key));
            assertNull(page.findPreference(Settings.GHOST_MODE.key));
        }
    }

    private static TikTokPreferenceFragment privacy(Activity activity) {
        Utils.setContext(activity);
        Utils.setActivity(activity);
        TikTokPreferenceFragment page = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "PRIVACY");
        page.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, page).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return page;
    }
}
