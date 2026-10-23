package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * What the settings screen does with a locked day.
 *
 * <p>The model refuses to give a locked day back, but the model is not what anyone touches. If
 * the rows that make up the budget can still be edited, the commitment is two taps deep, and a
 * commitment anyone can edit their way out of in two taps is a suggestion.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class BudgetLockSettingsTest {
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
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_RESET_HOUR.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 12, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        statuses.clear();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
    }

    @Test public void everyRowTheBudgetIsMadeOfIsRefusedOnALockedDay() {
        lockTodayOut();
        PreferenceScreen screen = playbackRows();

        String[] keys = {Settings.SESSION_BUDGET_VIDEOS.key, Settings.SESSION_BUDGET_MINUTES.key,
                Settings.SESSION_BUDGET_LOCK_MINUTES.key, Settings.SESSION_BUDGET_RESET_HOUR.key,
                Settings.SESSION_BUDGET_LOCK.key};
        for (String key : keys) {
            Preference row = screen.findPreference(key);
            assertNotNull("no row for " + key, row);
            assertNotNull("nothing is guarding " + key, row.getOnPreferenceChangeListener());
            assertFalse("a locked day accepted an edit to " + key,
                    row.getOnPreferenceChangeListener().onPreferenceChange(row, "500"));
        }

        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("nothing was said about the refusal", toast);
        assertTrue("the refusal does not say when the day ends: " + toast,
                toast.contains(SessionLockOverlay.resetTimeLabel()));
    }

    @Test public void theSwitchGoesOffFreelyRightUpUntilTheBudgetRunsOut() {
        Settings.SESSION_BUDGET_LOCK.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(2);
        SessionBudget.noteVideo("one");

        PreferenceScreen screen = playbackRows();
        Preference lock = screen.findPreference(Settings.SESSION_BUDGET_LOCK.key);
        assertNotNull(lock);
        assertNotNull("nothing is guarding the switch at all", lock.getOnPreferenceChangeListener());
        assertTrue("the switch was already stuck before the budget ran out",
                lock.getOnPreferenceChangeListener().onPreferenceChange(lock, false));
    }

    @Test public void startTodayOverIsRefusedOnALockedDayAndSaysWhy() {
        lockTodayOut();
        PreferenceScreen screen = playbackRows();
        Preference startOver = screen.findPreference("action_start_today_over");
        assertNotNull("Start today over is missing", startOver);

        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);

        assertTrue("Start today over cleared a locked day", SessionBudget.lockedToday());
        assertEquals("the day was cleared anyway", 1, SessionBudget.videosSeen());
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("nothing was said about the refusal", toast);
        assertTrue("the refusal does not say when the day starts over: " + toast,
                toast.contains(SessionLockOverlay.resetTimeLabel()));
    }

    @Test public void anUnlockedDayStillStartsOver() {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        SessionBudget.noteVideo("only-one");
        assertTrue(SessionBudget.claimNotice());

        PreferenceScreen screen = playbackRows();
        Preference startOver = screen.findPreference("action_start_today_over");
        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);

        assertEquals("Start today over stopped working without a lock",
                0, SessionBudget.videosSeen());
    }

    @Test public void startingTodayOverIsTakenBackByTheNextTap() {
        // One tap, no dialog, because the next tap puts it back. The row used to make that
        // bargain without keeping it: a mis-tap lifted a running hold and left nothing behind.
        Settings.SESSION_BUDGET_VIDEOS.save(2);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("one");
        SessionBudget.noteVideo("two");
        assertTrue("the budget was not reached", SessionBudget.claimNotice());
        assertTrue("no hold was placed", SessionBudget.isLocked());

        PreferenceScreen screen = playbackRows();
        Preference startOver = screen.findPreference("action_start_today_over");
        assertNotNull("Start today over is missing", startOver);
        assertTrue("the row does not offer the way back before it is used",
                startOver.getSummary().toString().contains("left alone"));

        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);
        assertEquals("the day was not cleared", 0, SessionBudget.videosSeen());
        assertFalse("the hold outlived the clear", SessionBudget.isLocked());
        assertTrue("the row does not say the tap can be taken back: " + startOver.getSummary(),
                startOver.getSummary().toString().contains("Tap again"));

        startOver.getOnPreferenceClickListener().onPreferenceClick(startOver);
        assertEquals("the counts did not come back", 2, SessionBudget.videosSeen());
        assertTrue("the hold did not come back", SessionBudget.isLocked());
        assertTrue("the row still offers a way back it has already used: " + startOver.getSummary(),
                startOver.getSummary().toString().contains("left alone"));
    }

    @Test public void aClearedDayCannotBeTakenBackOnceTheDayHasTurned() {
        Settings.SESSION_BUDGET_VIDEOS.save(2);
        SessionBudget.noteVideo("one");
        assertTrue(SessionBudget.clear());
        assertTrue("nothing was kept to put back", SessionBudget.canUndoClear());

        now.set(at(2026, Calendar.SEPTEMBER, 8, 4, 1));
        assertFalse("yesterday's counts were still on offer", SessionBudget.canUndoClear());
        assertFalse("yesterday's counts came back onto a new day", SessionBudget.undoClear());
        assertEquals("the new day did not start empty", 0, SessionBudget.videosSeen());
    }

    /**
     * A budget row refused while the day is locked used to accept the tap, close the dialog and
     * then say no over the settings screen. Nothing was saved, so the reader had to work out
     * from a toast that the number they had just typed had gone.
     */
    @Test public void aBudgetRowRefusedWhileTheDayIsLockedKeepsItsDialogOpen() throws Exception {
        lockTodayOut();
        PreferenceScreen screen = playbackRows();
        Preference row = screen.findPreference(Settings.SESSION_BUDGET_MINUTES.key);
        assertNotNull("the minutes row is not on the screen", row);

        app.morphe.extension.tiktok.settings.preference.InputCheckTest.openDialog(row);
        android.app.AlertDialog dialog =
                (android.app.AlertDialog) ((android.preference.DialogPreference) row).getDialog();
        assertNotNull("the dialog did not open", dialog);
        android.widget.EditText box =
                ((android.preference.EditTextPreference) row).getEditText();
        box.setText("45");

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the dialog closed on a change the locked day refused", dialog.isShowing());
        assertEquals("what was typed was thrown away", "45", box.getText().toString());
        assertTrue("a locked row saved anyway", Settings.SESSION_BUDGET_MINUTES.get() != 45);
        // The reason, where the reader is looking. The settings banner sits behind the dialog,
        // so a refusal told only there left Save looking dead.
        assertTrue("the dialog doesn't say why Save did nothing",
                textIn(dialog.getWindow().getDecorView()).contains("Today's budget is locked"));
    }

    private static String textIn(android.view.View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof android.widget.TextView && view.getVisibility() == android.view.View.VISIBLE) {
            text.append(((android.widget.TextView) view).getText()).append('\n');
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) text.append(textIn(group.getChildAt(index)));
        }
        return text.toString();
    }

    /** Spends a one video budget with the lock on, which commits the day. */
    private void lockTodayOut() {
        Settings.SESSION_BUDGET_LOCK.save(true);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        SessionBudget.noteVideo("only-one");
        assertTrue("the budget was not reached", SessionBudget.claimNotice());
        assertTrue("the day did not lock", SessionBudget.lockedToday());
    }

    /** A preference fragment only so the framework will hand out a PreferenceScreen. */
    public static class HostFragment extends android.preference.PreferenceFragment {
    }

    private static PreferenceScreen playbackRows() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostFragment fragment = new HostFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        PreferenceScreen screen = fragment.getPreferenceManager().createPreferenceScreen(activity);
        fragment.setPreferenceScreen(screen);
        // The category adds itself to the screen and its rows to itself.
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
