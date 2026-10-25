/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a sound's identity off TikTok's {@code Music} model and handles the comma
 * separated lists the sound settings are stored in.
 */
public final class SoundIdentity {
    private SoundIdentity() {
    }

    /** @return the sound id as a string, or null. Tries the string id before the numeric one. */
    public static String idOf(Object music) {
        String id = Reflect.firstNonBlank(
                Reflect.string(music, "getMid", "mid"),
                Reflect.string(music, "getMusicId", "musicId"),
                Reflect.string(music, "getIdStr", "idStr"));
        if (id != null) {
            return id;
        }
        Object numeric = Reflect.property(music, "getId", "id");
        if (numeric instanceof Number && ((Number) numeric).longValue() > 0) {
            return String.valueOf(((Number) numeric).longValue());
        }
        return null;
    }

    /** @return the display name of the sound, or null. */
    public static String nameOf(Object music) {
        return Reflect.firstNonBlank(
                Reflect.string(music, "getMusicName", "musicName"),
                Reflect.string(music, "getTitle", "title"));
    }

    /** @return the author or owner shown under the sound name, or null. */
    public static String authorOf(Object music) {
        return Reflect.firstNonBlank(
                Reflect.string(music, "getAuthorName", "authorName"),
                Reflect.string(music, "getOwnerNickName", "ownerNickName"));
    }

    public static List<String> entries(String stored) {
        List<String> entries = new ArrayList<>();
        if (stored == null) {
            return entries;
        }
        for (String part : stored.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    public static boolean containsEntry(String stored, String entry) {
        for (String candidate : entries(stored)) {
            if (candidate.equalsIgnoreCase(entry)) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code stored} with {@code entry} appended, unchanged if already present. */
    public static String withEntry(String stored, String entry) {
        if (entry == null || entry.trim().isEmpty() || containsEntry(stored, entry)) {
            return stored == null ? "" : stored;
        }
        String base = stored == null ? "" : stored.trim();
        return base.isEmpty() ? entry.trim() : base + "," + entry.trim();
    }

    /** @return {@code stored} with {@code entry} removed. */
    public static String withoutEntry(String stored, String entry) {
        StringBuilder builder = new StringBuilder();
        for (String candidate : entries(stored)) {
            if (candidate.equalsIgnoreCase(entry)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(candidate);
        }
        return builder.toString();
    }
}
