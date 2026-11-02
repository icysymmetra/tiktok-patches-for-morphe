package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExactCountsTest {
    private Locale locale;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        locale = Locale.getDefault();
    }

    @After public void tearDown() {
        Locale.setDefault(locale);
        Settings.SHOW_EXACT_COUNTS.save(false);
    }

    @Test public void theSwitchOffLeavesEveryCountToTikTok() {
        Settings.SHOW_EXACT_COUNTS.save(false);
        assertNull(ExactCounts.format(1_234_567L));
        assertNull(ExactCounts.format(12_345L));
    }

    @Test public void theSwitchOnGivesTheWholeNumberInThePhonesGrouping() {
        Settings.SHOW_EXACT_COUNTS.save(true);
        Locale.setDefault(Locale.US);
        assertEquals("1,234,567", ExactCounts.format(1_234_567L));
        assertEquals("2,500,000,000", ExactCounts.format(2_500_000_000L));
        assertEquals("999", ExactCounts.format(999L));
        Locale.setDefault(Locale.GERMANY);
        assertEquals("1.234.567", ExactCounts.format(1_234_567L));
    }

    @Test public void aNegativeCountStaysTikToksToTurnIntoZero() {
        Settings.SHOW_EXACT_COUNTS.save(true);
        assertNull(ExactCounts.format(-1L));
    }
}
