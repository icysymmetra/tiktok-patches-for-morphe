/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Turn off screen transitions: a screen of TikTok's that opens over another, like a video from a
 * profile, search or the settings, appears and closes without its slide.
 *
 * <p>TikTok asks Android for a slide with {@code overridePendingTransition} as it opens and closes
 * a screen, and the theme gives every other one a default animation. That call outranks the open
 * and close overrides Android 14 added. So each screen asks for no pending transition as it comes
 * to the front and as it closes, after TikTok has asked for its own, since the last one asked
 * wins. The Android 14 overrides are set as well: the back gesture's preview reads those.
 *
 * <p>Android calls the plain callbacks from inside the screen's own onCreate, onResume and
 * onPause, at their call to super, and TikTok's screens run code of their own after that call
 * (BaseActivity hands onResume to a list of processors on both sides of it, and some screens ask
 * for their slide right there). From Android 10 the same requests go again from the callbacks
 * Android makes once those methods have returned, so TikTok's request can't come after ours.
 *
 * <p>Swipes inside one screen, like For You to a profile, follow the finger and aren't touched.
 *
 * <p>The callbacks are registered from the host application before any screen exists, so a link
 * that opens a video straight away is covered too. Nothing here may throw into TikTok's screens.
 * Off, paused, or a failure in here, and screens slide as before.
 */
public final class ScreenTransitions {
    private static final String FAMILY = "screen transitions";
    private static boolean installed;

    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {
                    created(activity);
                }

                // Android 10 and later: after the screen's own onCreate has returned.
                @Override public void onActivityPostCreated(Activity activity, Bundle state) {
                    created(activity);
                }

                @Override public void onActivityStarted(Activity activity) {
                }

                @Override public void onActivityResumed(Activity activity) {
                    resumed(activity);
                }

                @Override public void onActivityPostResumed(Activity activity) {
                    resumed(activity);
                }

                @Override public void onActivityPaused(Activity activity) {
                    paused(activity);
                }

                @Override public void onActivityPostPaused(Activity activity) {
                    paused(activity);
                }

                @Override public void onActivityStopped(Activity activity) {
                }

                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                }

                @Override public void onActivityDestroyed(Activity activity) {
                }
            };

    private ScreenTransitions() {
    }

    /** Called from the host application's attachBaseContext with the application itself. */
    public static void install(Context context) {
        try {
            if (installed || !(context instanceof Application)) return;
            installed = true;
            ((Application) context).registerActivityLifecycleCallbacks(CALLBACKS);
            HookStatus.bound(FAMILY, "screen callbacks");
        } catch (Throwable error) {
            Logger.printInfo(() -> "Turn off screen transitions could not follow TikTok's screens: " + error);
        }
    }

    /** As each screen is created: Android 14's open and close overrides, for the back preview. */
    static void created(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT < 34 || !on()) return;
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            HookStatus.bound(FAMILY, "screen overrides");
        } catch (Throwable failure) {
            Logger.printException(() -> "Turn off screen transitions: could not set a screen's overrides", failure);
        }
    }

    /**
     * As a screen comes to the front: a new one over the screen that opened it, or the one under a
     * screen that closed. Android takes the request only from a screen in front.
     */
    @SuppressWarnings("deprecation")
    static void resumed(Activity activity) {
        try {
            if (!on()) return;
            activity.overridePendingTransition(0, 0);
            HookStatus.bound(FAMILY, "screen opened still");
        } catch (Throwable failure) {
            Logger.printException(() -> "Turn off screen transitions: could not open a screen still", failure);
        }
    }

    /** As a screen leaves the front. A closing one asks for no transition, after TikTok asked for its own. */
    @SuppressWarnings("deprecation")
    static void paused(Activity activity) {
        try {
            if (!activity.isFinishing() || !on()) return;
            activity.overridePendingTransition(0, 0);
            HookStatus.bound(FAMILY, "screen closed still");
        } catch (Throwable failure) {
            Logger.printException(() -> "Turn off screen transitions: could not close a screen still", failure);
        }
    }

    private static boolean on() {
        return Settings.TURN_OFF_SCREEN_TRANSITIONS.get();
    }

    static void resetForTests() {
        installed = false;
    }
}
