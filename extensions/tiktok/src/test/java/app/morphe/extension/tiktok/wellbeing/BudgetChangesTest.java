package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What counts as loosening the budget, when a loosening that waited applies, and how a restore
 * is held to the same rule.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BudgetChangesTest {
    private static final long HOUR = 60 * 60_000L;

    private final AtomicLong now = new AtomicLong();
    private TimeZone zone;

    @Before public void setUp() throws Exception {
        zone = TimeZone.getDefault();
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        for (Setting<?> setting : BudgetChanges.heldByALockedDay()) setting.resetToDefault();
        Settings.SESSION_BUDGET_PENDING.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Settings.SESSION_BUDGET_CUE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 21, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        BudgetChanges.resetForTests();
    }

    @After public void tearDown() throws Exception {
        TimeZone.setDefault(zone);
        SessionBudget.forgetTheDay();
        for (Setting<?> setting : BudgetChanges.heldByALockedDay()) setting.resetToDefault();
        Settings.SESSION_BUDGET_PENDING.resetToDefault();
        Settings.SESSION_BUDGET_CUE.resetToDefault();
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        BudgetChanges.resetForTests();
    }

    @Test public void aCountLoosensByGoingUpOrToNoneAndNeverFromNone() {
        for (Setting<?> count : new Setting<?>[]{Settings.SESSION_BUDGET_VIDEOS,
                Settings.SESSION_BUDGET_MINUTES, Settings.SESSION_BUDGET_PASSES_PER_DAY}) {
            assertTrue(count.key, BudgetChanges.loosens(count, 20, 30));
            assertTrue(count.key + " to none", BudgetChanges.loosens(count, 20, 0));
            assertFalse(count.key, BudgetChanges.loosens(count, 20, 10));
            assertFalse(count.key + " from none", BudgetChanges.loosens(count, 0, 10));
            assertFalse(count.key, BudgetChanges.loosens(count, 20, 20));
            assertFalse(count.key, BudgetChanges.loosens(count, 0, 0));
        }
    }

    @Test public void theHoldLoosensByGettingShorterAndTheSwitchesByGoingOff() {
        assertTrue(BudgetChanges.loosens(Settings.SESSION_BUDGET_LOCK_MINUTES, 10, 5));
        assertTrue(BudgetChanges.loosens(Settings.SESSION_BUDGET_LOCK_MINUTES, 10, 0));
        assertFalse(BudgetChanges.loosens(Settings.SESSION_BUDGET_LOCK_MINUTES, 10, 15));
        assertFalse(BudgetChanges.loosens(Settings.SESSION_BUDGET_LOCK_MINUTES, 0, 5));
        for (Setting<?> toggle : new Setting<?>[]{Settings.SESSION_BUDGET_LOCK,
                Settings.SESSION_BUDGET_WAIT_TO_LOOSEN}) {
            assertTrue(toggle.key, BudgetChanges.loosens(toggle, true, false));
            assertFalse(toggle.key, BudgetChanges.loosens(toggle, false, true));
            assertFalse(toggle.key, BudgetChanges.loosens(toggle, true, true));
        }
    }

    /** Either way: a later hour stretches today, an earlier one can start tomorrow right now. */
    @Test public void anyNewHourForTheDayToStartLoosens() {
        assertTrue(BudgetChanges.loosens(Settings.SESSION_BUDGET_RESET_HOUR, 4, 6));
        assertTrue(BudgetChanges.loosens(Settings.SESSION_BUDGET_RESET_HOUR, 4, 2));
        assertFalse(BudgetChanges.loosens(Settings.SESSION_BUDGET_RESET_HOUR, 4, 4));
    }

    @Test public void theRemindersAndEverythingElseAreNotTheBudget() {
        assertFalse(BudgetChanges.loosens(Settings.SESSION_BUDGET_NOTICE_MINUTES, 10, 0));
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_CUE.save(true);
        assertFalse(BudgetChanges.waits(Settings.SESSION_BUDGET_CUE, false));
        assertFalse(BudgetChanges.waits(Settings.SESSION_BUDGET_NOTICE_MINUTES, 0));
    }

    @Test public void nothingWaitsWithTheSwitchOff() {
        Settings.SESSION_BUDGET_MINUTES.save(30);
        assertFalse(BudgetChanges.waits(Settings.SESSION_BUDGET_MINUTES, 60));
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        assertTrue(BudgetChanges.waits(Settings.SESSION_BUDGET_MINUTES, 60));
        assertFalse("a tightening waited", BudgetChanges.waits(Settings.SESSION_BUDGET_MINUTES, 20));
    }

    @Test public void aLooseningKeptAtNineAtNightAppliesAtTheResetHourAndNotBefore() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(30);

        long applies = BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());

        assertEquals("not the next four in the morning",
                at(2026, Calendar.SEPTEMBER, 8, 4, 0), applies);
        assertEquals(applies, BudgetChanges.appliesAt());
        assertEquals(60, BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        assertEquals("the loosening applied at once", 30, (int) Settings.SESSION_BUDGET_MINUTES.get());

        BudgetChanges.applyDue(applies - 60_000L);
        assertEquals("applied a minute early", 30, (int) Settings.SESSION_BUDGET_MINUTES.get());

        BudgetChanges.applyDue(applies);
        assertEquals("not applied when the day started over", 60, (int) Settings.SESSION_BUDGET_MINUTES.get());
        assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        assertEquals(0, BudgetChanges.appliesAt());
    }

    @Test public void aLooseningKeptAfterMidnightAppliesThatSameMorning() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_RESET_HOUR.save(6);
        long applies = BudgetChanges.keep(Settings.SESSION_BUDGET_VIDEOS, 40,
                at(2026, Calendar.SEPTEMBER, 8, 1, 30));
        assertEquals(at(2026, Calendar.SEPTEMBER, 8, 6, 0), applies);

        long onTheHour = BudgetChanges.keep(Settings.SESSION_BUDGET_VIDEOS, 50,
                at(2026, Calendar.SEPTEMBER, 8, 6, 0));
        assertEquals("a change made as the day starts waited no time at all",
                at(2026, Calendar.SEPTEMBER, 9, 6, 0), onTheHour);
    }

    /** The night the clocks go back has 25 hours, and the change waits for four on the new clock. */
    @Test public void theNightTheClocksGoBackWaitsForTheResetHourOnTheNewClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        SessionBudget.forgetTheDay();
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        long evening = at(2026, Calendar.OCTOBER, 31, 22, 0);

        long applies = BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, evening);

        assertEquals(at(2026, Calendar.NOVEMBER, 1, 4, 0), applies);
        assertEquals("the extra hour was lost", 7 * HOUR, applies - evening);
    }

    @Test public void theDayStartingOverAppliesWhatWaitedBeforeTheBudgetIsRead() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        BudgetChanges.keep(Settings.SESSION_BUDGET_VIDEOS, 3, now.get());
        SessionBudget.noteVideo("tonight");
        assertTrue("one video did not spend a one video budget", SessionBudget.claimNotice());

        now.set(at(2026, Calendar.SEPTEMBER, 8, 4, 1));
        SessionBudget.noteVideo("morning-one");
        SessionBudget.noteVideo("morning-two");

        assertEquals("the waiting budget never arrived", 3, (int) Settings.SESSION_BUDGET_VIDEOS.get());
        assertEquals(2, SessionBudget.videosSeen());
        assertFalse("two videos spent a budget of three", SessionBudget.claimNotice());
    }

    @Test public void aNewChangeNeverPushesBackOneAlreadyDue() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(30);
        Settings.SESSION_BUDGET_VIDEOS.save(20);
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());

        long later = BudgetChanges.keep(Settings.SESSION_BUDGET_VIDEOS, 50,
                at(2026, Calendar.SEPTEMBER, 8, 5, 0));

        assertEquals("the due change was held another day", 60, (int) Settings.SESSION_BUDGET_MINUTES.get());
        assertEquals(at(2026, Calendar.SEPTEMBER, 9, 4, 0), later);
        assertEquals(50, BudgetChanges.waiting(Settings.SESSION_BUDGET_VIDEOS));
    }

    @Test public void choosingAgainDropsWhatWaited() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        BudgetChanges.keep(Settings.SESSION_BUDGET_VIDEOS, 60, now.get());

        BudgetChanges.forget(Settings.SESSION_BUDGET_MINUTES);
        assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        assertTrue("the other change stopped waiting", BudgetChanges.appliesAt() > 0);

        BudgetChanges.forget(Settings.SESSION_BUDGET_VIDEOS);
        assertEquals(0, BudgetChanges.appliesAt());
        assertEquals("", Settings.SESSION_BUDGET_PENDING.get());
    }

    @Test public void aFailedScheduleDoesNotClaimATimeOrChangeItsCache() throws Exception {
        try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(keys -> true, false)) {
            assertEquals("an unsaved change was reported as scheduled", 0,
                    BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get()));
            assertEquals(0, BudgetChanges.appliesAt());
            assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        }
        assertTrue(BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get()) > 0);
    }

    @Test public void aFailedDueCommitKeepsTheWaitingChangesForRetry() throws Exception {
        Settings.SESSION_BUDGET_MINUTES.save(30);
        long at = BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        String pending = Settings.SESSION_BUDGET_PENDING.savedValue();
        try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(
                keys -> keys.contains(Settings.SESSION_BUDGET_MINUTES.key), false)) {
            BudgetChanges.applyDue(at);
            assertEquals(30, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
            assertEquals("the waiting record was discarded before the value committed", pending,
                    Settings.SESSION_BUDGET_PENDING.savedValue());
            assertEquals(at, BudgetChanges.appliesAt());
        }
        BudgetChanges.applyDue(at);
        assertEquals(60, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
        assertEquals("", Settings.SESSION_BUDGET_PENDING.savedValue());
    }

    @Test public void aNewChangeCannotPostponeADueChangeWhoseWriteStillFails() throws Exception {
        Settings.SESSION_BUDGET_MINUTES.save(30);
        long at = BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        String pending = Settings.SESSION_BUDGET_PENDING.savedValue();
        try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(
                keys -> keys.contains(Settings.SESSION_BUDGET_MINUTES.key), false)) {
            assertEquals("a new request hid an overdue failed write", 0,
                    BudgetChanges.keep(Settings.SESSION_BUDGET_VIDEOS, 50, at + 60_000L));
            assertEquals(pending, Settings.SESSION_BUDGET_PENDING.savedValue());
            assertEquals(at, BudgetChanges.appliesAt());
        }
        BudgetChanges.applyDue(at + 60_000L);
        assertEquals(60, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
    }

    /** What waits is read back from storage, so it can only ever set the budget's own settings. */
    @Test public void aStoredChangeOutsideTheBudgetIsNeverApplied() throws Exception {
        long past = at(2026, Calendar.SEPTEMBER, 7, 4, 0);
        Settings.SESSION_BUDGET_PENDING.save(new JSONObject().put("at", past).put("values",
                new JSONObject().put(Settings.SESSION_BUDGET_CUE.key, true)).toString());
        BudgetChanges.resetForTests();

        BudgetChanges.applyDue(now.get());

        assertFalse("a stored change reached a setting outside the budget", Settings.SESSION_BUDGET_CUE.get());
        assertEquals(0, BudgetChanges.appliesAt());
        assertEquals("", Settings.SESSION_BUDGET_PENDING.get());
    }

    /** A value the setting refuses is dropped with the record, not tried again on every count. */
    @Test public void aStoredValueTheSettingRefusesIsDroppedNotRetried() throws Exception {
        long past = at(2026, Calendar.SEPTEMBER, 7, 4, 0);
        Settings.SESSION_BUDGET_PENDING.save(new JSONObject().put("at", past).put("values",
                new JSONObject().put(Settings.SESSION_BUDGET_MINUTES.key, "sixty")).toString());
        BudgetChanges.resetForTests();

        BudgetChanges.applyDue(now.get());

        assertEquals(0, (int) Settings.SESSION_BUDGET_MINUTES.get());
        assertEquals("a refused change stayed to be tried on every count", 0, BudgetChanges.appliesAt());
        assertEquals("", Settings.SESSION_BUDGET_PENDING.get());
    }

    @Test public void aRestoreKeepsWhatLoosensForTheNextDayAndAppliesTheRest() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(30);
        Settings.SESSION_BUDGET_VIDEOS.save(20);
        Map<Setting<?>, Object> incoming = new LinkedHashMap<>();
        incoming.put(Settings.SESSION_BUDGET_MINUTES, 60);
        incoming.put(Settings.SESSION_BUDGET_VIDEOS, 10);
        incoming.put(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN, false);
        incoming.put(Settings.SESSION_BUDGET_CUE, true);

        BudgetChanges.Split split = BudgetChanges.forRestore(incoming, now.get());

        assertFalse(split.apply.containsKey(Settings.SESSION_BUDGET_MINUTES));
        assertFalse(split.apply.containsKey(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN));
        assertEquals(10, split.apply.get(Settings.SESSION_BUDGET_VIDEOS));
        assertEquals(true, split.apply.get(Settings.SESSION_BUDGET_CUE));
        assertNull("recorded before the restore's own writes", BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));

        split.keepWaiting();
        assertEquals(60, BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        assertEquals(false, BudgetChanges.waiting(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN));
        assertEquals(at(2026, Calendar.SEPTEMBER, 8, 4, 0), BudgetChanges.appliesAt());
    }

    /** Undo writes back what the restore found, which loosens nothing and settles every row. */
    @Test public void aRestoreThatLoosensNothingDropsWhatWasWaiting() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(30);
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        Map<Setting<?>, Object> incoming = new LinkedHashMap<>();
        incoming.put(Settings.SESSION_BUDGET_MINUTES, 30);

        BudgetChanges.forRestore(incoming, now.get()).keepWaiting();

        assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        assertEquals(0, BudgetChanges.appliesAt());
    }

    @Test public void withTheSwitchOffARestoreAppliesEverything() {
        Settings.SESSION_BUDGET_MINUTES.save(30);
        Map<Setting<?>, Object> incoming = new LinkedHashMap<>();
        incoming.put(Settings.SESSION_BUDGET_MINUTES, 60);
        BudgetChanges.Split split = BudgetChanges.forRestore(incoming, now.get());
        assertEquals(60, split.apply.get(Settings.SESSION_BUDGET_MINUTES));
        split.keepWaiting();
        assertEquals(0, BudgetChanges.appliesAt());
    }

    @Test public void aRestoreOnALockedDayLeavesEveryBudgetSettingAsItIs() {
        Settings.SESSION_BUDGET_LOCK.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        SessionBudget.noteVideo("only-one");
        assertTrue(SessionBudget.claimNotice());
        assertTrue("the day did not lock", SessionBudget.lockedToday());
        Map<Setting<?>, Object> incoming = new LinkedHashMap<>();
        for (Setting<?> setting : BudgetChanges.heldByALockedDay()) incoming.put(setting, setting.defaultValue);
        incoming.put(Settings.SESSION_BUDGET_CUE, true);

        BudgetChanges.Split split = BudgetChanges.forRestore(incoming, now.get());

        for (Setting<?> setting : BudgetChanges.heldByALockedDay()) {
            assertFalse("a locked day let a restore set " + setting.key, split.apply.containsKey(setting));
        }
        assertEquals("the rest of the restore was dropped too", true, split.apply.get(Settings.SESSION_BUDGET_CUE));
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
