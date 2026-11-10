/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeRawAd;

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

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SeekbarTimeTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Settings.SEEKBAR_TIME.save(false);
        PausedProcess.set(false);
        SeekbarTime.clearTicks();
    }

    @After
    public void tearDown() {
        Settings.SEEKBAR_TIME.save(false);
        PausedProcess.set(false);
        SeekbarTime.clearTicks();
    }

    /** A post as the progress event carries it. */
    static final class Post extends Aweme {
        private final String aid;
        private final boolean ad;
        private final int type;

        Post(String aid, boolean ad, int type) {
            this.aid = aid;
            this.ad = ad;
            this.type = type;
        }

        static Post video(String aid) {
            return new Post(aid, false, 0);
        }

        @Override public String getAid() { return aid; }
        @Override public boolean isAd() { return ad; }
        @Override public AwemeRawAd getAwemeRawAd() { return null; }
        @Override public int getAwemeType() { return type; }
    }

    /** TikTok's bar as 47.1.4 builds it: a horizontal row holding one frame with the seek bar in it. */
    private LinearLayout bar() {
        LinearLayout bar = new LinearLayout(context);
        FrameLayout frame = new FrameLayout(context);
        frame.addView(new SeekBar(context), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return bar;
    }

    /** What PlayerController computes for the event: the position times 100 over the length, in floats. */
    private static float percent(long positionMs, long lengthMs) {
        return (float) positionMs * 100f / (float) lengthMs;
    }

    private void play(LinearLayout bar, Post post, long positionMs, long lengthMs) {
        float percent = percent(positionMs, lengthMs);
        SeekbarTime.onPlayTick(percent, positionMs, post);
        SeekbarTime.onBarProgress(bar, percent);
    }

    @Test
    public void theClockReadsMinutesAndSecondsAndHoursFromAnHourUp() {
        assertEquals("0:00", SeekbarTime.clock(0));
        assertEquals("0:09", SeekbarTime.clock(9));
        assertEquals("1:30", SeekbarTime.clock(90));
        assertEquals("59:59", SeekbarTime.clock(3599));
        assertEquals("1:00:00", SeekbarTime.clock(3600));
        assertEquals("1:02:03", SeekbarTime.clock(3723));
        assertEquals("0:00", SeekbarTime.clock(-5));
    }

    @Test
    public void theReadoutIsThatPercentOfTheLengthAndStaysInsideIt() {
        assertEquals("0:45 / 1:30", SeekbarTime.readout(50f, 90_000L));
        assertEquals("0:00 / 0:15", SeekbarTime.readout(0f, 15_045L));
        assertEquals("0:15 / 0:15", SeekbarTime.readout(100f, 15_045L));
        assertEquals("0:15 / 0:15", SeekbarTime.readout(140f, 15_045L));
        assertEquals("0:00 / 0:15", SeekbarTime.readout(-3f, 15_045L));
    }

    @Test
    public void theWidestReadoutKeepsTheShapeOfTheLength() {
        assertEquals("0:00 / 0:00", SeekbarTime.widest(0L));
        assertEquals("0:00 / 0:00", SeekbarTime.widest(90_000L));
        assertEquals("00:00 / 00:00", SeekbarTime.widest(754_000L));
        assertEquals("0:00:00 / 0:00:00", SeekbarTime.widest(3_723_000L));
    }

    @Test
    public void theLengthIsWorkedBackFromThePositionAndThePercent() {
        assertEquals(60_000L, SeekbarTime.lengthOf(percent(30_000L, 60_000L), 30_000L));
        assertEquals(15_045L, SeekbarTime.lengthOf(percent(250L, 15_045L), 250L));
        assertEquals(3_723_000L, SeekbarTime.lengthOf(percent(1_000L, 3_723_000L), 1_000L));
        assertEquals("nothing to work from at the start", 0L, SeekbarTime.lengthOf(0f, 0L));
    }

    @Test
    public void theTimeGoesAtTheEndOfTheRowAndTheFrameTakesTheRest() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout bar = bar();
        View frame = bar.getChildAt(0);
        ViewGroup.LayoutParams original = frame.getLayoutParams();

        play(bar, Post.video("1"), 30_000L, 60_000L);

        assertEquals(2, bar.getChildCount());
        assertSame(frame, bar.getChildAt(0));
        SeekbarTimeLabel label = (SeekbarTimeLabel) bar.getChildAt(1);
        assertEquals("0:30 / 1:00", label.getText().toString());
        assertEquals(1f, label.getAlpha(), 0f);
        LinearLayout.LayoutParams squeezed = (LinearLayout.LayoutParams) frame.getLayoutParams();
        assertNotSame("TikTok's own params are kept for later, not changed", original, squeezed);
        assertEquals(0, squeezed.width);
        assertEquals(1f, squeezed.weight, 0f);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, original.width);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, label.getImportantForAccessibility());
        assertTrue(!label.isClickable() && !label.isFocusable());
    }

    @Test
    public void turnedOffTheRowIsPutBackAsTikTokBuiltIt() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout bar = bar();
        View frame = bar.getChildAt(0);
        ViewGroup.LayoutParams original = frame.getLayoutParams();
        play(bar, Post.video("1"), 1_000L, 10_000L);
        assertEquals(2, bar.getChildCount());

        Settings.SEEKBAR_TIME.save(false);
        SeekbarTime.onBarProgress(bar, 11f);

        assertEquals(1, bar.getChildCount());
        assertSame(original, frame.getLayoutParams());
    }

    @Test
    public void pausedNothingIsKeptOrAdded() {
        Settings.SEEKBAR_TIME.save(true);
        PausedProcess.set(true);
        LinearLayout bar = bar();

        play(bar, Post.video("1"), 30_000L, 60_000L);

        assertEquals(1, bar.getChildCount());
        assertNull(SeekbarTime.matching(50f, SystemClock.uptimeMillis()));
    }

    @Test
    public void offTheProgressEventIsNotKept() {
        SeekbarTime.onPlayTick(50f, 30_000L, Post.video("1"));
        assertNull(SeekbarTime.matching(50f, SystemClock.uptimeMillis()));
    }

    @Test
    public void anEventMatchesOnlyItsOwnPercentAndOnlyWhileFresh() {
        Settings.SEEKBAR_TIME.save(true);
        SeekbarTime.onPlayTick(50f, 30_000L, Post.video("1"));
        long now = SystemClock.uptimeMillis();

        assertNotNull(SeekbarTime.matching(50f, now));
        assertNull(SeekbarTime.matching(51f, now));
        assertNull(SeekbarTime.matching(50f, now + SeekbarTime.TICK_FRESH_MS + 1));
    }

    @Test
    public void adsAndLiveGetNoTime() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout ad = bar();
        play(ad, new Post("ad", true, 0), 4_000L, 10_000L);
        SeekbarTimeLabel adLabel = SeekbarTimeLabel.find(ad);
        assertEquals("", adLabel.getText().toString());
        assertEquals(0f, adLabel.getAlpha(), 0f);

        LinearLayout live = bar();
        play(live, new Post("live", false, SeekbarTime.AWEME_TYPE_LIVE), 2_000L, 10_000L);
        SeekbarTimeLabel liveLabel = SeekbarTimeLabel.find(live);
        assertEquals("", liveLabel.getText().toString());
        assertEquals(0f, liveLabel.getAlpha(), 0f);
    }

    @Test
    public void aRedrawKeepsTheVideoALoopKeepsItAndAResetBlanksIt() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout bar = bar();
        Post first = Post.video("1");
        play(bar, first, 10_000L, 20_000L);
        SeekbarTimeLabel label = SeekbarTimeLabel.find(bar);
        assertEquals("0:10 / 0:20", label.getText().toString());

        // TikTok redrawing the bar on a pause, with no event of its own.
        SeekbarTime.onBarProgress(bar, 60f);
        assertEquals("0:12 / 0:20", label.getText().toString());

        // The video loops and the player reports its start.
        SeekbarTime.onPlayTick(0f, 0L, first);
        SeekbarTime.onBarProgress(bar, 0f);
        assertEquals("0:00 / 0:20", label.getText().toString());

        // TikTok resets the bar for another video, which hasn't reported yet.
        SeekbarTime.clearTicks();
        SeekbarTime.onBarProgress(bar, 0f);
        assertEquals("", label.getText().toString());
        assertEquals(0f, label.getAlpha(), 0f);

        // A start reported by some other video doesn't name this bar's video.
        play(bar, first, 10_000L, 20_000L);
        SeekbarTime.onPlayTick(0f, 0L, Post.video("2"));
        SeekbarTime.onBarProgress(bar, 0f);
        assertEquals("", label.getText().toString());
    }

    @Test
    public void aDragHidesTheTimeUntilTheBarSettles() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout bar = bar();
        play(bar, Post.video("1"), 30_000L, 60_000L);
        SeekbarTimeLabel label = SeekbarTimeLabel.find(bar);
        assertEquals(1f, label.getAlpha(), 0f);

        SeekbarTime.onBarStyle(bar, 100);
        assertEquals(0f, label.getAlpha(), 0f);
        SeekbarTime.onBarStyle(bar, 102);
        assertEquals(0f, label.getAlpha(), 0f);
        SeekbarTime.onBarStyle(bar, 0);
        assertEquals(1f, label.getAlpha(), 0f);
    }

    @Test
    public void theTimeFollowsTheSeekBarHidingAndFading() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout bar = bar();
        play(bar, Post.video("1"), 30_000L, 60_000L);
        SeekbarTimeLabel label = SeekbarTimeLabel.find(bar);
        SeekBar seekBar = (SeekBar) ((ViewGroup) bar.getChildAt(0)).getChildAt(0);

        // TikTok's hidden show type takes the seek bar out of the frame.
        seekBar.setVisibility(View.GONE);
        label.onPreDraw();
        assertEquals(0f, label.getAlpha(), 0f);

        // Its hidden-until-touched type leaves it in place, see-through.
        seekBar.setVisibility(View.VISIBLE);
        seekBar.setAlpha(0f);
        label.onPreDraw();
        assertEquals(0f, label.getAlpha(), 0f);

        seekBar.setAlpha(0.5f);
        label.onPreDraw();
        assertEquals(0.5f, label.getAlpha(), 0f);
    }

    @Test
    public void theLabelWidensOnlyWhenTheLengthNeedsAnotherDigit() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout bar = bar();
        play(bar, Post.video("1"), 30_000L, 60_000L);
        SeekbarTimeLabel label = SeekbarTimeLabel.find(bar);
        int width = label.getLayoutParams().width;
        assertTrue(width > 0);

        play(bar, Post.video("1"), 31_000L, 60_000L);
        assertEquals("a new second keeps the width", width, label.getLayoutParams().width);
        play(bar, Post.video("2"), 5_000L, 90_000L);
        assertEquals("another short video keeps it too", width, label.getLayoutParams().width);
        play(bar, Post.video("3"), 377_000L, 754_000L);
        assertTrue("a 12:34 video needs room for 00:00", label.getLayoutParams().width > width);
    }

    @Test
    public void aBarOfAnotherShapeIsLeftAlone() {
        Settings.SEEKBAR_TIME.save(true);
        LinearLayout vertical = bar();
        vertical.setOrientation(LinearLayout.VERTICAL);
        SeekbarTime.onBarProgress(vertical, 50f);
        assertEquals(1, vertical.getChildCount());

        LinearLayout crowded = bar();
        crowded.addView(new View(context));
        SeekbarTime.onBarProgress(crowded, 50f);
        assertEquals(2, crowded.getChildCount());
        assertNull(SeekbarTimeLabel.find(crowded));
    }

    @Test
    public void theTimeIsDrawnInsideTheLabelOnOneLine() {
        Settings.SEEKBAR_TIME.save(true);
        for (long lengthMs : new long[]{14_000L, 754_000L, 3_723_000L}) {
            LinearLayout bar = bar();
            play(bar, Post.video("v" + lengthMs), lengthMs / 3, lengthMs);
            bar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(79, View.MeasureSpec.EXACTLY));
            bar.layout(0, 0, 1080, 79);

            SeekbarTimeLabel label = SeekbarTimeLabel.find(bar);
            assertNotNull(label);
            android.text.Layout layout = label.getLayout();
            assertNotNull(layout);
            float room = label.getWidth() - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight();
            assertEquals(label.getText().toString(), 1, layout.getLineCount());
            assertTrue("starts inside: " + layout.getLineLeft(0), layout.getLineLeft(0) >= -0.5f);
            assertTrue("ends inside: " + layout.getLineRight(0) + " of " + room,
                    layout.getLineRight(0) <= room + 0.5f);
        }
    }

    @Test
    public void aRefusedBarIsDescribedByItsOrientationAndChildren() {
        LinearLayout crowded = bar();
        crowded.addView(new View(context));
        assertEquals("horizontal [android.widget.FrameLayout, android.view.View]",
                SeekbarTimeLabel.shapeOf(crowded));

        LinearLayout vertical = bar();
        vertical.setOrientation(LinearLayout.VERTICAL);
        assertEquals("vertical [android.widget.FrameLayout]", SeekbarTimeLabel.shapeOf(vertical));
    }
}
