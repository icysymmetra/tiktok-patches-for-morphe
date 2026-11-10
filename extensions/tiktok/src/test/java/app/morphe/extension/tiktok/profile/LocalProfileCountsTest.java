/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.preference.SwitchPreference;
import android.text.SpannableString;
import android.view.View;
import android.widget.EditText;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.profile.LocalProfileCounts.Metric;
import app.morphe.extension.tiktok.profile.LocalProfileCounts.SaveResult;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.LocalProfileCountsPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en-rUS")
@SuppressWarnings("deprecation")
public class LocalProfileCountsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PausedProcess.set(false);
        SignedInUser.idForTests = "account-a";
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        Settings.LOCAL_PROFILE_COUNT_VALUES.save("");
        SettingsStatus.followStatusEnabled = true;
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        Settings.LOCAL_PROFILE_COUNT_VALUES.save("");
        SettingsStatus.followStatusEnabled = false;
        SignedInUser.idForTests = null;
    }

    @Test public void theExistingPatchKeepsCountOverridesOffAndPortableBackupsExcludeAccountChoices() {
        assertEquals(Boolean.FALSE, Settings.LOCAL_PROFILE_COUNTS.defaultValue);
        assertTrue(Settings.LOCAL_PROFILE_COUNTS.rebootApp);
        assertTrue(Settings.LOCAL_PROFILE_COUNT_VALUES.rebootApp);
        assertFalse(Settings.LOCAL_PROFILE_COUNT_VALUES.includeWithImportExport);
        save("account-a", "23", "", "");
        assertFalse(LocalProfileCounts.enabled());
        assertNull(override("account-a", Metric.FOLLOWERS));
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        SettingsStatus.followStatusEnabled = false;
        assertFalse(LocalProfileCounts.enabled());
        assertNull(override("account-a", Metric.FOLLOWERS));
    }

    @Test public void eachAccountKeepsItsOwnChoicesAndZeroIsDifferentFromAnEmptyField() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        save("account-a", "0", "", "9223372036854775807");
        assertEquals(Integer.valueOf(0), override("account-a", Metric.FOLLOWERS));
        assertNull(override("account-a", Metric.FOLLOWING));
        assertEquals(Long.valueOf(Long.MAX_VALUE), override("account-a", Metric.LIKES));
        SignedInUser.idForTests = "account-b";
        assertNull(override("account-a", Metric.FOLLOWERS));
        assertNull(override("account-b", Metric.FOLLOWERS));
        save("account-b", "7", "12", "0");
        assertEquals(Integer.valueOf(7), override("account-b", Metric.FOLLOWERS));
        assertEquals(Long.valueOf(0), override("account-b", Metric.LIKES));
        SignedInUser.idForTests = "account-a";
        assertEquals(Integer.valueOf(0), override("account-a", Metric.FOLLOWERS));
        assertEquals("", LocalProfileCounts.value("account-a", Metric.FOLLOWING));
        assertEquals("7", LocalProfileCounts.value("account-b", Metric.FOLLOWERS));
    }

    @Test public void onlyTheExplicitSignedInProfileAndKnownMetricCanChange() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        save("account-a", "900", "4", "6");
        for (String viewed : new String[]{null, "", "0", "account-b"}) {
            assertNull(override(viewed, Metric.FOLLOWERS));
        }
        assertNull(LocalProfileCounts.override("account-a", "relation_info_posts"));
        assertNull(LocalProfileCounts.override("account-a", null));
        SignedInUser.idForTests = "";
        assertFalse(LocalProfileCounts.enabled());
        assertNull(override("account-a", Metric.FOLLOWERS));
        SignedInUser.idForTests = "0";
        assertFalse(LocalProfileCounts.enabled());
    }

    @Test public void pausePreservesChoicesAndReturnsTheExactNativeTextObject() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        save("account-a", "22", "", "");
        Number selected = override("account-a", Metric.FOLLOWERS);
        String saved = Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue();
        SpannableString nativeText = new SpannableString("0");
        PausedProcess.set(true);
        assertNull(override("account-a", Metric.FOLLOWERS));
        assertSame(nativeText, LocalProfileCounts.choose(nativeText, "22", selected,
                "account-a", Metric.FOLLOWERS.component));
        assertEquals(saved, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
        assertEquals(Boolean.TRUE, Settings.LOCAL_PROFILE_COUNTS.savedValue());
        PausedProcess.set(false);
        assertEquals(Integer.valueOf(22), override("account-a", Metric.FOLLOWERS));
    }

    @Test public void aChangedAccountOrChoiceDuringFormattingKeepsTheOriginalCount() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        save("account-a", "22", "", "");
        Number selected = override("account-a", Metric.FOLLOWERS);
        CharSequence nativeText = new SpannableString("1.2K");
        SignedInUser.idForTests = "account-b";
        assertSame(nativeText, LocalProfileCounts.choose(nativeText, "22", selected, "account-a", Metric.FOLLOWERS.component));
        SignedInUser.idForTests = "account-a";
        save("account-a", "23", "", "");
        assertSame(nativeText, LocalProfileCounts.choose(nativeText, "22", selected, "account-a", Metric.FOLLOWERS.component));
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        assertSame(nativeText, LocalProfileCounts.choose(nativeText, "23", Integer.valueOf(23), "account-a", Metric.FOLLOWERS.component));
    }

    @Test public void bindingARecycledRowDoesNotRetainAnotherProfilesCount() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        save("account-a", "22", "", "");
        CharSequence local = new SpannableString("22");
        Number selected = override("account-a", Metric.FOLLOWERS);
        assertSame(local, LocalProfileCounts.choose("native-a", local, selected, "account-a", Metric.FOLLOWERS.component));
        CharSequence other = new SpannableString("native-b");
        assertSame(other, LocalProfileCounts.choose(other, local, selected, "account-b", Metric.FOLLOWERS.component));
        assertSame(other, LocalProfileCounts.choose(other, null, selected, "account-a", Metric.FOLLOWERS.component));
        assertSame(other, LocalProfileCounts.choose(other, local, null, "account-a", Metric.FOLLOWERS.component));
    }

    @Test public void anEditorOpenedForAnotherAccountCannotSave() {
        save("account-a", "22", "", "");
        String before = Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue();
        SignedInUser.idForTests = "account-b";
        assertEquals(SaveResult.ACCOUNT_CHANGED, LocalProfileCounts.save("account-a", fields("23", "", "")));
        assertEquals(before, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
        SignedInUser.idForTests = "";
        assertEquals(SaveResult.ACCOUNT_CHANGED, LocalProfileCounts.save("", fields("23", "", "")));
        assertEquals(before, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
    }

    @Test public void invalidNumbersAreRefusedWithoutReplacingAnySavedChoices() {
        save("account-a", "22", "", "");
        String before = Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue();
        for (String invalid : List.of("-1", "+1", "1.2", "1e3", "1,000", "１２", "2147483648", "99999999999999999999")) {
            assertFalse(invalid, LocalProfileCounts.validValue(Metric.FOLLOWERS, invalid));
            assertEquals(invalid, SaveResult.INVALID_VALUE, LocalProfileCounts.save("account-a", fields(invalid, "", "")));
            assertEquals(before, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
        }
        assertFalse(LocalProfileCounts.validValue(Metric.LIKES, "9223372036854775808"));
        assertEquals(SaveResult.INVALID_VALUE, LocalProfileCounts.save("account-a", Map.of()));
        assertEquals(before, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
    }

    @Test public void clearingOneAccountsFieldsKeepsTheOtherAccountsAndTheirZeroChoices() {
        save("account-a", "22", "", "");
        SignedInUser.idForTests = "account-b";
        save("account-b", "0", "", "");
        SignedInUser.idForTests = "account-a";
        save("account-a", "", " ", "");
        assertEquals("", LocalProfileCounts.value("account-a", Metric.FOLLOWERS));
        assertEquals("0", LocalProfileCounts.value("account-b", Metric.FOLLOWERS));
        SignedInUser.idForTests = "account-b";
        save("account-b", "", "", "");
        assertEquals("", Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
    }

    @Test public void aFreshDecodeKeepsTheExactIntegerAndLongTypes() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        save("account-a", "2147483647", "0000", "9223372036854775807");
        ReflectionHelpers.setStaticField(LocalProfileCounts.class, "cached", null);
        assertTrue(override("account-a", Metric.FOLLOWERS) instanceof Integer);
        assertTrue(override("account-a", Metric.LIKES) instanceof Long);
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), override("account-a", Metric.FOLLOWERS));
        assertEquals(Integer.valueOf(0), override("account-a", Metric.FOLLOWING));
        assertEquals(Long.valueOf(Long.MAX_VALUE), override("account-a", Metric.LIKES));
    }

    @Test public void damagedOrFutureChoicesStayUntouchedAndCannotReplaceTheNativeCount() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        for (String bad : List.of("broken", "{\"schema\":2,\"accounts\":{}}",
                "{\"schema\":1,\"accounts\":{\"account-a\":{\"followers\":1.2}}}",
                "{\"schema\":1,\"accounts\":{\"account-a\":{\"followers\":\"12\"}}}",
                "{\"schema\":1,\"accounts\":{\"account-a\":{\"posts\":12}}}")) {
            Settings.LOCAL_PROFILE_COUNT_VALUES.save(bad);
            assertNull(override("account-a", Metric.FOLLOWERS));
            assertEquals(SaveResult.FAILED, LocalProfileCounts.save("account-a", fields("22", "", "")));
            assertEquals(bad, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
        }
    }

    @Test public void aFailedCommitKeepsTheOldAccountChoicesLiveAndOnDisk() {
        Settings.LOCAL_PROFILE_COUNTS.save(true);
        save("account-a", "22", "", "");
        String before = Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue();
        SharedPreferences store = ReflectionHelpers.getField(Setting.preferences, "preferences");
        SharedPreferences failed = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    Object result = method.invoke(store, args);
                    if (!method.getName().equals("edit")) return result;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, operation, values) -> {
                                if (operation.getName().equals("commit")) return false;
                                Object changed = operation.invoke(editor, values);
                                return changed == editor ? editorProxy : changed;
                            });
                });
        ReflectionHelpers.setField(Setting.preferences, "preferences", failed);
        try {
            assertEquals(SaveResult.FAILED, LocalProfileCounts.save("account-a", fields("23", "", "")));
            assertEquals(before, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
            assertEquals(before, store.getString(Settings.LOCAL_PROFILE_COUNT_VALUES.key, ""));
            assertEquals(Integer.valueOf(22), override("account-a", Metric.FOLLOWERS));
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", store);
        }
    }

    @Test public void theAppProfileRowsArePatchGatedAndStateTheirLocalScopeAndRestart() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.followStatusEnabled = false;
            assertNull(open(activity).findPreference(Settings.LOCAL_PROFILE_COUNTS.key));
            SettingsStatus.followStatusEnabled = true;
            var fragment = open(activity);
            SwitchPreference toggle = (SwitchPreference) fragment.findPreference(Settings.LOCAL_PROFILE_COUNTS.key);
            assertNotNull(toggle);
            assertFalse(toggle.isChecked());
            assertEquals("Local profile counts", toggle.getTitle());
            assertTrue(toggle.getSummary().toString().contains("Other people see the real counts."));
            assertTrue(toggle.getSummary().toString().endsWith(TogglePreference.RESTART_SENTENCE));
            assertNotNull(fragment.findPreference("local_profile_counts_editor"));
            assertEquals(Boolean.TRUE, Settings.SHOW_FOLLOW_STATUS.defaultValue);
        }
    }

    @Test public void theEditorDisplaysBlankAndZeroSeparatelyAndKeepsInvalidInputOpen() {
        save("account-a", "0", "", "42");
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LocalProfileCountsPreference row = (LocalProfileCountsPreference) open(activity).findPreference("local_profile_counts_editor");
            ReflectionHelpers.callInstanceMethod(row, "onClick");
            AlertDialog dialog = (AlertDialog) row.getDialog();
            EditText followers = dialog.getWindow().getDecorView().findViewWithTag("local_profile_count_followers");
            EditText following = dialog.getWindow().getDecorView().findViewWithTag("local_profile_count_following");
            assertEquals("0", followers.getText().toString());
            assertEquals("", following.getText().toString());
            String before = Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue();
            followers.setText("2147483648");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertEquals(before, Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
            dialog.dismiss();
        }
    }

    @Test public void typedCountsSurviveARecreatedSettingsScreenForTheSameAccountOnly() {
        save("account-a", "0", "", "42");
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LocalProfileCountsPreference row = (LocalProfileCountsPreference) open(activity).findPreference("local_profile_counts_editor");
            ReflectionHelpers.callInstanceMethod(row, "onClick");
            AlertDialog dialog = (AlertDialog) row.getDialog();
            field(dialog, "followers").setText("12");
            field(dialog, "following").setText("7");
            field(dialog, "likes").setText("");
            Bundle saved = new Bundle();
            row.saveHierarchyState(saved);
            dialog.dismiss();

            // A dark mode switch rebuilds the screen and its dialog, with new generated field ids.
            LocalProfileCountsPreference recreated = (LocalProfileCountsPreference) open(activity).findPreference("local_profile_counts_editor");
            assertNotSame(row, recreated);
            AtomicInteger ids = ReflectionHelpers.getStaticField(View.class, "sNextGeneratedId");
            int beforeReopen = ids.get();
            recreated.restoreHierarchyState(parcelled(saved));
            AlertDialog reopened = (AlertDialog) recreated.getDialog();
            assertNotNull(reopened);
            assertTrue(reopened.isShowing());
            assertNotEquals(field(dialog, "followers").getId(), field(reopened, "followers").getId());
            assertEquals("12", field(reopened, "followers").getText().toString());
            assertEquals("7", field(reopened, "following").getText().toString());
            assertEquals("", field(reopened, "likes").getText().toString());
            Bundle again = new Bundle();
            recreated.saveHierarchyState(again);
            reopened.dismiss();

            // Typed counts never carry over to another signed-in account, even when the id
            // counter starts over after process death and the new fields get the old ids.
            SignedInUser.idForTests = "account-b";
            save("account-b", "3", "", "9");
            LocalProfileCountsPreference other = (LocalProfileCountsPreference) open(activity).findPreference("local_profile_counts_editor");
            ids.set(beforeReopen);
            other.restoreHierarchyState(parcelled(again));
            AlertDialog otherDialog = (AlertDialog) other.getDialog();
            assertEquals(field(reopened, "followers").getId(), field(otherDialog, "followers").getId());
            assertEquals("3", field(otherDialog, "followers").getText().toString());
            assertEquals("", field(otherDialog, "following").getText().toString());
            assertEquals("9", field(otherDialog, "likes").getText().toString());
            otherDialog.dismiss();
        }
    }

    /** A saved state as the system hands it back: written to a parcel and read again. */
    private static Bundle parcelled(Bundle state) {
        Parcel parcel = Parcel.obtain();
        parcel.writeBundle(state);
        parcel.setDataPosition(0);
        return parcel.readBundle(LocalProfileCountsPreference.class.getClassLoader());
    }

    private static EditText field(AlertDialog dialog, String metric) {
        return dialog.getWindow().getDecorView().findViewWithTag("local_profile_count_" + metric);
    }

    private static Number override(String uid, Metric metric) {
        return LocalProfileCounts.override(uid, metric.component);
    }

    private static Map<Metric, String> fields(String followers, String following, String likes) {
        Map<Metric, String> fields = new EnumMap<>(Metric.class);
        fields.put(Metric.FOLLOWERS, followers);
        fields.put(Metric.FOLLOWING, following);
        fields.put(Metric.LIKES, likes);
        return fields;
    }

    private static void save(String uid, String followers, String following, String likes) {
        assertEquals(SaveResult.SAVED, LocalProfileCounts.save(uid, fields(followers, following, likes)));
    }

    private static TikTokPreferenceFragment open(Activity activity) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "BEHAVIOR");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return fragment;
    }
}
