/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.navigation.StartPage;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.TabSelectionPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class FeedNavigationPreferenceCategory extends ConditionalPreferenceCategory {
    public FeedNavigationPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Feed tabs");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.feedNavigationEnabled || SettingsStatus.bottomTabLabelsEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new TogglePreference(
                context,
                "Filter feed tabs",
                "Choose which feed tabs stay visible. If only For You is left, its tab name is "
                        + "hidden.",
                Settings.FEED_NAVIGATION
        ));
        addPreference(new TabSelectionPreference(
                context,
                Settings.FEED_NAVIGATION_TABS
        ));
        addPreference(new TogglePreference(
                context,
                "Block new feed tabs",
                "Hide tabs TikTok adds later unless you allow them.",
                Settings.FEED_NAVIGATION_BLOCK_NEW_TABS
        ));
        addPreference(new TogglePreference(
                context,
                "Hide the tab names above the feed",
                "The Following and For You names above the feed go away. Swiping between the feeds and the search button stay as they are.",
                Settings.HIDE_FEED_TAB_STRIP
        ));
        addPreference(new TogglePreference(
                context,
                "Filter bottom tabs",
                "Choose which bottom tabs stay visible.",
                Settings.BOTTOM_NAVIGATION
        ));
        addPreference(new TabSelectionPreference(
                context,
                Settings.BOTTOM_NAVIGATION_TABS,
                true
        ));
        addPreference(new TogglePreference(
                context,
                "Block new bottom tabs",
                "Hide bottom tabs TikTok adds later unless you allow them.",
                Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS
        ));
        addPreference(new ChoicePreference(context, "Open TikTok on", Settings.START_PAGE,
                new String[]{"TikTok default", "For You", "Following", "Friends", "Inbox", "Profile"},
                new String[]{StartPage.TIKTOK, StartPage.FOR_YOU, StartPage.FOLLOWING, StartPage.FRIENDS, StartPage.INBOX, StartPage.PROFILE}));
        addPreference(new TogglePreference(
                context,
                "Show TikTok's feed buttons",
                "Shows TikTok's play, pause, previous and next buttons on the feed. TikTok "
                        + "normally shows them only to people who use a screen reader. TikTok "
                        + "can see when you use them.",
                Settings.SHOW_FEED_BUTTONS
        ));
        addPreference(new TogglePreference(
                context,
                "Long-press Home for Metra settings",
                "Press and hold the Home tab to open these settings. A tap on Home works as before.",
                Settings.HOME_TAB_OPENS_SETTINGS
        ));
        addPreference(new TogglePreference(
                context,
                "Keep For You on a Home tap",
                "A tap on Home or on the For You tab while For You is showing no longer reloads it. "
                        + "The video you're on stays.",
                Settings.KEEP_FOR_YOU_ON_TAB_TAP
        ));
        addPreference(new TogglePreference(
                context,
                "Keep For You on a pull down",
                "Pulling down at the top of For You no longer reloads it.",
                Settings.KEEP_FOR_YOU_ON_PULL_DOWN
        ));
        addPreference(new TogglePreference(
                context,
                "Hide the unread badges on the bottom tabs",
                "Hide the red counts and dots on every bottom tab, Inbox and Profile included, and the "
                        + "bubble over Inbox that counts new likes, followers, comments and who's LIVE. "
                        + "The inbox itself still shows what came in.",
                Settings.HIDE_TAB_BADGES
        ));
        // Left out with their hooks on a build where the tab icons' names weren't found.
        if (SettingsStatus.bottomTabLabelsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the bottom tab names",
                    "The words under the bottom tab icons go away. The icons stay, and each tab "
                            + "opens what it always did.",
                    Settings.HIDE_BOTTOM_TAB_LABELS
            ));
            addTabName(context, "Home tab name", Settings.BOTTOM_TAB_NAME_HOME);
            addTabName(context, "Friends tab name", Settings.BOTTOM_TAB_NAME_FRIENDS);
            addTabName(context, "Inbox tab name", Settings.BOTTOM_TAB_NAME_INBOX);
            addTabName(context, "Profile tab name", Settings.BOTTOM_TAB_NAME_PROFILE);
            addTabName(context, "Shop tab name", Settings.BOTTOM_TAB_NAME_SHOP);
        }
        addPreference(new TogglePreference(
                context,
                "Hide the Tako bubble",
                "Hide the Tako AI bubble above the profile button or in the bottom corner of videos, "
                        + "the Ask bar under videos, "
                        + "the Ask Tako button on the search page, the Ask Tako tab on search results "
                        + "and the Tako bar above comments.",
                Settings.HIDE_TAKO_AI
        ));
    }

    private void addTabName(Context context, String title, StringSetting setting) {
        addPreference(new InputTextPreference(
                context,
                title,
                "A name of your own under this tab's icon. Leave it empty to keep TikTok's.",
                setting
        ));
    }
}
