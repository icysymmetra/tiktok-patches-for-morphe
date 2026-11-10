package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, qualifiers = "en-w480dp-h960dp-night-mdpi", fontScale = 1f)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class SettingsPreferenceOwnershipTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean oldAdvancedDownloads, oldAudio, oldSilent;
    private String oldQuality, oldExternalPackage;
    private SharedPreferences oldPreferences;
    private java.lang.reflect.Field preferencesField;

    @Before public void prepareDownloads() throws Exception {
        // Robolectric reuses Setting's class but resets ContextImpl's preference cache and
        // application directory between methods. Give the static holder this test's real store.
        oldPreferences = Setting.preferences.preferences;
        preferencesField = app.morphe.extension.shared.settings.preference.SharedPrefCategory.class
                .getDeclaredField("preferences");
        preferencesField.setAccessible(true);
        preferencesField.set(Setting.preferences,
                RuntimeEnvironment.getApplication().getSharedPreferences(Setting.preferences.name, 0));
        oldAdvancedDownloads = SettingsStatus.advancedDownloadsEnabled;
        oldAudio = Settings.DOWNLOAD_AUDIO_TRACK.get();
        oldSilent = Settings.DOWNLOAD_WITHOUT_SOUND.get();
        oldQuality = Settings.DOWNLOAD_VIDEO_QUALITY.get();
        oldExternalPackage = Settings.EXTERNAL_DOWNLOADER_PACKAGE.get();
        SettingsStatus.advancedDownloadsEnabled = true;
        Setting.saveAll(Map.of(Settings.DOWNLOAD_AUDIO_TRACK, false,
                Settings.DOWNLOAD_WITHOUT_SOUND, true,
                Settings.DOWNLOAD_VIDEO_QUALITY, "auto",
                Settings.EXTERNAL_DOWNLOADER_PACKAGE, ""));
    }

    @After public void restoreDownloads() throws Exception {
        try {
            Setting.saveAll(Map.of(Settings.DOWNLOAD_AUDIO_TRACK, oldAudio,
                    Settings.DOWNLOAD_WITHOUT_SOUND, oldSilent,
                    Settings.DOWNLOAD_VIDEO_QUALITY, oldQuality,
                    Settings.EXTERNAL_DOWNLOADER_PACKAGE, oldExternalPackage));
        } finally {
            SettingsStatus.advancedDownloadsEnabled = oldAdvancedDownloads;
            preferencesField.set(Setting.preferences, oldPreferences);
        }
    }

    /** Follows the native settings patch's initialize call immediately after super.onCreate. */
    public static class SettingsActivity extends AdPersonalizationActivity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
            assertTrue(TikTokActivityHook.initialize(this));
        }

        @Override public void onBackPressed() {
            if (!TikTokActivityHook.handleBackPressed(this)) super.onBackPressed();
        }
    }

    @Test
    @Config(sdk = 35)
    public void simultaneouslyResumedPagesUseTheChangedListAndTextValues() {
        try (var first = openDownloads()) {
            chooseQuality(first.get(), "1080");
            assertQuality(first.get(), "1080");
            enterExternalPackage(first.get(), "com.dv.adm");
            assertExternalPackage(first.get(), "com.dv.adm");
            chooseQuality(first.get(), "auto");
            enterExternalPackage(first.get(), "");
            assertQuality(first.get(), "auto");
            assertExternalPackage(first.get(), "");

            // Android multi-resume can leave both pages active. Pausing a listener is insufficient.
            try (var second = openDownloads()) {
                assertTrue(page(first.get()).isResumed());
                assertTrue(page(second.get()).isResumed());
                assertEquals("auto", qualityRow(first.get()).getValue());
                chooseQuality(second.get(), "720");
                assertEquals("the other resumed page erased the newly selected quality", "720",
                        Setting.preferences.preferences.getString(Settings.DOWNLOAD_VIDEO_QUALITY.key, "auto"));
                assertQuality(second.get(), "720");
                assertQuality(first.get(), "720");

                enterExternalPackage(second.get(), "com.deniscerri.ytdl");
                assertExternalPackage(second.get(), "com.deniscerri.ytdl");
                assertExternalPackage(first.get(), "com.deniscerri.ytdl");
                assertTrue(page(first.get()).findPreference(Settings.YTDLNIS_DOWNLOAD_TYPE.key).isEnabled());
                assertTrue(page(second.get()).findPreference(Settings.YTDLNIS_DOWNLOAD_TYPE.key).isEnabled());

                chooseQuality(first.get(), "auto");
                enterExternalPackage(first.get(), "");
                assertQuality(first.get(), "auto");
                assertQuality(second.get(), "auto");
                assertExternalPackage(first.get(), "");
                assertExternalPackage(second.get(), "");
                assertEquals("Automatic remains an explicit choice under Metra's Highest default", "auto",
                        Setting.preferences.preferences.getString(Settings.DOWNLOAD_VIDEO_QUALITY.key, "highest"));
                assertFalse(Setting.preferences.preferences.contains(Settings.EXTERNAL_DOWNLOADER_PACKAGE.key));
                assertFalse(page(first.get()).findPreference(Settings.YTDLNIS_DOWNLOAD_TYPE.key).isEnabled());
                assertFalse(page(second.get()).findPreference(Settings.YTDLNIS_DOWNLOAD_TYPE.key).isEnabled());
                assertTrue(Settings.DOWNLOAD_WITHOUT_SOUND.get());
            }
        }
    }

    @Test public void aNewSettingsActivityKeepsItsChangesWhenAnOlderDownloadsPageReturns() {
        try (var older = openDownloads()) {
            // Ordinary single-page changes still persist and remove the default normally.
            assertAudio(older.get(), false);
            clickAudio(older.get());
            assertAudio(older.get(), true);
            clickAudio(older.get());
            assertAudio(older.get(), false);
            assertFalse(Setting.preferences.preferences.contains(Settings.DOWNLOAD_AUDIO_TRACK.key));

            SwitchPreference staleOff = audioRow(older.get());
            older.pause().stop();
            try (var newer = openDownloads()) {
                assertFalse("the older page must retain the stale off value", staleOff.isChecked());
                assertFalse(audioRow(newer.get()).isChecked());
                clickAudio(newer.get());

                assertTrue("the older Downloads listener erased the newly enabled saved value",
                        Setting.preferences.preferences.getBoolean(Settings.DOWNLOAD_AUDIO_TRACK.key, false));
                assertAudio(newer.get(), true);
                newer.get().onBackPressed();
                assertTrue(newer.get().isFinishing());
            }
            resume(older);
            assertAudio(older.get(), true);

            SettingsActivity beforeRecreation = older.get();
            Configuration largerText = new Configuration(beforeRecreation.getResources().getConfiguration());
            largerText.fontScale = 1.5f;
            older.configurationChange(largerText);
            settle(older.get());
            assertNotSame("the return control must really recreate the activity",
                    beforeRecreation, older.get());
            assertAudio(older.get(), true);

            // Reverse direction: a stale on value must not re-enable the runtime consumer.
            SwitchPreference staleOn = audioRow(older.get());
            older.pause().stop();
            try (var newer = openDownloads()) {
                assertTrue("the older page must retain the stale on value", staleOn.isChecked());
                assertTrue(audioRow(newer.get()).isChecked());
                clickAudio(newer.get());
                assertAudio(newer.get(), false);
                assertFalse("the default should not leave an explicit stored value",
                        Setting.preferences.preferences.contains(Settings.DOWNLOAD_AUDIO_TRACK.key));
                newer.get().onBackPressed();
                assertTrue(newer.get().isFinishing());
            }
            resume(older);
            assertAudio(older.get(), false);
            older.get().onBackPressed();
            assertTrue(older.get().isFinishing());
        }
    }

    private static ActivityController<SettingsActivity> openDownloads() {
        Intent intent = new Intent("morphe_settings").putExtra("morphe", true)
                .putExtra("morphe_settings_section", "DOWNLOADS");
        ActivityController<SettingsActivity> owner =
                Robolectric.buildActivity(SettingsActivity.class, intent).setup().visible();
        settle(owner.get());
        assertSame("the runtime holder and actual preference page must use the same test store",
                Setting.preferences.preferences, page(owner.get()).getPreferenceManager().getSharedPreferences());
        return owner;
    }

    private static void resume(ActivityController<SettingsActivity> owner) {
        owner.restart().start().resume().visible();
        settle(owner.get());
    }

    private static TikTokPreferenceFragment page(SettingsActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        ViewGroup background = (ViewGroup) content.getChildAt(0);
        ViewGroup container = (ViewGroup) background.getChildAt(0);
        var current = activity.getFragmentManager().findFragmentById(container.getId());
        assertTrue(current instanceof TikTokPreferenceFragment);
        TikTokPreferenceFragment page = (TikTokPreferenceFragment) current;
        assertEquals("DOWNLOADS", page.getArguments().getString("morphe_settings_section"));
        return page;
    }

    private static SwitchPreference audioRow(SettingsActivity activity) {
        Preference row = page(activity).findPreference(Settings.DOWNLOAD_AUDIO_TRACK.key);
        assertTrue("the installed Downloads page has no audio switch", row instanceof SwitchPreference);
        return (SwitchPreference) row;
    }

    private static void assertAudio(SettingsActivity activity, boolean expected) {
        assertEquals("the visible row disagrees with the change", expected, audioRow(activity).isChecked());
        assertEquals("the runtime setting disagrees with the change", expected, Settings.DOWNLOAD_AUDIO_TRACK.get());
        assertEquals("the persisted setting disagrees with the change", expected,
                Setting.preferences.preferences.getBoolean(Settings.DOWNLOAD_AUDIO_TRACK.key, false));
        assertTrue("the neighboring silent-video setting was changed", Settings.DOWNLOAD_WITHOUT_SOUND.get());
        assertTrue(Setting.preferences.preferences.getBoolean(Settings.DOWNLOAD_WITHOUT_SOUND.key, false));
    }

    private static void clickAudio(SettingsActivity activity) {
        clickPreference(activity, Settings.DOWNLOAD_AUDIO_TRACK.key);
    }

    private static ListPreference qualityRow(SettingsActivity activity) {
        Preference row = page(activity).findPreference(Settings.DOWNLOAD_VIDEO_QUALITY.key);
        assertTrue(row instanceof ListPreference);
        return (ListPreference) row;
    }

    private static EditTextPreference externalRow(SettingsActivity activity) {
        Preference row = page(activity).findPreference(Settings.EXTERNAL_DOWNLOADER_PACKAGE.key);
        assertTrue(row instanceof EditTextPreference);
        return (EditTextPreference) row;
    }

    private static void assertQuality(SettingsActivity activity, String expected) {
        assertEquals(expected, qualityRow(activity).getValue());
        assertEquals(expected, Settings.DOWNLOAD_VIDEO_QUALITY.get());
        assertEquals(expected, Setting.preferences.preferences.getString(Settings.DOWNLOAD_VIDEO_QUALITY.key, "auto"));
    }

    private static void assertExternalPackage(SettingsActivity activity, String expected) {
        assertEquals(expected, externalRow(activity).getText());
        assertEquals(expected, Settings.EXTERNAL_DOWNLOADER_PACKAGE.get());
        assertEquals(expected, Setting.preferences.preferences.getString(Settings.EXTERNAL_DOWNLOADER_PACKAGE.key, ""));
    }

    private static void chooseQuality(SettingsActivity activity, String value) {
        ListPreference row = qualityRow(activity);
        clickPreference(activity, row.getKey());
        AlertDialog dialog = (AlertDialog) row.getDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        int position = row.findIndexOfValue(value);
        assertTrue("the requested quality is not in the installed native choice list", position >= 0);
        ListView choices = dialog.getListView();
        assertTrue(choices.performItemClick(choices.getAdapter().getView(position, null, choices),
                position, choices.getItemIdAtPosition(position)));
        settle(activity);
        assertFalse(dialog.isShowing());
    }

    private static void enterExternalPackage(SettingsActivity activity, String value) {
        EditTextPreference row = externalRow(activity);
        clickPreference(activity, row.getKey());
        AlertDialog dialog = (AlertDialog) row.getDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        row.getEditText().setText(value);
        assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
        settle(activity);
        assertFalse(dialog.isShowing());
    }

    private static void clickPreference(SettingsActivity activity, String key) {
        ListView list = page(activity).getView().findViewById(android.R.id.list);
        for (int position = 0; position < list.getCount(); position++) {
            Object item = list.getItemAtPosition(position);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                list.setSelection(position);
                settle(activity);
                View row = list.getChildAt(position - list.getFirstVisiblePosition());
                assertNotNull("the native preference row was not laid out", row);
                assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)));
                settle(activity);
                return;
            }
        }
        fail("the actual preference list has no " + key + " row");
    }

    private static void settle(SettingsActivity activity) {
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
