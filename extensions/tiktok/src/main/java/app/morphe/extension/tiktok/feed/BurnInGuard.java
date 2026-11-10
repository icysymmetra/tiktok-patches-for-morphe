/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.Window;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the feed's fixed controls from sitting on the same pixels of an OLED screen for hours,
 * as TikTok-Q, TikTokAntiBurn and TikTok You do.
 *
 * <p>The dim is the overlay pass's own fade at a lower level. Once {@link Settings#BURN_IN_DIM_AFTER}
 * seconds have gone by since the last wake, the parts of the screen picked in "What dims" are
 * drawn at {@link Settings#BURN_IN_DIM_LEVEL} (when the chosen fade is above it). It only lowers
 * what's drawn: Clear display, and whether a faded control lets taps through, still follow Fade
 * the video controls alone, so a dim never hides a control and never brings back one Clear
 * display took away.
 *
 * <p>Only what the reader picked wakes them (#191). Every touch used to, so someone scrolling
 * kept the rail bright though its icons sit on the same pixels on every video. A gesture is read
 * when it ends, from the window's own callback: a tap on a control the guard dims, a tap on the
 * video (pause, a double tap to like), a swipe or pinch, a still press past the long-press time,
 * and the window getting focus back are each a switch. A tap still does its job; nothing here
 * takes an event away from TikTok.
 *
 * <p>Cost. A swipe's lift does nothing. A move costs one distance compare until the finger has
 * gone past the touch slop and nothing after. A wake while the controls are up only moves the one
 * timer; a dim, and a wake while dimmed, run one overlay pass so every per-control rule (the
 * navigation floor, Clear display, hiding at 0) stays in {@link VideoOverlayHider}. Settings
 * reads are cached fields. {@link OverlayTiming} counts all of it for the export.
 *
 * <p>The shift moves the window's content around a small square, {@link #STEP_DP} at a time, every
 * {@link #SHIFT_EVERY_MS}. It's one view's translation, the content frame's, which TikTok doesn't
 * animate, where each control's would be fought over with TikTok's own animations. The strip it
 * uncovers at the edge is the window's own background.
 *
 * <p>Both run only over the feed or an opened video, not under Pause and not without the overlay
 * patch. Anywhere else nothing dims and the content goes back where it was.
 */
public final class BurnInGuard {
    public static final String OFF = "off";
    public static final String DIM = "dim";
    public static final String DIM_AND_SHIFT = "dim_shift";

    /** The parts of the screen "What dims" picks between, as VideoOverlayHider files its targets. */
    static final int RAIL = 0;
    static final int CAPTION = 1;
    static final int TOP = 2;
    static final int BOTTOM = 3;
    /** TikTok's subtitles: dimmed from full, whatever fade the controls are drawn at. */
    static final int SUBTITLES = 4;
    private static final int GROUPS = 5;

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
    /**
     * The controls the overlay pass filed under a group, for telling a tap on one from a tap on
     * the video. Kept across passes, since a pass only walks the tree while something asks it to;
     * a view that has gone is skipped at the test and dropped by the map.
     */
    private static final Map<View, Integer> CONTROLS = new WeakHashMap<>();
    private static final Rect HIT = new Rect();
    private static final Runnable TICK = BurnInGuard::tick;
    private static final Runnable HOLD = BurnInGuard::holdCheck;

    private static Handler handler;
    /** The window the last guarded pass ran on, which a timed pass runs on again. */
    private static WeakReference<Activity> passed = new WeakReference<>(null);
    /** When the controls last woke, or 0 while nothing is guarded. */
    private static long touchedAt;
    /** The last pass guarded a window. */
    private static boolean guarding;
    private static boolean dimmed;
    /** The timed pass is running, for OverlayTiming's count of the passes the guard asked for. */
    private static boolean ticking;
    /** When the next timed pass is due, or -1 when none is. */
    private static long tickAt = -1L;
    /**
     * A wake from dimmed posted the pass that draws the controls back up. Until it runs, a later
     * wake (a hold recognised, then its lift) only moves the touch: restarting the timer would
     * take the posted pass off the queue and leave them drawn dimmed.
     */
    private static boolean restoring;
    /** The last pass's answers, which a wake between passes times the next one from. */
    private static boolean couldDim;
    private static boolean shifted;
    private static long dimAfter = 5_000L;
    private static int dimLevel = 25;
    private static final boolean[] GROUP_ON = new boolean[GROUPS];

    // The open gesture. A gesture is read when it ends, never as it goes down: TapThroughControls
    // moves the views it lets taps through out of the way while a finger goes down.
    /** When the open gesture went down (MotionEvent time, the uptime clock), or 0 when none is open. */
    private static long downAt;
    private static float downX;
    private static float downY;
    /** It went past the touch slop: a swipe. */
    private static boolean moved;
    /** A second finger came down: a pinch, read as a swipe. */
    private static boolean multi;
    /** A still press past the long-press time, with the hold switch on: no dim until it ends. */
    private static boolean holding;
    /** Numbers gestures, so a hold check posted for one that ended does nothing. */
    private static int gestureId;
    private static int holdFor = -1;
    private static boolean holdPosted;
    private static WeakReference<Window> gestureWindow = new WeakReference<>(null);
    /** The activity of the open gesture's window, the one its wake has to run the pass on. */
    private static WeakReference<Activity> gestureOwner = new WeakReference<>(null);
    /**
     * When the overlay pass last walked the tree while guarding, so the controls of the cells on
     * screen are known for the tap test. With the fade at 100 and nothing else asking, the walk
     * doesn't run while the controls are up; this asks for it at most once a {@link #NOTE_EVERY_MS},
     * rather than on every layout.
     */
    private static long notedAt;
    static final long NOTE_EVERY_MS = 1_000L;
    /** Read each time: the reader can change the touch-and-hold delay in accessibility settings. */
    private static long longPressMs() {
        return ViewConfiguration.getLongPressTimeout();
    }

    private BurnInGuard() {
    }

    static String mode() {
        String value = Settings.BURN_IN_GUARD.get();
        return DIM.equals(value) || DIM_AND_SHIFT.equals(value) ? value : OFF;
    }

    /**
     * Runs with every overlay pass. Works out whether the controls are dimmed this pass, moves the
     * content or puts it back, and asks for a pass at the moment either next changes, since nothing
     * lays out on its own while a video just plays. {@code allowed} is false under Pause and without
     * the overlay patch. Answers {@code chosen}: the level each control is drawn at comes from
     * {@link #level}, per group.
     */
    static int pass(Activity activity, int chosen, boolean allowed) {
        // Any pass draws the current levels, the posted restore's job included.
        restoring = false;
        String mode = allowed ? mode() : OFF;
        boolean on = !OFF.equals(mode)
                && (FeedVisibility.isDetailPager(activity) || FeedVisibility.isOnFeed(activity));
        long now = SystemClock.uptimeMillis();
        if (on) {
            watch(activity);
            // While a detail pager opens or closes, the main activity behind it is on the feed
            // too and lays out as well. The timed pass belongs to the one in front.
            Activity held = passed.get();
            if (held != activity && (held == null || gone(held)
                    || activity.hasWindowFocus() || !held.hasWindowFocus())) {
                passed = new WeakReference<>(activity);
            }
            if (touchedAt == 0L) touchedAt = now;
        } else if (guarding || touchedAt != 0L) {
            touchedAt = 0L;
            holding = false;
            closeGesture();
            CONTROLS.clear();
            notedAt = 0L;
        }
        guarding = on;
        dimLevel = Math.max(10, Math.min(90, Settings.BURN_IN_DIM_LEVEL.get()));
        dimAfter = Math.max(2, Math.min(60, Settings.BURN_IN_DIM_AFTER.get())) * 1000L;
        GROUP_ON[RAIL] = Settings.BURN_IN_DIM_RAIL.get();
        GROUP_ON[CAPTION] = Settings.BURN_IN_DIM_CAPTION.get();
        GROUP_ON[TOP] = Settings.BURN_IN_DIM_TOP.get();
        GROUP_ON[BOTTOM] = Settings.BURN_IN_DIM_BOTTOM.get();
        GROUP_ON[SUBTITLES] = Settings.BURN_IN_DIM_SUBTITLES.get();
        boolean anyControls = GROUP_ON[RAIL] || GROUP_ON[CAPTION] || GROUP_ON[TOP] || GROUP_ON[BOTTOM];
        // Subtitles are drawn at full whatever the chosen fade, so they can dim below it too.
        couldDim = on && ((anyControls && chosen > dimLevel) || GROUP_ON[SUBTITLES]);
        // A still finger held past the long-press time while the controls are up: held, they stay.
        if (on && !holding && stillFor(now) >= longPressMs() && Settings.BURN_IN_WAKE_HOLD.get()) {
            holding = true;
            if (dimmed) OverlayTiming.woke(OverlayTiming.WAKE_HOLD);
            else OverlayTiming.restarted(OverlayTiming.WAKE_HOLD);
        }
        boolean was = dimmed;
        dimmed = couldDim && !holding && now - touchedAt >= dimAfter;
        if (dimmed && !was) {
            OverlayTiming.dimmed();
            // A dim landing under a still finger: once the press is long, the hold brings them back.
            if (stillFor(now) >= 0 && Settings.BURN_IN_WAKE_HOLD.get()) postHoldCheck();
        }
        shifted = on && DIM_AND_SHIFT.equals(mode);
        place(activity, shifted, now);
        schedule(nextDue(now));
        return chosen;
    }

    /**
     * The level a control in {@code group} is drawn at this pass: the dim when the guard has
     * dimmed and that group is picked, else {@code chosen}. Never 0, so a hide stays the chosen
     * fade's alone.
     */
    static int level(int group, int chosen) {
        return dimmed && GROUP_ON[group] && chosen > dimLevel ? dimLevel : chosen;
    }

    /** The guard has the controls dimmed, so the overlay pass has to walk the tree to draw them so. */
    static boolean dimsNow() {
        return dimmed;
    }

    /**
     * The overlay pass has to walk the tree this time so the tap test knows the controls on
     * screen: guarding, up, and not walked for {@link #NOTE_EVERY_MS}. Dimmed, the walk runs
     * anyway.
     */
    static boolean wantsControlWalk() {
        return guarding && !dimmed && SystemClock.uptimeMillis() - notedAt >= NOTE_EVERY_MS;
    }

    /** The overlay pass walked the tree, noting every control it filed. */
    static void walked(Activity activity) {
        if (!guarding) return;
        notedAt = SystemClock.uptimeMillis();
        // This walk notes every control on the window again, so nothing decided from an earlier
        // layout (a slot measured while TikTok's Clear display had it gone) outlives it.
        View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
        Iterator<View> noted = CONTROLS.keySet().iterator();
        while (noted.hasNext()) {
            View view = noted.next();
            if (view == null || !view.isAttachedToWindow() || view.getRootView() == decor) noted.remove();
        }
    }

    private static boolean gone(Activity activity) {
        return activity.isFinishing() || activity.isDestroyed();
    }

    /** Whether noting the rail's buttons does anything, so the pass can skip finding them otherwise. */
    static boolean notesRail() {
        return guarding && GROUP_ON[RAIL];
    }

    /** The overlay pass names a control it fades, under its group, for the tap test. */
    static void noteControl(View view, int group) {
        if (!guarding || view == null) return;
        Integer had = CONTROLS.get(view);
        if (had == null || had != group) CONTROLS.put(view, group);
    }

    /** A pass the guard's own timer asked for is running. */
    static boolean askedNow() {
        return ticking;
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

    /** When the next change is due after {@code now}: the dim, the next shift, or neither. */
    private static long nextDue(long now) {
        long due = couldDim && !dimmed && !holding ? touchedAt + dimAfter : Long.MAX_VALUE;
        if (shifted) due = Math.min(due, (now / SHIFT_EVERY_MS + 1) * SHIFT_EVERY_MS);
        return due;
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
        restoring = false;
        Activity activity = passed.get();
        if (activity == null || gone(activity)) return;
        ticking = true;
        try {
            VideoOverlayHider.applyTo(activity);
        } finally {
            ticking = false;
        }
    }

    /**
     * Something a switch counts happened: the wait starts again from now. Dimmed controls come
     * back through a pass posted after this event, never run inside TikTok's dispatch; controls
     * already up only have the timer moved.
     */
    private static void wake(int reason) {
        if (reason >= 0) {
            if (dimmed) OverlayTiming.woke(reason);
            else OverlayTiming.restarted(reason);
        }
        // The pass runs on the window that was touched, not on one that laid out behind it.
        Activity owner = gestureOwner.get();
        if (owner != null && owner != passed.get() && !gone(owner)) passed = gestureOwner;
        touchedAt = SystemClock.uptimeMillis();
        if (dimmed) {
            dimmed = false;
            Handler main = handler();
            main.removeCallbacks(TICK);
            tickAt = -1L;
            restoring = true;
            main.post(TICK);
        } else if (!restoring) {
            schedule(nextDue(touchedAt));
        }
    }

    /** How long the open gesture has been a still single finger, or -1 when it isn't. */
    private static long stillFor(long now) {
        return downAt != 0L && !moved && !multi ? now - downAt : -1L;
    }

    /** One finger's event on a guarded window, as the window's callback is handed it. */
    private static void onTouch(Window window, WeakReference<Activity> owner, MotionEvent event, int slopSquared) {
        if (!guarding) return;
        OverlayTiming.touched();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // A gesture whose end went to another window ends here at the latest.
                closeGesture();
                gestureId++;
                downAt = event.getEventTime();
                downX = event.getX();
                downY = event.getY();
                if (gestureWindow.get() != window) gestureWindow = new WeakReference<>(window);
                gestureOwner = owner;
                // Only while dimmed: up, the dim's own decision sees the hold.
                if (dimmed && Settings.BURN_IN_WAKE_HOLD.get()) postHoldCheck();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (downAt != 0L && !multi) {
                    multi = true;
                    cancelHoldCheck();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (downAt != 0L && !moved && !multi && pastSlop(event, slopSquared)) {
                    moved = true;
                    cancelHoldCheck();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (downAt == 0L || gestureWindow.get() != window) {
                    closeGesture();
                    break;
                }
                if (!moved && !multi && pastSlop(event, slopSquared)) moved = true;
                finish(window, owner.get(), event);
                break;
            case MotionEvent.ACTION_CANCEL:
                // System gesture navigation, focus going, or EdgeSwipeLevels taking the drag.
                closeGesture();
                break;
            default:
                break;
        }
    }

    private static boolean pastSlop(MotionEvent event, int slopSquared) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        return dx * dx + dy * dy > slopSquared;
    }

    /** The open gesture lifted: what it was decides which switch, if any, wakes the controls. */
    private static void finish(Window window, Activity owner, MotionEvent event) {
        boolean wasHolding = holding;
        boolean longStill = stillFor(event.getEventTime()) >= longPressMs();
        boolean swipe = moved || multi;
        holding = false;
        endGesture();
        if (wasHolding) {
            // Held, they stayed up; the wait starts at the lift.
            OverlayTiming.gesture(OverlayTiming.GESTURE_HOLD);
            wake(-1);
            return;
        }
        if (longStill) {
            // A press and hold, whatever it held: with its switch off it wakes nothing, rather
            // than reading as a tap on what was under the finger (the 2x lock, a long-pressed like).
            OverlayTiming.gesture(OverlayTiming.GESTURE_HOLD);
            if (Settings.BURN_IN_WAKE_HOLD.get()) wake(OverlayTiming.WAKE_HOLD);
            return;
        }
        if (swipe) {
            OverlayTiming.gesture(OverlayTiming.GESTURE_SCROLL);
            if (Settings.BURN_IN_WAKE_SCROLL.get()) wake(OverlayTiming.WAKE_SCROLL);
            return;
        }
        int kind = tapKind(window, owner, event.getX(), event.getY());
        OverlayTiming.gesture(kind);
        if (kind == OverlayTiming.GESTURE_CONTROL) {
            if (Settings.BURN_IN_WAKE_CONTROL.get()) wake(OverlayTiming.WAKE_CONTROL);
        } else if (kind == OverlayTiming.GESTURE_VIDEO) {
            if (Settings.BURN_IN_WAKE_VIDEO.get()) wake(OverlayTiming.WAKE_VIDEO);
        }
    }

    /**
     * Where a tap at window point ({@code x}, {@code y}) landed: on a control of a picked group,
     * on the video, or on a sheet or page over the feed, which counts as neither.
     *
     * <p>Only views in this window's tree, drawn (not GONE, not at 0, as TikTok's Clear display
     * leaves them), and not let through by the faded Clear display count, each by the rect it shows
     * on screen, which takes the rail's scale, a parent's clipping (the neighbouring cells TikTok
     * keeps laid out off screen) and the shift into account. Over a detail page shown in the main
     * activity, the feed's cells are still attached under it, so only views inside the page count.
     */
    private static int tapKind(Window window, Activity owner, float x, float y) {
        View decor = window.peekDecorView();
        if (decor == null) return OverlayTiming.GESTURE_VIDEO;
        if (owner != null && FeedVisibility.isCommentSheetVisible(owner)) return OverlayTiming.GESTURE_COVERED;
        View page = FeedVisibility.visibleDetailPage();
        if (page != null && page.getRootView() != decor) page = null;
        int px = (int) x;
        int py = (int) y;
        boolean through = VideoOverlayHider.tapsGoThroughNow();
        for (Map.Entry<View, Integer> entry : CONTROLS.entrySet()) {
            View view = entry.getKey();
            Integer group = entry.getValue();
            if (view == null || group == null || !GROUP_ON[group]) continue;
            if (!view.isAttachedToWindow() || view.getRootView() != decor) continue;
            if (page != null && !inside(page, view)) continue;
            if (!view.getGlobalVisibleRect(HIT) || !HIT.contains(px, py)) continue;
            if (takesTap(view, through)) return OverlayTiming.GESTURE_CONTROL;
        }
        return OverlayTiming.GESTURE_VIDEO;
    }

    /**
     * Drawn, and not one a finger goes through: the faded Clear display marks the rail's column,
     * and the noted buttons sit inside it. Whether taps go through is asked live, as
     * TapThroughControls does, since the marks are only rewritten by the next walk.
     */
    private static boolean takesTap(View view, boolean through) {
        for (View part = view; part != null; ) {
            if (part.getVisibility() != View.VISIBLE || part.getAlpha() <= 0f) return false;
            if (through && TapThroughControls.isMarked(part)) return false;
            ViewParent parent = part.getParent();
            part = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    private static boolean inside(View ancestor, View view) {
        for (View part = view; part != null; ) {
            if (part == ancestor) return true;
            ViewParent parent = part.getParent();
            part = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** The window gained or lost focus: whatever gesture was open has ended. */
    private static void focusChanged(WeakReference<Activity> owner, boolean hasFocus) {
        if (!guarding) return;
        if (hasFocus) {
            gestureOwner = owner;
            // The window coming back in front owns the timed pass again: a dialog over an opened
            // video took focus from both, and the feed behind may have taken it meanwhile.
            Activity front = owner.get();
            if (front != null && !gone(front)) passed = owner;
        }
        boolean wasHolding = holding;
        holding = false;
        endGesture();
        if (hasFocus && Settings.BURN_IN_WAKE_RETURN.get()) {
            wake(OverlayTiming.WAKE_RETURN);
        } else if (wasHolding) {
            wake(-1);
        }
    }

    /**
     * Ends the open gesture without reading it. A hold it left on would keep the controls up with
     * no timer, so its wait starts now.
     */
    private static void closeGesture() {
        boolean wasHolding = holding;
        holding = false;
        endGesture();
        if (wasHolding && guarding) wake(-1);
    }

    private static void endGesture() {
        downAt = 0L;
        moved = false;
        multi = false;
        cancelHoldCheck();
    }

    private static void postHoldCheck() {
        if (downAt == 0L) return;
        Handler main = handler();
        if (holdPosted) main.removeCallbacks(HOLD);
        holdFor = gestureId;
        holdPosted = true;
        main.postAtTime(HOLD, downAt + longPressMs());
    }

    private static void cancelHoldCheck() {
        if (!holdPosted) return;
        holdPosted = false;
        handler().removeCallbacks(HOLD);
    }

    /** The long-press time passed with the finger still down and still: a hold wakes them. */
    private static void holdCheck() {
        holdPosted = false;
        if (holdFor != gestureId || !guarding || holding || stillFor(SystemClock.uptimeMillis()) < 0L) return;
        holding = true;
        wake(OverlayTiming.WAKE_HOLD);
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
            if (window == null) return;
            // Counted here, on the main thread: the export reads only the number, never the
            // weak maps, which a read from its thread could corrupt.
            OverlayTiming.watchers((WRAPPED.containsKey(window) ? 1 : 0)
                    + (TapThroughControls.watches(window) ? 1 : 0)
                    + (EdgeSwipeLevels.watches(window) ? 1 : 0));
            if (WRAPPED.containsKey(window)) return;
            Window.Callback inner = window.getCallback();
            if (inner == null) return;
            int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            int slopSquared = slop * slop;
            WeakReference<Activity> owner = new WeakReference<>(activity);
            InvocationHandler watcher = (proxy, method, args) -> {
                try {
                    if (args != null && args.length == 1) {
                        Object arg = args[0];
                        if (arg instanceof MotionEvent) {
                            if ("dispatchTouchEvent".equals(method.getName())) {
                                onTouch(window, owner, (MotionEvent) arg, slopSquared);
                            }
                        } else if (arg instanceof Boolean && "onWindowFocusChanged".equals(method.getName())) {
                            focusChanged(owner, (Boolean) arg);
                        }
                    }
                } catch (Throwable ex) {
                    Logger.printException(() -> "Burn-in guard could not read a touch", ex);
                }
                try {
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() != null ? ex.getCause() : ex;
                }
            };
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, watcher));
            WRAPPED.put(window, Boolean.TRUE);
            OverlayTiming.watchers(1 + (TapThroughControls.watches(window) ? 1 : 0)
                    + (EdgeSwipeLevels.watches(window) ? 1 : 0));
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not watch the screen for touches", ex);
        }
    }

    static long nextPassAtForTests() {
        return tickAt;
    }

    static boolean holdingForTests() {
        return holding;
    }

    static void resetForTests() {
        if (handler != null) {
            handler.removeCallbacks(TICK);
            handler.removeCallbacks(HOLD);
        }
        tickAt = -1L;
        restoring = false;
        touchedAt = 0L;
        guarding = false;
        dimmed = false;
        ticking = false;
        couldDim = false;
        shifted = false;
        holding = false;
        holdPosted = false;
        holdFor = -1;
        downAt = 0L;
        moved = false;
        multi = false;
        gestureWindow = new WeakReference<>(null);
        gestureOwner = new WeakReference<>(null);
        notedAt = 0L;
        passed = new WeakReference<>(null);
        MOVED.clear();
        CONTROLS.clear();
    }
}
