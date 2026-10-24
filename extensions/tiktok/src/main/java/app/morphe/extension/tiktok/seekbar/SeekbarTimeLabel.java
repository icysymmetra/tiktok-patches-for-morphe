/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * The time at the end of TikTok's progress bar row.
 *
 * <p>The row is TikTok's bar: a horizontal LinearLayout whose only child is the frame holding
 * the seek bar. Attaching gives that frame the row's leftover width and puts this label after it,
 * so the bar ends where the time starts. The frame's own layout params are kept and handed back
 * when the label comes out, which leaves the row exactly as TikTok built it.
 *
 * <p>The label stays in the row while it is attached, so the bar's length doesn't change between
 * videos or while a drag runs. What changes is whether it draws: it takes the seek bar's alpha
 * while the seek bar is visible and has a time to show, and none during a drag, when TikTok shows
 * its own big readout. The seek bar is asked before every frame is drawn, because TikTok hides it
 * in several places without going through the bar's progress method.
 *
 * <p>With the time set to go above the bar (#173) the label stays in the row but takes no room
 * in it, so the bar keeps the whole row, and is drawn over the bar's right end instead, under
 * the rail, where TikTok's caption stops short. That only
 * holds while the spot is free of text; see {@link #place}.
 *
 * <p>Screen readers skip it. The seek bar already reports the progress, and a label that changes
 * every second would only interrupt.
 */
@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
final class SeekbarTimeLabel extends TextView implements ViewTreeObserver.OnPreDrawListener {
    static final float TEXT_SP = 11f;
    /** The row is 18dp tall on the feed; a larger font scale stops here so the digits fit. */
    static final float TEXT_CAP_DP = 14f;
    static final float GAP_DP = 4f;
    /** The same distance TikTok leaves between the bar and the screen edge. */
    static final float EDGE_DP = 12f;
    static final int TEXT_COLOR = 0xE6FFFFFF;
    static final int SHADOW_COLOR = 0x99000000;

    private final LinearLayout row;
    private final View frame;
    private final ViewGroup.LayoutParams frameParams;
    @Nullable private ProgressBar seekBar;
    @Nullable private ViewTreeObserver observer;

    @Nullable private String aid;
    private long durationMs;
    private boolean hidden;
    private boolean dragging;
    /** Whether the last answer drew the label; null before the first. */
    @Nullable private Boolean drawn;
    private String sizedFor = "";

    /** Whether the time sits over the bar's right end instead of at the end of the row (#173). */
    private boolean above;
    /** When the row last asked whether the spot above is free; 0 before the first ask. */
    private long checkedAt;
    /** The ancestors that had clipChildren on and were given it off so the time can draw above. */
    private final List<ViewGroup> unclipped = new ArrayList<>();
    /** The view raised over the feed while the time is above, its own Z and outline to give back. */
    @Nullable private View raised;
    private float raisedZ;
    @Nullable private ViewOutlineProvider raisedOutline;

    private static boolean reportedShape;

    private SeekbarTimeLabel(Context context, LinearLayout row, View frame,
                             ViewGroup.LayoutParams frameParams) {
        super(context);
        this.row = row;
        this.frame = frame;
        this.frameParams = frameParams;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float text = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, metrics);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(text, TEXT_CAP_DP * metrics.density));
        setTextColor(TEXT_COLOR);
        setShadowLayer(2f * metrics.density, 0f, 0.5f * metrics.density, SHADOW_COLOR);
        // Every digit the same width, so the time doesn't shuffle sideways as it counts.
        setFontFeatureSettings("tnum");
        // One line, but not setSingleLine: that scrolls sideways, and a sideways-scrolling line
        // set to the end is laid out against a very wide edge, far off the label (#90). The
        // width always fits the time, so one line never has to wrap.
        setMaxLines(1);
        setHorizontallyScrolling(false);
        setIncludeFontPadding(false);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        setTextDirection(View.TEXT_DIRECTION_LTR);
        setPaddingRelative(Math.round(GAP_DP * metrics.density), 0,
                Math.round(EDGE_DP * metrics.density), 0);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setAlpha(0f);
    }

    /** The label already in this bar, or null. */
    @Nullable
    static SeekbarTimeLabel find(LinearLayout bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child instanceof SeekbarTimeLabel) return (SeekbarTimeLabel) child;
        }
        return null;
    }

    /**
     * The bar's label, added the first time. Null when the bar isn't the one-frame row it is on
     * 47.1.4, which is left alone rather than guessed at.
     */
    @Nullable
    static SeekbarTimeLabel attach(LinearLayout bar) {
        SeekbarTimeLabel label = find(bar);
        if (label != null) return label;
        if (bar.getOrientation() != LinearLayout.HORIZONTAL || bar.getChildCount() != 1) {
            refused(bar);
            return null;
        }
        View frame = bar.getChildAt(0);
        ViewGroup.LayoutParams original = frame.getLayoutParams();
        if (original == null) {
            refused(bar);
            return null;
        }

        LinearLayout.LayoutParams squeezed;
        if (original instanceof LinearLayout.LayoutParams) {
            squeezed = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) original);
        } else if (original instanceof ViewGroup.MarginLayoutParams) {
            squeezed = new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) original);
        } else {
            squeezed = new LinearLayout.LayoutParams(original);
        }
        squeezed.width = 0;
        squeezed.weight = 1f;

        label = new SeekbarTimeLabel(bar.getContext(), bar, frame, original);
        label.sizedFor = SeekbarTime.widest(0L);
        LinearLayout.LayoutParams own = new LinearLayout.LayoutParams(
                label.widthFor(label.sizedFor), ViewGroup.LayoutParams.MATCH_PARENT);
        own.gravity = Gravity.CENTER_VERTICAL;
        frame.setLayoutParams(squeezed);
        bar.addView(label, own);
        return label;
    }

    /** Says once a launch which bar shape was left alone, so a device log shows a new layout. */
    private static void refused(LinearLayout bar) {
        if (reportedShape) return;
        reportedShape = true;
        Logger.printInfo(() -> "Seekbar time: left a bar alone, " + shapeOf(bar));
    }

    /** The row's orientation and its children's classes, as the refusal reports them. */
    static String shapeOf(LinearLayout bar) {
        StringBuilder shape = new StringBuilder(
                bar.getOrientation() == LinearLayout.HORIZONTAL ? "horizontal [" : "vertical [");
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (i > 0) shape.append(", ");
            shape.append(child.getClass().getName());
            if (child.getLayoutParams() == null) shape.append(" without layout params");
        }
        return shape.append(']').toString();
    }

    /** Takes the label out and gives the frame back the layout params TikTok gave it. */
    static void removeFrom(LinearLayout bar) {
        SeekbarTimeLabel label = find(bar);
        if (label == null) return;
        label.goBeside();
        bar.removeView(label);
        if (label.frame.getParent() == bar) label.frame.setLayoutParams(label.frameParams);
    }

    /**
     * Shows the time for the percent the bar is drawing. A matching event names the video and its
     * length. A zero percent is TikTok resetting the bar for the next video, unless it came with
     * an event from the video already shown here (a loop back to the start), and blanks the time
     * until the new video reports. Any other percent without an event, such as TikTok redrawing
     * the bar on a pause, keeps the video it had.
     */
    void show(float percent, @Nullable SeekbarTime.Tick tick) {
        boolean sameVideo = tick != null && tick.aid != null && tick.aid.equals(aid);
        if (tick != null && (percent > 0f || sameVideo)) {
            if (!sameVideo) checkedAt = 0L;
            aid = tick.aid;
            hidden = tick.hidden;
            if (tick.durationMs > 0L) durationMs = tick.durationMs;
        } else if (percent <= 0f) {
            aid = null;
            durationMs = 0L;
            hidden = false;
        }
        String text = hidden || durationMs <= 0L ? "" : SeekbarTime.readout(percent, durationMs);
        if (!text.contentEquals(getText())) setText(text);
        if (!hidden && durationMs > 0L) fitTo(SeekbarTime.widest(durationMs));
        follow();
    }

    void setDragging(boolean dragging) {
        if (this.dragging == dragging) return;
        this.dragging = dragging;
        follow();
    }

    /** Draws as the seek bar does, and not at all without a time or during a drag. */
    void follow() {
        float wanted = 0f;
        String why;
        if (dragging) {
            why = "a drag";
        } else if (length() == 0) {
            why = "no time yet";
        } else {
            ProgressBar bar = seekBar();
            if (bar == null) {
                why = "no seek bar in the row";
            } else if (bar.getVisibility() != View.VISIBLE) {
                why = "the seek bar isn't visible";
            } else {
                wanted = bar.getAlpha();
                why = "the seek bar's alpha " + wanted;
            }
        }
        // The first answer is logged too, so a label that never shows still leaves a line.
        Boolean shows = wanted > 0f;
        if (!shows.equals(drawn)) {
            drawn = shows;
            Logger.printDebug(() -> "Seekbar time: " + (shows ? "shown, " : "hidden, ") + why);
        }
        if (getAlpha() != wanted) setAlpha(wanted);
    }

    @Override
    public boolean onPreDraw() {
        alignAbove();
        follow();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observer = getViewTreeObserver();
        observer.addOnPreDrawListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        restoreClipping();
        ViewTreeObserver attached = observer;
        if (attached != null && attached.isAlive()) {
            attached.removeOnPreDrawListener(this);
        } else {
            getViewTreeObserver().removeOnPreDrawListener(this);
        }
        observer = null;
        super.onDetachedFromWindow();
    }

    /**
     * Puts the time where the setting says (#173). Above the bar's right end only while that spot
     * is free: a laid-out row, an ancestor that holds the spot, and no text on screen reaching
     * into it. Otherwise it steps back beside the bar and asks again after {@link
     * SeekbarTimePlacement#RECHECK_MS}, or at once when the row shows another video.
     */
    void place(boolean wantAbove, long now) {
        if (!wantAbove) {
            if (above) goBeside();
            checkedAt = 0L;
            return;
        }
        if (checkedAt != 0L && now - checkedAt < SeekbarTimePlacement.RECHECK_MS) return;
        Rect spot = spotAbove();
        if (spot == null) {
            if (above) goBeside();
            return;
        }
        checkedAt = now;
        List<ViewGroup> chain = SeekbarTimePlacement.textReaches(row.getRootView(), spot, row)
                ? null : SeekbarTimePlacement.clipChain(row, spot);
        if (chain == null) {
            if (above) goBeside();
            return;
        }
        if (!above) goAbove();
        unclip(chain);
    }

    /** Whether the time sits above the bar right now. */
    boolean isAbove() {
        return above;
    }

    /** The window rectangle just over the bar's right end, or null before the row is laid out. */
    @Nullable
    private Rect spotAbove() {
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params == null || row.getWidth() <= 0 || row.getHeight() <= 0
                || frame.getWidth() <= 0 || params.width <= 0) {
            return null;
        }
        int[] at = new int[2];
        SeekbarTimePlacement.positionInWindow(frame, at);
        int[] rowAt = new int[2];
        SeekbarTimePlacement.positionInWindow(row, rowAt);
        int right = at[0] + frame.getWidth();
        return new Rect(right - params.width, rowAt[1] - row.getHeight(), right, rowAt[1]);
    }

    /**
     * The label stays the row's last child, so the row, Clear display's fade and the removal path
     * are the ones beside the bar uses. Its start margin cancels its width, which gives the bar
     * the whole row, and a translation (set before each draw, from the row's current layout)
     * lifts it over the bar's right end.
     */
    private void goAbove() {
        above = true;
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams own = (LinearLayout.LayoutParams) params;
            own.setMarginStart(-own.width);
            setLayoutParams(own);
        }
        setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        setPaddingRelative(Math.round(GAP_DP * density()), 0, Math.round(EDGE_DP * density()), 0);
    }

    /** The label back at the end of the row, as it was attached. */
    private void goBeside() {
        restoreClipping();
        if (!above) return;
        above = false;
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams own = (LinearLayout.LayoutParams) params;
            own.setMarginStart(0);
            setLayoutParams(own);
        }
        setTranslationX(0f);
        setTranslationY(0f);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        setPaddingRelative(Math.round(GAP_DP * density()), 0, Math.round(EDGE_DP * density()), 0);
    }

    /** Keeps the lift over the bar's right end true to the row's current layout. */
    private void alignAbove() {
        if (!above) return;
        float dx = frame.getRight() - getRight();
        float dy = -getHeight();
        if (getTranslationX() != dx) setTranslationX(dx);
        if (getTranslationY() != dy) setTranslationY(dy);
    }

    /** Lets exactly [chain] draw outside its bounds; any other group this label unclipped is put back. */
    private void unclip(List<ViewGroup> chain) {
        for (int i = unclipped.size() - 1; i >= 0; i--) {
            ViewGroup group = unclipped.get(i);
            if (!chain.contains(group)) {
                group.setClipChildren(true);
                unclipped.remove(i);
            }
        }
        for (ViewGroup group : chain) {
            if (group.getClipChildren()) {
                group.setClipChildren(false);
                unclipped.add(group);
            }
        }
        raise(chain.isEmpty() ? null : chain.get(chain.size() - 1));
    }

    /**
     * The bar's strip sits under the feed in the container they share, so the feed's player, drawn
     * after it, painted over a time lifted out of the strip (Login2 emulator, 2026-10-15). While the
     * time is above, the outermost view of the chain draws over its siblings by a pixel of Z, with no
     * outline so it casts no shadow; both are given back as they were.
     */
    private void raise(@Nullable View top) {
        if (top == raised) return;
        lower();
        if (top == null) return;
        raised = top;
        raisedZ = top.getTranslationZ();
        raisedOutline = top.getOutlineProvider();
        top.setOutlineProvider(null);
        top.setTranslationZ(raisedZ + Math.max(1f, density()));
    }

    private void lower() {
        if (raised == null) return;
        raised.setTranslationZ(raisedZ);
        raised.setOutlineProvider(raisedOutline);
        raised = null;
        raisedOutline = null;
    }

    /** Gives back every clipChildren this label turned off, and the Z it raised. */
    private void restoreClipping() {
        for (ViewGroup group : unclipped) group.setClipChildren(true);
        unclipped.clear();
        lower();
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** TikTok's seek bar, the one progress bar inside the frame. */
    @Nullable
    private ProgressBar seekBar() {
        if (seekBar == null) seekBar = firstProgressBar(frame);
        return seekBar;
    }

    @Nullable
    private static ProgressBar firstProgressBar(View view) {
        if (view instanceof ProgressBar) return (ProgressBar) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ProgressBar found = firstProgressBar(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * A fixed width, measured from the widest time this length can show. Fixed, because a label
     * sized to its text would ask for a new layout every second; this asks only when a video's
     * length needs another digit.
     */
    private void fitTo(String widest) {
        if (widest.equals(sizedFor)) return;
        sizedFor = widest;
        ViewGroup.LayoutParams params = getLayoutParams();
        int width = widthFor(widest);
        if (params != null && params.width != width) {
            params.width = width;
            if (above && params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).setMarginStart(-width);
            }
            setLayoutParams(params);
        }
    }

    private int widthFor(String text) {
        return (int) Math.ceil(getPaint().measureText(withWidestDigit(text)))
                + getPaddingLeft() + getPaddingRight();
    }

    /**
     * The text with every digit swapped for the font's widest one. Not every phone font has even
     * digits for "tnum", and a time a hair wider than its label would lose its second half.
     */
    private String withWidestDigit(String text) {
        char widest = '0';
        float widestWidth = -1f;
        for (char digit = '0'; digit <= '9'; digit++) {
            float width = getPaint().measureText(String.valueOf(digit));
            if (width > widestWidth) {
                widestWidth = width;
                widest = digit;
            }
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= '0' && c <= '9' ? widest : c);
        }
        return out.toString();
    }

    // Test access.
    View frame() {
        return frame;
    }

    ViewGroup.LayoutParams frameParams() {
        return frameParams;
    }
}
