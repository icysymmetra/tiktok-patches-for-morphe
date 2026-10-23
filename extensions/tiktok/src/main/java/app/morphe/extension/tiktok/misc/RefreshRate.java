/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.misc;

import android.content.Context;
import android.view.Display;
import android.view.WindowManager;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * What the window ends up asking the screen to run at.
 *
 * <p>When a video starts, TikTok reads its frame rate and asks the window for that, so a
 * 30 fps video puts a 120 Hz phone at 30 Hz for as long as the app is in front. Android
 * reads zero as no preference, which is the phone's own choice, so that is what goes in
 * instead. Only a request slower than the screen can manage is treated that way: an app that
 * asks for the rate it already has, or for more, is asking for something else and is left to
 * it. Nothing here raises anything; it only declines to lower.
 */
public final class RefreshRate {
    /** {@code WindowManager.LayoutParams.preferredRefreshRate}: no preference. */
    private static final float NO_PREFERENCE = 0f;
    /** Rates within this of the screen's best are not a cap worth undoing. */
    private static final float TOLERANCE = 1f;

    private static volatile float highest;

    private RefreshRate() {}

    /**
     * Replaces the store TikTok was about to make. Taking the whole store rather than
     * rewriting the value keeps the register intact for whatever the method does with it
     * next, which in the feed's case is TikTok's own record of the rate it last asked for.
     */
    public static void apply(WindowManager.LayoutParams params, float requested) {
        if (params == null) return;
        params.preferredRefreshRate = preferredRefreshRate(requested);
    }

    /** The rate to actually ask for. */
    static float preferredRefreshRate(float requested) {
        if (!Settings.UNCAP_REFRESH_RATE.get()) return requested;
        if (requested <= 0f) return requested;
        float best = highestSupported();
        // Nothing known about the screen: any request is treated as the cap it usually is.
        if (best <= 0f) return NO_PREFERENCE;
        return requested < best - TOLERANCE ? NO_PREFERENCE : requested;
    }

    /** Only used by the tests, which have to say what screen they are pretending to be. */
    static void setHighestSupported(float rate) {
        highest = rate;
    }

    /**
     * The fastest the screen says it can go. Read once: a display's mode list does not
     * change while the app is running, and this sits on the path of every video start.
     */
    private static float highestSupported() {
        float known = highest;
        if (known != 0f) return known;
        float found = -1f;
        try {
            Context context = Utils.getContext();
            // DisplayManager rather than an application context's WindowManager, which Android
            // 11 and later count as a use of a context with no display.
            android.hardware.display.DisplayManager displays = context == null ? null
                    : (android.hardware.display.DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            float[] rates = display == null ? null : display.getSupportedRefreshRates();
            if (rates != null) {
                for (float rate : rates) if (rate > found) found = rate;
            }
        } catch (Exception exception) {
            Logger.printException(() -> "Could not read what the screen can do", exception);
        }
        highest = found;
        return found;
    }
}
