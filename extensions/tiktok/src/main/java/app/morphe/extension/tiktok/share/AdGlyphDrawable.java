/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.share;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Mark ad's symbol: a sound wave between two cut brackets, drawn like the other glyphs. */
public final class AdGlyphDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    public AdGlyphDrawable(int color, float strokeWidthPx) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidthPx);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float half = Math.min(b.width(), b.height()) * 0.30f;
        box.set(cx - half, cy - half, cx + half, cy + half);
        float arm = half * 0.35f;
        // Brackets.
        canvas.drawLine(box.left, box.top, box.left, box.bottom, paint);
        canvas.drawLine(box.left, box.top, box.left + arm, box.top, paint);
        canvas.drawLine(box.left, box.bottom, box.left + arm, box.bottom, paint);
        canvas.drawLine(box.right, box.top, box.right, box.bottom, paint);
        canvas.drawLine(box.right, box.top, box.right - arm, box.top, paint);
        canvas.drawLine(box.right, box.bottom, box.right - arm, box.bottom, paint);
        // Wave bars.
        float[] heights = {0.35f, 0.75f, 0.5f, 0.9f, 0.4f};
        float span = (box.width() - 2 * arm) ;
        for (int i = 0; i < heights.length; i++) {
            float x = box.left + arm + span * (i + 0.5f) / heights.length;
            float h = half * heights[i];
            canvas.drawLine(x, cy - h, x, cy + h, paint);
        }
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
