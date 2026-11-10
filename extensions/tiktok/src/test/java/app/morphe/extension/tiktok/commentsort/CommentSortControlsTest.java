package app.morphe.extension.tiktok.commentsort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Both answers the comment sort switch replaces: the rollout style TikTok reads once per run,
 * and the per-post eligibility check it asks on every comment sheet.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentSortControlsTest {
    /** What TikTok's own eligibility check compares the style against before offering the sheet. */
    private static final int FULL_SORT_SHEET_STYLE = 2;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.COMMENT_SORT_CONTROLS.resetToDefault();
    }

    @Test
    public void withTheSwitchOffTikTokOwnAnswersComeBackUntouched() {
        Settings.COMMENT_SORT_CONTROLS.save(false);

        for (int style : new int[] {0, 1, 2, 7, -1}) {
            assertEquals(style, CommentSortControls.forceOptionStyle(style));
        }
        assertFalse(CommentSortControls.shouldForceSortEligibility());
    }

    @Test
    public void withTheSwitchOnEveryStyleBecomesTheFullSheetAndEveryPostIsEligible() {
        Settings.COMMENT_SORT_CONTROLS.save(true);

        for (int style : new int[] {0, 1, 2, 7, -1}) {
            assertEquals(FULL_SORT_SHEET_STYLE, CommentSortControls.forceOptionStyle(style));
        }
        assertTrue(CommentSortControls.shouldForceSortEligibility());
    }
}
