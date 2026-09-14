package app.morphe.extension.tiktok.sharesheet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Video Actions" keys as seen on other users' content. TikTok has no readable source for
 * these, so they were captured live via {@link ShareSheetFilter}'s debug logging. Keys not
 * seeded here are picked up automatically the first time they're observed.
 * <p>
 * The current account's own videos surface a different, mostly disjoint creator-only set
 * (Analytics, Delete, Edit Post, ...) that's intentionally left out, since this allow-list
 * is scoped to other people's content.
 */
public final class VideoActionOptions {
    private static final String RAW_PREFIX = "RAW:";

    public static final Option[] OPTIONS = {
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
            new Option("show_in_chat", "Show in Chat"),
    };

    private VideoActionOptions() {
    }

    public static String defaultEnabledKeys() {
        return "";
    }

    public static Set<String> parseEnabledKeys(String keys) {
        return parseKeys(keys);
    }

    public static Set<String> parseObservedKeys(String keys) {
        return parseKeys(keys);
    }

    private static Set<String> parseKeys(String keys) {
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

    public static String serializeEnabledKeys(Set<String> keys) {
        StringBuilder builder = new StringBuilder();
        for (Option option : OPTIONS) {
            appendKey(builder, keys, option.key);
        }

        for (String key : keys) {
            if (findOption(key) == null) {
                appendKey(builder, keys, key);
            }
        }
        return builder.toString();
    }

    public static List<Option> optionsForKeys(Set<String> keys) {
        ArrayList<Option> options = new ArrayList<>();
        for (Option option : OPTIONS) {
            if (keys.contains(option.key)) {
                options.add(option);
            }
        }

        for (String key : keys) {
            if (findOption(key) == null) {
                options.add(new Option(key, rawLabel(key)));
            }
        }

        return options;
    }

    public static Option findOption(String key) {
        String normalized = normalizeSettingKey(key);
        if (normalized == null) {
            return null;
        }

        for (Option option : OPTIONS) {
            if (option.key.equals(normalized)) {
                return option;
            }
        }
        return null;
    }

    private static String normalizeSettingKey(String key) {
        if (key == null) {
            return null;
        }

        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        for (Option option : OPTIONS) {
            if (option.key.equalsIgnoreCase(trimmed)) {
                return option.key;
            }
        }

        if (trimmed.toUpperCase(Locale.US).startsWith(RAW_PREFIX)) {
            return trimmed;
        }

        return trimmed.toLowerCase(Locale.US);
    }

    private static String rawLabel(String key) {
        if (key == null) {
            return "Unknown action";
        }

        String raw = key.startsWith(RAW_PREFIX) ? key.substring(RAW_PREFIX.length()) : key;
        raw = raw.replace('_', ' ').trim();
        if (raw.isEmpty()) {
            return "Unknown action";
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
