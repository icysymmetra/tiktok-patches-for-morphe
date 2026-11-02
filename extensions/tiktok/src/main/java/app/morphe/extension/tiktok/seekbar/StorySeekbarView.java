/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * The line {@link StorySeekbar} puts along the bottom of a video story.
 *
 * <p>At rest it is a thin track with the part played filled in. A drag thickens it, adds a handle
 * and shows the time above the finger, and letting go seeks there.
 *
 * <p>The strip it sits in is shared with TikTok rather than taken from it. A touch that lands on
 * it goes on to whichever of TikTok's own views under it takes touches, the way the frame would
 * have sent it, so a tap still moves between stories and a press and hold still pauses. Only a
 * sideways drag is kept: TikTok's view is told the touch was cancelled, and the drag seeks. A swipe
 * up or down is handed back to the screens around the story.
 */
final class StorySeekbarView extends View {
    private static final int TRACK = 0x4DFFFFFF;
    private static final int FILL = 0xE6FFFFFF;
    private static final int BUBBLE = 0xB3000000;
    private static final int TEXT = 0xFFFFFFFF;

    static final float STRIP_DP = 48f;
    static final float INSET_DP = 12f;
    private static final float LINE_FROM_BOTTOM_DP = 10f;
    private static final float LINE_DP = 2f;
    private static final float DRAG_LINE_DP = 4f;
    private static final float HANDLE_DP = 7f;
    private static final float BUBBLE_GAP_DP = 8f;
    private static final float BUBBLE_PAD_DP = 8f;
    private static final float BUBBLE_HEIGHT_DP = 22f;
    private static final float TEXT_SP = 12f;

    /** How far an accessibility scroll moves the story. */
    static final long STEP_MS = 5_000L;
    /** After a seek the line keeps the new spot until a tick near it arrives, or this long. */
    static final long HOLD_MS = 1_000L;
    /** A tick this close to the spot a seek asked for is the seek having landed. */
    static final long NEAR_MS = 1_500L;

    /** The story this line was built for. */
    final String aid;
    private final StorySeekbar.Cell cell;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bubbleBox = new RectF();
    private final int slop;

    private WeakReference<Object> controller = new WeakReference<>(null);
    private long positionMs;
    private long durationMs;
    private long heldMs = -1L;
    private long heldUntil;

    private boolean tracking;
    private boolean dragging;
    private boolean released;
    private float downX;
    private float downY;
    private float dragFraction;
    /** TikTok's view that took this touch along with the line, until a drag takes it over. */
    @Nullable private View shared;

    StorySeekbarView(Context context, StorySeekbar.Cell cell, String aid) {
        super(context);
        this.cell = cell;
        this.aid = aid;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        for (Paint paint : new Paint[]{track, played}) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(TRACK);
        played.setColor(FILL);
        handle.setColor(FILL);
        bubble.setColor(BUBBLE);
        text.setColor(TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics()));
        setContentDescription(L10n.t("Story position"));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    int stripHeight() {
        return Math.round(dp(STRIP_DP));
    }

    /** A progress tick for this line's story from {@code controller}. */
    void update(Object controller, long positionMs, long durationMs) {
        if (this.controller.get() != controller) this.controller = new WeakReference<>(controller);
        boolean beforeSeek = heldMs >= 0L && SystemClock.uptimeMillis() < heldUntil
                && Math.abs(positionMs - heldMs) > NEAR_MS;
        if (!beforeSeek) {
            heldMs = -1L;
            this.positionMs = positionMs;
        }
        this.durationMs = durationMs;
        if (!dragging) invalidate();
    }

    boolean isDrivenBy(Object controller) {
        return this.controller.get() == controller;
    }

    long shownMs() {
        return heldMs >= 0L ? heldMs : positionMs;
    }

    float fraction() {
        if (durationMs <= 0L) return 0f;
        return clamp(shownMs() / (float) durationMs);
    }

    boolean isDragging() {
        return dragging;
    }

    /** Seeks the story to this percent and shows it there. False when the seek was refused. */
    boolean seekTo(float percent) {
        float target = StorySeekbar.seekPercent(percent);
        if (!StorySeekbar.seek(cell, controller.get(), aid, target)) return false;
        heldMs = Math.round(durationMs * (double) target / 100.0);
        heldUntil = SystemClock.uptimeMillis() + HOLD_MS;
        invalidate();
        return true;
    }

    private boolean seekToMs(long ms) {
        return durationMs > 0L && seekTo(ms * 100f / durationMs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return down(event);
            case MotionEvent.ACTION_MOVE:
                move(event);
                break;
            case MotionEvent.ACTION_UP:
                up(event);
                break;
            case MotionEvent.ACTION_CANCEL:
                share(event);
                endGesture();
                break;
            default:
                share(event);
                break;
        }
        return true;
    }

