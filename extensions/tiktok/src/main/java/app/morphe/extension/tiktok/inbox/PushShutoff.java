/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import android.app.Notification;
import android.os.Bundle;
import android.os.PowerManager;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Turn off push notifications, the third switch of Notification controls.
 *
 * <p>While it's on, three things change. InitPushTask, the startup task that sets up TikTok's
 * push service (the push SDK's init, its notification channel restore and its system event
 * receiver), returns before doing any of it, so that launch runs with no push service. Every
 * notification TikTok's code hands NotificationManager is dropped unless it's ongoing or carries
 * a media session, which is how media controls, a running upload or a LIVE broadcast are posted.
 * And every wake lock TikTok's code takes is skipped, apart from the {@link #KEPT_TAGS}.
 *
 * <p>None of it outlasts the switch. A switch answers off while Hushfeed is paused, so every call
 * then goes through as TikTok made it, and push setup runs again on the first launch after the
 * switch goes off or Hushfeed is paused. What doesn't come back is what arrived while it was on:
 * a dropped notification is never posted later. TikTok's in-app message connection isn't
 * touched, so messages are there once the app is open.
 */
public final class PushShutoff {
    /**
     * Wake locks taken for something the person started: hosting a LIVE, whose service keeps the
     * stream going with the screen off, and WorkManager's foreground work, which shows its own
     * notification while it runs. WorkManager puts "WorkManager: " in front of every tag it makes.
     */
    static final String[] KEPT_TAGS = {"Live::AnchorWakeLock", "WorkManager: ProcessorForegroundLck"};

    /** The tag each wake lock was made with, so an acquire can tell a kept lock from the rest. */
    private static final Map<PowerManager.WakeLock, String> TAGS = new WeakHashMap<>();
    /** Wake locks with an acquire this class skipped. */
    private static final Map<PowerManager.WakeLock, Boolean> SKIPPED = new WeakHashMap<>();

    private PushShutoff() {
    }

    /**
     * Whether the switch is on. The notify filter is shared with Block suggested video
     * notifications, so it can be patched in without Notification controls, and then a value
     * restored from a backup must not reach it.
     */
    static boolean isOn() {
        return SettingsStatus.notificationControlsEnabled && Settings.TURN_OFF_PUSH_NOTIFICATIONS.get();
    }

    /** The guard at the start of InitPushTask.run: true skips TikTok's push setup for this launch. */
    public static boolean skipPushSetup() {
        return isOn();
    }

    /** Whether the notify filter drops this notification rather than posting it. */
    public static boolean dropsFromDrawer(Notification notification) {
        return notification != null && isOn() && !isOngoing(notification);
    }

    /** A notification for something still running, which the switch leaves alone. */
    static boolean isOngoing(Notification notification) {
        if ((notification.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
            return true;
        }
        Bundle extras = notification.extras;
        return extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }

    /** Stands in for PowerManager.newWakeLock and remembers the tag. A null manager throws as before. */
    public static PowerManager.WakeLock newWakeLock(PowerManager manager, int levelAndFlags, String tag) {
        PowerManager.WakeLock lock = manager.newWakeLock(levelAndFlags, tag);
        if (lock != null) {
            synchronized (TAGS) {
                TAGS.put(lock, tag);
            }
        }
        return lock;
    }

    public static void acquire(PowerManager.WakeLock lock) {
        if (skips(lock)) return;
        lock.acquire();
    }

    public static void acquire(PowerManager.WakeLock lock, long timeout) {
        if (skips(lock)) return;
        lock.acquire(timeout);
    }

    public static void release(PowerManager.WakeLock lock) {
        if (releasesNothing(lock)) return;
        lock.release();
    }

    public static void release(PowerManager.WakeLock lock, int flags) {
        if (releasesNothing(lock)) return;
        lock.release(flags);
    }

    /** Whether this acquire is skipped, noting the lock when it is. A null lock goes on and throws as before. */
    private static boolean skips(PowerManager.WakeLock lock) {
        if (lock == null || !isOn()) return false;
        String tag;
        synchronized (TAGS) {
            tag = TAGS.get(lock);
        }
        for (String kept : KEPT_TAGS) {
            if (kept.equals(tag)) return false;
        }
        synchronized (SKIPPED) {
            SKIPPED.put(lock, Boolean.TRUE);
        }
        return true;
    }

    /**
     * A release with nothing behind it. TikTok releases a lock it believes it acquired, and a
     * reference counted lock released more often than it was acquired throws "WakeLock
     * under-locked" (WorkManager's command handler, for one, releases without asking isHeld()
     * first). So a release of a lock
     * that isn't held, after an acquire of it was skipped, stops here. A held lock is always
     * released, whoever is holding it: the switch only ever lets go sooner. This holds while
     * paused too, since the skipped acquire it answers for has already happened.
     */
    private static boolean releasesNothing(PowerManager.WakeLock lock) {
        if (lock == null || lock.isHeld()) return false;
        synchronized (SKIPPED) {
            return SKIPPED.containsKey(lock);
        }
    }
}
