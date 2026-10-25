/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SwitchListPreference;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Every row on the Feed screen page, with the windows it reaches: the main feed (For You,
 * Following and the other top tabs), the detail pager a video opens in from a profile, a hashtag,
 * a sound or search, and a LIVE room.
 *
 * <p>#47 and #50 were the same gap twice: a switch written for the main feed, and a user finding
 * it missing on videos opened from a profile. Coverage was decided one switch at a time and
 * written down nowhere. Now a new row fails here until it says where it works, and a switch that
 * leaves out a window where TikTok draws the thing it changes says so on its own row.
 *
 * <p>The entries come from reading each switch's hook, 2026-10-10. A hook on a model getter, a
 * gate or a parser that every window goes through counts as reaching every window that shows the
 * thing. The ones the code can't settle are {@link Reach#UNCHECKED}, and only the rows in
 * {@link #WAITING_FOR_A_DEVICE} may say that, so the list can only shrink.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FeedScreenCoverageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Reach {
        /** The switch works there. */
        YES,
        /** TikTok draws the thing there and the switch leaves it be: the row has to say so. */
        NO,
        /** TikTok doesn't draw the thing there, so there is nothing to reach. */
        NONE,
        /** The code can't say; a phone has to. */
        UNCHECKED
    }

    static final class Coverage {
        final Reach main, detail, live;
        /** For a row with a NO, the words on the row that say which window it leaves out. */
        final String saysSo;

        Coverage(Reach main, Reach detail, Reach live, String saysSo) {
            this.main = main;
            this.detail = detail;
            this.live = live;
            this.saysSo = saysSo;
        }

        boolean any(Reach reach) {
            return main == reach || detail == reach || live == reach;
        }
    }

    private static final Reach YES = Reach.YES, NONE = Reach.NONE, UNCHECKED = Reach.UNCHECKED;

    /**
     * The rows whose reach in one window only a phone can settle. Each needs a look with the
     * switch on, in a video opened from a profile or search (or a LIVE room for the LIVE ones),
     * after which its entry says YES, NO or NONE and it comes off this list.
     */
    static final Set<String> WAITING_FOR_A_DEVICE = Set.of(
            "show_exact_counts", "hide_story_rings", "hide_live_ring", "hide_feed_search_button",
            "hide_playlist_bar", "hide_event_badge", "hide_inserted_cards", "hide_share_guide",
            "caption_text_size", "caption_background", "keep_captions_clear_display", "caption_regular_weight",
            "double_tap_action", "confirm_quick_repost");

    static final Map<String, Coverage> TABLE = new LinkedHashMap<>();

    private static void put(String key, Reach main, Reach detail, Reach live) {
        put(key, main, detail, live, null);
    }

    private static void put(String key, Reach main, Reach detail, Reach live, String saysSo) {
        if (TABLE.put(key, new Coverage(main, detail, live, saysSo)) != null) {
            throw new IllegalStateException("two entries for " + key);
        }
    }

    static {
        // Right column. The rail lives in each video's cell, which the overlay walk reaches in
        // both windows (#47). A LIVE room has its own screen with no rail.
        for (String rail : new String[]{"hide_rail_follow", "hide_rail_like", "hide_rail_comments",
                "hide_rail_favourite", "hide_rail_share", "hide_rail_music", "hide_rail_counts"}) {
            put(rail, YES, YES, NONE);
        }
        put("hide_feed_action_bar", YES, YES, NONE);
        put("touch_target_scale", YES, YES, NONE);
        // The disc's turn hangs on two AB settings read once per launch, which the cover assem
        // of every cell asks, in either window (#68).
        put("spin_music_disc", YES, YES, NONE);
        put("stop_music_disc_spin", YES, YES, NONE);
        put("hide_feed_follow_button", YES, YES, NONE);
        put("hide_feed_save_button", YES, YES, NONE);
        // Formatters and model getters every screen reads; whether a LIVE room reads them too
        // is the open part.
        put("show_exact_counts", YES, YES, UNCHECKED);
        put("hide_story_rings", YES, YES, UNCHECKED);
        put("hide_live_ring", YES, YES, UNCHECKED);

        // Buttons on videos: Hushfeed's own overlay, attached to whichever feed window is in
        // front, main or detail. It isn't drawn in a LIVE room.
        for (String button : new String[]{"block_author_button", "local_hide_button",
                "block_sound_button", "not_interested_button", "feed_mute_button"}) {
            put(button, YES, YES, NONE);
        }

        // Video info. The text sizes, the date and the creator row hook TikTok's own owners,
        // which both windows share.
        put("feed_description_text_size", YES, YES, NONE);
        put("feed_author_text_size", YES, YES, NONE);
        put("always_show_publish_date", YES, YES, NONE);
        put("publish_date_exact_time", YES, YES, NONE);
        // Both only shape the exact time, so they reach where it does.
        put("publish_date_time_zone", YES, YES, NONE);
        put("publish_date_short_date", YES, YES, NONE);
        // Profile grids only.
        put("publish_date_on_grid", NONE, NONE, NONE);
        put("video_length_on_grid", NONE, NONE, NONE);
        put("show_author_region", YES, YES, NONE);
        // Only how the region label shows its code, so it reaches where the label does (#157).
        put("region_as_flag", YES, YES, NONE);
        put("show_author_handle", YES, YES, NONE);
        put("show_engagement_rate", YES, YES, NONE);
        put("hide_feed_caption", YES, YES, NONE);
        put("hide_feed_music", YES, YES, NONE);

        // Around the video. The LIVE button and the side menu button are the home toolbar's,
        // which an opened video doesn't have.
        put("hide_live_entrance", YES, NONE, NONE);
        put("hide_feed_sidebar_button", YES, NONE, NONE);
        // Whether an opened video shows a search button of its own is the open part.
        put("hide_feed_search_button", YES, UNCHECKED, NONE);
        put("hide_fullscreen_button", YES, YES, NONE);
        put("hide_feed_report_button", YES, YES, NONE);
        put("hide_location_labels", YES, YES, NONE);
        put("hide_creation_tags", YES, YES, NONE);
        put("hide_feed_surveys", YES, YES, NONE);
        put("hide_footnotes", YES, YES, NONE);
        put("hide_visual_search", YES, YES, NONE);
        // The bar is an opened post's: the detail pager's videos and, on 47.1.4, a photo from
        // search in the main activity. The main feed has the tabs there.
        put("hide_detail_comment_bar", NONE, YES, NONE);
        // The search box in an opened video's top bar; the main feed has the home toolbar there (#158).
        put("hide_opened_video_search", NONE, YES, NONE);
        put("hide_bottom_search_bar", YES, YES, NONE);
        put("hide_playlist_bar", YES, UNCHECKED, NONE);
        put("hide_event_badge", YES, UNCHECKED, NONE);
        put("hide_inserted_cards", YES, UNCHECKED, NONE);

        // Popups. The promotion and popup hooks sit where TikTok parses or raises them, which
        // every window goes through.
        put("hide_homepage_coin", YES, YES, NONE);
        // SecApiImpl's puzzle entry points serve every window, and LiveHostUser has its own copy.
        put("hide_browsing_captcha_popups", YES, YES, YES);
        put("popup_label_checklist", YES, YES, YES);
        put("hide_live_bubble", YES, YES, NONE);
        put("hide_wind_down_screens", YES, YES, NONE);
        put("hide_sensitive_warnings", YES, YES, NONE);
        put("hide_unverified_notices", YES, YES, NONE);
        // A feed cell slot both pagers build, and a prompt every video share package goes through.
        put("hide_check_your_sources", YES, YES, NONE);
        // The main feed's panel; whether an opened video raises the prompt through another is open.
        put("hide_share_guide", YES, UNCHECKED, NONE);

        // Captions: a hook on the caption renderer, if an opened video uses the same one.
        put("caption_text_size", YES, UNCHECKED, NONE);
        put("caption_background", YES, UNCHECKED, NONE);
        put("keep_captions_clear_display", YES, UNCHECKED, NONE);
        // The same caption layout the size reaches (#158).
        put("caption_regular_weight", YES, UNCHECKED, NONE);

        // Clear display follows TikTok's own mode in either window (#84).
        put("automatic_clear_display", YES, YES, NONE);
        put("automatic_clear_display_delay", YES, YES, NONE);
        put("hide_clear_display_controls", YES, YES, NONE);
        put("fade_controls_opacity", YES, YES, NONE);
        put("fade_in_clear_display", YES, YES, NONE);
        // The tab bar belongs to the main feed; an opened video has none (#145).
        put("see_through_bottom_bar", YES, NONE, NONE);
        // Only the look of that same bar (#124).
        put("floating_bottom_bar", YES, NONE, NONE);
        // The main feed only: an opened video keeps the strip under its own top bar (#145).
        put("see_through_status_bar", YES, NONE, NONE);
        // The guard runs with the overlay pass, which walks both windows and no LIVE room.
        put("burn_in_guard", YES, YES, NONE);

        // Gestures.
        put("swipe_levels", YES, YES, NONE);
        put("swipe_levels_left", YES, YES, NONE);
        put("swipe_levels_right", YES, YES, NONE);
        put("swipe_levels_strip_percent", YES, YES, NONE);
        put("double_tap_action", YES, UNCHECKED, NONE);
        put("swipe_left_action", YES, YES, NONE);
        put("long_press_action", YES, YES, NONE);
        // The same press hook as the choice it narrows, so it reaches the same windows (#136).
        put("long_press_sides_only", YES, YES, NONE);
        // Only the profile-opened video's compact menu changes; For You already has the full menu.
        put("long_press_full_menu_on_profiles", NONE, YES, NONE);
        // The same for a video opened from search, which opens in the detail pager (#120).
        put("long_press_full_menu_in_search", NONE, YES, NONE);
        put("edge_seek", YES, YES, NONE);
        put("edge_seek_seconds", YES, YES, NONE);
        put("rail_hold_comment", YES, YES, NONE);
        put("rail_hold_share", YES, YES, NONE);
        put("rail_hold_favorites", YES, YES, NONE);
        put("enable_long_press_speed_lock", YES, YES, NONE);
        put("disable_long_press_quick_share", YES, YES, NONE);
        put("disable_long_press_repost", YES, YES, NONE);
        put("confirm_follow", YES, YES, NONE);
        put("confirm_like", YES, YES, NONE);
        // The comments sheet, opened from either window.
        put("confirm_comment_like", YES, YES, NONE);
        // The story viewer only.
        put("confirm_story_like", NONE, NONE, NONE);
        put("confirm_quick_repost", YES, UNCHECKED, NONE);
    }

    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();

    @Before public void turnEveryPatchOn() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())
                    && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void putThePatchesBack() throws Exception {
        for (Map.Entry<Field, Boolean> status : statuses.entrySet()) {
            status.getKey().setBoolean(null, status.getValue());
        }
    }

    /** The Feed screen page as a phone with every patch builds it, by key. */
    private static Map<String, Preference> page() {
        try (var controller = Robolectric.buildActivity(AdvancedDownloadsTest.TestActivity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            PreferenceScreen screen = ((android.preference.PreferenceActivity) activity)
                    .getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            Map<String, Preference> rows = new LinkedHashMap<>();
            collect(screen, rows);
            return rows;
        }
    }

    private static void collect(Preference preference, Map<String, Preference> into) {
        if (preference instanceof SwitchListPreference) {
            // One row, several switches: each is its own entry.
            for (BooleanSetting setting : ((SwitchListPreference) preference).settings()) {
                into.put(setting.key, preference);
            }
        } else if (preference.getKey() != null) {
            into.put(preference.getKey(), preference);
        }
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            for (int i = 0; i < group.getPreferenceCount(); i++) collect(group.getPreference(i), into);
        }
    }

    /** What's wrong between the page's rows and the table, in words a failure can print. */
    static List<String> problems(Map<String, CharSequence> summaries, Map<String, Coverage> table,
                                 Set<String> waiting) {
        List<String> problems = new ArrayList<>();
        for (String key : summaries.keySet()) {
            if (!table.containsKey(key)) {
                problems.add(key + " is on the Feed screen page with no entry saying which windows it reaches");
            }
        }
        for (String key : table.keySet()) {
            if (!summaries.containsKey(key)) problems.add(key + " has an entry but no row any more");
        }
        for (Map.Entry<String, Coverage> entry : table.entrySet()) {
            String key = entry.getKey();
            Coverage coverage = entry.getValue();
            CharSequence summary = summaries.get(key);
            String text = summary == null ? "" : summary.toString();
            if (coverage.any(Reach.NO)) {
                if (coverage.saysSo == null || coverage.saysSo.isEmpty()) {
                    problems.add(key + " leaves a window out and its entry doesn't say what the row tells the reader");
                } else if (summary != null && !text.contains(coverage.saysSo)) {
                    problems.add(key + " leaves a window out and its row doesn't say \"" + coverage.saysSo + "\"");
                }
            }
            // A home-feed control, absent from opened videos, is placed on the feed by its row.
            if (coverage.main == Reach.YES && coverage.detail == Reach.NONE && summary != null
                    && !text.contains("feed")) {
                problems.add(key + " is the main feed's alone and its row doesn't say it's on the feed");
            }
            if (coverage.any(Reach.UNCHECKED) && !waiting.contains(key)) {
                problems.add(key + " is unchecked but not on the list waiting for a phone");
            }
            if (!coverage.any(Reach.UNCHECKED) && waiting.contains(key)) {
                problems.add(key + " is settled, so it comes off the list waiting for a phone");
            }
        }
        for (String key : waiting) {
            if (!table.containsKey(key)) problems.add(key + " is waiting for a phone but has no entry");
        }
        return problems;
    }

    private static Map<String, CharSequence> summaries(Map<String, Preference> rows) {
        Map<String, CharSequence> summaries = new LinkedHashMap<>();
        for (Map.Entry<String, Preference> row : rows.entrySet()) {
            summaries.put(row.getKey(), row.getValue().getSummary());
        }
        return summaries;
    }

    @Test public void everyFeedScreenRowSaysWhichWindowsItReaches() {
        Map<String, Preference> rows = page();
        assertTrue("the page came out nearly empty, so the patch flags didn't take: " + rows.keySet(),
                rows.size() > 60);
        List<String> problems = problems(summaries(rows), TABLE, WAITING_FOR_A_DEVICE);
        assertEquals("Feed screen coverage:\n" + String.join("\n", problems), 0, problems.size());
    }

    /** Each rule above reports what it's for, so a green run means the table was read. */
    @Test public void theCoverageCheckCanActuallyFail() {
        Map<String, CharSequence> summaries = new LinkedHashMap<>();
        summaries.put("new_switch", "Hide a thing.");
        summaries.put("gap", "Hide a thing on videos.");
        summaries.put("toolbar", "Hide the top button.");
        summaries.put("guess", "Hide another thing.");
        Map<String, Coverage> table = new LinkedHashMap<>();
        table.put("gap", new Coverage(YES, Reach.NO, NONE, "Opened videos keep it."));
        table.put("toolbar", new Coverage(YES, NONE, NONE, null));
        table.put("guess", new Coverage(YES, UNCHECKED, NONE, null));
        table.put("gone", new Coverage(YES, YES, NONE, null));
        summaries.put("settled", "Hide one more thing.");
        table.put("settled", new Coverage(YES, YES, NONE, null));
        summaries.put("fine", "Hide the button at the top of the feed.");
        table.put("fine", new Coverage(YES, NONE, NONE, null));

        Set<String> found = new TreeSet<>();
        for (String problem : problems(summaries, table, Set.of("settled_long_ago", "settled"))) {
            found.add(problem.substring(0, problem.indexOf(' ')));
        }
        assertEquals(new TreeSet<>(Arrays.asList("gap", "gone", "guess", "new_switch", "settled",
                "settled_long_ago", "toolbar")), found);
    }

    /**
     * The overlay walk's switches, run in both windows the table says they reach. A video
     * opened from a profile plays in DetailActivity with the same cell and ids as the feed.
     */
    @Test public void theOverlaySwitchesHideInTheFeedAndInAnOpenedVideo() throws Exception {
        // The walk runs with the patch flags a phone starts with, as in VideoOverlayHiderTest,
        // not with every patch on as the page needs.
        putThePatchesBack();
        int cellId = 0x7f0a1c01;
        Map<String, Integer> ids = new LinkedHashMap<>();
        ids.put("desc", 0x7f0a1c02);
        ids.put("47.4.3:o7a", 0x7f0a1c03);
        ids.put("47.4.3:mud", 0x7f0a1c04);
        ids.put("47.4.3:f92", 0x7f0a1c05);
        Map<BooleanSetting, String> switches = new LinkedHashMap<>();
        switches.put(Settings.HIDE_FEED_CAPTION, "desc");
        switches.put(Settings.HIDE_FEED_MUSIC, "47.4.3:o7a");
        switches.put(Settings.HIDE_FEED_ACTION_BAR, "47.4.3:mud");
        switches.put(Settings.HIDE_FEED_SURVEYS, "47.4.3:f92");
        for (BooleanSetting setting : switches.keySet()) {
            Coverage coverage = TABLE.get(setting.key);
            assertNotNull(setting.key, coverage);
            assertEquals(setting.key, YES, coverage.main);
            assertEquals(setting.key, YES, coverage.detail);
        }

        VideoOverlayHider.resolveForTests("view_rootview", cellId);
        for (Map.Entry<String, Integer> id : ids.entrySet()) VideoOverlayHider.resolveForTests(id.getKey(), id.getValue());
        try (var main = Robolectric.buildActivity(com.ss.android.ugc.aweme.main.MainActivity.class).create().start();
             var detail = Robolectric.buildActivity(
                     com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).create().start()) {
            for (var window : List.of(main, detail)) {
                Activity activity = window.get();
                Utils.setContext(activity);
                FrameLayout cell = new FrameLayout(activity);
                cell.setId(cellId);
                Map<BooleanSetting, View> views = new LinkedHashMap<>();
                for (Map.Entry<BooleanSetting, String> entry : switches.entrySet()) {
                    View view = new View(activity);
                    view.setId(ids.get(entry.getValue()));
                    cell.addView(view);
                    views.put(entry.getKey(), view);
                }
                FrameLayout root = new FrameLayout(activity);
                root.addView(cell);
                activity.setContentView(root);
                window.resume();
                String where = activity.getClass().getSimpleName();

                for (BooleanSetting setting : switches.keySet()) setting.save(true);
                VideoOverlayHider.applyTo(activity);
                for (Map.Entry<BooleanSetting, View> view : views.entrySet()) {
                    assertEquals(view.getKey().key + " in " + where, View.GONE, view.getValue().getVisibility());
                }
                for (BooleanSetting setting : switches.keySet()) setting.save(false);
                VideoOverlayHider.applyTo(activity);
                for (Map.Entry<BooleanSetting, View> view : views.entrySet()) {
                    assertEquals(view.getKey().key + " back in " + where, View.VISIBLE, view.getValue().getVisibility());
                }
            }
        } finally {
            for (BooleanSetting setting : switches.keySet()) setting.save(false);
            VideoOverlayHider.resolveForTests("view_rootview", 0);
            for (String name : ids.keySet()) VideoOverlayHider.resolveForTests(name, 0);
        }
    }

}
