/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Iterator;

/**
 * An import or undo that was interrupted is put right from its journal at the next settings
 * visit, by asking whether the settings hold the "before" or the "after" snapshot. Those were
 * written from saved values, so they are compared with saved values: read through get() while
 * paused, a committed change would match neither and be rolled back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PauseKeepsTheJournalHonestTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void runAgain() {
        PausedProcess.set(false);
        Settings.HIDE_SERIES.resetToDefault();
        Settings.MAX_VIDEO_SECONDS.resetToDefault();
    }

    /**
     * A backup of the saved values that parses back whatever other test classes left in the
     * registry. SettingRangeTest declares settings under real keys, and a second object with
     * a key already taken gives create() one key entry more than it has values, which the
     * parse refuses as incomplete. The app never registers a key twice.
     */
    private static String backupOfSavedValues() throws Exception {
        JSONObject root = new JSONObject(SettingsBackup.create(false));
        JSONArray keys = new JSONArray();
        for (Iterator<String> names = root.getJSONObject("settings").keys(); names.hasNext(); ) {
            keys.put(names.next());
        }
        return root.put("setting_keys", keys).toString();
    }

    @Test public void aJournalWrittenFromSavedValuesStillMatchesWhilePaused() throws Exception {
        Settings.HIDE_SERIES.save(true);
        Settings.MAX_VIDEO_SECONDS.save(90);
        SettingsBackup.Snapshot committed = SettingsBackup.parseForJournal(backupOfSavedValues());
        assertTrue("the snapshot did not match the settings it was taken from",
                SettingsBackup.matchesForJournal(committed));

        PausedProcess.set(true);
        assertTrue("paused, a committed journal read as never having been applied",
                SettingsBackup.matchesForJournal(committed));
    }
}
