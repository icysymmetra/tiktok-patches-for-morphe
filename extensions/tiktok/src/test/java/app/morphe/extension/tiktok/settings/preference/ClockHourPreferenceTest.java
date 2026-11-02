package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
 * The hour row takes an hour the way it shows one. It showed "06:00" and took digits only, so
 * "0600" was read as six hundred and pulled down to 23:00.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ClockHourPreferenceTest {
    private ClockHourPreference row;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        row = new ClockHourPreference(RuntimeEnvironment.getApplication(), "Day starts at", "",
                Settings.SESSION_BUDGET_RESET_HOUR);
    }

    @Test public void everyWayOfWritingAnHourReadsAsThatHour() {
        String[][] cases = {{"6", "6"}, {"06", "6"}, {"6:00", "6"}, {"06:00", "6"}, {"0600", "6"},
                {"600", "6"}, {"18:00", "18"}, {"1800", "18"}, {"0", "0"}, {" 23 ", "23"}, {"100", "1"},
                // Arabic-Indic digits, which a time keyboard in that locale types.
                {"٠٦:٠٠", "6"}, {"١٨", "18"}};
        for (String[] typed : cases) {
            assertEquals("typed " + typed[0], Integer.valueOf(typed[1]), row.parseTyped(typed[0]));
        }
    }

    @Test public void minutesAndWordsAreNotAnHour() {
        for (String typed : new String[]{"06:30", "0630", "six", "", "6:0", "123:00"}) {
            assertNull("typed " + typed, row.parseTyped(typed));
        }
        assertTrue(row.unreadableMessage().contains("06:00"));
    }
}
