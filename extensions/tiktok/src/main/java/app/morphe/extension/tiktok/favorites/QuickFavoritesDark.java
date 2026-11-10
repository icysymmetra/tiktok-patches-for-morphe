/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.favorites;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The quick favorites popup (issue #142): hold the bookmark on a video and TikTok lists your
 * collections in a small panel beside it. TikTok draws that panel white with dark text whatever
 * the theme, so it is the one surface left light in dark mode. Once the panel shows, and again
 * whenever it lays out (its rows can arrive after it opens), the light parts go dark: the panel,
 * the icon tiles, the dividers, the titles and the icons. Collection covers are pictures and are
 * left alone, and light mode is never touched.
 *
 * <p>Every colour is read before it is replaced, and only light opaque fills, near-black
 * see-through lines and dark text match, so a second pass over a painted panel changes nothing.
 */
public final class QuickFavoritesDark {
    static final String FAMILY = "quick favorites";

    /** TikTok's dark popover surface. */
    static final int SURFACE = 0xFF252525;
    /** The grey tile behind an icon, one step up from the surface. */
    static final int TILE = 0xFF3A3A3A;
    static final int TEXT = 0xE6FFFFFF;
    static final int TEXT_SECONDARY = 0x99FFFFFF;

    /** Panels whose layouts are already watched, so showing one again adds no second listener. */
    private static final Set<View> WATCHED = Collections.newSetFromMap(new WeakHashMap<>());

    private QuickFavoritesDark() {}

    /** Called at every return of the panel's show, with the panel. */
    public static void paint(Object dialog) {
        try {
            boolean on = Settings.DARK_QUICK_FAVORITES.get();
            HookStatus.bound(FAMILY, on ? "painted dark" : "left");
            if (!on || !(dialog instanceof Dialog)) return;
            Window window = ((Dialog) dialog).getWindow();
            View decor = window == null ? null : window.peekDecorView();
            if (decor == null || !SettingsUi.isDarkContext(decor.getContext())) return;
            View content = decor.findViewById(android.R.id.content);
            if (content == null) return;
            repaint(content);
            if (WATCHED.add(decor)) {
                decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    try {
                        repaint(content);
                    } catch (Throwable failure) {
                        HookStatus.threw(FAMILY, "relayout", failure);
                    }
                });
            }
        } catch (Throwable failure) {
            // A build whose panel moved: leave it as TikTok drew it and say so.
            HookStatus.threw(FAMILY, "paint", failure);
        }
    }

    /** One view and everything under it. */
    static void repaint(View view) {
        recolor(view.getBackground());
        if (view instanceof LinearLayout) recolor(((LinearLayout) view).getDividerDrawable());
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int dark = darkText(text.getCurrentTextColor());
            if (dark != 0) text.setTextColor(dark);
        } else if (view instanceof ImageView && isIcon(view)) {
            ((ImageView) view).setColorFilter(TEXT, PorterDuff.Mode.SRC_IN);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) repaint(group.getChildAt(i));
        }
    }

    /** A solid fill or line in its dark colour, in place; anything else is left as it is. */
    static void recolor(Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            int dark = darkFill(((ColorDrawable) drawable).getColor());
            if (dark != 0) ((ColorDrawable) drawable.mutate()).setColor(dark);
        } else if (drawable instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            GradientDrawable gradient = (GradientDrawable) drawable;
            if (gradient.getColor() == null) return;
            int dark = darkFill(gradient.getColor().getDefaultColor());
            if (dark != 0) ((GradientDrawable) gradient.mutate()).setColor(dark);
        }
    }

    /**
     * The dark counterpart of a fill, or 0 for one that stays: white becomes the surface, a light
     * grey the tile, and a faint near-black line the same faint line in white.
     */
    static int darkFill(int color) {
        int alpha = Color.alpha(color);
        float light = lightness(color);
        if (alpha == 0xFF) {
            if (light >= 0.98f) return SURFACE;
            if (light >= 0.80f) return TILE;
            return 0;
        }
        if (alpha > 0 && alpha < 0x80 && light < 0.3f) return Color.argb(alpha, 0xFF, 0xFF, 0xFF);
        return 0;
    }

    /** Dark text in white, grey text in a dimmer white, or 0 for text that is light already. */
    static int darkText(int color) {
        if (Color.alpha(color) < 0x40) return 0;
        float light = lightness(color);
        if (light < 0.25f) return TEXT;
        if (light < 0.6f) return TEXT_SECONDARY;
        return 0;
    }

    /** TikTok's icon views; a collection's cover is a picture view of another kind. */
    static boolean isIcon(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().endsWith("TuxIconView")) return true;
        }
        return false;
    }

    private static float lightness(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }
}
