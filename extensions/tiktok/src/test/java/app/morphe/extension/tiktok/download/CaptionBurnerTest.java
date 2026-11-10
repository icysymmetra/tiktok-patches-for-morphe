/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.os.Looper;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

/**
 * What goes on a saved video and what happens when it can't. Robolectric has no codecs or GL,
 * so the encode itself is swapped out here and checked on a phone; the words, their cut and
 * size, the overlay's corner and the fallback to the video as it came are all checked here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptionBurnerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    private static final byte[] VIDEO = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
    /** Ten pixels a character, so a cut can be worked out by hand. */
    private static final CaptionBurner.Measure TEN_EACH = text -> text.length() * 10f;

    private CaptionBurner.Burn realBurner;

    @Before public void keepTheRealBurner() {
        realBurner = CaptionBurner.burner;
        ShadowToast.reset();
    }

    @After public void restoreTheRealBurner() {
        CaptionBurner.burner = realBurner;
        Settings.DOWNLOAD_BURN_CAPTION.resetToDefault();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void theNameGoesFirstAndThenTheCaptionsFirstLine() {
        assertEquals(List.of("@alice", "First line"),
                CaptionBurner.lines("@alice", "First line\nsecond line #fyp", 1000f, TEN_EACH));
        assertEquals("blank lines before the caption are skipped", List.of("@alice", "Hello there"),
                CaptionBurner.lines("@alice", "\n\n  Hello there  \nmore", 1000f, TEN_EACH));
    }

    @Test public void anEmptyCaptionGivesTheNameAlone() {
        assertEquals(List.of("@alice"), CaptionBurner.lines("@alice", "", 1000f, TEN_EACH));
        assertEquals(List.of("@alice"), CaptionBurner.lines("@alice", null, 1000f, TEN_EACH));
        assertEquals(List.of("@alice"), CaptionBurner.lines("@alice", "  \n  ", 1000f, TEN_EACH));
        assertEquals("a post without a creator still gets its caption",
                List.of("Hi"), CaptionBurner.lines("", "Hi", 1000f, TEN_EACH));
        assertTrue(CaptionBurner.lines("", " ", 1000f, TEN_EACH).isEmpty());
    }

    @Test public void aLongLineIsCutWithAnEllipsisToFit() {
        List<String> lines = CaptionBurner.lines("@alice", "A very long caption here", 100f, TEN_EACH);
        assertEquals("@alice", lines.get(0));
        assertEquals("A very lo" + CaptionBurner.ELLIPSIS, lines.get(1));
        assertTrue(TEN_EACH.width(lines.get(1)) <= 100f);
        assertEquals("a cut doesn't leave a space before the ellipsis",
                "A very" + CaptionBurner.ELLIPSIS, CaptionBurner.fit("A very long", 80f, TEN_EACH));
        assertEquals("a line that fits is left whole", "Short", CaptionBurner.fit("Short", 50f, TEN_EACH));
    }

    @Test public void aCutNeverSplitsACharacterInHalf() {
        String waving = "ab😀cd";
        assertEquals("ab" + CaptionBurner.ELLIPSIS, CaptionBurner.fit(waving, 40f, TEN_EACH));
        assertEquals("ab😀" + CaptionBurner.ELLIPSIS, CaptionBurner.fit(waving, 50f, TEN_EACH));
    }

    @Test public void theTextAndItsMarginScaleWithTheVideosWidth() {
        assertEquals(45f, CaptionBurner.textSize(1080), 0.001f);
        assertEquals(CaptionBurner.textSize(1080) / 2, CaptionBurner.textSize(540), 0.001f);
        assertEquals(CaptionBurner.textSize(1080) * 2, CaptionBurner.textSize(2160), 0.001f);
        assertEquals(54, CaptionBurner.margin(1080));
        assertEquals(27, CaptionBurner.margin(540));
        assertEquals("a tiny video keeps text that can be read",
                CaptionBurner.MIN_TEXT_PX, CaptionBurner.textSize(120), 0.001f);
    }

    /** Upright, the words sit in the bottom left corner and nowhere else. */
    @Test public void theWordsSitInTheBottomLeftCorner() {
        Bitmap overlay = CaptionBurner.overlay(360, 640, 0, "@alice", "Hello there");
        try {
            assertTrue(inked(overlay, 0, 480, 180, 640));
            assertFalse("something was drawn in the top half", inked(overlay, 0, 0, 360, 320));
            assertFalse("something was drawn on the right", inked(overlay, 270, 0, 360, 640));
        } finally {
            overlay.recycle();
        }
    }

    /**
     * A portrait video stored on its side and turned 90 degrees by the player: the words have to
     * land where the player's bottom left corner comes from, the stored frame's bottom right.
     */
    @Test public void aTurnedVideoGetsItsWordsTurnedBackToMatch() {
        Bitmap overlay = CaptionBurner.overlay(640, 360, 90, "@alice", "Hello there");
        try {
            assertTrue(inked(overlay, 480, 180, 640, 360));
            assertFalse("the words were turned the wrong way", inked(overlay, 0, 0, 320, 360));
        } finally {
            overlay.recycle();
        }
    }

    @Test public void theBitRateFollowsTheSourceWithALittleOver() {
        assertEquals(5_000_000, CaptionBurner.bitRate(4_000_000, 0, 0, 1080, 1920, 30));
        assertEquals("from the file's size and length when the track doesn't say",
                5_242_880, CaptionBurner.bitRate(0, 10L * 1024 * 1024, 20_000_000L, 1080, 1920, 30));
        assertEquals("from the frame size when nothing else says",
                7_776_000, CaptionBurner.bitRate(0, 0, 0, 1080, 1920, 30));
        assertEquals(1_000_000, CaptionBurner.bitRate(100_000, 0, 0, 1080, 1920, 30));
        assertEquals(20_000_000, CaptionBurner.bitRate(40_000_000, 0, 0, 1080, 1920, 30));
    }

    /** A long video gets the time to encode again; the job's own end is never brought closer. */
    @Test public void aLongVideoGetsTheTimeToBeEncodedAgain() {
        long[] now = {0};
        MediaBudget.Clock clock = new MediaBudget.Clock() {
            @Override public long nanoTime() { return now[0]; }
            @Override public long wallMillis() { return 0; }
            @Override public void sleep(long millis) { }
        };
        MediaBudget.Deadline deadline = new MediaBudget.Deadline(10_000_000_000L, clock);
        deadline.allowAtLeast(5_000);
        now[0] = 9_999_000_000L;
        assertFalse("a shorter allowance cut the job's own time", deadline.expired());
        now[0] = 10_000_000_000L;
        assertTrue(deadline.expired());

        // A minute of video: the two minutes any save gets and three more.
        deadline.allowAtLeast(CaptionBurner.timeAllowedMs(60_000_000L));
        now[0] += 299_000_000_000L;
        assertFalse(deadline.expired());
        now[0] += 1_000_000_000L;
        assertTrue(deadline.expired());
    }

    @Test public void aFailedEncodeKeepsTheVideoAsItCameAndSaysSo() throws Exception {
        File source = files.newFile("saved.mp4");
        Files.write(source.toPath(), VIDEO);
        File output = files.newFile("captioned.mp4");
        for (RuntimeException runtime : new RuntimeException[]{null, new IllegalStateException("Could not make codec EGL surface current")}) {
            ShadowToast.reset();
            CaptionBurner.burner = (in, out, creator, caption, progress) -> {
                if (runtime != null) throw runtime;
                throw new IOException("No H.264 encoder took 1080 by 1920");
            };
            assertFalse(CaptionBurner.burnOrKeep(source, output, "@alice", "Hello", null));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, ShadowToast.shownToastCount());
            assertEquals(L10n.t("Couldn't write the caption on this video. Saving it without."),
                    ShadowToast.getTextOfLatestToast());
            assertArrayEquals(VIDEO, Files.readAllBytes(source.toPath()));
        }
    }

    /** Cancel, or no room, is the save's answer, not a reason to save it without. */
    @Test public void aStopIsPassedOnRatherThanSavedWithout() throws Exception {
        File source = files.newFile("saved.mp4");
        for (MediaBudget.StopException.Reason reason : new MediaBudget.StopException.Reason[]{
                MediaBudget.StopException.Reason.CANCELLED, MediaBudget.StopException.Reason.SPACE}) {
            MediaBudget.StopException stop = new MediaBudget.StopException("Stopped", reason);
            CaptionBurner.burner = (in, out, creator, caption, progress) -> { throw stop; };
            assertSame(stop, assertThrows(MediaBudget.StopException.class,
                    () -> CaptionBurner.burnOrKeep(source, files.newFile(reason + ".mp4"), "@alice", "Hello", null)));
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, ShadowToast.shownToastCount());
    }

    /**
     * Out of time partway through, the video already downloaded is saved without the caption, and
     * the job gets the time the rest of the save needs rather than failing at the next check.
     */
    @Test public void runningOutOfTimeSavesTheVideoWithout() throws Exception {
        File source = files.newFile("saved.mp4");
        Files.write(source.toPath(), VIDEO);
        long[] now = {0};
        MediaBudget.Clock clock = new MediaBudget.Clock() {
            @Override public long nanoTime() { return now[0]; }
            @Override public long wallMillis() { return 0; }
            @Override public void sleep(long millis) { }
        };
        MediaBudget.Deadline deadline = new MediaBudget.Deadline(1_000_000_000L, clock);
        File output = files.newFile("out.mp4");
        boolean[] kept = new boolean[1];
        CaptionBurner.burner = (in, out, creator, caption, progress) -> {
            now[0] = 2_000_000_000L;
            MediaBudget.check(deadline);
        };
        MediaBudget.runWithJobDeadline(deadline, () -> {
            try {
                kept[0] = !CaptionBurner.burnOrKeep(source, output, "@alice", "Hello", null);
            } catch (MediaBudget.StopException stop) {
                throw new AssertionError("running out of time lost the save", stop);
            }
        });
        assertTrue(kept[0]);
        assertFalse("the rest of the save has time again", deadline.expired());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(L10n.t("Couldn't write the caption on this video. Saving it without."),
                ShadowToast.getTextOfLatestToast());
        assertArrayEquals(VIDEO, Files.readAllBytes(source.toPath()));
    }

    @Test public void aPostWithNothingToWriteIsLeftAlone() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        CaptionBurner.burner = (in, out, creator, caption, progress) -> asked.incrementAndGet();
        assertFalse(CaptionBurner.burnOrKeep(files.newFile("saved.mp4"), files.newFile("out.mp4"), "", " \n ", null));
        assertEquals(0, asked.get());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, ShadowToast.shownToastCount());
    }

    /** Paused, the switch answers off like every other one, so a save never reaches the encoder. */
    @Test public void pausedHushfeedReadsTheSwitchAsOff() {
        Settings.DOWNLOAD_BURN_CAPTION.save(true);
        try {
            PausedProcess.set(true);
            assertFalse(Settings.DOWNLOAD_BURN_CAPTION.get());
        } finally {
            PausedProcess.set(false);
        }
        assertTrue(Settings.DOWNLOAD_BURN_CAPTION.get());
    }

    /** Whether anything at all was drawn in the rectangle, shadow included. */
    private static boolean inked(Bitmap bitmap, int left, int top, int right, int bottom) {
        int width = right - left;
        int[] pixels = new int[width * (bottom - top)];
        bitmap.getPixels(pixels, 0, width, left, top, width, bottom - top);
        for (int pixel : pixels) if ((pixel >>> 24) != 0) return true;
        return false;
    }
}
