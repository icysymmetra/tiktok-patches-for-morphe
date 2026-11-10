/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The stored-key rules the feed tabs and the bottom tabs share: a comma list of known keys, plus
 * "RAW:" keys for tabs TikTok named that no option knows, with the tabs that can't be turned off
 * always kept. Each list differs only in its options, the runtime tags it knows and those tabs.
 */
final class TabKeys {
    private static final String RAW_PREFIX = "RAW:";

    private final TabOption[] options;
    /** Lower-case runtime tag to key. */
    private final Map<String, String> aliases;
    /** In the order they are added when missing. */
    private final List<String> required;

    TabKeys(TabOption[] options, Map<String, String> aliases, String... required) {
        this.options = options;
        this.aliases = Collections.unmodifiableMap(new HashMap<>(aliases));
        this.required = Collections.unmodifiableList(Arrays.asList(required.clone()));
    }

    /** Alias helper: every tag in {@code tags} names {@code key}. */
    static void alias(Map<String, String> aliases, String key, String... tags) {
        for (String tag : tags) aliases.put(tag, key);
    }

    String defaultEnabledKeys() {
        StringBuilder builder = new StringBuilder();
        for (TabOption option : options) {
            if (builder.length() > 0) builder.append(',');
            builder.append(option.key);
        }
        return builder.toString();
    }

    /** What the reader kept, with the required tabs added. */
    Set<String> parseEnabledKeys(String keys) {
        return parseKeys(keys, true);
    }

    /** What TikTok was seen showing, as stored. */
    Set<String> parseObservedKeys(String keys) {
        return parseKeys(keys, false);
    }

    private Set<String> parseKeys(String keys, boolean withRequired) {
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        if (keys != null) {
            for (String key : keys.split(",")) {
                String normalized = normalizeSettingKey(key);
                if (normalized != null) parsed.add(normalized);
            }
        }
        if (withRequired) parsed.addAll(required);
        return parsed;
    }

    /** Known keys in the options' order, then the raw ones; the required tabs always. */
    String serializeEnabledKeys(Set<String> keys) {
        StringBuilder builder = new StringBuilder();
        for (TabOption option : options) appendKey(builder, keys, option.key);
        for (String key : keys) {
            if (findOption(key) == null) appendKey(builder, keys, key);
        }
        return builder.toString();
    }

    List<TabOption> optionsForKeys(Set<String> keys) {
        ArrayList<TabOption> found = new ArrayList<>();
        for (TabOption option : options) {
            if (keys.contains(option.key)) found.add(option);
        }
        for (String key : keys) {
            if (findOption(key) == null) found.add(new TabOption(key, rawLabel(key)));
        }
        return found;
    }

    /** A known key for a tag this list knows, a "RAW:" key for any other, null for nothing. */
    String normalizeRuntimeTag(String tag) {
        if (tag == null) return null;
        String known = aliases.get(tag.trim().toLowerCase(Locale.US));
        if (known != null) return known;
        String raw = normalizeRawRuntimeTag(tag);
        return raw == null ? null : RAW_PREFIX + raw;
    }

    TabOption findOption(String key) {
        String normalized = normalizeSettingKey(key);
        if (normalized == null) return null;
        for (TabOption option : options) {
            if (option.key.equals(normalized)) return option;
        }
        return null;
    }

    boolean isRequiredKey(String key) {
        return required.contains(key);
    }

    private String normalizeSettingKey(String key) {
        if (key == null) return null;
        String normalized = key.trim().toUpperCase(Locale.US);
        for (TabOption option : options) {
            if (option.key.equals(normalized)) return option.key;
        }
        if (normalized.startsWith(RAW_PREFIX)) {
            String raw = normalizeRawRuntimeTag(normalized.substring(RAW_PREFIX.length()));
            return raw == null ? null : RAW_PREFIX + raw;
        }
        return null;
    }

    private static String normalizeRawRuntimeTag(String tag) {
        if (tag == null) return null;
        String normalized = tag.trim().toLowerCase(Locale.US)
                .replace(',', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ');
        while (normalized.contains("  ")) normalized = normalized.replace("  ", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private static String rawLabel(String key) {
        if (key == null || !key.startsWith(RAW_PREFIX)) return key == null ? "Unknown tab" : key;
        String raw = key.substring(RAW_PREFIX.length()).replace('_', ' ');
        if (raw.isEmpty()) return "Unknown tab";
        return raw.substring(0, 1).toUpperCase(Locale.US) + raw.substring(1);
    }

    private void appendKey(StringBuilder builder, Set<String> keys, String key) {
        if (!required.contains(key) && !keys.contains(key)) return;
        if (builder.length() > 0) builder.append(',');
        builder.append(key);
    }
}
