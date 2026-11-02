/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.playback.FeedMute;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only pauses made for the daily-budget panel. Native player objects remain weakly held. */
public final class SessionPlaybackHold {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean syncQueued = new AtomicBoolean();
    private static volatile Target current;
    // Accessed only on the main thread, where the panel changes and native playback is controlled.
    private static Target held;
    // Published by main-thread release; progress can prove an independent resume before focus returns.
    private static volatile Target waitingForFocus;
    // A queued LIZ can still report PLAYING until its native dispatcher applies the pause.
    private static volatile boolean heldPauseObserved;
    private static WeakReference<Object> heldManager = new WeakReference<>(null);
    // The pause switches' own pause (comments open, or the feed waiting for a tap after a
    // return), kept apart from the panel's so neither hands back a video the other paused.
    private static volatile boolean switchWanted;
    // The app lock's own wish for the same pause, so it and the switches each hand it back alone.
    private static volatile boolean lockWanted;
    private static final AtomicBoolean switchQueued = new AtomicBoolean();
    // Written on the main thread only, like held. Volatile because TikTok's play and resume, which
    // ask about them, can run on its player's own threads.
    private static volatile Target switchHeld;
    private static volatile WeakReference<Object> switchManager = new WeakReference<>(null);
    // True while the hold itself is pausing or resuming, so its own resume isn't turned down as
    // TikTok's. Main thread.
    private static boolean ownCommand;
    // Native requests may run on an executor while focus callbacks arrive on another thread.
    // Compare listener identity, and never retain an activity's listener through static state.
    private static final List<WeakReference<Object>> nativeFocusListeners = new ArrayList<>();
    private static WeakReference<Object> nativeFocusOwner = new WeakReference<>(null);

    private SessionPlaybackHold() { }

