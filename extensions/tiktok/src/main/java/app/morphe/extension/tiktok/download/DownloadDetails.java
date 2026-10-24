/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Captured before queueing, so a later swipe cannot change the saved video's details. */
final class DownloadDetails {
    private static final int TITLE_LIMIT = 255;

    private final String id;
    /** The creator's handle with its @, or empty when the post didn't say. */
    private final String creator;
    private final String link;
    /** ISO 8601 in UTC, or empty when the post had no usable time. */
    private final String published;
    private final String caption;
    private final boolean json;

    DownloadDetails(Object aweme) {
        this(aweme, false);
    }

    DownloadDetails(Object aweme, boolean json) {
        this.json = json;
        Object author = Reflect.property(aweme, "getAuthor", "author");
        String handle = value(Reflect.string(author, "getUniqueId", "uniqueId"), 256);
        id = value(Reflect.string(aweme, "getAid", "aid"), 128);
        link = value(handle.isEmpty() || id.isEmpty() ? ExternalDownloader.shareUrl(aweme)
                : "https://www.tiktok.com/@" + Uri.encode(handle) + "/video/" + Uri.encode(id), 4096);
        creator = handle.isEmpty() ? "" : "@" + handle;
        Object created = Reflect.property(aweme, "getCreateTime", "createTime");
        long time = created instanceof Number ? ((Number) created).longValue() : 0;
        // TikTok normally supplies Unix seconds; older imported models may supply milliseconds.
        if (time > 0 && time < 100_000_000_000L) time *= 1000;
        String date = "";
        if (time > 0 && time <= 253_402_300_799_000L) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            date = format.format(new Date(time));
        }
        published = date;
        caption = value(Reflect.string(aweme, "getDesc", "desc"), 65536);
    }

    String text() {
        return "Creator: " + (creator.isEmpty() ? "Unknown" : creator)
                + "\nLink: " + link
                + "\nPublished: " + (published.isEmpty() ? "Unknown" : published)
                + "\n\nCaption:\n" + caption + "\n";
    }

    /** The same details for scripts and archive tools. A value the post didn't have is null. */
    String json() {
        return "{\n"
                + "  \"id\": " + quote(id) + ",\n"
                + "  \"creator\": " + quote(creator) + ",\n"
                + "  \"link\": " + quote(link) + ",\n"
                + "  \"published\": " + quote(published) + ",\n"
                + "  \"caption\": " + quote(caption) + "\n"
                + "}\n";
    }

    String creator() { return creator; }

    String link() { return link; }

    String published() { return published; }

    String caption() { return caption; }

    /** The caption's first line, which players show as the title. */
    String title() {
        String line = caption.trim();
        int end = line.indexOf('\n');
        if (end >= 0) line = line.substring(0, end).trim();
        return value(line, TITLE_LIMIT);
    }

    /** The details file's extension, after the video's own name. */
    String extension() { return json ? ".json" : ".txt"; }

    /** Android's scoped store only accepts a plain-text sidecar in Download or Documents. */
    static String pairedPath(String path) {
        if (Build.VERSION.SDK_INT < 29 || path.equals("Download") || path.startsWith("Download/")
                || path.equals("Documents") || path.startsWith("Documents/")) return path;
        int slash = path.indexOf('/');
        return "Download" + (slash < 0 ? "/TikTok" : path.substring(slash));
    }

    void save(Context context, MediaFileWriter.Saved video, String path) throws IOException {
        File temporary = MediaCache.createTempFile(context, "video-details-", extension());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write((json ? json() : text()).getBytes(StandardCharsets.UTF_8));
            }
            int dot = video.name.lastIndexOf('.');
            String stem = dot > 0 ? video.name.substring(0, dot) : video.name;
            MediaFileWriter.publish(context, temporary, stem + extension(),
                    json ? "application/json" : "text/plain", path, false);
        } finally {
            if (!MediaCache.delete(temporary)) Logger.printInfo(() -> "Could not remove the details temporary file");
        }
    }

    private static String quote(String value) {
        if (value.isEmpty()) return "null";
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': quoted.append("\\\""); break;
                case '\\': quoted.append("\\\\"); break;
                case '\n': quoted.append("\\n"); break;
                case '\r': quoted.append("\\r"); break;
                case '\t': quoted.append("\\t"); break;
                default:
                    if (character < 0x20 || character == ' ' || character == ' ') {
                        quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
            }
        }
        return quoted.append('"').toString();
    }

    private static String value(String value, int limit) {
        if (value == null) return "";
        int end = Math.min(value.length(), limit);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).replace('\u0000', ' ');
    }
}
