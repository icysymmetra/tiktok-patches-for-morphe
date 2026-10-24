/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays the H.264 rendition of a video that also comes in ByteVC1 (TikTok's HEVC) or ByteVC2.
 *
 * <p>TikTok often decodes ByteVC1 in software even on phones with a hardware HEVC decoder, which
 * shows up as stutter on older chips and heat on newer ones. H.264 has a hardware decoder on
 * practically every phone. Each gear in the list the player chooses from carries its codec in
 * {@code SimBitRate.getCodecType()}, which every converter fills from the feed's
 * {@code is_bytevc1}: 0 is H.264, 1 ByteVC1 and 2 ByteVC2 on 47.0.3, 47.1.3 and 47.1.4. The
 * patch filters what reaches {@code SimVideo.setBitRate} and {@code SimVideoUrlModel.setBitRate},
 * the same doors Play SDR instead of HDR and Playback quality use.
 *
 * <p>A list without an H.264 gear goes back whole, so a video only offered in TikTok's own
 * codecs still plays as it did.
 */
public final class H264Playback {
    static final String FAMILY = "H.264 playback";

    /** {@code SimBitRate.getCodecType()} for an H.264 gear. */
    static final int CODEC_H264 = 0;

    private static volatile boolean described;

    private H264Playback() {}

    /** The gear list handed to {@code SimVideo.setBitRate}. */
    public static List<?> filterPlayerVideoGears(List<?> original) {
        return filter(original, "SimVideo#setBitRate");
    }

    /** The gear list handed to {@code SimVideoUrlModel.setBitRate}. */
    public static List<?> filterPlayerUrlModelGears(List<?> original) {
        return filter(original, "SimVideoUrlModel#setBitRate");
    }

    /**
     * What the player may choose from with both codec and HDR switches applied: the H.264 gears
     * first, then the SDR ones among them. Each step keeps a subset only when one is left, so a
     * second pass changes nothing, and the hooks on one setter agree whichever of them runs
     * first. Playback quality picks among what this leaves.
     */
    static List<?> preferredGears(List<?> original) {
        return SdrPlayback.dropHdr(keepH264(original));
    }

    /**
     * The same list with only its H.264 gears, when the switch is on and the list mixes H.264
     * with another codec. Anything else goes back as it came, and so does a list whose H.264
     * gears have no playable address while another gear has one ({@link PreferredGears}).
     */
    static List<?> keepH264(List<?> original) {
        // A switch saved on by a build that had the patch, or a restored backup, keeps its
        // value after a repatch without it, and no row is left to turn it off.
        if (original == null || original.size() < 2 || !SettingsStatus.h264PlaybackEnabled
                || !Settings.PREFER_H264.get()) return original;
        List<Object> h264 = new ArrayList<>(original.size());
        for (Object gear : original) {
            if (isH264(gear)) h264.add(gear);
        }
        return PreferredGears.narrow(original, h264, "Prefer H.264 video");
    }

    private static List<?> filter(List<?> original, String member) {
        List<?> h264 = keepH264(original);
        if (h264 != original) {
            HookStatus.bound(FAMILY, member);
            if (!described) {
                described = true;
                int dropped = original.size() - h264.size();
                Logger.printInfo(() -> "H.264 playback dropped " + dropped + " HEVC or ByteVC2 gear(s) of "
                        + original.size() + " from " + member);
            }
        }
        return SdrPlayback.dropHdr(h264);
    }

    /**
     * Whether TikTok marks the gear H.264. A gear without a readable codec is not, so a renamed
     * getter leaves every list as TikTok built it.
     */
    static boolean isH264(Object gear) {
        Object type = Reflect.invoke(gear, "getCodecType");
        return type instanceof Integer && (Integer) type == CODEC_H264;
    }

    static void resetForTests() {
        described = false;
        PreferredGears.resetForTests();
    }
}
