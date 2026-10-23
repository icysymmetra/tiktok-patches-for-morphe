/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings;

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

/**
 * Padding for the system bars on the injected settings screen.
 *
 * <p>TikTok 46.2.3 targets SDK 36, so the app is drawn edge to edge whether it asks to be or not,
 * and there is no opting out from target 35. The two calls that used to colour the bars,
 * {@link android.view.Window#setStatusBarColor} and {@link
 * android.view.Window#setNavigationBarColor}, do nothing from that target either. Left alone, the
 * settings screen puts its first row under the status bar and its last under the navigation bar.
 *
 * <p>The padding goes on the root view rather than the list inside it, because a view paints its
 * background across its padding: the screen still fills the display, and only the rows move.
 */
public final class SystemBarInsets {
    private SystemBarInsets() {
    }

    /** Keeps {@code view} padded by the system bars and any display cutout. */
    public static void applyTo(View view) {
        if (view == null) return;
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            Rect bars = systemBarsAndCutout(insets);
            int bottom = bars.bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                if (imeBottom > bottom) bottom = imeBottom;
            }
            target.setPadding(bars.left, bars.top, bars.right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    /** Current bars and cutout for an app-owned overlay attached to {@code view}'s window. */
    public static Rect current(View view) {
        return view == null ? new Rect() : systemBarsAndCutout(view.getRootWindowInsets());
    }

    /**
     * The space the bars and the cutout take, in pixels.
     *
     * <p>Both are asked for. A cutout is not always inside the status bar: held sideways, it sits
     * on an edge that the system window insets on their own do not cover.
     */
    static Rect systemBarsAndCutout(WindowInsets insets) {
        if (insets == null) return new Rect();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Rect(bars.left, bars.top, bars.right, bars.bottom);
        }
        Rect rect = new Rect(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                rect.left = Math.max(rect.left, cutout.getSafeInsetLeft());
                rect.top = Math.max(rect.top, cutout.getSafeInsetTop());
                rect.right = Math.max(rect.right, cutout.getSafeInsetRight());
                rect.bottom = Math.max(rect.bottom, cutout.getSafeInsetBottom());
            }
        }
        return rect;
    }
}
