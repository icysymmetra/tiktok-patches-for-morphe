/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TikTokPreferenceFragment.java
 */

package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.provider.DocumentsContract;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.widget.ListView;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabFragment;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabRuntime;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedNavigationPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;

@SuppressWarnings("deprecation")
public class TikTokPreferenceFragment extends AbstractPreferenceFragment {
    private static final int REQUEST_DOWNLOAD_PATH_FOLDER = 8841;
    private static final String ARG_SECTION = "morphe_settings_section";
    private static TikTokPreferenceFragment activeFragment;
    private static DownloadPathPreference pendingDownloadPathPreference;

    private enum Section {
        FEED_FILTER("Feed filter", "Ads, Shop, livestreams, and view limits."),
        FEED_NAVIGATION("Feed navigation", "Feed tabs, bottom tabs, and Tako AI."),
        INTERFACE("Interface", "Promotions, popups, and publish dates."),
        COMMENTS("Comments and translation", "Auto translate, quick reactions, and copy options."),
        DOWNLOADS("Downloads", "Path, watermark, and offline videos."),
        REGION("Bypass regional restriction", "SIM info, country, and operator."),
        BEHAVIOR("App behavior", "Sharing, playback, and gestures."),
        DIAGNOSTICS("Diagnostics", "Logging, crash capture, and report export.");

        final String title;
        final String description;

