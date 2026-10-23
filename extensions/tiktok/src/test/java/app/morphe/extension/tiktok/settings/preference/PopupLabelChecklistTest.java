/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

/** The popup checklist lists what TikTok tried to show and saves only the ticks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PopupLabelChecklistTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class Host extends Activity {}

    @Before public void seenPopups() {
        Settings.POPUP_LABEL_CATALOG.save("Follow friends\nUpsell sheet");
    }

    @After public void reset() {
        PausedProcess.set(false);
        Settings.POPUP_LABEL_CATALOG.save("");
        Settings.POPUP_LABEL_PICKS.save("");
    }

    private static CheckBox row(View root, String key) {
        return (CheckBox) root.findViewWithTag("popup_label_" + key);
    }

    @Test public void listsEachSeenLabelAndKeepsATickTheListLost() {
        Settings.POPUP_LABEL_PICKS.save("upsell sheet, gone popup");
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            PopupLabelChecklistPreference checklist = new PopupLabelChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertNotNull(row(root, "follow friends"));
            assertEquals("Follow friends", row(root, "follow friends").getText().toString());
            assertTrue("a saved pick shows ticked", row(root, "upsell sheet").isChecked());
            assertFalse(row(root, "follow friends").isChecked());

            row(root, "follow friends").setChecked(true);
            assertEquals("gone popup, follow friends, upsell sheet", checklist.buildPicks());
        }
    }

    @Test public void pausedTheChecklistStillShowsWhatWasPicked() {
        Settings.POPUP_LABEL_PICKS.save("follow friends");
        PausedProcess.set(true);
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            PopupLabelChecklistPreference checklist = new PopupLabelChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertTrue(row(root, "follow friends").isChecked());
            assertEquals("saving while paused keeps the pick", "follow friends", checklist.buildPicks());
        }
    }
}
