/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */

package app.morphe.extension.tiktok.share;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps a small, persistent catalogue of share actions seen in the native model, and which share
 * sheets each was seen on. A line is {@code key TAB label TAB surfaces}; a line written before
 * the sheets were told apart has no third field and reads as seen on videos.
 */
public final class ShareActionCatalog {
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_LABEL_LENGTH = 80;

    private ShareActionCatalog() {
    }

    public static final class Entry {
        public final String key;
        public final String label;
        /** One {@link ShareSurface} flag letter per sheet this action was seen on. */
        final String surfaces;

        Entry(String key, String label, String surfaces) {
            this.key = key;
            this.label = label;
            this.surfaces = surfaces;
        }

        public boolean seenOn(ShareSurface surface) {
            return surfaces.indexOf(surface.flag) >= 0;
        }
    }

    static synchronized void observe(List<?> items, ShareSurface surface) {
        if (items == null || items.isEmpty()) return;
        LinkedHashMap<String, String> catalog = read();
        LinkedHashMap<String, String> seen = readSurfaces();
        boolean changed = false;
        for (Object item : items) {
            String rawKey = Reflect.firstNonBlank(
                    Reflect.string(item, "getKey", "key"),
                    Reflect.string(item, "key", "key")
            );
            if (rawKey == null) continue;
            String key = canonical(rawKey);
            if (key.isEmpty()) continue;
            String label = label(item, key);
            if (!catalog.containsKey(key)) {
                if (catalog.size() >= MAX_ENTRIES) continue;
                catalog.put(key, label);
                changed = true;
            } else if (!catalog.get(key).equals(label) && !label.equals(key)) {
                catalog.put(key, label);
                changed = true;
            }
            String flags = seen.containsKey(key) ? seen.get(key) : "";
            if (flags.indexOf(surface.flag) < 0) {
                seen.put(key, flags + surface.flag);
                changed = true;
            }
        }
        if (changed) {
            Settings.SHARE_ACTION_CATALOG.save(write(catalog, seen));
        }
    }

    public static synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        LinkedHashMap<String, String> seen = readSurfaces();
        for (Map.Entry<String, String> entry : read().entrySet()) {
            String flags = seen.get(entry.getKey());
            result.add(new Entry(entry.getKey(), entry.getValue(), flags == null ? "" : flags));
        }
        return result;
    }

    /** The actions seen on [surface]'s sheet, in the order they were first seen. */
    public static synchronized List<Entry> entries(ShareSurface surface) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries()) {
            if (entry.seenOn(surface)) result.add(entry);
        }
        return result;
    }

    public static String canonical(String value) {
        if (value == null) return "";
        String key = value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        switch (key) {
            case "copy":
            case "copy link":
                return "copy";
            case "not interested":
            case "dislike":
                return "dislike";
            case "save video":
            case "download":
            case "save":
                return "save";
            case "create group":
            case "im create group":
                return "im create group";
            case "why this post":
            case "why this video":
                return "why this video";
            case "add to story":
            case "share to story":
                return "share to story";
            case "set as wallpaper":
            case "live photo":
                return "live photo";
            case "promote":
            case "promote for others fyp":
                return "promote for others fyp";
            default:
                return key;
        }
    }

    private static LinkedHashMap<String, String> read() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String stored = Settings.SHARE_ACTION_CATALOG.get();
        if (stored == null || stored.trim().isEmpty()) return result;
        for (String line : stored.split("\\n")) {
            int separator = line.indexOf('\t');
            if (separator <= 0) continue;
            String key = canonical(line.substring(0, separator));
            int flags = line.indexOf('\t', separator + 1);
            String label = cleanLabel(flags < 0
                    ? line.substring(separator + 1) : line.substring(separator + 1, flags));
            if (!key.isEmpty() && !label.isEmpty() && result.size() < MAX_ENTRIES) {
                result.put(key, label);
            }
        }
        return result;
    }

    /** key to surface flags, for every line; a line with no flags field was written by videos. */
    private static LinkedHashMap<String, String> readSurfaces() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String stored = Settings.SHARE_ACTION_CATALOG.get();
        if (stored == null || stored.trim().isEmpty()) return result;
        for (String line : stored.split("\\n")) {
            int separator = line.indexOf('\t');
            if (separator <= 0) continue;
            String key = canonical(line.substring(0, separator));
            int flags = line.indexOf('\t', separator + 1);
            String raw = flags < 0 ? String.valueOf(ShareSurface.VIDEO.flag) : line.substring(flags + 1);
            StringBuilder known = new StringBuilder();
            for (int index = 0; index < raw.length(); index++) {
                char flag = raw.charAt(index);
                if (ShareSurface.ofFlag(flag) != null && known.indexOf(String.valueOf(flag)) < 0) {
                    known.append(flag);
                }
            }
            if (!key.isEmpty()) result.put(key, known.toString());
        }
        return result;
    }

    private static String write(LinkedHashMap<String, String> catalog, Map<String, String> seen) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            if (result.length() > 0) result.append('\n');
            String flags = seen.get(entry.getKey());
            result.append(entry.getKey()).append('\t').append(cleanLabel(entry.getValue()))
                    .append('\t').append(flags == null ? "" : flags);
        }
        return result.toString();
    }

    private static String label(Object item, String key) {
        String found = Reflect.firstNonBlank(
                Reflect.string(item, "getLabel", "label"),
                Reflect.string(item, "label", "label"),
                Reflect.string(item, "getTitle", "title"),
                Reflect.string(item, "title", "title"),
                Reflect.string(item, "getName", "name"),
                Reflect.string(item, "name", "name"),
                Reflect.string(item, "getText", "text"),
                Reflect.string(item, "text", "text")
        );
        return found == null ? humanize(key) : cleanLabel(found);
    }

    private static String humanize(String key) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (Character.isWhitespace(character)) {
                result.append(' ');
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                result.append(character);
            }
        }
        return cleanLabel(result.toString());
    }

    private static String cleanLabel(String value) {
        if (value == null) return "";
        String clean = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_LABEL_LENGTH) {
            clean = clean.substring(0, MAX_LABEL_LENGTH).trim();
        }
        return clean;
    }
}
