package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
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
 * Burn-in guard: the controls the fade covers dim after five seconds without a touch, through a
 * pass the guard asks for itself, and the next touch or the window coming back brings them back.
 * The shift moves the window's content every two minutes and puts it back when it's turned off.
 * Off, Pause and a fade already below the dim leave the level alone, and Clear display keeps
 * following the chosen fade.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BurnInGuardTest {
    private static final int CELL_ID = 0x7f0a1d01;
    private static final int COLUMN_ID = 0x7f0a1d02;
    private static final int TABS_ID = 0x7f0a1d03;
    private static final float DIMMED = BurnInGuard.IDLE_LEVEL / 100f;

    private boolean overlays;
    private Activity activity;
    private View column;
    private View tabs;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.4.3:mud", COLUMN_ID);
        VideoOverlayHider.resolveForTests("47.4.3:uwq", TABS_ID);
        overlays = SettingsStatus.videoOverlaysEnabled;
        SettingsStatus.videoOverlaysEnabled = true;
        BurnInGuard.resetForTests();
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        clear(false);
        Settings.CLEAR_DISPLAY.save(false);
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.FADE_CONTROLS_OPACITY.save(100);
        SettingsStatus.videoOverlaysEnabled = overlays;
        BurnInGuard.resetForTests();
        ClearDisplayShownControls.resetForTests();
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.4.3:mud", 0);
        VideoOverlayHider.resolveForTests("47.4.3:uwq", 0);
    }

    /** A feed cell with the rail's column in it, and the tabs under it. */
    private void feed(Activity on) {
        activity = on;
        Utils.setContext(on);
        FrameLayout root = new FrameLayout(on);
        FrameLayout cell = new FrameLayout(on);
        cell.setId(CELL_ID);
        column = new FrameLayout(on);
        column.setId(COLUMN_ID);
        cell.addView(column);
        tabs = new View(on);
        tabs.setId(TABS_ID);
        root.addView(cell);
        root.addView(tabs);
        on.setContentView(root);
    }

    private static void clear(boolean on) {
        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(on, on ? 0 : 2));
    }

    /** Lets {@code millis} go by, running whatever the main thread had due in them. */
    private static void after(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /**
     * A finger down and up the way the system delivers it: to the decor view, which hands it to
     * the callback. Answers when it lifted: the frame the touch draws moves Robolectric's clock on
     * by its frame delay, so a wait counted from after this ran past the deadline.
     */
    private long touch() {
        send(MotionEvent.ACTION_DOWN);
        long lifted = send(MotionEvent.ACTION_UP);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return lifted;
    }

    private long send(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 10f, 10f, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
        return now;
    }

    /** Lets time run to {@code uptime}, running whatever the main thread had due by then. */
    private static void until(long uptime) {
        long left = uptime - SystemClock.uptimeMillis();
        if (left > 0) after(left);
    }

    /** A finger held still, for speed or a thumb resting on a paused video, is a touch until it lifts. */
    @Test
    public void aFingerHeldDownKeepsTheControlsUpAndTheWaitStartsAtTheLift() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            VideoOverlayHider.applyTo(activity);
            send(MotionEvent.ACTION_DOWN);
            after(BurnInGuard.IDLE_AFTER_MS * 3);
            assertEquals("held for 15 s, still up", 1f, column.getAlpha(), 0f);
            assertEquals("and no dim is timed while it's down", -1L, BurnInGuard.nextPassAtForTests());

            send(MotionEvent.ACTION_UP);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the lift asks for a pass that times the wait",
                    SystemClock.uptimeMillis() + BurnInGuard.IDLE_AFTER_MS, BurnInGuard.nextPassAtForTests());
            after(BurnInGuard.IDLE_AFTER_MS);
            assertEquals("5 s after the lift it dims", DIMMED, column.getAlpha(), 0.0001f);
        }
    }

    /** A press that opened something else ends there, and its up never reaches the feed's window. */
    @Test
    public void aTouchWhoseLiftWentElsewhereStillLetsTheControlsDim() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            VideoOverlayHider.applyTo(activity);
            send(MotionEvent.ACTION_DOWN);
            activity.getWindow().getCallback().onWindowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            after(BurnInGuard.IDLE_AFTER_MS);
            assertEquals("coming back ends the touch", DIMMED, column.getAlpha(), 0.0001f);

            send(MotionEvent.ACTION_DOWN);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try (var next = Robolectric.buildActivity(Activity.class).setup()) {
                feed(next.get());
                VideoOverlayHider.applyTo(activity);
                after(BurnInGuard.IDLE_AFTER_MS);
                assertEquals("a new window doesn't inherit the old one's finger", DIMMED, column.getAlpha(), 0.0001f);
            }
        }
    }

    @Test
    public void theControlsDimAfterFiveSecondsWithoutATouchAndComeBackOnTheNext() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a fresh window starts undimmed", 1f, column.getAlpha(), 0f);
            assertEquals("and asks for a pass when the wait is up",
                    SystemClock.uptimeMillis() + BurnInGuard.IDLE_AFTER_MS, BurnInGuard.nextPassAtForTests());

            after(BurnInGuard.IDLE_AFTER_MS - 1);
            assertEquals(1f, column.getAlpha(), 0f);
            after(1);
            assertEquals("the guard's own pass dims, with no layout", DIMMED, column.getAlpha(), 0.0001f);
            assertEquals(DIMMED, tabs.getAlpha(), 0.0001f);
            assertEquals("a dimmed control is still there to tap", View.VISIBLE, column.getVisibility());

            long lifted = touch();
            assertEquals("the next touch brings them back", 1f, column.getAlpha(), 0f);
            assertEquals(1f, tabs.getAlpha(), 0f);
            assertEquals("the wait starts again from that touch",
                    lifted + BurnInGuard.IDLE_AFTER_MS, BurnInGuard.nextPassAtForTests());
            until(lifted + BurnInGuard.IDLE_AFTER_MS - 1);
            assertEquals(1f, column.getAlpha(), 0f);
            until(lifted + BurnInGuard.IDLE_AFTER_MS);
            assertEquals(DIMMED, column.getAlpha(), 0.0001f);
        }
    }

    @Test
    public void comingBackToTheWindowCountsAsATouch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            VideoOverlayHider.applyTo(activity);
            after(BurnInGuard.IDLE_AFTER_MS);
            assertEquals(DIMMED, column.getAlpha(), 0.0001f);

            // The comments sheet closing, or the way back from another screen.
            activity.getWindow().getCallback().onWindowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1f, column.getAlpha(), 0f);
        }
    }

    @Test
    public void offPauseAndALowerFadeLeaveTheLevelAlone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            VideoOverlayHider.applyTo(activity);
            assertEquals("off asks for no pass", -1L, BurnInGuard.nextPassAtForTests());
            after(BurnInGuard.IDLE_AFTER_MS * 2);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off never dims", 1f, column.getAlpha(), 0f);

            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM);
            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            after(BurnInGuard.IDLE_AFTER_MS * 2);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause answers the stock look", 1f, column.getAlpha(), 0f);
            assertEquals(-1L, BurnInGuard.nextPassAtForTests());
            PausedProcess.set(false);

            Settings.FADE_CONTROLS_OPACITY.save(10);
            VideoOverlayHider.applyTo(activity);
            after(BurnInGuard.IDLE_AFTER_MS * 2);
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
            after(BurnInGuard.IDLE_AFTER_MS);
            assertFalse("a dim doesn't turn Clear display into the faded one", TapThroughControls.isMarked(column));
            assertFalse(VideoOverlayHider.tapsGoThroughNow());

            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);
            assertTrue("the faded Clear display keeps its taps going through", TapThroughControls.isMarked(column));
            assertEquals("drawn at the dim instead of the chosen half", DIMMED, column.getAlpha(), 0.0001f);

            clear(false);
            VideoOverlayHider.applyTo(activity);
            assertFalse(TapThroughControls.isMarked(column));
        }
    }

    @Test
    public void theShiftMovesTheContentEveryTwoMinutesAndPutsItBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            View content = activity.findViewById(android.R.id.content);
            float step = BurnInGuard.STEP_DP * activity.getResources().getDisplayMetrics().density;
            Settings.BURN_IN_GUARD.save(BurnInGuard.DIM_AND_SHIFT);
            VideoOverlayHider.applyTo(activity);
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

    private static void assertAt(View content, float step) {
        int[] at = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertEquals(at[0] * step, content.getTranslationX(), 0.0001f);
        assertEquals(at[1] * step, content.getTranslationY(), 0.0001f);
    }
}
