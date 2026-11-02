/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.misc;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Whether a video says it may be duetted or stitched.
 *
 * <p>TikTok reads one number off the video and gives two different reasons for refusing on
 * it: 1 is the creator saying friends only, and 2 is the commercial one, a video carrying
 * music or a partnership that closes duets whatever anyone wants. Both duet and stitch use
 * the same two numbers. Only the first is answered differently here. Turning the second into
 * "anyone may" would put the upload in front of a wall it is meant to be behind, and it is
 * the case most likely to have the post taken down afterwards.
 *
 * <p>Every other reason the app has for refusing is a separate check on a separate value and
 * is untouched: a photo post, a private video, children's mode, a deleted video.
 */
public final class DuetStitch {
    /** What the app reads as "anyone may". */
    private static final int ANYONE = 0;
    /** The creator's own choice: only people they follow back. */
    private static final int FRIENDS_ONLY = 1;

    private DuetStitch() {}

    public static int setting(int original) {
        if (!Settings.ALLOW_DUET_AND_STITCH.get()) return original;
        return original == FRIENDS_ONLY ? ANYONE : original;
    }

    /**
     * The creator's account-wide choice, which TikTok checks as well as the video's own: 0 anyone,
     * 1 only people they follow back, anything else nobody. On the S22 a video whose own setting
     * said friends only still had no Duet with the switch on, because its creator's account said
     * 3, and it is the video's creator choosing either way, with no commercial reason behind it,
     * so the switch answers all of it. In every build this patch targets, the only readers of
     * the account value are the duet and stitch checks for a video on screen; the owner's own
     * settings screen keeps its setting elsewhere.
     */
    public static int authorSetting(int original) {
        return Settings.ALLOW_DUET_AND_STITCH.get() ? ANYONE : original;
    }
}
