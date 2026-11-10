package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentSurveyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.HIDE_COMMENT_SURVEYS.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.HIDE_COMMENT_SURVEYS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void tikTokDecidesByDefault() {
        assertFalse(Settings.HIDE_COMMENT_SURVEYS.get());
        assertFalse("the server's survey config answers", CommentSurvey.hide());
    }

    @Test public void theSwitchAnswersNoSurvey() {
        Settings.HIDE_COMMENT_SURVEYS.save(true);
        assertTrue(CommentSurvey.hide());
    }

    @Test public void pausedTheSurveyIsTikToks() {
        Settings.HIDE_COMMENT_SURVEYS.save(true);
        PausedProcess.set(true);
        assertFalse(CommentSurvey.hide());
    }

    @Test public void theExportCountsTheCheckAndTheSurveysHidden() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (CommentSurvey.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            CommentSurvey.hide();
            HookStatus.report();
            assertEquals("the check alone", 1, found[0]);
            Settings.HIDE_COMMENT_SURVEYS.save(true);
            CommentSurvey.hide();
            HookStatus.report();
            assertEquals("and the survey hidden", 2, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}
