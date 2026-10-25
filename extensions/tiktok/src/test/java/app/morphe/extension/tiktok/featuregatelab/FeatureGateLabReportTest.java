package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The exported report says what the Feature Gate Lab was told to do and whether it was asked.
 *
 * <p>Issue #4's reporter set two overrides as asked and the tab stayed empty, and nothing in
 * the export could say whether the overrides were live or ever consulted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FeatureGateLabReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        LogBufferManager.clearLogBuffer();
        FeatureGateLabReport.install();
    }

    @After public void tearDown() {
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        LogBufferManager.clearLogBuffer();
    }

    @Test public void theReportNamesEachOverrideAndWhetherAGateReadReachedIt() {
        FeatureGateLabSession.begin();
        assertTrue("the rule was not saved; main process " + Utils.isMainProcess()
                        + ", context " + (Utils.getContext() != null),
                FeatureGateLabStore.saveRule(FeatureGateLabStore.MANAGER_ABMOCK,
                        "favorite_reverse", "INT", "0", true));
        FeatureGateLabStore.setMasterEnabled(true);

        List<String> lines = new FeatureGateLabReportProbe().lines();
        assertEquals(lines.toString(), 2, lines.size());
        assertEquals("overrides: on, rules: 1", lines.get(0));
        assertEquals("abmock favorite_reverse INT = 0, enabled, not read yet", lines.get(1));

        // A read from TikTok's side marks the rule as consulted, with what TikTok had.
        assertEquals(0, FeatureGateLabRuntime.overrideInt("favorite_reverse", 1));
        lines = new FeatureGateLabReportProbe().lines();
        assertTrue(lines.get(1), lines.get(1).startsWith("abmock favorite_reverse INT = 0, enabled, read"));
        assertTrue(lines.get(1), lines.get(1).endsWith(", TikTok had 1"));

        // And it is in the export, under its own heading. Like the counters, the section on
        // its own does not make an empty report worth sending, so the export carries one
        // ordinary event here the way every real one does.
        LogBufferManager.appendEvent(app.morphe.extension.shared.diagnostics.DiagnosticCategory.OTHER,
                "Test", "INFO", "an ordinary event");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("[" + FeatureGateLabReport.TITLE + "]"));
        assertTrue(report, report.contains("abmock favorite_reverse INT = 0, enabled, read"));
    }

    @Test public void aPhoneThatNeverTouchedTheLabSaysNothing() {
        assertTrue(new FeatureGateLabReportProbe().lines().isEmpty());
        LogBufferManager.appendEvent(app.morphe.extension.shared.diagnostics.DiagnosticCategory.OTHER,
                "Test", "INFO", "an ordinary event");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("an ordinary event"));
        assertFalse(report, report.contains(FeatureGateLabReport.TITLE));
    }

    /** The section as registered, reached without a second instance. */
    private static final class FeatureGateLabReportProbe {
        List<String> lines() {
            java.lang.reflect.Field field;
            try {
                field = FeatureGateLabReport.class.getDeclaredField("INSTANCE");
                field.setAccessible(true);
                return ((FeatureGateLabReport) field.get(null)).lines();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
