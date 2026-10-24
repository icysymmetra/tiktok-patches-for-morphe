/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.SwitchPreference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Root answers retain TikTok's schemas and use the ordinary privacy-switch safety rules. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en-rUS")
@SuppressWarnings("deprecation")
public class RootSignalsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PausedProcess.set(false);
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        Settings.HIDE_ROOT.save(false);
        Settings.HIDE_VPN.save(false);
        Settings.BLOCK_ADVERTISING_ID.save(false);
        Settings.BLOCK_CLIPBOARD_READS.save(false);
        SettingsStatus.devicePrivacyGuardEnabled = false;
    }

    @After public void tearDown() {
        Utils.setContext(context);
        PausedProcess.set(false);
        EarlyApplication.reset();
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        Settings.HIDE_ROOT.save(false);
        Settings.HIDE_VPN.save(false);
        Settings.BLOCK_ADVERTISING_ID.save(false);
        Settings.BLOCK_CLIPBOARD_READS.save(false);
        SettingsStatus.devicePrivacyGuardEnabled = false;
        File marker = HushfeedPause.markerFile(context);
        if (marker != null && marker.exists()) assertTrue(marker.delete());
    }

    @Test public void selectingThePatchKeepsRootProbesNativeUntilTheRestartRequiredSwitchIsOn() {
        assertEquals(Boolean.FALSE, Settings.HIDE_ROOT.defaultValue);
        assertEquals(Settings.HIDE_ROOT.key, DevicePrivacyGuard.ROOT_KEY);
        assertTrue("native collectors can cache a previous answer", Settings.HIDE_ROOT.rebootApp);
        assertFalse(DevicePrivacyGuard.hideRoot());
        Settings.HIDE_ROOT.save(true);
        assertTrue(DevicePrivacyGuard.hideRoot());
        Settings.HIDE_ROOT.save(false);
        assertFalse(DevicePrivacyGuard.hideRoot());
    }

    @Test public void processPauseRunsNativeProbesAndPreservesTheSavedChoice() {
        Settings.HIDE_ROOT.save(true);
        PausedProcess.set(true);
        assertFalse(DevicePrivacyGuard.hideRoot());
        assertEquals(Boolean.TRUE, Settings.HIDE_ROOT.savedValue());
        PausedProcess.set(false);
        assertTrue(DevicePrivacyGuard.hideRoot());
    }

    @Test public void earlyRootChecksReadTheSavedFileBeforeTheExtensionContextExists() {
        saveInFile(Settings.HIDE_ROOT.key, true);
        EarlyApplication.set(context);
        Utils.setContext(null);
        assertTrue(DevicePrivacyGuard.hideRoot());
        saveInFile(Settings.HIDE_ROOT.key, false);
        assertFalse(DevicePrivacyGuard.hideRoot());
    }

    @Test public void earlyPauseAndSafeModeBothRunNativeProbes() {
        saveInFile(Settings.HIDE_ROOT.key, true);
        EarlyApplication.set(context);
        Utils.setContext(null);
        for (String key : List.of(BaseSettings.PAUSED.key, BaseSettings.SAFE_MODE.key)) {
            saveInFile(key, true);
            assertFalse(key, DevicePrivacyGuard.hideRoot());
            saveInFile(key, false);
            assertTrue(key, DevicePrivacyGuard.hideRoot());
        }
    }

    @Test public void anEarlyPauseMarkerRunsNativeProbes() throws Exception {
        saveInFile(Settings.HIDE_ROOT.key, true);
        File marker = HushfeedPause.markerFile(context);
        assertNotNull(marker);
        assertTrue(marker.createNewFile());
        EarlyApplication.set(context);
        Utils.setContext(null);
        assertFalse(DevicePrivacyGuard.hideRoot());
        assertTrue(marker.delete());
        assertTrue(DevicePrivacyGuard.hideRoot());
    }

    @Test public void noEarlyApplicationLeavesNativeProbesIntact() {
        saveInFile(Settings.HIDE_ROOT.key, true);
        EarlyApplication.set(null);
        Utils.setContext(null);
        assertFalse(DevicePrivacyGuard.hideRoot());
    }

    @Test public void anUnreadableEarlyPreferenceFileLeavesNativeProbesIntact() {
        EarlyApplication.set(new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new IllegalStateException("unreadable test store");
            }
        });
        Utils.setContext(null);
        assertFalse(DevicePrivacyGuard.hideRoot());
    }

    @Test public void rootSummariesKeepNativeIntegerCountsBooleanResultsAndEmptyItems() throws Exception {
        assertSummary(DevicePrivacyGuard.hiddenRootPackages(), "rootPackage");
        assertSummary(DevicePrivacyGuard.hiddenRootPaths(), "rootPath");
    }

    @Test public void commandResultsKeepNativeStringValuesIncludingUidZero() throws Exception {
        Map<String, Object> which = DevicePrivacyGuard.hiddenWhichSu();
        Map<String, Object> command = DevicePrivacyGuard.hiddenSuCommand();
        assertEquals(Map.of("state", "not_detected", "path", ""), which);
        assertEquals(Map.of("state", "not_detected", "uidZero", "false"), command);
        JSONObject serialized = new JSONObject(new JSONObject(command).toString());
        assertEquals("false", serialized.get("uidZero"));
        assertTrue(serialized.get("uidZero") instanceof String);
    }

    @Test public void everyAnswerOwnsItsMutableMapsAndLists() {
        for (Supplier<Map<String, Object>> answer : List.<Supplier<Map<String, Object>>>of(
                DevicePrivacyGuard::hiddenRootPackages, DevicePrivacyGuard::hiddenRootPaths,
                DevicePrivacyGuard::hiddenWhichSu, DevicePrivacyGuard::hiddenSuCommand)) {
            Map<String, Object> first = answer.get();
            Map<String, Object> second = answer.get();
            assertNotSame(first, second);
            first.put("state", "detected");
            assertFalse("one collector must not poison another", "detected".equals(second.get("state")));
            if (first.containsKey("summary")) {
                summary(first).put("hit", 99);
                items(first).add(Map.of("state", "detected"));
                assertNotSame(summary(first), summary(second));
                assertNotSame(items(first), items(second));
                assertEquals(0, summary(second).get("hit"));
                assertTrue(items(second).isEmpty());
            }
        }
    }

    @Test public void rootPrivacyIsIndependentOfTheOtherDevicePrivacySwitches() {
        Settings.HIDE_VPN.save(true);
        Settings.BLOCK_ADVERTISING_ID.save(true);
        Settings.BLOCK_CLIPBOARD_READS.save(true);
        assertFalse(DevicePrivacyGuard.hideRoot());
        Settings.HIDE_VPN.save(false);
        Settings.BLOCK_ADVERTISING_ID.save(false);
        Settings.BLOCK_CLIPBOARD_READS.save(false);
        Settings.HIDE_ROOT.save(true);
        assertTrue(DevicePrivacyGuard.hideRoot());
        assertEquals("real-id", DevicePrivacyGuard.interceptAdvertisingIdRead("real-id"));
        assertFalse(DevicePrivacyGuard.interceptLimitAdTracking(false));
    }

    @Test public void privacyShowsOneDefaultOffRootRowWithRestartWordingOnlyWhenThePatchIsPresent() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            assertNull(openPrivacy(activity).findPreference(Settings.HIDE_ROOT.key));
            SettingsStatus.devicePrivacyGuardEnabled = true;
            var privacy = openPrivacy(activity);
            SwitchPreference row = (SwitchPreference) privacy.findPreference(Settings.HIDE_ROOT.key);
            assertNotNull(row);
            assertEquals("Hide root", row.getTitle());
            assertEquals("Hide root status and details about root tools from TikTok. "
                    + TogglePreference.RESTART_SENTENCE, row.getSummary());
            assertFalse(row.isChecked());
            assertEquals(1, java.util.stream.IntStream.range(0, privacy.getPreferenceScreen().getPreferenceCount())
                    .mapToObj(index -> privacy.getPreferenceScreen().getPreference(index).getKey())
                    .filter(Settings.HIDE_ROOT.key::equals).count());
        }
    }

    private static void assertSummary(Map<String, Object> value, String kind) throws Exception {
        assertEquals(Set.of("summary", "items"), value.keySet());
        assertEquals(Map.of("hit", 0, "unknown", 0, kind, false), summary(value));
        assertTrue(summary(value).get("hit") instanceof Integer);
        assertTrue(summary(value).get("unknown") instanceof Integer);
        assertTrue(summary(value).get(kind) instanceof Boolean);
        assertTrue(items(value).isEmpty());
        JSONObject serialized = new JSONObject(new JSONObject(value).toString());
        assertEquals(0, serialized.getJSONObject("summary").get("hit"));
        assertEquals(false, serialized.getJSONObject("summary").get(kind));
        assertEquals(0, serialized.getJSONArray("items").length());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> value) {
        return (Map<String, Object>) value.get("summary");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> items(Map<String, Object> value) {
        return (List<Object>) value.get("items");
    }

    private void saveInFile(String key, boolean value) {
        assertTrue(context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit());
    }

    private static TikTokPreferenceFragment openPrivacy(Activity activity) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "PRIVACY");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return fragment;
    }
}
