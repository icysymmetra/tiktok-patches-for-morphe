/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.Window;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lets touches through the controls Fade the video controls keeps in sight during Clear display
 * (#84). The reporter wanted the buttons and caption to stay faded on screen there, for the burn-in,
 * without a tap on them opening anything, since that breaks Clear display. A finger that goes down
 * on one goes to whatever is under it instead: the video's own layer, where TikTok's tap, press and
 * hold and pinch for Clear display live. The native photo close keeps its visible hit area, even
 * when its parent is faded. A second finger always goes through to the video.
 *
 * <p>Android has no flag that keeps a view drawn but out of the touch search, and TikTok's buttons
 * read touches in their own ways (the caption's links answer through the text's movement method,
 * whatever its clickable flag says), so turning their flags off would miss some and would have to
 * be put back exactly as TikTok had them. Instead the window's {@link Window.Callback} is wrapped
 * once, as {@link EdgeSwipeLevels} does, and while a finger goes down the marked views are moved far
 * out of its way, the event is dispatched and they're put straight back. A view group picks a
 * finger's target only as it goes down, so the rest of the gesture follows the view underneath, and
 * nothing is drawn in between, so nothing moves on screen. No state of TikTok's changes, which
 * leaves nothing to restore when Clear display ends.
 */
final class TapThroughControls {
    /** Far enough that no finger on any screen lands on a view moved by it. */
    static final float AWAY = 100000f;

    /** The views a finger passes through while {@link VideoOverlayHider#tapsGoThroughNow} holds. */
    private static final Map<View, Boolean> MARKED = new WeakHashMap<>();
    private static final Map<View, Boolean> PHOTO_EXITS = new WeakHashMap<>();
    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();

    private TapThroughControls() {
    }

    /** Marks a faded control as one a finger goes through, or takes the mark off. */
    static void mark(View view, boolean through) {
        if (view == null) return;
        if (through) MARKED.put(view, Boolean.TRUE);
        else MARKED.remove(view);
    }

    static boolean isMarked(View view) {
        return MARKED.containsKey(view);
    }

    static boolean anyMarked() {
        return !MARKED.isEmpty();
    }

    /** The native photo close is inside the faded rail. Keep only its current hit area usable. */
    static void photoExits(Activity activity, List<View> exits) {
        View decor = activity.getWindow().peekDecorView();
        if (decor == null) return;
        Iterator<View> previous = PHOTO_EXITS.keySet().iterator();
        while (previous.hasNext()) {
            View view = previous.next();
            if (view == null || !view.isAttachedToWindow() || view.getRootView() == decor) previous.remove();
        }
        if (exits == null) return;
        for (View view : exits) {
            if (view != null && view.isAttachedToWindow() && view.getRootView() == decor) {
                PHOTO_EXITS.put(view, Boolean.TRUE);
            }
        }
    }

    private static View photoExitAt(View decor, MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN || Settings.HIDE_CLEAR_DISPLAY_CONTROLS.get()) return null;
        Rect visible = new Rect();
        for (View view : PHOTO_EXITS.keySet()) {
            if (view == null || !view.isAttachedToWindow() || view.getRootView() != decor || !view.isEnabled()) continue;
            boolean shown = true;
            for (View part = view; part != null; ) {
                if (part.getVisibility() != View.VISIBLE || part.getAlpha() <= 0f) { shown = false; break; }
                ViewParent parent = part.getParent();
                part = parent instanceof View ? (View) parent : null;
            }
            if (shown && view.getGlobalVisibleRect(visible) && visible.contains((int) event.getX(), (int) event.getY())) return view;
        }
        return null;
    }

    private static boolean contains(View ancestor, View descendant) {
        for (View part = descendant; part != null; ) {
            if (part == ancestor) return true;
            ViewParent parent = part.getParent();
            part = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** Wraps {@code activity}'s window touch dispatch, once per window. */
    static void watch(Activity activity) {
        if (activity == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null || WRAPPED.containsKey(window)) return;
            Window.Callback inner = window.getCallback();
            if (inner == null) return;
            InvocationHandler handler = (proxy, method, args) -> {
                try {
                    if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                            && "dispatchTouchEvent".equals(method.getName())) {
                        return dispatch(window, inner, (MotionEvent) args[0]);
                    }
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() != null ? ex.getCause() : ex;
                }
            };
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, handler));
            WRAPPED.put(window, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not let taps through the faded controls", ex);
        }
    }

    /**
     * Hands {@code event} on, with the marked views in {@code window} out of the way when it puts a
     * finger down. The state is asked live rather than taken from the last layout pass, so the
     * controls take taps again the moment Clear display ends or Hushfeed is paused.
     */
    static boolean dispatch(Window window, Window.Callback inner, MotionEvent event) {
        int action = event.getActionMasked();
        if ((action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN)
                || MARKED.isEmpty() || !VideoOverlayHider.tapsGoThroughNow()) {
            return inner.dispatchTouchEvent(event);
        }
        View decor = window.peekDecorView();
        View photoExit = photoExitAt(decor, event);
        List<View> moved = new ArrayList<>();
        for (View view : MARKED.keySet()) {
            if (view != null && view.isAttachedToWindow() && view.getRootView() == decor
                    && !contains(view, photoExit)) moved.add(view);
        }
        float[] from = new float[moved.size()];
        int count = 0;
        try {
            for (; count < from.length; count++) {
                View view = moved.get(count);
                from[count] = view.getTranslationX();
                view.setTranslationX(from[count] + AWAY);
            }
            return inner.dispatchTouchEvent(event);
        } finally {
            for (int i = 0; i < count; i++) {
                moved.get(i).setTranslationX(from[i]);
            }
        }
    }
}
