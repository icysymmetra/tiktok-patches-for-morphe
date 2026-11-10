/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saves a video's sound as its own .m4a, copied out of a container rather than re-encoded, or
 * as an .ogg encoded to Opus when {@link SoundFormat} says so.
 *
 * The bytes come from whatever has already been fetched wherever possible: when
 * {@link VideoDownloads} handles the download it hands its own temporary file over, which is
 * the separate audio stream for a video with DASH and the video file itself otherwise. Only
 * when TikTok's own downloader takes the video does this fetch anything of its own.
 */
final class AudioDownloads {
    private static final Set<String> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private AudioDownloads() {}

    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.DOWNLOAD_AUDIO_TRACK.get();
    }

    /** Fetches the sound itself, for the downloads this extension does not handle. */
    static void start(Object aweme, Context context) {
        if (context == null || !enabled()) return;
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video == null) return;

        // The separate audio stream when there is one, otherwise the video, which carries it.
        List<String> sound = VideoDownloads.audioUrls(video, null);
        List<String> source = sound.isEmpty() ? VideoDownloads.sourceUrls(video) : sound;
        if (source.isEmpty()) return;
        final List<String> sourceUrls = List.copyOf(source);

        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) return;
        Context app = context.getApplicationContext();
        String audioName, videoPath;
        try {
            audioName = DownloadFilenameFormatter.formatSelectedAudioName(aweme);
            // TikTok's own save of the video goes to the creator's folder when the name asks
            // for one, so the sound follows it there.
            videoPath = DownloadFilenameFormatter.destinationPath(aweme, false);
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not work out the sound download name", exception);
            return;
        }
        String key = "sound " + id;
        if (!ACTIVE.add(id)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return;
        }
        MediaJobScheduler.Job job = MediaJobScheduler.submit("sound", key, () -> {
            File fetched = null;
            try {
                fetched = MediaCache.createTempFile(app, "sound-source-", ".mp4");
                RemoteMedia.fetch(sourceUrls, fetched, RemoteMedia.Kind.VIDEO);
                write(app, audioName, fetched, videoPath);
            } catch (IOException | RuntimeException exception) {
                Logger.printException(() -> "Sound download failed", exception);
                Utils.showToastLong(L10n.t("The sound couldn't be saved. Try again."));
            } finally {
                if (fetched != null && !MediaCache.delete(fetched)) {
                    Logger.printInfo(() -> "Could not remove sound temporary file");
                }
            }
        }, () -> ACTIVE.remove(id));
        String saying = L10n.t("Saving the sound");
        MediaJobScheduler.acknowledge(job, saying, saying);
    }

    /**
     * Copies the sound out of a file that is already on disk. Runs on the caller's thread, so
     * the file has to outlive the call, and reports its own failures rather than taking the
     * video download down with it.
     */
    static void write(Context app, Object aweme, File source) {
        if (aweme == null) return;
        String name, videoPath;
        try {
            name = DownloadFilenameFormatter.formatSelectedAudioName(aweme);
            videoPath = DownloadFilenameFormatter.destinationPath(aweme, false);
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not work out the sound download name", exception);
            return;
        }
        write(app, name, source, videoPath);
    }

    static void write(Context app, String name, File source, String videoPath) {
        try {
            write(app, name, source, videoPath, true);
        } catch (MediaBudget.StopException refusal) {
            Logger.printException(() -> "Sound save stopped", refusal);
            Utils.showToastLong(L10n.t("The sound couldn't be saved. Try again."));
        }
    }

    /**
     * {@code announce} false keeps this save's word to a plain toast: a video or story save that
     * carries the sound shows one banner, its own, and a second banner a tick later would take
     * the first one down before anyone saw it.
     */
    /**
     * True when the sound landed; false when the switch is off or the save failed (said by toast).
     * {@code videoPath} is the folder the video itself went to, creator folder included, so the
     * pair stays together.
     */
    static boolean write(Context app, String name, File source, String videoPath, boolean announce)
            throws MediaBudget.StopException {
        if (!enabled()) return false;
        File output = null;
        // Read once, so the temporary file, the name and the type all agree.
        boolean opus = SoundFormat.opus();
        try {
            output = MediaCache.createTempFile(app, "sound-", opus ? ".ogg" : ".m4a");
            if (opus && !OpusTranscoder.transcodeOrKeep(source, output)) {
                // A fresh file for the M4A: the muxer needn't cut off what the encoder left.
                opus = false;
                File failed = output;
                output = null;
                if (!MediaCache.delete(failed)) Logger.printInfo(() -> "Could not remove sound temporary file");
                output = MediaCache.createTempFile(app, "sound-", ".m4a");
            }
            if (!opus) TrackMuxer.audioOnly(source, output);
            String path = audioPath(videoPath);
            String fileName = opus ? SoundFormat.withExtension(name, "ogg") : name;
            MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, output, fileName,
                    opus ? "audio/ogg" : "audio/mp4", path, true);
            if (announce) SaveNotice.saved(L10n.f("Sound saved to %1$s", path), saved);
            else Utils.showToastShort(L10n.f("Sound saved to %1$s", path));
            return true;
        } catch (MediaBudget.StopException refusal) {
            throw refusal;
        } catch (IOException | RuntimeException exception) {
            Logger.printException(() -> "Sound save failed", exception);
            Utils.showToastLong(L10n.t("The sound couldn't be saved. Try again."));
            return false;
        } finally {
            if (output != null && !MediaCache.delete(output)) {
                Logger.printInfo(() -> "Could not remove sound temporary file");
            }
        }
    }

    /**
     * Where the sound file goes. Android 10 and later file media by type, and an audio file does not
     * belong to the video collection, so the sound mirrors the video's own folder under Music.
     * Older versions write real files and keep the two together.
     */
    static String audioPath(String videoPath) {
        if (android.os.Build.VERSION.SDK_INT < 29) return videoPath;
        int slash = videoPath == null ? -1 : videoPath.indexOf('/');
        return "Music" + (slash < 0 ? "/TikTok" : videoPath.substring(slash));
    }
}
