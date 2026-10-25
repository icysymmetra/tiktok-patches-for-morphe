/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.QuickSetups;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Back to defaults goes through the screen-time budget the way a restore does: a locked day keeps
 * its switches as they are, and with Wait a day to loosen on, a looser switch waits for the next
 * day. What waits is still a change the banner counts and Undo takes back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QuickSetupsHoldToTheBudgetTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final AtomicLong now = new AtomicLong();
    private Context context;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Settings.SESSION_BUDGET_PENDING.resetToDefault();
        Calendar noon = Calendar.getInstance();
        noon.set(2026, Calendar.SEPTEMBER, 22, 12, 0, 0);
        now.set(noon.getTimeInMillis());
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        BudgetChanges.resetForTests();
    }

    @After public void tearDown() throws Exception {
        Settings.HIDE_STORY.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.resetToDefault();
        Settings.SESSION_BUDGET_PENDING.resetToDefault();
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        BudgetChanges.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
    }

    /** Built per test: the settings can't load before the rule has given them a context. */
    private static Collection<Setting<?>> onPages() {
        return Arrays.asList(Settings.HIDE_STORY, Settings.SESSION_BUDGET_LOCK,
                Settings.SESSION_BUDGET_WAIT_TO_LOOSEN);
    }

    @Test public void aLockedDayKeepsItsSwitchesThroughBackToDefaultsAndUndo() throws Exception {
        Settings.HIDE_STORY.save(!Settings.HIDE_STORY.defaultValue);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK.save(true);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());
        assertTrue("the day did not lock", SessionBudget.lockedToday());

        QuickSetups.Result applied = QuickSetups.apply(context, QuickSetups.Choice.DEFAULTS, onPages());

        assertEquals(Settings.HIDE_STORY.defaultValue, Settings.HIDE_STORY.get());
        assertTrue("Back to defaults turned the lock off on a locked day", Settings.SESSION_BUDGET_LOCK.get());
        assertNull("the locked day's switch was put off until tomorrow instead",
                BudgetChanges.waiting(Settings.SESSION_BUDGET_LOCK));
        assertEquals("the count includes a switch the day held", 1, applied.changedCount);

        QuickSetups.undo(context, applied);
        assertEquals(!Settings.HIDE_STORY.defaultValue, Settings.HIDE_STORY.get());
        assertTrue(Settings.SESSION_BUDGET_LOCK.get());
    }

    @Test public void aLooserSwitchWaitsCountsAsAChangeAndUndoCancelsIt() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(10);
        Settings.SESSION_BUDGET_LOCK.save(true);
        Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.save(true);
        assertFalse(SessionBudget.lockedToday());

        QuickSetups.Result applied = QuickSetups.apply(context, QuickSetups.Choice.DEFAULTS, onPages());

        assertTrue("the lock came off today", Settings.SESSION_BUDGET_LOCK.get());
        assertTrue("Wait a day to loosen came off today", Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.get());
        assertEquals(Boolean.FALSE, BudgetChanges.waiting(Settings.SESSION_BUDGET_LOCK));
        assertEquals(Boolean.FALSE, BudgetChanges.waiting(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN));
        assertEquals("two changes wait for tomorrow and the banner would say nothing changed",
                2, applied.changedCount);
        assertEquals("a change that waits for tomorrow asked for a restart now", 0, applied.restartChangedCount);

        assertEquals("a second tap counted the waiting changes again", 0,
                QuickSetups.apply(context, QuickSetups.Choice.DEFAULTS, onPages()).changedCount);

        QuickSetups.Result undone = QuickSetups.undo(context, applied);
        assertEquals("Undo didn't redraw both rows", 2, undone.changedCount);
        assertEquals("cancelling a wait asked for a restart, though no saved value moved",
                0, undone.restartChangedCount);
        assertNull("Undo left the lock to come off tomorrow", BudgetChanges.waiting(Settings.SESSION_BUDGET_LOCK));
        assertNull("Undo left Wait a day to loosen to come off tomorrow",
                BudgetChanges.waiting(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN));
        assertTrue(Settings.SESSION_BUDGET_LOCK.get());
        assertTrue(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.get());
    }
}
