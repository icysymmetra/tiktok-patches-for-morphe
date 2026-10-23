package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The two settings the music disc's turn hangs on, answered from the two switches (#68).
 *
 * <p>47.1.4 ships {@code music_animation_close_exp} at 3, the disc and the track name both held,
 * and the rotation duration at 0, which on the S22 left the disc still everywhere with every
 * Hushfeed switch off. These pin that Spin clears the disc's flag only, Keep still sets it and
 * zeroes a duration that would let it turn anyway, the track name's flag is never touched, and
 * nothing changes with both switches off.
 *
 * <p>Answered past the close setting the S22's disc still stood, so Spin also lifts the holds
 * further on (anim_opt, the high frame rate hold, the play animation's load downgrade and
 * musicCoverDegrade), and only Spin does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class MusicDiscSpinTest {
    private static final String CLOSE = "music_animation_close_exp";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        reset();
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
    }

    @After public void tearDown() {
        reset();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    private static void reset() {
        Settings.SPIN_MUSIC_DISC.resetToDefault();
        Settings.STOP_MUSIC_DISC_SPIN.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    @Test public void bothSwitchesOffLeaveEveryValueAsTikTokSentIt() {
        for (int value = -1; value <= 5; value++) {
            assertEquals(value, MusicDiscSpin.closeSetting(value));
            assertEquals(value, MusicDiscSpin.rotationSeconds(value));
        }
        assertFalse(LogBufferManager.buildExportText().contains("Music disc:"));
    }

    @Test public void spinClearsTheDiscsFlagAndKeepsTheTrackNamesAndTheReportSaysSo() {
        MusicDiscSpin.installed();
        Settings.SPIN_MUSIC_DISC.save(true);

        // 47.1.4's own default: both held. The track name stays held.
        assertEquals(2, MusicDiscSpin.closeSetting(3));
        assertEquals(0, MusicDiscSpin.closeSetting(1));
        // Already turning: nothing to answer.
        assertEquals(0, MusicDiscSpin.closeSetting(0));
        assertEquals(2, MusicDiscSpin.closeSetting(2));
        // A value the app doesn't test for holds nothing, so it passes as it came.
        assertEquals(4, MusicDiscSpin.closeSetting(4));
        // The duration is the turn's length once the disc may turn; Spin leaves it be.
        assertEquals(12, MusicDiscSpin.rotationSeconds(12));
        assertEquals(0, MusicDiscSpin.rotationSeconds(0));

        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains("music disc spin: 2 found, 0 missing"));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Music disc: " + CLOSE + " came back 3, answered 2"));
        assertFalse(report, report.contains("came back 0"));
    }

    @Test public void keepStillSetsTheDiscsFlagAndZeroesADurationThatWouldTurnItAnyway() {
        MusicDiscSpin.installed();
        Settings.STOP_MUSIC_DISC_SPIN.save(true);

        assertEquals(1, MusicDiscSpin.closeSetting(0));
        assertEquals(3, MusicDiscSpin.closeSetting(2));
        assertEquals(3, MusicDiscSpin.closeSetting(3));
        assertEquals(1, MusicDiscSpin.closeSetting(1));
        assertEquals(0, MusicDiscSpin.rotationSeconds(8));
        assertEquals(0, MusicDiscSpin.rotationSeconds(0));
        assertEquals(-1, MusicDiscSpin.rotationSeconds(-1));

        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains("music disc spin: 3 found, 0 missing"));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Music disc: " + CLOSE + " came back 0, answered 1"));
        assertTrue(report, report.contains(
                "Music disc: video_music_cover_visual_opt_rotation_duration came back 8, answered 0"));
    }

    @Test public void keepStillOverridesSpinWhenBothAreOn() {
        Settings.SPIN_MUSIC_DISC.save(true);
        Settings.STOP_MUSIC_DISC_SPIN.save(true);

        assertEquals(3, MusicDiscSpin.closeSetting(3));
        assertEquals(1, MusicDiscSpin.closeSetting(0));
        assertEquals(0, MusicDiscSpin.rotationSeconds(8));
        // And Spin's row says so by being unavailable while Keep still is on.
        assertFalse(Settings.SPIN_MUSIC_DISC.isAvailable());
        Settings.STOP_MUSIC_DISC_SPIN.save(false);
        assertTrue(Settings.SPIN_MUSIC_DISC.isAvailable());
    }

    @Test public void bothSwitchesOffLeaveTheLaterHoldsAsTikTokSentThem() {
        for (boolean value : new boolean[] {false, true}) {
            assertEquals(value, MusicDiscSpin.coverAnimationHeld(value));
            assertEquals(value, MusicDiscSpin.highFrameRateHeld(value));
            assertEquals(value, MusicDiscSpin.playAnimationSwitchedOff(value));
            assertEquals(value, MusicDiscSpin.degradeAllows(value));
        }
        assertFalse(LogBufferManager.buildExportText().contains("Music disc:"));
        assertFalse(HookStatus.report().toString().contains(MusicDiscSpin.FAMILY));
    }

    @Test public void spinLiftsEveryHoldPastTheCloseSettingAndLogsEachOncePerClear() {
        MusicDiscSpin.installed();
        Settings.SPIN_MUSIC_DISC.save(true);

        // Each hold is read once a video. The answer holds every time, the line is written once.
        for (int video = 0; video < 3; video++) {
            assertFalse(MusicDiscSpin.coverAnimationHeld(true));
            assertFalse(MusicDiscSpin.highFrameRateHeld(true));
            assertFalse(MusicDiscSpin.playAnimationSwitchedOff(true));
            assertTrue(MusicDiscSpin.degradeAllows(false));
        }
        // A hold TikTok didn't set has nothing to answer.
        assertFalse(MusicDiscSpin.coverAnimationHeld(false));
        assertFalse(MusicDiscSpin.highFrameRateHeld(false));
        assertFalse(MusicDiscSpin.playAnimationSwitchedOff(false));
        assertTrue(MusicDiscSpin.degradeAllows(true));

        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains("music disc spin: 5 found, 0 missing"));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, ANIM_OPT_LINE));
        assertEquals(report, 1, occurrences(report, FRAME_RATE_LINE));
        assertEquals(report, 1, occurrences(report, LOAD_LINE));
        assertEquals(report, 1, occurrences(report, DEGRADE_LINE));

        // Clearing the diagnostics starts over, so a fresh export names the holds again.
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        assertFalse(MusicDiscSpin.highFrameRateHeld(true));
        report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, FRAME_RATE_LINE));
        assertEquals(report, 0, occurrences(report, ANIM_OPT_LINE));
    }

    @Test public void keepStillLeavesTheLaterHoldsAloneEvenWithSpinOn() {
        Settings.SPIN_MUSIC_DISC.save(true);
        Settings.STOP_MUSIC_DISC_SPIN.save(true);

        assertTrue(MusicDiscSpin.coverAnimationHeld(true));
        assertTrue(MusicDiscSpin.highFrameRateHeld(true));
        assertTrue(MusicDiscSpin.playAnimationSwitchedOff(true));
        assertFalse(MusicDiscSpin.degradeAllows(false));
        assertFalse(MusicDiscSpin.coverAnimationHeld(false));
        assertTrue(MusicDiscSpin.degradeAllows(true));
        assertFalse(LogBufferManager.buildExportText().contains("Music disc:"));
    }

    private static final String ANIM_OPT_LINE = "Music disc: anim_opt came back true, answered false";
    private static final String FRAME_RATE_LINE =
            "Music disc: the high frame rate hold came back true, answered false";
    private static final String LOAD_LINE = "Music disc: feed_play_anim_downgrade came back true, answered false";
    private static final String DEGRADE_LINE = "Music disc: musicCoverDegrade came back false, answered true";

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