    private boolean down(MotionEvent event) {
        abandon();
        if (!StorySeekbar.isEnabled()) {
            Utils.runOnMainThread(this::leave);
            return false;
        }
        ViewParent parent = getParent();
        if (durationMs <= 0L || !(parent instanceof ViewGroup)) return false;
        // When none of TikTok's views under the finger takes the touch, it is left to TikTok
        // entirely rather than half taken.
        View under = shareDown((ViewGroup) parent, event);
        if (under == null) return false;
        shared = under;
        tracking = true;
        downX = event.getX();
        downY = event.getY();
        parent.requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void move(MotionEvent event) {
        if (!tracking) return;
        if (dragging) {
            dragTo(event.getX());
            return;
        }
        share(event);
        if (released) return;
        float dx = Math.abs(event.getX() - downX);
        float dy = Math.abs(event.getY() - downY);
        if (dx > slop && dx > dy) {
            cancelShared(event);
            dragging = true;
            dragTo(event.getX());
        } else if (dy > slop) {
            released = true;
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    private void up(MotionEvent event) {
        if (!tracking) return;
        if (!dragging) {
            share(event);
            endGesture();
            return;
        }
        dragTo(event.getX());
        float percent = dragFraction * 100f;
        endGesture();
        seekTo(percent);
    }

    private void dragTo(float x) {
        float inset = dp(INSET_DP);
        float span = getWidth() - 2f * inset;
        dragFraction = span <= 0f ? 0f : clamp((x - inset) / span);
        invalidate();
    }

    /** The first of the frame's other children under the touch to take it, top first, as the frame picks. */
    @Nullable
    private View shareDown(ViewGroup parent, MotionEvent event) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child == this || child.getVisibility() != VISIBLE) continue;
            MotionEvent copy = toChild(event, child);
            try {
                if (copy.getX() >= 0f && copy.getY() >= 0f && copy.getX() < child.getWidth()
                        && copy.getY() < child.getHeight() && child.dispatchTouchEvent(copy)) {
                    return child;
                }
            } finally {
                copy.recycle();
            }
        }
        return null;
    }

    private void share(MotionEvent event) {
        View target = shared;
        if (target == null) return;
        MotionEvent copy = toChild(event, target);
        try {
            target.dispatchTouchEvent(copy);
        } finally {
            copy.recycle();
        }
    }

    /** Tells TikTok's view the touch it had is over, and stops sharing. */
    private void cancelShared(@Nullable MotionEvent from) {
        View target = shared;
        shared = null;
        if (target == null) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = from != null ? toChild(from, target)
                : MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            target.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    /** This line's touch in a sibling's own coordinates, as the frame would hand it over. */
    private MotionEvent toChild(MotionEvent event, View child) {
        MotionEvent copy = MotionEvent.obtain(event);
        copy.offsetLocation(getLeft() - child.getLeft(), getTop() - child.getTop());
        Matrix matrix = child.getMatrix();
        if (!matrix.isIdentity()) {
            Matrix inverse = new Matrix();
            if (matrix.invert(inverse)) copy.transform(inverse);
        }
        return copy;
    }

    private void endGesture() {
        boolean redraw = dragging;
        tracking = false;
        dragging = false;
        released = false;
        shared = null;
        if (redraw) invalidate();
    }

    /** The line is out of the frame: whatever it was sharing is cancelled. */
    void abandon() {
        cancelShared(null);
        endGesture();
    }

    private void leave() {
        if (cell.line.get() == this) StorySeekbar.detach(cell);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        abandon();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!StorySeekbar.isEnabled()) {
            Utils.runOnMainThread(this::leave);
            return;
        }
        if (durationMs <= 0L) return;
        float inset = dp(INSET_DP);
        float left = inset;
        float right = getWidth() - inset;
        if (right <= left) return;
        float y = getHeight() - dp(LINE_FROM_BOTTOM_DP);
        float fraction = dragging ? dragFraction : fraction();
        float x = left + (right - left) * fraction;

        float width = dp(dragging ? DRAG_LINE_DP : LINE_DP);
        track.setStrokeWidth(width);
        played.setStrokeWidth(width);
        canvas.drawLine(left, y, right, y, track);
        if (x > left) canvas.drawLine(left, y, x, y, played);
        if (!dragging) return;

        canvas.drawCircle(x, y, dp(HANDLE_DP), handle);
        String label = SeekbarTime.readout(fraction * 100f, durationMs);
        float half = text.measureText(label) / 2f + dp(BUBBLE_PAD_DP);
        float center = Math.max(half, Math.min(getWidth() - half, x));
        float bottom = y - dp(HANDLE_DP) - dp(BUBBLE_GAP_DP);
        bubbleBox.set(center - half, bottom - dp(BUBBLE_HEIGHT_DP), center + half, bottom);
        float corner = dp(BUBBLE_HEIGHT_DP) / 2f;
        canvas.drawRoundRect(bubbleBox, corner, corner, bubble);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(label, center, bubbleBox.centerY() - (metrics.ascent + metrics.descent) / 2f, text);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return SeekBar.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (durationMs <= 0L) return;
        int total = seconds(durationMs);
        int now = Math.min(total, seconds(shownMs()));
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, total, now));
        if (now > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (now < total) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.setStateDescription(L10n.f("%1$s of %2$s",
                    SeekbarTime.clock(now), SeekbarTime.clock(total)));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        if (durationMs > 0L && StorySeekbar.isEnabled()) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return seekToMs(shownMs() + STEP_MS);
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return seekToMs(shownMs() - STEP_MS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && action == android.R.id.accessibilityActionSetProgress
                    && arguments != null
                    && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                float seconds = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                return seekToMs(Math.round(seconds * 1000.0));
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private static int seconds(long ms) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.max(0L, ms) / 1000.0));
    }

    private static float clamp(float fraction) {
        if (Float.isNaN(fraction)) return 0f;
        return Math.max(0f, Math.min(1f, fraction));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
