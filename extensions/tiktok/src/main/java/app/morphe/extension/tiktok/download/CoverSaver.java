/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saves a video's cover, the still TikTok shows for it in grids and before it plays.
 *
 * <p>A video carries its cover in more than one size: {@code originCover} (field
 * {@code originCoverValue} on 47.x) at the video's own size, {@code cover} a smaller crop and
 * {@code lowResCover} a placeholder. Each names its width and height, so the largest one that has
 * addresses is fetched, and one that names no size loses to one that does; with no sizes at all
 * the order above decides. The animated and AI covers are other pictures and stay out. A photo
 * post has no cover of its own, only its first photo, which its own save already takes.
 */
public final class CoverSaver {
    private static final Set<String> ACTIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final String[][] SIZES = {
            {"getOriginCover", "originCoverValue"},
            {"getCover", "cover"},
            {"getLowResCover", "lowResCover"},
    };

    private CoverSaver() {
    }

    /** Whether a Download saves the cover too. Not while the share sheet has its own Save cover (#146). */
    static boolean besideEnabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.DOWNLOAD_COVER.get()
                && !Settings.SAVE_COVER_BUTTON.get();
    }

    /** Whether the share sheet shows Save cover beside Download (#146). */
    public static boolean buttonEnabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.SAVE_COVER_BUTTON.get();
    }

    /** Whether this post has a still cover to save, so a Save cover button has something to do. */
    public static boolean hasCover(Object aweme) {
        return !coverUrls(aweme).isEmpty();
    }

    /** The addresses of the largest still cover, best mirror first, or empty when there's none. */
    static List<String> coverUrls(Object aweme) {
        if (aweme == null) return List.of();
        if (Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo") != null) return List.of();
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video == null) return List.of();
        List<String> best = List.of();
        long bestArea = -1;
        for (String[] size : SIZES) {
            Object model = Reflect.property(video, size[0], size[1]);
            List<String> urls = VideoDownloads.urls(model);
            if (urls.isEmpty()) continue;
            long area = area(model);
            if (area > bestArea) {
                best = urls;
                bestArea = area;
            }
        }
        return List.copyOf(best);
    }

    /** Width times height as the address names them, or 0 when it doesn't say. */
    static long area(Object model) {
        long width = number(Reflect.property(model, "getWidth", "width"));
        long height = number(Reflect.property(model, "getHeight", "height"));
        return width > 0 && height > 0 ? width * height : 0;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /** The Long press action: saves the cover of the video on screen, or says why it can't. */
    public static void save(Context context, Object aweme) {
        start(context, aweme, true);
    }

    /**
     * With a Download, when the switch is on. It's a second small save beside the video's, so it
     * keeps quiet when there's no cover and says what happened by toast, which leaves the video's
     * own banner on screen.
     */
    static void beside(Context context, Object aweme) {
        if (besideEnabled()) start(context, aweme, false);
    }

    private static void start(Context context, Object aweme, boolean asked) {
        if (context == null) return;
        List<String> urls = coverUrls(aweme);
        if (urls.isEmpty()) {
            if (asked) Utils.showToastShort(L10n.t("This video has no cover to save"));
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (asked) Utils.showToastLong(L10n.t("Allow storage for TikTok in Android settings to save covers"));
            return;
        }
        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) return;
        String name;
        try {
            name = DownloadFilenameFormatter.formatCoverName(aweme, "jpg");
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not work out the cover name", exception);
            Utils.showToastLong(L10n.t("The cover couldn't be saved. Try again."));
            return;
        }
        Context app = context.getApplicationContext();
        String path = DownloadsPatch.getPhotoDownloadPath();
        String key = "cover " + id;
        if (!ACTIVE.add(id)) {
            if (asked) Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return;
        }
        MediaJobScheduler.Job job = MediaJobScheduler.submit("cover", key, () -> {
            File temp = null;
            try {
                MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
                temp = MediaCache.createTempFile(app, "cover-", ".tmp");
                // TikTok may send a HEIF or WebP cover, which plenty of galleries won't open.
                String extension = PhotoToJpeg.convert(app, temp, RemoteMedia.fetch(urls, temp, RemoteMedia.Kind.IMAGE));
                String mime = "jpg".equals(extension) ? "image/jpeg" : "image/" + extension;
                String saved = name.substring(0, name.lastIndexOf('.') + 1) + extension;
                MediaFileWriter.Saved landed = MediaFileWriter.publishForResult(app, temp, saved, mime, path, false);
                if (asked) SaveNotice.saved(L10n.f("Cover saved to %1$s", path), landed);
                else Utils.showToastShort(L10n.f("Cover saved to %1$s", path));
            } catch (IOException | RuntimeException exception) {
                Logger.printException(() -> "Cover download failed", exception);
                Utils.showToastLong(L10n.t("The cover couldn't be saved. Try again."));
            } finally {
                if (temp != null && !MediaCache.delete(temp)) {
                    Logger.printInfo(() -> "Could not remove cover temporary file");
                }
            }
        }, () -> ACTIVE.remove(id));
        if (asked) {
            String saying = L10n.t("Saving the cover");
            MediaJobScheduler.acknowledge(job, saying, saying);
        }
    }
}