    /** Receives the result of the native activity audio-focus request. */
    public static void onNativeFocusRequestResult(Object listener, int result) {
        if (listener == null) return;
        synchronized (nativeFocusListeners) {
            forgetNativeFocus(listener);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                nativeFocusListeners.add(new WeakReference<>(listener));
                nativeFocusOwner = new WeakReference<>(listener);
            }
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) resumeAfterNativeFocus();
    }

    /** Receives a focus change for the same native listener that requested it. */
    public static void onNativeFocusChange(Object listener, int change) {
        if (listener == null) return;
        boolean gained = false;
        synchronized (nativeFocusListeners) {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                for (WeakReference<Object> request : nativeFocusListeners) {
                    if (request.get() == listener) {
                        nativeFocusOwner = new WeakReference<>(listener);
                        gained = true;
                        break;
                    }
                }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
                forgetNativeFocus(listener);
            } else if ((change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                    && nativeFocusOwner.get() == listener) {
                nativeFocusOwner = new WeakReference<>(null);
            }
        }
        if (gained) resumeAfterNativeFocus();
    }

    /** A grant can arrive after the panel and its timer have already gone away. */
    private static void resumeAfterNativeFocus() {
        MAIN.post(() -> {
            if (waitingForFocus == null || SessionBudget.isLocked() || !hasNativeFocus()) return;
            release(false);
        });
    }

    /** Receives the native listener immediately before its focus request is abandoned. */
    public static void onNativeFocusAbandon(Object listener) {
        if (listener == null) return;
        synchronized (nativeFocusListeners) {
            forgetNativeFocus(listener);
        }
    }

    /** Caller holds nativeFocusListeners. A transient loss keeps admission for a later GAIN. */
    private static void forgetNativeFocus(Object listener) {
        for (int index = nativeFocusListeners.size() - 1; index >= 0; index--) {
            Object requested = nativeFocusListeners.get(index).get();
            if (requested == null || requested == listener) nativeFocusListeners.remove(index);
        }
        if (nativeFocusOwner.get() == listener) nativeFocusOwner = new WeakReference<>(null);
    }

    private static boolean hasNativeFocus() {
        // Muted, the feed's focus is turned down on purpose and no grant is coming to wait for.
        if (FeedMute.isHoldingFocus()) return true;
        synchronized (nativeFocusListeners) {
            return nativeFocusOwner.get() != null;
        }
    }

    private static final class Target {
        final WeakReference<Object> controller;
        final String awemeId;

        Target(Object controller, String awemeId) {
            this.controller = new WeakReference<>(controller);
            this.awemeId = awemeId;
        }

        boolean isCurrentCell(Object controller) {
            Object aweme;
            try {
                aweme = currentAweme(controller);
            } catch (RuntimeException notAController) {
                return false;
            }
            return awemeId.equals(Reflect.invoke(aweme, "getAid"));
        }
    }

    /**
     * What the hold calls on TikTok's player: the video a PlayerController has on screen, and its
     * player manager's pause and resume. TikTok renames all three with every build (the pause was
     * LIZ on 46.2.3 and is LJJLIIIJJI on 47.0.3, where LIZ reads a number instead), and naming
     * them here left the hold covering a video that played on. So nothing here names them: the
     * Block author patch reads them off PlayerController.pauseVideo and the For You feed's
     * space-key toggle and writes these three bodies. Unpatched, as in the tests, they go to
     * {@link #nativeForTests}.
     */
    interface NativeControls {
        Object currentAweme(Object controller);
        void pause(Object manager);
        void resume(Object manager);
    }

    static volatile NativeControls nativeForTests;

    static Object currentAweme(Object controller) {
        NativeControls controls = nativeForTests;
        return controls == null ? null : controls.currentAweme(controller);
    }

    static boolean pauseNative(Object manager) {
        NativeControls controls = nativeForTests;
        if (controls == null) return false;
        controls.pause(manager);
        return true;
    }

    static boolean resumeNative(Object manager) {
        NativeControls controls = nativeForTests;
        if (controls == null) return false;
        controls.resume(manager);
        return true;
    }

    /** Called with p0 and p1 from PlayerController.onPlayProgressChange(String, long, long). */
    public static void onPlayerProgress(Object controller, String awemeId) {
        if (controller == null || awemeId == null || awemeId.isEmpty()) return;
        Target previous = current;
        if (previous == null || previous.controller.get() != controller
                || !awemeId.equals(previous.awemeId)) {
            current = new Target(controller, awemeId);
        }
        FeedLock.onVideo(awemeId);
        if (!SessionBudget.isLocked()) {
            Target waiting = waitingForFocus;
            if (waiting != null && waiting == current) {
                Object manager = Reflect.invoke(controller, "getPlayerManager");
                boolean pauseWasObserved = heldPauseObserved;
                boolean playing = Boolean.TRUE.equals(Reflect.invoke(manager, "isPlaying"));
                if ((playing && pauseWasObserved)
                        || (!playing && Boolean.TRUE.equals(Reflect.invoke(manager, "isPaused")))) {
                    // Only playback after an observed pause proves an independent resume.
                    // The old PLAYING state before our queued pause must retain its handback.
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        observeNativePlayback(waiting, manager, playing);
                    } else {
                        WeakReference<Object> observedManager = new WeakReference<>(manager);
                        MAIN.post(() -> observeNativePlayback(waiting, observedManager.get(), playing));
                    }
                }
            }
            // TikTok starts the video again by itself, on a return above all, so while a pause
            // switch wants it stopped each report of it playing pauses it again.
            if ((switchWanted || lockWanted) && switchQueued.compareAndSet(false, true)) {
                MAIN.post(() -> {
                    switchQueued.set(false);
                    if (pauseForSwitchIfPlaying()) log("paused " + awemeId + " after a progress report");
                });
            }
            // The feed lock puts the same panel up with no budget behind it, so each report is
            // where it finds out the feed is on screen, as the hold does just below.
            if (FeedLock.covers() && syncQueued.compareAndSet(false, true)) {
                MAIN.post(() -> {
                    syncQueued.set(false);
                    SessionLockOverlay.sync();
                });
            }
            return;
        }
        if (!syncQueued.compareAndSet(false, true)) return;
        // Do not pause reentrantly inside a native callback. A fresh report also catches native
        // auto-resume while the panel already exists, including a return from settings.
        MAIN.post(() -> {
            syncQueued.set(false);
            SessionLockOverlay.sync();
        });
    }

    /**
     * Called with p0 from PlayerController.onRenderFirstFrame. A start's first video plays from
     * its first frame, and its first progress report comes a second or two later (#83), so while a
     * pause switch or the app lock wants the feed stopped, the player is stopped here rather than
     * after that report. Only a playing player counts: a preloaded neighbour draws its first frame
     * without playing.
     */
    public static void onFirstFrame(Object controller) {
        if (controller == null || !(switchWanted || lockWanted)) return;
        WeakReference<Object> owner = new WeakReference<>(controller);
        // Not inside the native callback, for the reason the progress report posts its pause.
        MAIN.post(() -> pauseAtFirstFrame(owner.get()));
    }

    private static void pauseAtFirstFrame(Object controller) {
        if (controller == null || !(switchWanted || lockWanted) || SessionBudget.isLocked()) return;
        if (!Boolean.TRUE.equals(Reflect.invoke(Reflect.invoke(controller, "getPlayerManager"), "isPlaying"))) {
            return;
        }
        Object aid;
        try {
            aid = Reflect.invoke(currentAweme(controller), "getAid");
        } catch (RuntimeException notAController) {
            return;
        }
        if (!(aid instanceof String) || ((String) aid).isEmpty()) return;
        // The player's own video, as a progress report would name it, so the tap that ends the
        // hold finds this player still current and hands the video back.
        Target previous = current;
        if (previous == null || previous.controller.get() != controller
                || !aid.equals(previous.awemeId)) {
            current = new Target(controller, (String) aid);
        }
        if (pauseForSwitchIfPlaying()) log("paused at the first frame of " + aid);
    }

    /**
     * Asked at the start of PlayerController's play method, with p0 and the Aweme it was asked to
     * play. On a cold start TikTok plays the first video a second time about 1.7 s after the
     * first-frame pause (#83, S22): the feed panel's resume, a new surface, a network change and
     * the end of a TopView ad all reach this method. While a pause switch or the app lock holds
     * this controller's video, the play is turned down before the player starts, so the video
     * doesn't play for the moment it took the next progress report to pause it again. The play
     * that starts a video before the hold has paused anything goes through, since the tap that
     * ends the hold resumes that player. Any thread.
     *
     * @return whether to answer the play the way TikTok answers one while casting
     */
    public static boolean refusePlay(Object controller, Object aweme) {
        try {
            Target owner = switchHeld;
            if (owner == null || controller == null || owner.controller.get() != controller
                    || !refusing()) return false;
            log("blocked TikTok's play of " + Reflect.invoke(aweme, "getAid"), true);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "The session hold could not answer TikTok's play", failure);
            return false;
        }
    }

    /**
     * Asked at the start of the player manager's own resume, which TikTok calls without the play
     * method from its feed events and as some sheets and dialogs close. Turned down for the
     * manager a pause switch or the app lock has paused, until the hold hands the video back.
     * Any thread.
     *
     * @return whether to skip the resume
     */
    public static boolean refuseResume(Object manager) {
        try {
            if (manager == null || switchHeld == null || switchManager.get() != manager
                    || !refusing()) return false;
            log("blocked TikTok's resume", true);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "The session hold could not answer TikTok's resume", failure);
            return false;
        }
    }

    /** Whether a held pause is still wanted and the call isn't the hold's own. */
    private static boolean refusing() {
        if (!(switchWanted || lockWanted)) return false;
        return !(ownCommand && Looper.myLooper() == Looper.getMainLooper());
    }

    /**
     * A debug log line for the device check, with the uptime it happened at. With {@code route}
     * it also names the first frames outside Hushfeed, read inside the message so the stack is
     * only walked with Log diagnostics on.
     */
    private static void log(String what, boolean route) {
        long at = android.os.SystemClock.uptimeMillis();
        Logger.printDebug(() -> "Session hold at " + at + " ms: " + what + (route ? caller() : ""));
    }

    private static void log(String what) {
        log(what, false);
    }

    /** The first few frames outside Hushfeed, so a debug log names the route a play came by. */
    private static String caller() {
        StringBuilder route = new StringBuilder(" from");
        int shown = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String type = frame.getClassName();
            if (type.startsWith("app.morphe.") || type.startsWith("java.") || type.startsWith("dalvik.")) continue;
            route.append(' ').append(type).append('.').append(frame.getMethodName());
            if (++shown == 5) break;
        }
        return route.toString();
    }

    /** The video the player last reported, or null before any report. */
    public static String currentAwemeId() {
        Target target = current;
        return target == null ? null : target.awemeId;
    }

    /**
     * Whether the player of the last reported video is playing it now, or null when there is no
     * such player or it has moved to another video. Reports stop while a video is paused, so the
     * last one names the video the reader paused.
     */
    public static Boolean currentPlaying() {
        Target target = current;
        if (target == null) return null;
        Object controller = target.controller.get();
        if (controller == null || !target.isCurrentCell(controller)) return null;
        Object playing = Reflect.invoke(Reflect.invoke(controller, "getPlayerManager"), "isPlaying");
        return playing instanceof Boolean ? (Boolean) playing : null;
    }

    /**
     * Plays or pauses the video on screen with TikTok's own controls, for a button that toggles it
     * the way a tap on the video does (the picture-in-picture window's). Does nothing while the
     * daily panel, a pause switch or the app lock holds the video, since each of those hands it
     * back itself. Main thread.
     *
     * @return whether TikTok's player took the command
     */
    public static boolean setCurrentPlaying(boolean play) {
        if (SessionBudget.isLocked() || switchWanted || lockWanted) return false;
        Target target = current;
        if (target == null) return false;
        Object controller = target.controller.get();
        if (controller == null || !target.isCurrentCell(controller)) return false;
        return control(Reflect.invoke(controller, "getPlayerManager"), !play);
    }

    /** The post the player of the last reported video has on screen, or null. */
    public static Object currentPlayingAweme() {
        Target target = current;
        Object controller = target == null ? null : target.controller.get();
        if (controller == null) return null;
        try {
            return currentAweme(controller);
        } catch (RuntimeException notAController) {
            return null;
        }
    }

    /** Runs on each visible hold sync, not only when the panel is first attached. */
    static void pauseIfPlaying() {
        Target target = current;
        if (target == null) return;
        Object controller = target.controller.get();
        if (!target.isCurrentCell(controller)) return;
        Object manager = Reflect.invoke(controller, "getPlayerManager");
        if (!Boolean.TRUE.equals(Reflect.invoke(manager, "isPlaying"))) return;
        // TikTok's feed play/pause control uses this same manager pair. Pause is queued by the
        // native engine, so successful intent is owned without requiring an immediate state flip.
        if (control(manager, true)) {
            held = target;
            heldManager = new WeakReference<>(manager);
            waitingForFocus = null;
            heldPauseObserved = false;
        }
    }

    /**
     * The pause switches want the video on screen stopped: the comments are open, or the feed is
     * waiting for a tap after a return. They ask for the audio focus, and TikTok 47.0.3's player
     * plays on through that, so they stop it the way the panel does, with TikTok's own pause: now
     * if it is playing, and again on any report of it playing, until {@link #releaseForSwitch}.
     * Main thread.
     */
    public static void pauseForSwitch() {
        switchWanted = true;
        if (pauseForSwitchIfPlaying()) log("paused the video for a pause switch");
    }

    /**
     * The switch is done. With {@code resume} the video it paused plays on from where it stopped,
     * but only while it is still the one on screen in the same player and no panel is up; a video
     * the reader had paused was never taken, so it is never started. A panel that came up in
     * between keeps the video stopped after it, for the reader to start. Going away passes false,
     * since TikTok stops its player itself then. Main thread.
     */
    public static void releaseForSwitch(boolean resume) {
        switchWanted = false;
        // The app lock still covers the screen, so the video stays stopped until it lifts.
        if (lockWanted) return;
        handBackSwitchPause(resume);
    }

    /**
     * The app lock covers TikTok: the video on screen stops the way the switches stop it, and
     * stays stopped on each report of it playing until {@link #releaseForLock}. Kept apart from
     * the switches' own wish so neither one letting go starts a video the other still holds.
     * Main thread.
     */
    public static void pauseForLock() {
        lockWanted = true;
        if (pauseForSwitchIfPlaying()) log("paused the video for the app lock");
    }

    /** The lock lifted. With {@code resume} the video it stopped plays on. Main thread. */
    public static void releaseForLock(boolean resume) {
        lockWanted = false;
        if (switchWanted) return;
        handBackSwitchPause(resume);
    }

    private static void handBackSwitchPause(boolean resume) {
        Target owner = switchHeld;
        Object manager = switchManager.get();
        switchHeld = null;
        switchManager.clear();
        if (!resume || owner == null || manager == null || SessionBudget.isLocked()) return;
        Object controller = owner.controller.get();
        if (current != owner || !owner.isCurrentCell(controller)
                || Reflect.invoke(controller, "getPlayerManager") != manager) return;
        // As in release: playing can still be the state before the queued pause, and the resume
        // queues behind it.
        if (!Boolean.TRUE.equals(Reflect.invoke(manager, "isPlaying"))
                && !Boolean.TRUE.equals(Reflect.invoke(manager, "isPaused"))) return;
        if (control(manager, false)) log("handed " + owner.awemeId + " back");
    }

    /** Pauses the current video for a switch or the lock. Answers whether it paused one. */
    private static boolean pauseForSwitchIfPlaying() {
        if (!(switchWanted || lockWanted) || SessionBudget.isLocked()) return false;
        Target target = current;
        if (target == null) return false;
        Object controller = target.controller.get();
        if (!target.isCurrentCell(controller)) return false;
        Object manager = Reflect.invoke(controller, "getPlayerManager");
        if (!Boolean.TRUE.equals(Reflect.invoke(manager, "isPlaying"))) return false;
        if (!control(manager, true)) return false;
        switchHeld = target;
        switchManager = new WeakReference<>(manager);
        return true;
    }

    private static void observeNativePlayback(Target owner, Object manager, boolean playing) {
        if (playing) relinquishAfterPlayback(owner, manager);
        else if (waitingForFocus == owner && heldManager.get() == manager) heldPauseObserved = true;
    }

    private static void relinquishAfterPlayback(Target owner, Object manager) {
        if (waitingForFocus == owner && heldManager.get() == manager) forgetHeld();
    }

    private static void forgetHeld() {
        waitingForFocus = null;
        heldPauseObserved = false;
        held = null;
        heldManager.clear();
    }

    /** Hands back only the still-current video this hold paused, while its feed is foreground. */
    static void release(boolean mayResume) {
        Target owner = held;
        Object manager = heldManager.get();
        if (owner == null) return;
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !activity.hasWindowFocus() || !FeedVisibility.isOnFeed(activity)) {
            forgetHeld();
            return;
        }
        Object controller = owner.controller.get();
        if (current != owner || !owner.isCurrentCell(controller)
                || manager == null || Reflect.invoke(controller, "getPlayerManager") != manager) {
            forgetHeld();
            return;
        }
        // Pause and resume share the native dispatcher. Playing can still be the state before
        // our queued pause; its matching resume must follow it when the hold is released early.
        // Read playing first so the PLAYING -> PAUSED transition cannot fall between the checks.
        if (!Boolean.TRUE.equals(Reflect.invoke(manager, "isPlaying"))) {
            if (!Boolean.TRUE.equals(Reflect.invoke(manager, "isPaused"))) {
                forgetHeld();
                return;
            }
            heldPauseObserved = true;
        }
        if (!mayResume && !hasNativeFocus()) {
            // Keep this exact weak owner through repeated detach calls until focus can return it.
            waitingForFocus = owner;
            return;
        }
        forgetHeld();
        control(manager, false);
    }

    private static boolean control(Object manager, boolean pause) {
        if (manager == null) return false;
        boolean outer = ownCommand;
        ownCommand = true;
        try {
            boolean done = pause ? pauseNative(manager) : resumeNative(manager);
            if (!done) Logger.printDebug(() -> "The session hold has no native playback control");
            return done;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "The session hold could not change native playback", failure);
            return false;
        } finally {
            ownCommand = outer;
        }
    }
}
