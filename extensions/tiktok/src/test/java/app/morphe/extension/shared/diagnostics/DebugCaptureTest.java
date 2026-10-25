package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.text.format.DateFormat;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsBackup;
import app.morphe.extension.tiktok.settings.preference.TimedDiagnosticsPreference;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.util.Date;

/**
 * A timed capture logs like Log diagnostics for fifteen minutes and then stops, whichever way
 * the time passes, and never moves the switch itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DebugCaptureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final class FakeClock implements DebugCapture.Clock {
        long wall = 1_800_000_000_000L;
        long elapsed = 50_000_000L;
        int boot = 7;

        @Override public long wall() { return wall; }
        @Override public long elapsed() { return elapsed; }
        @Override public int boot() { return boot; }

        void pass(long millis) {
            wall += millis;
            elapsed += millis;
        }
    }

    private final FakeClock clock = new FakeClock();

    @Before public void setUp() {
        DebugCapture.resetForTests();
        DebugCapture.setClockForTests(clock);
        BaseSettings.DEBUG.resetToDefault();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        DebugCapture.resetForTests();
        BaseSettings.DEBUG.resetToDefault();
    }

    /** What a TikTok that died and started again knows: the saved record, nothing in memory. */
    private static void forgetAsIfTikTokRestarted() throws Exception {
        Field endsAt = DebugCapture.class.getDeclaredField("endsAt");
        endsAt.setAccessible(true);
        endsAt.setLong(null, 0L);
    }

    @Test public void aCaptureLogsForItsTimeWithoutSavingTheSwitch() {
        assertFalse(BaseSettings.DEBUG.get());
        long end = DebugCapture.start();

        assertEquals(clock.wall + DebugCapture.DURATION_MS, end);
        assertTrue("debug lines are logged while it runs", BaseSettings.DEBUG.get());
        assertFalse("the switch keeps what the reader set", BaseSettings.DEBUG.savedValue());
        assertFalse(Setting.preferences.preferences.contains(BaseSettings.DEBUG.key));
        assertEquals(end, DebugCapture.endsAtWallClock());

        clock.pass(DebugCapture.DURATION_MS - 1);
        assertTrue(BaseSettings.DEBUG.get());
        clock.pass(1);
        assertFalse("the capture stops on time with nobody tapping anything", BaseSettings.DEBUG.get());
        assertEquals(0, DebugCapture.endsAtWallClock());
    }

    @Test public void stoppingEndsItAtOnce() {
        DebugCapture.start();
        clock.pass(60_000L);
        DebugCapture.stop();

        assertFalse(DebugCapture.isRunning());
        assertFalse(BaseSettings.DEBUG.get());
        assertEquals("", BaseSettings.DEBUG_CAPTURE.savedValue());
    }

    @Test public void aRestartedTikTokKeepsTheSameEnd() throws Exception {
        long end = DebugCapture.start();
        clock.pass(10 * 60_000L);
        forgetAsIfTikTokRestarted();
        assertFalse(DebugCapture.isRunning());

        Utils.setContext(RuntimeEnvironment.getApplication());

        assertTrue("the saved capture is picked back up at start", BaseSettings.DEBUG.get());
        assertEquals(end, DebugCapture.endsAtWallClock());
        clock.pass(5 * 60_000L);
        assertFalse("a restart doesn't give it another fifteen minutes", BaseSettings.DEBUG.get());
    }

    @Test public void aCaptureThatRanOutWhileTikTokWasClosedIsClearedAtStart() throws Exception {
        DebugCapture.start();
        forgetAsIfTikTokRestarted();
        clock.pass(DebugCapture.DURATION_MS + 1);

        DebugCapture.load();

        assertFalse(BaseSettings.DEBUG.get());
        assertEquals("", BaseSettings.DEBUG_CAPTURE.savedValue());
    }

    @Test public void settingThePhonesClockBackCantStretchIt() throws Exception {
        DebugCapture.start();
        clock.wall -= 60 * 60_000L;
        clock.elapsed += DebugCapture.DURATION_MS;
        assertFalse(DebugCapture.isRunning());

        // Nor after a restart, where only the saved record speaks.
        clock.elapsed -= 60_000L;
        forgetAsIfTikTokRestarted();
        DebugCapture.load();
        assertTrue(DebugCapture.isRunning());
        assertEquals(clock.wall + 60_000L, DebugCapture.endsAtWallClock());
    }

    @Test public void eitherClockEndsIt() {
        String record = DebugCapture.record(1_000_000L, 5_000L, 3, DebugCapture.DURATION_MS);
        long minute = 60_000L;

        assertEquals(DebugCapture.DURATION_MS - minute,
                DebugCapture.remaining(record, 1_000_000L + minute, 5_000L + minute, 3));
        assertEquals("the wall clock moved forward", 0,
                DebugCapture.remaining(record, 1_000_000L + DebugCapture.DURATION_MS, 5_000L + minute, 3));
        assertEquals("the wall clock moved back", DebugCapture.DURATION_MS - minute,
                DebugCapture.remaining(record, 1_000_000L - 60 * minute, 5_000L + minute, 3));
        assertEquals(0, DebugCapture.remaining(record, 1_000_000L + minute, 5_000L + DebugCapture.DURATION_MS, 3));
    }

    @Test public void aRestartedPhoneEndsIt() {
        String record = DebugCapture.record(1_000_000L, 5_000_000L, 3, DebugCapture.DURATION_MS);

        assertEquals("another boot count", 0, DebugCapture.remaining(record, 1_000_100L, 5_000_100L, 4));
        assertEquals("before 7.0, the elapsed clock went back", 0,
                DebugCapture.remaining(record, 1_000_100L, 4_000L, -1));
        assertEquals("a boot count Android doesn't give leaves the clocks to decide",
                DebugCapture.DURATION_MS - 100L, DebugCapture.remaining(record, 1_000_100L, 5_000_100L, -1));
        assertEquals("a network time nudge before 7.0 isn't a restart",
                DebugCapture.DURATION_MS - 2_100L, DebugCapture.remaining(record, 1_002_100L, 5_000_100L, -1));
    }

    @Test public void beforeSevenARestartEndsItEvenWhenTheNewBootHasRunLonger() {
        long minute = 60_000L;
        // Started a minute after boot, the phone restarted two minutes later, and TikTok opened
        // three minutes into the new boot: the elapsed clock reads past where it started.
        String record = DebugCapture.record(1_000_000L, minute, -1, DebugCapture.DURATION_MS);

        assertEquals(0, DebugCapture.remaining(record, 1_000_000L + 5 * minute, 3 * minute, -1));
        assertEquals("the same boot keeps running", DebugCapture.DURATION_MS - 2 * minute,
                DebugCapture.remaining(record, 1_000_000L + 2 * minute, 3 * minute, -1));
    }

    @Test public void aRecordAskingForLongerOrUnreadableIsOver() {
        assertEquals(0, DebugCapture.remaining(
                DebugCapture.record(1_000L, 1_000L, 1, DebugCapture.DURATION_MS + 1), 1_001L, 1_001L, 1));
        assertEquals(0, DebugCapture.remaining(DebugCapture.record(1_000L, 1_000L, 1, 0), 1_001L, 1_001L, 1));
        assertEquals(0, DebugCapture.remaining("1;x;1000;1;900000", 1_001L, 1_001L, 1));
        assertEquals(0, DebugCapture.remaining("2;1000;1000;1;900000", 1_001L, 1_001L, 1));
        assertEquals(0, DebugCapture.remaining("", 1_001L, 1_001L, 1));
    }

    @Test public void loggingTheReaderLeftOnSurvivesEveryWayACaptureEnds() {
        BaseSettings.DEBUG.save(true);

        DebugCapture.start();
        DebugCapture.stop();
        assertTrue("stopped", BaseSettings.DEBUG.get());

        DebugCapture.start();
        clock.pass(DebugCapture.DURATION_MS);
        assertFalse(DebugCapture.isRunning());
        assertTrue("ran out", BaseSettings.DEBUG.get());
        assertTrue(BaseSettings.DEBUG.savedValue());
    }

    @Test public void aBackupNeitherCarriesNorRestartsACapture() throws Exception {
        DebugCapture.start();
        JSONObject backup = new JSONObject(SettingsBackup.create(false));
        JSONObject settings = backup.getJSONObject("settings");

        assertFalse(settings.has(BaseSettings.DEBUG_CAPTURE.key));
        assertFalse("the backup carries the switch as the reader set it",
                settings.getBoolean(BaseSettings.DEBUG.key));

        // A file edited to carry a running capture restores everything else and not that.
        DebugCapture.stop();
        String record = DebugCapture.record(clock.wall, clock.elapsed, clock.boot, DebugCapture.DURATION_MS);
        settings.put(BaseSettings.DEBUG_CAPTURE.key, record);
        backup.getJSONArray("setting_keys").put(BaseSettings.DEBUG_CAPTURE.key);
        SettingsBackup.restore(Utils.getContext(), backup.toString(), false);
        Utils.setContext(RuntimeEnvironment.getApplication());

        assertEquals("", BaseSettings.DEBUG_CAPTURE.savedValue());
        assertFalse(DebugCapture.isRunning());
        assertFalse(BaseSettings.DEBUG.get());
    }

    @Test public void aCaptureLogsWhileHushfeedIsPaused() {
        PausedProcess.set(true);
        DebugCapture.start();

        assertTrue(BaseSettings.DEBUG.get());
    }

    @Test public void theRowStartsShowsTheEndAndStops() {
        TimedDiagnosticsPreference row = new TimedDiagnosticsPreference(RuntimeEnvironment.getApplication());
        assertTrue(row.getSummary().toString().startsWith("Logs for 15 minutes, then stops on its own."));

        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        String end = DateFormat.getTimeFormat(RuntimeEnvironment.getApplication())
                .format(new Date(clock.wall + DebugCapture.DURATION_MS));
        assertTrue(DebugCapture.isRunning());
        assertEquals("Logging diagnostics until " + end, ShadowToast.getTextOfLatestToast());
        assertEquals("Logging until " + end + ". Tap to stop now.", row.getSummary().toString());
        assertFalse("the switch above doesn't move", BaseSettings.DEBUG.savedValue());

        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(DebugCapture.isRunning());
        assertEquals("Timed logging stopped", ShadowToast.getTextOfLatestToast());
        assertTrue(row.getSummary().toString().startsWith("Logs for 15 minutes"));
    }
}
