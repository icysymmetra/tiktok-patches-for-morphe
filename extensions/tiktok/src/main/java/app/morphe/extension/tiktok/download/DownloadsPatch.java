/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/download/DownloadsPatch.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/download/DownloadsPatch.java
 */

package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.base.model.UrlModel;
import com.ss.android.ugc.aweme.feed.model.Video;

import java.lang.reflect.Field;
import java.util.List;

@SuppressWarnings("unused")
public class DownloadsPatch {
    private static volatile String lastLoggedPath;
    private static volatile Boolean lastLoggedRemoveWatermark;
    private static volatile String lastLoggedCleanSourceSignature;

    public static String getVideoDownloadPath() {
        return getDownloadPath(Settings.DOWNLOAD_VIDEO_PATH.get(), DownloadDestination.Kind.VIDEO);
    }

    public static String getPhotoDownloadPath() {
        return getDownloadPath(Settings.DOWNLOAD_PHOTO_PATH.get(), DownloadDestination.Kind.PHOTO);
    }

    public static String getMediaDownloadPath(boolean video) {
        return video ? getVideoDownloadPath() : getPhotoDownloadPath();
    }

    private static String getDownloadPath(String configuredPath, DownloadDestination.Kind kind) {
        String path = DownloadDestination.resolve(configuredPath, kind);
        if (BaseSettings.DEBUG.get() && (lastLoggedPath == null || !lastLoggedPath.equals(path))) {
            lastLoggedPath = path;
            Logger.printInfo(() -> "[Morphe Downloads] " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + "_path=\"" + path + "\"");
        }
        return path;
    }

    public static android.net.Uri getVideoCollectionUri() {
        String path = getVideoDownloadPath();
        return DownloadDestination.collectionUri(path, true);
    }

    public static android.net.Uri getPhotoCollectionUri() {
        String path = getPhotoDownloadPath();
        return DownloadDestination.collectionUri(path, false);
    }

    public static boolean shouldRemoveWatermark() {
        boolean removeWatermark = Settings.REMOVE_DOWNLOAD_WATERMARK.get();
        if (BaseSettings.DEBUG.get() && (lastLoggedRemoveWatermark == null || lastLoggedRemoveWatermark != removeWatermark)) {
            lastLoggedRemoveWatermark = removeWatermark;
            Logger.printInfo(() -> "[Morphe Downloads] remove_watermark=" + removeWatermark);
        }
        return removeWatermark;
    }

    public static void patchVideoObject(Video video) {
        if (video == null) return;

        try {
            UrlModel original = video.downloadNoWatermarkAddr;
            if (hasUsableUrl(original)) {
                return;
            }

            Candidate fallback = selectCleanFallback(video);
            if (fallback == null) {
                return;
            }

            video.downloadNoWatermarkAddr = fallback.model;

            if (BaseSettings.DEBUG.get()) {
                String originalSummary = describeUrlModel(original);
                String selectedSummary = describeUrlModel(fallback.model);
                String source = fallback.name;
                String signature = source + '|' + originalSummary + '|' + selectedSummary;
                if (!signature.equals(lastLoggedCleanSourceSignature)) {
                    lastLoggedCleanSourceSignature = signature;
                    Logger.printInfo(() -> "[Morphe Downloads] selected missing-address fallback"
                            + " original=" + originalSummary
                            + " source=" + source
                            + " replacement=" + selectedSummary);
                }
            }
        } catch (Throwable ex) {
            if (BaseSettings.DEBUG.get()) {
                Logger.printException(() -> "[Morphe Downloads] patchVideoObject failure", ex);
            }
        }
    }

    /**
     * The two play addresses, by field. 46.9.3 renamed both to ...Value, so reading the old names
     * threw NoSuchFieldError there and this fallback silently never ran. The getters exist on every
     * build but rewrite the models' codec type, ratio and expiry as a side effect, so the fields
     * are read, the newer name first because older builds never declare it.
     */
    private static final String[] H264_PLAY_ADDR_FIELDS = {"h264PlayAddrValue", "h264PlayAddr"};
    private static final String[] PLAY_ADDR_FIELDS = {"playAddrValue", "playAddr"};

    private static UrlModel playAddress(Video video, String[] names) {
        Field field = Reflect.firstField(video.getClass(), names);
        if (field == null) return null;
        try {
            Object value = field.get(video);
            return value instanceof UrlModel ? (UrlModel) value : null;
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private static Candidate selectCleanFallback(Video video) {
        Candidate[] candidates = {
                new Candidate("h264PlayAddr", playAddress(video, H264_PLAY_ADDR_FIELDS)),
                new Candidate("playAddr", playAddress(video, PLAY_ADDR_FIELDS)),
        };

        for (Candidate candidate : candidates) {
            if (candidate.usable) {
                return candidate;
            }
        }

        return null;
    }

    private static boolean hasUsableUrl(UrlModel model) {
        List<String> urls = getUrlListSafe(model);
        if (urls == null || urls.isEmpty()) {
            return false;
        }

        for (String url : urls) {
            if (url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim())) {
                return true;
            }
        }

        return false;
    }

    private static String describeUrlModel(UrlModel model) {
        if (model == null) {
            return "null";
        }

        List<String> urls = getUrlListSafe(model);
        int urlCount = urls == null ? -1 : urls.size();
        return "{class=" + model.getClass().getName()
                + ",uri=" + getUriSafe(model)
                + ",urlKey=" + getUrlKeySafe(model)
                + ",size=" + getSizeSafe(model)
                + ",urlCount=" + urlCount
                + ",firstUrl=" + redactUrl(firstUrl(urls)) + "}";
    }

    private static List<String> getUrlListSafe(UrlModel model) {
        try {
            return model.getUrlList();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getUriSafe(UrlModel model) {
        try {
            return model.getUri();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getUrlKeySafe(UrlModel model) {
        try {
            return model.getUrlKey();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long getSizeSafe(UrlModel model) {
        try {
            return model.getSize();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String firstUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }

        return urls.get(0);
    }

    private static String redactUrl(String url) {
        if (url == null) {
            return null;
        }

        int queryIndex = url.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        return withoutQuery.length() <= 96 ? withoutQuery : withoutQuery.substring(0, 96) + "...";
    }

    private static final class Candidate {
        final String name;
        final UrlModel model;
        final boolean usable;

        Candidate(String name, UrlModel model) {
            this.name = name;
            this.model = model;
            this.usable = hasUsableUrl(model);
        }
    }
}
