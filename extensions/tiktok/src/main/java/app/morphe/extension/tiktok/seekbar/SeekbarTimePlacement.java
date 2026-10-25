/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Where the progress bar's time goes (#173): {@link #BESIDE} the bar, or {@link #ABOVE} its right
 * end, and the checks that decide whether the spot above is free.
 *
 * <p>Everything here works in window coordinates built from the views' own offsets, so it gives
 * the same answer on a view that isn't attached yet (a test's, or a cell TikTok has just
 * inflated) as on a laid-out one.
 */
final class SeekbarTimePlacement {
    static final String BESIDE = "beside";
    static final String ABOVE = "above";
    /** How often a row asks again whether the spot above is free. */
    static final long RECHECK_MS = 500L;

    private SeekbarTimePlacement() {
    }

    /** Whether the choice is Above the bar. Anything else, a stale value included, is beside. */
    static boolean wantsAbove() {
        return ABOVE.equals(Settings.SEEKBAR_TIME_PLACEMENT.get());
    }

    /** The view's top left corner in the window, summed up through its parents' offsets. */
    static void positionInWindow(View view, int[] out) {
        float x = 0f;
        float y = 0f;
        View current = view;
        while (true) {
            x += current.getLeft() + current.getTranslationX();
            y += current.getTop() + current.getTranslationY();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            View above = (View) parent;
            x -= above.getScrollX();
            y -= above.getScrollY();
            current = above;
        }
        out[0] = Math.round(x);
        out[1] = Math.round(y);
    }

    /** The view's bounds in the window. */
    static Rect boundsInWindow(View view) {
        int[] at = new int[2];
        positionInWindow(view, at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /**
     * Whether any text or icon on screen reaches into [spot]: a shown, visible TextView with
     * something in it, or an icon-sized ImageView with a picture, whose bounds overlap. The
     * caption, the creator's name and a hashtag line are text, and over the bar's right end sit
     * the Tako button and the music disc, so the time never goes where it would cover one of
     * them. A picture wider or taller than a third of the screen is the video's cover, not an
     * icon. The bar's own row is skipped.
     */
    static boolean textReaches(View root, Rect spot, View row) {
        if (root == row || root.getVisibility() != View.VISIBLE || root.getAlpha() <= 0.01f) {
            return false;
        }
        if (inTheWay(root) && Rect.intersects(boundsInWindow(root), spot)) {
            return true;
        }
        if (!(root instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (textReaches(group.getChildAt(i), spot, row)) return true;
        }
        return false;
    }

    private static boolean inTheWay(View view) {
        if (view instanceof SeekbarTimeLabel) return false;
        if (view instanceof TextView) return ((TextView) view).length() > 0;
        if (!(view instanceof ImageView) || ((ImageView) view).getDrawable() == null) return false;
        int screen = view.getResources().getDisplayMetrics().widthPixels;
        return view.getWidth() * 3 <= screen && view.getHeight() * 3 <= screen;
    }

    /**
     * The row and the ancestors above it that would cut off a time drawn over [spot], up to the
     * first ancestor whose bounds hold the spot (it clips at its own edge, which the spot is
     * inside). Null when no ancestor holds it: the spot is off screen or past every container.
     */
    @Nullable
    static List<ViewGroup> clipChain(LinearLayout row, Rect spot) {
        List<ViewGroup> chain = new ArrayList<>();
        ViewGroup current = row;
        while (current != null) {
            if (boundsInWindow(current).contains(spot)) return chain;
            chain.add(current);
            ViewParent parent = current.getParent();
            current = parent instanceof ViewGroup ? (ViewGroup) parent : null;
        }
        return null;
    }
}
