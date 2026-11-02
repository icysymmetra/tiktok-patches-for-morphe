package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.playback.KeepPaused;
import app.morphe.extension.tiktok.playback.PausePlayback;
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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Keep a paused video paused: the state read as the app goes away, and TikTok's plays answered as
 * it comes back. Here beside the session hold's tests for its fake player.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class KeepPausedTest {
    private Activity activity;
    private SessionPlaybackHoldTest.NativeController player;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The switches this suite drives have their rows with Comment tools and Block author.
        SettingsStatus.commentToolsEnabled = true;
        SettingsStatus.blockAuthorEnabled = true;
        SessionPlaybackHoldTest.installNativeControls();
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        ReflectionHelpers.callStaticMethod(KeepPaused.class, "resetForTests");
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
        Settings.KEEP_PAUSED_ON_RETURN.resetToDefault();
        HookStatus.clear();
        activity = Robolectric.buildActivity(SessionPlaybackHoldTest.HostActivity.class).setup().get();
        player = new SessionPlaybackHoldTest.NativeController();
        player.bind("first");
        player.reportProgress();
    }

    @After public void tearDown() {
        SettingsStatus.commentToolsEnabled = false;
        SettingsStatus.blockAuthorEnabled = false;
        Settings.KEEP_PAUSED_ON_RETURN.resetToDefault();
        ReflectionHelpers.callStaticMethod(KeepPaused.class, "resetForTests");
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        SessionPlaybackHold.nativeForTests = null;
        HookStatus.clear();
    }

    private void leave() {
        ReflectionHelpers.callStaticMethod(KeepPaused.class, "onLeaving", ClassParameter.from(Activity.class, activity));
    }

    private static void comeBack() {
        ReflectionHelpers.callStaticMethod(KeepPaused.class, "onReturning");
    }

    private static boolean play(String aid) {
        return KeepPaused.refusePlay(new SessionPlaybackHoldTest.Clip(aid));
    }

    @Test public void aVideoTheReaderPausedIsNotPlayedAgainByTheReturn() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        player.manager.playing = false;
        leave();
        comeBack();
        assertTrue("the feed panel's resume", play("first"));
        assertTrue("and the new surface's", play("first"));
        assertFalse("another video still plays", play("second"));
    }

    @Test public void aVideoLeftPlayingStartsAgain() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        leave();
        comeBack();
        assertFalse(play("first"));
    }

    @Test public void theSwitchOffLeavesEveryPlayToTikTok() {
        player.manager.playing = false;
        leave();
        comeBack();
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        assertFalse("nothing was noted on the way out", play("first"));
        leave();
        comeBack();
        Settings.KEEP_PAUSED_ON_RETURN.save(false);
        assertFalse("turned off after the leaving", play("first"));
    }

    /** A screen of TikTok's own, a separate activity, as messages or a web page are. */
    public static final class OtherScreen extends Activity { }

    /**
     * TikTok's own stop as the feed's activity pauses: its player stops in a lifecycle observer,
     * which comes after the application's pre-pause callbacks. Registered after Hushfeed's.
     */
    private void stopThePlayerOnEveryPause() {
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(new EmptyCallbacks() {
            @Override public void onActivityPaused(Activity paused) {
                player.manager.playing = false;
            }
        });
    }

    /** From Android 10 the leaving is read before TikTok's stop, through the pre-pause callback. */
    @Test @Config(sdk = 30) public void theFeedGoingAwayIsReadBeforeTikToksStop() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
        try (var feed = Robolectric.buildActivity(SessionPlaybackHoldTest.HostActivity.class).setup()) {
            PausePlayback.install(feed.get());
            stopThePlayerOnEveryPause();
            feed.pause();
            feed.resume();
            assertFalse("a video left playing starts again", play("first"));

            player.manager.playing = false;
            feed.pause();
            feed.resume();
            assertTrue("one the reader paused is kept", play("first"));
        } finally {
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
        }
    }

    /** Messages, a web page or Hushfeed's settings cover the feed and pause on the way back. */
    @Test @Config(sdk = 30) public void anotherTikTokScreenOnTheWayBackIsNotTheFeed() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
        try (var feed = Robolectric.buildActivity(SessionPlaybackHoldTest.HostActivity.class).setup();
             var other = Robolectric.buildActivity(OtherScreen.class).create().start()) {
            PausePlayback.install(feed.get());
            stopThePlayerOnEveryPause();
            feed.pause();
            other.resume();
            other.pause();
            feed.resume();
            assertFalse("the video left playing starts again", play("first"));
        } finally {
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
        }
    }

    /**
     * Android 9 and older have no pre-pause callback, and TikTok's stop comes before anything the
     * application hears, so Keep paused stays out there and its row is greyed.
     */
    @Test public void onAndroid9KeepPausedStaysOut() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        assertFalse("the row is greyed", Settings.KEEP_PAUSED_ON_RETURN.isAvailable());
        ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
        try (var feed = Robolectric.buildActivity(SessionPlaybackHoldTest.HostActivity.class).setup()) {
            PausePlayback.install(feed.get());
            player.manager.playing = false;
            feed.pause();
            feed.resume();
            assertFalse("nothing is turned down", play("first"));
        } finally {
            ReflectionHelpers.callStaticMethod(PausePlayback.class, "resetForTests");
        }
    }

    @Test @Config(sdk = 29) public void fromAndroid10TheRowIsThere() {
        assertTrue(Settings.KEEP_PAUSED_ON_RETURN.isAvailable());
    }

    /** TikTok's feed buttons and a screen reader's play action call the same play method. */
    @Test public void aPlayTheReaderAsksForGoesThrough() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        player.manager.playing = false;
        leave();
        comeBack();
        boolean[] refused = {true};
        View button = new View(activity);
        button.setOnClickListener(v -> refused[0] = play("first"));
        button.performClick();
        assertFalse("a click on a play button", refused[0]);
        assertFalse("and the record is spent", play("first"));

        leave();
        comeBack();
        int action = 0x7f0a005f;
        refused[0] = true;
        View video = new View(activity);
        video.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public boolean performAccessibilityAction(View host, int id, Bundle arguments) {
                if (id != action) return super.performAccessibilityAction(host, id, arguments);
                refused[0] = play("first");
                return true;
            }
        });
        video.performAccessibilityAction(action, null);
        assertFalse("a screen reader's play action", refused[0]);

        leave();
        comeBack();
        assertTrue("TikTok's own play on the way back is still turned down", play("first"));
    }

    /** Only what the tests need of the callbacks, the rest left empty. */
    private static class EmptyCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    @Test public void onceThePausedVideoPlaysAgainItsPlaysGoThrough() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        player.manager.playing = false;
        leave();
        comeBack();
        assertTrue(play("first"));
        player.manager.playing = true;
        assertFalse("the reader started it", play("first"));
        player.manager.playing = false;
        assertFalse("and the record is spent", play("first"));
    }

    @Test public void onlyThePlaysThatComeWithTheReturnAreTurnedDown() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        player.manager.playing = false;
        leave();
        assertFalse("still away", play("first"));
        leave();
        comeBack();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3100));
        assertFalse("long after the return", play("first"));
        assertFalse("and the record is spent", play("first"));
    }

    /** The player has moved to another video since it last reported, so nobody knows the state. */
    @Test public void aPlayerThatCannotSayIsNotTakenForPaused() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        player.manager.playing = false;
        player.current = new SessionPlaybackHoldTest.Clip("other");
        leave();
        comeBack();
        assertFalse(play("first"));
    }

    @Test public void aVideoWithNoPlayerToAskIsLeftToTikTok() {
        Settings.KEEP_PAUSED_ON_RETURN.save(true);
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
        leave();
        comeBack();
        assertFalse(play("first"));
        assertFalse("nothing named", KeepPaused.refusePlay(null));
    }

    @Test public void theExportCountsThePlayAndTheVideoKeptPaused() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if ("keep paused".equals(family)) found[0] = count;
            return family;
        });
        try {
            Settings.KEEP_PAUSED_ON_RETURN.save(true);
            play("first");
            HookStatus.report();
            assertEquals("the play alone", 1, found[0]);
            player.manager.playing = false;
            leave();
            comeBack();
            play("first");
            HookStatus.report();
            assertEquals("and the video kept paused", 2, found[0]);
            View button = new View(activity);
            button.setOnClickListener(v -> play("first"));
            button.performClick();
            HookStatus.report();
            assertEquals("and the reader's own play", 3, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}
