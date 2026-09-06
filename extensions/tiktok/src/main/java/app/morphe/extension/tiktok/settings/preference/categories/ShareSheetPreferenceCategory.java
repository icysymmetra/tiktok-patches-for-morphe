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
import app.morphe.extension.tiktok.sharesheet.VideoActionOptions;

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
        addPreference(new TogglePreference(
                context,
                "Show \"Send to\"",
                "Show quick-share contacts at the top of the share sheet.",
                Settings.SHARE_SHEET_SEND_TO
        ));

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
                VideoActionOptions::parseEnabledKeys,
                VideoActionOptions::serializeEnabledKeys,
                keys -> toRowsFromActions(VideoActionOptions.optionsForKeys(keys)),
                ShareSheetPreferenceCategory::observedActionKeys
        ));
    }

    private static Set<String> observedChannelKeys() {
        Set<String> keys = ShareChannelOptions.parseEnabledKeys(Settings.SHARE_SHEET_CHANNELS_ENABLED.get());
        keys.addAll(ShareChannelOptions.parseObservedKeys(Settings.SHARE_SHEET_CHANNELS_OBSERVED.get()));
        return keys;
    }

    private static Set<String> observedActionKeys() {
        Set<String> keys = VideoActionOptions.parseEnabledKeys(Settings.SHARE_SHEET_ACTIONS_ENABLED.get());
        keys.addAll(VideoActionOptions.parseObservedKeys(Settings.SHARE_SHEET_ACTIONS_OBSERVED.get()));
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
            List<VideoActionOptions.Option> options
    ) {
        List<ShareSheetItemSelectionPreference.Row> rows = new ArrayList<>();
        for (VideoActionOptions.Option option : options) {
            rows.add(new ShareSheetItemSelectionPreference.Row(option.key, option.label));
        }
        return rows;
    }
}
