/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

/** A switch read straight from the saved file, before Hushfeed's settings context exists. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EarlySwitchTest {
    private static final String KEY = "early_switch_test";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private SharedPreferences saved;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        saved = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        clear();
        EarlyApplication.set(context);
    }

    @After public void tearDown() {
        clear();
        EarlyApplication.reset();
    }

    private void clear() {
        saved.edit().remove(KEY).remove(EarlySwitch.PAUSED_KEY).remove(EarlySwitch.SAFE_MODE_KEY).commit();
        File marker = HushfeedPause.markerFile(context);
        if (marker != null) marker.delete();
    }

    @Test public void theKeysAreTheOnesThePauseSettingsSaveUnder() {
        assertEquals(BaseSettings.PAUSED.key, EarlySwitch.PAUSED_KEY);
        assertEquals(BaseSettings.SAFE_MODE.key, EarlySwitch.SAFE_MODE_KEY);
        assertEquals(Setting.preferences.name, Setting.PREFERENCES_NAME);
    }

    @Test public void aSwitchSavedOnReadsOnAndOneNeverSavedReadsOff() {
        assertFalse("nothing saved is the default, off", EarlySwitch.isOn(KEY));
        saved.edit().putBoolean(KEY, true).commit();
        assertTrue(EarlySwitch.isOn(KEY));
        saved.edit().putBoolean(KEY, false).commit();
        assertFalse(EarlySwitch.isOn(KEY));
    }

    @Test public void aStartThatRunsPausedReadsOff() throws IOException {
        saved.edit().putBoolean(KEY, true).commit();

        saved.edit().putBoolean(EarlySwitch.PAUSED_KEY, true).commit();
        assertFalse("the Pause switch", EarlySwitch.isOn(KEY));
        saved.edit().remove(EarlySwitch.PAUSED_KEY).commit();

        saved.edit().putBoolean(EarlySwitch.SAFE_MODE_KEY, true).commit();
        assertFalse("safe mode", EarlySwitch.isOn(KEY));
        saved.edit().remove(EarlySwitch.SAFE_MODE_KEY).commit();

        File marker = HushfeedPause.markerFile(context);
        assertNotNull(marker);
        //noinspection ResultOfMethodCallIgnored
        marker.getParentFile().mkdirs();
        assertTrue(marker.createNewFile());
        assertFalse("the marker file", EarlySwitch.isOn(KEY));
        assertTrue(marker.delete());

        assertTrue("on again once nothing pauses the start", EarlySwitch.isOn(KEY));
    }

    @Test public void withNoApplicationYetEverySwitchReadsOff() {
        saved.edit().putBoolean(KEY, true).commit();
        EarlyApplication.set(null);
        assertFalse(EarlySwitch.isOn(KEY));
    }

    @Test public void aFileThatCantBeReadReadsOff() {
        saved.edit().putString(KEY, "true").commit();
        assertFalse("a value of the wrong type is not a switch that's on", EarlySwitch.isOn(KEY));
    }

    @Test public void theApplicationComesFromActivityThread() {
        Context application = EarlySwitch.fromActivityThread();
        assertNotNull("ActivityThread.currentApplication answered nothing", application);
        assertEquals(context.getPackageName(), application.getPackageName());
    }
}
