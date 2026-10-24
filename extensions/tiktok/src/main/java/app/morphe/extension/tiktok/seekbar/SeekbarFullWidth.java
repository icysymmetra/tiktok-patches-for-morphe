/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsSeekBar;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * A progress bar that runs from screen edge to screen edge (#173).
 *
 * <p>TikTok leaves a gap of its own at each end of the bar: the row's horizontal padding and
 * margins, the padding around the frame that holds the seek bar, and on 47.1.4 the seek bar's own
 * side margins (32 px on the Login2 emulator, the whole gap there). This takes those to zero on
 * each progress tick while the switch is on (TikTok can write them again when it lays out
 * another video), and gives the first values back the moment the switch is off or Hushfeed is
 * paused. The seek bar's own padding, which places its thumb at the ends, is left alone, so a
 * drag still runs from one end of the bar to the other.
 */
final class SeekbarFullWidth {
    /** What a row had before the first tick that widened it. */
    private static final Map<LinearLayout, Saved> SAVED = new WeakHashMap<>();

    private SeekbarFullWidth() {
    }

    static boolean isEnabled() {
        return Settings.SEEKBAR_FULL_WIDTH.get();
    }

    /** The values a row and its frame had, so off puts the row back exactly. */
    private static final class Saved {
        final int rowPaddingLeft, rowPaddingRight;
        final boolean hasMargins, relativeMargins;
        final int marginLeft, marginRight, marginStart, marginEnd;
        @Nullable final View frame;
        final int framePaddingLeft, framePaddingRight;
        @Nullable final SideMargins frameMargins;
        @Nullable final SideMargins seekBarMargins;

        Saved(LinearLayout row, @Nullable View frame) {
            rowPaddingLeft = row.getPaddingLeft();
            rowPaddingRight = row.getPaddingRight();
            ViewGroup.LayoutParams params = row.getLayoutParams();
            hasMargins = params instanceof ViewGroup.MarginLayoutParams;
            ViewGroup.MarginLayoutParams margins = hasMargins ? (ViewGroup.MarginLayoutParams) params : null;
            relativeMargins = margins != null && margins.isMarginRelative();
            marginLeft = margins != null ? margins.leftMargin : 0;
            marginRight = margins != null ? margins.rightMargin : 0;
            marginStart = margins != null ? margins.getMarginStart() : 0;
            marginEnd = margins != null ? margins.getMarginEnd() : 0;
            this.frame = frame;
            framePaddingLeft = frame != null ? frame.getPaddingLeft() : 0;
            framePaddingRight = frame != null ? frame.getPaddingRight() : 0;
            frameMargins = SideMargins.of(frame);
            View seekBar = seekBarIn(frame);
            seekBarMargins = seekBar == frame ? null : SideMargins.of(seekBar);
        }
    }

    /** A view's horizontal margins as they were, to zero and to give back. */
    private static final class SideMargins {
        final View view;
        final boolean relative;
        final int left, right, start, end;

        private SideMargins(View view, ViewGroup.MarginLayoutParams margins) {
            this.view = view;
            relative = margins.isMarginRelative();
            left = margins.leftMargin;
            right = margins.rightMargin;
            start = margins.getMarginStart();
            end = margins.getMarginEnd();
        }

        @Nullable
        static SideMargins of(@Nullable View view) {
            if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return null;
            return new SideMargins(view, (ViewGroup.MarginLayoutParams) view.getLayoutParams());
        }

        void zero() {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            boolean isRelative = margins.isMarginRelative();
            if (margins.leftMargin == 0 && margins.rightMargin == 0
                    && (!isRelative || (margins.getMarginStart() == 0 && margins.getMarginEnd() == 0))) return;
            margins.leftMargin = 0;
            margins.rightMargin = 0;
            if (isRelative) {
                margins.setMarginStart(0);
                margins.setMarginEnd(0);
            }
            view.setLayoutParams(margins);
        }

        void restore() {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            margins.leftMargin = left;
            margins.rightMargin = right;
            if (relative) {
                margins.setMarginStart(start);
                margins.setMarginEnd(end);
            }
            view.setLayoutParams(margins);
        }
    }

    /** The seek bar the frame is or holds, the first one in tree order, or null. */
    @Nullable
    private static View seekBarIn(@Nullable View view) {
        if (view instanceof AbsSeekBar) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = seekBarIn(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Whether the row is widened right now. */
    static boolean isWidened(LinearLayout row) {
        synchronized (SAVED) {
            return SAVED.containsKey(row);
        }
    }

    /**
     * Widens the row when [on], or puts it back when not. Cheap to call on every tick: it only
     * writes what differs, so an unchanged row isn't asked to lay out again.
     */
    static void apply(LinearLayout row, boolean on) {
        Saved saved;
        synchronized (SAVED) {
            saved = SAVED.get(row);
            if (!on) {
                SAVED.remove(row);
            } else if (saved == null) {
                saved = new Saved(row, frameOf(row));
                SAVED.put(row, saved);
            }
        }
        if (on) {
            widen(row, saved);
        } else if (saved != null) {
            restore(row, saved);
        }
    }

    /** The frame holding the seek bar: the row's first child that isn't the time label. */
    @Nullable
    private static View frameOf(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof SeekbarTimeLabel)) return child;
        }
        return null;
    }

    private static void widen(LinearLayout row, Saved saved) {
        if (row.getPaddingLeft() != 0 || row.getPaddingRight() != 0) {
            row.setPadding(0, row.getPaddingTop(), 0, row.getPaddingBottom());
        }
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            boolean relative = margins.isMarginRelative();
            if (margins.leftMargin != 0 || margins.rightMargin != 0
                    || (relative && (margins.getMarginStart() != 0 || margins.getMarginEnd() != 0))) {
                margins.leftMargin = 0;
                margins.rightMargin = 0;
                if (relative) {
                    margins.setMarginStart(0);
                    margins.setMarginEnd(0);
                }
                row.setLayoutParams(margins);
            }
        }
        View frame = saved.frame;
        if (frame != null && (frame.getPaddingLeft() != 0 || frame.getPaddingRight() != 0)) {
            frame.setPadding(0, frame.getPaddingTop(), 0, frame.getPaddingBottom());
        }
        if (saved.frameMargins != null) saved.frameMargins.zero();
        if (saved.seekBarMargins != null) saved.seekBarMargins.zero();
    }

    private static void restore(LinearLayout row, Saved saved) {
        if (row.getPaddingLeft() != saved.rowPaddingLeft || row.getPaddingRight() != saved.rowPaddingRight) {
            row.setPadding(saved.rowPaddingLeft, row.getPaddingTop(), saved.rowPaddingRight, row.getPaddingBottom());
        }
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (saved.hasMargins && params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.leftMargin = saved.marginLeft;
            margins.rightMargin = saved.marginRight;
            if (saved.relativeMargins) {
                margins.setMarginStart(saved.marginStart);
                margins.setMarginEnd(saved.marginEnd);
            }
            row.setLayoutParams(margins);
        }
        View frame = saved.frame;
        if (frame != null && (frame.getPaddingLeft() != saved.framePaddingLeft
                || frame.getPaddingRight() != saved.framePaddingRight)) {
            frame.setPadding(saved.framePaddingLeft, frame.getPaddingTop(),
                    saved.framePaddingRight, frame.getPaddingBottom());
        }
        if (saved.frameMargins != null) saved.frameMargins.restore();
        if (saved.seekBarMargins != null) saved.seekBarMargins.restore();
    }
}
