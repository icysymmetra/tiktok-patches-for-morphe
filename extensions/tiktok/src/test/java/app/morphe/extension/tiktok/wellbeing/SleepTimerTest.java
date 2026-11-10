/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.os.SystemClock;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.interaction.GestureActions;
import app.morphe.extension.tiktok.settings.Settings;

import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

/** The sleep timer from the Long press action, with closing TikTok counted instead of done. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SleepTimerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long MINUTE = 60_000L;
    private int closes;

    @Before public void setUp() {
        closes = 0;
        SleepTimer.setCloserForTests(() -> closes++);
    }

    @After public void tearDown() {
        SleepTimer.resetForTests();
        Settings.LONG_PRESS_ACTION.resetToDefault();
        Settings.DOUBLE_TAP_ACTION.resetToDefault();
        Settings.EDGE_SEEK.resetToDefault();
    }

    private static void idleMinutes(long minutes) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(minutes));
    }

    @Test public void theTimerClosesTikTokWhenItRunsOutAndNotBefore() {
        ShadowToast.reset();
        SleepTimer.start(15);

        assertTrue(SleepTimer.isRunning());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().startsWith("TikTok closes at "));
        idleMinutes(14);
        assertEquals(0, closes);
        idleMinutes(1);
        assertEquals(1, closes);
        assertFalse(SleepTimer.isRunning());
        idleMinutes(60);
        assertEquals("it closes once", 1, closes);
    }

    @Test public void aNewLengthReplacesTheRunningTimer() {
        SleepTimer.start(30);
        idleMinutes(10);
        SleepTimer.start(15);

        idleMinutes(14);
        assertEquals("the first timer's end no longer counts", 0, closes);
        idleMinutes(1);
        assertEquals(1, closes);
        idleMinutes(30);
        assertEquals(1, closes);
    }

    @Test public void turningItOffMeansNothingCloses() {
        SleepTimer.start(15);
        SleepTimer.cancel();

        idleMinutes(20);
        assertEquals(0, closes);
        assertFalse(SleepTimer.isRunning());
    }

    @Test public void aTimerThePhoneSleptThroughLeavesTikTokOpen() {
        long started = SystemClock.elapsedRealtime();
        SleepTimer.start(15);
        SleepTimer.check(started + 15 * MINUTE + SleepTimer.LATE_MS + 1);
        assertEquals("due long after its end: the phone slept and nothing was playing", 0, closes);
        assertFalse(SleepTimer.isRunning());

        started = SystemClock.elapsedRealtime();
        SleepTimer.start(15);
        SleepTimer.check(started + 16 * MINUTE);
        assertEquals("a minute late still closes", 1, closes);
    }

    @Test public void itLooksEveryHalfMinuteSoASleepPartWayCanOnlyDelayItThatMuch() {
        SleepTimer.start(60);
        long wait = shadowOf(Looper.getMainLooper()).getNextScheduledTaskTime().toMillis() - SystemClock.uptimeMillis();
        assertTrue("waits " + wait + " ms before its first look", wait > 0 && wait <= SleepTimer.STEP_MS);

        // Thirty awake minutes and five asleep: the end-time clock ran on while the looper's
        // stood still, so the look that follows reads five minutes more than the looper waited.
        long started = SystemClock.elapsedRealtime();
        SleepTimer.check(started + 35 * MINUTE);
        assertTrue(SleepTimer.isRunning());
        wait = shadowOf(Looper.getMainLooper()).getNextScheduledTaskTime().toMillis() - SystemClock.uptimeMillis();
        assertTrue("waits " + wait + " ms after a sleep", wait > 0 && wait <= SleepTimer.STEP_MS);
        SleepTimer.check(started + 60 * MINUTE + SleepTimer.STEP_MS);
        assertEquals("half a minute past its end still closes", 1, closes);
    }

    @Test public void anEarlyWakeWaitsOutTheRest() {
        long started = SystemClock.elapsedRealtime();
        SleepTimer.start(15);
        SleepTimer.check(started + 5 * MINUTE);
        assertEquals(0, closes);
        assertTrue(SleepTimer.isRunning());

        idleMinutes(14);
        assertEquals(0, closes);
        idleMinutes(1);
        assertEquals(1, closes);
    }

    @Test public void theLongPressOpensThePickerAndItStartsAndStopsTheTimer() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);
            Settings.EDGE_SEEK.save(false);
            Settings.LONG_PRESS_ACTION.save("sleep_timer");
            assertTrue(GestureActions.takesLongPress("sleep_timer"));

            assertTrue("the press is the timer's, not TikTok's", GestureActions.onLongPress(0f));
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(picker);
            CharSequence[] items = shadowOf(picker).getItems();
            assertEquals(5, items.length);
            assertEquals("15 minutes", items[0].toString());
            assertEquals("90 minutes", items[4].toString());
            shadowOf(picker).clickOnItem(1);
            assertTrue(SleepTimer.isRunning());

            assertTrue(GestureActions.onLongPress(0f));
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
            CharSequence[] withOff = shadowOf(again).getItems();
            assertEquals("turning it off comes first", 6, withOff.length);
            assertTrue(withOff[0].toString(), withOff[0].toString().startsWith("Turn off the timer (TikTok closes at "));
            ShadowToast.reset();
            shadowOf(again).clickOnItem(0);
            assertFalse(SleepTimer.isRunning());
            assertEquals("Sleep timer off", ShadowToast.getTextOfLatestToast());

            idleMinutes(40);
            assertEquals(0, closes);
        }
    }

    /**
     * #136: a double tap set to the timer opens the same picker. It's posted, as from the long
     * press, so it opens once the tap that asked for it is done with the touch.
     */
    @Test public void theDoubleTapOpensThePickerOnceTheTapIsDone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);
            Settings.DOUBLE_TAP_ACTION.save("sleep_timer");
            AlertDialog before = ShadowAlertDialog.getLatestAlertDialog();

            assertTrue("the tap is the timer's, not TikTok's like", GestureActions.onDoubleTap());
            assertSame("the picker opened under the tap", before, ShadowAlertDialog.getLatestAlertDialog());
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(picker);
            assertNotSame(before, picker);
            shadowOf(picker).clickOnItem(0);
            assertTrue(SleepTimer.isRunning());
        }
    }
}
