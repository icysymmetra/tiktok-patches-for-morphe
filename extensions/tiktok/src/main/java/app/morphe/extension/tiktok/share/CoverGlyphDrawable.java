/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Save cover's symbol, a framed picture with a hill and a sun, drawn like the block glyph. */
public final class CoverGlyphDrawable extends Drawable {
    /** Frame half-size as a fraction of the shorter side. */
    private static final float HALF_FRACTION = 0.30f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Path hills = new Path();

    public CoverGlyphDrawable(int color, float strokeWidthPx) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
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
        float half = Math.min(bounds.width(), bounds.height()) * HALF_FRACTION;
        frame.set(centreX - half, centreY - half, centreX + half, centreY + half);
        float corner = half * 0.25f;
        canvas.drawRoundRect(frame, corner, corner, paint);

        // Two hills rising from the frame's bottom edge, and a sun above the smaller one.
        float base = frame.bottom;
        hills.reset();
        hills.moveTo(frame.left, base - half * 0.35f);
        hills.lineTo(frame.left + half * 0.65f, base - half * 0.95f);
        hills.lineTo(frame.left + half * 1.25f, base - half * 0.30f);
        hills.lineTo(frame.left + half * 1.55f, base - half * 0.60f);
        hills.lineTo(frame.right, base - half * 0.15f);
        canvas.drawPath(hills, paint);
        canvas.drawCircle(frame.left + half * 1.40f, frame.top + half * 0.55f, half * 0.18f, paint);
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
