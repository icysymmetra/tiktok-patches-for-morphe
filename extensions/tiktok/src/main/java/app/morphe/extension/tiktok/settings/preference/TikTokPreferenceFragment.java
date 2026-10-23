/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TikTokPreferenceFragment.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TikTokPreferenceFragment.java
 */

package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.playback.FeedMute;
import app.morphe.extension.tiktok.settings.L10n;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.widget.ListView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.captions.CaptionTools;
import app.morphe.extension.tiktok.comment.CommentSearch;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabFragment;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabRuntime;
import app.morphe.extension.tiktok.settings.ProblemReport;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.share.ShareUrlSanitizer;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedNavigationPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.PrivacyPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SharePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;

@SuppressWarnings("deprecation")
public class TikTokPreferenceFragment extends AbstractPreferenceFragment {
    private static final String FEATURE_GATE_LAB_KEY = "action_feature_gate_lab";
    private static final String PAUSE_SUMMARY = "Pause Metra's switches after TikTok restarts. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay.";
    /** A switch from a patch left unticked in the Manager is on no page and in no index, and
     *  nothing else on the screen says so (#29 looked for Fill without its patch). */
    private static final String NO_MATCHES_SUMMARY = "Try a different word or clear the search. "
            + "Switches from patches you didn't tick in Morphe Manager aren't listed.";
    private static final int REQUEST_DOWNLOAD_PATH_FOLDER = 8841;
    private static final String ARG_SECTION = "morphe_settings_section";
    private static final String ARG_HUB = "morphe_settings_hub";
    private static final String ARG_SEARCH = "morphe_settings_search";
    private static final String ARG_TARGET_KEY = "morphe_settings_target_key";
    /** A checklist row's one box: the setting key of the member search landed on. */
    private static final String ARG_TARGET_MEMBER = "morphe_settings_target_member";
    /**
     * The row a restart was pressed with at the top of the screen. The page comes back scrolled
     * to it, quietly: unlike a search result's target it isn't flashed and changes nothing else.
     */
    private static final String ARG_RETURN_ROW = "morphe_settings_return_row";
    /** Between the page and the row in a return marker: "home#hushfeed_paused". */
    private static final char ROW_MARK = '#';
    /** The page names a restart's return marker carries ({@link #pageKey}). */
    public static final String PAGE_HOME = "home";
    static final String REPORT_PROBLEM_KEY = "action_report_problem";
    static final String SUPPORT_KEY = "action_support_hushfeed";
    static final String SUPPORT_URL = "https://ko-fi.com/P5P5YOUU7";
    static final String PAGE_SEARCH = "search";
    private static final String PAGE_HUB = "hub:";
    private static final String PAGE_SECTION = "section:";
    // Volatile: a file read or write's worker looks it up to report in the page open now.
    private static volatile TikTokPreferenceFragment activeFragment;
    /** Pinned to the top of this page while a restart is owed; off the page otherwise. */
    private RestartPendingPreference restartPending;
    /** Rows on this page whose sentence was swapped for "Restart pending.", to swap back. */
    private final java.util.Set<String> pendingSummaries = new java.util.HashSet<>();
    /** Summaries a parent's reason was appended to, so it can be taken off again. */
    private final java.util.Map<String, String> reasonSummaries = new java.util.HashMap<>();
    /**
     * Which folder setting the picker was opened for, by key rather than by the preference
     * itself. The picker is a separate activity, so this one is routinely destroyed behind it
     * and the object that was waiting no longer belongs to the screen that comes back.
     */
    private static String pendingDownloadPathKey;
    private static final String PENDING_DOWNLOAD_PATH_STATE = "morphe_pending_download_path";
    private static final String SEARCH_QUERY_STATE = "morphe_search_query";
    private SettingsListAdapter styledAdapter;
    /**
     * Set once this page has scrolled to where it was opened for. Back to a page another one
     * opened over brings this same instance back with a new view and no saved state, which
     * looks like an arrival; the page keeps where the reader left it instead.
     */
    private boolean arrived;
    private PreferenceScreen searchScreen;
    private List<SearchResult> searchIndex;
    private final List<Preference> searchRows = new ArrayList<>();
    private SettingsSearchInputPreference searchInput;
    private SettingsHeaderPreference masterHeader;
    /** The home page's Quick setups card, which a long press takes off the page. Null off the home page. */
    private QuickSetupsPreference quickSetupsRow;

    /**
     * Each section carries one sentence, used both as the subtitle on the home row and as the
     * caption at the top of its own page. There used to be two, and they disagreed: the
     * Downloads row promised quality and subtitles while its page said path and watermark.
     */
    private enum Section {
        FEED_FILTER("Feed controls", "Choose which posts appear in your feeds", SettingsMenuPreference.Icon.FILTER),
        FEED_NAVIGATION("Feed navigation", "Feed tabs, bottom tabs, and Tako AI", SettingsMenuPreference.Icon.TABS),
        INTERFACE("Interface", "Promotions, overlays, profile and inbox controls", SettingsMenuPreference.Icon.LAYOUT),
        PLAYBACK("Playback", "Quality, speed and auto-advance", SettingsMenuPreference.Icon.PLAYBACK),
        SCREEN_TIME("Screen time", "Daily budgets, reminders and the hold", SettingsMenuPreference.Icon.SCREEN_TIME),
        COMMENTS("Comments and translation", "Filters, translation, copy options and split view", SettingsMenuPreference.Icon.COMMENTS),
        DOWNLOADS("Downloads", "Quality, files, subtitles and hand-off", SettingsMenuPreference.Icon.DOWNLOADS),
        SHARE("Share sheet", "People, shortcuts and sending controls", SettingsMenuPreference.Icon.SHARE),
        INBOX("Inbox", "Choose which rows and controls appear", SettingsMenuPreference.Icon.INBOX),
        PRIVACY("Privacy", "Tracking, device access and links", SettingsMenuPreference.Icon.PRIVACY),
        REGION("Region spoof", "Change the region TikTok reads", SettingsMenuPreference.Icon.REGION),
        BEHAVIOR("App behavior", "Playback, gestures, privacy, screen time and system", SettingsMenuPreference.Icon.BEHAVIOR),
        DIAGNOSTICS("Diagnostics", "Logging, hook status and reports", SettingsMenuPreference.Icon.DIAGNOSTICS),
        BACKUP("Backup and restore", "Save, restore, reset and undo your settings", SettingsMenuPreference.Icon.BACKUP);

        final String title;
        final String description;
        final SettingsMenuPreference.Icon icon;

        Section(String title, String description, SettingsMenuPreference.Icon icon) {
            this.title = title;
            this.description = description;
            this.icon = icon;
        }

        /** Whether the bundle carries the patches behind this page. */
        boolean isAvailable() {
            switch (this) {
                case FEED_FILTER: return FeedFilterPreferenceCategory.isAvailable();
                case FEED_NAVIGATION: return FeedNavigationPreferenceCategory.isAvailable();
                case INTERFACE: return InterfacePreferenceCategory.isAvailable() || InboxPreferenceCategory.isAvailable();
                case PLAYBACK: return PlaybackPreferenceCategory.isAvailable();
                case SCREEN_TIME: return ScreenTimePreferenceCategory.isAvailable();
                case COMMENTS: return CommentsPreferenceCategory.isAvailable();
                case DOWNLOADS: return DownloadsPreferenceCategory.isAvailable();
                case SHARE: return SharePreferenceCategory.isAvailable();
                case INBOX: return InboxPreferenceCategory.isAvailable();
                case PRIVACY: return PrivacyPreferenceCategory.isAvailable();
                case REGION: return SimSpoofPreferenceCategory.isAvailable();
                case BEHAVIOR: return ExtensionPreferenceCategory.isAvailable()
                        || PlaybackPreferenceCategory.isAvailable() || PrivacyPreferenceCategory.isAvailable()
                        || ScreenTimePreferenceCategory.isAvailable();
                case DIAGNOSTICS: return DebugPreferenceCategory.isAvailable();
                case BACKUP:
                default: return true;
            }
        }
    }

    /** Metra's existing feature pages, plus the backup utility. Hidden aliases remain restorable. */
    private static Section[] navigationSections() {
        return new Section[]{Section.FEED_FILTER, Section.FEED_NAVIGATION, Section.INTERFACE,
                Section.COMMENTS, Section.DOWNLOADS, Section.REGION, Section.SHARE,
                Section.BEHAVIOR, Section.DIAGNOSTICS, Section.BACKUP};
    }

    private static Section canonicalSection(Section section) {
        switch (section) {
            case PLAYBACK:
            case PRIVACY:
            case SCREEN_TIME: return Section.BEHAVIOR;
            case INBOX: return Section.INTERFACE;
            default: return section;
        }
    }

    /**
     * The one page that isn't a section. Every section is a row on the home page (Matt,
     * 2026-10-12: at most one level below it), so the four groups that used to sit between them
     * are gone, and a restart marker naming one of those opens the home page.
     */
    private enum Hub {
        ABOUT("About Metra", "Version, changes and licenses");

        final String title;
        final String description;

