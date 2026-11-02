package app.morphe.extension.tiktok.speed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * Changing the speed of the video on screen from a gesture: which player is the one on screen, the
 * grid a speed lands on, and that the change belongs to that video alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveSpeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for TikTok's player: what each controller has, and what it was told to play at. */
    private static final class Player implements PlaybackSpeedPatch.NativePlayer {
        final Map<Object, Aweme> videos = new HashMap<>();
        final List<Object> controllers = new ArrayList<>();
        final List<Float> speeds = new ArrayList<>();
        boolean refuses;

        @Override
        public Aweme aweme(Object controller) {
            return videos.get(controller);
        }

        @Override
        public void setSpeed(Object controller, float speed) {
            if (refuses) throw new IllegalStateException("the player said no");
            controllers.add(controller);
            speeds.add(speed);
        }
    }

    private Player player;

    private static Aweme video(String id) {
        return new Aweme() {
            @Override
            public String getAid() {
                return id;
            }
        };
    }

    @Before
    public void setUp() {
        Settings.DEFAULT_SPEED_ENABLED.save(true);
        Settings.DEFAULT_SPEED.save("1.5");
        Settings.REMEMBER_SPEED.save(true);
        Settings.REMEMBERED_SPEED.save(1f);
        PlaybackSpeedPatch.beginVideo(null);
        forgetPlayer();
        player = new Player();
        PlaybackSpeedPatch.nativeForTests = player;
    }

    @After
    public void tearDown() {
        PlaybackSpeedPatch.nativeForTests = null;
        forgetPlayer();
        PlaybackSpeedPatch.beginVideo(null);
        Settings.DEFAULT_SPEED_ENABLED.resetToDefault();
        Settings.DEFAULT_SPEED.resetToDefault();
        Settings.REMEMBER_SPEED.resetToDefault();
        Settings.REMEMBERED_SPEED.resetToDefault();
    }

    /** The on-screen player and the last drag are process-wide state, so a test starts and ends without them. */
    private static void forgetPlayer() {
        ReflectionHelpers.setStaticField(PlaybackSpeedPatch.class, "onScreen", null);
        ReflectionHelpers.setStaticField(PlaybackSpeedPatch.class, "liveVideoId", "");
        ReflectionHelpers.setStaticField(PlaybackSpeedPatch.class, "liveSpeed", Float.NaN);
    }

    /** A player that has reported video {@code id} playing, as the progress hook says it. */
    private Object playing(String id) {
        Object controller = new Object();
        player.videos.put(controller, video(id));
        PlaybackSpeedPatch.onPlayerProgress(controller, id);
        return controller;
    }

    @Test
    public void speedsLandOnQuarterStepsFromHalfThroughThree() {
        assertEquals(0.5f, PlaybackSpeedPatch.snapLiveSpeed(0.1f), 0f);
        assertEquals(0.5f, PlaybackSpeedPatch.snapLiveSpeed(0.62f), 0f);
        assertEquals(0.75f, PlaybackSpeedPatch.snapLiveSpeed(0.63f), 0f);
        assertEquals(1f, PlaybackSpeedPatch.snapLiveSpeed(1.1f), 0f);
        assertEquals(1.25f, PlaybackSpeedPatch.snapLiveSpeed(1.2f), 0f);
        assertEquals(2.5f, PlaybackSpeedPatch.snapLiveSpeed(2.6f), 0f);
        assertEquals(3f, PlaybackSpeedPatch.snapLiveSpeed(3.4f), 0f);
        assertEquals(3f, PlaybackSpeedPatch.snapLiveSpeed(10f), 0f);
        assertTrue(Float.isNaN(PlaybackSpeedPatch.snapLiveSpeed(Float.NaN)));
    }

    @Test
    public void everyStepOnTheGridIsAFixedPoint() {
        for (float speed = PlaybackSpeedPatch.MIN_SPEED; speed <= PlaybackSpeedPatch.MAX_SPEED; speed += 0.25f) {
            assertEquals(speed, PlaybackSpeedPatch.snapLiveSpeed(speed), 0f);
        }
    }

    @Test
    public void withNoPlayerReportedNothingIsChanged() {
        assertTrue(Float.isNaN(PlaybackSpeedPatch.liveStartSpeed()));
        assertFalse(PlaybackSpeedPatch.setLiveSpeed(2f));
        assertTrue(player.speeds.isEmpty());
    }

    @Test
    public void reportsWithoutAPlayerOrAVideoIdNameNobody() {
        PlaybackSpeedPatch.onPlayerProgress(null, "a");
        PlaybackSpeedPatch.onPlayerProgress(new Object(), null);
        PlaybackSpeedPatch.onPlayerProgress(new Object(), "");
        assertTrue(Float.isNaN(PlaybackSpeedPatch.liveStartSpeed()));
        assertFalse(PlaybackSpeedPatch.setLiveSpeed(2f));
        assertTrue(player.speeds.isEmpty());
    }

    @Test
    public void theVideoOnScreenGetsTheSpeedAndItsStartIsTheDefaultUntilChanged() {
        Object controller = playing("a");

        assertEquals("starts from the default the first frame gave it", 1.5f, PlaybackSpeedPatch.liveStartSpeed(), 0f);
        assertTrue(PlaybackSpeedPatch.setLiveSpeed(2.6f));

        assertEquals(List.of(2.5f), player.speeds);
        assertEquals(List.of(controller), player.controllers);
        assertEquals("the next drag starts where this one stopped", 2.5f, PlaybackSpeedPatch.liveStartSpeed(), 0f);
        assertEquals("a repeated first frame of the same video keeps it",
                2.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("a")), 0f);
    }

    @Test
    public void theNextVideoStartsAtTheSavedDefaultAndNothingIsSaved() {
        playing("a");
        Settings.DEFAULT_SPEED_ENABLED.save(false);

        assertTrue(PlaybackSpeedPatch.setLiveSpeed(2.5f));

        assertEquals("the remembered speed is left alone", 1f, Settings.REMEMBERED_SPEED.get(), 0f);
        assertEquals("so is the default", "1.5", Settings.DEFAULT_SPEED.get());
        assertEquals("the same video drawing its first frame again keeps the speed it was given",
                2.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("a")), 0f);
        assertEquals("the next video starts at the remembered speed",
                1f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("b")), 0f);
        Settings.DEFAULT_SPEED_ENABLED.save(true);
        assertEquals("and at the default when that is on",
                1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("c")), 0f);
    }

    @Test
    public void aNeighboursEarlyFirstFrameLeavesTheDragOnTheVideoOnScreen() {
        playing("a");
        assertTrue(PlaybackSpeedPatch.setLiveSpeed(2.5f));

        // The next video's player was preloaded and drew its first frame while "a" still plays.
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("b")), 0f);

        assertEquals("a second drag starts where the first stopped", 2.5f, PlaybackSpeedPatch.liveStartSpeed(), 0f);
        assertEquals("and the dragged video keeps its speed", 2.5f,
                PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("a")), 0f);
    }

    @Test
    public void theSwipeToTheNextVideoLeavesTheDraggedSpeedBehind() {
        playing("a");
        Settings.DEFAULT_SPEED_ENABLED.save(false);
        assertTrue(PlaybackSpeedPatch.setLiveSpeed(2.5f));

        // The transition reset begins the next video, then asks what TikTok's own state carries.
        PlaybackSpeedPatch.beginVideo(video("b"));
        assertEquals("the drag was the last video's alone", 1f, PlaybackSpeedPatch.preserveTransitionSpeed(2.5f), 0f);
        assertEquals("another speed TikTok carries is kept as before",
                1.75f, PlaybackSpeedPatch.preserveTransitionSpeed(1.75f), 0f);

        PlaybackSpeedPatch.beginVideo(video("a"));
        assertEquals("back on the dragged video it stands", 2.5f, PlaybackSpeedPatch.preserveTransitionSpeed(2.5f), 0f);
    }

    @Test
    public void aChoiceFromTikToksOwnMenuTakesOverFromALiveOne() {
        playing("a");
        Settings.DEFAULT_SPEED_ENABLED.save(false);
        assertTrue(PlaybackSpeedPatch.setLiveSpeed(2.5f));

        PlaybackSpeedPatch.onSelection(1.75f, video("a"), "feed", "long_press");

        assertEquals("what the menu saved is what the first frame gets",
                1.75f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("a")), 0f);
    }

    @Test
    public void aPlayerNowOnAnotherVideoIsNotTheOneOnScreen() {
        Object controller = playing("a");
        // A recycled cell: the player that reported "a" has moved on to "b" without reporting it.
        player.videos.put(controller, video("b"));

        assertTrue(Float.isNaN(PlaybackSpeedPatch.liveStartSpeed()));
        assertFalse(PlaybackSpeedPatch.setLiveSpeed(2f));
        assertTrue(player.speeds.isEmpty());
        assertEquals("the speed the video would get is untouched",
                1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("b")), 0f);
    }

    @Test
    public void aPlayerWithNoVideoTakesNothing() {
        Object controller = playing("a");
        player.videos.put(controller, null);

        assertTrue(Float.isNaN(PlaybackSpeedPatch.liveStartSpeed()));
        assertFalse(PlaybackSpeedPatch.setLiveSpeed(2f));
        assertTrue(player.speeds.isEmpty());
    }

    @Test
    public void theLatestReportIsTheVideoOnScreen() {
        Object first = playing("a");
        Object second = playing("b");

        assertTrue(PlaybackSpeedPatch.setLiveSpeed(2f));

        assertEquals(List.of(second), player.controllers);
        assertEquals(1, player.controllers.size());
        assertTrue(first != second);
    }

    @Test
    public void aSpeedThatIsNotANumberIsRefused() {
        playing("a");
        assertFalse(PlaybackSpeedPatch.setLiveSpeed(Float.NaN));
        assertTrue(player.speeds.isEmpty());
    }

    @Test
    public void aPlayerThatRefusesLeavesNothingRecordedForTheVideo() {
        playing("a");
        player.refuses = true;

        assertFalse(PlaybackSpeedPatch.setLiveSpeed(2.5f));

        assertEquals(1.5f, PlaybackSpeedPatch.liveStartSpeed(), 0f);
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("a")), 0f);
    }
}
