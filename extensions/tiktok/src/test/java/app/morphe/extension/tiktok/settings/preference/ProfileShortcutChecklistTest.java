/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.CheckBox;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The profile shortcut checklist (#49) saves its own picks and leaves typed names alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ProfileShortcutChecklistTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class Host extends Activity {}

    @Before public void seenShortcuts() {
        Settings.PROFILE_SHORTCUT_CATALOG.save("creator tools\tTikTok Studio\nfeature 21\tYour orders");
    }

    @After public void reset() {
        Settings.PROFILE_SHORTCUT_CATALOG.save("");
        Settings.PROFILE_SHORTCUT_PICKS.save("");
        Settings.HIDDEN_PROFILE_SHORTCUTS.save("");
    }

    private static CheckBox row(View root, String key) {
        return (CheckBox) root.findViewWithTag("profile_shortcut_" + key);
    }

    @Test public void picksAreSavedApartAndAPickTheCatalogLostIsKept() {
        Settings.PROFILE_SHORTCUT_PICKS.save("feature 21, gone pill");
        Settings.HIDDEN_PROFILE_SHORTCUTS.save("Your orders, Shop");
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            ProfileShortcutChecklistPreference checklist = new ProfileShortcutChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertTrue("a saved pick shows ticked", row(root, "feature 21").isChecked());
            assertFalse("a typed name doesn't tick its row", row(root, "creator tools").isChecked());

            row(root, "creator tools").setChecked(true);
            assertEquals("gone pill, creator tools, feature 21", checklist.buildPicks());
            assertEquals("the typed row keeps what was typed", "Your orders, Shop",
                    Settings.HIDDEN_PROFILE_SHORTCUTS.savedValue());
        }
    }

    @Test public void pausedTheChecklistStillShowsWhatWasPicked() {
        Settings.PROFILE_SHORTCUT_PICKS.save("creator tools");
        PausedProcess.set(true);
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            ProfileShortcutChecklistPreference checklist = new ProfileShortcutChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertTrue(row(root, "creator tools").isChecked());
            assertEquals("saving while paused keeps the pick", "creator tools", checklist.buildPicks());
        } finally {
            PausedProcess.set(false);
        }
    }
}
