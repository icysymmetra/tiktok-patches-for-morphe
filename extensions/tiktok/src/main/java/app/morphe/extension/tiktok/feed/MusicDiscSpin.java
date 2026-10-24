/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether the music disc at the bottom of the right column turns (#68).
 *
 * <p>On 47.1.4 the turn is a ValueAnimator that VideoMusicCoverAssem builds and starts once its
 * view model's startMusicAnimation goes true, and the only thing that sets it is the assem's play
 * subscriber. On For You the way there runs through these holds, in order:
 *
 * <ol>
 *   <li>VideoViewCell's play hook sends the play animation event only while TikTok's power mode
 *       is off, the high frame rate hold is false, and the load scene strategy
 *       {@code feed_play_anim_downgrade} has not switched the play animation off, which it does
 *       when the phone's load reaches SEVERE.</li>
 *   <li>The play subscriber returns when the app AB int {@code music_animation_close_exp} is 1 or
 *       3, unless {@code video_music_cover_visual_opt_rotation_duration} is above 0. TikTok's own
 *       defaults are 3 and 0, which is why the disc stays still with every Hushfeed switch off.</li>
 *   <li>It returns again on a photo without music, once it has started, while VideoMusicCoverVM's
 *       hold is true (the app AB boolean {@code anim_opt}, or a tablet layout), in power mode, and
 *       while the high frame rate hold is true. That hold is set at launch from
 *       {@code frame_rate_high_type}: 4 always, 2 when a display runs above 60 Hz (a 120 Hz
 *       flagship like the S22), 1 from another frame rate flag, and the default 0 only on a
 *       Google phone above 60 Hz.</li>
 *   <li>The animator starts only when the check registered under {@code musicCoverDegrade} says
 *       yes. It says no under some of TikTok's new user strategies, when
 *       {@code ui_down_grade_strategy} has its 1 bit, and when {@code pythia_low_device_opt} is
 *       open and the on device performance score is missing or out of its range.</li>
 * </ol>
 *
 * <p>With TikTok's defaults only the close setting holds the disc on a Samsung flagship. An S22
 * answered past it still showed a still disc, so one of the later ones came from the server or
 * the phone's load, and nothing in the APK says which. Spin answers every one of them, and each
 * answer is logged the first time it changes something, so a device log names the hold that was
 * set. Power mode and a photo without music are left alone.
 *
 * <p>The close setting is two flags: 1 holds the disc still, 2 stops the track name scrolling,
 * 3 is both. Only the disc's flag is answered here, so the track name keeps TikTok's choice. A
 * rotation duration above 0 lets the disc turn even with its flag set (and sets the turn's length
 * in seconds), so holding it still answers that one too. Both are read once per launch through a
 * lazy value, which is why the two switches take a restart. Keep still needs nothing past the
 * close setting, and while it is on the later holds go through as TikTok sent them.
 */
public final class MusicDiscSpin {
    /** The Hook status family this reports under. */
    static final String FAMILY = "music disc spin";
    static final String INSTALLED = "installed";
    /** The close setting's flag that holds the disc still. */
    static final int HOLD_DISC = 1;
    /** The close setting's flag that stops the track name scrolling. */
    static final int HOLD_TRACK_NAME = 2;

    /** The holds already logged since the diagnostics were last cleared; they run once a video. */
    private static final Set<String> LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static volatile long loggedGeneration = -1;

    private MusicDiscSpin() {}

    /** Called at settings load, so the family is in every export whether or not a read came by. */
    public static void installed() {
        HookStatus.bound(FAMILY, INSTALLED);
    }

    /** {@code music_animation_close_exp} as TikTok is about to keep it for this launch. */
    public static int closeSetting(int value) {
        boolean still = Settings.STOP_MUSIC_DISC_SPIN.get();
        if (!still && !Settings.SPIN_MUSIC_DISC.get()) return value;
        // The two flags as the app tests them: the disc for 1 or 3, the track name for 2 or 3.
        boolean discHeld = value == HOLD_DISC || value == (HOLD_DISC | HOLD_TRACK_NAME);
        if (discHeld == still) return value;
        boolean trackNameHeld = value == HOLD_TRACK_NAME || value == (HOLD_DISC | HOLD_TRACK_NAME);
        int answer = (still ? HOLD_DISC : 0) | (trackNameHeld ? HOLD_TRACK_NAME : 0);
        HookStatus.bound(FAMILY, still ? "held the disc" : "spun the disc");
        Logger.printInfo(() -> "Music disc: music_animation_close_exp came back " + value
                + ", answered " + answer);
        return answer;
    }

    /** {@code video_music_cover_visual_opt_rotation_duration}, in seconds, as TikTok is about to keep it. */
    public static int rotationSeconds(int seconds) {
        if (seconds <= 0 || !Settings.STOP_MUSIC_DISC_SPIN.get()) return seconds;
        HookStatus.bound(FAMILY, "held the disc's turn");
        Logger.printInfo(() -> "Music disc: video_music_cover_visual_opt_rotation_duration came back "
                + seconds + ", answered 0");
        return 0;
    }

    /** VideoMusicCoverVM's hold on the cover's animation ({@code anim_opt}), as it is about to return. */
    public static boolean coverAnimationHeld(boolean held) {
        if (!held || !spinning()) return held;
        answered("lifted anim_opt", "Music disc: anim_opt came back true, answered false");
        return false;
    }

    /** The high frame rate hold ({@code frame_rate_high_type}), just read where the play animation starts. */
    public static boolean highFrameRateHeld(boolean held) {
        if (!held || !spinning()) return held;
        answered("lifted the high frame rate hold",
                "Music disc: the high frame rate hold came back true, answered false");
        return false;
    }

    /** The play animation switch {@code feed_play_anim_downgrade} sets, just read in the play hook; true is off. */
    public static boolean playAnimationSwitchedOff(boolean off) {
        if (!off || !spinning()) return off;
        answered("lifted feed_play_anim_downgrade",
                "Music disc: feed_play_anim_downgrade came back true, answered false");
        return false;
    }

    /** The {@code musicCoverDegrade} check's answer, as it is about to return; true starts the disc. */
    public static boolean degradeAllows(boolean allows) {
        if (allows || !spinning()) return allows;
        answered("lifted musicCoverDegrade", "Music disc: musicCoverDegrade came back false, answered true");
        return true;
    }

    /** Spin is on and Keep still, which overrides it, is off. */
    private static boolean spinning() {
        return Settings.SPIN_MUSIC_DISC.get() && !Settings.STOP_MUSIC_DISC_SPIN.get();
    }

    /** Reports one lifted hold, and logs it the first time since the diagnostics were cleared. */
    private static void answered(String hold, String line) {
        HookStatus.bound(FAMILY, hold);
        long generation = HookStatus.generation();
        if (generation != loggedGeneration) {
            LOGGED.clear();
            loggedGeneration = generation;
        }
        if (LOGGED.add(hold)) Logger.printInfo(() -> line);
    }
}
