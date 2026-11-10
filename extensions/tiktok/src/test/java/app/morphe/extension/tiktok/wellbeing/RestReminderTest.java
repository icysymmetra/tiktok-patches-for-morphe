package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The "Leave when TikTok says time is up" switch: TikTok's own daily screen-time reminder
 * backgrounds the app once, and coming straight back leaves the reminder on screen so the
 * limit and the switch itself stay reachable while the day's limit is tripped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class RestReminderTest {
    public static final class HostActivity extends Activity {
        final List<Boolean> movedBack = new ArrayList<>();
        boolean canMoveBack = true;

        @Override public boolean moveTaskToBack(boolean nonRoot) {
            movedBack.add(nonRoot);
            return canMoveBack;
        }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.LEAVE_ON_REST_REMINDER.resetToDefault();
        RestReminder.resetForTests();
    }

    @After public void tearDown() {
        Utils.setActivity(null);
        Settings.LEAVE_ON_REST_REMINDER.resetToDefault();
        RestReminder.resetForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private HostActivity host() {
        HostActivity activity = Robolectric.buildActivity(HostActivity.class).setup().get();
        Utils.setActivity(activity);
        return activity;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void theSwitchOffLeavesTheReminderAlone() {
        HostActivity activity = host();
        RestReminder.reminderShown();
        idle();
        assertEquals(List.of(), activity.movedBack);
    }

    @Test public void theReminderSendsTikTokToTheBackground() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        RestReminder.reminderShown();
        idle();
        assertEquals("one moveTaskToBack(true)", List.of(true), activity.movedBack);
    }

    @Test public void comingStraightBackKeepsTheReminderOnScreen() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        RestReminder.reminderShown();
        idle();
        // The reminder is rebuilt on the way back in; leaving again would lock the app shut.
        RestReminder.reminderShown();
        idle();
        assertEquals(List.of(true), activity.movedBack);
    }

    @Test public void aShowAfterTheQuietSpellLeavesAgain() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        RestReminder.reminderShown();
        idle();
        ShadowSystemClock.advanceBy(RestReminder.LEAVE_AGAIN_AFTER_MS + 1000, TimeUnit.MILLISECONDS);
        RestReminder.reminderShown();
        idle();
        assertEquals(List.of(true, true), activity.movedBack);
    }

    /** The cooldown guard collapses a slot rebuilt twice in one frame to one leave. */
    @Test public void twoShowsInOneFrameLeaveOnce() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        RestReminder.reminderShown();
        RestReminder.reminderShown();
        idle();
        assertEquals(List.of(true), activity.movedBack);
    }

    @Test public void noActivityIsQuietAndTheNextShowStillLeaves() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        RestReminder.reminderShown();
        idle();
        HostActivity activity = host();
        RestReminder.reminderShown();
        idle();
        assertEquals("the null-activity show must not arm the guard", List.of(true), activity.movedBack);
    }

    @Test public void aTaskThatCannotGoBackDoesNotArmTheGuard() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        activity.canMoveBack = false;
        RestReminder.reminderShown();
        idle();
        RestReminder.reminderShown();
        idle();
        assertTrue("a refused move must be tried again on the next show",
                activity.movedBack.size() == 2);
    }

    @Test public void aQueuedReminderRespectsTheSwitchBeingTurnedOff() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        RestReminder.reminderShown();
        Settings.LEAVE_ON_REST_REMINDER.save(false);
        idle();
        assertEquals("the queued reminder ignored the new setting", List.of(), activity.movedBack);

        Settings.LEAVE_ON_REST_REMINDER.save(true);
        RestReminder.reminderShown();
        idle();
        assertEquals("a cancelled leave consumed the cooldown", List.of(true), activity.movedBack);
    }

    @Test public void aQueuedReminderCannotLeaveAReplacementActivity() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity first = host();
        HostActivity replacement = host();
        Utils.setActivity(first);
        RestReminder.reminderShown();
        Utils.setActivity(replacement);
        idle();
        assertEquals(List.of(), first.movedBack);
        assertEquals("an old reminder acted on the replacement activity", List.of(), replacement.movedBack);

        RestReminder.reminderShown();
        idle();
        assertEquals(List.of(true), replacement.movedBack);
    }

    @Test public void aQueuedReminderCannotLeaveAFinishingActivity() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        RestReminder.reminderShown();
        activity.finish();
        idle();
        assertEquals("the old activity was already finishing", List.of(), activity.movedBack);
    }

    @Test public void aQueuedReminderRespectsThePausedProcess() {
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        HostActivity activity = host();
        RestReminder.reminderShown();
        app.morphe.extension.shared.settings.PausedProcess.set(true);
        try {
            idle();
            assertEquals("a queued reminder acted while paused", List.of(), activity.movedBack);
        } finally {
            app.morphe.extension.shared.settings.PausedProcess.set(false);
        }
    }
}
