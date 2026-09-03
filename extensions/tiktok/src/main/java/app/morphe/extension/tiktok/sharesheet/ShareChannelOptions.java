package app.morphe.extension.tiktok.sharesheet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Known "Share via" channel keys, sourced from TikTok's
 * {@code com.ss.android.ugc.aweme.channel.share.channelservice.*ChannelService} classes.
 * Channels not seeded here are picked up automatically the first time they're observed;
 * see {@link ShareSheetFilter}.
 */
public final class ShareChannelOptions {
    private static final String RAW_PREFIX = "RAW:";

    public static final Option[] OPTIONS = {
            new Option("repost", "Repost"),
            new Option("copy", "Copy Link"),
            new Option("ai_remix", "AI Remix"),
            new Option("live_repost_note", "Live Repost Add Note"),
            new Option("chat_merge", "Chat Merge"),
            new Option("qr_code", "QR Code"),
            new Option("sms", "SMS"),
            new Option("email", "Email"),
            new Option("facebook", "Facebook"),
            new Option("facebook_lite", "Facebook Lite"),
            new Option("facebook_group", "Facebook Group"),
            new Option("facebook_story", "Facebook Story"),
            new Option("messenger", "Messenger"),
            new Option("messenger_lite", "Messenger Lite"),
            new Option("instagram", "Instagram"),
            new Option("instagram_story", "Instagram Story"),
            new Option("instagram_dm", "Instagram DM"),
            new Option("whatsapp", "WhatsApp"),
            new Option("whatsapp_business", "WhatsApp Business"),
            new Option("whatsapp_status", "WhatsApp Status"),
            new Option("telegram", "Telegram"),
            new Option("twitter", "X (Twitter)"),
            new Option("snapchat", "Snapchat"),
            new Option("snapchat_chats", "Snapchat Chats"),
            new Option("reddit", "Reddit"),
            new Option("imo", "imo"),
            new Option("pinterest", "Pinterest"),
            new Option("imgur", "Imgur"),
            new Option("line", "Line"),
            new Option("viber", "Viber"),
            new Option("kakaotalk", "KakaoTalk"),
            new Option("kakao_story", "KakaoStory"),
            new Option("google_messages", "Google Messages"),
            new Option("youtube", "YouTube"),
            new Option("zalo", "Zalo"),
            new Option("band", "Band"),
            new Option("lemon8", "Lemon8"),
            new Option("vk", "VK"),
            new Option("more", "More (system share sheet)"),
            new Option("upscrolled", "Upscrolled"),
    };

    private ShareChannelOptions() {
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
            return "Unknown app";
        }

        String raw = key.startsWith(RAW_PREFIX) ? key.substring(RAW_PREFIX.length()) : key;
        raw = raw.replace('_', ' ').trim();
        if (raw.isEmpty()) {
            return "Unknown app";
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
