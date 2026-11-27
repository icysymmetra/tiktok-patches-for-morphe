/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

/**
 * Interactive timeline view with frame thumbnails, audio waveform, draggable start/end handles,
 * hold-to-fine-zoom, pinch-to-zoom, and edge auto-panning.
 */
@SuppressLint("ViewConstructor")
final class SegmentRangeView extends View {
    interface Listener {
        void rangeChanged(long startMs, long endMs, boolean fromUser, boolean dragging);

        void seekRequested(long ms, boolean dragging);

        void zoomChanged(boolean zoomed);

        /** The strip zoomed in by itself because a handle was held still. */
        void fineZoomed();
    }

    private final float density;
    private final int accent;
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleActive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripActive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path path = new Path();

    private float[] envelope = new float[0];
    private Bitmap[] thumbs = new Bitmap[0];
    private long durationMs = 1;
    /** The visible window, for zooming into a long video. */
    private long viewStart = 0, viewEnd = 1;
    private long startMs, endMs;
    private long playMs = -1;
    private Listener listener;

    private static final int NONE = 0, START = 1, END = 2, HEAD = 3, MOVE = 4;
    private int dragging = NONE;
    private float downX, lastX;
    private long downMs, downStart, downEnd;
    private boolean moved;
    private boolean panning;
    private static final float PAN_PER_FRAME = 0.025f;
    static final long MIN_LENGTH_MS = 1000;
    static final long MAX_LENGTH_MS = 30_000;

    private static final long HOLD_MS = 350;
    private static final long NEXT_HOLD_MS = 650;
    private static final float HOLD_SLOP_DP = 5;
    private static final int FINE_LEVELS = 2;
    static final long FINE_SPAN_MS = 1500;
    private float holdX;
    private int fineLevel;
    private long restoreStart = -1, restoreEnd = -1;
    private long lastTickTenth = Long.MIN_VALUE;

    private final ScaleGestureDetector pinch;
    private boolean pinching;
    private ValueAnimator viewAnimator;

