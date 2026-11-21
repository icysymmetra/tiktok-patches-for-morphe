package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Burn-in guard (#191): the controls the fade covers dim a chosen number of seconds after the
 * last wake, through a pass the guard asks for itself, and only what the wake switches pick
 * brings them back. By default that is a tap on a dimmed control or a still press and hold; a
 * swipe, a tap on the video and the window coming back leave them dimmed, and a swipe asks for no
 * pass at all. The shift moves the window's content every two minutes and puts it back when it's
 * turned off. Off, Pause and a fade already below the dim leave the level alone, and Clear display
 * keeps following the chosen fade.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BurnInGuardTest {
    private static final int CELL_ID = 0x7f0a1d01;
    private static final int COLUMN_ID = 0x7f0a1d02;
    private static final int TABS_ID = 0x7f0a1d03;
    private static final int LIKE_ID = 0x7f0a1d04;
    private static final int MUSIC_ID = 0x7f0a1d05;
    private static final int SUBTITLE_ID = 0x7f0a1d06;
    private static final long WAIT = 5_000L;
    private static final float DIMMED = 0.25f;
    private static final long LONG_PRESS = ViewConfiguration.getLongPressTimeout();

    private boolean overlays;
    private Activity activity;
    private View cell;
    private View column;
    /** The like button's slot in the column, the part of the rail a tap on a control lands on. */
    private View like;
    private View disc;
    /** A view in the music row reaching past the rail onto the video, as on the A57. */
    private View notes;
    private View subtitles;
    private View tabs;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.4.3:ljn", COLUMN_ID);
        VideoOverlayHider.resolveForTests("47.4.3:uwq", TABS_ID);
        VideoOverlayHider.resolveForTests("47.4.3:g8a", LIKE_ID);
        VideoOverlayHider.resolveForTests("47.4.3:pop", MUSIC_ID);
        VideoOverlayHider.resolveForTests("47.4.3:dly", SUBTITLE_ID);
        overlays = SettingsStatus.videoOverlaysEnabled;
        SettingsStatus.videoOverlaysEnabled = true;
        BurnInGuard.resetForTests();
        OverlayTiming.resetForTests();
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        clear(false);
        Settings.CLEAR_DISPLAY.save(false);
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_DIM_AFTER.resetToDefault();
        Settings.BURN_IN_DIM_LEVEL.resetToDefault();
        Settings.BURN_IN_WAKE_CONTROL.resetToDefault();
        Settings.BURN_IN_WAKE_VIDEO.resetToDefault();
        Settings.BURN_IN_WAKE_SCROLL.resetToDefault();
        Settings.BURN_IN_WAKE_RETURN.resetToDefault();
        Settings.BURN_IN_WAKE_HOLD.resetToDefault();
        Settings.BURN_IN_DIM_RAIL.resetToDefault();
        Settings.BURN_IN_DIM_CAPTION.resetToDefault();
        Settings.BURN_IN_DIM_TOP.resetToDefault();
        Settings.BURN_IN_DIM_BOTTOM.resetToDefault();
        Settings.BURN_IN_DIM_SUBTITLES.resetToDefault();
        Settings.FADE_CONTROLS_OPACITY.save(100);
        SettingsStatus.videoOverlaysEnabled = overlays;
        BurnInGuard.resetForTests();
        OverlayTiming.resetForTests();
        ClearDisplayShownControls.resetForTests();
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.4.3:ljn", 0);
        VideoOverlayHider.resolveForTests("47.4.3:uwq", 0);
        VideoOverlayHider.resolveForTests("47.4.3:g8a", 0);
        VideoOverlayHider.resolveForTests("47.4.3:pop", 0);
        VideoOverlayHider.resolveForTests("47.4.3:dly", 0);
    }

    /**
     * A feed cell with the tab strip across the top and, as on 47.4.3, the rail's column across
     * the width of its lower part: the like button's slot at the right, and under it the music
     * row's slot across the column's width with its disc at the right. The subtitle strip sits
     * at the left, over the video.
     */
    private void feed(Activity on) {
        activity = on;
        Utils.setContext(on);
        FrameLayout root = new FrameLayout(on);
        FrameLayout frame = new FrameLayout(on);
        frame.setId(CELL_ID);
        cell = frame;
        FrameLayout rail = new FrameLayout(on);
        rail.setId(COLUMN_ID);
        column = rail;
        frame.addView(rail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 240, Gravity.BOTTOM));
        FrameLayout likeSlot = new FrameLayout(on);
        View heart = new View(on);
        heart.setId(LIKE_ID);
        likeSlot.addView(heart, new FrameLayout.LayoutParams(80, 80));
        rail.addView(likeSlot, new FrameLayout.LayoutParams(80, 80, Gravity.END | Gravity.TOP));
        like = likeSlot;
        FrameLayout musicSlot = new FrameLayout(on);
        FrameLayout musicRow = new FrameLayout(on);
        musicRow.setId(MUSIC_ID);
        notes = new View(on);
        musicRow.addView(notes, new FrameLayout.LayoutParams(120, 80, Gravity.END));
        disc = new View(on);
        musicRow.addView(disc, new FrameLayout.LayoutParams(80, 80, Gravity.END));
        musicSlot.addView(musicRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80));
        rail.addView(musicSlot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 80, Gravity.BOTTOM));
        subtitles = new View(on);
        subtitles.setId(SUBTITLE_ID);
        frame.addView(subtitles, new FrameLayout.LayoutParams(200, 40, Gravity.START | Gravity.CENTER_VERTICAL));
        tabs = new View(on);
        tabs.setId(TABS_ID);
        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60, Gravity.TOP));
        on.setContentView(root);
        idle();
    }

    /** Guard on in the mode given, with a first pass, the way a layout would start it. */
    private void guard(String mode) {
        Settings.BURN_IN_GUARD.save(mode);
        VideoOverlayHider.applyTo(activity);
    }

    private static void clear(boolean on) {
        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(on, on ? 0 : 2));
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lets {@code millis} go by, running whatever the main thread had due in them. */
    private static void after(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Lets time run to {@code uptime}, running whatever the main thread had due by then. */
    private static void until(long uptime) {
        long left = uptime - SystemClock.uptimeMillis();
        if (left > 0) after(left);
    }

    /** Where {@code view} shows on screen, in the window's coordinates a touch arrives in. */
    private static int[] centreOf(View view) {
        Rect shown = new Rect();
        assertTrue("the view is laid out on screen", view.getGlobalVisibleRect(shown));
        return new int[]{shown.centerX(), shown.centerY()};
    }

    /** A point on the video: in the cell, clear of the column, the subtitles and the tabs. */
    private int[] onTheVideo() {
        Rect shown = new Rect();
        assertTrue(cell.getGlobalVisibleRect(shown));
        return new int[]{shown.centerX(), shown.top + shown.height() / 3};
    }

    private long send(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
        return now;
    }

    /** A finger down and straight up at {@code at}. Answers when it lifted. */
    private long tap(int[] at) {
        send(MotionEvent.ACTION_DOWN, at[0], at[1]);
        long lifted = send(MotionEvent.ACTION_UP, at[0], at[1]);
        idle();
        return lifted;
    }

    /** A finger dragged a long way up from {@code at}, as a scroll to the next video is. */
    private void swipe(int[] at) {
        send(MotionEvent.ACTION_DOWN, at[0], at[1]);
        for (int i = 1; i <= 5; i++) send(MotionEvent.ACTION_MOVE, at[0], at[1] - 40 * i);
        send(MotionEvent.ACTION_UP, at[0], at[1] - 200);
        idle();
    }

    private void assertDimmed(String message) {
        assertEquals(message, DIMMED, column.getAlpha(), 0.0001f);
    }

    private void assertUp(String message) {
        assertEquals(message, 1f, column.getAlpha(), 0f);
    }

    @Test
    public void theControlsDimAfterTheWaitAndATapOnOneBringsThemBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            assertUp("a fresh window starts undimmed");
            assertEquals("and asks for a pass when the wait is up",
                    SystemClock.uptimeMillis() + WAIT, BurnInGuard.nextPassAtForTests());

            after(WAIT - 1);
            assertUp("not before the wait is up");
            after(1);
            assertDimmed("the guard's own pass dims, with no layout");
            assertEquals(DIMMED, tabs.getAlpha(), 0.0001f);
            assertEquals("a dimmed control is still there to tap", View.VISIBLE, column.getVisibility());

            long lifted = tap(centreOf(like));
            assertUp("a tap on a dimmed control brings them back");
            assertEquals(1f, tabs.getAlpha(), 0f);
            assertEquals("the wait starts again from that tap", lifted + WAIT, BurnInGuard.nextPassAtForTests());
            until(lifted + WAIT);
            assertDimmed("and runs out the same way");
        }
    }

    @Test
    public void theWaitAndTheLevelAreTheReadersToPick() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_DIM_AFTER.save(12);
            Settings.BURN_IN_DIM_LEVEL.save(40);
            guard(BurnInGuard.DIM);
            after(11_999);
            assertUp("12 seconds asked for");
            after(1);
            assertEquals("drawn at the 40 asked for", 0.4f, column.getAlpha(), 0.0001f);
        }
    }

    /** #191: scrolling kept the controls bright, and every lift ran an extra overlay pass. */
    @Test
    public void aSwipeNeitherWakesNorPutsOffTheDimAndAsksForNoPass() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            long started = SystemClock.uptimeMillis();
            after(2_000);
            int passes = OverlayTiming.passesForTests();
            swipe(onTheVideo());
            assertEquals("a swipe's lift runs no pass", passes, OverlayTiming.passesForTests());
            assertEquals("nor moves the dim", started + WAIT, BurnInGuard.nextPassAtForTests());
            until(started + WAIT);
            assertDimmed("so it dims on time while scrolling");

            swipe(onTheVideo());
            assertDimmed("and the next swipe leaves it dimmed");

            Settings.BURN_IN_WAKE_SCROLL.save(true);
            swipe(onTheVideo());
            assertUp("unless scrolling is picked to wake them");
        }
    }

    @Test
    public void aTapOnTheVideoWakesThemOnlyWithItsSwitch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(WAIT);
            tap(onTheVideo());
            assertDimmed("pausing leaves them dimmed");

            Settings.BURN_IN_WAKE_VIDEO.save(true);
            tap(onTheVideo());
            assertUp("with the video picked, the tap wakes them");
        }
    }

    @Test
    public void tapsOnAControlWakeNothingWithThatSwitchOff() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_WAKE_CONTROL.save(false);
            guard(BurnInGuard.DIM);
            after(WAIT);
            tap(centreOf(like));
            assertDimmed("with nothing picked they stay dimmed");
        }
    }

    /** A finger held still, for speed or a thumb resting on a paused video, keeps them up until it lifts. */
    @Test
    public void aStillPressKeepsTheControlsUpAndTheWaitStartsAtTheLift() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            int[] at = onTheVideo();
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            after(WAIT * 3);
            assertUp("held for 15 s, still up");
            assertTrue(BurnInGuard.holdingForTests());
            assertEquals("and no dim is timed while it's held", -1L, BurnInGuard.nextPassAtForTests());

            long lifted = send(MotionEvent.ACTION_UP, at[0], at[1]);
            idle();
            assertEquals("the lift times the wait", lifted + WAIT, BurnInGuard.nextPassAtForTests());
            until(lifted + WAIT);
            assertDimmed("which runs out after the lift");

            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            after(LONG_PRESS - 1);
            assertDimmed("a press shorter than a long press doesn't wake them");
            after(1);
            assertUp("a press held as long as a long press does");
            send(MotionEvent.ACTION_UP, at[0], at[1]);
            idle();
        }
    }

    @Test
    public void withTheHoldSwitchOffAStillFingerLetsThemDim() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_WAKE_HOLD.save(false);
            guard(BurnInGuard.DIM);
            int[] at = onTheVideo();
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            after(WAIT);
            assertDimmed("held, it dims all the same");
            assertFalse(BurnInGuard.holdingForTests());
        }
    }

    /** A press that opened something else ends there, and its up never reaches the feed's window. */
    @Test
    public void aHoldWhoseLiftWentElsewhereStillLetsThemDim() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            int[] at = onTheVideo();
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            after(WAIT);
            assertTrue(BurnInGuard.holdingForTests());
            // The long-press panel takes focus and gets the lift.
            activity.getWindow().getCallback().onWindowFocusChanged(false);
            idle();
            assertFalse("losing focus ends the hold", BurnInGuard.holdingForTests());
            after(WAIT);
            assertDimmed("and the wait runs from there");
        }
    }

    @Test
    public void comingBackToTheWindowWakesThemOnlyWithItsSwitch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(WAIT);
            // The comments sheet closing, or the way back from another screen.
            activity.getWindow().getCallback().onWindowFocusChanged(true);
            idle();
            assertDimmed("coming back leaves them dimmed by default");

            Settings.BURN_IN_WAKE_RETURN.save(true);
            activity.getWindow().getCallback().onWindowFocusChanged(true);
            idle();
            assertUp("with it picked, coming back wakes them");
        }
    }

    @Test
    public void onlyThePickedPartsDimAndOnlyTheyCountAsControls() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_DIM_TOP.save(false);
            guard(BurnInGuard.DIM);
            after(WAIT);
            assertDimmed("the rail is picked");
            assertEquals("the top tabs are not", 1f, tabs.getAlpha(), 0f);

            tap(centreOf(tabs));
            assertDimmed("a tap on a part that doesn't dim is a tap on the video");
        }
    }

    /** Clear display does its own job: a tap that goes through a faded control lands on the video. */
    @Test
    public void inTheFadedClearDisplayATapThroughAControlIsATapOnTheVideo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            guard(BurnInGuard.DIM);
            after(WAIT);
            assertTrue("the faded Clear display lets taps through", TapThroughControls.isMarked(column));
            assertDimmed("drawn at the dim instead of the chosen half");
            tap(centreOf(like));
            assertDimmed("so the tap doesn't wake them");

            // Clear display ends with no pass since: the marks are stale, the taps are back.
            Settings.CLEAR_DISPLAY.save(false);
            clear(false);
            assertTrue("the mark waits for the next walk", TapThroughControls.isMarked(column));
            tap(centreOf(like));
            assertEquals("a tap on the rail is a tap on a control again, back at the chosen half",
                    0.5f, column.getAlpha(), 0.0001f);
        }
    }

    @Test
    public void offPauseAndALowerFadeLeaveTheLevelAlone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            VideoOverlayHider.applyTo(activity);
            assertEquals("off asks for no pass", -1L, BurnInGuard.nextPassAtForTests());
            after(WAIT * 2);
            VideoOverlayHider.applyTo(activity);
            assertUp("off never dims");

            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            after(WAIT * 2);
            VideoOverlayHider.applyTo(activity);
            assertUp("Pause answers the stock look");
            assertEquals(-1L, BurnInGuard.nextPassAtForTests());
            PausedProcess.set(false);

            Settings.FADE_CONTROLS_OPACITY.save(10);
            VideoOverlayHider.applyTo(activity);
            after(WAIT * 2);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a fade below the dim stays where it was", 0.1f, column.getAlpha(), 0.0001f);
        }
    }

    /** The dim lowers what's drawn and nothing else: Clear display goes on following the chosen fade. */
    @Test
    public void inClearDisplayTheDimLeavesTheChosenFadeInCharge() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            after(WAIT);
            assertFalse("a dim doesn't turn Clear display into the faded one", TapThroughControls.isMarked(column));
            assertFalse(VideoOverlayHider.tapsGoThroughNow());

            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            assertTrue("the faded Clear display keeps its taps going through", TapThroughControls.isMarked(column));
            assertDimmed("drawn at the dim instead of the chosen half");

            clear(false);
            VideoOverlayHider.applyTo(activity);
            assertFalse(TapThroughControls.isMarked(column));
        }
    }

    /**
     * The column spans the cell's width on 47.4.3, and so does the music row's slot: only the
     * buttons are controls, so a tap beside them or on the music row's empty width is a tap on
     * the video, while the disc is a control.
     */
    @Test
    public void onlyTheButtonsInTheWideColumnAreControls() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(WAIT);
            assertDimmed("the column dims as a whole");
            Rect shown = new Rect();
            assertTrue(column.getGlobalVisibleRect(shown));

            tap(new int[]{shown.left + 10, shown.top + 120});
            assertDimmed("a tap in the column beside the buttons is a tap on the video");
            tap(new int[]{shown.left + 10, shown.bottom - 40});
            assertDimmed("so is one on the music row's empty width");
            Rect reach = new Rect();
            assertTrue(notes.getGlobalVisibleRect(reach));
            tap(new int[]{reach.left + 10, reach.centerY()});
            assertDimmed("and one where the music row reaches past the rail");

            tap(centreOf(disc));
            idle();
            assertUp("the disc is a control");
        }
    }

    /**
     * TikTok's Clear display leaves the rail's slots gone, and a walk then saw no slot showing.
     * Taken as the column's right half, the music row's reach past the rail was noted and stayed
     * a control after the slots came back. Each walk notes the window afresh now.
     */
    @Test
    public void aWalkWhileTheSlotsAreGoneLeavesNoStaleControls() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(WAIT);
            assertDimmed("dimmed after the wait");
            View likeSlot = like;
            View musicSlot = (View) disc.getParent().getParent();
            likeSlot.setVisibility(View.GONE);
            musicSlot.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            likeSlot.setVisibility(View.VISIBLE);
            musicSlot.setVisibility(View.VISIBLE);
            idle();
            Rect reach = new Rect();
            assertTrue(notes.getGlobalVisibleRect(reach));
            tap(new int[]{reach.left + 10, reach.centerY()});
            assertDimmed("the music row's reach is still the video");
        }
    }

    /** With "I press and hold" off a long press wakes nothing, on a button or the video. */
    @Test
    public void withTheHoldSwitchOffALongPressOnAButtonWakesNothing() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_WAKE_HOLD.save(false);
            guard(BurnInGuard.DIM);
            after(WAIT);
            assertDimmed("dimmed after the wait");
            int[] at = centreOf(like);
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            after(LONG_PRESS + 50);
            send(MotionEvent.ACTION_UP, at[0], at[1]);
            idle();
            assertDimmed("a long press is a hold, not a tap on the button");
        }
    }

    /**
     * A wake from dimmed posts the pass that draws them back up. A second wake before it runs (a
     * hold recognised, then its lift, both in the queue) must not take that pass off the queue.
     */
    @Test
    public void aSecondWakeBeforeTheRestoreRunsKeepsTheRestore() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(WAIT);
            assertDimmed("dimmed after the wait");
            int[] at = centreOf(like);
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            send(MotionEvent.ACTION_UP, at[0], at[1]);
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            send(MotionEvent.ACTION_UP, at[0], at[1]);
            idle();
            assertUp("the posted restore still ran");
            assertTrue("and the wait is set again", BurnInGuard.nextPassAtForTests() > SystemClock.uptimeMillis());
        }
    }

    /** Subtitles dim with the guard on their own switch, from full whatever fade the controls have. */
    @Test
    public void subtitlesDimOnTheirOwnSwitchAndATapOnThemIsATapOnTheVideo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(10);
            guard(BurnInGuard.DIM);
            assertEquals("bright before the wait", 1f, subtitles.getAlpha(), 0f);
            after(WAIT);
            assertEquals("dimmed though the controls' fade is below the dim",
                    DIMMED, subtitles.getAlpha(), 0.0001f);
            assertEquals("the controls keep their own fade", 0.1f, column.getAlpha(), 0.0001f);
            tap(centreOf(subtitles));
            assertEquals("a tap on them is a tap on the video", DIMMED, subtitles.getAlpha(), 0.0001f);

            Settings.BURN_IN_DIM_SUBTITLES.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off, they're back at full", 1f, subtitles.getAlpha(), 0f);

            Settings.BURN_IN_DIM_SUBTITLES.save(true);
            Settings.BURN_IN_GUARD.save(BurnInGuard.OFF);
            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            after(WAIT);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the chosen fade never reaches them", 1f, subtitles.getAlpha(), 0f);
        }
    }

    @Test
    public void theShiftMovesTheContentEveryTwoMinutesAndPutsItBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            View content = activity.findViewById(android.R.id.content);
            float step = BurnInGuard.STEP_DP * activity.getResources().getDisplayMetrics().density;
            guard(BurnInGuard.DIM_AND_SHIFT);
            assertAt(content, step);
            float x = content.getTranslationX();
            float y = content.getTranslationY();

            long now = SystemClock.uptimeMillis();
            after((now / BurnInGuard.SHIFT_EVERY_MS + 1) * BurnInGuard.SHIFT_EVERY_MS - now);
            assertAt(content, step);
            assertTrue("the guard's own pass moved it",
                    x != content.getTranslationX() || y != content.getTranslationY());
            after(BurnInGuard.SHIFT_EVERY_MS);
            assertAt(content, step);

            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            VideoOverlayHider.applyTo(activity);
            assertEquals("without the shift the content goes back", 0f, content.getTranslationX(), 0f);
            assertEquals(0f, content.getTranslationY(), 0f);
        }
    }

    @Test
    public void theExportSaysWhatThePassesAndTheGuardDid() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(WAIT);
            tap(centreOf(like));
            swipe(onTheVideo());
            String report = String.join("\n", OverlayTiming.sectionForTests().lines());
            assertTrue(report, report.contains("asked by Burn-in guard"));
            assertTrue(report, report.contains("Burn-in guard: dimmed 1 times; brought back by a control 1"));
            assertTrue(report, report.contains("Passes that walked the view tree: "));
            assertTrue(report, report.contains("taps on a control 1"));
            assertTrue(report, report.contains("swipes 1"));
            assertTrue(report, report.contains("Touch watchers on the feed's window: "));
            assertEquals(1, OverlayTiming.wakesForTests(OverlayTiming.WAKE_CONTROL));
        }
    }

    /**
     * With the fade at 100 and nothing else asking, the overlay pass doesn't walk the tree while
     * the controls are up; the guard asks for a walk now and then so a tap on a bright control is
     * still known for one and starts the wait again.
     */
    @Test
    public void aTapOnABrightControlStartsTheWaitAgainWithNothingElseOn() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(2_000);
            long lifted = tap(centreOf(like));
            assertUp("still up");
            assertEquals("the tap on the bright rail started the wait again",
                    lifted + WAIT, BurnInGuard.nextPassAtForTests());
            assertEquals("up, it restarts the wait rather than counting as bringing them back",
                    0, OverlayTiming.wakesForTests(OverlayTiming.WAKE_CONTROL));
        }
    }

    /** A dim landing under a finger that isn't a long press yet: once it is, the hold brings them back. */
    @Test
    public void aDimUnderAStillFingerLiftsOnceThePressIsLong() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            int[] at = onTheVideo();
            after(WAIT - LONG_PRESS / 2);
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            after(LONG_PRESS / 2);
            assertDimmed("the wait ran out under the finger");
            after(LONG_PRESS / 2);
            assertUp("the press grew long, and the hold brought them back");
            long lifted = send(MotionEvent.ACTION_UP, at[0], at[1]);
            idle();
            assertEquals("the wait starts at the lift", lifted + WAIT, BurnInGuard.nextPassAtForTests());
        }
    }

    @Test
    public void aFingerThatMovesOrIsJoinedByASecondIsNoHold() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            after(WAIT);
            int[] at = onTheVideo();
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            send(MotionEvent.ACTION_MOVE, at[0], at[1] - 100);
            after(LONG_PRESS * 2);
            assertDimmed("a finger past the slop is a swipe, and the hold check never wakes them");
            send(MotionEvent.ACTION_UP, at[0], at[1] - 100);
            idle();
            assertDimmed();

            long now = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            MotionEvent second = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    2, new int[]{0, 1}, new MotionEvent.PointerCoords[]{coords(at[0], at[1]), coords(at[0] + 50, at[1])},
                    0, 1f, 1f, 0, 0, 0, 0);
            try {
                activity.getWindow().getDecorView().dispatchTouchEvent(second);
            } finally {
                second.recycle();
            }
            after(LONG_PRESS * 2);
            assertDimmed("a pinch is no hold");
            send(MotionEvent.ACTION_UP, at[0], at[1]);
            idle();
            assertDimmed("and with scrolling not picked, it wakes nothing");
        }
    }

    @Test
    public void aCancelledHoldStartsTheWaitFromTheCancel() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            guard(BurnInGuard.DIM);
            int[] at = onTheVideo();
            send(MotionEvent.ACTION_DOWN, at[0], at[1]);
            after(WAIT);
            assertTrue(BurnInGuard.holdingForTests());
            long cancelled = send(MotionEvent.ACTION_CANCEL, at[0], at[1]);
            idle();
            assertFalse(BurnInGuard.holdingForTests());
            assertEquals(cancelled + WAIT, BurnInGuard.nextPassAtForTests());
            until(cancelled + WAIT);
            assertDimmed();
        }
    }

    /** The feed behind a detail pager lays out too; a wake runs its pass on the window that was touched. */
    @Test
    public void aWakeRunsItsPassOnTheWindowThatWasTouched() {
        try (var back = Robolectric.buildActivity(Activity.class).setup()) {
            feed(back.get());
            View backColumn = column;
            guard(BurnInGuard.DIM);
            try (var front = Robolectric.buildActivity(Activity.class).setup()) {
                Activity behind = activity;
                feed(front.get());
                VideoOverlayHider.applyTo(activity);
                after(WAIT);
                assertDimmed("the window in front dimmed");
                // The activity behind lays out last, as it does while a pager opens or closes.
                VideoOverlayHider.applyTo(behind);
                tap(centreOf(like));
                assertUp("the tapped window comes back, not the one behind");
                assertTrue(backColumn != column);
            }
        }
    }

    private static MotionEvent.PointerCoords coords(float x, float y) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        return coords;
    }

    private void assertDimmed() {
        assertDimmed("still dimmed");
    }

    private static void assertAt(View content, float step) {
        int[] at = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertEquals(at[0] * step, content.getTranslationX(), 0.0001f);
        assertEquals(at[1] * step, content.getTranslationY(), 0.0001f);
    }
}
