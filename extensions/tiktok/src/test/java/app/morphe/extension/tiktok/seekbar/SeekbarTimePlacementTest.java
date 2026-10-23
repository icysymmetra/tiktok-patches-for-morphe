/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The time above the bar and the full-width bar (#173): where the label goes, when it steps back
 * beside the bar, what it unclips on the way and that every change is given back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SeekbarTimePlacementTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2000;
    private static final int ROW = 70;

    private Context context;
    private FrameLayout window;
    private FrameLayout container;
    private FrameLayout inner;
    private LinearLayout bar;
    private View frame;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        reset();
        SeekbarTime.clearTicks();
    }

    @After
    public void tearDown() {
        reset();
        SeekbarTime.clearTicks();
    }

    private void reset() {
        Settings.SEEKBAR_TIME.save(false);
        Settings.SEEKBAR_TIME_PLACEMENT.save("beside");
        Settings.SEEKBAR_FULL_WIDTH.save(false);
        PausedProcess.set(false);
    }

    /**
     * The bottom of the main feed as far as this goes: a window, a strip at its foot holding a
     * smaller box, and TikTok's one-frame row in that box. The strip is taller than the box, so
     * the spot above the row is inside the strip but outside the box.
     */
    private void build() {
        window = new FrameLayout(context);
        container = new FrameLayout(context);
        inner = new FrameLayout(context);
        bar = new LinearLayout(context);
        FrameLayout seekFrame = new FrameLayout(context);
        seekFrame.addView(new SeekBar(context), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame = seekFrame;
        bar.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        inner.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ROW, Gravity.BOTTOM));
        window.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 200, Gravity.BOTTOM));
        layoutAll();
    }

    private void layoutAll() {
        window.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, WIDTH, HEIGHT);
    }

    /** A line of text in the window at [top], [height] tall and [width] wide from the left edge. */
    private TextView text(String words, int top, int height, int width) {
        TextView caption = new TextView(context);
        caption.setText(words);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.TOP);
        params.topMargin = top;
        window.addView(caption, params);
        layoutAll();
        return caption;
    }

    private static class Post extends com.ss.android.ugc.aweme.feed.model.Aweme {
        @Override public String getAid() { return "1"; }
        @Override public boolean isAd() { return false; }
        @Override public com.ss.android.ugc.aweme.feed.model.AwemeRawAd getAwemeRawAd() { return null; }
        @Override public int getAwemeType() { return 0; }
    }

    /** A progress tick at 30 of 60 seconds, which attaches the label and asks where it goes. */
    private SeekbarTimeLabel tick() {
        SeekbarTime.onPlayTick(50f, 30_000L, new Post());
        SeekbarTime.onBarProgress(bar, 50f);
        layoutAll();
        SeekbarTimeLabel label = SeekbarTimeLabel.find(bar);
        assertNotNull(label);
        label.onPreDraw();
        return label;
    }

    private static LinearLayout.LayoutParams params(View label) {
        return (LinearLayout.LayoutParams) label.getLayoutParams();
    }

    @Test
    public void besideIsTheDefaultAndNothingMoves() {
        Settings.SEEKBAR_TIME.save(true);
        build();

        SeekbarTimeLabel label = tick();

        assertFalse(label.isAbove());
        assertEquals(0, params(label).getMarginStart());
        assertEquals(0f, label.getTranslationX(), 0f);
        assertEquals(0f, label.getTranslationY(), 0f);
        assertTrue("the bar still gives the time its room", frame.getWidth() < WIDTH);
        assertTrue(bar.getClipChildren());
    }

    @Test
    public void aboveGivesTheBarTheWholeRowAndLiftsTheTimeOverItsRightEnd() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();

        SeekbarTimeLabel label = tick();

        assertTrue(label.isAbove());
        assertEquals(-params(label).width, params(label).getMarginStart());
        assertEquals("the bar runs the whole row", WIDTH, frame.getWidth());
        assertEquals("over the bar's right end", frame.getRight(), label.getRight() + label.getTranslationX(), 0.5f);
        assertEquals("one row up", -ROW, label.getTranslationY(), 0.5f);
        assertEquals(Gravity.END, label.getGravity() & Gravity.END);
        assertEquals("0:30 / 1:00", label.getText().toString());
    }

    @Test
    public void aboveUnclipsExactlyTheViewsBetweenTheRowAndTheStripThatHoldsTheSpot() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();

        tick();

        assertFalse("the row", bar.getClipChildren());
        assertFalse("the box around the row", inner.getClipChildren());
        assertTrue("the strip holds the spot and keeps clipping", container.getClipChildren());
        assertTrue(window.getClipChildren());
        assertTrue("the box draws over the feed beside it", inner.getTranslationZ() > 0f);
        assertNull("without a shadow", inner.getOutlineProvider());
        assertEquals(0f, bar.getTranslationZ(), 0f);

        Settings.SEEKBAR_TIME_PLACEMENT.save("beside");
        tick();
        assertEquals("its Z is given back", 0f, inner.getTranslationZ(), 0f);
        assertNotNull("and its outline", inner.getOutlineProvider());
    }

    @Test
    public void anIconInTheSpotKeepsTheTimeBesideTheBar() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();
        // The Tako button over the bar's right end.
        android.widget.ImageView tako = new android.widget.ImageView(context);
        tako.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xffffffff));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(95, 95, Gravity.TOP | Gravity.END);
        params.topMargin = 1850;
        window.addView(tako, params);
        layoutAll();

        assertFalse(tick().isAbove());
    }

    @Test
    public void textInTheSpotKeepsTheTimeBesideTheBarAndTheTimeComesBackWhenItGoes() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();
        // A line that runs the whole width, right over the bar's right end: the strip's top is at
        // 1800 and the row at 1930, so the spot is 1860 to 1930.
        TextView caption = text("a long caption that reaches down", 1870, 40, WIDTH);

        SeekbarTimeLabel label = tick();
        assertFalse("the caption is in the way", label.isAbove());
        assertTrue(bar.getClipChildren());
        assertTrue("the bar keeps giving the time its room", frame.getWidth() < WIDTH);

        caption.setText("");
        long later = SystemClock.uptimeMillis() + 10 * SeekbarTimePlacement.RECHECK_MS;
        label.place(true, later);
        assertTrue("an empty line isn't in the way", label.isAbove());

        caption.setText("back again");
        label.place(true, later + 10 * SeekbarTimePlacement.RECHECK_MS);
        assertFalse(label.isAbove());
        assertEquals(0, params(label).getMarginStart());
        assertEquals(0f, label.getTranslationY(), 0f);
        assertTrue("clipping is given back", bar.getClipChildren());
        assertTrue(inner.getClipChildren());
    }

    @Test
    public void textThatIsOutOfTheSpotHiddenOrFadedDoesNotBlock() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();
        text("high up in the caption", 1500, 40, 700);
        TextView hidden = text("hidden in the spot", 1870, 40, WIDTH);
        hidden.setVisibility(View.INVISIBLE);
        TextView faded = text("faded in the spot", 1870, 40, WIDTH);
        faded.setAlpha(0f);
        // TikTok's caption stops short of the rail, so its last line sits beside the spot (#173).
        text("the caption's last line", 1870, 40, 700);

        SeekbarTimeLabel label = tick();
        assertTrue(label.isAbove());
        assertTrue("the label clears the caption", label.getLeft() + label.getTranslationX() >= 700);
    }

    @Test
    public void aSpotThatNoViewHoldsKeepsTheTimeBeside() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();
        // A row at the very top of the window has no room over it.
        inner.removeView(bar);
        window.removeAllViews();
        window.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW));
        layoutAll();

        assertFalse(tick().isAbove());
        assertTrue(window.getClipChildren());
    }

    @Test
    public void backToBesideAndOffGiveEverythingBack() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();
        ViewGroup.LayoutParams original = frame.getLayoutParams();
        SeekbarTimeLabel label = tick();
        assertTrue(label.isAbove());

        Settings.SEEKBAR_TIME_PLACEMENT.save("beside");
        SeekbarTime.onBarProgress(bar, 51f);
        layoutAll();
        label.onPreDraw();
        assertFalse(label.isAbove());
        assertEquals(0, params(label).getMarginStart());
        assertEquals(0f, label.getTranslationX(), 0f);
        assertEquals(0f, label.getTranslationY(), 0f);
        assertEquals(Gravity.END, label.getGravity() & Gravity.END);
        assertTrue(bar.getClipChildren() && inner.getClipChildren());

        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        SeekbarTimeLabel again = SeekbarTimeLabel.find(bar);
        again.place(true, SystemClock.uptimeMillis() + 10 * SeekbarTimePlacement.RECHECK_MS);
        assertTrue(again.isAbove());
        assertFalse(bar.getClipChildren());

        Settings.SEEKBAR_TIME.save(false);
        SeekbarTime.onBarProgress(bar, 52f);
        assertEquals(1, bar.getChildCount());
        assertSame(original, frame.getLayoutParams());
        assertTrue("off puts clipping back", bar.getClipChildren() && inner.getClipChildren());
    }

    @Test
    public void pausedPutsTheTimeAndTheClippingBack() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();
        tick();
        assertFalse(bar.getClipChildren());

        PausedProcess.set(true);
        SeekbarTime.onBarProgress(bar, 53f);

        assertEquals(1, bar.getChildCount());
        assertTrue(bar.getClipChildren() && inner.getClipChildren());
    }

    @Test
    public void aNewLengthKeepsTheTimeOverTheBarWithoutTakingRoomFromIt() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        build();
        SeekbarTimeLabel label = tick();
        int before = params(label).width;

        SeekbarTime.onPlayTick(50f, 377_000L, new Post() {
            @Override public String getAid() { return "2"; }
        });
        SeekbarTime.onBarProgress(bar, 50f);
        layoutAll();
        label.onPreDraw();

        assertTrue("a 12 minute video needs a wider time", params(label).width > before);
        assertEquals(-params(label).width, params(label).getMarginStart());
        assertEquals("the bar still has the whole row", WIDTH, frame.getWidth());
    }

    @Test
    public void onlyAboveIsReadAsAboveAndAnythingElseIsBeside() {
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        assertTrue(SeekbarTimePlacement.wantsAbove());
        Settings.SEEKBAR_TIME_PLACEMENT.save("beside");
        assertFalse(SeekbarTimePlacement.wantsAbove());
        Settings.SEEKBAR_TIME_PLACEMENT.save("sideways");
        assertFalse(SeekbarTimePlacement.wantsAbove());
        assertEquals("beside", Settings.SEEKBAR_TIME_PLACEMENT.defaultValue);
    }

    @Test
    public void windowPositionsAddUpEachParentsOffsetsAndScroll() {
        build();
        int[] at = new int[2];
        SeekbarTimePlacement.positionInWindow(bar, at);
        assertEquals(0, at[0]);
        assertEquals(HEIGHT - ROW, at[1]);
        container.scrollTo(0, -10);
        SeekbarTimePlacement.positionInWindow(bar, at);
        assertEquals(HEIGHT - ROW + 10, at[1]);
        assertEquals(new Rect(0, HEIGHT - ROW + 10, WIDTH, HEIGHT + 10), SeekbarTimePlacement.boundsInWindow(bar));
    }

    // Full width.

    /** A row with TikTok-like gaps: padding on the row and the frame and margins on the row. */
    private void buildWithGaps() {
        build();
        bar.setPadding(20, 3, 24, 5);
        frame.setPadding(6, 1, 9, 2);
        FrameLayout.LayoutParams rowParams = (FrameLayout.LayoutParams) bar.getLayoutParams();
        rowParams.leftMargin = 15;
        rowParams.rightMargin = 17;
        bar.setLayoutParams(rowParams);
        layoutAll();
    }

    @Test
    public void fullWidthTakesTheSideGapOffTheRowAndOffGivesItBackExactly() {
        buildWithGaps();
        Settings.SEEKBAR_FULL_WIDTH.save(true);

        SeekbarTime.onBarProgress(bar, 10f);
        layoutAll();

        FrameLayout.LayoutParams rowParams = (FrameLayout.LayoutParams) bar.getLayoutParams();
        assertEquals(0, bar.getPaddingLeft());
        assertEquals(0, bar.getPaddingRight());
        assertEquals("top and bottom padding stay", 3, bar.getPaddingTop());
        assertEquals(5, bar.getPaddingBottom());
        assertEquals(0, rowParams.leftMargin);
        assertEquals(0, rowParams.rightMargin);
        assertEquals(0, frame.getPaddingLeft());
        assertEquals(0, frame.getPaddingRight());
        assertEquals(1, frame.getPaddingTop());
        assertEquals("the bar reaches both edges", WIDTH, frame.getWidth());
        assertEquals(0, frame.getLeft());
        assertTrue(SeekbarFullWidth.isWidened(bar));

        Settings.SEEKBAR_FULL_WIDTH.save(false);
        SeekbarTime.onBarProgress(bar, 11f);
        layoutAll();

        assertFalse(SeekbarFullWidth.isWidened(bar));
        assertEquals(20, bar.getPaddingLeft());
        assertEquals(24, bar.getPaddingRight());
        assertEquals(15, rowParams.leftMargin);
        assertEquals(17, rowParams.rightMargin);
        assertEquals(6, frame.getPaddingLeft());
        assertEquals(9, frame.getPaddingRight());
        assertEquals(2, frame.getPaddingBottom());
        assertEquals("the row's padding and margins narrow the frame again",
                WIDTH - 15 - 17 - 20 - 24, frame.getWidth());
    }

    @Test
    public void pausedPutsTheGapBack() {
        buildWithGaps();
        Settings.SEEKBAR_FULL_WIDTH.save(true);
        SeekbarTime.onBarProgress(bar, 10f);
        assertEquals(0, bar.getPaddingLeft());

        PausedProcess.set(true);
        SeekbarTime.onBarProgress(bar, 11f);

        assertEquals(20, bar.getPaddingLeft());
        assertEquals(24, bar.getPaddingRight());
        assertEquals(15, ((FrameLayout.LayoutParams) bar.getLayoutParams()).leftMargin);
        assertEquals(6, frame.getPaddingLeft());
    }

    @Test
    public void fullWidthWinsBackWhenTikTokWritesTheGapAgain() {
        buildWithGaps();
        Settings.SEEKBAR_FULL_WIDTH.save(true);
        SeekbarTime.onBarProgress(bar, 10f);

        bar.setPadding(30, 3, 30, 5);
        SeekbarTime.onBarProgress(bar, 12f);
        assertEquals(0, bar.getPaddingLeft());
        assertEquals(0, bar.getPaddingRight());

        // The values restored on off are the first ones, not the ones TikTok wrote in between.
        Settings.SEEKBAR_FULL_WIDTH.save(false);
        SeekbarTime.onBarProgress(bar, 13f);
        assertEquals(20, bar.getPaddingLeft());
        assertEquals(24, bar.getPaddingRight());
    }

    @Test
    public void fullWidthWorksWithoutTheTimeAndAlongsideIt() {
        buildWithGaps();
        Settings.SEEKBAR_FULL_WIDTH.save(true);
        SeekbarTime.onBarProgress(bar, 10f);
        assertEquals("no time, no label", 1, bar.getChildCount());
        assertEquals(0, bar.getPaddingLeft());

        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        SeekbarTimeLabel label = tick();
        assertEquals(0, bar.getPaddingLeft());
        assertTrue(label.isAbove());
        assertEquals(WIDTH, frame.getWidth());
    }

    /**
     * 47.1.4's row on the Login2 emulator: no padding on the row, the seek bar straight in it with
     * 32 px side margins, which are the whole gap. Left alone, the switch changed nothing there.
     */
    @Test
    public void fullWidthTakesTheSeekBarsOwnSideMarginsOff() {
        build();
        bar.removeAllViews();
        SeekBar seekBar = new SeekBar(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.leftMargin = 32;
        params.rightMargin = 32;
        bar.addView(seekBar, params);
        layoutAll();
        assertEquals(32, seekBar.getLeft());
        Settings.SEEKBAR_FULL_WIDTH.save(true);

        SeekbarTime.onBarProgress(bar, 10f);
        layoutAll();
        assertEquals("the bar reaches the left edge", 0, seekBar.getLeft());
        assertEquals("and the right one", WIDTH, seekBar.getRight());

        Settings.SEEKBAR_FULL_WIDTH.save(false);
        SeekbarTime.onBarProgress(bar, 11f);
        layoutAll();
        assertEquals(32, ((LinearLayout.LayoutParams) seekBar.getLayoutParams()).leftMargin);
        assertEquals(32, ((LinearLayout.LayoutParams) seekBar.getLayoutParams()).rightMargin);
        assertEquals(WIDTH - 32, seekBar.getRight());

        // The seek bar inside build()'s frame gets the same.
        build();
        SeekBar inner = (SeekBar) ((ViewGroup) frame).getChildAt(0);
        FrameLayout.LayoutParams innerParams = (FrameLayout.LayoutParams) inner.getLayoutParams();
        innerParams.leftMargin = 12;
        innerParams.rightMargin = 14;
        inner.setLayoutParams(innerParams);
        Settings.SEEKBAR_FULL_WIDTH.save(true);
        SeekbarTime.onBarProgress(bar, 12f);
        assertEquals(0, innerParams.leftMargin);
        Settings.SEEKBAR_FULL_WIDTH.save(false);
        SeekbarTime.onBarProgress(bar, 13f);
        assertEquals(12, innerParams.leftMargin);
        assertEquals(14, innerParams.rightMargin);
    }

    @Test
    public void aRowWithNoMarginsOrFrameIsWidenedAndRestoredWithoutHarm() {
        LinearLayout lone = new LinearLayout(context);
        lone.setPadding(4, 0, 4, 0);
        SeekbarFullWidth.apply(lone, true);
        assertEquals(0, lone.getPaddingLeft());
        assertNull(lone.getLayoutParams());
        SeekbarFullWidth.apply(lone, false);
        assertEquals(4, lone.getPaddingLeft());
        assertEquals(4, lone.getPaddingRight());
    }
}
