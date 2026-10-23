package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The export says which settings the reader changed. #149's reporter's export couldn't: a search
 * bar and captions were missing, and nothing in it said whether a switch was on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChangedSettingsReportTest {
    private static final String BLOCKED_NAME = "a sound somebody blocked";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    @After public void tearDown() {
        for (Setting<?> setting : some()) setting.resetToDefault();
        for (Setting<?> setting : kindsOfText()) setting.resetToDefault();
    }

    private static List<Setting<?>> some() {
        return Arrays.<Setting<?>>asList(Settings.HIDE_BOTTOM_SEARCH_BAR, Settings.SHOW_AUTHOR_REGION,
                Settings.HIDE_BLOCKED_SOUNDS, Settings.FADE_CONTROLS_OPACITY,
                Settings.BLOCKED_SOUND_NAMES, Settings.FEED_MUTED);
    }

    private static List<Setting<?>> kindsOfText() {
        return Arrays.<Setting<?>>asList(Settings.SWIPE_LEFT_ACTION, Settings.SHARE_ACTION_CATALOG,
                Settings.FEED_MUTE_BUTTON_POSITION, Settings.SIM_SPOOF_ISO, Settings.BLOCKED_SOUND_NAMES);
    }

    @Test public void changedSettingsAreListedByKindAndATextSettingKeepsItsValue() {
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        Settings.SHOW_AUTHOR_REGION.save(true);
        Settings.HIDE_BLOCKED_SOUNDS.save(false);
        Settings.FADE_CONTROLS_OPACITY.save(50);
        Settings.BLOCKED_SOUND_NAMES.save(BLOCKED_NAME);

        assertEquals(List.of(
                "5 of 5 settings differ from their defaults; typed text shows no value",
                "on: hide_bottom_search_bar, show_author_region",
                "off: hide_blocked_sounds",
                "set: fade_controls_opacity 50",
                "text changed: blocked_sound_names"),
                ChangedSettingsReport.lines(some()));
    }

    @Test public void untouchedSettingsSaySo() {
        assertEquals(List.of(ChangedSettingsReport.NONE), ChangedSettingsReport.lines(some()));
    }

    @Test public void whatABackupLeavesOutIsNotAChoice() {
        Settings.FEED_MUTED.save(true);
        assertEquals(List.of(ChangedSettingsReport.NONE), ChangedSettingsReport.lines(some()));
    }

    /**
     * The S22's first export with the section named swipe_left_action and left out what it was
     * set to, which is the half a report needs.
     */
    @Test public void aChoiceKeptAsTextShowsItsValue() {
        Settings.SWIPE_LEFT_ACTION.save("nothing");

        assertEquals(List.of(
                "1 of 3 settings differ from their defaults; typed text shows no value",
                "set: swipe_left_action nothing"),
                ChangedSettingsReport.lines(kindsOfText()));
    }

    /** That export also listed two of TikTok's own lists and four dragged buttons as changed. */
    @Test public void whatTikTokSentAndWhereAButtonSitsAreNotChoices() {
        Settings.SHARE_ACTION_CATALOG.save("stitch\tStitch\tv");
        Settings.FEED_MUTE_BUTTON_POSITION.save("0.901,0.208");

        assertEquals(List.of(ChangedSettingsReport.NONE), ChangedSettingsReport.lines(kindsOfText()));
    }

    /** One word typed where a list of names goes reads like a choice. Its default says it isn't. */
    @Test public void typedTextIsNamedHoweverShortItIs() {
        Settings.BLOCKED_SOUND_NAMES.save("cats");
        Settings.SIM_SPOOF_ISO.save("de");
        Settings.SWIPE_LEFT_ACTION.save("a value no list offers");

        assertEquals(List.of(
                "3 of 3 settings differ from their defaults; typed text shows no value",
                "text changed: blocked_sound_names, simspoof_iso, swipe_left_action"),
                ChangedSettingsReport.lines(kindsOfText()));
    }

    /**
     * Every text setting whose value an export can carry, each of them a pick from a list, a
     * number or a range. A new text setting that starts on a word lands here and fails this until
     * someone has checked that nothing typed into it is private.
     */
    @Test public void theTextSettingsThatShowAValueAreTheReviewedOnes() {
        // Each setting is a static field of Settings, so reading one is what creates the rest.
        assertEquals("swipe_left_action", Settings.SWIPE_LEFT_ACTION.key);
        TreeSet<String> shown = new TreeSet<>();
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (setting.includeWithImportExport && ChangedSettingsReport.showsItsValue(setting)) {
                shown.add(setting.key);
            }
        }

        assertEquals(new TreeSet<>(List.of(
                "app_lock_timeout", "bottom_navigation_tabs", "burn_in_guard", "caption_background",
                "default_speed", "double_tap_action", "download_sound_format", "download_sticker_format",
                "download_video_quality", "feed_navigation_tabs", "hold_speed", "live_min_max_followers",
                "live_min_max_viewers", "long_press_action", "min_max_comments", "min_max_favourites",
                "min_max_likes", "min_max_shares", "min_max_views", "network_proxy_type",
                "playback_quality", "playback_quality_metered", "seekbar_time_placement", "start_page",
                "store_identity_installer", "subtitle_language", "swipe_left_action", "swipe_levels_left", "swipe_levels_right",
                "touch_target_scale", "ytdlnis_download_type")), shown);
    }

    @Test public void aLongListWraps() {
        Setting<?>[] many = new Setting<?>[ChangedSettingsReport.PER_LINE + 1];
        Arrays.fill(many, Settings.HIDE_BOTTOM_SEARCH_BAR);
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);

        List<String> lines = ChangedSettingsReport.lines(Arrays.asList(many));

        assertEquals(3, lines.size());
        assertEquals("on: hide_bottom_search_bar", lines.get(2));
    }

    /**
     * The redactor blanks the value after a name holding "auth", so a switch written as
     * {@code show_author_region: on} would reach the tracker as "[omitted]".
     */
    @Test public void theExportCarriesTheKeysTheRedactorWouldBlankAndNoTextValue() {
        ChangedSettingsReport.install();
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        Settings.SHOW_AUTHOR_REGION.save(true);
        Settings.BLOCKED_SOUND_NAMES.save(BLOCKED_NAME);

        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Test", "INFO", "something worth reporting");
        String report = LogBufferManager.buildExportText();

        assertTrue(report, report.contains("[" + ChangedSettingsReport.TITLE + "]\n"));
        assertTrue(report, report.contains("hide_bottom_search_bar"));
        assertTrue(report, report.contains("show_author_region"));
        assertTrue(report, report.contains("blocked_sound_names"));
        assertFalse(report, report.contains(BLOCKED_NAME));
    }
}
