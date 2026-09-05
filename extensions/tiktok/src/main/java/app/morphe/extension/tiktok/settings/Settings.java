/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/Settings.java
 */

package app.morphe.extension.tiktok.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.navigation.BottomNavigationTabOptions;
import app.morphe.extension.tiktok.navigation.NavigationTabOptions;

public class Settings extends BaseSettings {
    public static final BooleanSetting REMOVE_ADS = new BooleanSetting("remove_ads", TRUE, true);
    public static final BooleanSetting HIDE_LIVE = new BooleanSetting("hide_live", FALSE, true);
    public static final BooleanSetting HIDE_SHOP = new BooleanSetting("hide_shop", FALSE, true);
    public static final BooleanSetting HIDE_STORY = new BooleanSetting("hide_story", FALSE, true);
    public static final BooleanSetting HIDE_IMAGE = new BooleanSetting("hide_image", FALSE, true);
    public static final BooleanSetting HIDE_CAPTCHA_POPUPS = new BooleanSetting("hide_captcha_popups", FALSE, true);
    public static final BooleanSetting HIDE_HOMEPAGE_COIN = new BooleanSetting("hide_homepage_coin", FALSE, true);
    public static final StringSetting MIN_MAX_VIEWS = new StringSetting("min_max_views", "0-" + Long.MAX_VALUE, true);
    public static final StringSetting MIN_MAX_LIKES = new StringSetting("min_max_likes", "0-" + Long.MAX_VALUE, true);
    public static final BooleanSetting FILTER_OFFLINE_FALLBACK_VIDEOS = new BooleanSetting(
            "filter_cached_offline_videos",
            TRUE,
            true
    );
    public static final BooleanSetting FEED_NAVIGATION = new BooleanSetting("feed_navigation", FALSE, true);
    public static final StringSetting FEED_NAVIGATION_TABS = new StringSetting(
            "feed_navigation_tabs",
            NavigationTabOptions.defaultEnabledKeys(),
            true,
            Setting.parent(FEED_NAVIGATION)
    );
    public static final BooleanSetting FEED_NAVIGATION_BLOCK_NEW_TABS = new BooleanSetting(
            "feed_navigation_block_new_tabs",
            FALSE,
            true,
            Setting.parent(FEED_NAVIGATION)
    );
    public static final StringSetting FEED_NAVIGATION_OBSERVED_TABS = new StringSetting(
            "feed_navigation_observed_tabs",
            NavigationTabOptions.HOT,
            false,
            false
    );
    public static final BooleanSetting BOTTOM_NAVIGATION = new BooleanSetting("bottom_navigation", FALSE, true);
    public static final StringSetting BOTTOM_NAVIGATION_TABS = new StringSetting(
            "bottom_navigation_tabs",
            BottomNavigationTabOptions.defaultEnabledKeys(),
            true,
            Setting.parent(BOTTOM_NAVIGATION)
    );
    public static final BooleanSetting BOTTOM_NAVIGATION_BLOCK_NEW_TABS = new BooleanSetting(
            "bottom_navigation_block_new_tabs",
            FALSE,
            true,
            Setting.parent(BOTTOM_NAVIGATION)
    );
    public static final StringSetting BOTTOM_NAVIGATION_OBSERVED_TABS = new StringSetting(
            "bottom_navigation_observed_tabs",
            BottomNavigationTabOptions.HOME,
            false,
            false
    );
    public static final BooleanSetting HIDE_TAKO_AI = new BooleanSetting("hide_tako_ai", FALSE, true);
    public static final BooleanSetting COMMENT_BATCH_TRANSLATION = new BooleanSetting("comment_batch_translation", FALSE);
    public static final StringSetting COMMENT_TRANSLATION_EXCLUDED_LANGUAGES = new StringSetting("comment_translation_excluded_languages", "");
    public static final BooleanSetting HIDE_COMMENT_QUICK_REACTIONS =
            new BooleanSetting("hide_comment_quick_reactions", FALSE);
    public static final BooleanSetting HIDE_FEED_FOLLOW_BUTTON =
            new BooleanSetting("hide_feed_follow_button", FALSE, true);
    public static final BooleanSetting HIDE_FEED_SAVE_BUTTON =
            new BooleanSetting("hide_feed_save_button", FALSE, true);
    public static final BooleanSetting HIDE_FEED_LIVE_BUTTON =
            new BooleanSetting("hide_feed_live_button", FALSE, true);
    public static final BooleanSetting HIDE_FEED_SEARCH_BUTTON =
            new BooleanSetting("hide_feed_search_button", FALSE, true);
    public static final StringSetting DOWNLOAD_PATH = new StringSetting("down_path", "DCIM/TikTok");
    public static final StringSetting DOWNLOAD_VIDEO_PATH = new StringSetting("download_video_path", "DCIM/TikTok");
    public static final StringSetting DOWNLOAD_PHOTO_PATH = new StringSetting("download_photo_path", "DCIM/TikTok");
    public static final StringSetting DOWNLOAD_STICKER_PATH = new StringSetting("download_sticker_path", "DCIM/TikTok");
    private static final BooleanSetting DOWNLOAD_PATHS_MIGRATED = new BooleanSetting(
            "download_paths_migrated",
            FALSE,
            false,
            false
    );
    public static final StringSetting DOWNLOAD_VIDEO_FILENAME_TEMPLATE = new StringSetting(
            "download_video_filename_template",
            "{creator}_{date}_{video_id}"
    );
    public static final StringSetting DOWNLOAD_PHOTO_FILENAME_TEMPLATE = new StringSetting(
            "download_photo_filename_template",
            "{creator}_{date}_{video_id}_{index}"
    );
    public static final StringSetting DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE = new StringSetting(
            "download_comment_media_filename_template",
            "comment_{date}_{media_id}"
    );
    public static final BooleanSetting DOWNLOAD_WATERMARK = new BooleanSetting("down_watermark", TRUE);
    public static final BooleanSetting CUSTOM_OFFLINE_VIDEOS = new BooleanSetting("custom_offline_videos", FALSE, true);
    public static final IntegerSetting CUSTOM_OFFLINE_VIDEO_LIMIT = new IntegerSetting(
            "custom_offline_video_limit",
            500,
            true,
            Setting.parent(CUSTOM_OFFLINE_VIDEOS)
    );
    public static final BooleanSetting SHOW_SEEKBAR = new BooleanSetting("show_seekbar", TRUE);
    public static final BooleanSetting SHOW_SEEKBAR_THUMBNAIL = new BooleanSetting(
            "show_seekbar_thumbnail",
            TRUE
    );
    public static final BooleanSetting STOP_VIDEO_LOOPING = new BooleanSetting("stop_video_looping", FALSE, true);
    public static final BooleanSetting RESUME_VIDEO_AFTER_SCROLL = new BooleanSetting(
            "resume_video_after_scroll",
            TRUE,
            true
    );
    public static final BooleanSetting OPEN_EXTERNAL_LINKS = new BooleanSetting("open_external_links", TRUE);
    public static final BooleanSetting ALWAYS_SHOW_PUBLISH_DATE = new BooleanSetting("always_show_publish_date", TRUE, true);
    public static final BooleanSetting CLEAR_DISPLAY = new BooleanSetting("clear_display", FALSE);
    public static final BooleanSetting FOLDABLE_SPLIT_VIEW = new BooleanSetting("foldable_split_view", FALSE, true);
    public static final IntegerSetting FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP = new IntegerSetting(
            "foldable_split_view_min_width_dp",
            600,
            true,
            Setting.parent(FOLDABLE_SPLIT_VIEW)
    );
    public static final BooleanSetting COPY_COMMENTS_WITHOUT_USERNAME = new BooleanSetting("copy_comments_without_username", TRUE);
    public static final FloatSetting REMEMBERED_SPEED = new FloatSetting("remembered_speed_v2", 1.0f);
    public static final BooleanSetting ENABLE_LONG_PRESS_SPEED_LOCK = new BooleanSetting("enable_long_press_speed_lock", FALSE, true);
    public static final BooleanSetting DISABLE_LONG_PRESS_QUICK_SHARE =
            new BooleanSetting("disable_long_press_quick_share", FALSE);
    public static final BooleanSetting DISABLE_LONG_PRESS_REPOST =
            new BooleanSetting("disable_long_press_repost", FALSE);
    public static final BooleanSetting ENABLE_NON_PERSONALIZED_SEARCH =
            new BooleanSetting("enable_non_personalized_search", FALSE, true);
    public static final BooleanSetting ENABLE_LIVE_SEARCH =
            new BooleanSetting("enable_live_search", FALSE, true);
    public static final BooleanSetting SIM_SPOOF = new BooleanSetting("simspoof", FALSE, true);
    public static final StringSetting SIM_SPOOF_ISO = new StringSetting("simspoof_iso", "us");
    public static final StringSetting SIMSPOOF_MCCMNC = new StringSetting("simspoof_mccmnc", "310260");
    public static final StringSetting SIMSPOOF_OP_NAME = new StringSetting("simspoof_op_name", "T-Mobile");

    static {
        if (!DOWNLOAD_PATHS_MIGRATED.get()) {
            String legacyPath = DOWNLOAD_PATH.get();
            DOWNLOAD_VIDEO_PATH.save(legacyPath);
            DOWNLOAD_PHOTO_PATH.save(legacyPath);
            DOWNLOAD_STICKER_PATH.save(legacyPath);
            DOWNLOAD_PATHS_MIGRATED.save(TRUE);
        }
    }
}
