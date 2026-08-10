/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/SettingsStatus.java
 */

package app.morphe.extension.tiktok.settings;

public class SettingsStatus {
    public static boolean feedFilterEnabled = false;
    public static boolean feedNavigationEnabled = false;
    public static boolean commentTranslationEnabled = false;
    public static boolean hideCommentQuickReactionsEnabled = false;
    public static boolean copyCommentsWithoutUsernameEnabled = false;
    public static boolean downloadEnabled = false;
    public static boolean simSpoofEnabled = false;
    public static boolean captchaPopupSuppressionEnabled = false;
    public static boolean promotionalBannersEnabled = false;
    public static boolean longPressSpeedLockEnabled = false;
    public static boolean disableLongPressQuickShareEnabled = false;
    public static boolean disableLongPressRepostEnabled = false;
    public static boolean nonPersonalizedSearchEnabled = false;
    public static boolean liveSearchEnabled = false;
    public static boolean seekbarThumbnailEnabled = false;
    public static boolean stopVideoLoopingEnabled = false;
    public static boolean resumeVideoAfterScrollEnabled = false;
    public static boolean externalBrowserEnabled = false;
    public static boolean alwaysShowPublishDateEnabled = false;
    public static boolean diagnosticsEnabled = false;

    public static void enableFeedFilter() {
        feedFilterEnabled = true;
    }

    public static void enableFeedNavigation() {
        feedNavigationEnabled = true;
    }

    public static void enableCommentTranslation() {
        commentTranslationEnabled = true;
    }

    public static void enableHideCommentQuickReactions() {
        hideCommentQuickReactionsEnabled = true;
    }

    public static void enableCopyCommentsWithoutUsername() {
        copyCommentsWithoutUsernameEnabled = true;
    }

    public static void enableDownload() {
        downloadEnabled = true;
    }

    public static void enableSimSpoof() {
        simSpoofEnabled = true;
    }

    public static void enableCaptchaPopupSuppression() {
        captchaPopupSuppressionEnabled = true;
    }

    public static void enablePromotionalBanners() {
        promotionalBannersEnabled = true;
    }

    public static void enableLongPressSpeedLock() {
        longPressSpeedLockEnabled = true;
    }

    public static void enableDisableLongPressQuickShare() {
        disableLongPressQuickShareEnabled = true;
    }

    public static void enableDisableLongPressRepost() {
        disableLongPressRepostEnabled = true;
    }

    public static void enableNonPersonalizedSearch() {
        nonPersonalizedSearchEnabled = true;
    }

    public static void enableLiveSearch() {
        liveSearchEnabled = true;
    }

    public static void enableSeekbarThumbnail() {
        seekbarThumbnailEnabled = true;
    }

    public static void enableStopVideoLooping() {
        stopVideoLoopingEnabled = true;
    }

    public static void enableResumeVideoAfterScroll() {
        resumeVideoAfterScrollEnabled = true;
    }

    public static void enableExternalBrowser() {
        externalBrowserEnabled = true;
    }

    public static void enableAlwaysShowPublishDate() {
        alwaysShowPublishDateEnabled = true;
    }

    public static void enableDiagnostics() {
        diagnosticsEnabled = true;
    }

    public static void load() {
    }
}

