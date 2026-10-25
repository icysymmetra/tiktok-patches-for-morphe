/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * An export says whether a ghost mode reporter was reached and what it answered.
 *
 * <p>Two exports taken on 2026-09-15, one with the setting on and one with it off, carried no
 * ghost mode line of any kind, so a profile showing no follower counts could not be told from a
 * profile TikTok never asked about. These cases pin the three states apart: no family, the
 * family with nothing called, and a call site with its answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GhostModeDiagnosticsTest {
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        forgetWhatWasReported();
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(false);
    }

    @After
    public void tearDown() {
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        forgetWhatWasReported();
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(false);
    }

    @Test
    public void aReporterThatWasBlockedSaysSo() {
        Settings.GHOST_MODE.save(true);
        assertTrue(GhostMode.shouldBlockProfileView());

        String export = LogBufferManager.buildExportText();
        assertTrue(export, export.contains("Ghost mode profile view: blocked"));
        assertTrue(ghostModeLine(), ghostModeLine().contains("ghost mode"));
    }

    @Test
    public void theOnlineStatusReportNamesItsOwnCallSiteAndFollowsItsOwnSwitch() {
        Settings.GHOST_MODE.save(true);
        assertFalse(GhostMode.shouldBlockOnlineStatus());
        assertTrue(LogBufferManager.buildExportText().contains("Ghost mode online status: sent"));

        Settings.GHOST_HIDE_ONLINE_STATUS.save(true);
        assertTrue(GhostMode.shouldBlockOnlineStatus());
        assertTrue(LogBufferManager.buildExportText().contains("Ghost mode online status: blocked"));
    }

    @Test
    public void aReporterThatWasLetThroughSaysThatInstead() {
        assertFalse(GhostMode.shouldBlockProfileView());

        String export = LogBufferManager.buildExportText();
        assertTrue(export, export.contains("Ghost mode profile view: sent"));
        // Two-sided: without this the case above would pass against a line that always reads
        // "blocked", and the setting would not be what the export is reporting.
        assertFalse(export, export.contains("Ghost mode profile view: blocked"));
    }

    @Test
    public void oneCallSiteSaysNothingAboutTheOthers() {
        Settings.GHOST_MODE.save(true);
        GhostMode.shouldBlockStoryView();

        String export = LogBufferManager.buildExportText();
        assertTrue(export, export.contains("Ghost mode story view: blocked"));
        assertFalse(export, export.contains("profile view"));
        assertFalse(export, export.contains("typing status"));
    }

    /**
     * The typing indicator asks on a timer while a message is being written. A line per call
     * would push everything else out of the buffer the reader is trying to read, so a steady
     * answer is worth one line and a changed answer is worth another.
     */
    @Test
    public void aSteadyAnswerIsWrittenOnceAndAChangedOneIsWrittenAgain() {
        Settings.GHOST_MODE.save(true);
        for (int call = 0; call < 50; call++) {
            GhostMode.shouldBlockTypingStatus();
        }
        assertEquals(1, occurrences(LogBufferManager.buildExportText(), "Ghost mode typing status:"));

        Settings.GHOST_MODE.save(false);
        GhostMode.shouldBlockTypingStatus();
        String export = LogBufferManager.buildExportText();
        assertEquals(2, occurrences(export, "Ghost mode typing status:"));
        assertTrue(export, export.contains("Ghost mode typing status: sent"));
    }

    /**
     * The state the two failed reproductions could not name: the patch is in this build and no
     * reporter was reached. A family that is simply absent means the build has no ghost mode.
     */
    @Test
    public void theFamilyIsInTheReportEvenWhenNoReporterRan() {
        assertEquals("a family exists before anything ran", List.of(), HookStatus.report());

        GhostMode.installed();

        List<String> report = HookStatus.report();
        assertEquals(report.toString(), 1, report.size());
        assertTrue(report.get(0), report.get(0).contains("ghost mode"));
        assertFalse(
                LogBufferManager.buildExportText(),
                LogBufferManager.buildExportText().contains("Ghost mode profile view"));
    }

    @Test
    public void theLineNamesTheCallSiteAndTheAnswerAndNothingElse() {
        Settings.GHOST_MODE.save(true);
        GhostMode.shouldBlockProfileView();

        String line = lineContaining("Ghost mode profile view");
        // The report names no creator anywhere else and this must not be the exception. The
        // whole message is the call site and the answer.
        assertTrue(line, line.endsWith("Ghost mode profile view: blocked"));
    }

    /**
     * The per-call-site memory, emptied the way a fresh process starts. Reflection rather than a
     * reset method, because a method only a test calls would ship in the payload on every phone.
     */
    /**
     * The play report every story view also sends (#39): held back for a story, whichever way
     * TikTok marks it, and left alone for a feed video, which says nothing in the export.
     */
    @Test
    public void onlyAStorysPlayReportIsHeldBack() {
        Settings.GHOST_MODE.save(true);
        assertTrue("a story TikTok flags", GhostMode.shouldBlockStoryStats(aweme(true, 0)));
        assertTrue("a story by its type", GhostMode.shouldBlockStoryStats(aweme(false, 40)));
        assertTrue("a shared story by its type", GhostMode.shouldBlockStoryStats(aweme(false, 45)));
        assertTrue(LogBufferManager.buildExportText().contains("Ghost mode story play stats: blocked"));

        forgetWhatWasReported();
        LogBufferManager.clearLogBuffer();
        assertFalse("a feed video's play report was held back", GhostMode.shouldBlockStoryStats(aweme(false, 0)));
        assertFalse(GhostMode.shouldBlockStoryStats(null));
        assertFalse("a feed video asked about the story report",
                LogBufferManager.buildExportText().contains("story play stats"));

        Settings.GHOST_MODE.save(false);
        assertFalse(GhostMode.shouldBlockStoryStats(aweme(true, 40)));
        assertTrue(LogBufferManager.buildExportText().contains("Ghost mode story play stats: sent"));
    }

    @Test
    public void aStoryGetterThatFailsSendsTheReportAndSaysWhy() {
        Settings.GHOST_MODE.save(true);
        Aweme renamed = new Aweme() {
            @Override public boolean getIsTikTokStory() { throw new NoSuchMethodError("getIsTikTokStory"); }
        };
        assertFalse(GhostMode.shouldBlockStoryStats(renamed));
        assertTrue(HookStatus.missing("ghost mode").toString(),
                HookStatus.missing("ghost mode").toString().contains("NoSuchMethodError"));
    }

    private static Aweme aweme(boolean story, int type) {
        return new Aweme() {
            @Override public boolean getIsTikTokStory() { return story; }
            @Override public int getAwemeType() { return type; }
        };
    }

    private static void forgetWhatWasReported() {
        try {
            java.lang.reflect.Field field = GhostMode.class.getDeclaredField("reported");
            field.setAccessible(true);
            ((java.util.Map<?, ?>) field.get(null)).clear();
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError("GhostMode no longer keeps what it reported", unreachable);
        }
    }

    private static String ghostModeLine() {
        for (String line : HookStatus.report()) {
            if (line.contains("ghost mode")) return line;
        }
        return "no ghost mode family in " + HookStatus.report();
    }

    private static String lineContaining(String needle) {
        for (String line : LogBufferManager.buildExportText().split("\n")) {
            if (line.contains(needle)) return line;
        }
        throw new AssertionError("no line containing \"" + needle + "\" in the export");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
