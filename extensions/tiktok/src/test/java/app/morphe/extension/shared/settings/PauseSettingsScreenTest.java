/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsPagesTest;
import app.morphe.extension.tiktok.settings.preference.RestartPendingPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsStatusPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;

/** What the settings and the diagnostic export say while Hushfeed is paused, and the way back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PauseSettingsScreenTest {
    private static final String BACK_ON_AT_RESTART = "Metra turns back on when TikTok restarts.";
    private static final String SWITCH_SUMMARY =
            "Metra's switches are paused. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void runAgain() {
        HushfeedPause.resetForTests();
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        AbstractPreferenceFragment.restartPending.clear();
        HookStatus.clear();
    }

    /** The home page, where the status card and Pause Hushfeed sit in the Hushfeed group. */
    private static TikTokPreferenceFragment openHome(Activity activity) {
        Utils.setContext(activity);
        TikTokPreferenceFragment home = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, home).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("App & advanced came back as a menu of menus", home.findPreference("hub_app_advanced"));
        return home;
    }

    @Test public void aStartPausedByACrashLoopSaysSoAndOffersTheWayBack() {
        BaseSettings.SAFE_MODE.save(true);
        HushfeedPause.pauseForTests(HushfeedPause.Reason.CRASH_LOOP);
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment home = openHome(owner.get());
            Preference status = home.getPreferenceScreen().findPreference(SettingsStatusPreference.KEY);
            assertEquals("Metra is paused", String.valueOf(status.getTitle()));
            assertEquals("TikTok closed three times within a minute of starting, so Metra paused "
                    + "itself. Your settings stay as they are.", String.valueOf(status.getSummary()));

            View card = status.getView(null, null);
            TextView back = card.findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG);
            assertNotNull("a paused card offered no way back", back);
            assertNull("Diagnostics took the place of the way back",
                    card.findViewWithTag(SettingsStatusPreference.ACTION_TAG));
            assertEquals("Turn Metra back on", back.getText().toString());
            assertEquals("Turn Metra back on", String.valueOf(back.getContentDescription()));

            assertTrue(back.performClick());
            assertFalse("safe mode is still on", BaseSettings.SAFE_MODE.savedValue());
            assertTrue("turning back on owed no restart",
                    AbstractPreferenceFragment.restartPending.contains(BaseSettings.SAFE_MODE.key));

            // Taken, the way back is not offered again: the card says what happens next.
            assertEquals(BACK_ON_AT_RESTART, String.valueOf(status.getSummary()));
            assertEquals("a card that was already used still offered the way back", View.GONE,
                    status.getView(card, null).findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG).getVisibility());
            assertEquals(View.GONE, status.getView(null, null)
                    .findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG).getVisibility());
            assertNotNull("no restart row", home.getPreferenceScreen().findPreference(RestartPendingPreference.KEY));
        }
    }

    @Test public void turningBackOnFromTheMarkerFileOwesARestartToo() throws Exception {
        File marker = HushfeedPause.markerFile(RuntimeEnvironment.getApplication());
        assertTrue(marker.getParentFile().exists() || marker.getParentFile().mkdirs());
        assertTrue(marker.createNewFile());
        HushfeedPause.pauseForTests(HushfeedPause.Reason.MARKER_FILE);
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment home = openHome(owner.get());
            SettingsStatusPreference status = (SettingsStatusPreference)
                    home.getPreferenceScreen().findPreference(SettingsStatusPreference.KEY);
            View card = status.getView(null, null);
            TextView back = card.findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG);
            assertEquals(View.VISIBLE, back.getVisibility());

            assertTrue(back.performClick());
            assertFalse(marker.exists());
            // No setting changed, but the start the file paused still runs paused.
            assertEquals("the start the file paused owed no restart, or owed more than one",
                    "[" + HushfeedPause.MARKER_FILE_NAME + "]",
                    String.valueOf(AbstractPreferenceFragment.restartPending));
            assertNotNull("no restart row", home.getPreferenceScreen().findPreference(RestartPendingPreference.KEY));
            assertEquals("Restart TikTok to apply this change", RestartPendingPreference.label(owner.get()));
            assertEquals(BACK_ON_AT_RESTART, String.valueOf(status.getSummary()));
            assertEquals(View.GONE, status.getView(card, null)
                    .findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG).getVisibility());
        } finally {
            marker.delete();
        }
    }

    @Test public void turningBackOnFromTheSwitchOwesARestartToo() {
        BaseSettings.PAUSED.save(true);
        HushfeedPause.pauseForTests(HushfeedPause.Reason.SWITCH);
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment home = openHome(owner.get());
            SettingsStatusPreference status = (SettingsStatusPreference)
                    home.getPreferenceScreen().findPreference(SettingsStatusPreference.KEY);
            View card = status.getView(null, null);
            TextView back = card.findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG);

            assertTrue(back.performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(BaseSettings.PAUSED.savedValue());
            // The store's listener reaches the page as soon as the save lands and reads the
            // switch as never having changed; what this process runs with was put on record
            // first, so the restart is still owed.
            assertEquals("[" + BaseSettings.PAUSED.key + "]",
                    String.valueOf(AbstractPreferenceFragment.restartPending));
            assertNotNull("no restart row", home.getPreferenceScreen().findPreference(RestartPendingPreference.KEY));
            assertFalse(((TogglePreference) home.getPreferenceScreen().findPreference(BaseSettings.PAUSED.key)).isChecked());
            assertEquals(BACK_ON_AT_RESTART, String.valueOf(status.getSummary()));
        }
    }

    @Test public void aFamilyThatReadsNoSettingIsNotMarkedPaused() throws Exception {
        LogBufferManager.clearLogBuffer();
        // The sticker save button reads no switch; loading its class says so to the export.
        Class.forName("app.morphe.extension.tiktok.download.StickerGallerySaver");
        HookStatus.bound("sticker saves", "sheet action buttons");
        HookStatus.bound("comments", "jlk");
        HushfeedPause.pauseForTests(HushfeedPause.Reason.SWITCH);

        String paused = LogBufferManager.buildExportText();
        String table = paused.substring(paused.indexOf("[HOOK STATUS]"));
        table = table.substring(0, table.indexOf("\n\n") < 0 ? table.length() : table.indexOf("\n\n"));
        String stickers = null;
        String comments = null;
        for (String line : table.split("\n")) {
            if (line.contains("sticker saves")) stickers = line;
            if (line.contains("comments")) comments = line;
        }
        assertNotNull(table, stickers);
        assertNotNull(table, comments);
        assertFalse("a family Pause cannot reach was marked paused: " + stickers, stickers.endsWith(" (paused)"));
        assertTrue("a family a setting controls was not marked: " + comments, comments.endsWith(" (paused)"));
    }

    @Test public void theCardFollowsThePauseSwitchWhilePaused() {
        BaseSettings.PAUSED.save(true);
        HushfeedPause.pauseForTests(HushfeedPause.Reason.SWITCH);
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment home = openHome(owner.get());
            SettingsStatusPreference status = (SettingsStatusPreference)
                    home.getPreferenceScreen().findPreference(SettingsStatusPreference.KEY);
            TogglePreference pause = (TogglePreference) home.getPreferenceScreen().findPreference(BaseSettings.PAUSED.key);
            assertTrue(pause.isChecked());
            assertEquals(SWITCH_SUMMARY, String.valueOf(status.getSummary()));

            pause.setChecked(false);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(BaseSettings.PAUSED.savedValue());
            assertEquals(BACK_ON_AT_RESTART, String.valueOf(status.getSummary()));
            assertEquals(View.GONE, status.getView(null, null)
                    .findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG).getVisibility());

            // Back on in the same start: the next one is paused again, and the way back returns.
            pause.setChecked(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(SWITCH_SUMMARY, String.valueOf(status.getSummary()));
            assertEquals(View.VISIBLE, status.getView(null, null)
                    .findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG).getVisibility());
        }
    }

    @Test public void eachReasonExplainsItselfAndARunningStartKeepsDiagnostics() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            BaseSettings.PAUSED.save(true);
            HushfeedPause.pauseForTests(HushfeedPause.Reason.SWITCH);
            assertEquals(SWITCH_SUMMARY,
                    String.valueOf(new SettingsStatusPreference(activity, () -> {}).getSummary()));
            BaseSettings.PAUSED.save(false);
            File marker = HushfeedPause.markerFile(activity);
            assertTrue(marker.getParentFile().exists() || marker.getParentFile().mkdirs());
            assertTrue(marker.createNewFile());
            HushfeedPause.pauseForTests(HushfeedPause.Reason.MARKER_FILE);
            assertEquals("A file named hushfeed-safe-mode in TikTok's folder under Android/data paused "
                    + "Metra. Your settings stay as they are.",
                    String.valueOf(new SettingsStatusPreference(activity, () -> {}).getSummary()));
            assertTrue(marker.delete());

            HushfeedPause.pauseForTests(HushfeedPause.Reason.NONE);
            SettingsStatusPreference running = new SettingsStatusPreference(activity, () -> {});
            running.setTurnBackOnAction(() -> {});
            assertEquals("Metra is active", String.valueOf(running.getTitle()));
            View card = running.getView(null, null);
            assertNotNull(card.findViewWithTag(SettingsStatusPreference.ACTION_TAG));
            assertNull("a running start offered to turn Metra back on",
                    card.findViewWithTag(SettingsStatusPreference.TURN_BACK_ON_TAG));
        }
    }

    @Test public void thePauseSwitchIsOnTheHomePage() {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment home = openHome(owner.get());
            Preference row = home.getPreferenceScreen().findPreference(BaseSettings.PAUSED.key);
            assertTrue("no Pause Metra switch on the home page", row instanceof TogglePreference);
            assertEquals("Pause Metra", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()).contains(
                    "Pause Metra's switches after TikTok restarts. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay."));
            assertFalse(String.valueOf(row.getSummary()).contains("as if it weren't patched"));
            assertFalse(((TogglePreference) row).isChecked());
        }
    }

    @Test public void theCompactHomeStatusFollowsPauseAndRestartState() {
        HushfeedPause.pauseForTests(HushfeedPause.Reason.NONE);
        AbstractPreferenceFragment.restartPending.clear();
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TextView status = home.getView().findViewWithTag("hushfeed_compact_status");
            assertNotNull(status);
            assertEquals("Active", status.getText().toString());
            assertEquals("Metra is active", String.valueOf(status.getContentDescription()));

            BaseSettings.PAUSED.save(true);
            HushfeedPause.pauseForTests(HushfeedPause.Reason.SWITCH);
            AbstractPreferenceFragment.restartPending.clear();
            home.onResume();
            assertEquals("Paused", status.getText().toString());
            assertEquals("Metra is paused", String.valueOf(status.getContentDescription()));

            AbstractPreferenceFragment.restartPending.add(BaseSettings.PAUSED.key);
            home.onResume();
            assertEquals("Restart pending", status.getText().toString());
            assertEquals("Restart pending", String.valueOf(status.getContentDescription()));

            // The press scrolls down to the status card on this same page.
            assertTrue(status.performClick());
            activity.getFragmentManager().executePendingTransactions();
            assertSame(home, activity.getFragmentManager().findFragmentById(android.R.id.content));
            assertNotNull(home.findPreference(SettingsStatusPreference.KEY));
        }
    }

    @Test public void theExportSaysPausedAndMarksEveryFamily() {
        LogBufferManager.clearLogBuffer();
        HookStatus.bound("comments", "jlk");
        HookStatus.bound("feed", "desc");
        assertEquals("an all-bound running start had something to report", "",
                LogBufferManager.buildExportText());

        HushfeedPause.pauseForTests(HushfeedPause.Reason.SWITCH);
        String paused = LogBufferManager.buildExportText();
        assertTrue("an all-bound paused start said nothing", !paused.isEmpty());
        assertTrue(paused, paused.contains("Metra: paused (switch)"));
        String table = paused.substring(paused.indexOf("[HOOK STATUS]"));
        table = table.substring(0, table.indexOf("\n\n") < 0 ? table.length() : table.indexOf("\n\n"));
        int families = 0;
        for (String line : table.split("\n")) {
            if (line.startsWith("[") || line.trim().isEmpty()) continue;
            families++;
            assertTrue("a family was not marked paused: " + line, line.endsWith(" (paused)"));
        }
        assertEquals(2, families);
    }
}
