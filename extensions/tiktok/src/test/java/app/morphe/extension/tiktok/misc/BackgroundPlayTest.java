package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.media.AudioManager;
import android.os.Looper;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * What TikTok's background play reads while Keep playing in the background is on and off, and
 * when Replay in the background presses Play on a video that ended (#99).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BackgroundPlayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void offLeavesTikTokItsOwnAnswers() {
        Settings.BACKGROUND_PLAY.save(false);
        for (int served : new int[]{0, 1, 2, 7, -1}) assertEquals(served, BackgroundPlay.mode(served));
        assertFalse(BackgroundPlay.remembered(false));
        assertTrue(BackgroundPlay.remembered(true));
        assertFalse(BackgroundPlay.scene(false, BackgroundPlay.OWN_PROFILE));
        assertTrue(BackgroundPlay.scene(true, "homepage_hot"));
        assertTrue(BackgroundPlay.photoMode(true));
        assertFalse(BackgroundPlay.photoMode(false));
        assertFalse(BackgroundPlay.skipsPageFocus());
    }

    @Test public void onlyAScreenShowingCountsAsShowing() {
        assertFalse(BackgroundPlay.hidden(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND));
        assertTrue(BackgroundPlay.hidden(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE));
        assertTrue(BackgroundPlay.hidden(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE));
        assertTrue(BackgroundPlay.hidden(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED));
    }

    @Test public void onLetsYourProfileAndPhotoPostsThrough() {
        try {
            Settings.BACKGROUND_PLAY.save(true);
            assertTrue(BackgroundPlay.scene(false, BackgroundPlay.OWN_PROFILE));
            assertTrue(BackgroundPlay.scene(true, "others_homepage"));
            // Only your own profile joins TikTok's list; any other page keeps TikTok's answer.
            assertFalse(BackgroundPlay.scene(false, "chat"));
            assertFalse(BackgroundPlay.scene(false, null));
            assertFalse(BackgroundPlay.photoMode(true));
            assertFalse(BackgroundPlay.photoMode(false));
        } finally {
            Settings.BACKGROUND_PLAY.save(false);
        }
    }

    @Test public void onKeepsItOnForGood() {
        try {
            Settings.BACKGROUND_PLAY.save(true);
            // 2 is the value the menu leaves on for good, whatever the server moved the account to.
            for (int served : new int[]{0, 1, 2}) assertEquals(2, BackgroundPlay.mode(served));
            assertTrue(BackgroundPlay.remembered(false));
            assertTrue(BackgroundPlay.remembered(true));
        } finally {
            Settings.BACKGROUND_PLAY.save(false);
        }
    }

    @Test public void theLabSaysWhenTheSwitchDecidesTheKey() {
        boolean was = SettingsStatus.backgroundPlayEnabled;
        try {
            Settings.BACKGROUND_PLAY.save(true);
            // Not patched: a stored true from an older bundle decides nothing.
            SettingsStatus.backgroundPlayEnabled = false;
            assertFalse(BackgroundPlay.decidesGate(BackgroundPlay.GATE_KEY));

            SettingsStatus.backgroundPlayEnabled = true;
            assertTrue(BackgroundPlay.decidesGate(BackgroundPlay.GATE_KEY));
            assertFalse(BackgroundPlay.decidesGate("background_play_enable_v2"));
            assertFalse(BackgroundPlay.decidesGate(null));

            Settings.BACKGROUND_PLAY.save(false);
            assertFalse(BackgroundPlay.decidesGate(BackgroundPlay.GATE_KEY));
        } finally {
            Settings.BACKGROUND_PLAY.save(false);
            SettingsStatus.backgroundPlayEnabled = was;
        }
    }

    @Test public void theSwitchIsOnThePlaybackPageOnlyWhenPatched() {
        boolean was = SettingsStatus.backgroundPlayEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.backgroundPlayEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.BACKGROUND_PLAY.key));
            assertNull(without.findPreference(Settings.BACKGROUND_REPLAY.key));

            SettingsStatus.backgroundPlayEnabled = true;
            assertTrue(PlaybackPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.BACKGROUND_PLAY.key));
            assertNotNull(with.findPreference(Settings.BACKGROUND_REPLAY.key));
        } finally {
            SettingsStatus.backgroundPlayEnabled = was;
        }
    }

    private static final int AWAY = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
    private static final int SHOWING = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

    /** The phone and TikTok as the replay sees them. */
    private static final class FakeHost implements BackgroundPlay.Host {
        int importance = AWAY;
        int audioMode = AudioManager.MODE_NORMAL;
        final List<Object> pressed = new ArrayList<>();

        @Override public int importance() { return importance; }
        @Override public int audioMode() { return audioMode; }
        @Override public void pressPlay(Object listener) { pressed.add(listener); }
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void waitOutTheGap() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackgroundPlay.REPLAY_GAP_MS));
    }

    @Test public void replayNeedsBothSwitchesAndPauseTurnsItOff() {
        try {
            assertFalse("starts off", BackgroundPlay.replays());
            Settings.BACKGROUND_REPLAY.save(true);
            assertFalse("Keep playing in the background is off", BackgroundPlay.replays());
            Settings.BACKGROUND_PLAY.save(true);
            assertTrue(BackgroundPlay.replays());
            Settings.BACKGROUND_REPLAY.save(false);
            assertFalse(BackgroundPlay.replays());

            Settings.BACKGROUND_REPLAY.save(true);
            PausedProcess.set(true);
            try {
                assertFalse("Pause gives TikTok's own behavior", BackgroundPlay.replays());
            } finally {
                PausedProcess.set(false);
            }
        } finally {
            Settings.BACKGROUND_PLAY.save(false);
            Settings.BACKGROUND_REPLAY.save(false);
        }
    }

    @Test public void replaysOnlyAwayFromTikTokOutsideACallAndNotTwiceInARow() {
        long gap = BackgroundPlay.REPLAY_GAP_MS;
        int normal = AudioManager.MODE_NORMAL;
        try {
            Settings.BACKGROUND_PLAY.save(true);
            Settings.BACKGROUND_REPLAY.save(true);
            assertTrue(BackgroundPlay.shouldReplay(true, AWAY, normal, 10_000, 10_000 - gap));
            assertTrue(BackgroundPlay.shouldReplay(true, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED, normal, 10_000, 0));
            assertFalse("a session that moves on by itself",
                    BackgroundPlay.shouldReplay(false, AWAY, normal, 10_000, 0));
            assertFalse("back in TikTok", BackgroundPlay.shouldReplay(true, SHOWING, normal, 10_000, 0));
            for (int call : new int[]{AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL,
                    AudioManager.MODE_IN_COMMUNICATION}) {
                assertFalse("audio mode " + call, BackgroundPlay.shouldReplay(true, AWAY, call, 10_000, 0));
            }
            assertFalse("ended again right after a replay",
                    BackgroundPlay.shouldReplay(true, AWAY, normal, 10_000, 10_000 - gap + 1));

            Settings.BACKGROUND_REPLAY.save(false);
            assertFalse(BackgroundPlay.shouldReplay(true, AWAY, normal, 10_000, 0));
        } finally {
            Settings.BACKGROUND_PLAY.save(false);
            Settings.BACKGROUND_REPLAY.save(false);
        }
        assertFalse(BackgroundPlay.inCall(AudioManager.MODE_NORMAL));
        assertFalse(BackgroundPlay.inCall(AudioManager.MODE_CURRENT));
        assertFalse(BackgroundPlay.inCall(AudioManager.MODE_INVALID));
    }

    @Test public void anEndInTheBackgroundPressesPlayOnceTikTokHasPaused() {
        BackgroundPlay.Host was = BackgroundPlay.host;
        FakeHost fake = new FakeHost();
        Object listener = new Object();
        try {
            BackgroundPlay.host = fake;
            BackgroundPlay.lastReplay = -BackgroundPlay.REPLAY_GAP_MS;
            Settings.BACKGROUND_PLAY.save(true);
            Settings.BACKGROUND_REPLAY.save(true);

            BackgroundPlay.onBackgroundEnd(true, listener);
            assertTrue("pressed before TikTok finished with the end", fake.pressed.isEmpty());
            idle();
            assertEquals(Collections.singletonList(listener), fake.pressed);

            // A video that ends again straight away stays paused, and starts over after the gap.
            BackgroundPlay.onBackgroundEnd(true, listener);
            idle();
            assertEquals(1, fake.pressed.size());
            waitOutTheGap();
            BackgroundPlay.onBackgroundEnd(true, listener);
            idle();
            assertEquals(2, fake.pressed.size());
            waitOutTheGap();

            // Back in TikTok by the time it runs: TikTok picks the video up itself.
            fake.importance = SHOWING;
            BackgroundPlay.onBackgroundEnd(true, listener);
            idle();
            fake.importance = AWAY;
            // A call is ringing or on.
            fake.audioMode = AudioManager.MODE_IN_CALL;
            BackgroundPlay.onBackgroundEnd(true, listener);
            idle();
            fake.audioMode = AudioManager.MODE_NORMAL;
            // A session that can move on to another video, and nothing to press.
            BackgroundPlay.onBackgroundEnd(false, listener);
            BackgroundPlay.onBackgroundEnd(true, null);
            idle();
            // Turned off before the end, or in the moment before the press.
            Settings.BACKGROUND_REPLAY.save(false);
            BackgroundPlay.onBackgroundEnd(true, listener);
            idle();
            Settings.BACKGROUND_REPLAY.save(true);
            BackgroundPlay.onBackgroundEnd(true, listener);
            Settings.BACKGROUND_REPLAY.save(false);
            idle();
            // Paused.
            Settings.BACKGROUND_REPLAY.save(true);
            PausedProcess.set(true);
            try {
                BackgroundPlay.onBackgroundEnd(true, listener);
                idle();
            } finally {
                PausedProcess.set(false);
            }
            assertEquals(2, fake.pressed.size());

            // And with nothing in the way it still goes.
            BackgroundPlay.onBackgroundEnd(true, listener);
            idle();
            assertEquals(3, fake.pressed.size());
        } finally {
            BackgroundPlay.host = was;
            BackgroundPlay.lastReplay = -BackgroundPlay.REPLAY_GAP_MS;
            Settings.BACKGROUND_PLAY.save(false);
            Settings.BACKGROUND_REPLAY.save(false);
        }
    }
}
