/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.privacy.BenchmarkRuns;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.DeviceIdentityPreference;
import app.morphe.extension.tiktok.settings.preference.GhostModePreference;
import app.morphe.extension.tiktok.settings.preference.HookStatusPreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

/**
 * Everything that decides what TikTok learns: tracking, what it may read off the phone, and
 * what a link carries. Before this page the two tracking switches sat at the bottom of App
 * behavior and the seven device-access patches had no switch at all.
 */
@SuppressWarnings("deprecation")
public final class PrivacyPreferenceCategory extends ConditionalPreferenceCategory {
    public PrivacyPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Privacy");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.appLockEnabled || hasTracking() || hasDeviceAccess() || hasLinks();
    }

    private static boolean hasTracking() {
        return SettingsStatus.disableTelemetryEnabled || SettingsStatus.ghostModeEnabled
                || SettingsStatus.searchHistoryEnabled || SettingsStatus.watchHistoryEnabled
                || SettingsStatus.aiProfilingEnabled;
    }

    private static boolean hasDeviceAccess() {
        return SettingsStatus.contactListBlockerEnabled
                || SettingsStatus.installedAppsBlockerEnabled
                || SettingsStatus.locationGovernorEnabled
                || SettingsStatus.devicePrivacyGuardEnabled
                || SettingsStatus.resourceGovernorEnabled
                || SettingsStatus.cameraMicIndicatorEnabled;
    }

    private static boolean hasLinks() {
        return SettingsStatus.sanitizeShareUrlsEnabled
                || SettingsStatus.externalBrowserEnabled
                || SettingsStatus.browserPrivacyGuardEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        if (SettingsStatus.appLockEnabled) {
            addPreference(new SectionHeadingPreference(context, "App lock"));
            addPreference(new TogglePreference(
                    context,
                    "Lock TikTok",
                    "Ask for the unlock your phone uses, like a fingerprint or a PIN, when TikTok "
                            + "starts and when you come back to it. A link you open from another app "
                            + "still goes to its video once you unlock. While this is on, TikTok's "
                            + "preview in recent apps stays blank. Needs a screen lock on the phone.",
                    Settings.APP_LOCK
            ));
            addPreference(new ChoicePreference(context, "Lock again after", Settings.APP_LOCK_TIMEOUT,
                    new String[]{"Right away", "After 1 minute", "After 5 minutes", "After 15 minutes"},
                    new String[]{"0", "1", "5", "15"}));
        }
        if (hasTracking()) {
            addPreference(new SectionHeadingPreference(context, "Tracking"));
        }
        if (SettingsStatus.disableTelemetryEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Stop analytics and tracking",
                    "Stops TikTok from sending usage reports, ad tracking and crash reports. "
                            + "TikTok's own diagnostic tools go quiet too.",
                    Settings.DISABLE_ANALYTICS
            ));
        }
        if (SettingsStatus.aiProfilingEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Stop on-device AI profiling",
                    "Stop TikTok's built-in AI engine from starting, so it doesn't get a copy of "
                            + "everything TikTok logs about your use. TikTok works as if the engine "
                            + "weren't there.",
                    Settings.STOP_AI_PROFILING
            ));
        }
        if (SettingsStatus.ghostModeEnabled) {
            addPreference(new GhostModePreference(context));
            addPreference(new TogglePreference(
                    context,
                    "Hide online status",
                    "Friends won't see a green dot or Active now while you're in TikTok. Needs "
                            + "Ghost mode on. Their status may stop updating for you, and your "
                            + "last status can linger.",
                    Settings.GHOST_HIDE_ONLINE_STATUS
            ));
            HookStatusPreference diagnostics = new HookStatusPreference(context);
            diagnostics.setKey("action_ghost_mode_diagnostics");
            diagnostics.setTitle(L10n.t(context, "Ghost mode diagnostics"));
            addPreference(diagnostics);
        }
        if (SettingsStatus.searchHistoryEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Don't save new searches",
                    "Keep what you search for out of the search history TikTok saves on this phone. "
                            + "Searches already there stay until you delete them, and TikTok's servers "
                            + "may still keep their own record.",
                    Settings.STOP_SEARCH_HISTORY
            ));
        }
        if (SettingsStatus.watchHistoryEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Keep videos out of Watch history",
                    "Stops TikTok from reporting the videos you watch, so they don't get added "
                            + "to Watch history in Activity center. Your views won't count and "
                            + "For You learns less. Likes, follows and searches are still seen.",
                    Settings.STOP_WATCH_HISTORY
            ));
        }

        if (hasDeviceAccess()) {
            addPreference(new SectionHeadingPreference(context, "Device access"));
        }
        if (SettingsStatus.contactListBlockerEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Block contact list access",
                    "TikTok sees an empty contact list instead of yours. Find Friends and "
                            + "People you may know can no longer use your contacts.",
                    Settings.BLOCK_CONTACT_LIST
            ));
        }
        if (SettingsStatus.installedAppsBlockerEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Block installed app scanning",
                    "TikTok sees an empty list of apps instead of the apps on your phone. "
                            + "Checks for one specific app, such as when TikTok opens an app "
                            + "you tap, still work.",
                    Settings.BLOCK_INSTALLED_APPS
            ));
        }
        if (SettingsStatus.locationGovernorEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Block location",
                    "TikTok gets no location from your phone. The Region settings change your "
                            + "country and time zone. This switch stops the exact position.",
                    Settings.BLOCK_LOCATION
            ));
        }
        if (SettingsStatus.devicePrivacyGuardEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Block clipboard reads",
                    "Stop TikTok reading what you copied. Copying a link from TikTok still works.",
                    Settings.BLOCK_CLIPBOARD_READS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide a VPN connection",
                    "Android stops telling TikTok a VPN is on, and the VPN's tunnel is left out "
                            + "of the network list. TikTok can still see the VPN's IP address. "
                            + "Leave this off if you need a feature that checks for a VPN.",
                    Settings.HIDE_VPN
            ));
            addPreference(new TogglePreference(
                    context,
                    "Block the advertising ID",
                    "TikTok gets a blank advertising ID, like the one Android gives after you "
                            + "delete yours. Apps can't use it to recognize your phone.",
                    Settings.BLOCK_ADVERTISING_ID
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide root",
                    "Hide root status and details about root tools from TikTok.",
                    Settings.HIDE_ROOT
            ));
            addPreference(new DeviceIdentityPreference(context));
        }
        if (SettingsStatus.resourceGovernorEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Block motion sensors",
                    "Stops TikTok from reading your phone's motion and compass sensors. Apps "
                            + "can use them to recognize your phone.",
                    Settings.BLOCK_MOTION_SENSORS
            ));
            TogglePreference benchmark = new TogglePreference(
                    context,
                    "Stop TikTok's speed tests",
                    "TikTok sometimes tests your phone's speed in a background process that can "
                            + "use a lot of memory. This stops that test from starting. One "
                            + "already running ends when TikTok restarts.",
                    Settings.STOP_BENCHMARK_RUNS
            );
            benchmark.setOnPreferenceChangeListener((preference, value) -> {
                BenchmarkRuns.settingsChanged(context, Boolean.TRUE.equals(value));
                return true;
            });
            addPreference(benchmark);
        }
        if (SettingsStatus.cameraMicIndicatorEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show when the camera or microphone is in use",
                    "A green square in the top corner while TikTok has the camera open, and an "
                            + "orange diamond while it records sound. They go when the access ends.",
                    Settings.CAMERA_MIC_INDICATOR
            ));
        }

        if (hasLinks()) {
            addPreference(new SectionHeadingPreference(context, "Links"));
        }
        if (SettingsStatus.sanitizeShareUrlsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Clean up shared links",
                    "Cuts the tracking bits out of links you share.",
                    BaseSettings.SANITIZE_SHARING_LINKS
            ));
            addPreference(new InputTextPreference(
                    context,
                    "Swap the website in shared links",
                    "Type a website name to use instead of tiktok.com in links you share or "
                            + "copy, like vxtiktok.com. Leave empty to share TikTok's own "
                            + "links. Only TikTok links change, and only the website name.",
                    Settings.CUSTOM_SHARE_DOMAIN
            ).withNameKeyboard());
            addPreference(new TogglePreference(
                    context,
                    "Copy the full link for short links",
                    "When Copy link gives a short vt.tiktok.com or vm.tiktok.com link, Metra "
                            + "opens it once in the background to find the full video link and "
                            + "copies that instead. TikTok sees that visit, and it connects directly "
                            + "even when a proxy is on. Links you send to "
                            + "another app go out as they were.",
                    Settings.EXPAND_SHORT_SHARE_LINKS
            ));
        }
        if (SettingsStatus.externalBrowserEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Open external links directly",
                    "Open profile and story website links in your system browser instead of TikTok's in-app browser.",
                    Settings.OPEN_EXTERNAL_LINKS
            ));
        }
        if (SettingsStatus.browserPrivacyGuardEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Protect external pages in TikTok's browser",
                    "Stops websites you open from inside TikTok from reaching back into the "
                            + "app. TikTok's own pages, such as Activity center, Watch history, "
                            + "shop checkout and CAPTCHA, keep working.",
                    Settings.BLOCK_WEBVIEW_JS_INTERFACES
            ));
        }

    }
}