        Hub(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    private static final class SearchResult implements app.morphe.extension.tiktok.settings.SearchMatch.Row {
        final Section section;
        final String key;
        final String title;
        final String summary;
        final String category;
        /** The setting key of one box in a checklist row, or null for a row of its own. */
        final String member;
        /** Folded once when the index is built: it never changes, and a query is typed a letter
         *  at a time over about 170 of these. */
        final String normalized;
        /** The title alone, folded the same way, so a hit there ranks above a description hit. */
        final String normalizedTitle;
        /** A plain switch, which the results flip where it stands instead of opening its page. */
        boolean flipsInPlace;

        SearchResult(Section section, String key, String title, String summary, String category) {
            this(section, key, title, summary, category, "");
        }

        /** {@code keywords} are searched but never shown: words a reader types for the row that
         *  its title and description don't use. */
        SearchResult(Section section, String key, String title, String summary, String category,
                String keywords) {
            this(section, key, null, title, summary, category,
                    title + " " + summary + " " + category + " " + keywords);
        }

        private SearchResult(Section section, String key, String member, String title,
                String summary, String category, String searched) {
            this.section = section;
            this.key = key;
            this.member = member;
            this.title = title;
            this.summary = summary;
            this.category = category;
            this.normalized = normalizeSearchText(searched);
            this.normalizedTitle = normalizeSearchText(title);
        }

        /**
         * One box in a checklist row. Its second line names the row it sits in, but the row's
         * title is not searched: "hide buttons" would otherwise list all seven boxes under the
         * row that already answers it.
         */
        static SearchResult member(Section section, String key, String member, String label,
                String rowTitle, String category) {
            return new SearchResult(section, key, member, label, rowTitle, category,
                    label + " " + category);
        }

        String displaySummary() {
            if (summary.isEmpty()) {
                return category;
            }
            return category + " · " + summary;
        }

        @Override public String searchedText() {
            return normalized;
        }

        @Override public String titleText() {
            return normalizedTitle;
        }
    }

    public static void openSeenVideoHistoryPicker() {
        if (activeFragment != null && activeFragment.isAdded()) {
            ImportSeenVideoHistoryPreference.pickFile(activeFragment);
        } else {
            SettingsActionBanner.showNotice(Utils.getContext(), L10n.t(
                    "Reopen Metra settings, then choose the file"));
        }
    }

    /** Import a font file, whose picker answers the page that asked. */
    public static void openImportedFontPicker() {
        if (activeFragment != null && activeFragment.isAdded()) {
            ImportedFontPreference.pickFile(activeFragment);
        } else {
            SettingsActionBanner.showNotice(Utils.getContext(), L10n.t(
                    "Reopen Metra settings, then choose the file"));
        }
    }

    /**
     * Owes a restart for a file the next start reads rather than a setting, the way turning
     * Hushfeed back on owes one for its marker file, and says so on the page open now.
     */
    static void noteFileRestartPending(String key) {
        AbstractPreferenceFragment.restartPending.add(key);
        TikTokPreferenceFragment open = active();
        if (open != null) open.refreshRestartPending();
    }

    /** Takes back a restart {@link #noteFileRestartPending} owed, once what needed it is undone. */
    static void noteFileRestartSettled(String key) {
        AbstractPreferenceFragment.restartPending.remove(key);
        TikTokPreferenceFragment open = active();
        if (open != null) open.refreshRestartPending();
    }

    /** Save or Restore seen history, whose picker answers the page that asked. */
    public static void openSeenHistoryFilePicker(int request) {
        if (activeFragment != null && activeFragment.isAdded()) {
            SeenHistoryFilePreference.pickFile(activeFragment, request);
        } else {
            SettingsActionBanner.showNotice(Utils.getContext(), L10n.t(
                    "Reopen Metra settings, then choose the file"));
        }
    }

    public static void openDownloadPathFolderPicker(DownloadPathPreference preference) {
        if (activeFragment == null) {
            app.morphe.extension.shared.Utils.showToastLong(L10n.t(
                    "This phone has no folder picker. Type the folder into the row instead."));
            return;
        }

        pendingDownloadPathKey = preference.getKey();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activeFragment.startActivityForResult(intent, REQUEST_DOWNLOAD_PATH_FOLDER);
        } catch (ActivityNotFoundException exception) {
            pendingDownloadPathKey = null;
            app.morphe.extension.shared.Utils.showToastLong(L10n.t(
                    "This phone has no folder picker. Type the folder into the row instead."));
        }
    }

