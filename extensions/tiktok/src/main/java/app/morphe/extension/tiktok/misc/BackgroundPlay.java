/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Keeps TikTok's own background play on (#52).
 *
 * <p>TikTok reads the server value {@code background_play_enable} once a process: 0 is no
 * background play, 1 lets the long-press menu turn it on for one video, and 2 lets the menu turn
 * it on for good, which TikTok remembers as {@code long_term_bg_play_enable} in its
 * {@code background_play_repo} store. The server moves accounts between those values, which is
 * why the menu's switch comes and goes and why 2 set in the Feature Gate Lab can still fall back.
 * With Keep playing in the background on, the value reads as 2 and the remembered switch reads
 * as on, and TikTok does the rest: its player keeps going, its media notification pauses and
 * resumes, and it gives way when another app takes audio focus. Hushfeed starts no service of
 * its own. Which posts may play on stays TikTok's call too, with two exceptions: TikTok leaves
 * out photo posts and the videos on your own profile (where your private videos play), and the
 * switch lets both through. Ads, LIVE and paid posts still stop.
 *
 * <p>Replay in the background (#99) is the one thing here TikTok doesn't do on its own. Its
 * background session holds one video: it turns the player's looping off when the session starts,
 * and when the video ends it rewinds it and pauses, leaving the Play button in the notification.
 * With the switch on, that Play is pressed for you once TikTok has finished pausing.
 */
public final class BackgroundPlay {
    /** The Feature Gate Lab key the switch decides while it's on. */
    public static final String GATE_KEY = "background_play_enable";
    /** TikTok's value for background play the menu can leave on for good. */
    static final int ALWAYS = 2;
    /** The event type TikTok gives the videos you open from your own profile. */
    static final String OWN_PROFILE = "personal_homepage";
    /**
     * The shortest time between two replays. A video ends after it has played, so two ends this
     * close together mean something is wrong with it, and it's left paused rather than started
     * over and over.
     */
    static final long REPLAY_GAP_MS = 1_500L;

    /** What the replay needs from the phone and from TikTok, which tests stand in for. */
    interface Host {
        /** This process's importance, as {@link ActivityManager#getMyMemoryState} reports it. */
        int importance();

        /** The audio mode, which says whether a call is ringing or on. */
        int audioMode();

        /** Presses Play on TikTok's background session, the way its notification does. */
        void pressPlay(Object listener);
    }

    static Host host = new Host() {
        @Override public int importance() {
            ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(state);
            return state.importance;
        }

        @Override public int audioMode() {
            Context context = Utils.getContext();
            AudioManager audio = context == null ? null
                    : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return audio == null ? AudioManager.MODE_NORMAL : audio.getMode();
        }

        @Override public void pressPlay(Object listener) {
            replay(listener);
        }
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** When the last replay was pressed, on the elapsed-time clock. Tests reset it. */
    static long lastReplay = -REPLAY_GAP_MS;

    private BackgroundPlay() {}

    /**
     * The {@code background_play_enable} value TikTok acts on. TikTok keeps the first answer for
     * the life of the process, so the switch applies from the next start.
     */
    public static int mode(int served) {
        return Settings.BACKGROUND_PLAY.get() ? ALWAYS : served;
    }

    /** Whether TikTok's remembered background play switch reads as on. */
    public static boolean remembered(boolean stored) {
        return stored || Settings.BACKGROUND_PLAY.get();
    }

    /**
     * Whether background play may start on the page {@code eventType} names. TikTok's list has
     * the For You and Following feeds, search and other people's profiles, and leaves out your
     * own profile, the only place your private videos play.
     */
    public static boolean scene(boolean listed, String eventType) {
        return listed || (OWN_PROFILE.equals(eventType) && Settings.BACKGROUND_PLAY.get());
    }

    /** Whether a photo post counts as one for background play, which TikTok leaves out. */
    public static boolean photoMode(boolean photo) {
        return photo && !Settings.BACKGROUND_PLAY.get();
    }

    /**
     * Whether a page's claim on the sound is skipped. TikTok takes transient audio focus when one
     * of its pages resumes, and at a cold start it holds the feed's resume back until the feed's
     * first page loads. Leaving before then had the claim land in the background, where TikTok's
     * own background player took it for another app's sound and paused the first video. While
     * the switch is on, a claim made with none of TikTok's screens showing is skipped.
     */
    public static boolean skipsPageFocus() {
        if (!Settings.BACKGROUND_PLAY.get()) return false;
        try {
            return hidden(host.importance());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether a video that ends in the background starts again. Both switches have to be on. */
    static boolean replays() {
        return Settings.BACKGROUND_PLAY.get() && Settings.BACKGROUND_REPLAY.get();
    }

    /**
     * TikTok's background session has played its video to the end (#99). The patch calls this
     * from the session's own player listener, before TikTok rewinds and pauses it, and hands over
     * the answer TikTok just got to whether the session holds one video. Only then does TikTok
     * pause. A session that can move on to another video, as a collection's can, is left alone.
     * The replay waits until TikTok has finished with the end, so it starts from the beginning
     * of a paused video, as a tap on Play in the notification would.
     */
    public static void onBackgroundEnd(boolean single, Object listener) {
        try {
            if (!single || listener == null || !replays()) return;
            MAIN.post(() -> replayIfStillAway(listener));
        } catch (Throwable error) {
            Logger.printException(() -> "Could not replay the video in the background", error);
        }
    }

    private static void replayIfStillAway(Object listener) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (!shouldReplay(true, host.importance(), host.audioMode(), now, lastReplay)) return;
            lastReplay = now;
            host.pressPlay(listener);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not replay the video in the background", error);
        }
    }

    /**
     * Whether to press Play now. The switches are read again, since the replay waits a moment.
     * If a TikTok screen is showing, you came back and TikTok picks the video up itself. During
     * a call it stays paused, and so does a video that ended again straight after a replay.
     */
    static boolean shouldReplay(boolean single, int importance, int audioMode, long now, long last) {
        return single && replays() && hidden(importance) && !inCall(audioMode)
                && now - last >= REPLAY_GAP_MS;
    }

    /** Whether the phone's audio mode says a call is ringing, on, or being screened. */
    static boolean inCall(int audioMode) {
        return audioMode > AudioManager.MODE_NORMAL;
    }

    // Replaced with TikTok's own Play on its background session when the patch is applied. Not
    // private, so the host above calls it straight and not through an accessor D8 would add.
    static void replay(Object listener) { }

    /**
     * Whether a process of this importance has none of its screens showing. A screen that shows,
     * picture in picture included, keeps the process at the foreground importance, and TikTok
     * playing in the background with its media notification sits just below it.
     */
    static boolean hidden(int importance) {
        return importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /** Whether the switch is deciding {@code key} right now, which the Lab shows on that key. */
    public static boolean decidesGate(String key) {
        return SettingsStatus.backgroundPlayEnabled && GATE_KEY.equals(key) && Settings.BACKGROUND_PLAY.get();
    }
}
