/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.preference.Preference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsPagesTest;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pausing takes the screen-time budget off with everything else, so a day the reader locked
 * refuses the Pause switch the way it refuses every budget setting, and so does Wait a day to
 * loosen the budget.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PauseRefusesALockedDayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final AtomicLong now = new AtomicLong();

    @Before public void setUp() throws Exception {
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Calendar noon = Calendar.getInstance();
        noon.set(2026, Calendar.SEPTEMBER, 22, 12, 0, 0);
        now.set(noon.getTimeInMillis());
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
    }

    @After public void tearDown() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
    }

    private static Preference pauseRow(android.app.Activity activity) {
        Utils.setContext(activity);
        // Pause Hushfeed is on the home page, in the Hushfeed group.
        TikTokPreferenceFragment home = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, home).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return home.getPreferenceScreen().findPreference(BaseSettings.PAUSED.key);
    }

    @Test public void aLockedDayRefusesPauseAndAnOpenDayAllowsIt() {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            Preference row = pauseRow(owner.get());
            assertTrue("an ordinary day refused Pause", row.getOnPreferenceChangeListener().onPreferenceChange(row, Boolean.TRUE));

            Settings.SESSION_BUDGET_VIDEOS.save(1);
            Settings.SESSION_BUDGET_LOCK.save(true);
            SessionBudget.noteVideo("a");
            assertTrue(SessionBudget.claimNotice());
            assertTrue("the day did not lock", SessionBudget.lockedToday());

            assertFalse("a locked day let Pause turn the budget off", row.getOnPreferenceChangeListener().onPreferenceChange(row, Boolean.TRUE));
            assertTrue("turning Pause off is never refused", row.getOnPreferenceChangeListener().onPreferenceChange(row, Boolean.FALSE));
        }
    }

    /**
     * Paused from the next start, the budget would be gone at once, which is the one thing Wait
     * a day to loosen the budget is there to stop. Start today over is refused the same way.
     */
    @Test public void waitingToLoosenRefusesPauseToo() {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            Preference row = pauseRow(owner.get());
            Settings.SESSION_BUDGET_VIDEOS.save(10);
            Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
            ShadowToast.reset();

            assertFalse("Pause took the budget off while loosening it has to wait",
                    row.getOnPreferenceChangeListener().onPreferenceChange(row, Boolean.TRUE));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Wait a day to loosen the budget is on, so Metra stays on",
                    ShadowToast.getTextOfLatestToast());
            assertTrue("turning Pause off is never refused",
                    row.getOnPreferenceChangeListener().onPreferenceChange(row, Boolean.FALSE));

            Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(false);
            assertTrue("Pause stayed refused with the switch off",
                    row.getOnPreferenceChangeListener().onPreferenceChange(row, Boolean.TRUE));
        }
    }
}
