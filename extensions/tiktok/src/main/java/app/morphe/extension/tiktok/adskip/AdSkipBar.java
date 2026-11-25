/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.WeakHashMap;

/**
 * The found ads drawn inside TikTok's own progress line, the way SponsorBlock colours sponsor
 * parts: the stretch of the line where each ad plays turns green.
 *
 * <p>How TikTok draws its line (47.4.3, the seek bar's progress drawable {@code X.06pW}): every
 * frame it works out one rectangle, {@code centerY - height/2 + offset} to {@code centerY +
 * height/2 + offset} across the drawable's width, and draws the track, the buffered part and
 * the played part into that same band. Height and offset change with the bar's style (rest,
 * paused, dragging) and are animated over 100 ms by the seek bar. The drawable keeps the band it
 * last drew in its only {@link RectF} field. The marks read that rectangle on every frame, after
 * TikTok has drawn, so they are always exactly where TikTok's line is, mid-animation included,
 * without measuring or guessing anything. A drawable without that field (another build) falls
 * back to the drawable's bounds.
 */
public final class AdSkipBar {
    static final int MARK = 0xFF2BD96B;
    static final int MARK_SKIPPED = 0x8C2BD96B;

    private static final WeakHashMap<LinearLayout, Marks> BARS = new WeakHashMap<>();

    /** TikTok's handle width in pixels, as its style method hands it over. */
    static float handle;

    private static Class<?> bandOwner;
    @Nullable private static Field bandField;

    private AdSkipBar() {}

    /** TikTok's bar style method begins. Nothing to remember: the band is read live. */
    public static void beginStyle(@Nullable LinearLayout bar, int style) {
        refresh();
    }

    /** The line height TikTok is handing the seek bar. Read live from the band instead. */
    public static void lineHeight(@Nullable Float height) {
    }

    /** The handle's side on its way to the seek bar. Main thread. */
    public static void handleSide(@Nullable Float side) {
        if (side != null && side > 0) handle = side;
    }

    /** The bar's progress method, on the main thread, with the percent it is about to draw. */
    public static void onBar(@Nullable LinearLayout bar, float percent) {
        if (bar == null) return;
        try {
            Marks marks = BARS.get(bar);
            if (!AdSkip.active()) {
                if (marks != null) marks.setAid(null);
                return;
            }
            if (marks == null) {
                ProgressBar seekBar = findSeekBar(bar);
                if (seekBar == null) return;
                marks = new Marks(seekBar);
                seekBar.getOverlay().add(marks);
                BARS.put(bar, marks);
            }
            marks.follow(percent);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: could not mark the progress bar", failure);
        }
    }

    @Nullable
    private static ProgressBar findSeekBar(View view) {
        if (view instanceof ProgressBar) return (ProgressBar) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ProgressBar found = findSeekBar(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Redraws every bar, after a scan found ads in a video or an ad was skipped. */
    static void refresh() {
        for (Marks marks : BARS.values()) {
            if (marks != null) marks.invalidateSelf();
        }
    }

    /** The band TikTok's drawable last drew its line in, in the drawable's own coordinates. */
    @Nullable
    static RectF band(@Nullable Drawable drawable) {
        if (drawable == null) return null;
        Class<?> type = drawable.getClass();
        if (type != bandOwner) {
            bandOwner = type;
            bandField = null;
            Field found = null;
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != RectF.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (found != null) {
                    found = null; // two of them: not the drawable this was written against
                    break;
                }
                found = field;
            }
            if (found != null) {
                try {
                    found.setAccessible(true);
                    bandField = found;
                } catch (Throwable ignored) {
                    bandField = null;
                }
            }
        }
        Field field = bandField;
        if (field == null) return null;
        try {
            Object value = field.get(drawable);
            return value instanceof RectF ? (RectF) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class Marks extends Drawable {
        private final WeakReference<ProgressBar> seekBar;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        @Nullable private String aid;

        Marks(ProgressBar seekBar) {
            this.seekBar = new WeakReference<>(seekBar);
            paint.setStyle(Paint.Style.FILL);
        }

        void setAid(@Nullable String value) {
            if (value == null ? aid == null : value.equals(aid)) return;
            aid = value;
            invalidateSelf();
        }

        void follow(float percent) {
            ProgressBar bar = seekBar.get();
            if (bar != null) {
                Rect b = getBounds();
                if (b.width() != bar.getWidth() || b.height() != bar.getHeight()) {
                    setBounds(0, 0, bar.getWidth(), bar.getHeight());
                }
            }
            String playing = AdSkip.playingAid();
            long duration = AdSkip.playingDuration();
            if (playing != null && duration > 0) {
                float expected = AdSkip.playingPosition() * 100f / duration;
                if (Math.abs(expected - percent) < 2.5f) {
                    setAid(playing);
                    return;
                }
            }
            if (percent < 0.5f && aid != null && !aid.equals(playing)) setAid(null);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            String shown = aid;
            ProgressBar bar = seekBar.get();
            if (shown == null || bar == null || !bar.isShown()) return;
            AdScanner.Result result = AdScanner.result(shown);
            if (result == null || result.state != AdScanner.State.DONE || result.matches.isEmpty()) return;
            long duration = shown.equals(AdSkip.playingAid()) ? AdSkip.playingDuration() : 0;
            if (duration <= 0) duration = result.scannedMs;
            if (duration <= 0) return;

            Drawable track = bar.getProgressDrawable();
            if (track == null) return;
            Rect bounds = track.getBounds();
            if (bounds.isEmpty()) return;
            // ProgressBar draws its drawable translated by its paddings.
            float dx = bar.getPaddingLeft(), dy = bar.getPaddingTop();
            float width = bounds.width();
            float left = dx, right = dx + width;
            float top, bottom, handleX;
            RectF band = band(track);
            if (band != null && band.bottom > band.top) {
                // TikTok's band: its last draw is the played part, 0 to the handle.
                top = dy + band.top;
                bottom = dy + band.bottom;
                handleX = dx + band.right;
            } else {
                float density = bar.getResources().getDisplayMetrics().density;
                float centre = dy + bounds.exactCenterY();
                top = centre - density;
                bottom = centre + density;
                int max = bar.getMax();
                handleX = dx + (max <= 0 ? 0 : width * bar.getProgress() / max);
            }
            float thickness = bottom - top;
            float radius = thickness / 2f;
            float density = bar.getResources().getDisplayMetrics().density;
            float minWidth = Math.max(thickness, 2 * density);

            // TikTok's handle stays on top: the marks leave a gap where it sits.
            int saved = canvas.save();
            float side = handle > 0 ? handle : 0;
            if (side > 0) {
                float gap = side / 2f;
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    canvas.clipOutRect(handleX - gap, 0, handleX + gap, bar.getHeight());
                } else {
                    //noinspection deprecation
                    canvas.clipRect(handleX - gap, 0, handleX + gap, bar.getHeight(), android.graphics.Region.Op.DIFFERENCE);
                }
            }
            List<AdMatcher.Match> matches = result.matches;
            for (AdMatcher.Match match : matches) {
                float x0 = left + width * Math.max(0, match.startMs) / duration;
                float x1 = left + width * Math.min(duration, match.endMs) / duration;
                if (x1 - x0 < minWidth) x1 = x0 + minWidth;
                x0 = Math.max(left, x0);
                x1 = Math.min(right, x1);
                if (x1 <= x0) continue;
                paint.setColor(AdSkip.wasSkipped(shown, match.startMs) ? MARK_SKIPPED : MARK);
                rect.set(x0, top, x1, bottom);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            canvas.restoreToCount(saved);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
