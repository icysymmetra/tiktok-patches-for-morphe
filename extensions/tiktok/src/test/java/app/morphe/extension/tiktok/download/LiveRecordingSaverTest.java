package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import android.os.Looper;
import static org.robolectric.Shadows.shadowOf;

import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

/** #37: what a LIVE recording is called and what the viewer is told when it ends. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveRecordingSaverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private TimeZone savedZone;

    @Before public void zone() {
        savedZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ShadowToast.reset();
    }

    @After public void restoreZone() {
        TimeZone.setDefault(savedZone);
    }

    private static long start() {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2026, java.util.Calendar.OCTOBER, 13, 21, 45, 2);
        return calendar.getTimeInMillis();
    }

    @Test public void theFileIsNamedAfterTheCreatorAndTheStartTime() {
        assertEquals("live_someone_20261013_214502.mp4", LiveRecordingSaver.fileName("someone", start()));
    }

    @Test public void aHandleIsCleanedForAFileName() {
        assertEquals("live_a.bc-d_20261013_214502.mp4", LiveRecordingSaver.fileName("a.b/c-d é!", start()));
        assertEquals("live_20261013_214502.mp4", LiveRecordingSaver.fileName(null, start()));
        assertEquals("live_20261013_214502.mp4", LiveRecordingSaver.fileName("???", start()));
        String name = LiveRecordingSaver.fileName("x".repeat(100), start());
        assertEquals("live_" + "x".repeat(40) + "_20261013_214502.mp4", name);
    }

    @Test public void eachWayARecordingStopsShortIsSaidInItsOwnWords() {
        Set<String> sentences = new HashSet<>();
        for (LiveRecorder.End end : new LiveRecorder.End[]{LiveRecorder.End.DISCONNECTED, LiveRecorder.End.STORAGE,
                LiveRecorder.End.SIZE_LIMIT, LiveRecorder.End.APP_EXIT, LiveRecorder.End.ERROR}) {
            String text = LiveRecordingSaver.partialText(end, "DCIM/TikTok");
            assertTrue(end + " names where it went", text.contains("DCIM/TikTok"));
            assertFalse(end + " keeps its placeholder out of the text", text.contains("%1$s"));
            sentences.add(text);
        }
        assertEquals(5, sentences.size());
    }

    @Test public void nothingSavedSaysWhyWhenItKnows() {
        assertNotEquals(LiveRecordingSaver.nothingMessage(LiveRecorder.End.UNSUPPORTED),
                LiveRecordingSaver.nothingMessage(LiveRecorder.End.DISCONNECTED));
        assertNotEquals(LiveRecordingSaver.nothingMessage(LiveRecorder.End.STORAGE),
                LiveRecordingSaver.nothingMessage(LiveRecorder.End.DISCONNECTED));
    }

    private static LiveRecorder.Result result(LiveRecorder.Outcome outcome, LiveRecorder.End end, Object saved) {
        return new LiveRecorder.Result(outcome, end, 61_000, saved == null ? null : "live.mp4", saved, null);
    }

    @Test public void aFailedOrEmptyRecordingNeverReadsAsSaved() {
        LiveRecordingSaver.announce(result(LiveRecorder.Outcome.FAILED, LiveRecorder.End.STOPPED, null));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("The recording couldn't be saved. Try again.", ShadowToast.getTextOfLatestToast());
        ShadowToast.reset();
        LiveRecordingSaver.announce(result(LiveRecorder.Outcome.NOTHING_SAVED, LiveRecorder.End.DISCONNECTED, null));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Nothing was recorded", ShadowToast.getTextOfLatestToast());
        assertFalse(ShadowToast.getTextOfLatestToast().contains("saved to"));
    }

    @Test public void aSaveWithNothingToOpenIsToastedWithItsFolder() {
        LiveRecordingSaver.announce(result(LiveRecorder.Outcome.SAVED, LiveRecorder.End.STOPPED, "handle"));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("LIVE recording saved to "));
        ShadowToast.reset();
        LiveRecordingSaver.announce(result(LiveRecorder.Outcome.SAVED_PARTIAL, LiveRecorder.End.STORAGE, "handle"));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("The phone is running out of room."));
    }
}
