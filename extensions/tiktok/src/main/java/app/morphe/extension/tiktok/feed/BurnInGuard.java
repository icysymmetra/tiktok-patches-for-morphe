/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the feed's fixed controls from sitting on the same pixels of an OLED screen for hours,
 * as TikTok-Q, TikTokAntiBurn and TikTok You do.
 *
 * <p>The dim is the overlay pass's own fade at a lower level. Once the screen has gone
 * {@link #IDLE_AFTER_MS} without a touch, the buttons, caption, music disc, search bar and tabs
 * the fade covers are drawn at {@link #IDLE_LEVEL} (or at the chosen fade, if that's lower), and
 * the next finger brings them back. It only lowers what's drawn: Clear display, and whether a
 * faded control lets taps through, still follow Fade the video controls alone, so a dim never
 * brings back a control Clear display took away.
 *
 * <p>The shift moves the window's content around a small square, {@link #STEP_DP} at a time, every
 * {@link #SHIFT_EVERY_MS}. It's one view's translation, the content frame's, which TikTok doesn't
 * animate, where each control's would be fought over with TikTok's own animations. The strip it
 * uncovers at the edge is the window's own background.
 *
 * <p>Both run only over the feed or an opened video, not under Pause and not without the overlay
 * patch. Anywhere else nothing dims and the content goes back where it was. A window getting focus
 * back counts as a touch, so coming back from another screen doesn't land on dimmed controls.
 */
public final class BurnInGuard {
    public static final String OFF = "off";
    public static final String DIM = "dim";
    public static final String DIM_AND_SHIFT = "dim_shift";

    /** How long the screen goes untouched before the controls dim. */
    static final long IDLE_AFTER_MS = 5_000L;
    /** The opacity the controls dim to, as a percentage of what TikTok draws. */
    static final int IDLE_LEVEL = 25;
    /** How long the content stays in one place. */
    static final long SHIFT_EVERY_MS = 120_000L;
    static final float STEP_DP = 2f;
    /** Where the content goes, in steps: where TikTok put it, then once around it. */
    private static final int[][] PLACES = {
            {0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();
    /** Content views this class moved: the [x, y] they had, then the [x, y] written here. */
    private static final Map<View, float[]> MOVED = new WeakHashMap<>();
    private static final Runnable TICK = BurnInGuard::tick;

    private static Handler handler;
    /** The window the last guarded pass ran on, which a timed pass runs on again. */
    private static WeakReference<Activity> passed = new WeakReference<>(null);
    /** When a finger last touched a guarded window, or 0 while nothing is guarded. */
    private static long touchedAt;
    /**
     * A finger is on the screen. Holding still is still a touch: a press and hold for speed or
     * a paused video under a resting thumb mustn't dim, and the wait starts when it lifts.
     */
    private static boolean fingerDown;
    /** The last pass guarded a window, so a lifted finger asks for one to start the wait. */
    private static boolean guarding;
    private static boolean dimmed;
    /** When the next timed pass is due, or -1 when none is. */
    private static long tickAt = -1L;

    private BurnInGuard() {
    }

    static String mode() {
        String value = Settings.BURN_IN_GUARD.get();
        return DIM.equals(value) || DIM_AND_SHIFT.equals(value) ? value : OFF;
    }

    /**
     * Runs with every overlay pass. Answers the level the fade draws the controls at this pass,
     * {@code chosen} unless the screen has gone untouched, moves the content or puts it back, and
     * asks for a pass at the moment either next changes, since nothing lays out on its own while
     * a video just plays. {@code allowed} is false under Pause and without the overlay patch.
     */
    static int pass(Activity activity, int chosen, boolean allowed) {
        String mode = allowed ? mode() : OFF;
        boolean on = !OFF.equals(mode)
                && (FeedVisibility.isDetailPager(activity) || FeedVisibility.isOnFeed(activity));
        long now = SystemClock.uptimeMillis();
        if (on) {
            watch(activity);
            if (passed.get() != activity) {
                passed = new WeakReference<>(activity);
                touchedAt = now;
                // A finger that went down on a window that's gone may never have come up.
                fingerDown = false;
            }
            if (touchedAt == 0L) touchedAt = now;
        } else {
            touchedAt = 0L;
            fingerDown = false;
        }
        guarding = on;
        boolean canDim = on && chosen > IDLE_LEVEL;
        dimmed = canDim && !fingerDown && now - touchedAt >= IDLE_AFTER_MS;
        boolean shifting = on && DIM_AND_SHIFT.equals(mode);
        place(activity, shifting, now);
        // No dim is timed while a finger is down; its lift asks for the pass that times one.
        schedule(Math.min(canDim && !dimmed && !fingerDown ? touchedAt + IDLE_AFTER_MS : Long.MAX_VALUE,
                shifting ? (now / SHIFT_EVERY_MS + 1) * SHIFT_EVERY_MS : Long.MAX_VALUE));
        return dimmed ? IDLE_LEVEL : chosen;
    }

    /** The place the content is in at {@code now}, in steps of {@link #STEP_DP}. */
    static int[] placeAt(long now) {
        return PLACES[(int) ((now / SHIFT_EVERY_MS) % PLACES.length)];
    }

    private static void place(Activity activity, boolean shifting, long now) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        float[] held = MOVED.get(content);
        if (!shifting) {
            if (held != null) {
                MOVED.remove(content);
                if (content.getTranslationX() == held[2]) content.setTranslationX(held[0]);
                if (content.getTranslationY() == held[3]) content.setTranslationY(held[1]);
            }
            return;
        }
        float x = content.getTranslationX();
        float y = content.getTranslationY();
        if (held == null) {
            held = new float[]{x, y, x, y};
            MOVED.put(content, held);
        } else {
            // Something else wrote its own since the last pass; that's where the content is now.
            if (x != held[2]) held[0] = x;
            if (y != held[3]) held[1] = y;
        }
        int[] at = placeAt(now);
        float step = STEP_DP * content.getResources().getDisplayMetrics().density;
        held[2] = held[0] + at[0] * step;
        held[3] = held[1] + at[1] * step;
        if (x != held[2]) content.setTranslationX(held[2]);
        if (y != held[3]) content.setTranslationY(held[3]);
    }

    private static void schedule(long next) {
        if (next == Long.MAX_VALUE) next = -1L;
        if (next == tickAt) return;
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = next;
        if (next >= 0L) main.postAtTime(TICK, next);
    }

    private static void tick() {
        tickAt = -1L;
        Activity activity = passed.get();
        if (activity != null && !activity.isFinishing()) VideoOverlayHider.applyTo(activity);
    }

    /**
     * A finger on a guarded window ({@code action} is its MotionEvent action, or -1 for the window
     * getting focus back): the dim waits again, dimmed controls come back now, and a lift asks
     * for a pass so the wait is timed from it. Focus coming back counts as a lift, since a touch
     * that opened something else may have ended there without an up reaching this window.
     */
    private static void touched(int action) {
        touchedAt = SystemClock.uptimeMillis();
        boolean lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action < 0;
        if (action == MotionEvent.ACTION_DOWN) fingerDown = true;
        else if (lifted) fingerDown = false;
        if (!guarding || !(dimmed || lifted)) return;
        dimmed = false;
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = -1L;
        main.post(TICK);
    }

    private static Handler handler() {
        Looper main = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != main) handler = new Handler(main);
        return handler;
    }

    /** Wraps {@code activity}'s window callback once, as {@link TapThroughControls} does, to see touches. */
    private static void watch(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null || WRAPPED.containsKey(window)) return;
            Window.Callback inner = window.getCallback();
            if (inner == null) return;
            InvocationHandler watcher = (proxy, method, args) -> {
                try {
                    if (args != null && args.length == 1) {
                        String name = method.getName();
                        if (args[0] instanceof MotionEvent && "dispatchTouchEvent".equals(name)) {
                            touched(((MotionEvent) args[0]).getActionMasked());
                        } else if (Boolean.TRUE.equals(args[0]) && "onWindowFocusChanged".equals(name)) {
                            touched(-1);
                        }
                    }
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() != null ? ex.getCause() : ex;
                }
            };
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, watcher));
            WRAPPED.put(window, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not watch the screen for touches", ex);
        }
    }

    static long nextPassAtForTests() {
        return tickAt;
    }

    static void resetForTests() {
        if (handler != null) handler.removeCallbacks(TICK);
        tickAt = -1L;
        touchedAt = 0L;
        fingerDown = false;
        guarding = false;
        dimmed = false;
        passed = new WeakReference<>(null);
        MOVED.clear();
    }
}
