/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** The editor's and the list's shared look: a dark sheet, TikTok's red accent, round chips. */
final class AdSkipUi {
    static final int BG = 0xFF0E0E10;
    static final int SURFACE = 0xFF1C1C1F;
    static final int SURFACE_HI = 0xFF2A2A2E;
    static final int TEXT = 0xFFF2F2F4;
    static final int TEXT_2 = 0xFFA0A0A8;
    static final int ACCENT = 0xFFFE2C55;
    static final int GOOD = 0xFF35C76F;
    static final int WARN = 0xFFF5A623;

    private AdSkipUi() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static String time(long ms, boolean tenths) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long m = totalSeconds / 60, s = totalSeconds % 60;
        if (!tenths) return String.format(Locale.ROOT, "%d:%02d", m, s);
        return String.format(Locale.ROOT, "%d:%02d.%d", m, s, (ms % 1000) / 100);
    }

    static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }

    static TextView text(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    /** A pill button: filled for the main action, a dark chip otherwise. */
    static TextView chip(Context context, CharSequence label, boolean primary) {
        TextView chip = text(context, label, 14, primary ? Color.WHITE : TEXT, primary);
        chip.setGravity(Gravity.CENTER);
        int h = dp(context, 14), v = dp(context, 9);
        chip.setPadding(h, v, h, v);
        chip.setMinHeight(dp(context, 40));
        chip.setMinWidth(dp(context, 40));
        GradientDrawable fill = round(primary ? ACCENT : SURFACE_HI, dp(context, 20));
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    /**
     * A pill button with an icon beside its label. The two are separate views centred on one
     * line, so the icon sits on the text's middle whatever the font, which an icon drawn inside
     * the text does not.
     */
    static final class IconChip extends LinearLayout {
        private final ImageView icon;
        private final TextView label;
        private final int color;

        IconChip(Context context, boolean primary) {
            super(context);
            color = primary ? Color.WHITE : TEXT;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(context, 16), 0, dp(context, 16), 0);
            setMinimumHeight(dp(context, 40));
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                    round(primary ? ACCENT : SURFACE_HI, dp(context, 24)), null));
            setClickable(true);
            setFocusable(true);
            icon = new ImageView(context);
            addView(icon, new LayoutParams(dp(context, 20), dp(context, 20)));
            label = text(context, "", 15, color, primary);
            label.setIncludeFontPadding(false);
            label.setSingleLine(true);
            LayoutParams labelParams = new LayoutParams(-2, -2);
            labelParams.leftMargin = dp(context, 8);
            addView(label, labelParams);
        }

        void set(AdIcons.Icon glyph, CharSequence text) {
            icon.setImageDrawable(AdIcons.of(getContext(), glyph, color, 20));
            label.setText(text);
            setContentDescription(text);
        }
    }

    /** A round icon-only button. */
    static ImageView iconButton(Context context, AdIcons.Icon icon, String description, float sizeDp) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(AdIcons.of(context, icon, TEXT, sizeDp * 0.5f));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        int size = dp(context, sizeDp);
        button.setMinimumWidth(size);
        button.setMinimumHeight(size);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(SURFACE_HI, size / 2f), null));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    static void setIcon(ImageView button, AdIcons.Icon icon, float sizeDp) {
        button.setImageDrawable(AdIcons.of(button.getContext(), icon, TEXT, sizeDp * 0.5f));
    }

    static void enable(View chip, boolean enabled) {
        chip.setEnabled(enabled);
        chip.setAlpha(enabled ? 1f : 0.4f);
    }

    /**
     * Keeps a full-screen sheet clear of the status bar, the camera cutout and the navigation
     * bar. TikTok targets Android 15, where every window is drawn edge to edge, so a dialog's
     * content starts under the camera unless it pads itself.
     */
    static void padForSystemBars(View root) {
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = bars.left;
                t = bars.top;
                r = bars.right;
                b = bars.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    t = Math.max(t, insets.getDisplayCutout().getSafeInsetTop());
                }
            }
            v.setPadding(left + l, top + t, right + r, bottom + b);
            return insets;
        });
        root.requestApplyInsets();
    }
}
