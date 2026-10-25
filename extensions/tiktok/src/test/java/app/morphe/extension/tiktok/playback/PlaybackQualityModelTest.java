package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.List;

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

/**
 * What the quality picker does with a model string it cannot pick a gear out of.
 *
 * <p>Issue #3 is a screenshot of {@code PlaybackQuality: Could not read the playback quality
 * model} sitting over the feed, once per video, on a build whose model getter hands back an
 * empty string. The message was right and the channel was wrong: it is written for whoever is
 * fixing the code, it is English on a phone set to any language, and a per-video path repeats
 * it forever. These pin the channel it moved to, and that the getter is named there.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PlaybackQualityModelTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The worst case for the reader: diagnostic logging on, so the error toast that used to
        // carry this is armed. A test that left it off could not tell the two channels apart.
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("errors");
        Settings.PLAYBACK_QUALITY.save("lowest");
        PlaybackQuality.resetForTests();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
        PlaybackQuality.resetForTests();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        ShadowToast.reset();
    }

    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void aModelThatIsNotAnObjectComesBackUntouchedAndNeverReachesTheScreen() {
        for (String unusable : List.of("", "   ", "[]", "not json", "\"a string\"", "null")) {
            assertSame(unusable, PlaybackQuality.filterVideoModelJson(unusable));
        }
        settle();

        assertNull("No failure inside the bundle belongs on the feed",
                ShadowToast.getTextOfLatestToast());
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("Could not read the playback quality model"));
    }

    @Test public void theGetterThatHandedBackTheUnusableModelIsNamedOnce() {
        // Six unusable strings, one getter. Hook status is a statement about the build, so it
        // says the getter once however many videos go past.
        for (String unusable : List.of("", "   ", "[]", "not json", "\"a string\"", "null")) {
            PlaybackQuality.filterVideoModelJson(unusable);
        }

        List<String> missing = HookStatus.missing(PlaybackQuality.FAMILY);
        assertEquals("Expected one miss, got " + missing, 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(
                PlaybackQuality.VIDEO_MODEL + "#" + PlaybackQuality.VIDEO_MODEL_GETTER));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(PlaybackQuality.FAMILY));
        assertTrue(report, report.contains(PlaybackQuality.VIDEO_MODEL_GETTER));
    }

    @Test public void eachModelGetterAnswersForItself() {
        PlaybackQuality.filterVideoModelJson("[]");
        PlaybackQuality.filterDashVideoModelJson("[]");

        List<String> missing = HookStatus.missing(PlaybackQuality.FAMILY);
        assertEquals("Expected both getters, got " + missing, 2, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains(PlaybackQuality.VIDEO_MODEL_GETTER));
        assertTrue(missing.toString(), missing.get(1).contains(PlaybackQuality.DASH_MODEL_GETTER));
    }

    @Test public void anEmptyModelIsAnOrdinaryVideoAndNotAMiss() {
        // Video.getVideoModelStr hands its stored string back only for an adaptive item and
        // builds nothing otherwise, so "" is what every non-adaptive video says on every
        // retained build. The reporter's phone said it for every video and the row read
        // "2 missing", which is a statement about a broken build, and the build was not.
        for (String empty : List.of("", "   ")) {
            assertSame(empty, PlaybackQuality.filterVideoModelJson(empty));
            assertSame(empty, PlaybackQuality.filterDashVideoModelJson(empty));
        }

        assertEquals(List.of(), HookStatus.missing(PlaybackQuality.FAMILY));
        assertFalse(HookStatus.anyMissing());
        // Nothing was chosen either, so the family has nothing to say yet.
        assertEquals(HookStatus.report().toString(), List.of(), HookStatus.report());
    }

    @Test public void thePlayerSetterIsTheChoiceTheExportCanCallPlayed() {
        // The player kit's models are built out of getRawBitRate by four converters, so the
        // aweme getter's choice above was reported and never played. The setter is where the
        // player's own list is decided, and the line names it so a reader can tell the two apart.
        var low = new AdvancedDownloadsTest.Gear("normal_360_0", 100, "https://example.com/low");
        var high = new AdvancedDownloadsTest.Gear("normal_1080_0", 400, "https://example.com/high");
        assertEquals(List.of(low), PlaybackQuality.filterPlayerUrlModelGears(List.of(high, low)));
        assertEquals(List.of(low), PlaybackQuality.filterPlayerVideoGears(List.of(high, low)));
        // One gear is nothing to choose from: the same list goes back and no line is written.
        List<?> single = List.of(high);
        assertSame(single, PlaybackQuality.filterPlayerUrlModelGears(single));

        assertTrue(HookStatus.missing(PlaybackQuality.FAMILY).isEmpty());
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().contains("playback quality: 2 found, 0 missing"));
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("of 2 gears from SimVideoUrlModel#setBitRate: normal_1080_0 1080p, normal_360_0 360p"));
        assertTrue(report, report.contains("of 2 gears from SimVideo#setBitRate: "));
        assertFalse(report, report.contains("of 1 gears"));
    }

    @Test public void anOrdinaryVideoWithNoAdaptiveGearsIsNotAMiss() {
        // A readable model with nothing to choose from is the common case, not a broken build.
        String plain = "{\"video_id\":\"one\"}";
        assertSame(plain, PlaybackQuality.filterVideoModelJson(plain));

        assertEquals(List.of(), HookStatus.missing(PlaybackQuality.FAMILY));
        assertFalse(HookStatus.anyMissing());
        List<String> report = HookStatus.report();
        assertEquals("Expected the family to report as bound, got " + report, 1, report.size());
        assertTrue(report.get(0), report.get(0).startsWith(PlaybackQuality.FAMILY + ": 1 found"));
    }

    @Test public void aModelThatStopsPartWayThroughIsTreatedTheSameWay() {
        // Well formed enough to start, truncated in the middle: the JSON reader is the only
        // thing that can tell, so this is the branch the catch still has to cover.
        String truncated = "{\"dynamic_video\":{\"dynamic_video_list\":[";
        assertSame(truncated, PlaybackQuality.filterVideoModelJson(truncated));
        settle();

        assertNull(ShadowToast.getTextOfLatestToast());
        assertEquals(1, HookStatus.missing(PlaybackQuality.FAMILY).size());
    }

    @Test public void theGearListPathSaysWhatItPickedAndFromWhat() {
        // Issue #3's phone hands back an empty string from both model getters, which leaves the
        // gear list as the only path the picker has there. Until now that path said nothing, so
        // an export could not tell a list never handed over from a gear chosen and ignored.
        var low = new AdvancedDownloadsTest.Gear("normal_360_0", 100, "https://example.com/low");
        var high = new AdvancedDownloadsTest.Gear("normal_1080_0", 400, "https://example.com/high");
        assertEquals(List.of(low), PlaybackQuality.filterVideoGears(List.of(high, low)));
        assertEquals(List.of(low), PlaybackQuality.filterVideoGears(List.of(high, low)));

        assertTrue(HookStatus.missing(PlaybackQuality.FAMILY).isEmpty());
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().contains("playback quality: 1 found, 0 missing"));
        // The line is an ordinary event, so it is in the export whenever the reader's filter
        // takes ordinary events, which the default filter does.
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        String report = LogBufferManager.buildExportText();
        String line = "Playback quality lowest picked normal_360_0 360p of 2 gears from "
                + "Video#getBitRate: normal_1080_0 1080p, normal_360_0 360p";
        assertTrue(report, report.contains(line));
        // One line per distinct choice, not one per video.
        assertEquals(report, report.indexOf(line), report.lastIndexOf(line));
    }

    @Test public void aChoiceIsWrittenOncePerKindNotOncePerVideo() {
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        // Two videos offering the same sizes under different names and bitrates: one line.
        PlaybackQuality.filterVideoGears(List.of(
                new AdvancedDownloadsTest.Gear("normal_1080_0", 400, "https://example.com/a1"),
                new AdvancedDownloadsTest.Gear("normal_360_0", 100, "https://example.com/a2")));
        PlaybackQuality.filterVideoGears(List.of(
                new AdvancedDownloadsTest.Gear("adapt_1080_1", 390, "https://example.com/b1"),
                new AdvancedDownloadsTest.Gear("lowest_360_1", 90, "https://example.com/b2")));
        // A different ladder is a different kind of choice.
        PlaybackQuality.filterVideoGears(List.of(
                new AdvancedDownloadsTest.Gear("normal_720_0", 300, "https://example.com/c1"),
                new AdvancedDownloadsTest.Gear("normal_540_0", 200, "https://example.com/c2")));
        String report = LogBufferManager.buildExportText();
        int lines = report.split("Playback quality ", -1).length - 1;
        assertEquals(report, 2, lines);
    }

    @Test public void aGearListWithNothingPlayableIsAMissNamedByItsGetter() {
        List<?> unplayable = List.of(new AdvancedDownloadsTest.Gear("normal_720_0", 200, null));
        assertSame(unplayable, PlaybackQuality.filterDashGears(unplayable));
        assertSame(unplayable, PlaybackQuality.filterVideoGears(unplayable));
        // No gears at all, null or empty, is an ordinary item such as a photo post, not a
        // broken getter: the S22 feed has some in every session and the family must not read
        // as broken on a build where the path works.
        assertNull(PlaybackQuality.filterVideoGears(null));
        assertTrue(PlaybackQuality.filterVideoGears(new ArrayList<>()).isEmpty());

        List<String> missing = HookStatus.missing(PlaybackQuality.FAMILY);
        assertEquals(missing.toString(), 2, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("playable gear list from VideoUrlModel#getBitRate"));
        assertTrue(missing.toString(), missing.get(1).contains("playable gear list from Video#getBitRate"));
    }
}
