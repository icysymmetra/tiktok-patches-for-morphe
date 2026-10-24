/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.os.Build;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SubtitleDownloads {
    static final class Track {
        final String language, format;
        final List<String> urls;
        final boolean original;
        Track(String language, String format, List<String> urls, boolean original) {
            this.language = language; this.format = format; this.urls = urls; this.original = original;
        }
    }

    static List<Track> tracks(Object video, String choice, Locale locale) {
        Object model = Reflect.property(video, "getCaptionModel", "captionModel");
        Object list = Reflect.readField(model, "captionList");
        if (!(list instanceof List<?>)) return Collections.emptyList();
        LinkedHashMap<String, Track> languages = new LinkedHashMap<>();
        Set<String> filenames = new HashSet<>();
        for (Object caption : (List<?>) list) {
            String format = Reflect.string(caption, "getFormat", "format");
            if (format == null || !List.of("srt", "webvtt", "vtt", "creator_caption", "json").contains(format)) continue;
            List<String> urls = new ArrayList<>();
            String url = Reflect.string(caption, "getUrl", "url");
            if (MediaTransport.hasAllowedShape(url)) urls.add(url);
            Object mirrors = Reflect.readField(caption, "urlList");
            if (mirrors instanceof List<?>) for (Object mirror : (List<?>) mirrors) {
                if (mirror instanceof String
                        && MediaTransport.hasAllowedShape((String) mirror)
                        && !urls.contains(mirror)) {
                    urls.add((String) mirror);
                }
            }
            if (urls.isEmpty()) continue;
            String label = Reflect.firstNonBlank(Reflect.string(caption, "getLanguageCode", "languageCode"),
                    Reflect.string(caption, "getLanguageName", "languageName"));
            label = label == null ? "" : label.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            String identity = label.isEmpty() ? "url:" + urls.get(0) : label;
            Track old = languages.get(identity);
            String language = old == null ? label.replaceAll("[^\\p{L}\\p{N}-]", "") : old.language;
            if (old == null) {
                if (isBlankLanguage(language)) language = "und";
                String stem = language.substring(0, language.offsetByCodePoints(0, Math.min(48, language.codePointCount(0, language.length()))));
                language = stem;
                for (int suffix = 2; !filenames.add(language); suffix++) language = stem + "-" + suffix;
            }
            boolean original = Boolean.TRUE.equals(Reflect.property(caption, "isOriginalCaption", "isOriginalCaption"));
            if (old == null || priority(format) < priority(old.format)) languages.put(identity, new Track(language, format, urls, original || (old != null && old.original)));
            else if (original && !old.original) languages.put(identity, new Track(language, old.format, old.urls, true));
        }
        List<Track> result = new ArrayList<>(languages.values());
        if (result.isEmpty() || "all".equals(choice)) return result;
        if ("device".equals(choice)) {
            for (Track track : result) if (track.language.equalsIgnoreCase(locale.toLanguageTag())) return List.of(track);
            for (Track track : result) if (primaryLanguage(track.language).equals(locale.getLanguage())) return List.of(track);
        }
        for (Track track : result) if (track.original) return List.of(track);
        return List.of(result.get(0));
    }

    private static int priority(String format) { return "srt".equals(format) ? 0 : (format.contains("vtt") ? 1 : 2); }

    /** A language tag with nothing but separators left in it names no language. */
    private static boolean isBlankLanguage(String language) {
        for (int index = 0; index < language.length(); index++) {
            if (language.charAt(index) != '-') return false;
        }
        return true;
    }

    /**
     * The part before the first separator, which is the language itself. Not a split: Java
     * drops trailing empty parts, so splitting a tag that is all separators gives no parts at
     * all and asking for the first one throws.
     */
    private static String primaryLanguage(String language) {
        int dash = language.indexOf('-');
        return dash < 0 ? language : language.substring(0, dash);
    }

    static String pairedPath(String videoPath) {
        if (Build.VERSION.SDK_INT < 29) return videoPath;
        int slash = videoPath.indexOf('/');
        // Android 11+ restricts subtitles in MediaStore.Files to Music or Movies.
        return (Build.VERSION.SDK_INT >= 30 ? "Movies" : "Download") + (slash < 0 ? "/TikTok" : videoPath.substring(slash));
    }

    static int save(Context context, List<Track> tracks, String videoName, String path) throws MediaBudget.StopException {
        return save(context, tracks, videoName, path, MediaTransport.DEFAULT);
    }

    static int save(
            Context context,
            List<Track> tracks,
            String videoName,
            String path,
            MediaTransport.Client transport
    ) throws MediaBudget.StopException {
        int[] saved = {0};
        MediaBudget.StopException[] stopped = {null};
        MediaBudget.runWithJobDeadline(() -> {
            for (Track track : tracks) {
                try {
                    saveOne(context, track, videoName, path, transport);
                    saved[0]++;
                } catch (MediaBudget.StopException refusal) {
                    stopped[0] = refusal;
                    break;
                } catch (IOException | RuntimeException error) {
                    Logger.printException(() -> "Could not save " + track.language + " subtitles", error);
                }
            }
        });
        if (stopped[0] != null) throw stopped[0];
        return saved[0];
    }

    /** One track beside its video, named after it; throws when it could not be written. */
    static void saveOne(Context context, Track track, String videoName, String path) throws IOException {
        saveOne(context, track, videoName, path, MediaTransport.DEFAULT);
    }

    static void saveOne(
            Context context,
            Track track,
            String videoName,
            String path,
            MediaTransport.Client transport
    ) throws IOException {
        // The name comes back from the media provider, which is free to hand back one with no
        // extension. Taking the whole name then keeps the subtitle beside its video instead of
        // throwing away a save whose video is already on disk.
        // An empty name would publish the subtitle as ".en.srt", which the gallery hides and
        // which every video would then collide on, so it falls back like a missing one.
        String base = videoName == null || videoName.isEmpty() ? "video" : videoName;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        File temp = null;
        try {
            MediaBudget.check(null);
            String srt = fetch(track.urls, track.format, transport);
            MediaBudget.checkDiskSpace(context.getCacheDir(), srt.length() * 2L);
            temp = MediaCache.createTempFile(context, "subtitle-", ".srt");
            try (var output = new FileOutputStream(temp)) { output.write(srt.getBytes(StandardCharsets.UTF_8)); }
            MediaFileWriter.publish(context, temp, stem + "." + track.language + ".srt", "application/x-subrip", path, false);
        } finally {
            if (temp != null && !MediaCache.delete(temp)) Logger.printInfo(() -> "Could not remove temporary subtitle file");
        }
    }

    static String fetch(List<String> urls, String format) throws IOException {
        return fetch(urls, format, MediaTransport.DEFAULT);
    }

    static String fetch(
            List<String> urls,
            String format,
            MediaTransport.Client transport
    ) throws IOException {
        IOException last = new IOException("No subtitle URL");
        MediaBudget.Deadline deadline = MediaBudget.deadline();
        for (String url : urls) {
            for (int attempt = 0; attempt < MediaBudget.MAX_ATTEMPTS_PER_MIRROR; attempt++) {
                try (MediaTransport.Response response = transport.open(
                        url, deadline, 15000, 30000, null, true)) {
                    MediaBudget.check(deadline);
                    int responseCode = response.statusCode;
                    if (MediaBudget.isTransientStatus(responseCode)
                            || (responseCode >= 300 && response.header("Retry-After") != null)) {
                        String retryAfter = response.header("Retry-After");
                        response.close();
                        MediaBudget.waitBeforeRetry(retryAfter, attempt, deadline);
                        if (MediaBudget.isTransientStatus(responseCode)
                                && attempt + 1 < MediaBudget.MAX_ATTEMPTS_PER_MIRROR) continue;
                    }
                    if (responseCode != 200) throw new IOException("Subtitle server returned " + responseCode);
                    String lengthHeader = response.header("Content-Length");
                    if (lengthHeader != null) {
                        try {
                            if (Long.parseLong(lengthHeader.trim()) > 2 * 1024 * 1024) {
                                throw new IOException("Subtitle file is too large");
                            }
                        } catch (NumberFormatException ignored) {
                            // The streamed copy below remains the authoritative size limit.
                        }
                    }
                    try (var input = response.inputStream(); var output = new ByteArrayOutputStream()) {
                        MediaFileWriter.copy(input, output, 2 * 1024 * 1024, deadline);
                        return SubtitleFormat.toSrt(new String(output.toByteArray(), StandardCharsets.UTF_8), format);
                }
                } catch (IOException | RuntimeException error) {
                    if (error instanceof MediaBudget.StopException) {
                        throw (MediaBudget.StopException) error;
                    }
                    boolean retryable = MediaBudget.isRetryableTransport(error);
                    if (retryable && attempt + 1 < MediaBudget.MAX_ATTEMPTS_PER_MIRROR) {
                        MediaBudget.waitBeforeRetry(null, attempt, deadline);
                        continue;
                    }
                    last.addSuppressed(new IOException("Subtitle mirror failed ("
                            + error.getClass().getSimpleName() + "): "
                            + RemoteMedia.summarizeUrl(url), error));
                    break;
                }
            }
        }
        throw last;
    }
}
