/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.download;

import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import app.morphe.extension.tiktok.settings.L10n;

import java.util.Locale;

public final class DownloadDestination {
    public enum Kind {
        VIDEO,
        PHOTO,
        STICKER
    }

    public static final String DEFAULT_PATH = "DCIM/TikTok";

    private DownloadDestination() {
    }

    public static String validate(String path, Kind kind) {
        String normalized = normalize(path);
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        L10n.t("That folder name has a character Android doesn't allow. Use letters, numbers, spaces or dashes."));
            }
        }

        String root = canonicalRoot(segments[0]);
        if (!isAllowedRoot(root, kind)) {
            throw new IllegalArgumentException(L10n.f(
                    "%1$s destinations must start with %2$s",
                    kindLabel(kind), allowedRoots(kind)));
        }

        segments[0] = root;
        return join(segments);
    }

    public static String resolve(String path, Kind kind) {
        try {
            return validate(path, kind);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_PATH;
        }
    }

    /** The folders each kind may go under, as Android names them. */
    private static final String[] VIDEO_ROOTS = {"DCIM", "Movies", "Download", "Documents"};
    private static final String[] PHOTO_ROOTS = {"DCIM", "Pictures", "Download", "Documents"};
    private static final String[] STICKER_ROOTS = {"DCIM", "Download", "Documents"};

    public static String allowedRoots(Kind kind) {
        switch (kind) {
            // One key per list shape, so the language writes the whole list: the names are
            // what Android calls those folders and stay as they are, and the word between the
            // last two used to be a key on its own, which fixed the shape to English.
            case VIDEO:
                return L10n.f("%1$s, %2$s, %3$s or %4$s", (Object[]) VIDEO_ROOTS);
            case PHOTO:
                return L10n.f("%1$s, %2$s, %3$s or %4$s", (Object[]) PHOTO_ROOTS);
            case STICKER:
                return L10n.f("%1$s, %2$s or %3$s", (Object[]) STICKER_ROOTS);
            default:
                throw new IllegalArgumentException("Unknown destination kind");
        }
    }

    public static Uri collectionUri(String path, boolean video) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return video
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }

        String root = firstSegment(path);
        if ("Download".equals(root) || "Documents".equals(root)) {
            return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }

        return video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    private static String normalize(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        String storagePrefix = "/storage/emulated/0/";
        if (normalized.startsWith(storagePrefix)) {
            normalized = normalized.substring(storagePrefix.length());
        }
        // One pass for each step: trimming a slash at a time copied the rest of the path per
        // slash, which a restored folder of slashes turned into seconds on the main thread.
        normalized = normalized.replaceAll("/{2,}", "/");
        int start = normalized.startsWith("/") ? 1 : 0;
        int end = normalized.endsWith("/") ? normalized.length() - 1 : normalized.length();
        normalized = start < end ? normalized.substring(start, end) : "";
        if (normalized.isEmpty()) {
            return DEFAULT_PATH;
        }
        return normalized;
    }

    private static String canonicalRoot(String value) {
        String root = value.toLowerCase(Locale.ROOT);
        switch (root) {
            case "dcim":
                return "DCIM";
            case "movie":
            case "movies":
            case "video":
            case "videos":
                return "Movies";
            case "picture":
            case "pictures":
            case "photo":
            case "photos":
                return "Pictures";
            case "download":
            case "downloads":
                return "Download";
            case "document":
            case "documents":
                return "Documents";
            default:
                return value;
        }
    }

    private static boolean isAllowedRoot(String root, Kind kind) {
        if ("DCIM".equals(root) || "Download".equals(root) || "Documents".equals(root)) {
            return true;
        }
        if (kind == Kind.VIDEO) {
            return "Movies".equals(root);
        }
        if (kind == Kind.PHOTO) {
            return "Pictures".equals(root);
        }
        return false;
    }

    private static String firstSegment(String path) {
        String normalized = normalize(path);
        int slash = normalized.indexOf('/');
        return canonicalRoot(slash < 0 ? normalized : normalized.substring(0, slash));
    }

    private static String kindLabel(Kind kind) {
        switch (kind) {
            case VIDEO:
                return L10n.t("Video");
            case PHOTO:
                return L10n.t("Photo");
            case STICKER:
                return L10n.t("Sticker");
            default:
                return L10n.t("Media");
        }
    }

    private static String join(String[] segments) {
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (result.length() > 0) result.append('/');
            result.append(segment);
        }
        return result.toString();
    }
}
