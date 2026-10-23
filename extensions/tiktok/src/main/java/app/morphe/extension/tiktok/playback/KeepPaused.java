/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import android.app.Activity;
import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold;

/**
 * Leaves a video the reader paused paused when they come back to the app.
 *
 * <p>TikTok stops the video on screen whenever the app goes away and plays it again as it comes
 * back, whatever state the reader left it in. It does that twice over on 47.0.3, both times
 * through PlayerController's play method: once as the feed's panel resumes, and again when the
 * video's surface is made anew. So the state is read as the app starts to go away, before TikTok's
 * own stop lands: the application's pre-pause callback runs ahead of the activity's lifecycle
 * observers, where the player stops. That callback arrived in Android 10, and on older versions
 * nothing the application hears comes before the stop, so there is nothing true to read and this
 * stays out. Only the feed's own activity counts: another TikTok screen over it pauses on the way
 * back with the feed's player already stopped. If the reader had paused that video, the play
 * method turns those plays down for it the way TikTok turns a play down while it is casting. The
 * reader's own tap on the video resumes the player without that method, and a play asked for from
 * inside a click or an accessibility action (TikTok's feed buttons, a screen reader's play) is
 * the reader's too and goes through.
 *
 * <p>Only plays of that one video are turned down, only within {@link #RETURN_WINDOW_MS} of the
 * return, and none once it is playing again. A video left playing starts as usual.
 */
public final class KeepPaused {
    static final String FAMILY = "keep paused";
    static final long RETURN_WINDOW_MS = 3000;

    /** The video the reader had paused when the app went away, or null. Main thread. */
    private static String pausedAwemeId;
    /** When the app came back, or -1 while it is away or the record is spent. */
    private static long returnedAt = -1;

    private KeepPaused() {
    }

    /** The app is starting to go away: note whether the reader had paused the video on screen. */
    static void onLeaving(Activity activity) {
        try {
            pausedAwemeId = null;
            returnedAt = -1;
            if (!Settings.KEEP_PAUSED_ON_RETURN.get()) return;
            if (activity == null || !FeedVisibility.isOnFeed(activity)) return;
            String awemeId = SessionPlaybackHold.currentAwemeId();
            if (awemeId != null && Boolean.FALSE.equals(SessionPlaybackHold.currentPlaying())) {
                pausedAwemeId = awemeId;
            }
        } catch (Throwable failure) {
            pausedAwemeId = null;
            Logger.printException(() -> "Could not read the paused video on leaving", failure);
        }
    }

    /** The app is coming back. */
    static void onReturning() {
        if (pausedAwemeId != null) returnedAt = SystemClock.uptimeMillis();
    }

    /**
     * Asked as TikTok's play method is about to play {@code aweme}: whether to turn that play
     * down, because it is the video the reader left paused and the app has only just come back.
     */
    public static boolean refusePlay(Object aweme) {
        HookStatus.bound(FAMILY, "play");
        try {
            String awemeId = pausedAwemeId;
            if (awemeId == null) return false;
            if (!Settings.KEEP_PAUSED_ON_RETURN.get() || returnedAt < 0
                    || SystemClock.uptimeMillis() - returnedAt > RETURN_WINDOW_MS
                    || Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying())) {
                forget();
                return false;
            }
            if (!awemeId.equals(Reflect.string(aweme, "getAid", "aid"))) return false;
            if (readerAsked()) {
                forget();
                HookStatus.bound(FAMILY, "reader's play");
                return false;
            }
            HookStatus.bound(FAMILY, "kept paused");
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not answer TikTok's play on returning", failure);
            return false;
        }
    }

    /**
     * Whether the play comes out of a click or an accessibility action on a view: TikTok's own
     * feed buttons, a screen reader's play action on the video, a key. TikTok's own plays on the
     * way back come from the feed resuming and from a new surface, never from inside either.
     */
    static boolean readerAsked() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (!"android.view.View".equals(frame.getClassName())) continue;
            String method = frame.getMethodName();
            if (method.equals("performClick") || method.startsWith("performAccessibilityAction")) return true;
        }
        return false;
    }

    private static void forget() {
        pausedAwemeId = null;
        returnedAt = -1;
    }

    static void resetForTests() {
        forget();
    }
}
