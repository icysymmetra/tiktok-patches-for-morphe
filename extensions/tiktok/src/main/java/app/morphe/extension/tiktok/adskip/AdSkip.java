/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The patch's hooks: TikTok's player names each video it plays and reports its position several
 * times a second, and the feed binds the posts either side of the one on screen. Bound posts are
 * scanned ahead, the playing one first, and a progress tick inside a found ad seeks past it.
 */
public final class AdSkip {
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    /** A tick this close to a found ad's start counts as inside it. */
    private static final long LEAD_MS = 150;
    /** Nothing is skipped in the ad's last moments, where the seek would gain nothing. */
    private static final long TAIL_MS = 400;
    /** An ad that covers this much of a video makes the whole video an ad. */
    private static final float WHOLE_VIDEO = 0.85f;

    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile WeakReference<Object> playingAweme = new WeakReference<>(null);
    private static volatile String playingAid;
    private static volatile long positionMs;
    private static volatile long durationMs;

    /** Per playing video: the matches already skipped this play, and the ones put back. */
    private static String sessionAid;
    private static final Set<Long> skipped = new HashSet<>();
    private static final Set<Long> allowed = new HashSet<>();
    private static long lastTick = -1;
    private static boolean advancedAway;
    /** Where the last skip seeked to and when, until the player reports it got there. */
    private static long seekTarget = -1;
    private static long seekAt;
    private static int seeksThisPlay;
    private static final long SEEK_SETTLE_MS = 2500;
    /** A safety stop: no video gets more automatic seeks than this in one play. */
    private static final int MAX_SEEKS_PER_PLAY = 6;
    private static volatile boolean seekInFlight;

    private static volatile boolean paused;
    private static boolean askedThisPlay;

    /** How far from the end a skip may land. */
    private static final long END_GUARD_MS = 600;

    private static Class<?> seekOwner;
    private static Method seekMethod;

    private AdSkip() {}

    static boolean active() {
        return SettingsStatus.adSkipEnabled && Settings.AD_SKIP.get() && !paused;
    }

    /** The editor plays the video itself; nothing is skipped behind it. */
    static void setPaused(boolean value) {
        paused = value;
    }

    // region hooks

    /** {@code PlayerController.play(Aweme, ...)}, before the engine starts. */
    public static void onPlay(Object playerController, Object aweme) {
        try {
            // Only this selected patch's player hook runs during normal playback. SettingsStatus
            // is otherwise populated only when the settings screen is opened.
            SettingsStatus.enableAdSkip();
            SharedDb.syncIfDue();
            if (aweme == null) return;
            String aid = Reflect.string(aweme, "getAid", "aid");
            if (aid == null) return;
            playingAweme = new WeakReference<>(aweme);
            if (!active()) return;
            if (playerController != null) controller = new WeakReference<>(playerController);
            if (!shouldScan()) return;
            AdScanner.request(aweme, true);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: play hook failed", failure);
        }
    }

    /** {@code VideoAuthorInfoVM.paramSync2StateAccept(VideoItemParams)}: the feed bound a post. */
    public static void onBind(Object videoItemParams) {
        try {
            if (!active() || !Settings.AD_SKIP_PRESCAN.get() || !shouldScan()) return;
            Object aweme = Reflect.property(videoItemParams, "getAweme", "aweme");
            if (aweme != null) AdScanner.request(aweme, false);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: bind hook failed", failure);
        }
    }

    /** {@code PlayerController.onPlayProgressChange(sourceId, position, duration)}. */
    public static void onProgress(Object playerController, String aid, long position, long duration) {
        if (playerController == null || aid == null || duration <= 0) return;
        controller = new WeakReference<>(playerController);
        playingAid = aid;
        positionMs = position;
        durationMs = duration;
        if (!active()) return;
        try {
            tick(aid, position, duration);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: progress hook failed", failure);
        }
    }

    // endregion

    private static boolean shouldScan() {
        if (AdSegmentStore.index().isEmpty()) return false;
        if (!Settings.AD_SKIP_MOBILE_DATA.get() && Utils.getNetworkType() == Utils.NetworkType.MOBILE) return false;
        return true;
    }

