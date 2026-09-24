/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/FeedFilterPreferenceCategory.java
 */

package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.RangeValuePreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class FeedFilterPreferenceCategory extends ConditionalPreferenceCategory {
    public FeedFilterPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Feed filter");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.feedFilterEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(group(context, "Content types"));
        addPreference(new TogglePreference(
                context,
                "Remove feed ads", "Remove ads from feed.",
                Settings.REMOVE_ADS
        ));
        addPreference(new TogglePreference(
                context,
                "Hide TikTok Shop", "Hide TikTok shop from feed.",
                Settings.HIDE_SHOP
        ));
        addPreference(new TogglePreference(
                context,
                "Hide livestreams", "Hide livestreams from feed.",
                Settings.HIDE_LIVE
        ));
        addPreference(new TogglePreference(
                context,
                "Hide story", "Hide story from feed.",
                Settings.HIDE_STORY
        ));
        addPreference(new TogglePreference(
                context,
                "Hide image video", "Hide image video from feed.",
                Settings.HIDE_IMAGE
        ));
        addPreference(new TogglePreference(
                context,
                "Hide AI content",
                "Hide posts marked as AI-generated or AI-modified by TikTok or their creators. Unmarked AI content may still appear.",
                Settings.HIDE_AI_CONTENT
        ));

        addPreference(group(context, "Popularity limits"));
        addPreference(new RangeValuePreference(
                context,
                "Min/Max views", "The minimum or maximum views of a video to show.",
                Settings.MIN_MAX_VIEWS
        ));
        addPreference(new RangeValuePreference(
                context,
                "Min/Max likes", "The minimum or maximum likes of a video to show.",
                Settings.MIN_MAX_LIKES
        ));

        addPreference(group(context, "Offline fallback"));
        addPreference(new TogglePreference(
                context,
                "Filter offline fallback videos",
                "Apply the other content and popularity filters to downloaded fallback videos. Hide AI content always applies.",
                Settings.FILTER_OFFLINE_FALLBACK_VIDEOS
        ));
    }
}
