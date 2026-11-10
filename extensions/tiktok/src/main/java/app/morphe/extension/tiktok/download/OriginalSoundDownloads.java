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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saves the sound a video was made with, rather than the sound the video ends up making.
 *
 * <p>The two are different files. The video's own track is the finished mix: the music with
 * whatever was said over it, cut to the length of the post. The sound entry is the original,
 * whole, as it appears on its own page and in everyone else's videos made from it. Someone
 * saving "that song" wants the second one, and until now only the first was reachable.
 *
 * <p>It is published beside the video's own sound, under Music where Android files audio, and
 * named after the sound rather than the post.
 */
public final class OriginalSoundDownloads {
    /** How long a sound title may be before the filename cap has to deal with it. */
    private static final int MAX_TITLE_LENGTH = 80;

    private static final Set<String> ACTIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private OriginalSoundDownloads() {
    }

    /**
     * Where to fetch the original sound from, best mirror first, or empty when this post has no
     * sound entry. A video with only its own audio is the ordinary case, not a failure.
     */
    static List<String> sourceUrls(Object aweme) {
        Object music = Reflect.property(aweme, "getMusic", "music");
        if (music == null) return List.of();
        Object playUrl = Reflect.property(music, "getPlayUrl", "playUrl");
        if (playUrl == null) return List.of();

        Object urls = Reflect.property(playUrl, "getUrlList", "urlList");
        List<String> mirrors = new ArrayList<>();
        if (urls instanceof List<?>) {
            for (Object url : (List<?>) urls) {
                if (url == null) continue;
                // Over TLS only, the way every other saver here reads its addresses. These come
                // out of a server response, and a cleartext one hands the fetch to whoever is
                // on the network between the phone and the host.
                String text = url.toString().trim();
                if (MediaTransport.hasAllowedShape(text)) mirrors.add(text);
            }
        }
        if (mirrors.isEmpty()) {
            // Some builds carry only the single uri and no list at all.
            String single = Reflect.string(playUrl, "getUri", "uri");
            if (MediaTransport.hasAllowedShape(single)) mirrors.add(single);
        }
        return List.copyOf(mirrors);
    }

    /**
     * What the saved file is called: the sound's own title, falling back to the post's name.
     * The extension is the one the fetched bytes turned out to carry.
     */
    static String fileName(Object aweme, String extension) {
        Object music = Reflect.property(aweme, "getMusic", "music");
        String title = music == null ? null : Reflect.string(music, "getTitle", "title");
        if (title == null || title.isEmpty()) {
            return DownloadFilenameFormatter.formatSelectedAudioName(aweme, extension);
        }
        if (title.length() > MAX_TITLE_LENGTH) title = title.substring(0, MAX_TITLE_LENGTH);
        return DownloadFilenameFormatter.formatSoundName(title, extension);
    }

    /** The type that goes with each container {@link RemoteMedia.Kind#AUDIO} will accept. */
    static String mimeFor(String extension) {
        switch (extension) {
            case "mp3":
                return "audio/mpeg";
            case "aac":
                return "audio/aac";
            case "ogg":
                return "audio/ogg";
            case "wav":
                return "audio/wav";
            case "flac":
                return "audio/flac";
            default:
                return "audio/mp4";
        }
    }

    /**
     * Fetches the sound and publishes it. Says why when it cannot, because a long press that
     * quietly does nothing reads as a broken button rather than as a video with no sound entry.
     */
    public static void start(Object aweme, Context context) {
        if (context == null) return;
        // Android 6 to 9 write a real file, so without the permission the save fails after the
        // fetch has already run and the reader is told only that it could not be saved. Asked
        // before anything is fetched, the way every other saver here asks it.
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("Allow storage for TikTok in Android settings to save sounds"));
            return;
        }
        List<String> sources = sourceUrls(aweme);
        if (sources.isEmpty()) {
            Utils.showToastShort(L10n.t("This video has no original sound to save"));
            return;
        }

        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) return;

        Context app = context.getApplicationContext();
        String key = "original sound " + id;
        if (!ACTIVE.add(id)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return;
        }
        MediaJobScheduler.Job job = MediaJobScheduler.submit("original-sound", key, () -> {
            File fetched = null, encoded = null;
            try {
                fetched = MediaCache.createTempFile(app, "original-sound-", ".tmp");
                // A sound entry is not always an MP4 audio track, so the name and the type both
                // wait for the header instead of assuming one. A file called .m4a that holds
                // MPEG frames is one the gallery refuses to play.
                String extension = RemoteMedia.fetch(sources, fetched, RemoteMedia.Kind.AUDIO);
                File publish = fetched;
                // An Ogg that is Opus already goes out as it came, with no second encode.
                if (SoundFormat.opus() && !OpusTranscoder.isOpus(fetched)) {
                    encoded = MediaCache.createTempFile(app, "original-sound-", ".ogg");
                    if (OpusTranscoder.transcodeOrKeep(fetched, encoded)) {
                        publish = encoded;
                        extension = "ogg";
                    }
                }
                String name = fileName(aweme, extension);
                String path = AudioDownloads.audioPath(DownloadsPatch.getVideoDownloadPath());
                MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, publish, name, mimeFor(extension), path, true);
                SaveNotice.saved(L10n.f("Sound saved to %1$s", path), saved);
            } catch (IOException | RuntimeException exception) {
                Logger.printException(() -> "Original sound download failed", exception);
                Utils.showToastLong(L10n.t("The sound couldn't be saved. Try again."));
            } finally {
                for (File temporary : new File[]{fetched, encoded}) {
                    if (temporary != null && !MediaCache.delete(temporary)) {
                        Logger.printInfo(() -> "Could not remove sound temporary file");
                    }
                }
            }
        }, () -> ACTIVE.remove(id));
        String saying = L10n.t("Saving the sound");
        MediaJobScheduler.acknowledge(job, saying, saying);
    }
}
