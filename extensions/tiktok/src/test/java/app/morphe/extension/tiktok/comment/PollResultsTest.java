package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
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
public class PollResultsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.SHOW_POLL_RESULTS.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        Settings.SHOW_POLL_RESULTS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void tikTokDecidesByDefault() {
        assertFalse(Settings.SHOW_POLL_RESULTS.get());
        assertFalse("the ended check and the reader's vote decide", PollResults.showBeforeVoting());
    }

    @Test public void theSwitchDrawsTheResults() {
        Settings.SHOW_POLL_RESULTS.save(true);
        assertTrue(PollResults.showBeforeVoting());
    }

    @Test public void theExportCountsTheCheckAndTheResultsShown() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (PollResults.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            PollResults.showBeforeVoting();
            HookStatus.report();
            assertEquals("the check alone", 1, found[0]);
            Settings.SHOW_POLL_RESULTS.save(true);
            PollResults.showBeforeVoting();
            HookStatus.report();
            assertEquals("and the results drawn early", 2, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}
