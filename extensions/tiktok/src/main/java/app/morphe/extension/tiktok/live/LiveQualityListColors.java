/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

/**
 * Keeps the text of the older LIVE Video quality list (LiveDefinitionSelectionDialog) readable
 * in dark mode. On the S25 (2026-10-14, AMOLED on, Dark LIVE panels on or off) its rows read
 * white on white: the list's rows and its text take their colors from different places, a white
 * row drawable in some layouts and the room's theme in others, so dark mode can pair two light
 * colors.
 */
@SuppressWarnings("unused")
public final class LiveQualityListColors {
    /** Text this close to its background gets a readable color. */
    static final double MIN_CONTRAST = 3.0;
    /** TikTok's own dark text, used on a light row. */
    static final int DARK_TEXT = 0xFF161823;

    private LiveQualityListColors() {
    }

    /**
     * Called as the quality list creates its view. After each layout, while TikTok is in dark
     * mode, any text in the list with too little contrast against the color behind it turns white
     * on a dark row and TikTok's dark text on a light one. Readable text and light mode are left
     * alone. Paused, or without the patch's switch set loaded, the list is TikTok's.
     */
    public static void readableQualityList(View root) {
        if (root == null || !SettingsStatus.liveControlsEnabled || !isNight(root.getContext())) {
            return;
        }
        try {
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    fixContrast(root);
                } catch (Throwable failure) {
                    Logger.printException(() -> "Could not check the LIVE quality list's colors", failure);
                }
            });
            fixContrast(root);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not watch the LIVE quality list's colors", failure);
        }
    }

    static boolean isNight(Context context) {
        if (context == null) {
            return false;
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Gives every shown text under [view] that reads poorly on its background a readable color. Returns how many changed. */
    static int fixContrast(View view) {
        int changed = 0;
        if (view.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Integer behind = colorBehind(text);
            if (behind != null) {
                int current = text.getCurrentTextColor();
                if (contrast(current, behind) < MIN_CONTRAST) {
                    int readable = readableOn(behind);
                    if (readable != current) {
                        text.setTextColor(readable);
                        changed++;
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                changed += fixContrast(group.getChildAt(index));
            }
        }
        return changed;
    }

    /**
     * The first solid color drawn behind [view], from the view itself up through its parents,
     * or null when none is opaque enough to judge text against.
     */
    static Integer colorBehind(View view) {
        View current = view;
        while (current != null) {
            Integer color = solidColor(current);
            if (color != null) {
                return color;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** A view's background color when it's a solid fill at least 80% opaque, else null. */
    static Integer solidColor(View view) {
        Drawable background = view.getBackground();
        int color;
        if (background instanceof ColorDrawable) {
            color = ((ColorDrawable) background).getColor();
        } else if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            ColorStateList fill = ((GradientDrawable) background).getColor();
            if (fill == null) {
                return null;
            }
            color = fill.getColorForState(view.getDrawableState(), fill.getDefaultColor());
        } else {
            return null;
        }
        return Color.alpha(color) >= 0xCC ? color : null;
    }

    /** White on a dark color, TikTok's dark text on a light one. */
    static int readableOn(int background) {
        return luminance(background) < 0.4 ? Color.WHITE : DARK_TEXT;
    }

    /** The WCAG contrast ratio of [text], blended over [background] by its alpha, against [background]. */
    static double contrast(int text, int background) {
        double alpha = Color.alpha(text) / 255.0;
        int blended = Color.rgb(
            (int) Math.round(Color.red(text) * alpha + Color.red(background) * (1 - alpha)),
            (int) Math.round(Color.green(text) * alpha + Color.green(background) * (1 - alpha)),
            (int) Math.round(Color.blue(text) * alpha + Color.blue(background) * (1 - alpha)));
        double lighter = Math.max(luminance(blended), luminance(background));
        double darker = Math.min(luminance(blended), luminance(background));
        return (lighter + 0.05) / (darker + 0.05);
    }

    static double luminance(int color) {
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color))
            + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
