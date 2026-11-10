/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The block symbol, a ring with a diagonal bar, drawn rather than typed. U+2298 is missing
 * from plenty of fonts and shows as a tofu box when it is, and TikTok picks the font.
 */
public final class BlockGlyphDrawable extends Drawable {
    /** Ring radius as a fraction of the shorter side. */
    private static final float RADIUS_FRACTION = 0.30f;
    private static final float DIAGONAL = 0.70710678f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BlockGlyphDrawable(int color, float strokeWidthPx) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidthPx);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }

        float centreX = bounds.exactCenterX();
        float centreY = bounds.exactCenterY();
        float radius = Math.min(bounds.width(), bounds.height()) * RADIUS_FRACTION;
        canvas.drawCircle(centreX, centreY, radius, paint);

        // The bar runs corner to corner across the ring, so it meets it at both ends.
        float offset = radius * DIAGONAL;
        canvas.drawLine(centreX - offset, centreY + offset,
                centreX + offset, centreY - offset, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
