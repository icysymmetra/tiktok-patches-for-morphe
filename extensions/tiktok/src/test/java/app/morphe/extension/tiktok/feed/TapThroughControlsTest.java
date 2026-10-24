package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;

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
 * Fade the video controls in Clear display (#84): the controls it covers stay in sight at the chosen
 * level, and a finger goes through them to the video under them, so a tap opens nothing and a pinch
 * or a press and hold still reaches TikTok's own Clear display handling. Outside Clear display a
 * faded control takes taps as before, leaving Clear display hands each control its taps back with
 * its own flags untouched, and Pause gives TikTok its look and its taps back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1080dp-h1920dp-mdpi")
public class TapThroughControlsTest {
    private static final int CELL_ID = 0x7f0a0f01;
    private static final int COLUMN_ID = 0x7f0a0f02;
    private static final int FRAME_ID = 0x7f0a0f03;
    private static final int COVER_ID = 0x7f0a0f04;
    private static final int SEARCH_ID = 0x7f0a0f05;
    private static final int PHOTO_EXIT_ID = 0x7f0a0f06;
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    private boolean overlays;
    private Activity activity;
    private View video;
    private View column;
    private View frame;
    private int videoClicks;
    private int columnClicks;
    private int frameClicks;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.4.3:mud", COLUMN_ID);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", FRAME_ID);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", COVER_ID);
        VideoOverlayHider.resolveForTests("47.4.3:ljb", SEARCH_ID);
        VideoOverlayHider.resolveForTests("47.4.3:uym", PHOTO_EXIT_ID);
        Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
        overlays = SettingsStatus.videoOverlaysEnabled;
        SettingsStatus.videoOverlaysEnabled = true;
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(false, 2));
        Settings.CLEAR_DISPLAY.save(false);
        Settings.FADE_CONTROLS_OPACITY.save(100);
        Settings.FADE_IN_CLEAR_DISPLAY.save(true);
        Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(false);
        SettingsStatus.videoOverlaysEnabled = overlays;
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.4.3:mud", 0);
        VideoOverlayHider.resolveForTests("47.4.3:bqr", 0);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", 0);
        VideoOverlayHider.resolveForTests("47.4.3:ljb", 0);
        VideoOverlayHider.resolveForTests("47.4.3:uym", 0);
        ClearDisplayShownControls.resetForTests();
    }

    /**
     * A feed cell as 47.1.4 lays it out: the video's own layer across the cell, the rail's column
     * at the right and the caption frame at the bottom left, side by side over it.
     */
    private void feed(Activity on) {
        activity = on;
        Utils.setContext(on);
        FrameLayout root = new FrameLayout(on);
        FrameLayout cell = new FrameLayout(on);
        cell.setId(CELL_ID);
        video = new View(on);
        video.setOnClickListener(v -> videoClicks++);
        cell.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column = new View(on);
        column.setId(COLUMN_ID);
        column.setOnClickListener(v -> columnClicks++);
        column.setLongClickable(false);
        cell.addView(column, new FrameLayout.LayoutParams(160, 480, Gravity.END | Gravity.TOP));
        frame = new View(on);
        frame.setId(FRAME_ID);
        frame.setOnClickListener(v -> frameClicks++);
        cell.addView(frame, new FrameLayout.LayoutParams(640, 240, Gravity.START | Gravity.BOTTOM));
        root.addView(cell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        on.setContentView(root);
    }

    private static void clear(boolean on) {
        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(on, on ? 0 : 2));
    }

    /** On the phone the photo close is inside the full-width rail, beside ordinary buttons. */
    private View[] photoControls(int[] closes) {
        ViewGroup cell = (ViewGroup) column.getParent();
        int index = cell.indexOfChild(column);
        cell.removeView(column);
        FrameLayout rail = new FrameLayout(activity);
        rail.setId(COLUMN_ID);
        column = rail;
        cell.addView(rail, index, new FrameLayout.LayoutParams(WIDTH, 600, Gravity.BOTTOM));
        View close = new View(activity);
        close.setId(PHOTO_EXIT_ID);
        close.setOnClickListener(v -> closes[0]++);
        rail.addView(close, new FrameLayout.LayoutParams(124, 124, Gravity.START | Gravity.BOTTOM));
        View ordinary = new View(activity);
        ordinary.setOnClickListener(v -> columnClicks++);
        rail.addView(ordinary, new FrameLayout.LayoutParams(160, 180, Gravity.END | Gravity.TOP));
        return new View[]{close, ordinary};
    }

    private void frameDrawn() {
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnPreDraw();
    }

    /** The window at a known size, so every point below is the same on each run. */
    private void layOutWindow() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, WIDTH, HEIGHT);
    }

    /** A point inside {@code view} in window coordinates, {@code fx} and {@code fy} across it. */
    private float[] at(View view, float fx, float fy) {
        layOutWindow();
        int[] corner = new int[2];
        view.getLocationInWindow(corner);
        assertTrue("laid out", view.getWidth() > 0 && view.getHeight() > 0);
        return new float[]{corner[0] + view.getWidth() * fx, corner[1] + view.getHeight() * fy};
    }

    /** A tap the way the system delivers it: to the window's decor view, which hands it to the callback. */
    private void tap(View target) {
        float[] point = at(target, 0.5f, 0.5f);
        long now = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0));
        send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, point[0], point[1], 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void send(MotionEvent event) {
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent twoFingers(long down, int action, float[] first, float[] second) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        float[][] points = {first, second};
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0];
            coords[i].y = points[i][1];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        return MotionEvent.obtain(down, down + 20, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    @Test
    public void inClearDisplayAFadedControlStaysInSightAndTheTapGoesToTheVideo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(50);

            VideoOverlayHider.applyTo(activity);
            tap(column);
            assertEquals("outside Clear display a faded control takes the tap", 1, columnClicks);
            assertEquals(0, videoClicks);
            assertFalse(TapThroughControls.isMarked(column));

            clear(true);
            VideoOverlayHider.applyTo(activity);
            // TikTok's Clear display animates the controls to 0. The next frame holds the level.
            column.setAlpha(0f);
            frame.setAlpha(0f);
            frameDrawn();
            assertEquals("the rail stays in sight at the chosen level", 0.5f, column.getAlpha(), 0f);
            assertEquals(0.5f, frame.getAlpha(), 0f);
            assertEquals("the caption frame stays rather than going invisible",
                    View.VISIBLE, frame.getVisibility());
            assertEquals(View.VISIBLE, column.getVisibility());

            tap(column);
            tap(frame);
            assertEquals("a faded control takes no tap in Clear display", 1, columnClicks);
            assertEquals(0, frameClicks);
            assertEquals("the taps go to the video under them", 2, videoClicks);
            assertEquals("nothing is left out of place", 0f, column.getTranslationX(), 0f);
            assertEquals(0f, frame.getTranslationX(), 0f);

            clear(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);
            // Leaving, TikTok animates its own opacity back up from 0. The level holds the whole
            // way rather than dropping to a share of 0 and climbing back.
            column.setAlpha(0.2f);
            frameDrawn();
            assertEquals(0.5f, column.getAlpha(), 0f);
            column.setAlpha(1f);
            frameDrawn();
            assertEquals(0.5f, column.getAlpha(), 0f);
            // Back at full, a value TikTok writes is faded by the share again.
            column.setAlpha(0.6f);
            frameDrawn();
            assertEquals(0.3f, column.getAlpha(), 0.0001f);

            tap(column);
            assertEquals("out of Clear display the control takes taps again", 2, columnClicks);
            assertEquals(2, videoClicks);
            assertTrue("TikTok's own flags were never changed", column.isClickable());
            assertFalse(column.isLongClickable());
            assertTrue(column.isEnabled());
        }
    }

    /**
     * #84 on 0.71.2: any fade below 100 kept the buttons in sight in Clear display, and someone
     * who wanted the fade on the feed had no way to keep Clear display clear. With Keep faded
     * controls in Clear display off, the fade stays outside it and Clear display clears as at 100.
     */
    @Test
    public void withTheFadeKeptOutOfClearDisplayItClearsAsAt100() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(50);
            Settings.FADE_IN_CLEAR_DISPLAY.save(false);

            VideoOverlayHider.applyTo(activity);
            assertEquals("outside Clear display the fade still applies", 0.5f, column.getAlpha(), 0f);
            tap(column);
            assertEquals(1, columnClicks);

            clear(true);
            VideoOverlayHider.applyTo(activity);
            // TikTok's Clear display takes the controls to 0, and that's what stays.
            column.setAlpha(0f);
            frame.setAlpha(0f);
            frameDrawn();
            assertEquals("the rail isn't held at the level", 0f, column.getAlpha(), 0f);
            assertEquals(0f, frame.getAlpha(), 0f);
            assertFalse("no tap is sent through", VideoOverlayHider.tapsGoThroughNow());
            assertFalse(TapThroughControls.isMarked(column));
            assertFalse(TapThroughControls.isMarked(frame));

            Settings.FADE_IN_CLEAR_DISPLAY.save(true);
            VideoOverlayHider.applyTo(activity);
            frameDrawn();
            assertEquals("switched back on, the level holds again", 0.5f, column.getAlpha(), 0f);
            assertTrue(VideoOverlayHider.tapsGoThroughNow());
        }
    }

    /** Switched off while Clear display is already on, the controls go back to TikTok's cleared look. */
    @Test
    public void turningTheSwitchOffInClearDisplayLetsTheControlsClear() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(50);

            clear(true);
            VideoOverlayHider.applyTo(activity);
            column.setAlpha(0f);
            frame.setAlpha(0f);
            frameDrawn();
            assertEquals(0.5f, column.getAlpha(), 0f);
            assertTrue(VideoOverlayHider.tapsGoThroughNow());
            assertTrue(TapThroughControls.isMarked(column));

            Settings.FADE_IN_CLEAR_DISPLAY.save(false);
            VideoOverlayHider.applyTo(activity);
            assertFalse("taps stop going through at once", VideoOverlayHider.tapsGoThroughNow());
            assertFalse(TapThroughControls.isMarked(column));
            assertFalse(TapThroughControls.isMarked(frame));

            // The level is let go the way a Clear display exit lets it go: held through one
            // frame, then TikTok's 0 once the hold has run out.
            column.setAlpha(0f);
            frame.setAlpha(0f);
            frameDrawn();
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001));
            column.setAlpha(0f);
            frame.setAlpha(0f);
            frameDrawn();
            assertEquals("the rail clears with TikTok", 0f, column.getAlpha(), 0f);
            assertEquals(0f, frame.getAlpha(), 0f);
        }
    }

    @Test
    public void aVisiblePhotoCloseInsideTheFadedRailKeepsItsNativeTap() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            int[] closes = {0};
            View[] controls = photoControls(closes);
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            float[] point = at(controls[0], 0.5f, 0.5f);
            Rect hit = new Rect();
            assertTrue("the native close has a clipped visible hit area", controls[0].getGlobalVisibleRect(hit));
            assertTrue("the window tap is inside that hit area", hit.contains((int) point[0], (int) point[1]));
            tap(controls[0]);
            assertEquals("the photo close must remain usable inside its faded ancestor", 1, closes[0]);
            assertEquals(0, videoClicks);
            tap(controls[1]);
            assertEquals("other faded controls still pass through", 0, columnClicks);
            assertEquals(1, videoClicks);
            assertEquals("the rail never stays displaced", 0f, column.getTranslationX(), 0f);
        }
    }

    @Test
    public void aHiddenOrDisabledPhotoCloseNeverKeepsItsRailInTheTouchSearch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            int[] closes = {0};
            View close = photoControls(closes)[0];
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            close.setAlpha(0f);
            tap(close);
            close.setAlpha(1f);
            close.setEnabled(false);
            tap(close);
            close.setEnabled(true);
            Settings.HIDE_CLEAR_DISPLAY_CONTROLS.save(true);
            VideoOverlayHider.applyTo(activity);
            tap(close);
            assertEquals(0, closes[0]);
            assertEquals("invisible or disabled controls cannot trap the three taps", 3, videoClicks);
        }
    }

    @Test
    public void aSecondFingerOnThePhotoCloseStillBelongsToTheVideoPinch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            View close = photoControls(new int[]{0})[0];
            List<Integer> videoEvents = new ArrayList<>();
            List<Integer> closeEvents = new ArrayList<>();
            video.setOnTouchListener((v, event) -> { videoEvents.add(event.getActionMasked()); return true; });
            close.setOnTouchListener((v, event) -> { closeEvents.add(event.getActionMasked()); return true; });
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            float[] first = at(video, 0.3f, 0.3f);
            float[] second = at(close, 0.5f, 0.5f);
            long now = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, first[0], first[1], 0));
            send(twoFingers(now, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second));
            send(twoFingers(now, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second));
            send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, first[0], first[1], 0));
            assertTrue("the close must not split off the second finger", closeEvents.isEmpty());
            assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP), videoEvents);
        }
    }

    /**
     * TikTok's Clear display puts each button wrapper in the rail's column at 0 and GONE, and the
     * caption frame and the music disc GONE (emulator, 47.1.4, 2026-10-10), so the faded column
     * alone showed nothing. The faded Clear display keeps the ones that were showing on the
     * current video, and only those; Pause gives TikTok its own look back, and leaving holds the
     * wrappers at full through TikTok's animation up from 0.
     */
    @Test
    public void theFadedClearDisplayKeepsWhatTikTokPutsAwayByVisibility() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            FrameLayout rail = new FrameLayout(activity);
            rail.setId(COLUMN_ID);
            View like = new View(activity);
            View share = new View(activity);
            // A button TikTok keeps away on this post.
            View follow = new View(activity);
            follow.setVisibility(View.GONE);
            rail.addView(like);
            rail.addView(share);
            rail.addView(follow);
            cell.addView(rail, new FrameLayout.LayoutParams(160, 480, Gravity.END | Gravity.TOP));
            View caption = new View(activity);
            caption.setId(FRAME_ID);
            cell.addView(caption, new FrameLayout.LayoutParams(200, 100, Gravity.START | Gravity.BOTTOM));
            View disc = new View(activity);
            disc.setId(COVER_ID);
            cell.addView(disc, new FrameLayout.LayoutParams(60, 60, Gravity.END | Gravity.BOTTOM));
            // The search bar under the caption, its row in a wrapper like the rail's buttons.
            FrameLayout search = new FrameLayout(activity);
            search.setId(SEARCH_ID);
            View searchRow = new View(activity);
            search.addView(searchRow);
            cell.addView(search, new FrameLayout.LayoutParams(400, 60, Gravity.START | Gravity.BOTTOM));
            root.addView(cell, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // The next video's cell, inflated below the screen.
            FrameLayout next = new FrameLayout(activity);
            next.setId(CELL_ID);
            next.setTranslationY(100_000f);
            FrameLayout nextRail = new FrameLayout(activity);
            nextRail.setId(COLUMN_ID);
            View nextLike = new View(activity);
            nextRail.addView(nextLike);
            next.addView(nextRail, new FrameLayout.LayoutParams(160, 480, Gravity.END | Gravity.TOP));
            root.addView(next, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(root);
            VideoOverlayHider.install(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Settings.FADE_CONTROLS_OPACITY.save(50);
            VideoOverlayHider.applyTo(activity);

            clear(true);
            // TikTok's own handling, all in the frame after the event.
            rail.setAlpha(0f);
            for (View wrapper : new View[]{like, share, nextLike}) {
                wrapper.setAlpha(0f);
                wrapper.setVisibility(View.GONE);
            }
            caption.setVisibility(View.GONE);
            disc.setVisibility(View.GONE);
            searchRow.setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            frameDrawn();
            assertEquals("the rail holds the chosen level", 0.5f, rail.getAlpha(), 0f);
            assertEquals("the search bar keeps its row", View.VISIBLE, searchRow.getVisibility());
            assertEquals(0.5f, search.getAlpha(), 0f);
            for (View wrapper : new View[]{like, share}) {
                assertEquals("each button stays in the rail", View.VISIBLE, wrapper.getVisibility());
                assertEquals("at full, so the rail's level is what shows", 1f, wrapper.getAlpha(), 0f);
            }
            assertEquals(View.VISIBLE, caption.getVisibility());
            assertEquals(0.5f, caption.getAlpha(), 0f);
            assertEquals(View.VISIBLE, disc.getVisibility());
            assertEquals("a button TikTok kept away on this post stays away", View.GONE, follow.getVisibility());
            assertEquals("the next video's buttons are TikTok's to set", View.GONE, nextLike.getVisibility());
            assertEquals(0f, nextLike.getAlpha(), 0f);

            // TikTok writing its own again is undone on the next frame.
            like.setVisibility(View.GONE);
            like.setAlpha(0f);
            frameDrawn();
            assertEquals(View.VISIBLE, like.getVisibility());
            assertEquals(1f, like.getAlpha(), 0f);

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            frameDrawn();
            assertEquals("Pause gives back TikTok's Clear display", View.GONE, like.getVisibility());
            assertEquals(0f, like.getAlpha(), 0f);
            assertEquals(View.GONE, caption.getVisibility());
            assertEquals(View.GONE, disc.getVisibility());
            assertEquals(View.GONE, searchRow.getVisibility());

            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, like.getVisibility());
            assertEquals(1f, like.getAlpha(), 0f);
            assertEquals(View.VISIBLE, caption.getVisibility());

            clear(false);
            // Leaving, TikTok shows them at 0 and animates them up after a pause.
            for (View wrapper : new View[]{like, share}) {
                wrapper.setVisibility(View.VISIBLE);
                wrapper.setAlpha(0f);
            }
            VideoOverlayHider.applyTo(activity);
            frameDrawn();
            assertEquals("no blink while TikTok's animation runs", 1f, like.getAlpha(), 0f);
            like.setAlpha(0.3f);
            frameDrawn();
            assertEquals(1f, like.getAlpha(), 0f);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                    java.time.Duration.ofMillis(ClearDisplayShownControls.EXIT_HOLD_MS + 1));
            frameDrawn();
            assertFalse(ClearDisplayShownControls.anyKept());
            like.setAlpha(0.4f);
            frameDrawn();
            assertEquals("after the hold the opacity is TikTok's own", 0.4f, like.getAlpha(), 0f);
        }
    }

    /**
     * A pinch that starts on the video and puts its second finger on a faded control stays the
     * video's, which is how TikTok's pinch out of Clear display sees both fingers.
     */
    @Test
    public void aSecondFingerOnAFadedControlStaysWithThePinch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            List<String> videoEvents = new ArrayList<>();
            List<String> columnEvents = new ArrayList<>();
            video.setOnTouchListener((v, event) -> {
                videoEvents.add(event.getActionMasked() + "/" + event.getPointerCount());
                return true;
            });
            column.setOnTouchListener((v, event) -> {
                columnEvents.add(event.getActionMasked() + "/" + event.getPointerCount());
                return true;
            });
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            VideoOverlayHider.applyTo(activity);

            float[] first = at(video, 0.3f, 0.3f);
            float[] second = at(column, 0.5f, 0.5f);
            long now = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, first[0], first[1], 0));
            send(twoFingers(now, MotionEvent.ACTION_POINTER_DOWN
                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second));
            send(twoFingers(now, MotionEvent.ACTION_POINTER_UP
                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second));
            send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, first[0], first[1], 0));

            assertTrue("the faded control saw neither finger: " + columnEvents, columnEvents.isEmpty());
            assertEquals(List.of(MotionEvent.ACTION_DOWN + "/1", MotionEvent.ACTION_POINTER_DOWN + "/2",
                    MotionEvent.ACTION_POINTER_UP + "/2", MotionEvent.ACTION_UP + "/1"), videoEvents);
        }
    }

    /** At 0 Clear display takes the controls away as before, and nothing invisible takes a tap. */
    @Test
    public void atZeroTheControlsAreHiddenAndTheTapsStillGoToTheVideo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            float[] onColumn = at(column, 0.5f, 0.5f);
            Settings.FADE_CONTROLS_OPACITY.save(0);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, column.getVisibility());
            assertEquals(View.INVISIBLE, frame.getVisibility());
            assertFalse(TapThroughControls.isMarked(column));

            long now = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, onColumn[0], onColumn[1], 0));
            send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, onColumn[0], onColumn[1], 0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, columnClicks);
            assertEquals(1, videoClicks);
        }
    }

    /** Pause answers TikTok's own Clear display: its opacity and its handling of a tap. */
    @Test
    public void pauseGivesTikTokItsClearDisplayAndItsTapsBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            column.setAlpha(0f);
            frameDrawn();
            assertEquals(0.5f, column.getAlpha(), 0f);

            PausedProcess.set(true);
            // Live, before any layout: the tap is TikTok's again straight away.
            tap(column);
            assertEquals(1, columnClicks);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause gives back the opacity TikTok's Clear display gave it",
                    0f, column.getAlpha(), 0f);
            assertFalse(TapThroughControls.isMarked(column));

            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);
            tap(column);
            assertEquals(1, columnClicks);
            assertEquals(1, videoClicks);
        }
    }
}
