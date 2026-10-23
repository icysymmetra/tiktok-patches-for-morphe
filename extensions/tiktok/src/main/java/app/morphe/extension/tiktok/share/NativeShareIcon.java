/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

import androidx.annotation.Nullable;

/**
 * Draws an owned share action's glyph the way TikTok draws its own action icons.
 *
 * <p>Both native binders build a LayerDrawable whose last layer is the action's TuxIconDrawable:
 * the share grid puts its grey circle under it (layer 0), the feed's long-press panel list uses
 * it alone. Swapping only that last layer keeps the circle TikTok themed for light, dark and
 * AMOLED, and the glyph takes the native icon's size and ink colour.
 */
final class NativeShareIcon {
    /** Builds a glyph in the native icon's colour with a stroke weight for its box. */
    interface Glyph {
        Drawable make(int color, float strokeWidthPx);
    }

    /**
     * Hushfeed's glyphs fill 60% of their box, a quarter larger than TikTok's 24dp icon box puts
     * them at the 18dp its icon artwork takes.
     */
    static final float BOX_SCALE = 1.25f;
    /** TikTok's line icons are drawn about 2dp wide in a 24dp box. */
    static final float STROKE_FRACTION = 1f / 12f;

    private NativeShareIcon() {}

    /** The native icon with [glyph] in its icon layer, or null when it isn't TikTok's layered icon. */
    @Nullable
    static Drawable restyle(@Nullable Drawable current, Glyph glyph) {
        if (!(current instanceof LayerDrawable)) return null;
        LayerDrawable layers = (LayerDrawable) current;
        int count = layers.getNumberOfLayers();
        if (count < 1) return null;
        int last = count - 1;
        Drawable icon = layers.getDrawable(last);
        if (icon == null) return null;
        // The size TikTok gave the icon's layer, when it gave one, is the size it shows at.
        int width = layers.getLayerWidth(last) > 0 ? layers.getLayerWidth(last) : icon.getIntrinsicWidth();
        int height = layers.getLayerHeight(last) > 0 ? layers.getLayerHeight(last) : icon.getIntrinsicHeight();
        int size = Math.min(width, height);
        if (size <= 0) return null;
        Integer ink = inkOf(icon, size);
        if (ink == null) return null;

        Drawable[] parts = new Drawable[count];
        for (int i = 0; i < last; i++) parts[i] = layers.getDrawable(i);
        parts[last] = glyph.make(ink, size * STROKE_FRACTION);
        LayerDrawable styled = new LayerDrawable(parts);
        styled.setPaddingMode(layers.getPaddingMode());
        for (int i = 0; i < count; i++) copyLayout(layers, styled, i);
        int box = Math.round(size * BOX_SCALE);
        styled.setLayerSize(last, box, box);
        styled.setLayerGravity(last, Gravity.CENTER);
        // The glyph's box reaches past the icon's own insets by what it grew, so the drawable keeps
        // TikTok's intrinsic size: a list row sized to it neither grows nor scales the glyph back.
        int growX = box - width;
        int growY = box - height;
        int start = layers.getLayerInsetStart(last);
        int end = layers.getLayerInsetEnd(last);
        if (start != LayerDrawable.INSET_UNDEFINED) styled.setLayerInsetStart(last, start - growX / 2);
        else styled.setLayerInsetLeft(last, layers.getLayerInsetLeft(last) - growX / 2);
        if (end != LayerDrawable.INSET_UNDEFINED) styled.setLayerInsetEnd(last, end - (growX - growX / 2));
        else styled.setLayerInsetRight(last, layers.getLayerInsetRight(last) - (growX - growX / 2));
        styled.setLayerInsetTop(last, layers.getLayerInsetTop(last) - growY / 2);
        styled.setLayerInsetBottom(last, layers.getLayerInsetBottom(last) - (growY - growY / 2));
        return styled;
    }

    /** Layer [i]'s id, insets, size and gravity, which the new drawable would otherwise drop. */
    private static void copyLayout(LayerDrawable from, LayerDrawable to, int i) {
        to.setId(i, from.getId(i));
        to.setLayerInset(i, from.getLayerInsetLeft(i), from.getLayerInsetTop(i),
                from.getLayerInsetRight(i), from.getLayerInsetBottom(i));
        int start = from.getLayerInsetStart(i);
        if (start != LayerDrawable.INSET_UNDEFINED) to.setLayerInsetStart(i, start);
        int end = from.getLayerInsetEnd(i);
        if (end != LayerDrawable.INSET_UNDEFINED) to.setLayerInsetEnd(i, end);
        to.setLayerSize(i, from.getLayerWidth(i), from.getLayerHeight(i));
        to.setLayerGravity(i, from.getLayerGravity(i));
    }

    /** The most opaque pixel the native icon draws, as an opaque colour, or null if it draws nothing. */
    @Nullable
    static Integer inkOf(Drawable icon, int size) {
        Bitmap bitmap = null;
        Rect bounds = icon.copyBounds();
        try {
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, size, size);
            icon.draw(new Canvas(bitmap));
            int[] pixels = new int[size * size];
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
            int ink = 0;
            int alpha = 0;
            for (int pixel : pixels) {
                int a = pixel >>> 24;
                if (a > alpha) {
                    alpha = a;
                    ink = pixel;
                }
            }
            return alpha < 0x80 ? null : ink | 0xFF000000;
        } catch (Throwable ignored) {
            return null;
        } finally {
            icon.setBounds(bounds);
            if (bitmap != null) bitmap.recycle();
        }
    }
}
