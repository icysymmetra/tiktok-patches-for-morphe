/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * The popup labels TikTok has tried to show on this phone, so the checklist can offer them. One
 * label per line, in the order they were first seen. The list is bounded: a full one drops the
 * label seen first, but never one the reader has ticked.
 */
public final class PopupLabelCatalog {
    private PopupLabelCatalog() {
    }

    static synchronized void observe(String label) {
        String clean = PopupLabels.clean(label);
        String key = PopupLabels.key(clean);
        if (key.isEmpty() || PopupLabels.isSafety(clean)) return;
        LinkedHashMap<String, String> catalog = read();
        if (catalog.containsKey(key)) return;
        if (catalog.size() >= PopupLabels.MAX_LABELS && !dropOldest(catalog, savedPicks())) return;
        catalog.put(key, clean);
        Settings.POPUP_LABEL_CATALOG.save(write(catalog));
    }

    private static boolean dropOldest(Map<String, String> catalog, Set<String> picked) {
        for (Iterator<String> it = catalog.keySet().iterator(); it.hasNext();) {
            if (picked.contains(it.next())) continue;
            it.remove();
            return true;
        }
        return false;
    }

    /** What the reader saved to block, paused or not, so the list never drops one of them. */
    static Set<String> savedPicks() {
        Set<String> keys = new HashSet<>();
        String stored = Settings.POPUP_LABEL_PICKS.savedValue();
        if (stored == null) return keys;
        for (String token : stored.split("[,\\n]")) {
            String key = PopupLabels.key(token);
            if (!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    /** The labels seen so far, oldest first, as TikTok spelled them. */
    public static synchronized List<String> labels() {
        return new ArrayList<>(read().values());
    }

    private static LinkedHashMap<String, String> read() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String stored = Settings.POPUP_LABEL_CATALOG.get();
        if (stored == null || stored.trim().isEmpty()) return result;
        for (String line : stored.split("\\n")) {
            String label = PopupLabels.clean(line);
            String key = PopupLabels.key(label);
            // A line the deny-list would refuse today is dropped, so an older list can't show one.
            if (!key.isEmpty() && !PopupLabels.isSafety(label) && result.size() < PopupLabels.MAX_LABELS) {
                result.put(key, label);
            }
        }
        return result;
    }

    private static String write(Map<String, String> catalog) {
        return String.join("\n", catalog.values());
    }
}