    SegmentRangeView(Context context, int accent) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = accent;
        wavePaint.setColor(Color.WHITE);
        waveDim.setColor(0x59FFFFFF);
        shade.setColor(0xB30E0E10);
        frame.setColor(accent);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2.5f * density);
        handle.setColor(accent);
        handleActive.setColor(Color.WHITE);
        grip.setColor(Color.WHITE);
        grip.setStrokeWidth(1.5f * density);
        grip.setStrokeCap(Paint.Cap.ROUND);
        gripActive.setColor(accent);
        gripActive.setStrokeWidth(1.5f * density);
        gripActive.setStrokeCap(Paint.Cap.ROUND);
        head.setColor(Color.WHITE);
        head.setStrokeWidth(2 * density);
        head.setShadowLayer(3 * density, 0, 0, 0x99000000);
        tick.setColor(0x8CFFFFFF);
        tick.setTextSize(10 * density);
        thumbBg.setColor(AdSkipUi.SURFACE);
        bubble.setColor(accent);
        bubble.setShadowLayer(4 * density, 0, 1 * density, 0x80000000);
        bubbleText.setColor(Color.WHITE);
        bubbleText.setTextSize(12 * density);
        bubbleText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        bubbleText.setTextAlign(Paint.Align.CENTER);
        zoomText.setColor(AdSkipUi.TEXT_2);
        zoomText.setTextSize(10.5f * density);
        zoomText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        zoomText.setTextAlign(Paint.Align.RIGHT);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setHapticFeedbackEnabled(true);
        setContentDescription(app.morphe.extension.tiktok.settings.L10n.t("Ad range"));
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                beginPinch();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                long span = viewEnd - viewStart;
                long next = clamp(Math.round(span / Math.max(0.2f, detector.getScaleFactor())), Math.min(FINE_SPAN_MS, durationMs), durationMs);
                setSpanAround(msOf(detector.getFocusX()), detector.getFocusX(), next);
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setDuration(long ms) {
        durationMs = Math.max(1, ms);
        viewStart = 0;
        viewEnd = durationMs;
        invalidate();
    }

    long duration() {
        return durationMs;
    }

    /** Peak loudness per column, 0..1. */
    void setEnvelope(float[] values) {
        envelope = values == null ? new float[0] : values;
        invalidate();
    }

    void setThumbs(Bitmap[] bitmaps) {
        thumbs = bitmaps == null ? new Bitmap[0] : bitmaps;
        invalidate();
    }

    void setRange(long start, long end) {
        startMs = clamp(start, 0, durationMs);
        endMs = clamp(end, startMs, durationMs);
        invalidate();
    }

    long start() {
        return startMs;
    }

    long end() {
        return endMs;
    }

    void setPlayhead(long ms) {
        playMs = ms;
        invalidate();
    }

    /** Shows the chosen part with a margin either side, or the whole video again. */
    void zoom(boolean toSelection) {
        if (toSelection) {
            long span = Math.max(3000, endMs - startMs);
            long margin = span / 4 + 500;
            long from = Math.max(0, startMs - margin);
            animateView(from, Math.min(durationMs, endMs + margin));
        } else {
            animateView(0, durationMs);
        }
    }

    /** Scrolls a zoomed view just enough to show this moment. */
    void reveal(long ms) {
        long span = viewEnd - viewStart;
        long margin = span / 10;
        long shift = 0;
        if (ms < viewStart + margin) shift = ms - margin - viewStart;
        else if (ms > viewEnd - margin) shift = ms + margin - viewEnd;
        shiftView(shift);
    }

    /** Moves the view by this much, kept inside the video; returns how far it really moved. */
    private long shiftView(long by) {
        long span = viewEnd - viewStart;
        long start = clamp(viewStart + by, 0, Math.max(0, durationMs - span));
        long moved = start - viewStart;
        viewStart = start;
        viewEnd = start + span;
        if (moved != 0) invalidate();
        return moved;
    }

    /** A view this wide in which the moment ms sits at x. */
    private void setSpanAround(long ms, float x, long span) {
        float left = pad(), right = getWidth() - pad();
        float f = (x - left) / Math.max(1f, right - left);
        span = clamp(span, 1, durationMs);
        long start = clamp(ms - Math.round(f * span), 0, durationMs - span);
        boolean was = zoomed();
        viewStart = start;
        viewEnd = start + span;
        if (was != zoomed() && listener != null) listener.zoomChanged(zoomed());
        invalidate();
    }

    private void animateView(long toStart, long toEnd) {
        if (viewAnimator != null) viewAnimator.cancel();
        long fromStart = viewStart, fromEnd = viewEnd;
        if (fromStart == toStart && fromEnd == toEnd) return;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(240);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            viewStart = fromStart + Math.round((toStart - fromStart) * f);
            viewEnd = fromEnd + Math.round((toEnd - fromEnd) * f);
            invalidate();
            if (f >= 1f && listener != null) listener.zoomChanged(zoomed());
        });
        viewAnimator = animator;
        animator.start();
    }

    boolean zoomed() {
        return viewStart > 0 || viewEnd < durationMs;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(132 * density));
    }

    private float pad() {
        return 16 * density;
    }

    private float laneBottom() {
        return 26 * density;
    }

    private float thumbTop() {
        return 30 * density;
    }

    private float thumbBottom() {
        return thumbTop() + 52 * density;
    }

    private float xOf(long ms) {
        float left = pad(), right = getWidth() - pad();
        return left + (right - left) * (ms - viewStart) / (float) Math.max(1, viewEnd - viewStart);
    }

    private long msOf(float x) {
        float left = pad(), right = getWidth() - pad();
        float f = (x - left) / Math.max(1f, right - left);
        return clamp(viewStart + Math.round(f * (viewEnd - viewStart)), 0, durationMs);
    }

    // region drawing

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float left = pad(), right = w - pad();
        float thumbTop = thumbTop(), thumbBottom = thumbBottom();
        float waveTop = thumbBottom + 6 * density, waveBottom = h - 18 * density;
        float radius = 8 * density;

        // Frames, in a rounded strip.
        rect.set(left, thumbTop, right, thumbBottom);
        canvas.drawRoundRect(rect, radius, radius, thumbBg);
        if (thumbs.length > 0) {
            float cell = (right - left) / thumbs.length;
            canvas.save();
            path.reset();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            for (int i = 0; i < thumbs.length; i++) {
                // The frame for this cell's time in the current view, so a zoomed strip shows
                // the frames of the part it shows.
                long at = viewStart + (viewEnd - viewStart) * (2L * i + 1) / (2L * thumbs.length);
                int which = (int) Math.min(thumbs.length - 1, at * thumbs.length / Math.max(1, durationMs));
                Bitmap b = thumbs[which];
                if (b == null || b.isRecycled()) continue;
                rect.set(left + i * cell, thumbTop, left + (i + 1) * cell + 1, thumbBottom);
                float ar = b.getWidth() / (float) b.getHeight();
                float cellAr = rect.width() / rect.height();
                if (ar > cellAr) {
                    int cw = Math.round(b.getHeight() * cellAr);
                    src.set((b.getWidth() - cw) / 2, 0, (b.getWidth() + cw) / 2, b.getHeight());
                } else {
                    int ch = Math.round(b.getWidth() / cellAr);
                    src.set(0, (b.getHeight() - ch) / 2, b.getWidth(), (b.getHeight() + ch) / 2);
                }
                canvas.drawBitmap(b, src, rect, null);
            }
            canvas.restore();
        }

        // Waveform: a rounded bar per column, mirrored about the middle.
        float mid = (waveTop + waveBottom) / 2f, amp = (waveBottom - waveTop) / 2f;
        float xs = xOf(startMs), xe = xOf(endMs);
        if (envelope.length > 0) {
            int columns = Math.max(1, Math.round((right - left) / (3 * density)));
            float colW = (right - left) / columns;
            for (int c = 0; c < columns; c++) {
                long t0 = viewStart + (viewEnd - viewStart) * c / columns;
                long t1 = viewStart + (viewEnd - viewStart) * (c + 1) / columns;
                int e0 = (int) (t0 * envelope.length / durationMs);
                int e1 = Math.max(e0 + 1, (int) (t1 * envelope.length / durationMs));
                float v = 0;
                for (int e = Math.max(0, e0); e < Math.min(envelope.length, e1); e++) v = Math.max(v, envelope[e]);
                float bh = Math.max(1 * density, v * amp);
                float x = left + c * colW;
                float cx = x + colW / 2;
                Paint p = cx >= xs && cx <= xe ? wavePaint : waveDim;
                rect.set(x + colW * 0.2f, mid - bh, x + colW * 0.8f, mid + bh);
                canvas.drawRoundRect(rect, colW * 0.3f, colW * 0.3f, p);
            }
        }

        // Before the video is loaded there is no range to show, only the empty strip.
        if (durationMs <= 1) return;

        // Dim outside the range.
        canvas.drawRect(left, thumbTop, Math.max(left, xs), waveBottom, shade);
        canvas.drawRect(Math.min(right, xe), thumbTop, right, waveBottom, shade);

        // Selection frame and handles.
        canvas.save();
        canvas.clipRect(left, 0, right, h);
        float outside = 24 * density;
        rect.set(xs < left - 1 ? left - outside : xs, thumbTop, xe > right + 1 ? right + outside : xe, waveBottom);
        canvas.drawRoundRect(rect, 4 * density, 4 * density, frame);
        canvas.restore();
        canvas.save();
        canvas.clipRect(left - 16 * density, 0, right + 16 * density, h);
        if (xs >= left - 1) drawHandle(canvas, xs, thumbTop, waveBottom, true, dragging == START);
        if (xe <= right + 1) drawHandle(canvas, xe, thumbTop, waveBottom, false, dragging == END);
        canvas.restore();

        // Play head.
        if (playMs >= viewStart && playMs <= viewEnd) {
            float x = xOf(playMs);
            canvas.drawLine(x, thumbTop - 3 * density, x, waveBottom + 2 * density, head);
            canvas.drawCircle(x, thumbTop - 3 * density, 3.5f * density, head);
        }

        // Time ticks: finer steps as the view narrows, tenths once a step is under a second.
        long span = viewEnd - viewStart;
        long step = span <= 1600 ? 200 : span <= 3500 ? 500 : span <= 7000 ? 1000 : span <= 20000 ? 2000
                : span <= 60000 ? 5000 : span <= 180000 ? 15000 : 30000;
        for (long t = ((viewStart + step - 1) / step) * step; t <= viewEnd; t += step) {
            float x = xOf(t);
            String label = AdSkipUi.time(t, step < 1000 ? 1 : 0);
            float tw = tick.measureText(label);
            canvas.drawText(label, Math.max(0, Math.min(w - tw, x - tw / 2)), h - 4 * density, tick);
        }

        // The lane above the strip: how far in it is, and the time of the edge being moved.
        if (zoomed()) {
            float factor = durationMs / (float) Math.max(1, span);
            String label = factor < 10 ? String.format(Locale.ROOT, "%.1f\u00D7", factor)
                    : String.format(Locale.ROOT, "%d\u00D7", Math.round(factor));
            canvas.drawText(label, right, laneBottom() - 8 * density, zoomText);
        }
        if (dragging == START || dragging == END) {
            long at = dragging == START ? startMs : endMs;
            drawBubble(canvas, xOf(at), AdSkipUi.time(at, fineLevel > 0 || span <= 3500 ? 2 : 1));
        } else if (dragging == MOVE) {
            drawBubble(canvas, (xs + xe) / 2f, AdSkipUi.time(startMs, 1) + " \u2013 " + AdSkipUi.time(endMs, 1));
        }
    }

    private void drawHandle(Canvas canvas, float x, float top, float bottom, boolean start, boolean active) {
        float hw = (active ? 15 : 13) * density;
        float r = 6 * density;
        if (start) rect.set(x - hw, top, x + 1, bottom);
        else rect.set(x - 1, top, x + hw, bottom);
        canvas.drawRoundRect(rect, r, r, active ? handleActive : handle);
        // Square the inner side, so the handle joins the frame cleanly.
        if (start) canvas.drawRect(x - r, top, x + 1, bottom, active ? handleActive : handle);
        else canvas.drawRect(x - 1, top, x + r, bottom, active ? handleActive : handle);
        float cx = rect.centerX(), cy = rect.centerY();
        Paint g = active ? gripActive : grip;
        canvas.drawLine(cx - 2 * density, cy - 7 * density, cx - 2 * density, cy + 7 * density, g);
        canvas.drawLine(cx + 2 * density, cy - 7 * density, cx + 2 * density, cy + 7 * density, g);
    }

    /** A label pointing down at x from the lane above the strip, kept inside the view. */
    private void drawBubble(Canvas canvas, float x, String text) {
        float padH = 8 * density, height = 20 * density, pointer = 4 * density;
        float width = bubbleText.measureText(text) + 2 * padH;
        float bottom = laneBottom() - 1 * density, top = bottom - height - pointer + 4 * density;
        float left = clampF(x - width / 2, 2 * density, getWidth() - width - 2 * density);
        rect.set(left, top, left + width, bottom - pointer);
        canvas.drawRoundRect(rect, height / 2, height / 2, bubble);
        float px = clampF(x, rect.left + height / 2, rect.right - height / 2);
        path.reset();
        path.moveTo(px - pointer, rect.bottom - 1);
        path.lineTo(px + pointer, rect.bottom - 1);
        path.lineTo(px, rect.bottom + pointer);
        path.close();
        canvas.drawPath(path, bubble);
        Paint.FontMetrics fm = bubbleText.getFontMetrics();
        canvas.drawText(text, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, bubbleText);
    }

    // endregion

    // region touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event);
        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                if (viewAnimator != null) viewAnimator.cancel();
                pinching = false;
                float xs = xOf(startMs), xe = xOf(endMs);
                float grab = 26 * density;
                float ds = Math.abs(x - (xs - 6 * density)), de = Math.abs(x - (xe + 6 * density));
                float y = event.getY();
                if (ds <= grab && ds <= de && y >= thumbTop() - 8 * density) dragging = START;
                else if (de <= grab && y >= thumbTop() - 8 * density) dragging = END;
                else if (x > xs && x < xe && y >= thumbTop() && y < thumbBottom()) dragging = MOVE;
                else dragging = HEAD;
                downX = x;
                lastX = x;
                holdX = x;
                downMs = msOf(x);
                downStart = startMs;
                downEnd = endMs;
                moved = false;
                fineLevel = 0;
                restoreStart = restoreEnd = -1;
                lastTickTenth = Long.MIN_VALUE;
                if (dragging == HEAD) seek(msOf(x), true);
                if (dragging == START || dragging == END) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    scheduleHold(HOLD_MS);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                beginPinch();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (pinching || dragging == NONE) return true;
                if (Math.abs(x - downX) > 3 * density) moved = true;
                lastX = x;
                if (Math.abs(x - holdX) > HOLD_SLOP_DP * density) {
                    holdX = x;
                    // The finger moves on: a zoom still easing in stops where it is, so the handle
                    // follows the finger rather than the point where it rested.
                    if (viewAnimator != null) viewAnimator.cancel();
                    if (fineLevel < FINE_LEVELS) scheduleHold(fineLevel == 0 ? HOLD_MS : NEXT_HOLD_MS);
                }
                drag(x);
                if (moved && !panning && edgeDepth(x) != 0) {
                    panning = true;
                    postOnAnimation(panner);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int was = dragging;
                dragging = NONE;
                panning = false;
                removeCallbacks(panner);
                removeCallbacks(holdCheck);
                if (pinching) {
                    pinching = false;
                    if (listener != null) listener.zoomChanged(zoomed());
                    invalidate();
                    return true;
                }
                if (was == START || was == END || was == MOVE) {
                    settle();
                    if (listener != null) listener.rangeChanged(startMs, endMs, true, false);
                    if (was == MOVE && !moved) seek(msOf(x), false);
                    else seek(was == END ? endMs : startMs, false);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (was == HEAD) {
                    seek(msOf(x), false);
                }
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    /** A second finger: whatever the first was doing ends where it is, and the strip zooms. */
    private void beginPinch() {
        if (pinching) return;
        pinching = true;
        removeCallbacks(holdCheck);
        removeCallbacks(panner);
        panning = false;
        if (viewAnimator != null) viewAnimator.cancel();
        int was = dragging;
        dragging = NONE;
        restoreStart = restoreEnd = -1;
        fineLevel = 0;
        if ((was == START || was == END || was == MOVE) && listener != null) {
            listener.rangeChanged(startMs, endMs, true, false);
        }
        invalidate();
    }

    /** Applies the drag under way to a finger at x in the current view. */
    private void drag(float x) {
        long ms = msOf(x);
        if (dragging == START) {
            startMs = clamp(ms, Math.max(0, endMs - MAX_LENGTH_MS), endMs - MIN_LENGTH_MS);
            notifyRange(true);
            seek(startMs, true);
            tickAt(startMs);
        } else if (dragging == END) {
            endMs = clamp(ms, startMs + MIN_LENGTH_MS, Math.min(durationMs, startMs + MAX_LENGTH_MS));
            notifyRange(true);
            seek(endMs, true);
            tickAt(endMs);
        } else if (dragging == MOVE) {
            long len = downEnd - downStart;
            long s = clamp(downStart + ms - downMs, 0, durationMs - len);
            startMs = s;
            endMs = s + len;
            notifyRange(true);
        } else if (dragging == HEAD) {
            seek(ms, true);
        }
        invalidate();
    }

    /** Zoomed in close, a light tick each time the edge crosses a tenth of a second. */
    private void tickAt(long ms) {
        if (fineLevel == 0) return;
        long tenth = ms / 100;
        if (lastTickTenth != Long.MIN_VALUE && tenth != lastTickTenth) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        lastTickTenth = tenth;
    }

    private void scheduleHold(long delay) {
        removeCallbacks(holdCheck);
        postDelayed(holdCheck, delay);
    }

    /** The finger rested on a handle: zoom in one level around it, the handle kept under the finger. */
    private final Runnable holdCheck = new Runnable() {
        @Override
        public void run() {
            if ((dragging != START && dragging != END) || pinching || panning || fineLevel >= FINE_LEVELS) return;
            long span = viewEnd - viewStart;
            long floor = Math.min(FINE_SPAN_MS, durationMs);
            long target = Math.max(floor, fineLevel == 0 ? Math.min(span / 4, 8000) : span / 3);
            if (target >= span * 0.85f) return;
            if (fineLevel == 0) {
                restoreStart = viewStart;
                restoreEnd = viewEnd;
            }
            fineLevel++;
            long edge = dragging == START ? startMs : endMs;
            float anchor = xOf(edge);
            animateSpanAround(edge, anchor, span, target);
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            lastTickTenth = edge / 100;
            if (listener != null) listener.fineZoomed();
            if (fineLevel < FINE_LEVELS) scheduleHold(NEXT_HOLD_MS + 240);
        }
    };

    private void animateSpanAround(long ms, float x, long fromSpan, long toSpan) {
        if (viewAnimator != null) viewAnimator.cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(240);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            setSpanAround(ms, x, fromSpan + Math.round((toSpan - fromSpan) * f));
        });
        viewAnimator = animator;
        animator.start();
    }

    /** Returns edge auto-pan velocity in [-1, 1], or 0 when outside the edge zone. */
    private float edgeDepth(float x) {
        if (!zoomed() || dragging == NONE) return 0;
        float zone = 36 * density, left = pad(), right = getWidth() - pad();
        if (dragging == MOVE) {
            float xs = xOf(startMs), xe = xOf(endMs);
            if (x < downX && xs < left + zone) return -Math.min(1f, (left + zone - xs) / zone);
            if (x > downX && xe > right - zone) return Math.min(1f, (xe - (right - zone)) / zone);
            return 0;
        }
        if (x < left + zone) return -Math.min(1f, (left + zone - x) / zone);
        if (x > right - zone) return Math.min(1f, (x - (right - zone)) / zone);
        return 0;
    }

    /** Restores the pre-hold zoom window after dragging and keeps the selection visible. */
    private void settle() {
        long fromStart = restoreStart >= 0 ? restoreStart : viewStart;
        long fromEnd = restoreStart >= 0 ? restoreEnd : viewEnd;
        restoreStart = restoreEnd = -1;
        fineLevel = 0;
        long toStart = fromStart, toEnd = fromEnd;
        boolean narrowed = toStart > 0 || toEnd < durationMs;
        if (narrowed && (startMs < toStart || endMs > toEnd)) {
            long length = endMs - startMs;
            long span = Math.min(durationMs, Math.max(toEnd - toStart, length + length / 2));
            toStart = clamp((startMs + endMs) / 2 - span / 2, 0, durationMs - span);
            toEnd = toStart + span;
        }
        animateView(toStart, toEnd);
    }

    private final Runnable panner = new Runnable() {
        @Override
        public void run() {
            float depth = edgeDepth(lastX);
            if (!panning || depth == 0) {
                panning = false;
                return;
            }
            long step = Math.round(depth * (viewEnd - viewStart) * PAN_PER_FRAME);
            if (step == 0) step = depth < 0 ? -1 : 1;
            if (shiftView(step) == 0) {
                panning = false;
                return;
            }
            drag(lastX);
            postOnAnimation(this);
        }
    };

    // endregion

    private void notifyRange(boolean dragging) {
        if (listener != null) listener.rangeChanged(startMs, endMs, true, dragging);
    }

    private void seek(long ms, boolean dragging) {
        playMs = ms;
        if (listener != null) listener.seekRequested(ms, dragging);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(holdCheck);
        removeCallbacks(panner);
        if (viewAnimator != null) viewAnimator.cancel();
    }

    /** For tests and previews: draw as if this edge were being held at fine zoom. */
    void previewDrag(boolean start, int level) {
        dragging = start ? START : END;
        fineLevel = level;
        invalidate();
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