    @Override
    protected void syncPreferenceWithStoredValue(@NonNull Preference pref,
                                                @NonNull Setting<?> setting,
                                                @NonNull SharedPreferences preferences) {
        if (pref instanceof NumberInputPreference) {
            NumberInputPreference numberPref = (NumberInputPreference) pref;
            numberPref.setValueWithoutPersisting(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof CreatorListPreference) {
            CreatorListPreference creatorPref = (CreatorListPreference) pref;
            creatorPref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof RangeValuePreference) {
            RangeValuePreference rangePref = (RangeValuePreference) pref;
            rangePref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof DownloadPathPreference) {
            DownloadPathPreference pathPref = (DownloadPathPreference) pref;
            pathPref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof TabSelectionPreference) {
            TabSelectionPreference tabsPref = (TabSelectionPreference) pref;
            tabsPref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else {
            super.syncPreferenceWithStoredValue(pref, setting, preferences);
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
                numberInputPreference.setValueWithoutPersisting(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, numberInputPreference.getValue());
            }
        } else if (pref instanceof CreatorListPreference) {
            CreatorListPreference creatorListPreference = (CreatorListPreference) pref;
            if (applySettingToPreference) {
                creatorListPreference.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, creatorListPreference.getValue());
            }
        } else if (pref instanceof RangeValuePreference) {
            RangeValuePreference rangeValuePref = (RangeValuePreference) pref;
            if (applySettingToPreference) {
                rangeValuePref.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, rangeValuePref.getValue());
            }
        } else if (pref instanceof DownloadPathPreference) {
            DownloadPathPreference downloadPathPref = (DownloadPathPreference) pref;
            if (applySettingToPreference) {
                downloadPathPref.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, downloadPathPref.getValue());
            }
        } else if (pref instanceof TabSelectionPreference) {
            TabSelectionPreference tabSelectionPref = (TabSelectionPreference) pref;
            if (applySettingToPreference) {
                tabSelectionPref.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, tabSelectionPref.getValue());
            }
        } else {
            super.syncSettingWithPreference(pref, setting, applySettingToPreference);
        }
        if (!applySettingToPreference
                && (setting == Settings.BLOCK_AUTHOR_BUTTON
                || setting == Settings.LOCAL_HIDE_BUTTON
                || setting == Settings.BLOCK_SOUND_BUTTON
                || setting == Settings.NOT_INTERESTED_BUTTON
                || setting == Settings.FEED_MUTE_BUTTON)) {
            BlockAuthorOverlay.refresh();
        }
        if (!applySettingToPreference && setting == Settings.FEED_MUTED) {
            FeedMute.refresh();
            BlockAuthorOverlay.refresh();
        }
        if (!applySettingToPreference && (setting == Settings.COMMENT_SEARCH || setting == Settings.COMMENT_EXPORT)) {
            CommentSearch.onSettingChanged();
        }
        if (!applySettingToPreference && setting == Settings.KEEP_CAPTIONS_CLEAR_DISPLAY) {
            CaptionTools.onSettingChanged();
        }
        if (!applySettingToPreference && (setting == Settings.SHOW_COMMENTER_HANDLE
                || setting == Settings.SHOW_COMMENTER_REGION || setting == Settings.COLOR_COMMENTER_NAMES
                || setting == Settings.REGION_AS_FLAG || setting == BaseSettings.PAUSED)) {
            app.morphe.extension.tiktok.comment.CommenterDetails.onSettingChanged();
        }
        if (!applySettingToPreference && (setting == Settings.FEED_DESCRIPTION_TEXT_SIZE
                || setting == Settings.FEED_AUTHOR_TEXT_SIZE || setting == BaseSettings.PAUSED)) {
            app.morphe.extension.tiktok.feed.FeedTextSize.onSettingChanged();
        }
        if (!applySettingToPreference && setting == BaseSettings.PAUSED) {
            refreshStatusCard();
        }
    }

    @Override
    protected boolean prefIsSetToDefault(Preference pref, Setting<?> setting) {
        String defaultValue = setting.defaultValue.toString();
        if (pref instanceof NumberInputPreference) {
            return defaultValue.equals(((NumberInputPreference) pref).getValue());
        }
        if (pref instanceof CreatorListPreference) {
            return defaultValue.equals(((CreatorListPreference) pref).getValue());
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
        return super.prefIsSetToDefault(pref, setting);
    }

    @Override
    protected boolean preferenceShowsSettingValue(@NonNull Preference pref,
                                                  @NonNull Setting<?> setting) {
        String expected = setting.savedValue() instanceof Enum<?>
                ? ((Enum<?>) setting.savedValue()).name() : String.valueOf(setting.savedValue());
        if (pref instanceof NumberInputPreference) {
            return expected.equals(((NumberInputPreference) pref).getValue());
        }
        if (pref instanceof CreatorListPreference) {
            return expected.equals(((CreatorListPreference) pref).getValue());
        }
        if (pref instanceof RangeValuePreference) {
            return expected.equals(((RangeValuePreference) pref).getValue());
        }
        if (pref instanceof DownloadPathPreference) {
            return expected.equals(((DownloadPathPreference) pref).getValue());
        }
        if (pref instanceof TabSelectionPreference) {
            return expected.equals(((TabSelectionPreference) pref).getValue());
        }
        return super.preferenceShowsSettingValue(pref, setting);
    }

    @Override protected CharSequence initializationErrorTitle(Context context) {
        return L10n.t(context, "Settings couldn't open");
    }

    @Override protected CharSequence initializationErrorSummary(Context context) {
        return L10n.t(context, "Try again, or go back to TikTok.");
    }

    @Override protected CharSequence initializationBackLabel(Context context) {
        return L10n.t(context, "Back");
    }

    @Override protected CharSequence initializationRetryLabel(Context context) {
        return L10n.t(context, "Retry");
    }

    /**
     * Draws Retry as the way forward and Back as the way out.
     *
     * <p>Both were plain rows in the same weight and colour, so the page that opens when settings
     * will not load offered two identical looking choices and no sense of which one to take.
     */
    @Override protected ErrorActionStyler errorActionStyler() {
        // SettingsListAdapter styles the rows again after this, so it applies the same helper.
        return SettingsUi::styleErrorAction;
    }

    @Override protected CharSequence preferenceChangeRecoveredMessage(Context context) {
        return L10n.t(context, "The setting couldn't finish updating. Its saved value is shown.");
    }

    @Override protected CharSequence preferenceChangeRecoveryFailedMessage(Context context) {
        return L10n.t(context,
                "Settings couldn't refresh completely. Reopen settings and try again.");
    }

    @Override
    protected void initialize() {
        final var context = getActivity();
        activeFragment = this;

        // Nothing sets one. The framework only raises that dialog for a setting carrying a
        // userDialogMessage, and no setting in this bundle has one, so this was a translated
        // string for a screen nobody could reach. The safety net Reset and Undo do want is its
        // own item on the roadmap.
        confirmDialogTitle = null;

        // The two sentences the shared library says on this bundle's behalf. It cannot reach a
        // translation table itself, so they are handed to it once, from here, where the
        // settings screen is being built and a context is at hand.
        savedMessage = L10n.t(context, "Saved. Restart TikTok to apply this.");
        AbstractPreferenceFragment.setRestartFeedbackPresenter((feedbackContext, ignored) ->
                SettingsActionBanner.showRestart(feedbackContext,
                        RestartPendingPreference.label(feedbackContext)));
        app.morphe.extension.shared.settings.preference.LogBufferManager.clearedMessage =
                L10n.t(context, "Diagnostic data cleared. Tap again to put it back.");
        app.morphe.extension.shared.settings.preference.LogBufferManager.nothingToClearMessage =
                L10n.t(context, "There is no diagnostic data to clear");
        app.morphe.extension.shared.settings.preference.LogBufferManager.restoredMessage =
                L10n.t(context, "Diagnostic data put back");
        app.morphe.extension.shared.settings.preference.LogBufferManager.nothingToRestoreMessage =
                L10n.t(context, "There is no diagnostic data to put back");
        app.morphe.extension.shared.settings.preference.LogBufferManager.restoreFailedMessage =
                L10n.t(context, "Couldn't put back the diagnostic data. Try again.");
        // The rest of what the shared export path says, on a German phone in German. Every one
        // of these reached the reader in English, branded for the library rather than the
        // bundle, and two of them carried an exception's text.
        app.morphe.extension.shared.settings.preference.LogBufferManager.nothingToExportMessage =
                L10n.t(context, "No matching diagnostics found");
        app.morphe.extension.shared.settings.preference.LogBufferManager.copiedMessage =
                L10n.t(context, "Diagnostic report copied to the clipboard");
        app.morphe.extension.shared.settings.preference.LogBufferManager.exportFailedMessage =
                L10n.t(context, "The diagnostic report couldn't be saved. Try again.");
        app.morphe.extension.shared.settings.preference.LogBufferManager.noContextMessage =
                L10n.t(context, "The diagnostic report couldn't be saved yet. Try again in a moment.");
        app.morphe.extension.shared.settings.preference.LogBufferManager.alreadySavingMessage =
                L10n.t(context, "A diagnostic report is already being saved");
        app.morphe.extension.shared.settings.preference.LogBufferManager.savedToMessage =
                L10n.t(context, "Full report saved to %1$s");
        app.morphe.extension.shared.settings.preference.LogBufferManager.couldNotStartMessage =
                L10n.t(context, "Couldn't start the report export. Try again in a moment.");
        // Four whole sentences rather than five fragments, so each one is a row a translator
        // can move the numbers around inside. The context is asked for when a line is written
        // rather than captured here: this writer is a static and outlives the screen.
        app.morphe.extension.shared.diagnostics.HookStatus.setLineWriter(
                (family, found, missing, truncated, firstMiss) -> {
                    Context lineContext = app.morphe.extension.shared.Utils.getContext();
                    if (firstMiss == null) {
                        return truncated
                                ? L10n.f(lineContext, "%1$s: %2$d found, %3$d missing, and more it "
                                        + "stopped counting", family, found, missing)
                                : L10n.f(lineContext, "%1$s: %2$d found, %3$d missing",
                                        family, found, missing);
                    }
                    return truncated
                            ? L10n.f(lineContext, "%1$s: %2$d found, %3$d missing, and more it "
                                    + "stopped counting. First missing: %4$s",
                                    family, found, missing, firstMiss)
                            : L10n.f(lineContext, "%1$s: %2$d found, %3$d missing. First missing: "
                                    + "%4$s", family, found, missing, firstMiss);
                });

        SettingsUi.syncDarkMode(context);

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        Section section = getRequestedSection();
        Hub hub = getRequestedHub();
        if (isSearchRequested()) {
            createSearchMenu(context, preferenceScreen);
        } else if (hub == Hub.ABOUT) {
            createAboutMenu(context, preferenceScreen);
        } else if (section == null) {
            createMasterMenu(context, preferenceScreen);
        } else {
            createSectionMenu(context, preferenceScreen, section);
        }
        restartPending = new RestartPendingPreference(context);
        refreshRestartPending();
    }

    @Override
    protected void onRestartPendingChanged() {
        refreshRestartPending();
    }

    /**
     * A choice row keeps its own summary, the chosen label with the restart note under it when
     * its setting needs one. The shared sync writes the bare label over that, which is how the
     * Proxy type and Store installer rows lost the note. A value the row has no label for still
     * takes the shared path, which shows the raw value.
     */
    @Override
    protected void updateListPreferenceSummary(android.preference.ListPreference list,
                                               Setting<?> setting) {
        if (list instanceof ChoicePreference
                && list.findIndexOfValue(setting.savedValue().toString()) >= 0) {
            ((ChoicePreference) list).showValue();
            return;
        }
        super.updateListPreferenceSummary(list, setting);
    }

    /**
     * Pins the restart row while a restart is owed and takes it away when nothing is, and lets
     * the rows that owe it say "Restart pending" in place of the sentence every restart-gated
     * row carries, so the reader can see which of their changes are still waiting.
     */
    /**
     * A row its parent has greyed says which switch would turn it on.
     *
     * <p>Four rows grey out when their parent is off and said nothing about it, so the row read
     * as broken and a screen reader announced only "dimmed". The parent's own title is read off
     * its row rather than kept in a second list, because both rows are always on the same page
     * and a title kept twice is a title that drifts.
     */
    @Override
    protected void updatePreferenceAvailability(Preference pref, Setting<?> setting) {
        super.updatePreferenceAvailability(pref, setting);
        Context context = getActivity();
        if (context == null || pref.getKey() == null) return;
        String key = pref.getKey();
        String appended = reasonSummaries.remove(key);
        if (appended != null) {
            CharSequence current = pref.getSummary();
            if (current != null && current.toString().endsWith(appended)) {
                pref.setSummary(current.toString().substring(0, current.length() - appended.length()));
            }
        }
        if (setting.isAvailable()) return;
        String parentTitle = null;
        Setting<?> named = null;
        for (Setting<?> parent : setting.getParentSettings()) {
            Preference row = findPreference(parent.key);
            CharSequence title = row == null ? null : row.getTitle();
            if (title != null && title.length() > 0) {
                parentTitle = title.toString();
                named = parent;
                break;
            }
        }
        if (parentTitle == null) return;
        // The YTDLnis rows wait on a text field holding one app's package, not on a switch, so
        // "Turn on" sent the reader looking for a toggle that isn't there.
        String note = named == Settings.EXTERNAL_DOWNLOADER_PACKAGE
                ? L10n.f(context, "Put %1$s in %2$s first.", Settings.YTDLNIS_PACKAGE_NAME, parentTitle)
                : L10n.f(context, "Turn on %1$s first.", parentTitle);
        CharSequence summary = pref.getSummary();
        String body = summary == null ? "" : summary.toString();
        // A number row's summary is lines ("0 to 1,000 videos", "Current: Off"), and a space ran
        // the note into the last of them; it gets a line of its own there, and follows the
        // sentence on a prose row. A choice row's summary is its value, "1.5x" or "Video", which
        // read as "Video Turn on ... first." run on, so it gets its own line there too.
        boolean ownLine = body.contains("\n") || pref instanceof android.preference.ListPreference;
        String reason = body.isEmpty() ? note : (ownLine ? "\n" : " ") + note;
        if (body.endsWith(reason)) return;
        pref.setSummary(body + reason);
        reasonSummaries.put(key, reason);
    }

    private void refreshRestartPending() {
        PreferenceScreen screen = getPreferenceScreen();
        Context context = getActivity();
        if (screen == null || restartPending == null || context == null) return;
        boolean owed = !restartPendingKeys().isEmpty();
        boolean shown = screen.findPreference(RestartPendingPreference.KEY) != null;
        // Only touched while something is owed or shown. removePreference notifies the
        // hierarchy whether or not the row was there, which rebuilt every page's list once
        // more at open and moved twenty captures by a switch frame. While owed, the row is
        // taken out and put back so the list rebinds the count.
        if (shown) screen.removePreference(restartPending);
        if (owed) screen.addPreference(restartPending);
        if (masterHeader != null) masterHeader.refreshStatus();

        String generic = L10n.t(context, TogglePreference.RESTART_SENTENCE);
        // The sentence it replaces ends in a full stop and sits inside prose ("... Restart
        // pending. If the old layout is still there, unfold again."), so this keeps one.
        String pending = L10n.t(context, "Restart pending") + ".";
        java.util.List<String> owedKeys = restartPendingKeys();
        for (String key : owedKeys) {
            Preference row = findPreference(key);
            CharSequence summary = row == null ? null : row.getSummary();
            if (summary == null || !summary.toString().contains(generic)) continue;
            row.setSummary(summary.toString().replace(generic, pending));
            pendingSummaries.add(key);
        }
        // A switch flipped back owes nothing, so its row says what it said before.
        for (String key : new java.util.ArrayList<>(pendingSummaries)) {
            if (owedKeys.contains(key)) continue;
            pendingSummaries.remove(key);
            Preference row = findPreference(key);
            CharSequence summary = row == null ? null : row.getSummary();
            if (summary == null || !summary.toString().contains(pending)) continue;
            row.setSummary(summary.toString().replace(pending, generic));
        }
    }

    private static java.util.List<String> restartPendingKeys() {
        synchronized (AbstractPreferenceFragment.restartPending) {
            return new java.util.ArrayList<>(AbstractPreferenceFragment.restartPending);
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            SettingsUi.styleScrollableList(list);
            list.setBackgroundColor(SettingsUi.background());
            list.setCacheColorHint(SettingsUi.background());
            list.setDivider(null);
            list.setDividerHeight(0);
            int side = 0;
            list.setPadding(SettingsUi.dp(getActivity(), side), 0,
                    SettingsUi.dp(getActivity(), side), SettingsUi.dp(getActivity(), 24));
            list.setClipToPadding(false);
            list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        // setStatusBarColor and setNavigationBarColor were here. Both do nothing from target 35,
        // which TikTok is well past, so the bars are painted by the root view's own background
        // reaching behind them instead. The icon flags below still work.
        view.setBackgroundColor(SettingsUi.background());

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

    @SuppressWarnings("deprecation")
    @Override public android.animation.Animator onCreateAnimator(
            int transit, boolean enter, int nextAnim) {
        if ((transit == 0 && nextAnim == 0) || getActivity() == null) {
            return super.onCreateAnimator(transit, enter, nextAnim);
        }
        float dp24 = SettingsUi.dp(getActivity(), 24);
        boolean isRtl = getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
        float endEdge = isRtl ? -dp24 : dp24;
        boolean opening = transit == android.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN;
        float fromX, toX;
        float fromAlpha, toAlpha;
        if (opening && enter) {
            fromX = endEdge; toX = 0f; fromAlpha = 0f; toAlpha = 1f;
        } else if (opening) {
            fromX = 0f; toX = -endEdge * 0.3f; fromAlpha = 1f; toAlpha = 0f;
        } else if (enter) {
            fromX = -endEdge * 0.3f; toX = 0f; fromAlpha = 0f; toAlpha = 1f;
        } else {
            fromX = 0f; toX = endEdge; fromAlpha = 1f; toAlpha = 0f;
        }
        ObjectAnimator slide = ObjectAnimator.ofFloat(null, "translationX", fromX, toX);
        ObjectAnimator fade = ObjectAnimator.ofFloat(null, "alpha", fromAlpha, toAlpha);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(slide, fade);
        set.setDuration(220);
        set.setInterpolator(new android.view.animation.DecelerateInterpolator());
        return set;
    }

    @Override public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ImportSeenVideoHistoryPreference.savePickerState(outState);
        SeenHistoryFilePreference.savePickerState(outState);
        outState.putString(PENDING_DOWNLOAD_PATH_STATE, pendingDownloadPathKey);
        if (searchInput != null) {
            String query = searchInput.getQuery();
            if (!query.isEmpty()) outState.putString(SEARCH_QUERY_STATE, query);
        }
    }

    @Override public void onActivityCreated(Bundle state) {
        super.onActivityCreated(state);
        ImportSeenVideoHistoryPreference.restorePickerState(state);
        SeenHistoryFilePreference.restorePickerState(state);
        if (pendingDownloadPathKey == null && state != null) {
            pendingDownloadPathKey = state.getString(PENDING_DOWNLOAD_PATH_STATE);
        }
        if (state != null && searchInput != null) {
            String savedQuery = state.getString(SEARCH_QUERY_STATE);
            if (savedQuery != null && !savedQuery.isEmpty()) {
                searchInput.setQuery(savedQuery);
            }
        }
        ListView list = getView().findViewById(android.R.id.list);
        if (list != null && list.getAdapter() != null) {
            styledAdapter = new SettingsListAdapter(list.getAdapter());
            list.setAdapter(styledAdapter);
            String targetKey = getTargetKey();
            String returnRow = getArguments() == null ? null : getArguments().getString(ARG_RETURN_ROW);
            boolean returning = arrived;
            arrived = true;
            if (!returning && targetKey == null && returnRow != null && state == null) {
                // Only on arrival: a recreated page keeps the scroll the list saved for it.
                scrollToPreference(list, returnRow, false);
                list.post(() -> scrollToPreference(list, returnRow, false));
            }
            if (!returning && targetKey != null) {
                scrollToPreference(list, targetKey);
                list.post(() -> scrollToPreference(list, targetKey));
                // Only on arrival. A recreated page brings back the dialog it had open, with the
                // boxes as they were, and one closed before the recreation stays closed.
                String member = getArguments().getString(ARG_TARGET_MEMBER);
                Preference row = findPreference(targetKey);
                if (state == null && member != null && row instanceof SwitchListPreference) {
                    list.post(() -> {
                        if (isAdded()) ((SwitchListPreference) row).showChoicesAt(member);
                    });
                }
            }
        }
    }

    @Override public void onDestroyView() {
        // The search page raises the keyboard for its field, and popping the page left it up
        // over whatever page came next until Back was pressed once more. The system Back closes
        // it because the field still has focus; taking it down with the view covers the header's
        // Back and a tapped result alike, since both replace this view.
        hideKeyboard();
        if (styledAdapter != null) {
            styledAdapter.dispose();
            styledAdapter = null;
        }
        super.onDestroyView();
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
            return canonicalSection("SHARE_SHEET".equals(name) ? Section.SHARE : Section.valueOf(name));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isSearchRequested() {
        Bundle arguments = getArguments();
        return arguments != null && arguments.getBoolean(ARG_SEARCH, false);
    }

    private Hub getRequestedHub() {
        Bundle arguments = getArguments();
        String name = arguments == null ? null : arguments.getString(ARG_HUB);
        if (name == null) return null;
        try {
            return Hub.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** This page, as the restart's return marker names it, so the next start can open it again. */
    String pageKey() {
        if (isSearchRequested()) return PAGE_SEARCH;
        Hub hub = getRequestedHub();
        if (hub != null) return PAGE_HUB + hub.name();
        Section section = getRequestedSection();
        return section != null ? PAGE_SECTION + section.name() : PAGE_HOME;
    }

    /**
     * What a restart's return marker records: this page, and the row at the top of the screen
     * when the page was scrolled, so a restart from far down the home page comes back there.
     */
    String returnMarker() {
        String row = rowInView();
        return row == null ? pageKey() : pageKey() + ROW_MARK + row;
    }

    /** The first row whole on screen, or null while the page is at its top. */
    private String rowInView() {
        View view = getView();
        ListView list = view == null ? null : view.findViewById(android.R.id.list);
        if (list == null || list.getAdapter() == null || list.getFirstVisiblePosition() == 0) return null;
        int first = list.getFirstVisiblePosition();
        for (int index = 0; index < list.getChildCount(); index++) {
            Object item = list.getAdapter().getItem(first + index);
            if (list.getChildAt(index).getTop() < 0 || !(item instanceof Preference)) continue;
            String key = ((Preference) item).getKey();
            if (key != null && !key.isEmpty() && key.indexOf(ROW_MARK) < 0) return key;
        }
        return null;
    }

    /**
     * The settings screen's first page for a restart's return marker: the home page, scrolled to
     * the marker's row when the restart came from home.
     */
    public static TikTokPreferenceFragment home(String marker) {
        TikTokPreferenceFragment home = new TikTokPreferenceFragment();
        int mark = marker == null ? -1 : marker.indexOf(ROW_MARK);
        if (mark > 0 && PAGE_HOME.equals(marker.substring(0, mark)) && mark < marker.length() - 1) {
            Bundle arguments = new Bundle();
            arguments.putString(ARG_RETURN_ROW, marker.substring(mark + 1));
            home.setArguments(arguments);
        }
        return home;
    }

    /**
     * The page a return marker names, built to open over the home page, or null for the home page
     * itself and for a page this build doesn't have. A marker from an older build has no row.
     */
    public static TikTokPreferenceFragment forPage(String page) {
        if (page == null) return null;
        Bundle arguments = new Bundle();
        int mark = page.indexOf(ROW_MARK);
        if (mark >= 0) {
            if (mark < page.length() - 1) arguments.putString(ARG_RETURN_ROW, page.substring(mark + 1));
            page = page.substring(0, mark);
        }
        try {
            if (PAGE_SEARCH.equals(page)) {
                arguments.putBoolean(ARG_SEARCH, true);
            } else if (page.startsWith(PAGE_HUB)) {
                arguments.putString(ARG_HUB, Hub.valueOf(page.substring(PAGE_HUB.length())).name());
            } else if (page.startsWith(PAGE_SECTION)) {
                arguments.putString(ARG_SECTION,
                        canonicalSection("SHARE_SHEET".equals(page.substring(PAGE_SECTION.length()))
                                ? Section.SHARE : Section.valueOf(page.substring(PAGE_SECTION.length()))).name());
            } else {
                return null;
            }
        } catch (IllegalArgumentException gone) {
            return null;
        }
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        fragment.setArguments(arguments);
        return fragment;
    }

    private String getTargetKey() {
        Bundle arguments = getArguments();
        if (arguments == null) {
            return null;
        }
        String key = arguments.getString(ARG_TARGET_KEY);
        return key == null || key.isEmpty() ? null : key;
    }

    private void createSearchMenu(Context context, PreferenceScreen screen) {
        searchScreen = screen;
        screen.addPreference(SettingsHeaderPreference.section(context, "Search settings", this::navigateBack));
        screen.addPreference(SettingsHeaderPreference.caption(context,
                "Search translated titles and descriptions, then open the original setting."));
        searchInput = new SettingsSearchInputPreference(context, this::updateSearchResults);
        screen.addPreference(searchInput);
        searchIndex = buildSearchIndex(context, FeatureGateLabRuntime.isInstalled());
        updateSearchResults("");
    }

    private void updateSearchResults(String query) {
        if (searchScreen == null) {
            return;
        }
        for (Preference row : searchRows) {
            searchScreen.removePreference(row);
        }
        searchRows.clear();

        // Folding happens before the empty check: a query of nothing but accent marks is not
        // empty as typed but folds away to nothing, and every setting contains "".
        String typed = query == null ? "" : query.trim();
        String normalizedQuery = normalizeSearchText(typed);
        if (normalizedQuery.isEmpty()) {
            if (typed.isEmpty()) {
                if (searchInput != null) searchInput.hideResultCount();
                addSearchState("Start typing", "Search by name, description or category.");
            } else {
                // "???" folds to nothing as well. Start typing, under a box with text in it,
                // read as if the search had ignored it.
                if (searchInput != null) searchInput.showResultCount(0);
                addSearchState("No matching settings", NO_MATCHES_SUMMARY);
            }
            return;
        }

        // Whole-word hits first, title hits ahead of description hits: SearchMatch has the why.
        List<SearchResult> matches = app.morphe.extension.tiktok.settings.SearchMatch.rank(searchIndex, typed);
        if (searchInput != null) searchInput.showResultCount(matches.size());
        if (matches.isEmpty()) {
            addSearchState("No matching settings", NO_MATCHES_SUMMARY);
            return;
        }

        int order = 0;
        java.util.Set<String> flipped = new java.util.HashSet<>();
        for (SearchResult result : matches) {
            // A plain switch is flipped right here, the way it is on its page, and the page's
            // change handling does the rest: the restart row, availability, the saved value.
            // One row per setting, so two pages binding the same switch can't disagree.
            Setting<?> setting = result.flipsInPlace ? Setting.getSettingFromPath(result.key) : null;
            if (setting instanceof app.morphe.extension.shared.settings.BooleanSetting
                    && setting.isAvailable() && flipped.add(setting.key)) {
                SearchTogglePreference toggle = new SearchTogglePreference(getActivity(), result.title,
                        result.displaySummary(), (app.morphe.extension.shared.settings.BooleanSetting) setting);
                toggle.setOrder(order++);
                searchRows.add(toggle);
                searchScreen.addPreference(toggle);
                continue;
            }
            Preference row = new Preference(getActivity());
            row.setTitle(result.title);
            row.setSummary(result.displaySummary());
            row.setOrder(order++);
            row.setOnPreferenceClickListener(preference -> {
                if (FEATURE_GATE_LAB_KEY.equals(result.key)) {
                    FeatureGateLabFragment.open(getActivity());
                } else if (LicensesPreference.KEY.equals(result.key)) {
                    LicensesPreference.show(getActivity());
                } else if (MorpheTikTokAboutPreference.KEY.equals(result.key)) {
                    Utils.openLink(MorpheTikTokAboutPreference.SOURCE_URL);
                } else if (BaseSettings.PAUSED.key.equals(result.key)) {
                    // The home page, scrolled to the row, the way a section opens for its rows.
                    openSection(null, result.key, null);
                } else if (BuildDetailsPreference.KEY.equals(result.key)
                        || ReleaseNotes.KEY.equals(result.key)) {
                    openHub(Hub.ABOUT, result.key);
                } else {
                    openSection(result.section, result.key, result.member);
                }
                return true;
            });
            searchRows.add(row);
            searchScreen.addPreference(row);
        }
    }

    private void addSearchState(String title, String summary) {
        Preference state = new Preference(getActivity());
        state.setTitle(L10n.t(getActivity(), title));
        state.setSummary(L10n.t(getActivity(), summary));
        state.setSelectable(false);
        state.setOrder(0);
        searchRows.add(state);
        searchScreen.addPreference(state);
    }

    /**
     * The settings one section binds, worked out once and remembered.
     *
     * <p>Building the page is how this knows what is on it, and building every page each time
     * Quick setups runs is about 184 preferences of work. The pages do not gain or lose rows
     * while the screen is open: what changes is the values, and those are read again every time.
     */
    private List<Setting<?>> settingsOn(Context context, Section section) {
        List<Setting<?>> known = sectionSettings.get(section);
        if (known != null) {
            return known;
        }
        List<Setting<?>> found = new ArrayList<>();
        PreferenceScreen scratch = getPreferenceManager().createPreferenceScreen(context);
        PreferenceCategory category = createCategory(context, scratch, section);
        if (category != null) {
            collectSettings(category, found, new java.util.HashSet<>());
            scratch.removePreference(category);
        }
        sectionSettings.put(section, found);
        return found;
    }

    /**
     * Every setting on Hushfeed's pages that this build can reach, for Quick setups, which
     * changes only what a reader could find and change back by hand.
     */
    private java.util.Collection<Setting<?>> settingsOnPages() {
        java.util.Set<Setting<?>> all = new java.util.LinkedHashSet<>();
        Context context = getActivity();
        if (context == null) return all;
        for (Section section : navigationSections()) {
            try {
                for (Setting<?> setting : settingsOn(context, section)) {
                    if (setting.isAvailable()) all.add(setting);
                }
            } catch (RuntimeException unreadable) {
                app.morphe.extension.shared.Logger.printException(
                        () -> "Couldn't read the settings on " + section + " for Quick setups", unreadable);
            }
        }
        return all;
    }

    private static void collectSettings(PreferenceGroup group, List<Setting<?>> found,
            java.util.Set<String> seen) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);
            if (preference instanceof PreferenceGroup) {
                collectSettings((PreferenceGroup) preference, found, seen);
                continue;
            }
            // A checklist row stands for several switches and has no setting of its own.
            if (preference instanceof SwitchListPreference) {
                for (Setting<?> setting : ((SwitchListPreference) preference).settings()) {
                    if (seen.add(setting.key)) found.add(setting);
                }
                continue;
            }
            if (!preference.hasKey()) {
                continue;
            }
            Setting<?> setting = Setting.getSettingFromPath(preference.getKey());
            // A row with no setting behind it is an action. Two rows can share one setting, and
            // a page can bind the same row twice.
            if (setting == null || !seen.add(setting.key)) {
                continue;
            }
            found.add(setting);
        }
    }

    /** Set by tests, which see Utils' empty release version and so never a pending What's new. */
    private static volatile String releaseVersionForTests;

    public static void setReleaseVersionForTests(String version) {
        releaseVersionForTests = version;
    }

    private static String releaseVersion() {
        String forTests = releaseVersionForTests;
        return forTests != null ? forTests : Utils.getPatchesReleaseVersion();
    }

    private List<SearchResult> buildSearchIndex(Context context, boolean featureGateLabInstalled) {
        List<SearchResult> results = new ArrayList<>();
        PreferenceScreen scratch = getPreferenceManager().createPreferenceScreen(context);
        for (Section section : navigationSections()) {
            // Back up, Restore, Reset and Undo are added straight to their page rather than
            // through a category, so walking categories alone never saw them: searching
            // "backup" or "restore" answered "No matching settings" for four rows one page away.
            if (section == Section.BACKUP) {
                PreferenceScreen backupRows = getPreferenceManager().createPreferenceScreen(context);
                SettingsBackupPreference.addTo(this, backupRows);
                indexRows(results, backupRows, section, L10n.t(context, section.title));
                continue;
            }
            PreferenceCategory category = createCategory(context, scratch, section);
            if (category == null) {
                continue;
            }
            String categoryTitle = String.valueOf(category.getTitle());
            indexRows(results, category, section, categoryTitle);
            scratch.removePreference(category);
        }
        // The rows below sit on the home page and the About Hushfeed page, outside the section
        // categories walked above, so each is indexed by hand. Their category line names where
        // a reader finds them, the home heading or the page; it said "Settings" for all of
        // them, which is no page.
        String privacyAndApp = L10n.t(context, "Privacy and app");
        String hushfeed = L10n.t(context, "Metra");
        String about = L10n.t(context, Hub.ABOUT.title);
        // Without an explicit entry, the Lab's title and summary returned zero results on a
        // patched phone even though the row was visible.
        if (featureGateLabInstalled) {
            results.add(new SearchResult(
                    null,
                    FEATURE_GATE_LAB_KEY,
                    L10n.t(context, "Feature Gate Lab"),
                    L10n.t(context, "Advanced. Find and force the hidden switches TikTok uses "
                            + "to test features."),
                    privacyAndApp,
                    "override gates"
            ));
        }
        // Pause Hushfeed is what a reader asking whether a problem is Hushfeed's searches for.
        results.add(new SearchResult(
                null,
                BaseSettings.PAUSED.key,
                L10n.t(context, "Pause Metra"),
                L10n.t(context, PAUSE_SUMMARY),
                hushfeed
        ));
        // Its summary carries the bundle version, which is what a reporter searches for.
        results.add(new SearchResult(
                null,
                MorpheTikTokAboutPreference.KEY,
                "Metra",
                MorpheTikTokAboutPreference.currentSummary(context).toString(),
                about
        ));
        // Searching "build" or "patches" found nothing, and the empty state then blamed patches
        // left unticked in Morphe Manager, while the row that lists them sat on About Hushfeed.
        results.add(new SearchResult(
                null,
                BuildDetailsPreference.KEY,
                L10n.t(context, "Build details"),
                L10n.t(context, BuildDetailsPreference.SUMMARY),
                about,
                "version"
        ));
        // What's new is on About Hushfeed only until its notes are read, and so is its result.
        String releaseVersion = releaseVersion();
        if (ReleaseNotes.pending(context, releaseVersion)) {
            results.add(new SearchResult(
                    null,
                    ReleaseNotes.KEY,
                    L10n.t(context, "What's new"),
                    L10n.f(context, "Changes in Metra %1$s", ReleaseNotes.rowVersion(context, releaseVersion)),
                    about,
                    "release notes changelog"
            ));
        }
        // Somebody looking for "licence" or "notice" is looking for exactly one thing.
        results.add(new SearchResult(
                null,
                LicensesPreference.KEY,
                LicensesPreference.title(context),
                LicensesPreference.summary(context),
                about
        ));
        return results;
    }

    private void indexRows(List<SearchResult> results, PreferenceGroup group, Section section,
            String categoryTitle) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);
            if (!preference.hasKey() || !preference.isSelectable()) {
                continue;
            }
            // A row greyed until its parent switch is on is still found: its page shows it with
            // the switch to turn on first, where leaving it out sent the reader to an empty
            // result that blamed patches not ticked in Morphe Manager.
            CharSequence title = preference.getTitle();
            if (title == null || title.length() == 0) {
                continue;
            }
            CharSequence summary = preference.getSummary();
            SearchResult result = new SearchResult(
                    section,
                    preference.getKey(),
                    title.toString(),
                    summary == null ? "" : summary.toString(),
                    categoryTitle,
                    searchKeywords(preference.getContext(), preference.getKey())
            );
            result.flipsInPlace = flipsInPlace(preference);
            results.add(result);
            // The row's summary names only the boxes that are ticked, so "counts" found nothing
            // while Counts under the buttons sat unticked inside it. Every box is a result of
            // its own, in the row's place, and opening one opens the row's dialog on that box.
            if (preference instanceof SwitchListPreference) {
                for (SwitchListPreference.Item item : ((SwitchListPreference) preference).items()) {
                    results.add(SearchResult.member(section, preference.getKey(), item.setting.key,
                            item.label, title.toString(), categoryTitle));
                }
            }
        }
    }

    /**
     * Whether a found row can be flipped in the results. Only a plain switch: one with a handler
     * of its own (a locked day's refusal, Ghost mode's status line, a background job to stop) or
     * a parent switch it waits on still opens its page, where that logic lives.
     */
    static boolean flipsInPlace(Preference preference) {
        return preference.getClass() == TogglePreference.class
                && preference.getOnPreferenceChangeListener() == null
                && preference.getDependency() == null
                && Setting.getSettingFromPath(preference.getKey())
                        instanceof app.morphe.extension.shared.settings.BooleanSetting;
    }

    /** Bug report templates ask for "debug logs", and "debug" found nothing: Log diagnostics never
     *  says the word. English stays searchable beside the translation, since that's the word
     *  the templates and guides use. */
    static final String LOG_DIAGNOSTICS_KEYWORDS = "debug debugging logs logging";

    private static String searchKeywords(Context context, String key) {
        if (!BaseSettings.DEBUG.key.equals(key)) return "";
        return LOG_DIAGNOSTICS_KEYWORDS + " " + L10n.t(context, LOG_DIAGNOSTICS_KEYWORDS);
    }

    private static String normalizeSearchText(String value) {
        return app.morphe.extension.tiktok.settings.SearchText.normalize(value);
    }

    private static PreferenceCategory createCategory(
            Context context,
            PreferenceScreen screen,
            Section section
    ) {
        switch (section) {
            case FEED_FILTER:
                return new FeedFilterPreferenceCategory(context, screen);
            case FEED_NAVIGATION:
                return new FeedNavigationPreferenceCategory(context, screen);
            case INTERFACE:
                return mergedCategory(context, screen, "Interface", false);
            case COMMENTS:
                return new CommentsPreferenceCategory(context, screen);
            case DOWNLOADS:
                return new DownloadsPreferenceCategory(context, screen);
            case PLAYBACK:
                return new PlaybackPreferenceCategory(context, screen);
            case SCREEN_TIME:
                return new ScreenTimePreferenceCategory(context, screen);
            case BACKUP:
                // Its four rows need the fragment, so createSectionMenu adds them itself.
                return null;
            case INBOX:
                return new InboxPreferenceCategory(context, screen);
            case SHARE:
                return new SharePreferenceCategory(context, screen);
            case REGION:
                return new SimSpoofPreferenceCategory(context, screen);
            case PRIVACY:
                return new PrivacyPreferenceCategory(context, screen);
            case DIAGNOSTICS:
                return new DebugPreferenceCategory(context, screen);
            case BEHAVIOR:
            default:
                return mergedCategory(context, screen, "App behavior", true);
        }
    }

    /** One builder feeds display, search, reports and settings imports, so all see the same rows. */
    private static PreferenceCategory mergedCategory(Context context, PreferenceScreen screen,
            String title, boolean appBehavior) {
        PreferenceCategory combined = new PreferenceCategory(context);
        combined.setTitle(L10n.t(context, title));
        screen.addPreference(combined);
        PreferenceScreen staging = screen.getPreferenceManager().createPreferenceScreen(context);
        PreferenceCategory[] categories = appBehavior
                ? new PreferenceCategory[]{new ExtensionPreferenceCategory(context, staging),
                    new PlaybackPreferenceCategory(context, staging), new PrivacyPreferenceCategory(context, staging),
                    new ScreenTimePreferenceCategory(context, staging)}
                : new PreferenceCategory[]{new InterfacePreferenceCategory(context, staging),
                    new InboxPreferenceCategory(context, staging)};
        for (PreferenceCategory category : categories) {
            if (category.getPreferenceCount() == 0) continue;
            Preference heading = new SectionHeadingPreference(context, String.valueOf(category.getTitle()));
            heading.setOrder(combined.getPreferenceCount());
            combined.addPreference(heading);
            while (category.getPreferenceCount() > 0) {
                Preference child = category.getPreference(0);
                category.removePreference(child);
                child.setOrder(combined.getPreferenceCount());
                combined.addPreference(child);
            }
            staging.removePreference(category);
        }
        return combined;
    }

    /** Scrolls this page to one of its rows and flashes it, as a search result arrives. */
    private void showRow(String key) {
        View view = getView();
        scrollToPreference(view == null ? null : view.findViewById(android.R.id.list), key);
    }

    private static void scrollToPreference(ListView list, String key) {
        scrollToPreference(list, key, true);
    }

    /** A search result's row is flashed; a restart's return row comes back as it was left. */
    private static void scrollToPreference(ListView list, String key, boolean highlight) {
        if (list == null || list.getAdapter() == null) {
            return;
        }
        int top = highlight ? SettingsUi.dp(list.getContext(), 24) : 0;
        for (int position = 0; position < list.getAdapter().getCount(); position++) {
            Object item = list.getAdapter().getItem(position);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                list.setSelectionFromTop(position, top);
                if (highlight) highlightRow(list, position);
                return;
            }
        }
    }

    static void highlightRow(ListView list, int position) {
        list.post(() -> {
            int first = list.getFirstVisiblePosition();
            int index = position - first;
            if (index < 0 || index >= list.getChildCount()) return;
            android.view.View row = list.getChildAt(index);
            if (row == null) return;
            float scale = android.provider.Settings.Global.getFloat(
                    list.getContext().getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (scale > 0f) {
                int highlight = SettingsUi.rippleTint();
                android.graphics.drawable.ColorDrawable flash =
                        new android.graphics.drawable.ColorDrawable(highlight);
                row.setForeground(flash);
                row.postDelayed(() -> {
                    android.animation.ObjectAnimator fade = android.animation.ObjectAnimator
                            .ofInt(flash, "alpha", 0x26, 0);
                    fade.setDuration(600);
                    fade.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            row.setForeground(null);
                        }
                    });
                    fade.start();
                }, 100);
            }
            row.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        });
    }

    /**
     * Metra's feature menus come first. Presets, status and recovery stay in the utility
     * section below them, reachable directly or through the header's status action.
     */
    private void createMasterMenu(Context context, PreferenceScreen screen) {
        // A home page aimed at a row was opened over the search results, so its Back goes back
        // to them. Only the home page settings opened on closes settings.
        Bundle arguments = getArguments();
        boolean overResults = arguments != null && arguments.getString(ARG_TARGET_KEY) != null;
        masterHeader = SettingsHeaderPreference.master(context,
                overResults ? this::navigateBack : this::closeSettings,
                () -> showRow(SettingsStatusPreference.KEY));
        screen.addPreference(masterHeader);
        SettingsSearchEntryPreference search = new SettingsSearchEntryPreference(context,
                preference -> {
                    openSearch();
                    return true;
                });
        search.setKey("action_search_settings");
        search.setOrder(-900);
        screen.addPreference(search);
        quickSetupsRow = new QuickSetupsPreference(context, this::settingsOnPages, this::hideQuickSetups);

        for (Section section : navigationSections()) {
            if (section == Section.DIAGNOSTICS || section == Section.BACKUP) continue;
            if (section.isAvailable()) addMenu(screen, section);
        }
        if (FeatureGateLabRuntime.isInstalled()) addFeatureGateLab(screen);

        screen.addPreference(SectionHeadingPreference.compact(context, "Metra"));
        if (!BaseSettings.HIDE_QUICK_SETUPS.get()) screen.addPreference(quickSetupsRow);
        // Detailed state and recovery, with Turn Hushfeed back on while paused.
        SettingsStatusPreference status = new SettingsStatusPreference(context,
                Section.DIAGNOSTICS.isAvailable() ? () -> openSection(Section.DIAGNOSTICS) : null);
        status.setTurnBackOnAction(this::turnHushfeedBackOn);
        screen.addPreference(status);
        screen.addPreference(pauseRow(context));
        if (Section.DIAGNOSTICS.isAvailable()) addMenu(screen, Section.DIAGNOSTICS);
        addMenu(screen, Section.BACKUP);
        addAbout(screen);
        addReportProblem(screen);
        addSupport(screen);
    }

    /** The long press on the Quick setups card: it leaves the home page now, and the banner says where it comes back. */
    private void hideQuickSetups() {
        BaseSettings.HIDE_QUICK_SETUPS.save(true);
        syncQuickSetupsRow();
        Context context = getActivity();
        if (context == null) return;
        SettingsActionBanner.showNotice(context, L10n.f(context,
                "Quick setups hidden. Bring it back from %1$s.", L10n.t(context, Section.BACKUP.title)));
    }

    /** Puts the home page's Quick setups card on the page or takes it off to match the saved choice. */
    private void syncQuickSetupsRow() {
        PreferenceScreen screen = getPreferenceScreen();
        if (quickSetupsRow == null || screen == null) return;
        boolean onPage = screen.findPreference(QuickSetupsPreference.KEY) != null;
        boolean hidden = BaseSettings.HIDE_QUICK_SETUPS.get();
        if (hidden && onPage) screen.removePreference(quickSetupsRow);
        else if (!hidden && !onPage) screen.addPreference(quickSetupsRow);
    }

    /** A home heading and its pages, or nothing when the bundle left out every one of them. */
    private void addGroup(Context context, PreferenceScreen screen, String heading,
            Section... sections) {
        boolean headed = false;
        for (Section section : sections) {
            if (!section.isAvailable()) continue;
            if (!headed) {
                screen.addPreference(SectionHeadingPreference.compact(context, heading));
                headed = true;
            }
            addMenu(screen, section);
        }
    }

    /** Report a problem, just above the Ko-fi row: a GitHub bug report with the facts filled in. */
    private void addReportProblem(PreferenceScreen screen) {
        SettingsMenuPreference row = new SettingsMenuPreference(getActivity(), "Report a problem",
                "Opens a GitHub report with your versions and settings filled in. Add what happened.",
                SettingsMenuPreference.Icon.REPORT, preference -> {
                    reportProblem();
                    return true;
                });
        row.setKey(REPORT_PROBLEM_KEY);
        row.setCompact(true);
        screen.addPreference(row);
    }

    /** The last row of the home page: the Ko-fi page, under its title alone. */
    private void addSupport(PreferenceScreen screen) {
        SettingsMenuPreference row = new SettingsMenuPreference(getActivity(), "Support Metra",
                "", SettingsMenuPreference.Icon.SUPPORT, preference -> {
                    openWebPage(getActivity(), SUPPORT_URL);
                    return true;
                });
        row.setKey(SUPPORT_KEY);
        row.setCompact(true);
        screen.addPreference(row);
    }

    private void reportProblem() {
        Activity activity = getActivity();
        if (activity == null) return;
        ProblemReport.Facts facts = ProblemReport.collect(activity, switchesOnInEnglish(activity));
        openInBrowser(activity, ProblemReport.url(facts));
    }

    /** A web page in the reader's browser, in a task of its own so it never opens inside TikTok's. */
    static Intent webPageIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void openWebPage(Context context, String url) {
        if (context == null) return;
        try {
            context.startActivity(webPageIntent(url));
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception would close TikTok.
            SettingsActionBanner.showNotice(context, L10n.t(context, "No app on this phone opens web links"));
        }
    }

    /**
     * What Android itself asks to find a browser: a web link with no address on it, which only
     * an app that opens every link answers. An app that claims one site's links doesn't.
     */
    static Intent anyWebPageIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.fromParts("https", "", null))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }

    /**
     * A web page in a browser even when an app claims its address. GitHub's app takes a new-issue
     * link into its own composer, which keeps the title and drops every field the bug form is
     * filled through (S25, 2026-10-13), so the report names the browser it goes to: the default
     * one, or the first that answers when none is the default. With no browser found the link is
     * left to open the way any other does.
     */
    static Intent browserPageIntent(Context context, String url) {
        Intent page = webPageIntent(url);
        String browser = browserPackage(context);
        if (browser != null) page.setPackage(browser);
        return page;
    }

    static String browserPackage(Context context) {
        try {
            PackageManager packages = context.getPackageManager();
            Intent anyPage = anyWebPageIntent();
            String chosen = browserNamed(packages.resolveActivity(anyPage, PackageManager.MATCH_DEFAULT_ONLY));
            if (chosen != null) return chosen;
            for (ResolveInfo candidate : packages.queryIntentActivities(anyPage, PackageManager.MATCH_DEFAULT_ONLY)) {
                String name = browserNamed(candidate);
                if (name != null) return name;
            }
        } catch (RuntimeException unreadable) {
            // The plain link still opens somewhere.
        }
        return null;
    }

    /** The package that answered, unless it's Android's own chooser standing in for several. */
    private static String browserNamed(ResolveInfo answer) {
        if (answer == null || answer.activityInfo == null) return null;
        String name = answer.activityInfo.packageName;
        return name == null || "android".equals(name) ? null : name;
    }

    static void openInBrowser(Context context, String url) {
        if (context == null) return;
        try {
            context.startActivity(browserPageIntent(context, url));
        } catch (ActivityNotFoundException | SecurityException refused) {
            // The browser that answered a moment ago is gone or switched off.
            openWebPage(context, url);
        }
    }

    /**
     * The title of every switch that's on, page by page, for a problem report. Read in English,
     * which is what the tracker is written in, whatever language the phone shows.
     */
    private List<String> switchesOnInEnglish(Context context) {
        Context english = context;
        try {
            android.content.res.Configuration configuration =
                    new android.content.res.Configuration(context.getResources().getConfiguration());
            configuration.setLocale(java.util.Locale.ENGLISH);
            english = context.createConfigurationContext(configuration);
        } catch (RuntimeException unavailable) {
            // The phone's own language then: a report in German still says which switches are on.
        }
        List<String> titles = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        PreferenceScreen scratch = getPreferenceManager().createPreferenceScreen(context);
        for (Section section : navigationSections()) {
            try {
                PreferenceCategory category = createCategory(english, scratch, section);
                if (category == null) continue;
                collectSwitchesOn(category, titles, seen);
                scratch.removePreference(category);
            } catch (RuntimeException unreadable) {
                app.morphe.extension.shared.Logger.printException(
                        () -> "Couldn't read the switches on " + section + " for the report", unreadable);
            }
        }
        return titles;
    }

    private static void collectSwitchesOn(PreferenceGroup group, List<String> titles,
            java.util.Set<String> seen) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);
            if (preference instanceof PreferenceGroup) {
                collectSwitchesOn((PreferenceGroup) preference, titles, seen);
                continue;
            }
            if (preference instanceof SwitchListPreference) {
                for (SwitchListPreference.Item item : ((SwitchListPreference) preference).items()) {
                    if (item.setting.savedValue() && seen.add(item.setting.key)) {
                        titles.add(preference.getTitle() + ": " + item.label);
                    }
                }
                continue;
            }
            if (!(preference instanceof android.preference.SwitchPreference) || !preference.hasKey()) continue;
            Setting<?> setting = Setting.getSettingFromPath(preference.getKey());
            if (setting instanceof app.morphe.extension.shared.settings.BooleanSetting
                    && Boolean.TRUE.equals(setting.savedValue()) && seen.add(setting.key)) {
                titles.add(String.valueOf(preference.getTitle()));
            }
        }
    }

    private void addAbout(PreferenceScreen screen) {
        SettingsMenuPreference row = new SettingsMenuPreference(getActivity(), Hub.ABOUT.title,
                "", SettingsMenuPreference.Icon.ABOUT, preference -> {
                    openHub(Hub.ABOUT);
                    return true;
                });
        row.setKey("hub_" + Hub.ABOUT.name().toLowerCase(java.util.Locale.ROOT));
        row.setCompact(true);
        screen.addPreference(row);
    }

    private void createAboutMenu(Context context, PreferenceScreen screen) {
        screen.addPreference(SettingsHeaderPreference.section(context, Hub.ABOUT.title, this::navigateBack));
        screen.addPreference(SettingsHeaderPreference.caption(context, Hub.ABOUT.description));
        screen.addPreference(new SectionHeadingPreference(context, "About"));
        screen.addPreference(new BuildDetailsPreference(context));
        addReleaseNotes(context, screen);
        screen.addPreference(new MorpheTikTokAboutPreference(context));
        screen.addPreference(new LicensesPreference(context));
    }

    private void addFeatureGateLab(PreferenceScreen screen) {
        SettingsMenuPreference lab = new SettingsMenuPreference(getActivity(), "Feature Gate Lab",
                "Advanced. Find and force the hidden switches TikTok uses to test features.",
                SettingsMenuPreference.Icon.LAB, preference -> {
                    FeatureGateLabFragment.open(getActivity());
                    return true;
                });
        lab.setKey(FEATURE_GATE_LAB_KEY);
        lab.setCompact(false);
        screen.addPreference(lab);
    }

    private void addReleaseNotes(Context context, PreferenceScreen screen) {
        String releaseVersion = releaseVersion();
        if (!ReleaseNotes.pending(context, releaseVersion)) return;
        SettingsMenuPreference notes = new SettingsMenuPreference(context, "What's new",
                L10n.f(context, "Changes in Metra %1$s", ReleaseNotes.rowVersion(context, releaseVersion)),
                SettingsMenuPreference.Icon.NEWS, preference -> {
                    ReleaseNotes.show(context, releaseVersion, () -> screen.removePreference(preference));
                    return true;
                });
        notes.setKey(ReleaseNotes.KEY);
        screen.addPreference(notes);
    }

    /**
     * Pause Hushfeed. It takes the screen-time budget off with everything else, so a locked
     * day refuses it the same way it refuses every budget setting, and so does Wait a day to
     * loosen the budget, the way it refuses Start today over: paused from the next start, the
     * budget would be gone without waiting for anything.
     */
    private TogglePreference pauseRow(Context context) {
        TogglePreference pause = new TogglePreference(context, "Pause Metra", PAUSE_SUMMARY,
                BaseSettings.PAUSED);
        pause.setOnPreferenceChangeListener((preference, value) -> {
            if (!Boolean.TRUE.equals(value)) return true;
            if (SessionBudget.lockedToday()) {
                SettingsActionBanner.showNotice(context, L10n.f(context,
                        "Today's budget is locked. This can be changed again at %1$s.",
                        SessionLockOverlay.resetTimeLabel()));
                return false;
            }
            if (Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.savedValue()) {
                SettingsActionBanner.showNotice(context, L10n.t(context,
                        "Wait a day to loosen the budget is on, so Metra stays on"));
                return false;
            }
            return true;
        });
        return pause;
    }

    /** The paused status card's action: safe mode, the switch and the marker file all go. */
    private void turnHushfeedBackOn() {
        Context context = getActivity();
        if (context == null) return;
        boolean pausedBefore = BaseSettings.PAUSED.savedValue();
        boolean safeModeBefore = BaseSettings.SAFE_MODE.savedValue();
        // What this process runs with goes on record before the save. The store's listener
        // reaches this page as soon as the save lands and reads the value "before" the change
        // from the setting, which by then already holds the new one, so it would record the
        // switch as never having changed and the restart it owes would go unsaid.
        noteRestartPending(BaseSettings.PAUSED, pausedBefore);
        noteRestartPending(BaseSettings.SAFE_MODE, safeModeBefore);
        boolean markerGone = HushfeedPause.turnBackOn(context);
        Preference row = findPreference(BaseSettings.PAUSED.key);
        if (row instanceof TogglePreference) ((TogglePreference) row).setChecked(false);
        noteRestartPending(BaseSettings.PAUSED, pausedBefore);
        noteRestartPending(BaseSettings.SAFE_MODE, safeModeBefore);
        if (markerGone && HushfeedPause.reason() == HushfeedPause.Reason.MARKER_FILE) {
            // The file is no setting, but the start it paused owes a restart all the same.
            AbstractPreferenceFragment.restartPending.add(HushfeedPause.MARKER_FILE_NAME);
            refreshRestartPending();
        }
        refreshStatusCard();
        if (markerGone) {
            SettingsActionBanner.showRestart(context,
                    L10n.t(context, "Metra turns back on when TikTok restarts."));
        } else {
            SettingsActionBanner.showNotice(context, L10n.t(context,
                    "The file hushfeed-safe-mode couldn't be removed. Delete it from TikTok's folder under Android/data to turn Metra back on."));
        }
    }

    /** The status card says again whether the next start still runs paused. */
    private void refreshStatusCard() {
        Preference status = findPreference(SettingsStatusPreference.KEY);
        if (status instanceof SettingsStatusPreference) ((SettingsStatusPreference) status).refresh();
        if (masterHeader != null) masterHeader.refreshStatus();
    }

    /** What each section binds, so Quick setups does not build the pages again. */
    private final java.util.Map<Section, List<Setting<?>>> sectionSettings =
            new java.util.EnumMap<>(Section.class);

    /** A page's row on the home page, in the compact layout. */
    private void addMenu(PreferenceScreen screen, Section section) {
        String description = L10n.t(getActivity(), section == Section.SCREEN_TIME
                ? "Budgets and reminders" : section.description);
        if (description.endsWith(".")) {
            description = description.substring(0, description.length() - 1);
        }
        SettingsMenuPreference row = new SettingsMenuPreference(
                getActivity(),
                section.title,
                description,
                section.icon,
                preference -> {
                    openSection(section);
                    return true;
                }
        );
        row.setKey("section_" + section.name().toLowerCase(java.util.Locale.ROOT));
        row.setCompact(false);
        screen.addPreference(row);
    }

    private void createSectionMenu(Context context, PreferenceScreen screen, Section section) {
        screen.addPreference(SettingsHeaderPreference.section(context, section.title, this::navigateBack));
        screen.addPreference(SettingsHeaderPreference.caption(context, section.description));

        PreferenceCategory category = createCategory(context, screen, section);
        flattenCategory(screen, category);
        if (section == Section.BACKUP) {
            screen.addPreference(new SectionHeadingPreference(context, "Your settings"));
            SettingsBackupPreference.addTo(this, screen);
        }
    }

    void refreshBackupSettings() { updateUIToSettingValues(); }

    /**
     * The settings page in front now, or null. After a rotation or a return from the file picker
     * it is not the page a file read or write started on, and that page is gone.
     */
    static TikTokPreferenceFragment active() {
        TikTokPreferenceFragment current = activeFragment;
        return current != null && current.isAdded() ? current : null;
    }

    /**
     * Where a file read or write reports its outcome: the page open now, else the window it
     * started in, else the fallback, which the banner turns into a toast.
     */
    static Context reportWindow(java.lang.ref.WeakReference<Activity> started, Context fallback) {
        TikTokPreferenceFragment open = active();
        Activity activity = open != null ? open.getActivity() : null;
        if (activity == null) activity = started.get();
        return activity != null ? activity : fallback;
    }

    /** Reconciles a preset's batch write with the rows and the restart debt on this page. */
    static void onSettingsBatchChanged(java.util.Map<Setting<?>, Object> previousValues) {
        app.morphe.extension.tiktok.feed.FeedTextSize.onSettingChanged();
        app.morphe.extension.tiktok.comment.CommenterDetails.onSettingChanged();
        TikTokPreferenceFragment current = activeFragment;
        if (current == null || !current.isAdded()) return;
        for (java.util.Map.Entry<Setting<?>, Object> entry : previousValues.entrySet()) {
            Setting<?> setting = entry.getKey();
            if (setting.rebootApp && !java.util.Objects.equals(entry.getValue(), setting.savedValue())) {
                current.noteRestartPending(setting, entry.getValue());
            }
        }
        current.updateUIToSettingValues();
        current.refreshRestartPending();
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

    private void openSearch() {
        FragmentManager manager = getFragmentManager();
        if (manager == null || getId() == 0) {
            Utils.showToastShort(L10n.t("Couldn't open settings search. Reopen settings and try again."));
            return;
        }

        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean(ARG_SEARCH, true);
        fragment.setArguments(arguments);
        manager.beginTransaction()
                .setTransition(android.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .replace(getId(), fragment)
                .addToBackStack("search")
                .commit();
    }

    private void openSection(Section section) {
        openSection(section, null, null);
    }

    private void openHub(Hub hub) {
        openHub(hub, null);
    }

    private void openHub(Hub hub, String targetKey) {
        FragmentManager manager = getFragmentManager();
        if (manager == null || getId() == 0) {
            Utils.showToastShort(L10n.t("Couldn't open that settings section. Reopen settings and try again."));
            return;
        }
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString(ARG_HUB, hub.name());
        if (targetKey != null) arguments.putString(ARG_TARGET_KEY, targetKey);
        fragment.setArguments(arguments);
        manager.beginTransaction()
                .setTransition(android.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .replace(getId(), fragment)
                .addToBackStack("hub_" + hub.name())
                .commit();
    }

    private void openSection(Section section, String targetKey, String targetMember) {
        FragmentManager manager = getFragmentManager();
        if (manager == null || getId() == 0) {
            Utils.showToastShort(L10n.t("Couldn't open that settings section. Reopen settings and try again."));
            return;
        }

        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        // Null is the home page, which Pause's search result opens scrolled to its row.
        if (section != null) {
            arguments.putString(ARG_SECTION, section.name());
        }
        if (targetKey != null) {
            arguments.putString(ARG_TARGET_KEY, targetKey);
            if (targetMember != null) arguments.putString(ARG_TARGET_MEMBER, targetMember);
        }
        fragment.setArguments(arguments);

        manager.beginTransaction()
                .setTransition(android.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .replace(getId(), fragment)
                .addToBackStack(section == null ? null : section.name())
                .commit();
    }

    private void navigateBack() {
        FragmentManager manager = getFragmentManager();
        if (manager != null && manager.getBackStackEntryCount() > 0) {
            manager.popBackStack();
        } else {
            // A page TikTok opened settings straight onto, as filter feedback does, has nothing
            // under it to go back to, so its arrow leaves settings like the phone's Back does.
            closeSettings();
        }
    }

    private void hideKeyboard() {
        View view = getView();
        if (view == null) return;
        InputMethodManager manager = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void closeSettings() {
        Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }

    /** Redraws the Screen time rows as this screen comes to the front and once a minute after. */
    private final android.os.Handler budgetTicks = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable budgetTick = new Runnable() {
        @Override public void run() {
            app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory.redrawOpenPages();
            budgetTicks.postDelayed(this, 60_000L);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        activeFragment = this;
        // Back from Backup and restore, where the card is shown or hidden again.
        syncQuickSetupsRow();
        refreshStatusCard();
        // Back from a section onto the master menu: whatever was changed there is owed here.
        refreshRestartPending();
        // A phone asleep over the day's start pauses this screen, so coming back is when the
        // Screen time rows are most likely to be out of date.
        budgetTicks.removeCallbacks(budgetTick);
        budgetTick.run();
    }

    @Override
    public void onPause() {
        budgetTicks.removeCallbacks(budgetTick);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        budgetTicks.removeCallbacks(budgetTick);
        if (activeFragment == this) {
            activeFragment = null;
            // The pending key deliberately survives: the picker destroys this fragment while it
            // is open, and clearing it here dropped the folder the reader had just chosen.
        }
        super.onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (ImportSeenVideoHistoryPreference.onResult(this, requestCode, resultCode, data)) return;
        if (SeenHistoryFilePreference.onResult(this, requestCode, resultCode, data)) return;
        if (SettingsBackupPreference.onResult(this, requestCode, resultCode, data)) return;
        if (ImportedFontPreference.onResult(this, requestCode, resultCode, data)) return;
        if (requestCode != REQUEST_DOWNLOAD_PATH_FOLDER) {
            return;
        }

        String pendingKey = pendingDownloadPathKey;
        pendingDownloadPathKey = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Preference found = pendingKey == null ? null : findPreference(pendingKey);
        if (!(found instanceof DownloadPathPreference)) {
            app.morphe.extension.shared.Utils.showToastLong(
                    L10n.t("Couldn't tell which folder to update. Choose it again."));
            return;
        }
        DownloadPathPreference preference = (DownloadPathPreference) found;

        String relativePath = getRelativePrimaryStoragePath(data.getData());
        if (relativePath == null) {
            preference.applyPickedPath(null);
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
