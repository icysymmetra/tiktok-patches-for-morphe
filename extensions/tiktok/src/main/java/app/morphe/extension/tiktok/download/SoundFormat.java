/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Which file a saved sound becomes: the container TikTok's bytes came in, or Opus in an Ogg
 * file. The choice covers both sound saves, the track written beside a video and the original
 * sound saved from a long press.
 */
public final class SoundFormat {
    public static final String ORIGINAL = "original";
    public static final String OPUS = "opus";

    private SoundFormat() {}

    /** Opus is chosen and this phone can make it. */
    static boolean opus() {
        return OPUS.equals(Settings.DOWNLOAD_SOUND_FORMAT.get()) && opusPossible();
    }

    /**
     * Android 10 and later with an Opus encoder, the only place the choice does anything. The
     * muxer's Ogg output arrived with Android 10, so an older phone has nowhere to put Opus even
     * when it has an encoder.
     */
    public static boolean opusPossible() {
        return android.os.Build.VERSION.SDK_INT >= 29 && hasEncoder();
    }

    private static boolean hasEncoder() {
        try {
            // The platform keeps the codec list once it has read it, so asking again is cheap.
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (OpusTranscoder.MIME.equalsIgnoreCase(type)) return true;
                }
            }
        } catch (RuntimeException unreadable) {
            Logger.printInfo(() -> "Could not read the encoder list: " + unreadable);
        }
        return false;
    }

    /** {@code name} with its ending swapped, so a sound named for an .m4a becomes an .ogg. */
    static String withExtension(String name, String extension) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 && dot > name.lastIndexOf('/') ? name.substring(0, dot) : name;
        return stem + "." + extension;
    }
}
