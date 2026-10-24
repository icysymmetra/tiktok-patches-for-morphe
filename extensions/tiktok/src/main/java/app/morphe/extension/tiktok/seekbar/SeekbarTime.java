/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Show the time beside the progress bar (#90): the time played and the video's length, at the
 * end of TikTok's own bar.
 *
 * <p>Two hooks feed it, and neither polls. PlayerController builds a progress event each time
 * the player reports where it is, with the percent, the position and the video; {@link
 * #onPlayTick} keeps the last few. The bar's own progress method then hands {@link
 * #onBarProgress} the percent it is about to draw, and the event carrying that same percent says
 * which video the bar is showing and how long it is. The length is the player's own, worked
 * back from the position and the percent, so the time always agrees with the bar.
 *
 * <p>The bar is a row holding one frame with the seek bar in it. The time is a second child at
 * the end of that row ({@link SeekbarTimeLabel}), so it can never sit over the caption or the
 * buttons on the right, and it goes wherever the row goes: Clear display fades the row, and the
 * label follows the seek bar inside it. Ads and LIVE get no time. Turned off, or with Hushfeed
 * paused (every switch then answers off), the label comes out and the row is put back as it was.
 *
 * <p>Two choices sit beside the time (#173). {@link Settings#SEEKBAR_TIME_PLACEMENT} moves the
 * label over the bar's right end while nothing on screen reaches into that spot (see {@link
 * SeekbarTimePlacement}), and {@link Settings#SEEKBAR_FULL_WIDTH} takes TikTok's side gap off
 * the bar's row ({@link SeekbarFullWidth}), with or without the time.
 */
@SuppressWarnings("unused")
public final class SeekbarTime {
    /** TikTok's own LIVE type, the one its isLive check compares against. */
    static final int AWEME_TYPE_LIVE = 101;
    /** An event older than this belongs to a video that has stopped reporting. */
    static final long TICK_FRESH_MS = 2_000L;
    /** The percent travels unchanged from the event to the bar; this only absorbs float noise. */
    static final float SAME_PERCENT = 0.0001f;
    /** The bar's drag styles. While one is up TikTok shows its own big readout instead. */
    static final int FIRST_DRAG_STYLE = 100;

    private static final int KEPT_TICKS = 4;
    private static final Tick[] TICKS = new Tick[KEPT_TICKS];
    private static int nextTick;

    private SeekbarTime() {
    }

    /** One progress event: which video, how far through, and how long it is. */
    static final class Tick {
        final float percent;
        @Nullable final String aid;
        final long durationMs;
        final boolean hidden;
        final long at;

        Tick(float percent, @Nullable String aid, long durationMs, boolean hidden, long at) {
            this.percent = percent;
            this.aid = aid;
            this.durationMs = durationMs;
            this.hidden = hidden;
            this.at = at;
        }
    }

    public static boolean isEnabled() {
        return Settings.SEEKBAR_TIME.get();
    }

    /** The end of TikTok's progress event constructor: the percent, the position and the video. */
    public static void onPlayTick(float percent, long positionMs, @Nullable Aweme aweme) {
        if (aweme == null || !isEnabled()) return;
        try {
            Tick tick = new Tick(percent, aweme.getAid(), lengthOf(percent, positionMs),
                    hides(aweme), SystemClock.uptimeMillis());
            synchronized (TICKS) {
                TICKS[nextTick] = tick;
                nextTick = (nextTick + 1) % KEPT_TICKS;
            }
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Seekbar time: could not read the progress event", failure);
        }
    }

    /** The entry of the bar's own progress method, with the percent (0 to 100) it will draw. */
    public static void onBarProgress(@Nullable LinearLayout bar, float percent) {
        if (bar == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bar.post(() -> onBarProgress(bar, percent));
            return;
        }
        try {
            // The full-width bar (#173) doesn't depend on the time, and it is put back first so the
            // label below attaches to the row as it will stay.
            SeekbarFullWidth.apply(bar, SeekbarFullWidth.isEnabled());
            if (!isEnabled()) {
                SeekbarTimeLabel.removeFrom(bar);
                return;
            }
            SeekbarTimeLabel label = SeekbarTimeLabel.attach(bar);
            if (label == null) return;
            long now = SystemClock.uptimeMillis();
            label.show(percent, matching(percent, now));
            label.place(SeekbarTimePlacement.wantsAbove(), now);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Seekbar time: could not update the time", failure);
        }
    }

    /** The entry of the bar's style method: a drag style hides the time until the drag ends. */
    public static void onBarStyle(@Nullable LinearLayout bar, int style) {
        if (bar == null) return;
        try {
            SeekbarTimeLabel label = SeekbarTimeLabel.find(bar);
            if (label != null) label.setDragging(style >= FIRST_DRAG_STYLE);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Seekbar time: could not follow the bar's style", failure);
        }
    }

    /** The newest fresh event that reported this percent, or null. */
    @Nullable
    static Tick matching(float percent, long now) {
        synchronized (TICKS) {
            for (int back = 1; back <= KEPT_TICKS; back++) {
                Tick tick = TICKS[(nextTick - back + KEPT_TICKS) % KEPT_TICKS];
                if (tick != null && now - tick.at <= TICK_FRESH_MS
                        && Math.abs(tick.percent - percent) <= SAME_PERCENT) {
                    return tick;
                }
            }
        }
        return null;
    }

    /** Forgets every event; tests start from nothing. */
    static void clearTicks() {
        synchronized (TICKS) {
            for (int i = 0; i < KEPT_TICKS; i++) TICKS[i] = null;
            nextTick = 0;
        }
    }

    /** The player's length for the video, from where it is and how far through that is. */
    static long lengthOf(float percent, long positionMs) {
        if (percent <= 0f || positionMs <= 0L) return 0L;
        return Math.round(positionMs * 100.0 / percent);
    }

    /** Whether the video is one that never gets a time: an ad or a LIVE. */
    static boolean hides(Aweme aweme) {
        return aweme.isAd() || aweme.getAwemeRawAd() != null
                || aweme.getAwemeType() == AWEME_TYPE_LIVE;
    }

    /** "0:42 / 1:30" for the percent of a video this long. */
    static String readout(float percent, long durationMs) {
        long total = Math.round(durationMs / 1000.0);
        float through = Math.max(0f, Math.min(100f, percent));
        long played = (long) Math.floor(durationMs * (double) through / 100_000.0);
        return clock(Math.min(played, total)) + " / " + clock(total);
    }

    /** The widest readout a video of this length can show, with every digit a zero. */
    static String widest(long durationMs) {
        String total = clock(Math.round(Math.max(0L, durationMs) / 1000.0));
        StringBuilder zeros = new StringBuilder(total.length());
        for (int i = 0; i < total.length(); i++) {
            char c = total.charAt(i);
            zeros.append(c >= '0' && c <= '9' ? '0' : c);
        }
        return zeros + " / " + zeros;
    }

    /** m:ss, or h:mm:ss from an hour up. */
    static String clock(long seconds) {
        long s = Math.max(0L, seconds);
        long hours = s / 3600;
        long minutes = (s / 60) % 60;
        long rest = s % 60;
        StringBuilder out = new StringBuilder(8);
        if (hours > 0) {
            out.append(hours).append(':');
            if (minutes < 10) out.append('0');
        }
        out.append(minutes).append(':');
        if (rest < 10) out.append('0');
        return out.append(rest).toString();
    }
}
