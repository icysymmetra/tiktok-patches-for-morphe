package app.morphe.extension.tiktok.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A hook that no longer finds its anchor fails quietly: the switch still reads on and nothing
 * happens. The Diagnostics row and the exported report both read this registry, so what it says
 * about a surface is the only thing standing between a broken build and a bug report about a
 * feature that never ran.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class HookStatusTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    @After public void tearDown() {
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    @Test public void aSurfaceThatFoundEverythingSaysSo() {
        HookStatus.bound("comments", "like_button");
        HookStatus.bound("comments", "dislike_button");

        assertFalse("a surface with no miss was called broken", HookStatus.anyMissing());
        assertEquals(List.of("comments: 2 found, 0 missing"), HookStatus.report());
    }

    @Test public void theSameLookupTwiceCountsOnce() {
        HookStatus.bound("inbox", "row");
        HookStatus.bound("inbox", "row");
        HookStatus.missingViewId("inbox", "avatar");
        HookStatus.missingViewId("inbox", "avatar");

        // Both run on every bind, so counting repeats would report thousands within a scroll.
        assertEquals(List.of("inbox: 1 found, 1 missing. First missing: view id 'avatar'"),
                HookStatus.report());
    }

    @Test public void theLineNamesTheFirstThingThatWentMissing() {
        HookStatus.missingViewId("share sheet", "action_row");
        HookStatus.missingViewId("share sheet", "action_label");

        assertTrue(HookStatus.anyMissing());
        assertEquals(List.of("share sheet: 0 found, 2 missing. First missing: view id 'action_row'"),
                HookStatus.report());
        assertEquals(List.of("share sheet"), HookStatus.familiesMissingSomething());
    }

    @Test public void recoveringAViewRemovesOnlyThatTransientMiss() {
        HookStatus.missingViewId("navigation", "Home tab");
        HookStatus.missingMember("navigation", "field", "TabModel", "selected");
        HookStatus.threw("navigation", "selection", new IllegalStateException());

        HookStatus.recoveredViewId("navigation", "Home tab");

        assertEquals(List.of(
                        "field TabModel#selected",
                        "a working 'selection' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing("navigation"));
        assertTrue(HookStatus.anyMissing());
        assertEquals(List.of("navigation"), HookStatus.familiesMissingSomething());
    }

    @Test public void eachSurfaceIsCountedOnItsOwn() {
        HookStatus.bound("overlay", "cover");
        HookStatus.missingMember("feed models", "method", "com.example.Card", "isAdOrContainAd");
        HookStatus.missingViewId("comments", "dislike_button");

        assertEquals(
                List.of("overlay: 1 found, 0 missing",
                        "feed models: 0 found, 1 missing. "
                                + "First missing: method com.example.Card#isAdOrContainAd",
                        "comments: 0 found, 1 missing. First missing: view id 'dislike_button'"),
                HookStatus.report());
        assertEquals(List.of("feed models", "comments"), HookStatus.familiesMissingSomething());
    }

    @Test public void aHookThatThrewIsAMissAndNamesWhatItThrew() {
        // A hook that found its anchor, ran, and came out through a catch used to leave nothing
        // behind: the export called the surface healthy while the feature did nothing at all.
        HookStatus.bound("share sheet", "dqr");
        HookStatus.threw("share sheet", "channels", new IllegalStateException("hostile"));

        assertTrue(HookStatus.anyMissing());
        assertEquals(
                List.of("share sheet: 1 found, 1 missing. First missing: a working 'channels' "
                        + "hook (it threw java.lang.IllegalStateException)"),
                HookStatus.report());
    }

    @Test public void aHookThatThrowsOnEveryPassIsCountedOnceAndDoesNotHideAnother() {
        // The share model is rebuilt every time the sheet opens, so a throw that counted per
        // pass would fill the family's cap in a few minutes and push out real misses.
        for (int pass = 0; pass < 5; pass++) {
            HookStatus.threw("share sheet", "channels", new IllegalStateException("hostile"));
        }
        HookStatus.threw("share sheet", "actions", new NullPointerException());
        HookStatus.threw("share sheet", "actions", null);

        assertEquals(2, HookStatus.missing("share sheet").size());
        assertTrue(HookStatus.missing("share sheet").get(1).contains("'actions'"));
        assertTrue(HookStatus.missing("share sheet").get(1).contains("NullPointerException"));
    }

    @Test public void aHookWithNoNameableCauseStillReportsSomething() {
        HookStatus.threw("share links", "rewrite", null);

        assertEquals(List.of("share links: 0 found, 1 missing. First missing: a working "
                + "'rewrite' hook (it threw an error it could not name)"), HookStatus.report());
    }

    @Test public void aSurfaceNobodyHasTouchedIsNotCalledBroken() {
        // A hook that has not run yet has not failed, so an empty registry reports nothing at
        // all rather than a wall of surfaces sitting at zero.
        assertEquals(List.of(), HookStatus.report());
        assertFalse(HookStatus.anyMissing());
    }

    @Test public void aFamilyPastItsCapSaysItStoppedCounting() {
        // Otherwise a build where everything came apart reads exactly like one with a round
        // number of misses, and the log goes quiet at the same moment.
        for (int miss = 0; miss < 260; miss++) HookStatus.missingViewId("overlay", "id_" + miss);

        String line = HookStatus.report().get(0);
        assertTrue("the report hid the truncation: " + line,
                line.contains("and more it stopped counting"));
        assertEquals(200, HookStatus.missing("overlay").size());
    }

    @Test public void aMissReachesTheExportedReport() {
        HookStatus.bound("comments", "like_button");
        HookStatus.missingViewId("comments", "dislike_button");

        String report = LogBufferManager.buildExportText();
        assertTrue("the export carried no hook table: " + report, report.contains("[HOOK STATUS]"));
        assertTrue("the export did not name the surface: " + report,
                report.contains("comments: 1 found, 1 missing. First missing: view id 'dislike_button'"));
    }

    @Test public void theTableFollowsTheIncludedDiagnosticsChoice() {
        // Misses are buffered as PATCH_ERRORS events, so a reader who deselected those and gets
        // the table anyway has been given something they asked not to have.
        BaseSettings.DEBUG_LOG_FILTERS.save("downloads");
        HookStatus.missingViewId("comments", "dislike_button");
        LogBufferManager.appendEvent(DiagnosticCategory.DOWNLOADS, "Test", "INFO", "a download");

        String report = LogBufferManager.buildExportText();
        assertTrue("nothing was exported at all: " + report, report.contains("a download"));
        assertFalse("the hook table ignored the filter: " + report,
                report.contains("[HOOK STATUS]"));
    }

    @Test public void theTableIsRedactedLikeEverythingElseInTheReport() {
        // Every other section goes through the redactor. This one carries names read out of the
        // running app, and the next one added may not be a literal.
        HookStatus.missingViewId("share sheet",
                "https://api16-normal.tiktokv.com/x?sessionid=secret123");

        String report = LogBufferManager.buildExportText();
        assertTrue("the export carried no hook table: " + report, report.contains("[HOOK STATUS]"));
        assertFalse("a credential went out in the hook table: " + report,
                report.contains("secret123"));
    }

    @Test public void clearingTheDiagnosticDataClearsTheHookTable() {
        HookStatus.bound("overlay", "cover");
        HookStatus.missingViewId("overlay", "gone");

        LogBufferManager.clearLogBuffer();

        assertEquals("the table survived being cleared", List.of(), HookStatus.report());
        assertFalse(HookStatus.anyMissing());
    }

    @Test public void aReportWithOnlyHookStatusInItIsStillEmpty() {
        // A family exists from the first layout pass onward, so if the table counted towards a
        // report having content, "No matching Morphe diagnostics found" could never be said
        // again and Clear diagnostic data would look as though it had done nothing.
        HookStatus.bound("overlay", "cover");

        assertEquals("a report was built out of nothing but hook status", "",
                LogBufferManager.buildExportText());
    }

    @Test public void anEmptyRegistryExportsNothing() {
        assertEquals("a report was built with nothing to put in it", "",
                LogBufferManager.buildExportText());
    }

    /**
     * The report line on a German phone.
     *
     * <p>It used to be five pieces glued together in the shared library, which no table row can
     * hold: a language that puts the counts elsewhere in the sentence had nowhere to go. It is
     * one sentence now, and this bundle hands the shared library a writer for it.
     */
    @Test
    public void theReportLineReadsGermanOnAGermanPhone() throws Exception {
        HookStatus.clear();
        try {
            android.content.Context german = germanContext();
            HookStatus.setLineWriter((family, found, missing, truncated, firstMiss) ->
                    firstMiss == null
                            ? app.morphe.extension.tiktok.settings.L10n.f(german,
                                    "%1$s: %2$d found, %3$d missing", family, found, missing)
                            : app.morphe.extension.tiktok.settings.L10n.f(german,
                                    "%1$s: %2$d found, %3$d missing. First missing: %4$s",
                                    family, found, missing, firstMiss));

            HookStatus.bound("comments", "like_button");
            HookStatus.missingViewId("comments", "dislike_button");

            String line = HookStatus.report().get(0);
            assertTrue("the report line is still English: " + line,
                    line.contains("gefunden") && line.contains("fehlen"));
            assertTrue("the family and the counts did not survive the translation: " + line,
                    line.startsWith("comments: 1 ") && line.contains("dislike_button"));
        } finally {
            HookStatus.setLineWriter(null);
            HookStatus.clear();
        }
    }

    /** The words the shipped table holds, under a German configuration. */
    private static android.content.Context germanContext() {
        android.content.res.Configuration configuration =
                new android.content.res.Configuration(
                        org.robolectric.RuntimeEnvironment.getApplication()
                                .getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.GERMANY);
        return org.robolectric.RuntimeEnvironment.getApplication()
                .createConfigurationContext(configuration);
    }
}
