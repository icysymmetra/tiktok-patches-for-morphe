package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Issue #162: with Hide Tako AI on, a caption tap on some videos still opened TikTok's search
 * panel with its "Discover more with Tako" section instead of expanding the caption.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TakoCaptionPanelTest {
    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        Settings.HIDE_TAKO_AI.save(false);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.HIDE_TAKO_AI.resetToDefault();
        HookStatus.clear();
    }

    @Test public void theSwitchOffLeavesTikToksAnswer() {
        assertTrue("the panel was refused with the switch off", TakoAiFilter.allowCaptionSearchPanel(true));
        assertFalse("the panel was asked for where TikTok said no", TakoAiFilter.allowCaptionSearchPanel(false));
        String report = HookStatus.report().toString();
        assertTrue("the ask was not named: " + report, report.contains("tako AI: 1 found"));
    }

    @Test public void theSwitchOnSendsTheTapToTheInPlaceExpand() {
        Settings.HIDE_TAKO_AI.save(true);
        assertFalse("the panel opened with the switch on", TakoAiFilter.allowCaptionSearchPanel(true));
        String report = HookStatus.report().toString();
        assertTrue("the skip was not named: " + report, report.contains("tako AI: 2 found"));
    }

    @Test public void aVideoWithoutThePanelIsNotCountedAsSkipped() {
        Settings.HIDE_TAKO_AI.save(true);
        assertFalse(TakoAiFilter.allowCaptionSearchPanel(false));
        String report = HookStatus.report().toString();
        assertTrue("the ask was not named: " + report, report.contains("tako AI: 1 found"));
    }

    @Test public void theSwitchOffLeavesTheCaptionASmartSearchOne() {
        assertTrue("the flag was refused with the switch off", TakoAiFilter.allowSmartSearchCaption(true));
        assertFalse("a video without smart search info gained some", TakoAiFilter.allowSmartSearchCaption(false));
        String report = HookStatus.report().toString();
        assertTrue("the ask was not named: " + report, report.contains("tako AI: 1 found"));
    }

    @Test public void theSwitchOnMakesTheCaptionOneWithNoSmartSearchInfo() {
        Settings.HIDE_TAKO_AI.save(true);
        assertFalse("a smart search caption kept its forced more", TakoAiFilter.allowSmartSearchCaption(true));
        assertFalse(TakoAiFilter.allowSmartSearchCaption(false));
        String report = HookStatus.report().toString();
        assertTrue("the drop was not named: " + report, report.contains("tako AI: 2 found"));
    }

    @Test public void aPausedProcessKeepsTikToksSmartSearchCaption() {
        Settings.HIDE_TAKO_AI.save(true);
        PausedProcess.set(true);
        try {
            assertTrue("the flag was refused while paused", TakoAiFilter.allowSmartSearchCaption(true));
        } finally {
            PausedProcess.set(false);
        }
        assertFalse("the switch did not come back after the pause", TakoAiFilter.allowSmartSearchCaption(true));
    }

    @Test public void aPausedProcessGetsTikToksPanel() {
        Settings.HIDE_TAKO_AI.save(true);
        PausedProcess.set(true);
        try {
            assertTrue("the panel was refused while paused", TakoAiFilter.allowCaptionSearchPanel(true));
        } finally {
            PausedProcess.set(false);
        }
        assertFalse("the switch did not come back after the pause", TakoAiFilter.allowCaptionSearchPanel(true));
    }
}
