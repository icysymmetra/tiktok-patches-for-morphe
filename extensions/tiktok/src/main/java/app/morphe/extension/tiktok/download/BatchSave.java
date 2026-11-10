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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Several posts saved one after another as one bounded run (#27): the picks from a profile grid.
 *
 * <p>The run is one job in the media queue, so it waits its turn like any other save, and one
 * {@link SaveProgress} file-count row follows it with "Saving 3 of 12" and Cancel. Cancel lets
 * the post under way finish and leaves the rest. A post that fails is skipped and the next one
 * still saves; a refusal from the disk or the clock ends the run with its reason. The result is
 * one banner that says what came of it.
 *
 * <p>Each post is saved the way Download saves it with the chosen quality: the clean file when
 * Remove watermark is on (TikTok's own when it is off and the quality is Automatic), its sound
 * put back in when the rendition carries it separately, or left out with Save videos without
 * sound. A photo post saves every photo at full size, or as one video with Save photo posts as a
 * video on, as a single download does. With Check for already-saved videos on, a
 * video or photo post saved here before is counted and left alone, and a post this run saves is
 * remembered.
 * Details files, subtitles, tags and a caption written on the video are single-save extras and
 * aren't added in a run.
 */
final class BatchSave {
    /** The queue label, which is also the kind the start after a closed TikTok names. */
    static final String LABEL = "picked videos";

    /** What came of one post. */
    enum Result { SAVED, ALREADY_SAVED }

    /** Saves one post, or throws to skip it. The tests put a fake in its place. */
    interface Saver {
        Result save(Context app, Object post, MediaFileWriter.Saved[] last) throws IOException;
    }

    static volatile Saver saverForTests;

    private BatchSave() {
    }

    /** Queues the run. False when it couldn't start: no posts, no permission or a full queue. */
    static boolean start(Context context, List<Object> posts) {
        if (context == null || posts == null || posts.isEmpty()) return false;
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("None of the picked videos could be saved. Try again."));
            return false;
        }
        // List.copyOf is API 30; the payload's floor is 23.
        List<Object> snapshot = Collections.unmodifiableList(
                new ArrayList<>(posts.subList(0, Math.min(posts.size(), GridPicker.MAX_PICKED))));
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Saver saver = saverForTests != null ? saverForTests : BatchSave::saveOne;
        SaveProgress progress = SaveProgress.queuedFiles(snapshot.size());
        MediaJobScheduler.Job job = progress.submit(LABEL, null, snapshot.size(),
                () -> run(app, snapshot, saver, progress), null);
        return job != null;
    }

    /** The run itself, on a media worker. */
    static void run(Context app, List<Object> posts, Saver saver, SaveProgress progress) {
        int[] already = {0};
        MediaFileWriter.Saved[] last = {null};
        try {
            SaveProgress.Outcome outcome = progress.run(index -> {
                if (saver.save(app, posts.get(index), last) == Result.ALREADY_SAVED) {
                    already[0]++;
                    // Nothing was written for it, but it isn't a file this run lost either.
                    SaveRecords.countedWithoutFile();
                }
            });
            if (outcome.saved == 0 && outcome.cancelled == 0 && outcome.stop == SaveProgress.Stop.NONE) {
                Utils.showToastLong(L10n.t("None of the picked videos could be saved. Try again."));
                return;
            }
            SaveNotice.saved(message(app, outcome, already[0]), last[0]);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "The picked videos run failed", failure);
            Utils.showToastLong(L10n.t("None of the picked videos could be saved. Try again."));
        }
    }

    /**
     * What the banner says: how many this run saved, and on a line of its own how many were
     * saved here before. A run cut short gives the count of what's in the gallery, the
     * already-saved ones included, and why the rest aren't.
     */
    static String message(Context context, SaveProgress.Outcome outcome, int already) {
        String before = already <= 0 ? null : L10n.quantity(context, already, "One was already saved",
                "%1$s were already saved", String.valueOf(already));
        if (!outcome.complete()) {
            String said = SaveProgress.message(outcome, "");
            return before == null ? said : said + "\n" + before;
        }
        int fresh = outcome.saved - Math.max(0, already);
        if (fresh <= 0) return before;
        String saved = L10n.quantity(context, fresh, "Saved one video", "Saved %1$s videos", String.valueOf(fresh));
        return before == null ? saved : saved + "\n" + before;
    }

    /** The real saver: a photo post's photos, or the video. */
    static Result saveOne(Context app, Object post, MediaFileWriter.Saved[] last) throws IOException {
        String id = Reflect.string(post, "getAid", "aid");
        if (id == null) throw new IOException("A picked post has no id");
        boolean checkSaved = Settings.CHECK_SAVED_VIDEOS.get();
        if (Reflect.property(post, "getPhotoModeImageInfo", "photoModeImageInfo") != null) {
            if (checkSaved && SavedVideoArchive.find(app, id) != null) return Result.ALREADY_SAVED;
            long photoGeneration = SavedVideoArchive.generation();
            MediaFileWriter.Saved first;
            // As a single save does: Save photo posts as a video makes the video, not the photos.
            // A live photo stays with the photos, as it stays with TikTok's own save.
            if (SlideshowVideo.enabled() && !OriginalPhotos.anyChosenIsLive(post, null)) {
                // A single save of the same post's video is under way; this one would race it.
                if (SlideshowVideo.saving(id)) throw new IOException("This post is already being saved as a video");
                first = SlideshowVideo.makeForRun(app, post);
                last[0] = first;
            } else {
                first = savePhotos(app, post, last);
            }
            if (checkSaved) SavedVideoArchive.rememberQuietly(app, id, first, photoGeneration);
            return Result.SAVED;
        }
        if (checkSaved && SavedVideoArchive.find(app, id) != null) return Result.ALREADY_SAVED;
        // A single save of the same video is under way; this one would race it for the file.
        if (VideoDownloads.saving(id)) throw new IOException("This video is already being saved");
        long generation = SavedVideoArchive.generation();

        Object video = Reflect.property(post, "getVideo", "video");
        Source source = source(video, Settings.DOWNLOAD_VIDEO_QUALITY.get(), Settings.DOWNLOAD_WITHOUT_SOUND.get());
        if (source.video.isEmpty()) throw new IOException("This video isn't available as a complete file");
        String name = DownloadFilenameFormatter.formatSelectedVideoName(post);
        String path = DownloadFilenameFormatter.destinationPath(post, false);
        List<File> temporary = new ArrayList<>();
        try {
            File picture = temp(app, temporary);
            RemoteMedia.fetch(source.video, picture, RemoteMedia.Kind.VIDEO);
            File result = picture;
            if (!source.audio.isEmpty()) {
                File sound = temp(app, temporary);
                RemoteMedia.fetch(source.audio, sound, RemoteMedia.Kind.VIDEO);
                result = temp(app, temporary);
                TrackMuxer.combine(picture, sound, result);
            } else if (source.stripSound) {
                result = temp(app, temporary);
                TrackMuxer.videoOnly(picture, result);
            }
            MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, result, name, "video/mp4", path, true);
            last[0] = saved;
            if (checkSaved) {
                try {
                    SavedVideoArchive.remember(app, id, saved, generation);
                } catch (RuntimeException failure) {
                    Logger.printException(() -> "Could not remember a video saved in a run", failure);
                }
            }
            return Result.SAVED;
        } finally {
            for (File file : temporary) if (!MediaCache.delete(file)) Logger.printInfo(() -> "Could not remove a run's temporary file");
        }
    }

    /** Where one video comes from: its addresses, its separate sound's, and whether to strip the sound. */
    static final class Source {
        final List<String> video;
        final List<String> audio;
        final boolean stripSound;

        Source(List<String> video, List<String> audio, boolean stripSound) {
            this.video = video;
            this.audio = audio;
            this.stripSound = stripSound;
        }
    }

    /**
     * The rendition Download would take for {@code quality} (TikTok's file on Automatic), with
     * its separate sound when the rendition is picture only and the sound is wanted. A picture-only
     * rendition whose sound can't be found has no complete file, so it comes back empty.
     */
    static Source source(Object video, String quality, boolean muted) {
        Object gear = VideoDownloads.selectedGear(video, quality, false);
        if (gear == null) {
            List<String> urls = VideoDownloads.automaticUrls(video);
            return new Source(urls, Collections.<String>emptyList(), muted && !urls.isEmpty());
        }
        List<String> urls = VideoDownloads.urls(Reflect.property(gear, "getPlayAddr", "playAddr"));
        boolean dash = Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"));
        if (!dash) return new Source(urls, Collections.<String>emptyList(), muted && !urls.isEmpty());
        if (muted) return new Source(urls, Collections.<String>emptyList(), false);
        List<String> audio = VideoDownloads.audioUrls(video, gear);
        if (audio.isEmpty()) return new Source(Collections.<String>emptyList(), audio, false);
        return new Source(urls, audio, false);
    }

    /**
     * Every photo of a photo post at full size, as Download original photos saves them.
     *
     * @return the first photo that landed, which the record of saved posts opens
     */
    private static MediaFileWriter.Saved savePhotos(Context app, Object post, MediaFileWriter.Saved[] last) throws IOException {
        List<List<String>> photos = OriginalPhotos.sources(post);
        if (photos.isEmpty()) throw new IOException("The original photos aren't available");
        String path = DownloadFilenameFormatter.destinationPath(post, true);
        MediaFileWriter.Saved first = null;
        for (int i = 0; i < photos.size(); i++) {
            MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
            File temp = MediaCache.createTempFile(app, "original-photo-", ".tmp");
            try {
                String extension = PhotoToJpeg.convert(app, temp,
                        RemoteMedia.fetch(photos.get(i), temp, RemoteMedia.Kind.IMAGE));
                String mime = "jpg".equals(extension) ? "image/jpeg" : "image/" + extension;
                String named = DownloadFilenameFormatter.formatOriginalPhotoName(post, i + 1, "tmp");
                String name = named.substring(0, named.lastIndexOf('.') + 1) + extension;
                last[0] = MediaFileWriter.publishForResult(app, temp, name, mime, path, false);
                if (first == null) first = last[0];
            } finally {
                if (!MediaCache.delete(temp)) Logger.printInfo(() -> "Could not remove a run's photo temporary file");
            }
        }
        return first;
    }

    private static File temp(Context context, List<File> files) throws IOException {
        File file = MediaCache.createTempFile(context, "picked-video-", ".mp4");
        files.add(file);
        return file;
    }
}
