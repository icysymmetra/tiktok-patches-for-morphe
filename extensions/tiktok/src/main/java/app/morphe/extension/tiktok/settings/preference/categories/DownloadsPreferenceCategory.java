/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/DownloadsPreferenceCategory.java
 */

package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.DownloadPathPreference;
import app.morphe.extension.tiktok.settings.preference.DownloadQualityPreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.download.DownloadDestination;
import app.morphe.extension.tiktok.offline.CustomOfflineVideosLimitPatch;

@SuppressWarnings("deprecation")
public class DownloadsPreferenceCategory extends ConditionalPreferenceCategory {
    public DownloadsPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Downloads");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.downloadEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(group(context, "Video downloads"));
        addPreference(new DownloadQualityPreference(
                context,
                Settings.DOWNLOAD_VIDEO_QUALITY
        ));
        addPreference(new TogglePreference(
                context,
                "Remove watermark",
                "Apply to video downloads and image downloads.",
                Settings.DOWNLOAD_WATERMARK
        ));

        addPreference(group(context, "Save locations"));
        addPreference(new DownloadPathPreference(
                context,
                "Video destination",
                Settings.DOWNLOAD_VIDEO_PATH,
                DownloadDestination.Kind.VIDEO
        ));
        addPreference(new DownloadPathPreference(
                context,
                "Photo destination",
                Settings.DOWNLOAD_PHOTO_PATH,
                DownloadDestination.Kind.PHOTO
        ));
        addPreference(new DownloadPathPreference(
                context,
                "Sticker destination",
                Settings.DOWNLOAD_STICKER_PATH,
                DownloadDestination.Kind.STICKER
        ));

        addPreference(group(context, "File names"));
        addPreference(new InputTextPreference(
                context,
                "Video filename",
                "Tokens: {creator}, {date}, {video_id}, {story_id}. Story ID is an alias for the current item's video ID. The file extension is kept automatically.",
                Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE
        ));
        addPreference(new InputTextPreference(
                context,
                "Photo filename",
                "Tokens: {creator}, {date}, {video_id}, {story_id}, {index}. Story ID is an alias for the current item's video ID. The file extension is kept automatically.",
                Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE
        ));
        addPreference(new InputTextPreference(
                context,
                "Comment media filename",
                "Tokens: {date}, {media_id}. Works for image and video stickers.",
                Settings.DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE
        ));

        addPreference(group(context, "Offline viewing"));
        addPreference(new TogglePreference(
                context,
                "Custom offline videos",
                "Adds a custom option to TikTok's offline videos menu after restart.",
                Settings.CUSTOM_OFFLINE_VIDEOS
        ));
        addPreference(new NumberInputPreference(
                context,
                "Offline videos limit",
                "Choose 1-1000 videos. Values outside this range use the nearest valid limit. Restart TikTok after saving.",
                Settings.CUSTOM_OFFLINE_VIDEO_LIMIT,
                CustomOfflineVideosLimitPatch.MIN_LIMIT,
                CustomOfflineVideosLimitPatch.MAX_LIMIT
        ));

    }
}
