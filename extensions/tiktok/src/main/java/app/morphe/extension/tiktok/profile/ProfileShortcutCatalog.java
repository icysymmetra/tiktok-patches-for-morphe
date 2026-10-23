/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * The profile shortcuts TikTok has sent to this phone, so the checklist can offer them by the
 * name on screen. A line is {@code key TAB label}, in the order they were first seen.
 */
public final class ProfileShortcutCatalog {
    private static final int MAX_ENTRIES = 48;
    private static final int MAX_LABEL_LENGTH = 80;

    private ProfileShortcutCatalog() {
    }

    public static final class Entry {
        public final String key;
        public final String label;

        Entry(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    static synchronized void observe(List<ProfileShortcuts.Shortcut> shortcuts) {
        if (shortcuts == null || shortcuts.isEmpty()) return;
        LinkedHashMap<String, String> catalog = read();
        Set<String> hidden = null;
        boolean changed = false;
        for (ProfileShortcuts.Shortcut shortcut : shortcuts) {
            String key = ProfileShortcuts.canonical(shortcut.key());
            if (key.isEmpty()) continue;
            String label = cleanLabel(shortcut.label());
            if (label.isEmpty()) label = key;
            String known = catalog.get(key);
            if (known == null) {
                if (catalog.size() >= MAX_ENTRIES) {
                    if (hidden == null) hidden = ProfileShortcuts.savedHiddenKeys();
                    if (!dropOldest(catalog, hidden)) continue;
                }
                catalog.put(key, label);
                changed = true;
            } else if (!known.equals(label) && !label.equals(key)) {
                catalog.put(key, label);
                changed = true;
            }
        }
        if (changed) Settings.PROFILE_SHORTCUT_CATALOG.save(write(catalog));
    }

    /**
     * Makes room for a new shortcut. Every profile visited can bring its own, so a full catalog
     * drops the one seen first, but never one the reader hides: the checklist has to keep those.
     */
    private static boolean dropOldest(Map<String, String> catalog, Set<String> hidden) {
        for (Iterator<Map.Entry<String, String>> it = catalog.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, String> entry = it.next();
            if (hidden.contains(entry.getKey())
                    || hidden.contains(ProfileShortcuts.canonical(entry.getValue()))) continue;
            it.remove();
            return true;
        }
        return false;
    }

    public static synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : read().entrySet()) {
            result.add(new Entry(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static LinkedHashMap<String, String> read() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String stored = Settings.PROFILE_SHORTCUT_CATALOG.get();
        if (stored == null || stored.trim().isEmpty()) return result;
        for (String line : stored.split("\\n")) {
            int separator = line.indexOf('\t');
            if (separator <= 0) continue;
            String key = ProfileShortcuts.canonical(line.substring(0, separator));
            String label = cleanLabel(line.substring(separator + 1));
            if (!key.isEmpty() && !label.isEmpty() && result.size() < MAX_ENTRIES) {
                result.put(key, label);
            }
        }
        return result;
    }

    private static String write(Map<String, String> catalog) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            if (result.length() > 0) result.append('\n');
            result.append(entry.getKey()).append('\t').append(cleanLabel(entry.getValue()));
        }
        return result.toString();
    }

    private static String cleanLabel(String value) {
        if (value == null) return "";
        String clean = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_LABEL_LENGTH) clean = clean.substring(0, MAX_LABEL_LENGTH).trim();
        return clean;
    }
}
