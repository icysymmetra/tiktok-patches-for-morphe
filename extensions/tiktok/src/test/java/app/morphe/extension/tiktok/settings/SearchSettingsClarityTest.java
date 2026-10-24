package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en-w480dp-h960dp-night-mdpi")
@SuppressWarnings("deprecation")
public class SearchSettingsClarityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();

    @Before public void enableControls() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void restoreControls() throws Exception {
        for (var entry : statuses.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
    }

    @Test public void commentsHaveSeparateControlsForSuggestionsAndSearchingLoadedComments() {
        assertRow("COMMENTS", "hide_comment_search_suggestions",
                "Hide search suggestions above comments", "Search:");
        assertRow("COMMENTS", "comment_search", "Search within comments", "already loaded");
    }

    @Test public void compactHeaderExplainsBothSpaceSavingsAndHowToCloseComments() {
        assertRow("COMMENTS", "compact_comment_header", "Compact comment header", "comment count");
        assertRow("COMMENTS", "compact_comment_header", "Compact comment header", "Back");
        assertRow("COMMENTS", "compact_comment_header", "Compact comment header", "sort");
    }

    @Test public void commentHeartTargetSaysWhatGetsEasierWithoutChangingTheRowLayout() {
        assertRow("COMMENTS", "larger_comment_like_target", "Easier comment likes", "heart");
        assertRow("COMMENTS", "larger_comment_like_target", "Easier comment likes", "empty space");
    }

    @Test public void otherSearchControlsNameTheirOwnSurface() {
        assertRow("BEHAVIOR", "hide_search_suggestions",
                "Hide suggestions on the search page", "search history");
        assertRow("INBOX", "hide_inbox_search", "Hide the Inbox search button", "Inbox");
        assertRow("INTERFACE", "hide_feed_search_button",
                "Hide the search button on the feed", "comments");
        assertRow("INTERFACE", "hide_visual_search",
                "Hide Search this image prompts", "above comments");
    }

    @Test public void videoOverlayOptionsExplainWhatTheyHideAndWhatTheyKeep() {
        assertRow("INTERFACE", "hide_fullscreen_button", "Hide the Full screen button", "rotation");
        assertRow("INTERFACE", "hide_location_labels", "Hide location labels", "permissions");
    }

    @Test public void locationFilteringIsSeparateFromHidingTheBadge() {
        assertRow("FEED_FILTER", "filter_location_videos", "Filter location-tagged videos", "aren't paid ads");
        assertRow("FEED_FILTER", "filter_location_videos", "Filter location-tagged videos", "Hide location labels");
        assertRow("INTERFACE", "hide_location_labels", "Hide location labels", "multiple places");
    }

    @Test public void creationTagsNameWhatTheyHideAndLeavePlacesToTheirOwnSwitch() {
        assertRow("INTERFACE", "hide_creation_tags", "Hide effect and template tags", "CapCut");
        assertRow("INTERFACE", "hide_creation_tags", "Hide effect and template tags", "Location labels");
    }

    @Test public void bottomSearchNamesItsStripAndExplainsSpaceRecovery() {
        assertRow("INTERFACE", "hide_bottom_search_bar", "Hide the search bar below videos", "space");
        assertRow("INTERFACE", "hide_bottom_search_bar", "Hide the search bar below videos", "comments");
    }

    /**
     * #128. The side menu row names the menu it takes away and says a restart applies it. TikTok
     * builds that button with no view id, so the video overlay hider can't reach it: without the
     * LIVE patch's toolbar check on the side menu the row would be a switch that does nothing, and
     * it isn't shown. That check has its own flag, since a build the patch doesn't declare can
     * leave it out while the LIVE half applies.
     */
    @Test public void sideMenuButtonRowNamesTheMenuAndNeedsTheToolbarCheck() {
        assertRow("INTERFACE", "hide_feed_sidebar_button", "Hide the side menu button", "TikTok Minis");
        assertRow("INTERFACE", "hide_feed_sidebar_button", "Hide the side menu button", "Restart TikTok");

        SettingsStatus.hideFeedSidebarButtonEnabled = false;
        onPage("INTERFACE", page -> assertNull("the side menu row outlived its toolbar check",
                page.findPreference("hide_feed_sidebar_button")));

        SettingsStatus.hideFeedSidebarButtonEnabled = true;
        SettingsStatus.hideFeedLiveButtonEnabled = false;
        onPage("INTERFACE", page -> {
            assertNotNull("the LIVE row stays with the overlay hider", page.findPreference("hide_live_entrance"));
            assertNotNull("the side menu row follows its own flag, not the LIVE half's",
                    page.findPreference("hide_feed_sidebar_button"));
        });
    }

    private void assertRow(String section, String key, String title, String detail) {
        onPage(section, page -> {
            Preference row = page.findPreference(key);
            assertNotNull("Missing independent setting: " + key, row);
            assertEquals(title, row.getTitle().toString());
            assertTrue(key + " must explain its location and behavior", row.getSummary().toString().contains(detail));
        });
    }

    private void onPage(String section, java.util.function.Consumer<TikTokPreferenceFragment> check) {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = new TikTokPreferenceFragment();
            Bundle args = new Bundle();
            args.putString("morphe_settings_section", section);
            page.setArguments(args);
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, page).commit();
            activity.getFragmentManager().executePendingTransactions();
            check.accept(page);
        }
    }
}