        Section(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    private static boolean isDarkModeEnabled(Context context) {
        final int currentNightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void openDownloadPathFolderPicker(DownloadPathPreference preference) {
        if (activeFragment == null) {
            app.morphe.extension.shared.Utils.showToastShort("Folder picker is not available");
            return;
        }

        pendingDownloadPathPreference = preference;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activeFragment.startActivityForResult(intent, REQUEST_DOWNLOAD_PATH_FOLDER);
        } catch (ActivityNotFoundException exception) {
            pendingDownloadPathPreference = null;
            app.morphe.extension.shared.Utils.showToastLong("Folder picker is not available on this device");
        }
    }

    @Override
    protected void syncSettingWithPreference(
            @NonNull Preference pref,
            @NonNull Setting setting,
            boolean applySettingToPreference
    ) {
        if (pref instanceof NumberInputPreference) {
            NumberInputPreference numberInputPreference = (NumberInputPreference) pref;
            if (applySettingToPreference) {
                numberInputPreference.setValue(setting.get().toString());
            } else {
                Setting.privateSetValueFromString(setting, numberInputPreference.getValue());
            }
        } else if (pref instanceof RangeValuePreference) {
            RangeValuePreference rangeValuePref = (RangeValuePreference) pref;
            if (applySettingToPreference) {
                rangeValuePref.setValue(setting.get().toString());
            } else {
                Setting.privateSetValueFromString(setting, rangeValuePref.getValue());
            }
        } else if (pref instanceof DownloadPathPreference) {
            DownloadPathPreference downloadPathPref = (DownloadPathPreference) pref;
            if (applySettingToPreference) {
                downloadPathPref.setValue(setting.get().toString());
            } else {
                Setting.privateSetValueFromString(setting, downloadPathPref.getValue());
            }
        } else if (pref instanceof TabSelectionPreference) {
            TabSelectionPreference tabSelectionPref = (TabSelectionPreference) pref;
            if (applySettingToPreference) {
                tabSelectionPref.setValue(setting.get().toString());
            } else {
                Setting.privateSetValueFromString(setting, tabSelectionPref.getValue());
            }
        } else if (pref instanceof LanguageSelectionPreference) {
            LanguageSelectionPreference languagePreference = (LanguageSelectionPreference) pref;
            if (applySettingToPreference) {
                languagePreference.setValue(setting.get().toString());
            } else {
                Setting.privateSetValueFromString(setting, languagePreference.getValue());
            }
        } else {
            super.syncSettingWithPreference(pref, setting, applySettingToPreference);
        }
    }

    @Override
    protected boolean prefIsSetToDefault(Preference pref, Setting<?> setting) {
        String defaultValue = setting.defaultValue.toString();
        if (pref instanceof NumberInputPreference) {
            return defaultValue.equals(((NumberInputPreference) pref).getValue());
        }
        if (pref instanceof RangeValuePreference) {
            return defaultValue.equals(((RangeValuePreference) pref).getValue());
        }
        if (pref instanceof DownloadPathPreference) {
            return defaultValue.equals(((DownloadPathPreference) pref).getValue());
        }
        if (pref instanceof TabSelectionPreference) {
            return defaultValue.equals(((TabSelectionPreference) pref).getValue());
        }
        if (pref instanceof LanguageSelectionPreference) {
            return defaultValue.equals(((LanguageSelectionPreference) pref).getValue());
        }

        return super.prefIsSetToDefault(pref, setting);
    }

    @Override
    protected void initialize() {
        final var context = getActivity();
        activeFragment = this;

        // Currently no resources can be compiled for TikTok (fails with aapt error).
        // So all TikTok Strings are hard coded in the extension.
        restartDialogTitle = "Restart required";
        restartDialogMessage = "Restart the app for this change to take effect.";
        restartDialogButtonText = "Restart";
        confirmDialogTitle = "Do you wish to proceed?";

        Utils.setIsDarkModeEnabled(isDarkModeEnabled(context));

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        Section section = getRequestedSection();
        if (section == null) {
            createMasterMenu(context, preferenceScreen);
        } else {
            createSectionMenu(context, preferenceScreen, section);
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            list.setBackgroundColor(SettingsUi.background());
            list.setCacheColorHint(SettingsUi.background());
            list.setDivider(new ColorDrawable(SettingsUi.divider()));
            list.setDividerHeight(1);
            list.setClipToPadding(false);
            list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        window.setStatusBarColor(SettingsUi.surface());
        window.setNavigationBarColor(SettingsUi.surface());

        View decor = window.getDecorView();
        int visibility = decor.getSystemUiVisibility();
        if (SettingsUi.isDarkMode()) {
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(visibility);
    }

    private Section getRequestedSection() {
        Bundle arguments = getArguments();
        if (arguments == null) {
            return null;
        }
        String name = arguments.getString(ARG_SECTION);
        if (name == null) {
            return null;
        }
        try {
            return Section.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void createMasterMenu(Context context, PreferenceScreen screen) {
        screen.addPreference(SettingsHeaderPreference.master(context, this::closeSettings));

        if (SettingsStatus.feedFilterEnabled) {
            addMenu(screen, Section.FEED_FILTER, SettingsMenuPreference.Icon.FILTER, countEnabled(
                    Settings.REMOVE_ADS.get(),
                    Settings.HIDE_SHOP.get(),
                    Settings.HIDE_LIVE.get(),
                    Settings.HIDE_STORY.get(),
                    Settings.HIDE_IMAGE.get()
            ));
        }
        if (SettingsStatus.feedNavigationEnabled) {
            addMenu(screen, Section.FEED_NAVIGATION, SettingsMenuPreference.Icon.TABS, countEnabled(
                    Settings.FEED_NAVIGATION.get(),
                    Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.get(),
                    Settings.BOTTOM_NAVIGATION.get(),
                    Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.get(),
                    Settings.HIDE_TAKO_AI.get()
            ));
        }
        if (SettingsStatus.captchaPopupSuppressionEnabled
                || SettingsStatus.promotionalBannersEnabled
                || SettingsStatus.alwaysShowPublishDateEnabled) {
            addMenu(screen, Section.INTERFACE, SettingsMenuPreference.Icon.LAYOUT, countEnabled(
                    SettingsStatus.promotionalBannersEnabled && Settings.HIDE_HOMEPAGE_COIN.get(),
                    SettingsStatus.captchaPopupSuppressionEnabled && Settings.HIDE_CAPTCHA_POPUPS.get(),
                    SettingsStatus.alwaysShowPublishDateEnabled && Settings.ALWAYS_SHOW_PUBLISH_DATE.get()
            ));
        }
        if (SettingsStatus.commentTranslationEnabled
                || SettingsStatus.hideCommentQuickReactionsEnabled
                || SettingsStatus.copyCommentsWithoutUsernameEnabled) {
            addMenu(screen, Section.COMMENTS, SettingsMenuPreference.Icon.COMMENTS, countEnabled(
                    SettingsStatus.commentTranslationEnabled && Settings.COMMENT_BATCH_TRANSLATION.get(),
                    SettingsStatus.hideCommentQuickReactionsEnabled && Settings.HIDE_COMMENT_QUICK_REACTIONS.get(),
                    SettingsStatus.copyCommentsWithoutUsernameEnabled && Settings.COPY_COMMENTS_WITHOUT_USERNAME.get()
            ));
        }
        if (SettingsStatus.downloadEnabled) {
            addMenu(screen, Section.DOWNLOADS, SettingsMenuPreference.Icon.DOWNLOADS, countEnabled(
                    Settings.DOWNLOAD_WATERMARK.get(),
                    Settings.CUSTOM_OFFLINE_VIDEOS.get()
            ));
        }
        if (SettingsStatus.simSpoofEnabled) {
            addMenu(screen, Section.REGION, SettingsMenuPreference.Icon.REGION, countEnabled(
                    Settings.SIM_SPOOF.get()
            ));
        }

        addMenu(screen, Section.BEHAVIOR, SettingsMenuPreference.Icon.BEHAVIOR, countBehaviorSettings());

        if (FeatureGateLabRuntime.isInstalled()) {
            screen.addPreference(new SettingsMenuPreference(
                    context,
                    "Feature Gate Lab",
                    "Search and override gate flags",
                    SettingsMenuPreference.Icon.LAB,
                    0,
                    preference -> {
                        FeatureGateLabFragment.open(getActivity());
                        return true;
                    }
            ));
        }

        if (SettingsStatus.diagnosticsEnabled) {
            addMenu(screen, Section.DIAGNOSTICS, SettingsMenuPreference.Icon.DIAGNOSTICS, countEnabled(
                    BaseSettings.DEBUG.get(),
                    BaseSettings.CAPTURE_JAVA_CRASHES.get()
            ));
        }

        screen.addPreference(new MorpheTikTokAboutPreference(context));
    }

    private void addMenu(
            PreferenceScreen screen,
            Section section,
            SettingsMenuPreference.Icon icon,
            int activeCount
    ) {
        screen.addPreference(new SettingsMenuPreference(
                getActivity(),
                section.title,
                section.description.substring(0, section.description.length() - 1),
                icon,
                activeCount,
                preference -> {
                    openSection(section);
                    return true;
                }
        ));
    }

    private void createSectionMenu(Context context, PreferenceScreen screen, Section section) {
        screen.addPreference(SettingsHeaderPreference.section(context, section.title, this::navigateBack));
        screen.addPreference(SettingsHeaderPreference.caption(context, section.description));

        PreferenceCategory category;
        switch (section) {
            case FEED_FILTER:
                category = new FeedFilterPreferenceCategory(context, screen);
                break;
            case FEED_NAVIGATION:
                category = new FeedNavigationPreferenceCategory(context, screen);
                break;
            case INTERFACE:
                category = new InterfacePreferenceCategory(context, screen);
                break;
            case COMMENTS:
                category = new CommentsPreferenceCategory(context, screen);
                break;
            case DOWNLOADS:
                category = new DownloadsPreferenceCategory(context, screen);
                break;
            case REGION:
                category = new SimSpoofPreferenceCategory(context, screen);
                break;
            case DIAGNOSTICS:
                category = new DebugPreferenceCategory(context, screen);
                break;
            case BEHAVIOR:
            default:
                category = new ExtensionPreferenceCategory(context, screen);
                break;
        }
        flattenCategory(screen, category);
    }

    private static void flattenCategory(PreferenceScreen screen, PreferenceCategory category) {
        if (category == null) {
            return;
        }
        int count = category.getPreferenceCount();
        Preference[] children = new Preference[count];
        for (int index = 0; index < count; index++) {
            children[index] = category.getPreference(index);
        }
        for (Preference child : children) {
            category.removePreference(child);
        }
        screen.removePreference(category);
        for (int index = 0; index < children.length; index++) {
            Preference child = children[index];
            child.setOrder(index);
            screen.addPreference(child);
        }
    }

    private void openSection(Section section) {
        FragmentManager manager = getFragmentManager();
        if (manager == null || getId() == 0) {
            Utils.showToastShort("Could not open settings section");
            return;
        }

        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString(ARG_SECTION, section.name());
        fragment.setArguments(arguments);

        manager.beginTransaction()
                .setCustomAnimations(
                        android.R.animator.fade_in,
                        android.R.animator.fade_out,
                        android.R.animator.fade_in,
                        android.R.animator.fade_out
                )
                .replace(getId(), fragment)
                .addToBackStack(section.name())
                .commit();
    }

    private void navigateBack() {
        FragmentManager manager = getFragmentManager();
        if (manager != null) {
            manager.popBackStack();
        }
    }

    private void closeSettings() {
        Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }

    private int countBehaviorSettings() {
        int count = countEnabled(
                BaseSettings.SANITIZE_SHARING_LINKS.get(),
                Settings.SHOW_SEEKBAR.get()
        );
        if (SettingsStatus.externalBrowserEnabled && Settings.OPEN_EXTERNAL_LINKS.get()) {
            count++;
        }
        if (SettingsStatus.stopVideoLoopingEnabled && Settings.STOP_VIDEO_LOOPING.get()) {
            count++;
        }
        if (SettingsStatus.resumeVideoAfterScrollEnabled && Settings.RESUME_VIDEO_AFTER_SCROLL.get()) {
            count++;
        }
        if (SettingsStatus.longPressSpeedLockEnabled && Settings.ENABLE_LONG_PRESS_SPEED_LOCK.get()) {
            count++;
        }
        if (SettingsStatus.disableLongPressQuickShareEnabled
                && Settings.DISABLE_LONG_PRESS_QUICK_SHARE.get()) {
            count++;
        }
        if (SettingsStatus.disableLongPressRepostEnabled
                && Settings.DISABLE_LONG_PRESS_REPOST.get()) {
            count++;
        }
        return count;
    }

    private static int countEnabled(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void onResume() {
        super.onResume();
        activeFragment = this;
    }

    @Override
    public void onDestroy() {
        if (activeFragment == this) {
            activeFragment = null;
            pendingDownloadPathPreference = null;
        }
        super.onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DOWNLOAD_PATH_FOLDER) {
            return;
        }

        DownloadPathPreference preference = pendingDownloadPathPreference;
        pendingDownloadPathPreference = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || preference == null) {
            return;
        }

        String relativePath = getRelativePrimaryStoragePath(data.getData());
        if (relativePath == null) {
            app.morphe.extension.shared.Utils.showToastLong("Only internal storage folders are supported");
            return;
        }

        preference.applyPickedPath(relativePath);
    }

    private static String getRelativePrimaryStoragePath(Uri uri) {
        try {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(uri);
            if (treeDocumentId == null) {
                return null;
            }

            String prefix = "primary:";
            if (!treeDocumentId.startsWith(prefix)) {
                return null;
            }

            return treeDocumentId.substring(prefix.length());
        } catch (Exception ignored) {
            return null;
        }
    }
}

