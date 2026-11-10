package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.preference.DialogPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.InputCheckTest;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * What the Screen time page does with Wait a day to loosen the budget: a loosening typed into a
 * row is kept for the next day and the row says what it becomes and when, a tightening applies
 * at once, and Start today over is off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class BudgetWaitSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final AtomicLong now = new AtomicLong();
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
        for (Setting<?> setting : BudgetChanges.heldByALockedDay()) setting.resetToDefault();
        Settings.SESSION_BUDGET_PENDING.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 21, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        BudgetChanges.resetForTests();
        ShadowToast.reset();
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        Settings.SESSION_BUDGET_MINUTES.save(30);
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        statuses.clear();
        for (Setting<?> setting : BudgetChanges.heldByALockedDay()) setting.resetToDefault();
        Settings.SESSION_BUDGET_PENDING.resetToDefault();
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        BudgetChanges.resetForTests();
    }

    @Test public void aLooseningTypedIntoARowWaitsAndTheRowSaysWhenItApplies() throws Exception {
        PreferenceScreen screen = savingPage();
        Preference row = screen.findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        AlertDialog dialog = typeAndSave(row, "60");

        assertFalse("the dialog stayed open over a change that was taken", dialog.isShowing());
        assertEquals("the loosening applied at once", 30, (int) Settings.SESSION_BUDGET_MINUTES.get());
        assertEquals(60, BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        String applies = SessionLockOverlay.timeLabel(at(2026, Calendar.SEPTEMBER, 8, 4, 0));
        String summary = row.getSummary().toString();
        assertTrue("the row does not say what waits: " + summary,
                summary.contains("Changes to 60 minutes at " + applies));
        assertTrue("the row lost its current value: " + summary, summary.contains("Current: 30 minutes"));
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("nothing was said about the change waiting", toast);
        assertTrue("the toast does not say when: " + toast, toast.contains(applies));
    }

    @Test public void aTighteningAppliesAtOnceAndDropsWhatWaited() throws Exception {
        PreferenceScreen screen = savingPage();
        Preference row = screen.findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        typeAndSave(row, "60");

        AlertDialog dialog = typeAndSave(row, "20");

        assertFalse(dialog.isShowing());
        assertEquals(20, (int) Settings.SESSION_BUDGET_MINUTES.get());
        assertNull("the looser number is still waiting", BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        assertFalse("the row still names a change that is gone: " + row.getSummary(),
                row.getSummary().toString().contains("Changes to"));
    }

    @Test public void withTheSwitchOffALooseningAppliesAtOnce() throws Exception {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(false);
        PreferenceScreen screen = savingPage();
        typeAndSave(screen.findPreference(Settings.SESSION_BUDGET_MINUTES.key), "60");

        assertEquals(60, (int) Settings.SESSION_BUDGET_MINUTES.get());
        assertEquals(0, BudgetChanges.appliesAt());
    }

    @Test public void aFailedPendingWriteKeepsTheNumberDialogOpenForRetry() throws Exception {
        Preference row = savingPage().findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        AlertDialog dialog;
        try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(keys -> true, false)) {
            dialog = typeAndSave(row, "60");
            assertTrue("an unsaved change closed its editor", dialog.isShowing());
            assertEquals("60", ((EditTextPreference) row).getEditText().getText().toString());
            assertSaveFailureIsVisible(dialog);
            assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertEquals(60, BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
    }

    @Test public void aFailedPendingRemovalCannotLetATighteningAppearSaved() throws Exception {
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        Preference row = savingPage().findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        AlertDialog dialog;
        try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(
                keys -> keys.contains(Settings.SESSION_BUDGET_PENDING.key), false)) {
            dialog = typeAndSave(row, "20");
            assertTrue("the old loosening still waits, so the tightening must be refused", dialog.isShowing());
            assertEquals(30, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
            assertEquals(60, BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
            assertSaveFailureIsVisible(dialog);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertEquals(20, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
        assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
    }

    @Test public void aFailedTighteningWriteKeepsItsCurrentValueAndPendingChangeForRetry() throws Exception {
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        String pending = Settings.SESSION_BUDGET_PENDING.savedValue();
        Preference row = savingPage().findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        AlertDialog dialog;
        try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(
                keys -> keys.contains(Settings.SESSION_BUDGET_MINUTES.key), false)) {
            dialog = typeAndSave(row, "20");
            assertEquals(30, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
            assertEquals("the failed replacement discarded tomorrow's change", 60,
                    BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
            assertEquals(pending, Settings.SESSION_BUDGET_PENDING.savedValue());
            assertTrue("the failed replacement closed its editor", dialog.isShowing());
            assertEquals("20", ((EditTextPreference) row).getEditText().getText().toString());
            assertSaveFailureIsVisible(dialog);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertEquals(20, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
        assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
        assertFalse(row.getSummary().toString().contains("Changes to"));
    }

    @Test public void savingADefaultNumberDoesNotWriteItBackOrCancelOtherWaitingChanges() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(10);
        long at = BudgetChanges.keep(Settings.SESSION_BUDGET_VIDEOS, 20, now.get());
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(false);
        Preference otherRow = savingPage().findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        Preference row = savingPage().findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        SharedPreferences preferences = Setting.preferences.preferences;
        AtomicInteger changes = new AtomicInteger();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (store, key) -> {
            if (Settings.SESSION_BUDGET_MINUTES.key.equals(key)) changes.incrementAndGet();
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
        try {
            AlertDialog dialog = typeAndSave(row, "0");

            assertFalse(dialog.isShowing());
            assertEquals(0, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
            assertFalse("refreshing the row wrote an explicit default back to storage",
                    preferences.contains(Settings.SESSION_BUDGET_MINUTES.key));
            assertEquals("the save caused a second persistence change", 1, changes.get());
            assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_MINUTES));
            assertEquals(20, BudgetChanges.waiting(Settings.SESSION_BUDGET_VIDEOS));
            assertEquals(at, BudgetChanges.appliesAt());
            assertTrue(row.getSummary().toString().contains("Current: Off"));
            assertTrue(otherRow.getSummary().toString().contains("Current: Off"));
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    @Test public void anAtomicallySavedNumberStillExplainsItsRangeAdjustment() throws Exception {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(false);
        Preference row = savingPage().findPreference(Settings.SESSION_BUDGET_MINUTES.key);

        AlertDialog dialog = typeAndSave(row, "5000");

        assertFalse(dialog.isShowing());
        assertEquals(600, (int) Settings.SESSION_BUDGET_MINUTES.savedValue());
        assertEquals("Kept to 600, the nearest value this row allows", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aConsumedNumberSaveLeavesLockingToTheLockSwitch() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(2);
        Settings.SESSION_BUDGET_LOCK.save(true);
        SessionBudget.noteVideo("one");
        PreferenceScreen screen = savingPage();

        AlertDialog dialog = typeAndSave(screen.findPreference(Settings.SESSION_BUDGET_VIDEOS.key), "1");

        assertFalse(dialog.isShowing());
        assertEquals(1, (int) Settings.SESSION_BUDGET_VIDEOS.savedValue());
        assertFalse("lowering a number locked a day that had not been spent", SessionBudget.lockedToday());
        Preference lock = screen.findPreference(Settings.SESSION_BUDGET_LOCK.key);
        assertTrue("the lock switch lost its accepted-change path",
                lock.getOnPreferenceChangeListener().onPreferenceChange(lock, true));
        assertTrue("the switch did not lock the spent day", SessionBudget.lockedToday());
    }

    private static void assertSaveFailureIsVisible(AlertDialog dialog) {
        android.widget.TextView error = dialog.getWindow().getDecorView()
                .findViewWithTag("hushfeed_field_error");
        assertNotNull("the failed save has no explanation", error);
        assertEquals(android.view.View.VISIBLE, error.getVisibility());
        assertTrue(error.getText().toString(), error.getText().toString().contains("Couldn't save"));
    }

    @Test public void aLongerHoldAppliesAndAShorterOneWaits() throws Exception {
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(10);
        PreferenceScreen screen = savingPage();
        Preference row = screen.findPreference(Settings.SESSION_BUDGET_LOCK_MINUTES.key);

        typeAndSave(row, "15");
        assertEquals(15, (int) Settings.SESSION_BUDGET_LOCK_MINUTES.get());

        typeAndSave(row, "5");
        assertEquals(15, (int) Settings.SESSION_BUDGET_LOCK_MINUTES.get());
        assertTrue("the hold row does not say what waits: " + row.getSummary(),
                row.getSummary().toString().contains("Changes to 5 minutes at"));
    }

    @Test public void turningTheLockOffWaitsAndTheSwitchSaysWhen() {
        Settings.SESSION_BUDGET_LOCK.save(true);
        PreferenceScreen screen = screenTimeRows();
        Preference lock = screen.findPreference(Settings.SESSION_BUDGET_LOCK.key);

        assertFalse("the switch was let go off",
                lock.getOnPreferenceChangeListener().onPreferenceChange(lock, false));

        assertTrue("the lock went off at once", Settings.SESSION_BUDGET_LOCK.get());
        assertEquals(false, BudgetChanges.waiting(Settings.SESSION_BUDGET_LOCK));
        String applies = SessionLockOverlay.timeLabel(at(2026, Calendar.SEPTEMBER, 8, 4, 0));
        assertTrue("the switch does not say when it goes off: " + lock.getSummary(),
                lock.getSummary().toString().contains("Turns off at " + applies));
    }

    @Test public void turningTheFeedLockOffWaitsAndOnAgainTakesItBack() {
        Settings.FEED_LOCK.save(true);
        PreferenceScreen screen = screenTimeRows();
        Preference lock = screen.findPreference(Settings.FEED_LOCK.key);
        assertNotNull("the feed lock row is not on the page", lock);

        assertFalse("the feed lock was let go off",
                lock.getOnPreferenceChangeListener().onPreferenceChange(lock, false));
        assertTrue("the feed lock went off at once", Settings.FEED_LOCK.get());
        assertEquals(false, BudgetChanges.waiting(Settings.FEED_LOCK));
        String applies = SessionLockOverlay.timeLabel(at(2026, Calendar.SEPTEMBER, 8, 4, 0));
        assertTrue("the row does not say when it goes off: " + lock.getSummary(),
                lock.getSummary().toString().contains("Turns off at " + applies));
        assertTrue(ShadowToast.getTextOfLatestToast() == null
                || !ShadowToast.getTextOfLatestToast().contains("budget"));

        assertTrue(lock.getOnPreferenceChangeListener().onPreferenceChange(lock, true));
        assertNull("the wait outlived turning it back on", BudgetChanges.waiting(Settings.FEED_LOCK));

        // Applied as the day starts over, like every other waiting change.
        lock.getOnPreferenceChangeListener().onPreferenceChange(lock, false);
        now.set(at(2026, Calendar.SEPTEMBER, 8, 4, 1));
        BudgetChanges.applyDue(now.get());
        assertFalse("the feed lock stayed on past the day", Settings.FEED_LOCK.savedValue());
    }

    @Test public void aLockedDayRefusesTurningTheFeedLockOffButNotOn() {
        Settings.SESSION_BUDGET_MINUTES.save(1);
        Settings.SESSION_BUDGET_LOCK.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        SessionBudget.noteVideo("only");
        assertTrue(SessionBudget.claimNotice());
        assertTrue("the fixture's day never locked", SessionBudget.lockedToday());
        PreferenceScreen screen = screenTimeRows();
        Preference lock = screen.findPreference(Settings.FEED_LOCK.key);

        assertTrue("a locked day refused tightening", lock.getOnPreferenceChangeListener()
                .onPreferenceChange(lock, true));
        Settings.FEED_LOCK.save(true);
        assertFalse("a locked day let the feed lock go",
                lock.getOnPreferenceChangeListener().onPreferenceChange(lock, false));
        assertTrue(Settings.FEED_LOCK.get());
        assertNull("a refused change was kept", BudgetChanges.waiting(Settings.FEED_LOCK));
    }

    @Test public void turningTheSwitchItselfOffWaitsAndOnAgainTakesItBack() {
        PreferenceScreen screen = screenTimeRows();
        Preference wait = screen.findPreference(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.key);
        assertNotNull("the switch is not on the page", wait);

        wait.getOnPreferenceChangeListener().onPreferenceChange(wait, false);
        assertTrue("the switch went off at once", Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.get());
        assertTrue(wait.getSummary().toString().contains("Turns off at"));

        assertTrue(wait.getOnPreferenceChangeListener().onPreferenceChange(wait, true));
        assertNull(BudgetChanges.waiting(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN));
        assertFalse("the switch still says it turns off: " + wait.getSummary(),
                wait.getSummary().toString().contains("Turns off at"));
    }

    @Test public void aPageOpenedAfterTheResetShowsTheChangeApplied() {
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        PreferenceScreen tonight = screenTimeRows();
        assertTrue(tonight.findPreference(Settings.SESSION_BUDGET_MINUTES.key).getSummary().toString()
                .contains("Changes to 60 minutes"));

        now.set(at(2026, Calendar.SEPTEMBER, 8, 4, 1));
        PreferenceScreen morning = screenTimeRows();

        Preference row = morning.findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        assertEquals(60, (int) Settings.SESSION_BUDGET_MINUTES.get());
        String summary = row.getSummary().toString();
        assertTrue(summary, summary.contains("Current: 60 minutes"));
        assertFalse(summary, summary.contains("Changes to"));
    }

    /**
     * Start today over redraws the page it was pressed on. On the S22 the row's "Today" line
     * kept the old count until the page was left and opened again, so the reset looked as if
     * it hadn't worked. Taking it back redraws the page too. The real settings page, since it
     * moves the category's rows onto itself.
     */
    @Test public void startTodayOverRedrawsTheCountsOnTheOpenPage() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(false);
        Settings.SESSION_BUDGET_VIDEOS.save(5);
        SessionBudget.noteVideo("one");
        SessionBudget.noteVideo("two");
        PreferenceScreen screen = savingPage();
        Preference videos = screen.findPreference(Settings.SESSION_BUDGET_VIDEOS.key);
        assertTrue(videos.getSummary().toString(), videos.getSummary().toString().contains("Today: 2 videos"));
        Preference startOver = screen.findPreference("action_start_today_over");

        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);
        assertEquals(0, SessionBudget.videosSeen());
        assertTrue("the open page still shows the old count: " + videos.getSummary(),
                videos.getSummary().toString().contains("Today: 0 videos"));

        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);
        assertTrue("taking it back left the page at zero: " + videos.getSummary(),
                videos.getSummary().toString().contains("Today: 2 videos"));
    }

    /**
     * A page left open while the phone sleeps through the day's start shows what the start
     * changed once the phone wakes. On the S22 the Wait a day switch still read on after 4:00,
     * and a waiting number still read as waiting, until the page was opened again. Seven hours
     * pass on the clock and only a minute of uptime, as they do on a phone asleep.
     */
    @Test public void anOpenPageShowsWhatTheDayStartChanged() {
        BudgetChanges.keep(Settings.SESSION_BUDGET_MINUTES, 60, now.get());
        PreferenceScreen screen = savingPage();
        Preference minutes = screen.findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        Preference wait = screen.findPreference(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.key);
        wait.getOnPreferenceChangeListener().onPreferenceChange(wait, false);
        assertTrue(minutes.getSummary().toString().contains("Changes to 60 minutes"));
        assertTrue(((android.preference.TwoStatePreference) wait).isChecked());

        now.set(at(2026, Calendar.SEPTEMBER, 8, 4, 1));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61));

        String summary = minutes.getSummary().toString();
        assertTrue("the open page kept the evening's value: " + summary, summary.contains("Current: 60 minutes"));
        assertFalse(summary, summary.contains("Changes to"));
        assertFalse("the open page still shows Wait a day on",
                ((android.preference.TwoStatePreference) wait).isChecked());
        assertFalse(wait.getSummary().toString().contains("Turns off at"));
    }

    /** A clear can only be taken back the day it was made, so the row stops offering it after. */
    @Test public void startTodayOverStopsOfferingYesterdaysCountsBack() {
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(false);
        Settings.SESSION_BUDGET_VIDEOS.save(5);
        SessionBudget.noteVideo("one");
        PreferenceScreen screen = savingPage();
        Preference startOver = screen.findPreference("action_start_today_over");
        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);
        assertTrue(startOver.getSummary().toString(), startOver.getSummary().toString().contains("Tap again"));

        now.set(at(2026, Calendar.SEPTEMBER, 8, 4, 1));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61));

        assertFalse("the row still offers yesterday's counts back: " + startOver.getSummary(),
                startOver.getSummary().toString().contains("Tap again"));
    }
    @Test public void startTodayOverIsOffWhileLooseningWaits() {
        Settings.SESSION_BUDGET_VIDEOS.save(2);
        SessionBudget.noteVideo("one");
        PreferenceScreen screen = screenTimeRows();
        Preference startOver = screen.findPreference("action_start_today_over");

        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);

        assertEquals("today started over anyway", 1, SessionBudget.videosSeen());
        String notice = ShadowToast.getTextOfLatestToast();
        assertNotNull("nothing was said about the refusal", notice);
        assertTrue(notice, notice.contains("can't start over"));
        assertTrue("the refusal does not say when the day starts over: " + notice,
                notice.contains(SessionLockOverlay.timeLabel(at(2026, Calendar.SEPTEMBER, 8, 4, 0))));
    }

    /** Types {@code text} into a number row's dialog and presses Save. */
    private static AlertDialog typeAndSave(Preference row, String text) throws Exception {
        assertNotNull("the row is not on the page", row);
        InputCheckTest.openDialog(row);
        AlertDialog dialog = (AlertDialog) ((DialogPreference) row).getDialog();
        assertNotNull("the dialog did not open", dialog);
        ((EditTextPreference) row).getEditText().setText(text);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return dialog;
    }

    /**
     * The real settings page on the Screen time section. A value the page takes is saved through
     * the settings store only there: a bare fragment keeps its own preferences file.
     */
    private static PreferenceScreen savingPage() {
        Activity activity = Robolectric.buildActivity(
                app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class)
                .setup().visible().get();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "SCREEN_TIME");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame("the settings page and its writes must share the same store",
                Setting.preferences.preferences,
                fragment.getPreferenceManager().getSharedPreferences());
        return fragment.getPreferenceScreen();
    }

    /** A preference fragment only so the framework will hand out a PreferenceScreen. */
    public static class HostFragment extends android.preference.PreferenceFragment {
    }

    private static PreferenceScreen screenTimeRows() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostFragment fragment = new HostFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        PreferenceScreen screen = fragment.getPreferenceManager().createPreferenceScreen(activity);
        fragment.setPreferenceScreen(screen);
        new ScreenTimePreferenceCategory(activity, screen);
        return screen;
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
