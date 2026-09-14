package app.morphe.extension.tiktok.sharesheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Allow-list catalogues for the action rows TikTok builds on its share panel. Which catalogue
 * applies depends on what is being shared: a video's panel and a profile's panel reuse the same
 * panel builder and the same item list field, but expose entirely different actions, so
 * {@link ShareSheetFilter} picks the catalogue from the panel's share package type.
 * <p>
 * Keys not seeded here are picked up automatically the first time they're observed.
 */
public final class ShareSheetOptions {

    /**
     * Actions on other users' videos. The current account's own videos surface a different,
     * mostly disjoint creator-only set (Analytics, Delete, Edit Post, ...) that's intentionally
     * left out, since this allow-list is scoped to other people's content.
     */
    public static final ShareSheetOptions VIDEO_ACTIONS = new ShareSheetOptions(
            "Unknown action",
            new Option("report", "Report"),
            new Option("dislike", "Not Interested"),
            new Option("why_this_video", "Why This Post"),
            new Option("duet", "Duet"),
            new Option("stitch", "Stitch"),
            new Option("save_photo", "Save Photo"),
            new Option("save", "Save Video"),
            new Option("gif", "Share as GIF"),
            new Option("captions", "Captions"),
            new Option("live_photo", "Set as Wallpaper"),
            new Option("im_create_group", "Create Group"),
            new Option("create_sticker", "Create Sticker"),
            new Option("playback_speed", "Playback Speed"),
            new Option("promote_for_others_fyp", "Promote"),
            new Option("share_to_story", "Add to Story"),
            new Option("show_in_chat", "Show in Chat")
    );

    /** Actions on another user's profile share sheet. */
    public static final ShareSheetOptions USER_ACTIONS = new ShareSheetOptions(
            "Unknown user action",
            new Option("report", "Report"),
            new Option("block_user", "Block User"),
            new Option("message", "Message"),
            new Option("qr_code", "QR Code")
    );

    /** Actions on a live stream's share sheet. */
    public static final ShareSheetOptions LIVE_ACTIONS = new ShareSheetOptions(
            "Unknown live action",
            new Option("report_live", "Report"),
            new Option("live_dislike_action", "Not Interested"),
            new Option("live_add_to_story", "Add to Story"),
            new Option("co_host_suggestion_action", "Co-host"),
            new Option("live_feed_back", "Feedback"),
            new Option("promote_button", "Promote"),
            new Option("live_mobile_to_pc", "Mobile to PC"),
            new Option("pip_switch", "Picture in Picture"),
            new Option("share_setting", "Share Settings"),
            new Option("definition_selection_key", "Video Quality")
    );

    private static final String RAW_PREFIX = "RAW:";

    private final String unknownLabel;
    private final Option[] options;

    private ShareSheetOptions(String unknownLabel, Option... options) {
        this.unknownLabel = unknownLabel;
        this.options = options;
    }

    public String defaultEnabledKeys() {
        return "";
    }

    public Set<String> parseKeys(String keys) {
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        if (keys != null) {
            for (String key : keys.split(",")) {
                String normalized = normalizeSettingKey(key);
                if (normalized != null) {
                    parsed.add(normalized);
                }
            }
        }
        return parsed;
    }

    public String serializeKeys(Set<String> keys) {
        StringBuilder builder = new StringBuilder();
        for (Option option : options) {
            appendKey(builder, keys, option.key);
        }

        for (String key : keys) {
            if (findOption(key) == null) {
                appendKey(builder, keys, key);
            }
        }
        return builder.toString();
    }

    public List<Option> optionsForKeys(Set<String> keys) {
        ArrayList<Option> matched = new ArrayList<>();
        for (Option option : options) {
            if (keys.contains(option.key)) {
                matched.add(option);
            }
        }

        for (String key : keys) {
            if (findOption(key) == null) {
                matched.add(new Option(key, rawLabel(key)));
            }
        }

        return matched;
    }

    public List<Option> allOptions() {
        return new ArrayList<>(Arrays.asList(options));
    }

    public Option findOption(String key) {
        String normalized = normalizeSettingKey(key);
        if (normalized == null) {
            return null;
        }

        for (Option option : options) {
            if (option.key.equals(normalized)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Drops keys that only ever appear on the profile panel. Both panels wrote into one shared
     * allow-list before the two were split apart, so existing installations carry profile keys
     * in their video action settings.
     */
    public static String pruneUserOnlyKeys(String serializedKeys) {
        Set<String> keys = VIDEO_ACTIONS.parseKeys(serializedKeys);
        for (Option option : USER_ACTIONS.options) {
            if (VIDEO_ACTIONS.findOption(option.key) == null) {
                keys.remove(option.key);
            }
        }
        return VIDEO_ACTIONS.serializeKeys(keys);
    }

    private String normalizeSettingKey(String key) {
        if (key == null) {
            return null;
        }

        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        for (Option option : options) {
            if (option.key.equalsIgnoreCase(trimmed)) {
                return option.key;
            }
        }

        if (trimmed.toUpperCase(Locale.US).startsWith(RAW_PREFIX)) {
            return trimmed;
        }

        return trimmed.toLowerCase(Locale.US);
    }

    private String rawLabel(String key) {
        if (key == null) {
            return unknownLabel;
        }

        String raw = key.startsWith(RAW_PREFIX) ? key.substring(RAW_PREFIX.length()) : key;
        raw = raw.replace('_', ' ').trim();
        if (raw.isEmpty()) {
            return unknownLabel;
        }

        return raw.substring(0, 1).toUpperCase(Locale.US) + raw.substring(1);
    }

    private static void appendKey(StringBuilder builder, Set<String> keys, String key) {
        if (!keys.contains(key)) {
            return;
        }

        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(key);
    }

    public static final class Option {
        public final String key;
        public final String label;

        private Option(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }
}
