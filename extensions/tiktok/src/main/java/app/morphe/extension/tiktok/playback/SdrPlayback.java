/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays the SDR rendition of a video that also comes in HDR.
 *
 * <p>An HDR stream on a phone with an HDR screen makes the panel jump to full brightness when it
 * starts. TikTok has its own switch for this, the accessibility setting {@code keva_is_hdr_off}
 * that {@code PlayerConfigImpl.isForceHdrOff} reads, and when its experiment is on the player
 * keeps only the gears whose {@code hdrType} is 0. The experiment is off for most accounts, so
 * the patch does the same filtering itself on the one door into the player's gear list,
 * {@code SimVideo.setBitRate} and {@code SimVideoUrlModel.setBitRate}, and answers TikTok's own
 * question with yes as well.
 *
 * <p>{@code SimBitRate.isHdr} is {@code hdrType == 1 || hdrType == 2} on 47.0.3, 47.1.3 and
 * 47.1.4, which is the rule {@link #isHdr} follows. A list with no SDR gear in it goes back
 * whole: a video only offered in HDR still plays.
 */
public final class SdrPlayback {
    static final String FAMILY = "SDR playback";

    private static volatile boolean described;

    private SdrPlayback() {}

    /** What {@code PlayerConfigImpl.isForceHdrOff} answers. */
    public static boolean forceHdrOff(boolean original) {
        return original || Settings.PLAY_SDR.get();
    }

    /** The gear list handed to {@code SimVideo.setBitRate}. */
    public static List<?> filterPlayerVideoGears(List<?> original) {
        return filter(original, "SimVideo#setBitRate");
    }

    /** The gear list handed to {@code SimVideoUrlModel.setBitRate}. */
    public static List<?> filterPlayerUrlModelGears(List<?> original) {
        return filter(original, "SimVideoUrlModel#setBitRate");
    }

    /**
     * The same list without its HDR gears, when the switch is on and at least one SDR gear is
     * left, unless no SDR gear has a playable address while an HDR one does
     * ({@link PreferredGears}). Playback quality calls this first so it picks among SDR gears
     * only, whichever of the two hooks runs first.
     */
    static List<?> dropHdr(List<?> original) {
        if (original == null || original.size() < 2 || !Settings.PLAY_SDR.get()) return original;
        List<Object> sdr = new ArrayList<>(original.size());
        for (Object gear : original) {
            if (!isHdr(gear)) sdr.add(gear);
        }
        return PreferredGears.narrow(original, sdr, "Play SDR instead of HDR");
    }

    /**
     * Hands back {@link H264Playback#preferredGears}, so this hook and Prefer H.264 video's on
     * the same setter agree whichever runs first. Only the HDR gears this step dropped count
     * toward its own report.
     */
    private static List<?> filter(List<?> original, String member) {
        List<?> codec = H264Playback.keepH264(original);
        List<?> result = dropHdr(codec);
        if (result != codec) {
            HookStatus.bound(FAMILY, member);
            if (!described) {
                described = true;
                int dropped = codec.size() - result.size();
                Logger.printInfo(() -> "SDR playback dropped " + dropped + " HDR gear(s) of "
                        + codec.size() + " from " + member);
            }
        }
        return result;
    }

    /**
     * Whether TikTok marks the gear HDR. A gear without a readable type counts as SDR, so a
     * renamed member leaves the list as TikTok built it.
     */
    static boolean isHdr(Object gear) {
        Object type = Reflect.invoke(gear, "getHdrType");
        if (type instanceof Integer) {
            int value = (Integer) type;
            return value == 1 || value == 2;
        }
        return Boolean.TRUE.equals(Reflect.invoke(gear, "isHdr"));
    }

    static void resetForTests() {
        described = false;
        PreferredGears.resetForTests();
    }
}
