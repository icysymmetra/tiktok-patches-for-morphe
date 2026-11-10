/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/DownloadsPreferenceCategory.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/DownloadsPreferenceCategory.java
 */

package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;
import android.text.format.Formatter;

import app.morphe.extension.tiktok.offline.CustomOfflineVideosLimitPatch;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.DownloadPathPreference;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.ForgetSavedVideosPreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.download.DownloadDestination;
import app.morphe.extension.tiktok.download.DownloadNamePreview;
import app.morphe.extension.tiktok.download.ExternalDownloader;
import app.morphe.extension.tiktok.download.GridPicker;
import app.morphe.extension.tiktok.download.SavedVideoArchive;
import app.morphe.extension.tiktok.download.SoundFormat;

/**
 * Where files go and what goes in them, then video, photos and stickers, the long presses, the
 * story share sheet, subtitles, the hand-off to another app, and the offline limit. Each block
 * belongs to its own patch and any of them can be in the bundle alone, so every block is a plain
 * if and never a return: a return for one of them took every later block with it once.
 */
@SuppressWarnings("deprecation")
public class DownloadsPreferenceCategory extends ConditionalPreferenceCategory {
    public DownloadsPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Downloads");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.downloadEnabled || SettingsStatus.advancedDownloadsEnabled
                || SettingsStatus.customOfflineVideosEnabled
                || SettingsStatus.subtitleToolsEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        // The downloader is what reads the destinations and names. With only the offline
        // videos limit selected they were rows on a reachable page that changed nothing.
        if (SettingsStatus.downloadEnabled || SettingsStatus.advancedDownloadsEnabled) {
            addPreference(new SectionHeadingPreference(context, "Files"));
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
            if (SettingsStatus.downloadEnabled) {
                addPreference(new DownloadPathPreference(
                        context,
                        "Sticker destination",
                        Settings.DOWNLOAD_STICKER_PATH,
                        DownloadDestination.Kind.STICKER
                ));
            }
            addPreference(new InputTextPreference(
                    context,
                    "Video filename",
                    "Use {creator}, {date} and {video_id} to build the name. Start with "
                            + "{creator}/ to put each creator in a folder. The file ending is "
                            + "added for you.",
                    Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE
            ).withNameKeyboard().withPreview(DownloadNamePreview::video));
            addPreference(new InputTextPreference(
                    context,
                    "Photo filename",
                    "Use {creator}, {date}, {video_id} and {index} to build the name. {index} "
                            + "numbers the photos of a slideshow saved with Download original "
                            + "photos. TikTok's own save button numbers files by folder "
                            + "instead. The file ending is added for you.",
                    Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE
            ).withNameKeyboard().withPreview(DownloadNamePreview::photo));
            if (SettingsStatus.downloadEnabled) {
                addPreference(new InputTextPreference(
                        context,
                        "Comment media filename",
                        "Use {date} and {media_id} to build the name. Works for image and video "
                                + "stickers.",
                        Settings.DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE
                ).withNameKeyboard().withPreview(DownloadNamePreview::commentMedia));
                addPreference(new TogglePreference(
                        context,
                        "Remove watermark",
                        "Applies to both video and photo downloads.",
                        Settings.REMOVE_DOWNLOAD_WATERMARK
                ));
            }
        }
        if (SettingsStatus.advancedDownloadsEnabled) {
            addPreference(new SectionHeadingPreference(context, "Video"));
            addPreference(new ChoicePreference(context, "Video download quality", Settings.DOWNLOAD_VIDEO_QUALITY,
                    new String[]{"Automatic", "Highest", "Balanced", "Lowest", "1080p", "720p", "540p", "480p", "360p"},
                    new String[]{"auto", "highest", "medium", "lowest", "1080", "720", "540", "480", "360"}));
            addPreference(new TogglePreference(context, "Ask for the quality on each save",
                    "Download lists the qualities this video comes in, with the size TikTok gives for each, and saves the one you pick. The quality above stays as it is for the next save.",
                    Settings.DOWNLOAD_ASK_QUALITY));
            addPreference(new TogglePreference(context, "Fall back to the watermarked copy",
                    "When the video without the watermark can't be fetched, save TikTok's own watermarked copy instead and say so. It's the size TikTok's own save uses, so it may be smaller than the quality you picked.",
                    Settings.DOWNLOAD_WATERMARK_FALLBACK));
            addPreference(new TogglePreference(context, "Save videos without sound",
                    "Leave the sound out of the saved video. Save the sound as well still writes "
                            + "the sound file beside it if you want both.", Settings.DOWNLOAD_WITHOUT_SOUND));
            addPreference(new TogglePreference(context, "Save the sound as well",
                    "Write the video's sound beside it, in the format picked under Sound format. Android 10 and later file audio separately, so it lands in Music under the same folder name as your videos.", Settings.DOWNLOAD_AUDIO_TRACK));
            addPreference(new ChoicePreference(context, "Sound format", Settings.DOWNLOAD_SOUND_FORMAT,
                    new String[]{"As TikTok sent it (M4A or MP3)", "Opus (.ogg), a smaller file"},
                    new String[]{SoundFormat.ORIGINAL, SoundFormat.OPUS})
                    .withNote(SoundFormat.opusPossible()
                            ? "Applies to Save the sound as well and to Save the original sound on a long press. Opus makes a smaller file, but the sound is encoded again from TikTok's copy."
                            : "Opus needs Android 10 or later, so sounds save as TikTok sent them."));
            addPreference(new TogglePreference(context, "Save the cover as well",
                    "Save the video's cover picture at the largest size TikTok has, each time you tap Download. It goes to your photo folder, named after the video.",
                    Settings.DOWNLOAD_COVER));
            addPreference(new TogglePreference(context, "Save cover button in the share sheet",
                    "Adds Save cover next to Download when you share a video, so you save the cover only when you want it. While this is on, Download saves just the video, even with Save the cover as well on.",
                    Settings.SAVE_COVER_BUTTON));
            addPreference(new TogglePreference(context, "Show download progress",
                    "Show a progress bar while a video saves.", Settings.DOWNLOAD_PROGRESS));
            addPreference(new TogglePreference(context, "Pick videos to save from profile grids",
                    L10n.f(context, "Puts a circle on each video in a profile, Liked or Favorites "
                            + "grid. Pick up to %1$s, then tap Save to save them one after another, "
                            + "with a count and Cancel.", String.valueOf(GridPicker.MAX_PICKED)),
                    Settings.PICK_GRID_VIDEOS));
            addPreference(new TogglePreference(context, "Save details beside the video",
                    "Saves the caption, creator, link and post date in a text file next to the "
                            + "video. On Android 10 and later, the pair goes in Download or "
                            + "Documents under the same folder name. Applies to saves made by "
                            + "Metra.",
                    Settings.DOWNLOAD_DETAILS));
            addPreference(new TogglePreference(context, "Save details as JSON",
                    "Saves the details file in JSON format instead of plain text. Useful if "
                            + "another tool will read it.",
                    Settings.DOWNLOAD_DETAILS_JSON));
            addPreference(new TogglePreference(context, "Tag saved videos with their details",
                    "Stores the caption, creator, post date and link inside the video file, "
                            + "where media players can read them. Applies to saves made by "
                            + "Metra.",
                    Settings.DOWNLOAD_TAGS));
            addPreference(new TogglePreference(context, "Write the username and caption on the video",
                    "Saved videos show the @username and the first line of the caption in the "
                            + "bottom left corner. The phone encodes the whole video again to do "
                            + "it, so saving takes longer. Applies to saves made by Metra.",
                    Settings.DOWNLOAD_BURN_CAPTION));
            addPreference(new TogglePreference(context, "Check for already-saved videos",
                    L10n.f(context, "Remembers up to %1$s videos you saved here. If the file is "
                            + "still there, you get Open or Save again instead of a second "
                            + "copy.",
                            java.text.NumberFormat.getIntegerInstance().format(SavedVideoArchive.LIMIT)),
                    Settings.CHECK_SAVED_VIDEOS));
            addPreference(new TogglePreference(context, "Mark saved videos",
                    "Puts a ✓ by videos and photo posts you saved with Metra, on profile "
                            + "grids and next to the time in the feed. Works only while Check "
                            + "for already-saved videos is on.",
                    Settings.MARK_SAVED_VIDEOS));
            addPreference(new ForgetSavedVideosPreference(context));
        }
        if (SettingsStatus.advancedDownloadsEnabled || SettingsStatus.downloadEnabled) {
            addPreference(new SectionHeadingPreference(context, "Photos and stickers"));
        }
        if (SettingsStatus.advancedDownloadsEnabled) {
            addPreference(new TogglePreference(context, "Download original photos",
                    "Save every photo in the post at full size as a JPEG, not as the screen shows it.", Settings.DOWNLOAD_ORIGINAL_PHOTOS));
            addPreference(new TogglePreference(context, "Save photo posts as a video",
                    "On a one-photo post, Download video makes an MP4 with the post's sound and "
                            + "no TikTok logo or end card. On a post with several photos, "
                            + "Download asks if you want the originals or one video.", Settings.DOWNLOAD_PHOTOS_AS_VIDEO));
            addPreference(new NumberInputPreference(context, "Seconds per photo",
                    "How long each photo stays on screen in a video made from a photo post.",
                    Settings.PHOTO_VIDEO_SECONDS, "%1$s second", "%1$s seconds"));
        }
        if (SettingsStatus.downloadEnabled) {
            addPreference(new ChoicePreference(context, "Animated sticker format", Settings.DOWNLOAD_STICKER_FORMAT,
                    new String[]{"Video (MP4)", "GIF", "WebP, exactly as TikTok sent it"},
                    new String[]{"mp4", "gif", "webp"}));
        }
        if (SettingsStatus.advancedDownloadsEnabled) {
            addPreference(new SectionHeadingPreference(context, "Long press"));
            addPreference(new TogglePreference(context, "Save a profile picture on a long press",
                    "Press and hold a profile picture to save the full size original to your photo destination.",
                    Settings.SAVE_PROFILE_PICTURE));
            addPreference(new TogglePreference(context, "Save a story on a long press",
                    "Press and hold a story to save it. Stories have no save button of their own, "
                            + "and holding one is how TikTok pauses it, so this takes that gesture over.",
                    Settings.SAVE_STORY));
            addPreference(new SectionHeadingPreference(context, "Stories"));
            addPreference(new TogglePreference(context, "Save a story from its share sheet",
                    "Puts a save button in the sheet a story's Share button opens. It saves the story "
                            + "you're watching, and holding a story still pauses it.",
                    Settings.SAVE_STORY_FROM_SHARE));
        }
        if (SettingsStatus.subtitleToolsEnabled) {
            addPreference(new SectionHeadingPreference(context, "Subtitles"));
            addPreference(new TogglePreference(context, "Save subtitles beside videos",
                    "Saves subtitle (SRT) files with the video. They go to Movies on Android 11 "
                            + "and newer, Download on Android 10, and your video folder on "
                            + "older versions. On Android 11 and newer with Save details on, "
                            + "the video moves to Download or Documents and the subtitles stay "
                            + "in Movies.", Settings.DOWNLOAD_SUBTITLES));
            addPreference(new ChoicePreference(context, "Subtitle language", Settings.SUBTITLE_LANGUAGE,
                    new String[]{"Original language", "Device language, then original", "All available languages"},
                    new String[]{"original", "device", "all"}));
        }
        if (SettingsStatus.advancedDownloadsEnabled) {
            addPreference(new SectionHeadingPreference(context, "Hand-off"));
            addPreference(new InputTextPreference(
                    context,
                    "Send links to another app",
                    "Type the other app's ID, like com.dv.adm. The save button sends the "
                            + "video's link there instead of saving. Leave empty to save here.",
                    Settings.EXTERNAL_DOWNLOADER_PACKAGE)
                    .withCheck(value -> ExternalDownloader.packageNameProblem(value.trim()))
                    .withNameKeyboard());
            ChoicePreference ytdlnisType = new ChoicePreference(context, "YTDLnis download type",
                    Settings.YTDLNIS_DOWNLOAD_TYPE, new String[]{"Video", "Audio"},
                    new String[]{"video", "audio"});
            ytdlnisType.setSummary("Works only with YTDLnis (com.deniscerri.ytdl). Picks audio "
                    + "or video when the save button sends it a link.");
            addPreference(ytdlnisType);
            addPreference(new TogglePreference(context, "YTDLnis background mode",
                    "Works only with YTDLnis (com.deniscerri.ytdl). Starts the download in the "
                            + "background without showing its download card.", Settings.YTDLNIS_BACKGROUND));
        }
        if (SettingsStatus.customOfflineVideosEnabled) {
            addPreference(new SectionHeadingPreference(context, "Offline videos"));
            addPreference(new TogglePreference(
                    context,
                    "Use your own offline videos limit",
                    "Let the Offline videos menu use your own limit instead of TikTok's fixed one. Restart TikTok to apply this.",
                    Settings.CUSTOM_OFFLINE_VIDEOS
            ));
            addPreference(new NumberInputPreference(
                    context,
                    "Offline videos limit",
                    L10n.f(context, "Choose %1$d to %2$d videos. Values outside this range use the nearest valid limit. Restart TikTok to apply this.",
                            CustomOfflineVideosLimitPatch.MIN_LIMIT, CustomOfflineVideosLimitPatch.MAX_LIMIT),
                    Settings.CUSTOM_OFFLINE_VIDEO_LIMIT
            ) {
                @Override protected String extraSummaryLine() {
                    return L10n.f(getContext(), "About %1$s of storage at this limit", Formatter.formatShortFileSize(
                            getContext(), CustomOfflineVideosLimitPatch.storageBytes(Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.get())));
                }
            });
            if (SettingsStatus.keepOfflineVideosEnabled) {
                addPreference(new TogglePreference(
                        context,
                        "Keep offline videos until you delete them",
                        "TikTok removes videos saved for offline viewing after a set time, "
                                + "sometimes only two days, watched or not. With this on they "
                                + "stay until you delete them in TikTok's Offline videos "
                                + "settings. TikTok can still clear watched ones when your "
                                + "phone is low on space.",
                        Settings.KEEP_OFFLINE_VIDEOS
                ));
            }
        }
    }
}
