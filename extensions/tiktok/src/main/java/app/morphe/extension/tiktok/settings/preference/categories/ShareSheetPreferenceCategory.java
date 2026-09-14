package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ShareSheetItemSelectionPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.sharesheet.ShareChannelOptions;
import app.morphe.extension.tiktok.sharesheet.ShareSheetOptions;

@SuppressWarnings("deprecation")
public class ShareSheetPreferenceCategory extends ConditionalPreferenceCategory {
    public ShareSheetPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Share sheet");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.shareSheetEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(group(context, "Quick share"));
        addPreference(new TogglePreference(
                context,
                "Show \"Send to\"",
                "Show quick-share contacts at the top of the share sheet.",
                Settings.SHARE_SHEET_SEND_TO
        ));

        addPreference(group(context, "Sharing apps"));
        addPreference(new TogglePreference(
                context,
                "Show \"Share via\"",
                "Show the row of sharing apps (Repost, Copy link, Discord, WhatsApp, ...).",
                Settings.SHARE_SHEET_CHANNELS
        ));
        addPreference(new ShareSheetItemSelectionPreference(
                context,
                "Allowed sharing apps",
                "Allowed sharing apps",
                "Only apps TikTok has exposed on this installation are listed. Open a video's Share menu "
                        + "once to discover currently available apps; newly discovered apps start enabled.",
                Settings.SHARE_SHEET_CHANNELS_ENABLED,
                ShareChannelOptions::parseEnabledKeys,
                ShareChannelOptions::serializeEnabledKeys,
                keys -> toRows(ShareChannelOptions.optionsForKeys(keys)),
                ShareSheetPreferenceCategory::observedChannelKeys
        ));

        addPreference(group(context, "Video actions"));
        addPreference(new TogglePreference(
                context,
                "Show \"Video Actions\"",
                "Show the actions grid (Report, Download, Duet, Stitch, Playback Speed, ...).",
                Settings.SHARE_SHEET_ACTIONS
        ));
        addPreference(new ShareSheetItemSelectionPreference(
                context,
                "Allowed video actions",
                "Allowed video actions",
                "Only actions TikTok has exposed on this installation are listed. Open a video's Share menu "
                        + "once to discover currently available actions; newly discovered actions start enabled.",
                Settings.SHARE_SHEET_ACTIONS_ENABLED,
                ShareSheetOptions.VIDEO_ACTIONS::parseKeys,
                ShareSheetOptions.VIDEO_ACTIONS::serializeKeys,
                keys -> toRowsFromActions(ShareSheetOptions.VIDEO_ACTIONS.optionsForKeys(keys)),
                ShareSheetPreferenceCategory::observedActionKeys
        ));

        addPreference(group(context, "User actions"));
        addPreference(new TogglePreference(
                context,
                "Show \"User Actions\"",
                "Show the actions grid on a profile's share sheet (Report, Block User, Message, ...).",
                Settings.SHARE_SHEET_USER_ACTIONS
        ));
        addPreference(new ShareSheetItemSelectionPreference(
                context,
                "Allowed user actions",
                "Allowed user actions",
                "Only actions TikTok has exposed on this installation are listed. Open a profile's Share menu "
                        + "once to discover currently available actions; newly discovered actions start enabled.",
                Settings.SHARE_SHEET_USER_ACTIONS_ENABLED,
                ShareSheetOptions.USER_ACTIONS::parseKeys,
                ShareSheetOptions.USER_ACTIONS::serializeKeys,
                keys -> toRowsFromActions(ShareSheetOptions.USER_ACTIONS.optionsForKeys(keys)),
                ShareSheetPreferenceCategory::observedUserActionKeys
        ));

        addPreference(group(context, "Live actions"));
        addPreference(new TogglePreference(
                context,
                "Show \"Live Actions\"",
                "Show the actions grid on a live stream's share sheet.",
                Settings.SHARE_SHEET_LIVE_ACTIONS
        ));
        addPreference(new ShareSheetItemSelectionPreference(
                context,
                "Allowed live actions",
                "Allowed live actions",
                "Only actions TikTok has exposed on this installation are listed. Open a live stream's "
                        + "Share menu once to discover currently available actions; newly discovered "
                        + "actions start enabled.",
                Settings.SHARE_SHEET_LIVE_ACTIONS_ENABLED,
                ShareSheetOptions.LIVE_ACTIONS::parseKeys,
                ShareSheetOptions.LIVE_ACTIONS::serializeKeys,
                keys -> toRowsFromActions(ShareSheetOptions.LIVE_ACTIONS.optionsForKeys(keys)),
                ShareSheetPreferenceCategory::observedLiveActionKeys
        ));
    }

    private static Set<String> observedChannelKeys() {
        Set<String> keys = ShareChannelOptions.parseEnabledKeys(Settings.SHARE_SHEET_CHANNELS_ENABLED.get());
        keys.addAll(ShareChannelOptions.parseObservedKeys(Settings.SHARE_SHEET_CHANNELS_OBSERVED.get()));
        return keys;
    }

    private static Set<String> observedActionKeys() {
        Set<String> keys = ShareSheetOptions.VIDEO_ACTIONS.parseKeys(Settings.SHARE_SHEET_ACTIONS_ENABLED.get());
        keys.addAll(ShareSheetOptions.VIDEO_ACTIONS.parseKeys(Settings.SHARE_SHEET_ACTIONS_OBSERVED.get()));
        return keys;
    }

    private static Set<String> observedLiveActionKeys() {
        Set<String> keys = ShareSheetOptions.LIVE_ACTIONS.parseKeys(Settings.SHARE_SHEET_LIVE_ACTIONS_ENABLED.get());
        keys.addAll(ShareSheetOptions.LIVE_ACTIONS.parseKeys(Settings.SHARE_SHEET_LIVE_ACTIONS_OBSERVED.get()));
        return keys;
    }

    private static Set<String> observedUserActionKeys() {
        Set<String> keys = ShareSheetOptions.USER_ACTIONS.parseKeys(Settings.SHARE_SHEET_USER_ACTIONS_ENABLED.get());
        keys.addAll(ShareSheetOptions.USER_ACTIONS.parseKeys(Settings.SHARE_SHEET_USER_ACTIONS_OBSERVED.get()));
        return keys;
    }

    private static List<ShareSheetItemSelectionPreference.Row> toRows(List<ShareChannelOptions.Option> options) {
        List<ShareSheetItemSelectionPreference.Row> rows = new ArrayList<>();
        for (ShareChannelOptions.Option option : options) {
            rows.add(new ShareSheetItemSelectionPreference.Row(option.key, option.label));
        }
        return rows;
    }

    private static List<ShareSheetItemSelectionPreference.Row> toRowsFromActions(
            List<ShareSheetOptions.Option> options
    ) {
        List<ShareSheetItemSelectionPreference.Row> rows = new ArrayList<>();
        for (ShareSheetOptions.Option option : options) {
            rows.add(new ShareSheetItemSelectionPreference.Row(option.key, option.label));
        }
        return rows;
    }
}
