/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.profile.LocalProfileCounts.Metric;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.LocalProfileCountsPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en-rUS-w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class LocalProfileCountsCaptureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean oldPatchStatus;

    @Before public void setUp() {
        oldPatchStatus = SettingsStatus.followStatusEnabled;
        SettingsStatus.followStatusEnabled = true;
        PausedProcess.set(false);
        SignedInUser.idForTests = "capture-account";
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        Settings.LOCAL_PROFILE_COUNT_VALUES.save("");
        assertEquals(LocalProfileCounts.SaveResult.SAVED, LocalProfileCounts.save("capture-account",
                Map.of(Metric.FOLLOWERS, "0", Metric.FOLLOWING, "", Metric.LIKES, "12500")));
    }

    @After public void tearDown() {
        Settings.LOCAL_PROFILE_COUNTS.save(false);
        Settings.LOCAL_PROFILE_COUNT_VALUES.save("");
        SettingsStatus.followStatusEnabled = oldPatchStatus;
        SignedInUser.idForTests = null;
        PausedProcess.set(false);
        Utils.setIsDarkModeEnabled(true);
    }

    @Test public void theOwnProfileCountEditorRendersInDarkMode() throws Exception {
        captureEditor(true);
    }

    @Test @Config(qualifiers = "en-rUS-w480dp-h960dp-notnight-mdpi")
    public void theOwnProfileCountEditorRendersInLightMode() throws Exception {
        captureEditor(false);
    }

    private static void captureEditor(boolean dark) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsUi.syncDarkMode(activity);
            assertEquals(dark, SettingsUi.isDarkMode());
            TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "BEHAVIOR");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment).commit();
            settle(activity);
            SwitchPreference toggle = (SwitchPreference) fragment.findPreference(Settings.LOCAL_PROFILE_COUNTS.key);
            assertNotNull(toggle);
            assertFalse("editing must not enable this default-off feature", toggle.isChecked());
            LocalProfileCountsPreference editor = (LocalProfileCountsPreference)
                    fragment.findPreference("local_profile_counts_editor");
            assertNotNull(editor);
            String before = Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue();
            clickEditor(activity, fragment);
            AlertDialog dialog = (AlertDialog) editor.getDialog();
            assertNotNull("the actual App preference did not open its editor", dialog);
            assertTrue(dialog.isShowing());
            View decor = dialog.getWindow().getDecorView();
            assertField(decor, "followers", "Followers", "0");
            assertField(decor, "following", "Following", "");
            assertField(decor, "likes", "Likes", "12500");
            assertTrue(findText(decor, "Own profile counts").isAccessibilityHeading());
            assertNotNull(findText(decor, editor.getSummary().toString()));
            assertEquals("Save", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST));
            assertTrue(decor.getMeasuredHeight() > 0);
            UiCapture.save(decor, "dialogs/" + (dark ? "dark" : "light") + "/local-profile-counts.png",
                    480, decor.getMeasuredHeight());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals("opening and cancelling changed the account choices", before,
                    Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue());
            assertFalse(Settings.LOCAL_PROFILE_COUNTS.savedValue());
        }
    }

    private static void clickEditor(Activity activity, TikTokPreferenceFragment fragment) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int index = 0; index < list.getAdapter().getCount(); index++) {
            Object item = list.getAdapter().getItem(index);
            if (item instanceof Preference && "local_profile_counts_editor".equals(((Preference) item).getKey())) {
                list.setSelection(index);
                settle(activity);
                View row = list.getChildAt(index - list.getFirstVisiblePosition());
                assertNotNull("the own-profile count row was not laid out", row);
                assertTrue(list.performItemClick(row, index, list.getAdapter().getItemId(index)));
                settle(activity);
                return;
            }
        }
        throw new AssertionError("App has no Own profile counts row");
    }

    private static void assertField(View decor, String key, String label, String expected) {
        EditText field = decor.findViewWithTag("local_profile_count_" + key);
        assertNotNull(field);
        assertEquals(expected, field.getText().toString());
        assertEquals(InputType.TYPE_CLASS_NUMBER, field.getInputType() & InputType.TYPE_MASK_CLASS);
        TextView caption = findText(decor, label);
        assertNotNull(caption);
        assertEquals("the count field lost its accessible label", field.getId(), caption.getLabelFor());
    }

    private static TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void settle(Activity activity) {
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