    private static synchronized void tick(String aid, long position, long duration) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!aid.equals(sessionAid)) {
            sessionAid = aid;
            skipped.clear();
            allowed.clear();
            seeksThisPlay = 0;
            seekTarget = -1;
            seekAt = 0;
            lastTick = -1;
            advancedAway = false;
            askedThisPlay = false;
        }
        if (!askedThisPlay) {
            askedThisPlay = true;
            Object aweme = playingAweme.get();
            if (aweme != null && aid.equals(Reflect.string(aweme, "getAid", "aid")) && shouldScan()) {
                AdScanner.request(aweme, true);
            }
        }
        // After a seek the player keeps reporting the old position for a moment. Until it
        // reports the new one, or a while has passed, nothing else happens: reading those
        // late ticks as a rewind used to clear the skips and seek again, over and over.
        if (seekTarget >= 0) {
            boolean landed = Math.abs(position - seekTarget) < 1500;
            if (!landed && now - seekAt < SEEK_SETTLE_MS) return;
            seekTarget = -1;
            lastTick = position;
            return;
        }
        // Played round to the start again, or the reader dragged back before an ad: it may
        // be skipped again. Only a real jump back counts, never a tick right after a seek.
        if (lastTick >= 0 && position + 3000 < lastTick && now - seekAt > SEEK_SETTLE_MS) {
            skipped.removeIf(start -> position < start - 500);
        }
        lastTick = position;
        if (seeksThisPlay >= MAX_SEEKS_PER_PLAY) return;

        AdScanner.Result result = AdScanner.result(aid);
        if (result == null || result.state != AdScanner.State.DONE || result.matches.isEmpty()) return;
        List<AdMatcher.Match> matches = result.matches;
        for (AdMatcher.Match match : matches) {
            long key = match.startMs;
            if (skipped.contains(key) || allowed.contains(key)) continue;
            long end = Math.min(match.endMs, duration);
            if (position < match.startMs - LEAD_MS || position >= end - TAIL_MS) continue;
            skipped.add(key);
            // The whole video, or an ad that runs into its last second: there is nothing after it
            // to seek to, so the feed moves on instead.
            boolean whole = (end - Math.max(0, match.startMs)) >= duration * WHOLE_VIDEO
                    || end >= duration - END_GUARD_MS - 400;
            if (whole) {
                if (!Settings.AD_SKIP_WHOLE_VIDEO.get() || advancedAway) return;
                advancedAway = true;
                seeksThisPlay = MAX_SEEKS_PER_PLAY;
                // A moment later, outside TikTok's progress callback, as a swipe would be.
                Utils.runOnMainThreadDelayed(() -> {
                    try {
                        if (!aid.equals(playingAid)) return;
                        if (advance()) {
                            AdSegmentStore.recordSkip(match.segment, Math.max(0, duration - positionMs));
                            AdSkipLog.add(aid, "whole video is " + match.segment.name + ", moved on");
                            notice(match.segment.name, match.segment.sharedServerId, null);
                        } else {
                            AdSkipLog.add(aid, "whole video is an ad, but the feed could not move on");
                        }
                    } catch (Throwable failure) {
                        Logger.printException(() -> "Ad skip: could not move past an ad video", failure);
                    }
                }, 350);
                return;
            }
            seekPast(aid, match, end, duration);
            return;
        }
    }

    private static void seekPast(String aid, AdMatcher.Match match, long target, long duration) {
        // Never onto the very end: landing there finishes the video inside TikTok's engine while
        // its own completion is still running, which is what took the app down.
        long landing = Math.min(target, duration - END_GUARD_MS);
        if (landing <= positionMs + 500) {
            AdSkipLog.add(aid, "ad runs to the end, nothing to seek past");
            return;
        }
        if (seekInFlight) return;
        seekInFlight = true;
        seeksThisPlay++;
        seekTarget = landing;
        seekAt = android.os.SystemClock.uptimeMillis();
        long from = Math.max(positionMs, match.startMs);
        Utils.runOnMainThread(() -> {
            try {
                if (!aid.equals(playingAid)) return;
                if (!seekTo(landing, duration)) {
                    AdSkipLog.add(aid, "could not seek");
                    return;
                }
                skippedOnce(aid, match, landing - from, duration);
            } finally {
                seekInFlight = false;
            }
        });
    }

    private static void skippedOnce(String aid, AdMatcher.Match match, long savedMs, long duration) {
        AdSegmentStore.recordSkip(match.segment, savedMs);
        AdSkipLog.add(aid, "skipped " + match.segment.name + ", saved " + savedMs + "ms");
        notice(match.segment.name, match.segment.sharedServerId, () -> {
            long back = Math.max(0, match.startMs);
            synchronized (AdSkip.class) {
                allowed.add(match.startMs);
                seekTarget = back;
                seekAt = android.os.SystemClock.uptimeMillis();
            }
            seekTo(back, duration);
        });
    }

    private static void notice(String name, String serverId, Runnable undo) {
        AdSkipBar.refresh();
        if (!Settings.AD_SKIP_NOTICE.get()) return;
        AdSkipToast.show(name, serverId, undo);
    }

    /** Seeks the player that last reported progress. Main thread. */
    static boolean seekTo(long targetMs, long duration) {
        Object current = controller.get();
        if (current == null || duration <= 0) return false;
        Object manager = Reflect.invoke(current, "getPlayerManager");
        if (manager == null) return false;
        try {
            Method method = seekMethod(manager.getClass());
            if (method == null) return false;
            long target = Math.max(0, Math.min(duration - 1, targetMs));
            method.invoke(manager, (float) target / duration * 100f);
            positionMs = target;
            synchronized (AdSkip.class) {
                lastTick = target;
            }
            return true;
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not seek", failure);
            return false;
        }
    }

    private static Method seekMethod(Class<?> type) {
        if (type == seekOwner) return seekMethod;
        seekOwner = type;
        seekMethod = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod("seek", float.class);
                method.setAccessible(true);
                seekMethod = method;
                break;
            } catch (NoSuchMethodException ignored) {
                // keep climbing
            } catch (Throwable ignored) {
                break;
            }
        }
        return seekMethod;
    }

    /** Moves the feed's pager on by one, as a swipe would. Main thread. */
    private static boolean advance() {
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        View pager = findPager(activity.findViewById(android.R.id.content));
        if (pager == null || !pager.canScrollVertically(1)) return false;
        try {
            Object position = Reflect.invoke(pager, "getCurrentItem");
            if (!(position instanceof Integer)) return false;
            int next = (Integer) position + 1;
            pager.getClass().getMethod("setCurrentItem", int.class).invoke(pager, next);
            return Integer.valueOf(next).equals(Reflect.invoke(pager, "getCurrentItem"));
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not move to the next video", failure);
            return false;
        }
    }

    private static View findPager(View view) {
        if (view == null || !view.isShown() || !view.getGlobalVisibleRect(new Rect())) return null;
        if (PAGER.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findPager(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // region state for the editor and the bar

    static String playingAid() {
        return playingAid;
    }

    static long playingPosition() {
        return positionMs;
    }

    static long playingDuration() {
        return durationMs;
    }

    /** Whether this ad of this video was already skipped this play; drawn dimmer on the bar. */
    static synchronized boolean wasSkipped(String aid, long startMs) {
        return aid != null && aid.equals(sessionAid) && skipped.contains(startMs);
    }

    static long playingPositionFor(String aid) {
        return aid != null && aid.equals(playingAid) ? positionMs : -1;
    }

    static Object playingAweme() {
        return playingAweme.get();
    }

    /** Whether TikTok's own player is playing; the editor pauses it while it plays its copy. */
    static void setFeedPlaying(boolean play) {
        try {
            app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold.setCurrentPlaying(play);
        } catch (Throwable ignored) {
            // Best effort: the bridge is part of the patch, but a missing member must not crash.
        }
    }

    // endregion
}
