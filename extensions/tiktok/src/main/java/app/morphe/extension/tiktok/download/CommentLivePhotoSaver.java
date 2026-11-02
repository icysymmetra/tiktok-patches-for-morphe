/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The motion clip of a live photo in a comment.
 *
 * <p>TikTok's own Save photo on a comment hands its downloader the tapped photo's still, one
 * UrlModel, and nothing else: the clip a live photo carries in its livePhotoInfoModel never
 * lands (upstream #169). The patch calls in here as that save begins, once TikTok has the
 * comment and the photo's index in hand. A still is left to TikTok; a live photo gets its clip
 * fetched through the same transport every other save uses and published beside the still as
 * an MP4, with a banner of its own.
 */
public final class CommentLivePhotoSaver {
    private static final String HOOK_FAMILY = "comment live photo";

    /** The photos whose clip is on its way, so a second tap waits rather than fetching twice. */
    private static final Set<String> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private CommentLivePhotoSaver() {
    }

    /** A build that saves a comment's first photo with no index (46.2.3 did). */
    public static void saveClip(Object comment) {
        saveClip(comment, 0);
    }

    /** Called as TikTok saves the photo at {@code index} of {@code comment}'s images. */
    public static void saveClip(Object comment, int index) {
        try {
            if (!SettingsStatus.advancedDownloadsEnabled) return;
            List<String> urls = clipUrls(comment, index);
            HookStatus.bound(HOOK_FAMILY, urls.isEmpty() ? "still only" : "clip found");
            if (urls.isEmpty()) return;
            Context context = Utils.getContext();
            if (context == null) return;
            // Android 6 to 9 write a real file, so without the permission the clip fails after
            // the fetch. TikTok asks for the same permission for its still right after this
            // call, so the next save of the photo has it.
            if (android.os.Build.VERSION.SDK_INT < 29
                    && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Utils.showToastLong(L10n.t("Allow storage for TikTok in Android settings to save the live photo's clip"));
                return;
            }
            String id = Reflect.string(comment, "getCid", "cid");
            String key = (id == null || id.isEmpty() ? "comment" : id) + '#' + index;
            String jobKey = "comment clip " + key;
            if (!ACTIVE.add(key)) {
                Utils.showToastShort(MediaJobScheduler.busyMessage(jobKey));
                return;
            }
            Context app = context.getApplicationContext();
            MediaJobScheduler.Job job = MediaJobScheduler.submit("comment live photo", jobKey, () -> {
                File temp = null;
                try {
                    MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
                    temp = MediaCache.createTempFile(app, "comment-live-", ".tmp");
                    RemoteMedia.fetch(urls, temp, RemoteMedia.Kind.VIDEO);
                    String name = DownloadFilenameFormatter.formatCommentMediaName(
                            "mp4", id == null || id.isEmpty() ? "live" : id + "-live");
                    String path = DownloadsPatch.getPhotoDownloadPath();
                    MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, temp, name, "video/mp4", path, true);
                    SaveNotice.saved(L10n.f("Live photo clip saved to %1$s", path), saved);
                } catch (IOException | RuntimeException failure) {
                    Logger.printException(() -> "Could not save the live photo clip", failure);
                    Utils.showToastLong(L10n.t("The live photo's clip couldn't be saved. Try again."));
                } finally {
                    if (temp != null && !MediaCache.delete(temp)) {
                        Logger.printInfo(() -> "Could not remove the live photo temporary file");
                    }
                }
            }, () -> ACTIVE.remove(key));
            // A refusal has already said the queue is full and let go of the photo.
            String saying = L10n.t("Saving the live photo's clip");
            MediaJobScheduler.acknowledge(job, saying, saying);
        } catch (Throwable failure) {
            // A build whose comment model moved: TikTok's own save of the still goes on untouched.
            HookStatus.threw(HOOK_FAMILY, "comment live photo", failure);
        }
    }

    /** The clip's addresses for the photo at {@code index}, or none for a still. */
    static List<String> clipUrls(Object comment, int index) {
        Object images = member(comment, "getImageList", "imageList");
        if (!(images instanceof List) || index < 0 || index >= ((List<?>) images).size()) {
            return Collections.emptyList();
        }
        Object image = ((List<?>) images).get(index);
        Object model = image == null ? null : member(image, "getLivePhotoInfoModel", "livePhotoInfoModel");
        Object urls = model == null ? null : member(model, "getUrlList", "urlList");
        if (!(urls instanceof List)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object url : (List<?>) urls) {
            if (url instanceof String && !((String) url).isEmpty()) result.add((String) url);
        }
        return result;
    }

    /**
     * A getter-or-field read that says so in the export when a build has neither. Without this
     * a renamed model read as "no clip" on every photo, and the family looked healthy for it.
     */
    private static Object member(Object target, String getter, String field) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        if (Reflect.method(type, getter) == null && Reflect.field(type, field) == null) {
            HookStatus.missingMember(HOOK_FAMILY, "member", type.getName(), getter + " or " + field);
            return null;
        }
        return Reflect.property(target, getter, field);
    }

    /** Whether a clip is on its way for a photo; for the tests that drive a save. */
    static boolean activeForTests(String commentId, int index) {
        return ACTIVE.contains(commentId + '#' + index);
    }
}
