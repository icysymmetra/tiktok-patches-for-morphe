/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.feedfilter.AdsFilter;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabRuntime;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;
import app.morphe.extension.tiktok.feedfilter.ContentMarkerFilters;
import app.morphe.extension.tiktok.navigation.NavigationTabsFilter;
import app.morphe.extension.tiktok.seekbar.SeekbarPatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsBackup;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.share.ShareModelFilter;
import app.morphe.extension.tiktok.share.ShareSurface;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Paused, every hook a setting controls takes the path an unpatched TikTok takes, and nothing the
 * reader saved changes. The walk covers every setting declared in {@link Settings} and
 * {@link BaseSettings}, so a switch added later is paused unless it is put on the list below.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PauseAnswersUnpatchedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Hushfeed's own state, which keeps its value while paused. Everything else is paused. */
    private static final Set<String> KEPT = new TreeSet<>(Arrays.asList(
            "morphe_debug", "morphe_debug_log_filters", "morphe_capture_java_crashes",
            "morphe_debug_stacktrace", "morphe_debug_toast_on_error",
            "morphe_check_environment_warnings_issued", "morphe_language", "morphe_show_menu_icons",
            "morphe_last_time_app_was_launched", "morphe_experimental_app_target_confirmed",
            "hushfeed_paused", "hushfeed_safe_mode",
            // The Quick setups card the reader hid stays hidden; a pause would bring it back.
            "hushfeed_hide_quick_setups",
            // A timed diagnostic capture logs while paused, like the switch it stands beside.
            "hushfeed_debug_capture",
            // Guests need their saved settings entry to resume Hushfeed after closing settings.
            "home_tab_opens_settings", "app_lock", "app_lock_timeout",
            "launcher_shortcuts_removed", "feed_navigation_observed_tabs",
            "bottom_navigation_observed_tabs", "down_path", "download_paths_migrated",
            "remembered_speed_v2", "session_budget_state", "block_author_button_position",
            "local_hide_button_position", "block_sound_button_position",
            "not_interested_button_position", "feed_mute_button_position", "share_action_catalog",
            "diagnostic_report_salt",
            // Which translate toggle Hushfeed's button turned on, so a pause can answer it off.
            "comment_translate_button_turned_on",
            // The profile shortcuts TikTok has sent, recorded for the checklist like share actions.
            "profile_shortcut_catalog",
            // The popup labels TikTok has tried to show, recorded for the checklist the same way.
            "popup_label_catalog",
            // The budget's day is worked out from this hour, paused or not.
            "session_budget_reset_hour",
            // The day the streak's message last went, so a pause can't send a second one.
            "auto_streak_state",
            // Downloads rewrite TikTok's folder and file name with no switch in front.
            "download_video_path", "download_photo_path", "download_sticker_path",
            "download_sticker_format", "download_video_filename_template",
            "download_photo_filename_template", "download_comment_media_filename_template"));

    private final Map<Setting<?>, Object> before = new LinkedHashMap<>();

    @Before public void remember() throws Exception {
        for (Setting<?> setting : everySetting()) before.put(setting, setting.savedValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @After public void restore() {
        Setting.setPausedForProcess(false);
        for (Map.Entry<Setting<?>, Object> entry : before.entrySet()) {
            ((Setting) entry.getKey()).save(entry.getValue());
        }
    }

    private static List<Setting<?>> everySetting() throws Exception {
        Settings.REGION_SPOOF.savedValue();
        List<Setting<?>> settings = new ArrayList<>();
        for (Class<?> owner : new Class<?>[]{BaseSettings.class, Settings.class}) {
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !Setting.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                settings.add((Setting<?>) field.get(null));
            }
        }
        return settings;
    }

    @Test public void everySettingAnswersItsUnpatchedValueWhilePaused() throws Exception {
        List<Setting<?>> settings = everySetting();
        // Every switch saved on first, so an answer of off can only come from the pause.
        for (Setting<?> setting : settings) {
            if (setting instanceof BooleanSetting && !KEPT.contains(setting.key)) {
                ((BooleanSetting) setting).save(Boolean.TRUE);
            }
        }
        Map<String, Object> saved = new LinkedHashMap<>();
        for (Setting<?> setting : settings) saved.put(setting.key, setting.savedValue());
        Map<String, ?> stored = Setting.preferences.preferences.getAll();

        Setting.setPausedForProcess(true);
        List<String> wrong = new ArrayList<>();
        for (Setting<?> setting : settings) {
            Object expected = KEPT.contains(setting.key) ? saved.get(setting.key)
                    : setting instanceof BooleanSetting ? Boolean.FALSE
                    : setting == Settings.DOWNLOAD_VIDEO_QUALITY ? "auto" : setting.defaultValue;
            if (!Objects.equals(expected, setting.get())) {
                wrong.add(setting.key + " answered " + setting.get() + " while paused, not " + expected);
            }
            if (!Objects.equals(saved.get(setting.key), setting.savedValue())) {
                wrong.add(setting.key + " lost its saved value");
            }
        }
        assertEquals(Collections.emptyList(), wrong);
        assertEquals("pausing wrote to the stored settings", stored, Setting.preferences.preferences.getAll());

        Setting.setPausedForProcess(false);
        for (Setting<?> setting : settings) {
            assertEquals(setting.key + " did not come back", saved.get(setting.key), setting.get());
        }
    }

    @Test public void onlyHushfeedsOwnStateKeepsItsValue() throws Exception {
        Set<String> kept = new TreeSet<>();
        for (Setting<?> setting : everySetting()) if (setting.isKeptWhenPaused()) kept.add(setting.key);
        assertEquals(KEPT, kept);
    }

    @Test public void hookEntriesTakeTheUnpatchedPath() {
        Settings.HIDE_SERIES.save(true);
        Settings.HIDE_PLAYLIST_VIDEOS.save(true);
        Settings.REMOVE_ADS.save(true);
        Settings.SHOW_SEEKBAR.save(true);
        Settings.SHARE_HIDDEN_ITEMS.save("@copy_link");
        assertTrue(new ContentMarkerFilters.SeriesFilter().getEnabled());
        assertTrue(new AdsFilter().getEnabled());
        assertTrue(SeekbarPatch.isEnabled());

        Setting.setPausedForProcess(true);
        assertFalse(new ContentMarkerFilters.SeriesFilter().getEnabled());
        assertFalse(new ContentMarkerFilters.PlaylistFilter().getEnabled());
        assertFalse("ads are removed by default, and paused they are not", new AdsFilter().getEnabled());
        assertFalse(SeekbarPatch.isEnabled());
        assertEquals(3, SeekbarPatch.overrideSeekbarShowType(3));
        assertEquals("", ShareModelFilter.hiddenItems(ShareSurface.VIDEO));
        // The share checklist still shows what the reader chose.
        assertEquals("@copy_link", ShareModelFilter.savedHiddenItems(ShareSurface.VIDEO));
    }

    @Test public void pausedDownloadKeepsTikToksOwnFileDespiteMetrasHighestDefault() {
        var gear = new app.morphe.extension.tiktok.download.AdvancedDownloadsTest.Gear(
                "normal_1080_0", 300, "https://example.com/1080");
        Object video = new Object() {
            public List<?> getRawBitRate() { return List.of(gear); }
            public boolean hasDashBitrate() { return false; }
        };
        Settings.DOWNLOAD_VIDEO_QUALITY.resetToDefault();
        assertEquals("highest", Settings.DOWNLOAD_VIDEO_QUALITY.savedValue());
        assertTrue(app.morphe.extension.tiktok.download.QualitySelector.download(video) != null);
        Setting.setPausedForProcess(true);
        assertEquals("auto", Settings.DOWNLOAD_VIDEO_QUALITY.get());
        org.junit.Assert.assertNull("Pause must leave the native download address untouched",
                app.morphe.extension.tiktok.download.QualitySelector.download(video));
        assertEquals("highest", Settings.DOWNLOAD_VIDEO_QUALITY.savedValue());
        Setting.setPausedForProcess(false);
        assertTrue(app.morphe.extension.tiktok.download.QualitySelector.download(video) != null);
    }

    @Test public void theLabsOverridesStopWhilePaused() {
        FeatureGateLabStore.resetAllLabData();
        try {
            FeatureGateLabStore.saveRule("abmock", "paused_gate", "BOOLEAN", "true", true);
            FeatureGateLabStore.setMasterEnabled(true);
            FeatureGateLabRuntime.reloadRules();
            assertEquals("Exact typed rule loaded",
                    FeatureGateLabRuntime.runtimeRuleState("abmock", "paused_gate", "BOOLEAN"));

            // The master switch is the Lab's own preference, outside the settings; paused, the
            // runtime treats it as off, and the reader's switch stays on for later.
            Setting.setPausedForProcess(true);
            FeatureGateLabRuntime.reloadRules();
            assertEquals("Master override is off",
                    FeatureGateLabRuntime.runtimeRuleState("abmock", "paused_gate", "BOOLEAN"));
            assertTrue(FeatureGateLabStore.masterEnabled());
        } finally {
            Setting.setPausedForProcess(false);
            FeatureGateLabStore.resetAllLabData();
            FeatureGateLabRuntime.reloadRules();
        }
    }

    @Test public void theSettingsScreenAndABackupKeepShowingTheSavedValues() throws Exception {
        Settings.HIDE_SERIES.save(true);
        Settings.MAX_VIDEO_SECONDS.save(90);
        Setting.setPausedForProcess(true);

        Context context = RuntimeEnvironment.getApplication();
        TogglePreference row = new TogglePreference(context, "Hide Series", "Summary", Settings.HIDE_SERIES);
        assertTrue("the row showed the paused answer, not the reader's choice", row.isChecked());

        JSONObject values = new JSONObject(SettingsBackup.create(false)).getJSONObject("settings");
        assertTrue("a backup taken while paused lost a switch", values.getBoolean(Settings.HIDE_SERIES.key));
        assertEquals(90, values.getInt(Settings.MAX_VIDEO_SECONDS.key));
    }

    @Test public void thePausedTabFilterKeepsTheReadersTabListTheirOwn() {
        Settings.FEED_NAVIGATION.save(true);
        Settings.FEED_NAVIGATION_TABS.save("HOT");
        Settings.FEED_NAVIGATION_OBSERVED_TABS.save("HOT");
        Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.save(true);

        // Paused, TikTok keeps every tab it sends, and a tab Hushfeed has no name for is judged
        // by the reader's own choices: blocked here, so the list stays as it was. Read through
        // get(), the block would answer off and the list would answer the default, and the
        // default plus the new tab would be written over the reader's one.
        Setting.setPausedForProcess(true);
        List<Tab> tabs = Arrays.asList(new Tab("For You"), new Tab("Brand new"));
        assertEquals("paused, TikTok keeps every tab it sends", tabs, NavigationTabsFilter.filterTopTabs(tabs));
        assertEquals("a blocked new tab was written over the reader's list", "HOT",
                Settings.FEED_NAVIGATION_TABS.savedValue());

        // With the door open, the new tab joins the reader's list and nothing else does.
        Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.save(false);
        Settings.FEED_NAVIGATION_OBSERVED_TABS.save("HOT");
        NavigationTabsFilter.filterTopTabs(tabs);
        String joined = Settings.FEED_NAVIGATION_TABS.savedValue();
        assertTrue(joined, joined.startsWith("HOT,") && joined.contains("RAW:") && !joined.contains("EXPLORE"));

        // The control: running normally, the same tab joins the same way.
        Setting.setPausedForProcess(false);
        Settings.FEED_NAVIGATION_TABS.save("HOT");
        Settings.FEED_NAVIGATION_OBSERVED_TABS.save("HOT");
        NavigationTabsFilter.filterTopTabs(tabs);
        assertEquals(joined, Settings.FEED_NAVIGATION_TABS.savedValue());
    }

    @Test public void thePausedBottomTabFilterKeepsTheReadersTabListTheirOwn() {
        Settings.BOTTOM_NAVIGATION.save(true);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME");
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME");
        Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.save(true);

        // The bottom row has its own observer with the same trap as the top one: read through
        // get(), the block answers off and the list answers the default, and a tab Hushfeed has
        // no name for is written over the reader's list with every default tab beside it.
        Setting.setPausedForProcess(true);
        List<Tab> tabs = Arrays.asList(new Tab("home"), new Tab("brand_new"));
        assertEquals("paused, TikTok keeps every bottom tab it sends", tabs, NavigationTabsFilter.filterBottomTabs(tabs));
        assertEquals("a blocked new tab was written over the reader's bottom list", "HOME",
                Settings.BOTTOM_NAVIGATION_TABS.savedValue());

        Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.save(false);
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME");
        NavigationTabsFilter.filterBottomTabs(tabs);
        String joined = Settings.BOTTOM_NAVIGATION_TABS.savedValue();
        assertTrue(joined, joined.startsWith("HOME,") && joined.contains("RAW:") && !joined.contains("FRIENDS"));

        // The control: running normally, the same tab joins the same way.
        Setting.setPausedForProcess(false);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME");
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME");
        NavigationTabsFilter.filterBottomTabs(tabs);
        assertEquals(joined, Settings.BOTTOM_NAVIGATION_TABS.savedValue());
    }

    @Test public void aClearModeToggleWhilePausedIsNotRememberedOverTheReadersChoice() {
        Settings.CLEAR_DISPLAY.save(false);
        Setting.setPausedForProcess(true);
        try {
            RememberClearDisplayPatch.rememberClearDisplayEvent(new ClearEvent(true, 0));
            assertFalse(Settings.CLEAR_DISPLAY.savedValue());
            assertFalse("paused, the remembered clear mode is not applied", Settings.CLEAR_DISPLAY.get());

            // The control: running normally, the same toggle is remembered.
            Setting.setPausedForProcess(false);
            RememberClearDisplayPatch.rememberClearDisplayEvent(new ClearEvent(true, 0));
            assertTrue(Settings.CLEAR_DISPLAY.savedValue());
        } finally {
            // The toggle leaves the process in Clear display, and the classes run after this one
            // share it: the overlay pass then took the bottom bar away in SeeThroughBottomBarTest.
            Setting.setPausedForProcess(false);
            RememberClearDisplayPatch.rememberClearDisplayEvent(new ClearEvent(false, 0));
        }
    }

    /** TikTok's clear-display event, by the two field names the hook reads. */
    public static final class ClearEvent {
        public final Boolean LIZ;
        public final Integer LIZIZ;

        ClearEvent(boolean clear, int type) {
            LIZ = clear;
            LIZIZ = type;
        }
    }

    public static final class Tab {
        private final String tag;

        Tab(String tag) {
            this.tag = tag;
        }

        public String getTag() {
            return tag;
        }
    }
}
