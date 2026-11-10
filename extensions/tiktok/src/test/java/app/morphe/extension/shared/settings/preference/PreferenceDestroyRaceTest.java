package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/**
 * A DialogPreference's positive-button click and the dialog's close are two separate posted
 * Handler messages: {@code android.app.AlertController}'s own button handler records which
 * button was pressed on one posted message, then posts a second one that actually dismisses the
 * dialog. That second message is what calls {@code onDialogClosed(true)} and persists the typed
 * value, and it can run after this fragment is torn down.
 *
 * <p>{@link AbstractPreferenceFragment#onDestroy()} used to unregister the preference-change
 * listener synchronously, so when that second message ran late the persisted value never
 * reached the running {@link app.morphe.extension.shared.settings.Setting}, which then answered
 * with the old value until TikTok restarted, even though the row and the preference file both
 * already held the new one. This models that second, value-persisting message as a posted
 * Runnable the same way the framework posts it (same Looper, same zero delay), rather than
 * depending on AlertDialog's own dismiss-animation timing, which Robolectric does not resolve
 * deterministically inside a single test run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35})
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class PreferenceDestroyRaceTest {
    // Not a static field initializer: BaseSettings.DEBUG_LOG_FILTERS.key would run BaseSettings'
    // class initialization (and so Setting.preferences' SharedPrefCategory) as soon as this test
    // class loads, before SettingsContextRule has given Utils a context to read.
    private static String key() {
        return BaseSettings.DEBUG_LOG_FILTERS.key;
    }

    private static final String TYPED_VALUE = "network,playback";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class HarnessFragment extends TikTokPreferenceFragment {
        @Override protected void initialize() {
            Activity activity = getActivity();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(activity);
            setPreferenceScreen(screen);

            EditTextPreference textPref = new EditTextPreference(activity);
            textPref.setKey(key());
            textPref.setTitle("Debug log filters");
            screen.addPreference(textPref);
        }
    }

    public static final class TestActivity extends Activity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Before public void resetState() {
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        Setting.preferences.preferences.edit().remove(key()).commit();
    }

    @After public void restoreState() {
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        Setting.preferences.preferences.edit().remove(key()).commit();
    }

    @Test
    public void aTextSettingSavedAsTheScreenClosesReachesTheRunningSetting() {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            HarnessFragment fragment = new HarnessFragment();
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment)
                    .commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            EditTextPreference textPref =
                    (EditTextPreference) fragment.findPreference(key());
            assertNotNull(textPref);

            // Open the real dialog and type the new value, exactly as a tap on the row and a
            // real keyboard would.
            ReflectionHelpers.callInstanceMethod(textPref, "showDialog",
                    ReflectionHelpers.ClassParameter.from(Bundle.class, null));
            AlertDialog dialog = (AlertDialog) textPref.getDialog();
            assertNotNull(dialog);
            textPref.getEditText().setText(TYPED_VALUE);

            // The dialog's own close is the second posted message: it runs later, on the same
            // Looper, and is what actually persists the typed value into the Setting.
            Utils.runOnMainThread(() -> ReflectionHelpers.callInstanceMethod(textPref,
                    "onDialogClosed", ReflectionHelpers.ClassParameter.from(boolean.class, true)));

            // The fragment is torn down before that posted close has been allowed to run.
            owner.destroy();

            // Let the looper run: the close message, posted ahead of this fragment's own posted
            // teardown, delivers the change to the still-registered listener before it is gone.
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("the preference file holds the typed value",
                    TYPED_VALUE, Setting.preferences.preferences.getString(key(), null));
            assertEquals("the running Setting answers with the value saved as the screen closed,"
                            + " not the value it held before the dialog was opened",
                    TYPED_VALUE, BaseSettings.DEBUG_LOG_FILTERS.get());
        }
    }

    /**
     * OK is recorded, and the screen is destroyed while the dialog is still showing. The
     * dialog's dismiss, and so its persist, is then posted by PreferenceFragment's own
     * onDestroy (PreferenceManager.dispatchActivityDestroy reaches the DialogPreference), which
     * runs inside {@link AbstractPreferenceFragment#onDestroy()}'s call to super. A teardown
     * posted before that call ran ahead of the persist.
     */
    @Test
    public void aTextSettingWhoseDialogTheDestroyClosesReachesTheRunningSetting() {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            HarnessFragment fragment = new HarnessFragment();
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment)
                    .commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            EditTextPreference textPref =
                    (EditTextPreference) fragment.findPreference(key());
            ReflectionHelpers.callInstanceMethod(textPref, "showDialog",
                    ReflectionHelpers.ClassParameter.from(Bundle.class, null));
            AlertDialog dialog = (AlertDialog) textPref.getDialog();
            assertNotNull(dialog);
            textPref.getEditText().setText(TYPED_VALUE);

            textPref.onClick(dialog, android.content.DialogInterface.BUTTON_POSITIVE);
            owner.destroy();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("the preference file holds the typed value",
                    TYPED_VALUE, Setting.preferences.preferences.getString(key(), null));
            assertEquals("the running Setting kept the value from before the dialog",
                    TYPED_VALUE, BaseSettings.DEBUG_LOG_FILTERS.get());
        }
    }
}
