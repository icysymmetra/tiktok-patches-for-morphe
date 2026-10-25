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
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Every glyph on the feed overlay controls, drawn at the same stroke weight and the same
 * radius fraction, so they look like one set rather than three fonts and one drawing.
 *
 * <p>The block symbol was already drawn here ({@link BlockGlyphDrawable}); the sound note, the
 * local-hide cross and the not-interested bar were text in whatever font TikTok happened to be
 * using, and they did not look alike.
 */
public final class OverlayGlyphDrawable extends Drawable {
    /** What to draw. */
    public enum Shape {
        /** Ring with a diagonal bar: the block symbol. */
        BLOCK,
        /** An × cross: local hide. */
        CROSS,
        /** A musical note: block sound. */
        NOTE,
        /** A horizontal bar, thicker than a font's dash: not interested. */
        MINUS,
        /** A speaker with two sound waves: the feed plays with sound. */
        SPEAKER,
        /** A speaker with a cross where the waves were: the feed is muted. */
        SPEAKER_OFF,
    }

    /** Each glyph sits inside this fraction of the shorter side. */
    private static final float RADIUS_FRACTION = 0.30f;
    private static final float DIAGONAL = 0.70710678f;

    private final Shape shape;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OverlayGlyphDrawable(Shape shape, int color, float strokeWidthPx) {
        this.shape = shape;
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidthPx);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        float r = Math.min(bounds.width(), bounds.height()) * RADIUS_FRACTION;

        switch (shape) {
            case BLOCK:
                canvas.drawCircle(cx, cy, r, paint);
                float d = r * DIAGONAL;
                canvas.drawLine(cx - d, cy + d, cx + d, cy - d, paint);
                break;

            case CROSS:
                float arm = r * 0.75f;
                canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint);
                canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, paint);
                break;

            case NOTE: {
                // A simplified note: an oval head with a stem and a flag.
                float headR = r * 0.28f;
                float headCx = cx - r * 0.1f;
                float headCy = cy + r * 0.45f;
                Paint filled = new Paint(paint);
                filled.setStyle(Paint.Style.FILL);
                // The head is an oval tilted about 30 degrees.
                canvas.save();
                canvas.rotate(-30, headCx, headCy);
                canvas.drawOval(headCx - headR * 1.3f, headCy - headR,
                        headCx + headR * 1.3f, headCy + headR, filled);
                canvas.restore();
                // The stem runs up from the right side of the head.
                float stemX = headCx + headR * 0.9f;
                float stemTop = cy - r * 0.7f;
                canvas.drawLine(stemX, headCy - headR * 0.3f, stemX, stemTop, paint);
                // A short flag at the top, curving to the right.
                Path flag = new Path();
                flag.moveTo(stemX, stemTop);
                flag.quadTo(stemX + r * 0.5f, stemTop + r * 0.15f,
                        stemX + r * 0.3f, stemTop + r * 0.5f);
                canvas.drawPath(flag, paint);
                break;
            }

            case MINUS:
                // A horizontal bar, wider than the cross's arms so it reads as its own thing.
                float half = r * 0.6f;
                canvas.drawLine(cx - half, cy, cx + half, cy, paint);
                break;

            case SPEAKER:
            case SPEAKER_OFF: {
                // The speaker sits left of centre so the waves or the cross balance it.
                float mouth = cx + r * 0.05f;
                Path speaker = new Path();
                speaker.moveTo(cx - r * 0.9f, cy - r * 0.3f);
                speaker.lineTo(cx - r * 0.45f, cy - r * 0.3f);
                speaker.lineTo(mouth, cy - r * 0.75f);
                speaker.lineTo(mouth, cy + r * 0.75f);
                speaker.lineTo(cx - r * 0.45f, cy + r * 0.3f);
                speaker.lineTo(cx - r * 0.9f, cy + r * 0.3f);
                speaker.close();
                canvas.drawPath(speaker, paint);
                if (shape == Shape.SPEAKER) {
                    for (float wave : new float[]{r * 0.4f, r * 0.75f}) {
                        canvas.drawArc(mouth - wave, cy - wave, mouth + wave, cy + wave, -45, 90, false, paint);
                    }
                } else {
                    float crossX = cx + r * 0.6f;
                    float crossArm = r * 0.3f;
                    canvas.drawLine(crossX - crossArm, cy - crossArm, crossX + crossArm, cy + crossArm, paint);
                    canvas.drawLine(crossX - crossArm, cy + crossArm, crossX + crossArm, cy - crossArm, paint);
                }
                break;
            }
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
