/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.view.View;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Auto-advance in search results, as TikTok's search flag reads it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AutoAdvanceSearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum State { AUTO_SCROLL_STATE_START, AUTO_SCROLL_STATE_STOP, AUTO_SCROLL_STATE_PAUSE }

    /** The host's component behind the native bridges, with a state the test can move. */
    @Implements(AutoAdvance.class)
    public static class HostComponent {
        static Object state;
        static int starts;
        static int stops;
        @Implementation protected static Object readState(Object component) { return state; }
        @Implementation protected static void start(Object component) {
            starts++;
            state = State.AUTO_SCROLL_STATE_START;
        }
        @Implementation protected static void stop(Object component) {
            stops++;
            state = State.AUTO_SCROLL_STATE_STOP;
        }
        @Implementation protected static Object readAweme(Object component) { return null; }
    }

    @Before @After public void reset() {
        PausedProcess.set(false);
        Settings.AUTO_ADVANCE.resetToDefault();
        Settings.AUTO_ADVANCE_SEARCH.resetToDefault();
        SessionBudget.clear();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        HostComponent.state = State.AUTO_SCROLL_STATE_STOP;
        HostComponent.starts = 0;
        HostComponent.stops = 0;
    }

    @Test public void offTheServersAnswerGoesThrough() {
        assertEquals(0, AutoAdvance.searchFlag(0));
        assertEquals(1, AutoAdvance.searchFlag(1));
        assertEquals(2, AutoAdvance.searchFlag(2));
    }

    @Test public void bothSwitchesOnTurnTheFlagOn() {
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        assertEquals(1, AutoAdvance.searchFlag(0));
        assertEquals(1, AutoAdvance.searchFlag(2));
    }

    @Test public void theSearchSwitchAloneDoesNothing() {
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        assertEquals("it's a child of Auto-advance videos", 0, AutoAdvance.searchFlag(0));
    }

    @Test public void autoAdvanceAloneLeavesSearchAlone() {
        Settings.AUTO_ADVANCE.save(true);
        assertEquals(0, AutoAdvance.searchFlag(0));
    }

    @Test public void pausingGivesTheServersAnswerBack() {
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        PausedProcess.set(true);
        assertEquals(0, AutoAdvance.searchFlag(0));
    }

    @Test @Config(shadows = HostComponent.class)
    public void aScrollTheHostRestoresOnANewSearchPageAnswersToTheHold() {
        // Backing out of a search page while its scroll runs leaves START remembered, and the
        // next search page's onViewCreated starts itself from that, after the hook has run.
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        holdTheFeed();
        Object component = new Object();
        try {
            AutoAdvance.onView(component, new View(RuntimeEnvironment.getApplication()));
            HostComponent.state = State.AUTO_SCROLL_STATE_START;
            idle();
            assertEquals("a restored search scroll ran on behind the hold", 1, HostComponent.stops);
            assertEquals(State.AUTO_SCROLL_STATE_STOP, HostComponent.state);
        } finally {
            AutoAdvance.onDestroy(component);
        }
    }

    @Test @Config(shadows = HostComponent.class)
    public void aScrollTheHostRestoresWhenItsPageComesBackAnswersToTheHold() {
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        holdTheFeed();
        Object component = new Object();
        try {
            AutoAdvance.onView(component, new View(RuntimeEnvironment.getApplication()));
            idle();
            assertEquals(0, HostComponent.stops);

            AutoAdvance.onPageResume(component);
            HostComponent.state = State.AUTO_SCROLL_STATE_START;
            idle();
            assertEquals("a scroll restored on page resume ran on behind the hold", 1, HostComponent.stops);
        } finally {
            AutoAdvance.onDestroy(component);
        }
    }

    @Test @Config(shadows = HostComponent.class)
    public void aResumeInTheSameMessageAsTheRestoreDoesNotDisarmIt() {
        // A page added to a running activity gets onViewCreated, which restores, and onResume in
        // one message. The resume reads the START the first check is waiting to claim.
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        holdTheFeed();
        Object component = new Object();
        try {
            AutoAdvance.onView(component, new View(RuntimeEnvironment.getApplication()));
            HostComponent.state = State.AUTO_SCROLL_STATE_START;
            AutoAdvance.onResume(component);
            AutoAdvance.onPageResume(component);
            idle();
            assertEquals("the restored scroll was never claimed", 1, HostComponent.stops);
            assertEquals(State.AUTO_SCROLL_STATE_STOP, HostComponent.state);
        } finally {
            AutoAdvance.onDestroy(component);
        }
    }

    @Test @Config(shadows = HostComponent.class)
    public void withoutTheSearchSwitchARestoredScrollStaysTikToks() {
        // Then it's the server's own flag that let search remember it, and a scroll Hushfeed
        // didn't cause is left to TikTok the way it always was.
        Settings.AUTO_ADVANCE.save(true);
        holdTheFeed();
        Object component = new Object();
        try {
            AutoAdvance.onView(component, new View(RuntimeEnvironment.getApplication()));
            HostComponent.state = State.AUTO_SCROLL_STATE_START;
            idle();
            assertEquals(0, HostComponent.stops);
        } finally {
            AutoAdvance.onDestroy(component);
        }
    }

    @Test public void onlyAStartFromAStopInThatOneStepIsClaimed() {
        Settings.AUTO_ADVANCE.save(true);
        var control = new AutoAdvance.Control(new View(RuntimeEnvironment.getApplication()));

        // The host's own resume of a paused scroll is TikTok's, not a restore.
        assertFalse(control.armRestore(true, State.AUTO_SCROLL_STATE_PAUSE));
        assertFalse(control.adoptRestore(State.AUTO_SCROLL_STATE_START));
        assertFalse(control.owned);

        // Nothing restored this time, and the arm is spent once it's looked at.
        assertTrue(control.armRestore(true, State.AUTO_SCROLL_STATE_STOP));
        assertFalse(control.adoptRestore(State.AUTO_SCROLL_STATE_STOP));
        assertFalse(control.adoptRestore(State.AUTO_SCROLL_STATE_START));
        assertFalse(control.owned);

        // A bridge that answers nothing claims nothing.
        assertTrue(control.armRestore(true, State.AUTO_SCROLL_STATE_STOP));
        assertFalse(control.adoptRestore(null));
        assertFalse(control.owned);

        assertFalse("the search switch is off", control.armRestore(false, State.AUTO_SCROLL_STATE_STOP));

        // A second read before the check runs leaves the waiting arm alone.
        assertTrue(control.armRestore(true, State.AUTO_SCROLL_STATE_STOP));
        assertFalse(control.armRestore(true, State.AUTO_SCROLL_STATE_START));
        assertTrue(control.adoptRestore(State.AUTO_SCROLL_STATE_START));
        assertTrue(control.owned);
    }

    private static void holdTheFeed() {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());
        assertTrue("no hold started", SessionBudget.isLocked());
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
