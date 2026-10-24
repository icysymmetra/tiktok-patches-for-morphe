package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** #37: the LIVE recording TikTok was closed on is saved at the next start, and nothing is left behind. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveSpoolRecoveryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static final long START = 1_760_480_102_000L;

    /** Records what it was asked to save, and fails the way it's told to. */
    private static final class Saves implements LiveSpoolRecovery.Publisher {
        final List<String> calls = new ArrayList<>();
        IOException failure;

        @Override
        public LiveRecorder.Published spool(File spool, boolean flv, String creator, long startedAtMs) throws IOException {
            calls.add((flv ? "flv " : "ts ") + creator + " " + startedAtMs);
            if (failure != null) throw failure;
            return new LiveRecorder.Published(LiveRecordingSaver.fileName(creator, startedAtMs), "row");
        }

        @Override
        public LiveRecorder.Published mp4(File mp4, String creator, long startedAtMs) {
            calls.add("mp4 " + creator + " " + startedAtMs);
            return new LiveRecorder.Published(LiveRecordingSaver.fileName(creator, startedAtMs), "row");
        }
    }

    @Before public void toasts() {
        ShadowToast.reset();
    }

    private File file(File directory, String name, int bytes) throws IOException {
        File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[bytes]);
        }
        return file;
    }

    @Test public void aSpoolsNameCarriesTheCreatorAndTheStart() {
        String prefix = LiveSpoolRecovery.spoolPrefix("some.one_1", START);
        assertEquals("live-" + START + "-some.one_1-", prefix);
        assertEquals(prefix, LiveSpoolRecovery.prefixOf(new File(prefix + "8812345.flv")));
        assertEquals("a handle loses what a file name can't hold", "live-" + START + "-abc-",
                LiveSpoolRecovery.spoolPrefix("a/b-c é", START));
        assertEquals("live-" + START + "--", LiveSpoolRecovery.spoolPrefix(null, START));
        assertEquals("an older spool has nothing to carry", "live-", LiveSpoolRecovery.prefixOf(new File("live-123.flv")));
    }

    @Test public void theSpoolTikTokClosedOnIsSavedUnderItsCreatorAndStart() throws IOException {
        File directory = folder.newFolder();
        File flv = file(directory, LiveSpoolRecovery.spoolPrefix("someone", START) + "41.flv", 8192);
        File ts = file(directory, LiveSpoolRecovery.spoolPrefix("other", START + 1) + "-42.ts", 8192);
        Saves saves = new Saves();

        List<LiveRecorder.Result> results = LiveSpoolRecovery.recover(directory, saves);

        assertEquals(List.of("flv someone " + START, "ts other " + (START + 1)), saves.calls);
        assertEquals(2, results.size());
        for (LiveRecorder.Result result : results) {
            assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.outcome);
            assertEquals("said as a recording TikTok closed on", LiveRecorder.End.APP_EXIT, result.end);
        }
        assertFalse(flv.exists());
        assertFalse(ts.exists());
    }

    @Test public void aCutOffRewriteIsDoneAgainFromItsSpoolAndAWholeOneIsSaved() throws IOException {
        File directory = folder.newFolder();
        String cut = LiveSpoolRecovery.spoolPrefix("cut", START);
        file(directory, cut + "1.flv", 8192);
        File halfMp4 = file(directory, cut + "2.mp4", 100);
        File wholeMp4 = file(directory, LiveSpoolRecovery.spoolPrefix("whole", START) + "3.mp4", 100);
        Saves saves = new Saves();

        LiveSpoolRecovery.recover(directory, saves);

        assertEquals(List.of("flv cut " + START, "mp4 whole " + START), saves.calls);
        assertFalse(halfMp4.exists());
        assertFalse(wholeMp4.exists());
    }

    @Test public void nothingIsLeftBehindAndOnlyAFailureIsSaid() throws IOException {
        File directory = folder.newFolder();
        File tiny = file(directory, LiveSpoolRecovery.spoolPrefix("tiny", START) + "1.flv", 100);
        File empty = file(directory, LiveSpoolRecovery.spoolPrefix("empty", START) + "2.flv", 8192);
        File other = file(directory, "story-77.mp4", 8192);
        Saves saves = new Saves();
        saves.failure = new LiveRemux.NothingRecorded("No video was recorded");

        assertTrue(LiveSpoolRecovery.recover(directory, saves).isEmpty());
        assertEquals("a spool under the useful size isn't opened", List.of("flv empty " + START), saves.calls);
        assertFalse(tiny.exists());
        assertFalse(empty.exists());
        assertTrue("another save's file is left alone", other.exists());

        File broken = file(directory, LiveSpoolRecovery.spoolPrefix("broken", START) + "3.flv", 8192);
        saves.failure = new IOException("no room");
        List<LiveRecorder.Result> results = LiveSpoolRecovery.recover(directory, saves);
        assertEquals(1, results.size());
        assertEquals(LiveRecorder.Outcome.FAILED, results.get(0).outcome);
        assertFalse(broken.exists());
        LiveSpoolRecovery.announce(results.get(0));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("The LIVE recording TikTok closed on couldn't be saved. Its unfinished file was cleared.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void anOlderSpoolIsSavedUnderTheTimeItWasLastWritten() throws IOException {
        File directory = folder.newFolder();
        File old = file(directory, "live--8812345.flv", 8192);
        assertTrue(old.setLastModified(START));
        Saves saves = new Saves();

        LiveSpoolRecovery.recover(directory, saves);

        assertEquals(List.of("flv  " + START), saves.calls);
    }

    @Test public void aRecordingThisProcessIsMakingIsLeftAlone() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File live = MediaCache.createTempFile(context, LiveSpoolRecovery.spoolPrefix("now", START), ".flv");
        try (FileOutputStream out = new FileOutputStream(live)) {
            out.write(new byte[8192]);
        }
        Saves saves = new Saves();
        try {
            assertTrue(LiveSpoolRecovery.recover(MediaCache.cacheDirectory(context), saves).isEmpty());
            assertTrue(saves.calls.isEmpty());
            assertTrue(live.exists());
        } finally {
            MediaCache.delete(live);
        }
    }
}
