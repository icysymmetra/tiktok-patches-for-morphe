package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.speed.PlaybackSpeedPatch;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * Which drags Swipe for brightness, volume and speed takes, what each strip changes, the level math,
 * and giving brightness back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EdgeSwipeLevelsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private boolean previousOverlays, previousPaused, previousSpeedPatch;
    private Activity activity;
    private int width, height;

    @Before
    public void setUp() {
        previousOverlays = SettingsStatus.videoOverlaysEnabled;
        previousSpeedPatch = SettingsStatus.liveSpeedEnabled;
        previousPaused = Setting.isPaused();
        setPaused(false);
        SettingsStatus.videoOverlaysEnabled = true;
        Settings.SWIPE_LEVELS.save(true);
        Settings.SWIPE_LEVELS_STRIP_PERCENT.save(15);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        width = activity.getResources().getDisplayMetrics().widthPixels;
        height = activity.getResources().getDisplayMetrics().heightPixels;
    }

    @After
    public void tearDown() {
        SettingsStatus.videoOverlaysEnabled = previousOverlays;
        SettingsStatus.liveSpeedEnabled = previousSpeedPatch;
        setPaused(previousPaused);
        PlaybackSpeedPatch.nativeForTests = null;
        forgetSpeedState();
        Settings.SWIPE_LEVELS.resetToDefault();
        Settings.SWIPE_LEVELS_STRIP_PERCENT.resetToDefault();
        Settings.SWIPE_LEVELS_LEFT.resetToDefault();
        Settings.SWIPE_LEVELS_RIGHT.resetToDefault();
        Settings.DEFAULT_SPEED_ENABLED.resetToDefault();
        Settings.DEFAULT_SPEED.resetToDefault();
    }

    /** TikTok's player for one video on screen, as the speed patch sees it. */
    private static final class Player implements PlaybackSpeedPatch.NativePlayer {
        final Object controller = new Object();
        final Aweme video;
        final List<Float> speeds = new ArrayList<>();

        Player(String id) {
            video = new Aweme() {
                @Override
                public String getAid() {
                    return id;
                }
            };
        }

        @Override
        public Aweme aweme(Object asked) {
            return asked == controller ? video : null;
        }

        @Override
        public void setSpeed(Object asked, float speed) {
            if (asked == controller) speeds.add(speed);
        }
    }

    /** A bundle with the speed patch, and a player that has reported video {@code id} playing at 1x. */
    private Player speedPlayerOnScreen(String id) {
        SettingsStatus.liveSpeedEnabled = true;
        Settings.DEFAULT_SPEED_ENABLED.save(true);
        Settings.DEFAULT_SPEED.save("1");
        forgetSpeedState();
        Player player = new Player(id);
        PlaybackSpeedPatch.nativeForTests = player;
        PlaybackSpeedPatch.onPlayerProgress(player.controller, id);
        return player;
    }

    /**
     * The on-screen player and the last drag are process-wide, and a drag stays with its video id,
     * so every test that uses video "a" would start at the speed an earlier one dragged it to.
     */
    private static void forgetSpeedState() {
        ReflectionHelpers.setStaticField(PlaybackSpeedPatch.class, "onScreen", null);
        ReflectionHelpers.setStaticField(PlaybackSpeedPatch.class, "liveVideoId", "");
        ReflectionHelpers.setStaticField(PlaybackSpeedPatch.class, "liveSpeed", Float.NaN);
        PlaybackSpeedPatch.beginVideo(null);
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", from(boolean.class, value));
    }

    /** Records the actions that reach the views below, standing in for the activity. */
    private static final class Below {
        final List<Integer> actions = new ArrayList<>();
        final Window.Callback callback = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> {
                    if ("dispatchTouchEvent".equals(method.getName())) {
                        actions.add(((MotionEvent) args[0]).getActionMasked());
                        return true;
                    }
                    return false;
                });
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    private boolean send(EdgeSwipeLevels.Gesture gesture, Below below, int action, float x, float y) {
        MotionEvent e = event(action, x, y);
        try {
            return gesture.dispatch(e, below.callback);
        } finally {
            e.recycle();
        }
    }

    private float brightness() {
        return activity.getWindow().getAttributes().screenBrightness;
    }

    // ---- the level math --------------------------------------------------------------------

    @Test
    public void stripWidthIsAPercentOfTheWidthKeptInRange() {
        assertEquals(150, EdgeSwipeLevels.stripWidth(1000, 15));
        assertEquals(50, EdgeSwipeLevels.stripWidth(1000, 1));
        assertEquals(300, EdgeSwipeLevels.stripWidth(1000, 90));
    }

    @Test
    public void aPressIsPlacedAgainstTheStrips() {
        assertEquals(EdgeSwipeLevels.Side.LEFT, EdgeSwipeLevels.sideAt(0f, 1000, 15));
        assertEquals(EdgeSwipeLevels.Side.LEFT, EdgeSwipeLevels.sideAt(149f, 1000, 15));
        assertEquals(EdgeSwipeLevels.Side.NONE, EdgeSwipeLevels.sideAt(150f, 1000, 15));
        assertEquals(EdgeSwipeLevels.Side.NONE, EdgeSwipeLevels.sideAt(849f, 1000, 15));
        assertEquals(EdgeSwipeLevels.Side.RIGHT, EdgeSwipeLevels.sideAt(850f, 1000, 15));
        assertEquals(EdgeSwipeLevels.Side.NONE, EdgeSwipeLevels.sideAt(10f, 0, 15));
    }

    @Test
    public void draggingUpRaisesTheLevelAndAThreeQuarterHeightDragSpansTheRange() {
        assertEquals(0.5f, EdgeSwipeLevels.levelAfter(0.5f, 800f, 800f, 1000, 0f), 0.0001f);
        assertEquals(1f, EdgeSwipeLevels.levelAfter(0f, 800f, 50f, 1000, 0f), 0.0001f);
        assertEquals(0.5f, EdgeSwipeLevels.levelAfter(0f, 800f, 425f, 1000, 0f), 0.0001f);
        assertEquals(0f, EdgeSwipeLevels.levelAfter(1f, 100f, 900f, 1000, 0f), 0.0001f);
        assertEquals(EdgeSwipeLevels.MIN_BRIGHTNESS,
                EdgeSwipeLevels.levelAfter(0.5f, 100f, 900f, 1000, EdgeSwipeLevels.MIN_BRIGHTNESS), 0.0001f);
    }

    @Test
    public void volumeStepsRoundAndStayInsideTheStream() {
        assertEquals(0, EdgeSwipeLevels.volumeStep(0f, 15));
        assertEquals(8, EdgeSwipeLevels.volumeStep(0.5f, 15));
        assertEquals(15, EdgeSwipeLevels.volumeStep(1.4f, 15));
        assertEquals(0, EdgeSwipeLevels.volumeStep(-1f, 15));
    }

    @Test
    public void verticalAndHorizontalMovesAreTold() {
        assertTrue(EdgeSwipeLevels.isVerticalDrag(2f, -30f, 8));
        assertFalse(EdgeSwipeLevels.isVerticalDrag(0f, -5f, 8));
        assertFalse(EdgeSwipeLevels.isVerticalDrag(20f, -30f, 8));
        assertTrue(EdgeSwipeLevels.isHorizontalMove(30f, 4f, 8));
        assertFalse(EdgeSwipeLevels.isHorizontalMove(3f, 40f, 8));
    }

    // ---- which drags are taken -------------------------------------------------------------

    @Test
    public void aVerticalDragInTheLeftStripIsTakenAndChangesBrightness() {
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = 5f;
        float y = height * 0.8f;

        assertTrue(send(gesture, below, MotionEvent.ACTION_DOWN, x, y));
        // The first move over the slop is not handed down: the views get a cancel instead.
        assertTrue(send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 40f));
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), below.actions);
        assertTrue(gesture.taken());

        // The drag has begun but no level was written yet; the window still follows the system.
        float afterFirst = brightness();
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, afterFirst, 0f);
        assertTrue(send(gesture, below, MotionEvent.ACTION_MOVE, x, y - height * 0.3f));
        assertTrue("dragging up raises it", brightness() > afterFirst);
        assertTrue(send(gesture, below, MotionEvent.ACTION_UP, x, y - height * 0.3f));
        assertEquals("the views below saw nothing more", 2, below.actions.size());
        assertFalse(gesture.taken());
        assertTrue(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    @Test
    public void aVerticalDragInTheRightStripChangesTheMusicVolume() {
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = width - 5f;
        float y = height * 0.9f;

        send(gesture, below, MotionEvent.ACTION_DOWN, x, y);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - height * 0.5f);
        send(gesture, below, MotionEvent.ACTION_UP, x, y - height * 0.5f);

        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0);
        assertFalse("volume leaves the window's brightness alone", EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0f);
    }

    @Test
    public void aDragFromTheMiddlePassesThroughUntouched() {
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = width / 2f;
        float y = height * 0.8f;

        send(gesture, below, MotionEvent.ACTION_DOWN, x, y);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 300f);
        send(gesture, below, MotionEvent.ACTION_UP, x, y - 300f);

        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP), below.actions);
        assertFalse(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    @Test
    public void aTapInAStripPassesThrough() {
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();

        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, 200f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, 203f);
        send(gesture, below, MotionEvent.ACTION_UP, 5f, 203f);

        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
                below.actions);
        assertFalse(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    @Test
    public void aHorizontalMoveInAStripPassesThroughAndALaterVerticalOneStaysPassed() {
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();

        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, 400f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 60f, 402f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 62f, 100f);
        send(gesture, below, MotionEvent.ACTION_UP, 62f, 100f);

        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP), below.actions);
        assertFalse(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    @Test
    public void aSecondFingerHandsTheGestureBack() {
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();

        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, 400f);
        send(gesture, below, MotionEvent.ACTION_POINTER_DOWN, 200f, 400f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, 100f);

        assertFalse(gesture.taken());
        assertEquals(3, below.actions.size());
        assertFalse(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    @Test
    public void theStripWidthSettingMovesTheEdge() {
        Settings.SWIPE_LEVELS_STRIP_PERCENT.save(5);
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = width * 0.10f;

        // 10 percent in is outside a 5 percent strip, so the drag is the feed's.
        send(gesture, below, MotionEvent.ACTION_DOWN, x, height * 0.8f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, height * 0.8f - 60f);
        assertFalse(gesture.taken());

        Settings.SWIPE_LEVELS_STRIP_PERCENT.save(30);
        send(gesture, below, MotionEvent.ACTION_UP, x, height * 0.8f - 60f);
        send(gesture, below, MotionEvent.ACTION_DOWN, x, height * 0.8f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, height * 0.8f - 60f);
        assertTrue(gesture.taken());
    }

    // ---- what each strip changes -----------------------------------------------------------

    @Test
    public void eachStripKeepsItsOwnDefaultUntilTheRowSaysOtherwise() {
        assertEquals(EdgeSwipeLevels.Mode.BRIGHTNESS, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.LEFT));
        assertEquals(EdgeSwipeLevels.Mode.VOLUME, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.RIGHT));
    }

    @Test
    public void theRowsCanSwapTheStrips() {
        Settings.SWIPE_LEVELS_LEFT.save("volume");
        Settings.SWIPE_LEVELS_RIGHT.save("brightness");
        assertEquals(EdgeSwipeLevels.Mode.VOLUME, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.LEFT));
        assertEquals(EdgeSwipeLevels.Mode.BRIGHTNESS, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.RIGHT));
    }

    @Test
    public void speedIsOfferedOnlyWithThePlaybackSpeedPatch() {
        Settings.SWIPE_LEVELS_LEFT.save("speed");
        Settings.SWIPE_LEVELS_RIGHT.save("speed");

        SettingsStatus.liveSpeedEnabled = true;
        assertEquals(EdgeSwipeLevels.Mode.SPEED, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.LEFT));
        assertEquals(EdgeSwipeLevels.Mode.SPEED, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.RIGHT));

        SettingsStatus.liveSpeedEnabled = false;
        assertEquals("a value saved by a bundle that had the patch", EdgeSwipeLevels.Mode.BRIGHTNESS,
                EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.LEFT));
        assertEquals(EdgeSwipeLevels.Mode.VOLUME, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.RIGHT));
    }

    @Test
    public void aValueNoRowWritesFallsBackToTheStripsDefault() {
        Settings.SWIPE_LEVELS_LEFT.save("sideways");
        Settings.SWIPE_LEVELS_RIGHT.save("Speed ");
        assertEquals(EdgeSwipeLevels.Mode.BRIGHTNESS, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.LEFT));
        assertEquals(EdgeSwipeLevels.Mode.VOLUME, EdgeSwipeLevels.modeFor(EdgeSwipeLevels.Side.RIGHT));
        assertEquals(EdgeSwipeLevels.Mode.VOLUME, EdgeSwipeLevels.parseMode(null, EdgeSwipeLevels.Mode.VOLUME));
        assertEquals(EdgeSwipeLevels.Mode.SPEED, EdgeSwipeLevels.parseMode("speed", EdgeSwipeLevels.Mode.VOLUME));
    }

    @Test
    public void speedAndItsLevelMapOntoEachOtherOverTheWholeGrid() {
        assertEquals(0f, EdgeSwipeLevels.levelForSpeed(0.5f), 0.0001f);
        assertEquals(1f, EdgeSwipeLevels.levelForSpeed(3f), 0.0001f);
        assertEquals(0.5f, EdgeSwipeLevels.levelForSpeed(1.75f), 0.0001f);
        assertEquals("below the slowest", 0f, EdgeSwipeLevels.levelForSpeed(0.1f), 0f);
        assertEquals("above the fastest", 1f, EdgeSwipeLevels.levelForSpeed(9f), 0f);
        assertEquals(0.5f, EdgeSwipeLevels.speedForLevel(0f), 0f);
        assertEquals(3f, EdgeSwipeLevels.speedForLevel(1f), 0f);
        assertEquals(1.75f, EdgeSwipeLevels.speedForLevel(0.5f), 0f);
        for (float speed = 0.5f; speed <= 3f; speed += 0.25f) {
            assertEquals(speed, EdgeSwipeLevels.speedForLevel(EdgeSwipeLevels.levelForSpeed(speed)), 0f);
        }
    }

    @Test
    public void theLevelLineReadsAsAPercentOrAsASpeed() {
        assertEquals("Volume 40%", EdgeSwipeLevels.levelText("Volume", 0.4f));
        assertEquals("Speed 1.5x", EdgeSwipeLevels.speedText("Speed", 1.5f));
        assertEquals("Speed 3x", EdgeSwipeLevels.speedText("Speed", 3f));
        assertEquals("Speed 0.75x", EdgeSwipeLevels.speedText("Speed", 0.75f));
    }

    @Test
    public void swappedStripsChangeWhatTheirNewRowsSay() {
        Settings.SWIPE_LEVELS_LEFT.save("volume");
        Settings.SWIPE_LEVELS_RIGHT.save("brightness");
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);

        drag(5f, height * 0.9f, height * 0.5f);
        assertTrue("the left strip raised the volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0);
        assertFalse(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));

        drag(width - 5f, height * 0.8f, height * 0.3f);
        assertTrue("the right strip took the brightness", EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    // ---- speed ------------------------------------------------------------------------------

    @Test
    public void aDragInAStripSetToSpeedChangesTheVideosSpeedInQuarterSteps() {
        Settings.SWIPE_LEVELS_LEFT.save("speed");
        Player player = speedPlayerOnScreen("a");
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = 5f;
        float y = height * 0.8f;

        send(gesture, below, MotionEvent.ACTION_DOWN, x, y);
        assertTrue(send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 40f));
        assertTrue(gesture.taken());
        assertEquals("the views below got a cancel, like the other levels",
                List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), below.actions);
        assertTrue("the drag has begun but chose nothing yet", player.speeds.isEmpty());

        // Two small moves stay on the step the video is at: the player hears of it once.
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 42f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 43f);
        assertEquals(List.of(1f), player.speeds);

        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 40f - height * 0.2f);
        assertEquals("dragging up went faster, one step at a time", List.of(1f, 1.75f), player.speeds);

        TextView level = activity.getWindow().getDecorView().findViewWithTag(EdgeSwipeLevels.TAG);
        assertEquals("Speed 1.75x", level.getText().toString());

        send(gesture, below, MotionEvent.ACTION_UP, x, y - 40f - height * 0.2f);
        assertFalse(gesture.taken());
        assertEquals("the views below saw nothing more", 2, below.actions.size());
        assertFalse("speed leaves the window's brightness alone", EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0f);
    }

    @Test
    public void draggingDownSlowsTheVideoAndStopsAtHalfSpeed() {
        Settings.SWIPE_LEVELS_RIGHT.save("speed");
        Player player = speedPlayerOnScreen("a");
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = width - 5f;
        float y = height * 0.2f;

        send(gesture, below, MotionEvent.ACTION_DOWN, x, y);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y + 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y + 40f + height * 0.7f);
        send(gesture, below, MotionEvent.ACTION_UP, x, y + 40f + height * 0.7f);

        assertEquals(0.5f, player.speeds.get(player.speeds.size() - 1), 0f);
        for (float speed : player.speeds) assertTrue(speed >= 0.5f && speed <= 3f);
    }

    @Test
    public void aSecondDragStartsFromTheSpeedTheFirstLeft() {
        Settings.SWIPE_LEVELS_LEFT.save("speed");
        Player player = speedPlayerOnScreen("a");
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();

        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, height * 0.9f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, height * 0.9f - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, height * 0.9f - 40f - height * 0.2f);
        send(gesture, below, MotionEvent.ACTION_UP, 5f, height * 0.9f - 40f - height * 0.2f);
        float afterFirst = player.speeds.get(player.speeds.size() - 1);
        assertTrue(afterFirst > 1f);

        player.speeds.clear();
        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, height * 0.9f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, height * 0.9f - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, height * 0.9f - 42f);
        assertEquals("it begins at the speed the video has now", List.of(afterFirst), player.speeds);
    }

    @Test
    public void withNoPlayerToGiveASpeedToTheFeedKeepsTheDrag() {
        Settings.SWIPE_LEVELS_LEFT.save("speed");
        SettingsStatus.liveSpeedEnabled = true;
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = 5f;
        float y = height * 0.8f;

        send(gesture, below, MotionEvent.ACTION_DOWN, x, y);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 300f);
        send(gesture, below, MotionEvent.ACTION_UP, x, y - 300f);

        assertFalse(gesture.taken());
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP), below.actions);
    }

    @Test
    public void aStripSavedAsSpeedWithoutThePatchStillDoesBrightness() {
        Settings.SWIPE_LEVELS_LEFT.save("speed");
        SettingsStatus.liveSpeedEnabled = false;

        dragBrightness();

        assertTrue(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    @Test
    public void aDragFromTheMiddleStillPagesTheFeedWithBothStripsOnSpeed() {
        Settings.SWIPE_LEVELS_LEFT.save("speed");
        Settings.SWIPE_LEVELS_RIGHT.save("speed");
        Player player = speedPlayerOnScreen("a");
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        float x = width / 2f;
        float y = height * 0.8f;

        send(gesture, below, MotionEvent.ACTION_DOWN, x, y);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, y - 300f);
        send(gesture, below, MotionEvent.ACTION_UP, x, y - 300f);

        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP), below.actions);
        assertTrue(player.speeds.isEmpty());
    }

    @Test
    public void aTapInASpeedStripPassesThrough() {
        Settings.SWIPE_LEVELS_LEFT.save("speed");
        Player player = speedPlayerOnScreen("a");
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();

        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, 200f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, 203f);
        send(gesture, below, MotionEvent.ACTION_UP, 5f, 203f);

        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
                below.actions);
        assertTrue(player.speeds.isEmpty());
    }

    // ---- the gate, the wrapper and giving brightness back ----------------------------------

    /** A whole drag in a strip, from {@code startY} up by {@code travel} pixels past the first move. */
    private void drag(float x, float startY, float travel) {
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        send(gesture, below, MotionEvent.ACTION_DOWN, x, startY);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, startY - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, x, startY - 40f - travel);
        send(gesture, below, MotionEvent.ACTION_UP, x, startY - 40f - travel);
    }

    @Test
    public void nothingIsTakenOrWrappedWhileTheSwitchIsOff() {
        Settings.SWIPE_LEVELS.save(false);
        Window.Callback before = activity.getWindow().getCallback();
        EdgeSwipeLevels.sync(activity);
        assertSame(before, activity.getWindow().getCallback());

        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, 600f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, 300f);
        assertFalse(gesture.taken());
        assertEquals(2, below.actions.size());
    }

    @Test
    public void aBundleWithoutTheHostPatchNeverTakesADrag() {
        SettingsStatus.videoOverlaysEnabled = false;
        Window.Callback before = activity.getWindow().getCallback();
        EdgeSwipeLevels.sync(activity);
        assertSame(before, activity.getWindow().getCallback());

        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, 600f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, 300f);
        assertFalse(gesture.taken());
    }

    @Test
    public void theSwitchOnWrapsTheWindowOnce() {
        Window.Callback before = activity.getWindow().getCallback();
        EdgeSwipeLevels.sync(activity);
        Window.Callback wrapped = activity.getWindow().getCallback();
        assertNotSame(before, wrapped);
        EdgeSwipeLevels.sync(activity);
        assertSame("a second pass leaves the one wrapper", wrapped, activity.getWindow().getCallback());
    }

    private void dragBrightness() {
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, height * 0.8f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, height * 0.8f - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, height * 0.8f - 120f);
        send(gesture, below, MotionEvent.ACTION_UP, 5f, height * 0.8f - 120f);
        assertTrue(brightness() >= 0f);
    }

    @Test
    public void brightnessGoesBackToTheSystemsWhenTheWindowPauses() {
        dragBrightness();
        EdgeSwipeLevels.onPaused(activity);
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0f);
        assertFalse(EdgeSwipeLevels.holdsBrightness(activity.getWindow()));
    }

    @Test
    public void brightnessGoesBackWhenTheSwitchGoesOff() {
        dragBrightness();
        Settings.SWIPE_LEVELS.save(false);
        EdgeSwipeLevels.sync(activity);
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0f);
    }

    @Test
    public void brightnessGoesBackWhileHushfeedIsPaused() {
        dragBrightness();
        setPaused(true);
        EdgeSwipeLevels.sync(activity);
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0f);

        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(activity);
        Below below = new Below();
        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, 600f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, 300f);
        assertFalse("paused Metra takes nothing", gesture.taken());
    }

    @Test
    public void aWindowThatNeverChangedBrightnessIsNotTouchedOnPause() {
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        params.screenBrightness = 0.7f;
        activity.getWindow().setAttributes(params);
        EdgeSwipeLevels.onPaused(activity);
        assertEquals("a brightness TikTok set stays", 0.7f, brightness(), 0.0001f);
    }

    // ---- the comment sheet -----------------------------------------------------------------

    /** Puts TikTok's comment sheet (47.1.4 ids) into a window that is really on screen. */
    private Activity activityWithCommentSheet(FrameLayout[] sheetOut) {
        Activity shown = Robolectric.buildActivity(Activity.class).setup().visible().get();
        FrameLayout content = shown.findViewById(android.R.id.content);
        FrameLayout sheet = new FrameLayout(shown);
        sheet.setId(0x7f0a1001);
        TextView title = new TextView(shown);
        title.setId(0x7f0a1002);
        sheet.addView(title, new FrameLayout.LayoutParams(200, 80));
        content.addView(sheet, new FrameLayout.LayoutParams(500, 700));
        FeedVisibility.resolveForTests(shown.getPackageName(), "47.4.3:pwm", sheet.getId());
        FeedVisibility.resolveForTests(shown.getPackageName(), "47.4.3:wla", title.getId());
        sheetOut[0] = sheet;
        return shown;
    }

    @Test
    public void anEdgeDragWhileTheCommentSheetIsUpPassesThroughAndChangesNothing() {
        FrameLayout[] sheet = new FrameLayout[1];
        Activity shown = activityWithCommentSheet(sheet);
        float y = shown.getResources().getDisplayMetrics().heightPixels * 0.8f;
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(shown);
        Below below = new Below();
        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, y);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, y - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, y - 200f);
        send(gesture, below, MotionEvent.ACTION_UP, 5f, y - 200f);
        assertFalse(gesture.taken());
        assertEquals("TikTok got every event, so the comments scroll", 4, below.actions.size());
        assertFalse(EdgeSwipeLevels.holdsBrightness(shown.getWindow()));
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                shown.getWindow().getAttributes().screenBrightness, 0f);
    }

    @Test
    public void withTheCommentSheetClosedEdgeSwipesStillWorkAndTheSheetIsOnlyAskedAtThePress() {
        FrameLayout[] sheet = new FrameLayout[1];
        Activity shown = activityWithCommentSheet(sheet);
        sheet[0].setVisibility(android.view.View.GONE);
        float y = shown.getResources().getDisplayMetrics().heightPixels * 0.8f;
        EdgeSwipeLevels.Gesture gesture = new EdgeSwipeLevels.Gesture(shown);
        Below below = new Below();
        send(gesture, below, MotionEvent.ACTION_DOWN, 5f, y);
        // The sheet opening mid-drag does not hand a taken gesture back halfway.
        sheet[0].setVisibility(android.view.View.VISIBLE);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, y - 40f);
        send(gesture, below, MotionEvent.ACTION_MOVE, 5f, y - 200f);
        assertTrue(gesture.taken());
        send(gesture, below, MotionEvent.ACTION_UP, 5f, y - 200f);
        assertTrue(EdgeSwipeLevels.holdsBrightness(shown.getWindow()));
    }
}
