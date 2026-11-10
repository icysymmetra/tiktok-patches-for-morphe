/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which layer owns a name that is already taken.
 *
 * <p>Not this one. What is formatted here is a wish: the name the download would like to be
 * published under. It is worked out beside TikTok's private staging file, which lives in the
 * app's own cache and has nothing in common with the folder the file ends up in, so asking
 * whether a name is free at this point is asking about the wrong directory. It could only ever
 * answer no when it should have said yes.
 *
 * <p>The destination settles it, and does so without a race. On Android 10 and later
 * {@code MediaFileWriter} hands the name to the provider, which appends its own suffix and
 * reports the name it actually used. Below that, {@code MediaFileWriter.claim} creates the file
 * in the destination folder and steps to _2, _3 until {@code createNewFile} succeeds, so two
 * saves running at once cannot land on one name. A probe here would have been a guess in front
 * of both.
 */
public final class DownloadFilenameFormatter {
    private static final int MAX_BASENAME_LENGTH = 160;
    /** What is left of a 255 byte filename once an extension and a collision suffix fit too. */
    private static final int MAX_BASENAME_BYTES = 200;
    private static final int MAX_EXTENSION_LENGTH = 12;
    private static final long PENDING_NAME_TTL_MS = 10 * 60 * 1000L;
    private static final Map<String, PendingName> PENDING_NAMES = new LinkedHashMap<String, PendingName>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PendingName> eldest) {
            return size() > 64;
        }
    };
    private static final Map<String, PhotoSequence> PHOTO_SEQUENCES = new LinkedHashMap<String, PhotoSequence>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PhotoSequence> eldest) {
            return size() > 64;
        }
    };

    private DownloadFilenameFormatter() {
    }

    public static void registerDownloadedMediaName(String originalPath, Object aweme) {
        if (originalPath == null || originalPath.trim().isEmpty() || aweme == null) {
            return;
        }

        try {
            File original = new File(originalPath);
            if (!original.isFile()) {
                return;
            }

            String extension = extensionOf(original.getName());
            boolean photo = isPhotoAweme(aweme) || isImageExtension(extension);
            String template = photo
                    ? Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get()
                    : Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.get();

            String aid = firstNonBlank(invokeString(aweme, "getAid"), readStringField(aweme, "aid"), "unknown");
            Object author = firstNonNull(invoke(aweme, "getAuthor"), readField(aweme, "author"));
            String creator = firstNonBlank(
                    invokeString(author, "getUniqueId"),
                    readStringField(author, "uniqueId"),
                    invokeString(author, "getNickname"),
                    readStringField(author, "nickname"),
                    invokeString(author, "getUid"),
                    readStringField(author, "uid"),
                    "unknown"
            );
            long createdAt = readCreateTime(aweme);
            int index = photo ? nextPhotoIndex(aweme, aid, System.currentTimeMillis()) : 1;
            Planned planned = planTikTokSave(template, original.getName(), extension, creator, aid, createdAt, index);
            String folder = planned.folder;
            if (planned.name.equals(original.getName()) && folder.isEmpty()) {
                return;
            }
            synchronized (PENDING_NAMES) {
                PENDING_NAMES.put(original.getName(), new PendingName(planned.name, folder, System.currentTimeMillis()));
            }
            debug("prepared type=" + (photo ? "photo" : "video") + (folder.isEmpty() ? "" : " in a creator folder"));
        } catch (Throwable ex) {
            if (BaseSettings.DEBUG.get()) {
                Logger.printException(() -> "[Morphe Downloads] filename formatting failed", ex);
            }
        }
    }

    public static String resolveDestinationName(String originalName) {
        return resolveDestinationName(originalName, false);
    }

    public static String consumeDestinationName(String originalName) {
        return resolveDestinationName(originalName, true);
    }

    public static String getVideoDestination(String originalName) {
        return getMediaDestination(originalName, true);
    }

    public static String getPhotoDestination(String originalName) {
        return getMediaDestination(originalName, false);
    }

    /** Read before consuming the staging-name entry; the native method keeps its own path. */
    public static String getMediaDestination(String originalName, boolean video) {
        String root = video ? DownloadsPatch.getVideoDownloadPath() : DownloadsPatch.getPhotoDownloadPath();
        synchronized (PENDING_NAMES) {
            PendingName pending = PENDING_NAMES.get(originalName);
            if (pending == null) return root;
            if (System.currentTimeMillis() - pending.createdAt > PENDING_NAME_TTL_MS) {
                PENDING_NAMES.remove(originalName);
                return root;
            }
            return pending.folder.isEmpty() ? root : root + "/" + pending.folder;
        }
    }

    static String destinationPath(Object aweme, boolean photo) {
        String root = photo ? DownloadsPatch.getPhotoDownloadPath() : DownloadsPatch.getVideoDownloadPath();
        String template = photo ? Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get() : Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.get();
        if (!hasCreatorFolder(template)) return root;
        Object author = firstNonNull(invoke(aweme, "getAuthor"), readField(aweme, "author"));
        String creator = firstNonBlank(invokeString(author, "getUniqueId"), readStringField(author, "uniqueId"),
                invokeString(author, "getNickname"), readStringField(author, "nickname"),
                invokeString(author, "getUid"), readStringField(author, "uid"), "unknown");
        return root + "/" + creatorFolder(creator);
    }

    /** A name and the creator folder in front of it, empty when the template asks for none. */
    static final class Planned {
        final String folder;
        final String name;

        Planned(String folder, String name) {
            this.folder = folder;
            this.name = name;
        }
    }

    /**
     * What TikTok's own downloader saves a staging file named {@code originalName} as. An empty
     * template keeps TikTok's name, and {@code {original}} is that name without its extension.
     */
    static Planned planTikTokSave(String template, String originalName, String extension, String creator,
                                  String aid, long createdAt, int index) {
        String folder = hasCreatorFolder(template) ? creatorFolder(creator) : "";
        File original = new File(originalName);
        File target = resolveTarget(
                original,
                filenameTemplate(template),
                extension,
                sanitizeToken(creator),
                formatDate(createdAt),
                sanitizeToken(aid),
                null,
                index
        );
        return new Planned(folder, target.getName());
    }

    static boolean hasCreatorFolder(String template) {
        return template != null && template.trim().replace('\\', '/').startsWith("{creator}/");
    }

    private static String filenameTemplate(String template) {
        return hasCreatorFolder(template) ? template.trim().substring("{creator}/".length()) : template;
    }

    static String creatorFolder(String creator) {
        String folder = trimToLength(sanitizeToken(creator), MAX_BASENAME_LENGTH);
        return folder.isEmpty() ? "unknown" : folder;
    }

    private static String resolveDestinationName(String originalName, boolean consume) {
        if (originalName == null || originalName.trim().isEmpty()) return originalName;
        synchronized (PENDING_NAMES) {
            PendingName pending = PENDING_NAMES.get(originalName);
            if (pending == null) return originalName;
            if (System.currentTimeMillis() - pending.createdAt > PENDING_NAME_TTL_MS) {
                PENDING_NAMES.remove(originalName);
                return originalName;
            }
            if (consume) {
                PENDING_NAMES.remove(originalName);
            }
            return pending.name;
        }
    }

    public static String formatCommentMediaName(String extension, String mediaId) {
        return formatCommentMediaName(Settings.DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE.get(), extension, mediaId,
                System.currentTimeMillis());
    }

    static String formatCommentMediaName(String template, String extension, String mediaId, long now) {
        String normalizedExtension = sanitizeExtension(extension);
        File placeholder = new File(".", "comment." + normalizedExtension);
        File result = resolveTarget(
                placeholder,
                template,
                normalizedExtension,
                null,
                formatDate(now),
                null,
                sanitizeToken(mediaId),
                1
        );
        return result.getName();
    }

    static String formatOriginalPhotoName(Object aweme, int index, String extension) {
        return formatSourceName(aweme, index, extension, true);
    }

    static String formatSelectedVideoName(Object aweme) {
        return formatSourceName(aweme, 1, "mp4", false);
    }

    /** The sound takes the video's own name, so the pair sorts together. */
    static String formatSelectedAudioName(Object aweme) {
        return formatSelectedAudioName(aweme, "m4a");
    }

    /**
     * The same name for a sound whose container is only known once its bytes arrive. The muxed
     * track beside a video is always m4a; a sound fetched from its own address is not.
     */
    static String formatSelectedAudioName(Object aweme, String extension) {
        return formatSourceName(aweme, 1, extension, false);
    }

    /**
     * The original sound is named after itself. The video template's tokens are all about the
     * post, and the same sound saved from two posts should be the same file, not two.
     *
     * <p>The extension comes from the fetched bytes rather than from here, because TikTok serves
     * a sound entry in more than one container.
     */
    static String formatSoundName(String title, String extension) {
        String base = trimToLength(sanitizeBaseName(sanitizeToken(title)), MAX_BASENAME_LENGTH);
        if (base.isEmpty()) base = "sound";
        return base + "." + sanitizeExtension(extension);
    }

    /**
     * A profile picture is named after the account rather than by the video template, whose
     * tokens have nothing to fill them here.
     */
    static String formatProfilePictureName(String handle) {
        String base = trimToLength(sanitizeBaseName(sanitizeToken(handle)), MAX_BASENAME_LENGTH);
        if (base.isEmpty()) base = "profile";
        return base + "_profile.jpg";
    }

    /**
     * A frame takes its video's own name and where in the video it was, so frames from one post
     * sort together and beside the video itself: {@code <video name>_frame_1m05s.jpg}. A frame
     * with no post to name it after (a LIVE, an ad) is named for when it was saved.
     *
     * @param positionMs where the video was, or a negative number when that isn't known
     */
    static String formatFrameName(Object aweme, long positionMs) {
        String at = positionMs >= 0 ? "_" + frameTime(positionMs) : "";
        if (aweme == null) {
            return "frame_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + at + ".jpg";
        }
        String video = stripExtension(formatSourceName(aweme, 1, "jpg", false));
        String suffix = "frame" + at;
        // Cut from the video's name, never from the suffix: the time is what tells frames apart.
        return boundTemplatedName(sanitizeBaseName(video + "_" + suffix), MAX_BASENAME_LENGTH, suffix) + ".jpg";
    }

    /**
     * A cover takes its video's own name, so it sorts beside the video and its frames:
     * {@code <video name>_cover.<extension>}. The extension is the one the fetched bytes end up as.
     */
    static String formatCoverName(Object aweme, String extension) {
        String video = stripExtension(formatSourceName(aweme, 1, "jpg", false));
        return boundTemplatedName(sanitizeBaseName(video + "_cover"), MAX_BASENAME_LENGTH, "cover")
                + "." + sanitizeExtension(extension);
    }

    /** A position as minutes and two-digit seconds, 65,400 ms as 1m05s. */
    static String frameTime(long positionMs) {
        long seconds = Math.max(0, positionMs) / 1000;
        return (seconds / 60) + "m" + String.format(Locale.US, "%02d", seconds % 60) + "s";
    }

    private static String formatSourceName(Object aweme, int index, String extension, boolean photo) {
        Object author = invoke(aweme, "getAuthor");
        String creator = firstNonBlank(invokeString(author, "getUniqueId"), invokeString(author, "getNickname"), "unknown");
        String id = firstNonBlank(invokeString(aweme, "getAid"), "unknown");
        return formatSourceName(
                photo ? Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get() : Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.get(),
                creator, id, readCreateTime(aweme), index, extension, photo);
    }

    static String formatSourceName(String rawTemplate, String creator, String id, long createdAt, int index,
                                   String extension, boolean photo) {
        String template = filenameTemplate(rawTemplate);
        if (template == null || template.trim().isEmpty()) template = "{creator}_{date}_{video_id}" + (photo ? "_{index}" : "");
        String base = template.replace("{creator}", sanitizeToken(creator))
                .replace("{date}", formatDate(createdAt))
                .replace("{video_id}", sanitizeToken(id)).replace("{index}", String.valueOf(index));
        if (photo && !template.contains("{index}")) base += "_" + index;
        // Every photo of a slideshow comes through here with its own number, so that number has
        // to survive the length cap or they all end up named the same.
        boolean carriesOrdinal = photo || template.contains("{index}");
        String sanitized = sanitizeBaseName(base);
        base = carriesOrdinal
                ? boundTemplatedName(sanitized, MAX_BASENAME_LENGTH, String.valueOf(index))
                : trimToLength(sanitized, MAX_BASENAME_LENGTH);
        if (base.isEmpty()) base = photo ? "original_photo_" + index : "video";
        return base + "." + sanitizeExtension(extension);
    }

    private static File resolveTarget(
            File original,
            String template,
            String extension,
            String creator,
            String date,
            String videoId,
            String mediaId,
            int index
    ) {
        String source = template == null ? "" : template.trim();
        if (source.isEmpty()) {
            return original;
        }

        String originalBase = stripExtension(original.getName());
        boolean hasIndexToken = source.contains("{index}");
        String counter = String.valueOf(Math.max(1, index));
        String base = source
                .replace("{creator}", safeToken(creator))
                .replace("{date}", safeToken(date))
                .replace("{video_id}", safeToken(videoId))
                .replace("{media_id}", safeToken(mediaId))
                .replace("{index}", counter)
                .replace("{original}", sanitizeToken(originalBase));
        base = sanitizeBaseName(base);
        if (base.isEmpty()) {
            return original;
        }

        String boundedBase = hasIndexToken
                ? boundTemplatedName(base, MAX_BASENAME_LENGTH, counter)
                : trimToLength(base, MAX_BASENAME_LENGTH, MAX_BASENAME_BYTES);
        return new File(original.getParentFile(), boundedBase + "." + sanitizeExtension(extension));
    }

    /**
     * TikTok's save callback supplies one staging path and the post, but no photo position. The
     * callbacks share the post id, so they take consecutive numbers until the post's image count
     * is reached. Completing the batch removes its state and makes a later save start at one.
     */
    private static int nextPhotoIndex(Object aweme, String aid, long now) {
        int count = photoCount(aweme);
        if (count <= 1) return 1;
        String key = "unknown".equals(aid)
                ? aweme.getClass().getName() + '@' + System.identityHashCode(aweme)
                : aid;
        synchronized (PENDING_NAMES) {
            PhotoSequence sequence = PHOTO_SEQUENCES.get(key);
            if (sequence == null || sequence.count != count
                    || now - sequence.updatedAt > PENDING_NAME_TTL_MS
                    || sequence.nextIndex > count) {
                sequence = new PhotoSequence(count, now);
                PHOTO_SEQUENCES.put(key, sequence);
            }
            int index = sequence.nextIndex++;
            sequence.updatedAt = now;
            if (index >= count) PHOTO_SEQUENCES.remove(key);
            return index;
        }
    }

    private static int photoCount(Object aweme) {
        Object direct = firstNonNull(invoke(aweme, "getImageInfos"), readField(aweme, "imageInfos"));
        if (direct instanceof List && !((List<?>) direct).isEmpty()) return ((List<?>) direct).size();
        Object info = firstNonNull(
                invoke(aweme, "getPhotoModeImageInfo"),
                readField(aweme, "photoModeImageInfo"));
        Object images = firstNonNull(invoke(info, "getImageList"), readField(info, "imageList"));
        return images instanceof List ? ((List<?>) images).size() : 0;
    }

    /**
     * Keeps a filled-in template inside the length limits without losing its counter. A creator
     * name long enough to reach the cut would otherwise take {@code index} with it, leaving every
     * photo of a slideshow with one name and the collision search with nothing to advance, so a
     * name that had to be shortened carries the counter on its end instead.
     */
    private static String boundTemplatedName(String base, int limit, String counter) {
        int room = Math.max(1, limit);
        String bounded = trimToLength(base, room);
        if (bounded.equals(base)) {
            return bounded;
        }

        int reserved = counter.length() + 1;
        String stem = trimToLength(base, Math.max(1, room - reserved), Math.max(1, MAX_BASENAME_BYTES - reserved));
        return sanitizeBaseName(stem + "_" + counter);
    }

    private static boolean isPhotoAweme(Object aweme) {
        Object imageInfos = invoke(aweme, "getImageInfos");
        if (imageInfos instanceof List && !((List<?>) imageInfos).isEmpty()) {
            return true;
        }
        return invoke(aweme, "getPhotoModeImageInfo") != null || readField(aweme, "photoModeImageInfo") != null;
    }

    private static boolean isImageExtension(String extension) {
        String value = extension.toLowerCase(Locale.ROOT);
        return value.equals("jpg") || value.equals("jpeg") || value.equals("png")
                || value.equals("webp") || value.equals("gif") || value.equals("heic") || value.equals("heif");
    }

    private static long readCreateTime(Object aweme) {
        Object value = firstNonNull(invoke(aweme, "getCreateTime"), readField(aweme, "createTime"));
        if (!(value instanceof Number)) {
            return System.currentTimeMillis();
        }
        long timestamp = ((Number) value).longValue();
        return timestamp > 0 && timestamp < 100_000_000_000L ? timestamp * 1000L : timestamp;
    }

    private static String formatDate(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(timestamp));
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String invokeString(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value instanceof String ? (String) value : null;
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static String readStringField(Object target, String fieldName) {
        Object value = readField(target, fieldName);
        return value instanceof String ? (String) value : null;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String safeToken(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }

    private static String sanitizeToken(String value) {
        return sanitizeBaseName(value == null ? "" : value);
    }

    private static String sanitizeBaseName(String value) {
        String cleaned = value.replace('/', '_').replace('\\', '_');
        cleaned = cleaned.replaceAll("[\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        // Indices, then one cut: a substring per stripped character copied the rest each time,
        // and a restored template of a few thousand dots stalled the preview on the main thread.
        int start = 0;
        int end = cleaned.length();
        while (start < end && cleaned.charAt(start) == '.') start++;
        while (end > start && (cleaned.charAt(end - 1) == '.' || cleaned.charAt(end - 1) == ' ')) end--;
        return cleaned.substring(start, end);
    }

    private static String sanitizeExtension(String extension) {
        String cleaned = extension == null ? "bin" : extension.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) return "bin";
        // An extension comes from the name the host handed over, so it is as arbitrary as the
        // rest of it. Nothing real is longer than this, and the byte budget assumes a short one.
        return cleaned.length() > MAX_EXTENSION_LENGTH ? cleaned.substring(0, MAX_EXTENSION_LENGTH) : cleaned;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "mp4";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Both bounds a filename has to stay inside. The character count is what the templates
     * are written against; the byte count is what the filesystem enforces, and 160 characters
     * of Japanese is 480 bytes of UTF-8, well past the 255 ext4 and f2fs allow. The cut lands
     * on a code point rather than between the halves of a surrogate pair.
     */
    private static String trimToLength(String value, int maxLength) {
        return trimToLength(value, maxLength, MAX_BASENAME_BYTES);
    }

    private static String trimToLength(String value, int maxLength, int maxBytes) {
        String trimmed = value;
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, maxLength));
        }
        while (!trimmed.isEmpty() && trimmed.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(trimmed.length(), -1));
        }
        return trimmed.trim();
    }

    private static void debug(String message) {
        if (BaseSettings.DEBUG.get()) {
            Logger.printInfo(() -> "[Morphe Downloads] " + message);
        }
    }

    private static final class PendingName {
        final String name;
        final String folder;
        final long createdAt;

        PendingName(String name, String folder, long createdAt) {
            this.name = name;
            this.folder = folder;
            this.createdAt = createdAt;
        }
    }

    private static final class PhotoSequence {
        final int count;
        int nextIndex = 1;
        long updatedAt;

        PhotoSequence(int count, long updatedAt) {
            this.count = count;
            this.updatedAt = updatedAt;
        }
    }
}
