package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.QuickSetupsHomePreference;
import app.morphe.extension.tiktok.settings.preference.QuickSetupsPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import java.util.ArrayList;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * A long press on the home page's Quick setups card hides it, and the row on Backup and restore
 * brings it back. The choice is Hushfeed's own: no backup carries it and no Quick setups choice
 * touches it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class QuickSetupsHideTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void putItBack() {
        BaseSettings.HIDE_QUICK_SETUPS.resetToDefault();
    }

    @Test public void theHomeSummaryTellsYouToPressAndHold() {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment home = attach(owner.get(), null);
            Preference card = home.findPreference(QuickSetupsPreference.KEY);
            assertNotNull(card);
            assertTrue(String.valueOf(card.getSummary()).startsWith("Quiet hides ads, Shop and LIVE."));
            assertTrue(String.valueOf(card.getSummary()).endsWith(" Press and hold to hide this row."));
        }
    }

    @Test public void aLongPressHidesTheCardAtOnceAndSaysWhereItComesBack() {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment home = attach(owner.get(), null);
            Preference card = home.findPreference(QuickSetupsPreference.KEY);
            View view = card.getView(null, null);
            assertFalse(BaseSettings.HIDE_QUICK_SETUPS.get());
            // The buttons on the card count as the card.
            assertTrue(view.findViewWithTag(QuickSetupsPreference.ROW_TAG)
                    .findViewWithTag("quick_setups_privacy").performLongClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(BaseSettings.HIDE_QUICK_SETUPS.get());
            assertNull("the card stayed on the home page", home.findPreference(QuickSetupsPreference.KEY));
            assertEquals("Quick setups hidden. Bring it back from Backup and restore.",
                    ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void theRestoreRowFlipsTheChoiceAndTheHomePageFollows() {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            BaseSettings.HIDE_QUICK_SETUPS.save(true);
            TikTokPreferenceFragment backup = attach(activity, "BACKUP");
            Preference row = backup.findPreference(QuickSetupsHomePreference.KEY);
            assertNotNull("Backup and restore has no row for it", row);
            assertEquals("Quick setups on the home page", String.valueOf(row.getTitle()));
            assertEquals("Hidden. Tap to show it again.", String.valueOf(row.getSummary()));

            assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
            assertFalse(BaseSettings.HIDE_QUICK_SETUPS.get());
            assertEquals("Shown. Tap to hide it.", String.valueOf(row.getSummary()));
            assertNotNull(attach(activity, null).findPreference(QuickSetupsPreference.KEY));

            assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
            assertTrue(BaseSettings.HIDE_QUICK_SETUPS.get());
            assertEquals("Hidden. Tap to show it again.", String.valueOf(row.getSummary()));
            TikTokPreferenceFragment home = attach(activity, null);
            assertNull(home.findPreference(QuickSetupsPreference.KEY));

            // A home page that was already open follows when it comes back to the front.
            BaseSettings.HIDE_QUICK_SETUPS.save(false);
            home.onResume();
            assertNotNull(home.findPreference(QuickSetupsPreference.KEY));
            BaseSettings.HIDE_QUICK_SETUPS.save(true);
            home.onResume();
            assertNull(home.findPreference(QuickSetupsPreference.KEY));
        }
    }

    @Test public void noBackupNoQuickSetupsChoiceAndNoPauseTouchesIt() throws Exception {
        BaseSettings.HIDE_QUICK_SETUPS.save(true);
        assertFalse(BaseSettings.HIDE_QUICK_SETUPS.rebootApp);
        assertFalse(BaseSettings.HIDE_QUICK_SETUPS.includeWithImportExport);
        assertTrue("Pause would bring the card back", BaseSettings.HIDE_QUICK_SETUPS.isKeptWhenPaused());
        assertFalse(SettingsBackup.create(false).contains(BaseSettings.HIDE_QUICK_SETUPS.key));
        assertFalse(SettingsBackup.create(true).contains(BaseSettings.HIDE_QUICK_SETUPS.key));
        for (QuickSetups.Choice choice : QuickSetups.Choice.values()) {
            QuickSetups.apply(Utils.getContext(), choice, new ArrayList<>(Setting.allLoadedSettings()));
            assertTrue(choice + " brought the card back", BaseSettings.HIDE_QUICK_SETUPS.get());
        }
        assertFalse(QuickSetups.listed().contains(BaseSettings.HIDE_QUICK_SETUPS));
    }

    private static TikTokPreferenceFragment attach(Activity activity, String section) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        if (section != null) {
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", section);
            fragment.setArguments(arguments);
        }
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }
}
