/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feed;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the controls of the faded Clear display (#84) on screen. TikTok's Clear display doesn't
 * only take the rail's column and the caption frame to 0: in one frame it sets each button
 * wrapper inside the column to alpha 0 and GONE, the wrapper inside the search bar GONE, and the
 * caption frame and the music disc GONE, so holding the column and the frames at the chosen level
 * showed nothing (emulator, 47.1.4, 2026-10-10). Leaving, it sets the wrappers back to VISIBLE at
 * 0 and animates them up to full over about a third of a second, after a pause of about as long.
 *
 * <p>Which of them were showing is noted as TikTok's clear-mode event arrives, before TikTok
 * handles it, so a wrapper TikTok keeps away outside Clear display (it hides some per post) stays
 * away. While the faded Clear display lasts, the noted views are kept VISIBLE, the wrappers at
 * full so the column's level is what shows. If the fade stops while TikTok is still clear (Pause,
 * the level set to 0 or 100, or Keep faded controls in Clear display turned off), each gets back
 * what TikTok had given it. When TikTok leaves Clear
 * display it shows them itself, and the wrappers are held at full through its animation up from
 * 0 so they don't blink out under the column's level.
 */
final class ClearDisplayShownControls {
    /** How long the wrappers stay at full after Clear display ends, well past TikTok's animation. */
    static final long EXIT_HOLD_MS = 1000;

    /**
     * Each kept view with [0] the visibility and [1] the alpha TikTok last gave it, [2] whether its
     * alpha is kept at full too (a wrapper, not a frame the fade holds itself), [3] when the hold
     * after leaving Clear display started, 0 before that, and [4] whether TikTok's own values were
     * put back while it stayed clear, so the view is shown again if the fade comes back on.
     */
    private static final Map<View, double[]> KEPT = new WeakHashMap<>();
    /** The clear-mode event last noted: TikTok may hand one event to more than one listener. */
    private static WeakReference<Object> noted = new WeakReference<>(null);

    private ClearDisplayShownControls() {
    }

    /**
     * Notes, as Clear display starts, which of the current video's controls are showing: each
     * child of the {@code columns} (the rail's, the search bar's), and the {@code frames}
     * themselves. Views from an earlier video are let go as they are; TikTok sets them when it
     * shows that video again.
     */
    static void note(Object event, List<View> columns, List<View> frames) {
        if (event != null && noted.get() == event) return;
        noted = new WeakReference<>(event);
        KEPT.clear();
        for (View column : columns) {
            if (!(column instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) column;
            for (int i = 0; i < group.getChildCount(); i++) {
                keepIfShown(group.getChildAt(i), true);
            }
        }
        for (View frame : frames) {
            keepIfShown(frame, false);
        }
    }

    private static void keepIfShown(View view, boolean wrapper) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        KEPT.put(view, new double[]{View.VISIBLE, view.getAlpha(), wrapper ? 1 : 0, 0, 0});
    }

    /** Whether anything is kept, which keeps the frame pass on the root to go on keeping it. */
    static boolean anyKept() {
        return !KEPT.isEmpty();
    }

    /**
     * One pass over the kept views. {@code faded} is the faded Clear display being on now, and
     * {@code clear} TikTok's Clear display, live or carried across a swipe, whatever Pause says.
     */
    static void apply(boolean faded, boolean clear) {
        if (KEPT.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        Iterator<Map.Entry<View, double[]>> entries = KEPT.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<View, double[]> entry = entries.next();
            View view = entry.getKey();
            double[] held = entry.getValue();
            boolean wrapper = held[2] != 0;
            if (view == null) {
                entries.remove();
            } else if (faded) {
                held[3] = 0;
                held[4] = 0;
                if (view.getVisibility() != View.VISIBLE) {
                    held[0] = view.getVisibility();
                    view.setVisibility(View.VISIBLE);
                }
                if (wrapper && view.getAlpha() != 1f) {
                    held[1] = view.getAlpha();
                    view.setAlpha(1f);
                }
            } else if (clear) {
                // TikTok is still clear: its own look back, once, on whatever this class changed.
                if (held[4] != 0) continue;
                held[4] = 1;
                if (held[0] != View.VISIBLE && view.getVisibility() == View.VISIBLE) {
                    view.setVisibility((int) held[0]);
                }
                if (wrapper && view.getAlpha() == 1f && held[1] != 1) view.setAlpha((float) held[1]);
            } else if (!wrapper || held[4] != 0) {
                entries.remove();
            } else {
                if (held[3] == 0) held[3] = now;
                if (now - held[3] > EXIT_HOLD_MS) {
                    entries.remove();
                } else if (view.getAlpha() != 1f) {
                    view.setAlpha(1f);
                }
            }
        }
    }

    static void resetForTests() {
        KEPT.clear();
        noted = new WeakReference<>(null);
    }
}
