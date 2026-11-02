/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pausing takes the screen-time budget off with everything else, so a hold that was running
 * covers nothing while Hushfeed is paused and is still there when it comes back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PauseLiftsTheHoldTest {
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
        PausedProcess.set(false);
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_RESET_HOUR.resetToDefault();
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
    }

    @Test public void aRunningHoldCoversNothingWhilePausedAndComesBackAfter() {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(60);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());
        assertTrue("the hold did not start", SessionBudget.isLocked());

        PausedProcess.set(true);
        assertFalse("paused, the hold still covered the feed", SessionBudget.isLocked());
        assertEquals(0L, SessionBudget.lockRemainingMs());

        PausedProcess.set(false);
        assertTrue("the hold did not come back with Metra", SessionBudget.isLocked());
        assertTrue(SessionBudget.lockRemainingMs() > 0);
    }

    @Test public void aVideoChangeWhilePausedLeavesTheHoldInTheRecord() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(60);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());
        SessionBudget.awaitWritesForTests();
        String record = Settings.SESSION_BUDGET_STATE.savedValue();

        // Every video change asks for the notice. Paused, both budgets read zero through get(),
        // which looked like a raised budget, and the hold was wiped out of the saved record.
        PausedProcess.set(true);
        assertFalse(SessionBudget.claimNotice());
        SessionBudget.awaitWritesForTests();
        assertEquals("a paused video change rewrote the budget record", record, Settings.SESSION_BUDGET_STATE.savedValue());

        PausedProcess.set(false);
        assertTrue("the hold was gone when Metra came back", SessionBudget.isLocked());
        assertFalse("the notice came back for a budget already spent", SessionBudget.claimNotice());
    }

    @Test public void aLockTurnedOnWhilePausedTakesASpentDay() {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.reachedLimit());

        // The settings screen shows the reader's budgets while paused, so the lock it offers has
        // to judge the day by them: read through get(), a budget of zero was never spent.
        PausedProcess.set(true);
        assertTrue("the lock did not take a day that was already spent", SessionBudget.lockIfSpent());
        assertTrue(SessionBudget.lockedToday());

        PausedProcess.set(false);
        assertTrue(SessionBudget.isLocked());
    }

    @Test public void thePausedBudgetStillKnowsWhichDayItIs() {
        // The day is worked out from the reader's reset hour. Read through get() while paused,
        // that hour would answer the default and a paused start between the two hours would
        // start a new day early, zeroing counts the reader still had to spend.
        Settings.SESSION_BUDGET_RESET_HOUR.save(8);
        PausedProcess.set(true);
        assertEquals(8, (int) Settings.SESSION_BUDGET_RESET_HOUR.get());
        Calendar six = Calendar.getInstance();
        six.set(2026, Calendar.SEPTEMBER, 23, 6, 0, 0);
        Calendar lateYesterday = Calendar.getInstance();
        lateYesterday.set(2026, Calendar.SEPTEMBER, 22, 23, 0, 0);
        assertEquals("six in the morning is still last night before an eight o'clock reset",
                SessionBudget.dayOf(lateYesterday.getTimeInMillis()), SessionBudget.dayOf(six.getTimeInMillis()));
    }
}
