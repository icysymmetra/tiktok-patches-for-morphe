/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.session.MediaSession;
import android.os.PowerManager;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

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

/**
 * Turn off push notifications: what it holds back, what it leaves alone, and that nothing it
 * does outlasts the switch or Pause.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PushShutoffTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    /** TikTok's message channel, and a tag FCM's message delivery uses. */
    private static final String MESSAGES = "im_push_associated_4";
    private static final String FCM_TAG = "wake:com.google.firebase.messaging";

    private Context context;
    private PowerManager power;
    private NotificationManager manager;
    private boolean controlsBefore;
    private boolean suggestedBefore;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        power = context.getSystemService(PowerManager.class);
        manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(MESSAGES, MESSAGES, NotificationManager.IMPORTANCE_HIGH));
        controlsBefore = SettingsStatus.notificationControlsEnabled;
        suggestedBefore = SettingsStatus.suggestedVideoPushBlockEnabled;
        SettingsStatus.notificationControlsEnabled = true;
        PausedProcess.set(false);
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.resetToDefault();
        SettingsStatus.notificationControlsEnabled = controlsBefore;
        SettingsStatus.suggestedVideoPushBlockEnabled = suggestedBefore;
    }

    @Test
    public void offByDefaultAndEveryCallGoesThroughAsTikTokMadeIt() {
        assertEquals(Boolean.FALSE, Settings.TURN_OFF_PUSH_NOTIFICATIONS.defaultValue);
        assertTrue("push setup waits for a launch, so the row asks for a restart",
                Settings.TURN_OFF_PUSH_NOTIFICATIONS.rebootApp);
        assertFalse(PushShutoff.skipPushSetup());

        PowerManager.WakeLock lock = PushShutoff.newWakeLock(power, PowerManager.PARTIAL_WAKE_LOCK, FCM_TAG);
        PushShutoff.acquire(lock);
        assertTrue(lock.isHeld());
        PushShutoff.release(lock);
        assertFalse(lock.isHeld());

        SuggestedVideoPushBlock.notify(manager, 1, plain());
        SuggestedVideoPushBlock.notify(manager, "app_notify_ame", 2, plain());
        assertEquals(2, posted());
    }

    @Test
    public void onTikToksWakeLocksAreSkippedAndTheirReleasesDontThrow() {
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        assertTrue(PushShutoff.skipPushSetup());

        PowerManager.WakeLock lock = PushShutoff.newWakeLock(power, PowerManager.PARTIAL_WAKE_LOCK, FCM_TAG);
        PushShutoff.acquire(lock);
        assertFalse("an acquire went through", lock.isHeld());
        PushShutoff.acquire(lock, 60_000L);
        assertFalse("a timed acquire went through", lock.isHeld());

        // The releases TikTok makes for those two acquires. Android throws for a reference
        // counted lock released with nothing held, which is what the stand-in is there to stop.
        PushShutoff.release(lock);
        PushShutoff.release(lock, 0);
        assertThrows(RuntimeException.class, lock::release);

        // A lock made somewhere the stand-in never saw has no tag, and is skipped as well.
        PowerManager.WakeLock untagged = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "elsewhere");
        PushShutoff.acquire(untagged);
        assertFalse(untagged.isHeld());
    }

    @Test
    public void aLiveBroadcastAndForegroundWorkKeepTheirLocks() {
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        for (String tag : PushShutoff.KEPT_TAGS) {
            PowerManager.WakeLock lock = PushShutoff.newWakeLock(power, PowerManager.PARTIAL_WAKE_LOCK, tag);
            PushShutoff.acquire(lock, 300_000L);
            assertTrue(tag + " was skipped", lock.isHeld());
            PushShutoff.release(lock);
            assertFalse(tag + " wasn't released", lock.isHeld());
        }
        assertEquals("Live::AnchorWakeLock", PushShutoff.KEPT_TAGS[0]);
        assertEquals("WorkManager: ProcessorForegroundLck", PushShutoff.KEPT_TAGS[1]);
    }

    @Test
    public void aLockHeldBeforeTheSwitchIsStillLetGo() {
        PowerManager.WakeLock lock = PushShutoff.newWakeLock(power, PowerManager.PARTIAL_WAKE_LOCK, FCM_TAG);
        PushShutoff.acquire(lock);
        assertTrue(lock.isHeld());

        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        PushShutoff.acquire(lock);
        // The first release lets go of the real hold, and the one left over answers for the
        // skipped acquire and stops before Android.
        PushShutoff.release(lock);
        assertFalse(lock.isHeld());
        PushShutoff.release(lock);
        assertFalse(lock.isHeld());
    }

    @Test
    public void pauseLetsEverythingThrough() {
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        PowerManager.WakeLock skipped = PushShutoff.newWakeLock(power, PowerManager.PARTIAL_WAKE_LOCK, FCM_TAG);
        PushShutoff.acquire(skipped);
        assertFalse(skipped.isHeld());

        PausedProcess.set(true);
        assertFalse(PushShutoff.skipPushSetup());
        assertFalse(NotificationControls.shouldDropPush(new Object()));
        PowerManager.WakeLock lock = PushShutoff.newWakeLock(power, PowerManager.PARTIAL_WAKE_LOCK, FCM_TAG);
        PushShutoff.acquire(lock);
        assertTrue("paused, a wake lock was still skipped", lock.isHeld());
        PushShutoff.release(lock);
        assertFalse(lock.isHeld());
        // The release owed for the acquire skipped before the pause still stops before Android.
        PushShutoff.release(skipped);

        SuggestedVideoPushBlock.notify(manager, 1, plain());
        assertEquals(1, posted());
    }

    @Test
    public void onNothingButOngoingNotificationsReachesTheDrawer() {
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        SuggestedVideoPushBlock.notify(manager, 1, plain());
        SuggestedVideoPushBlock.notify(manager, "app_notify_ame", 2, plain());
        assertEquals("a push reached the drawer", 0, posted());

        Notification ongoing = builder().setOngoing(true).build();
        SuggestedVideoPushBlock.notify(manager, 3, ongoing);
        Notification service = plain();
        service.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        SuggestedVideoPushBlock.notify(manager, 4, service);
        MediaSession session = new MediaSession(context, "push-shutoff-test");
        try {
            Notification media = builder()
                    .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                    .build();
            assertFalse("media controls posted as ongoing prove nothing here",
                    (media.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            SuggestedVideoPushBlock.notify(manager, 5, media);
        } finally {
            session.release();
        }
        assertEquals(3, posted());

        // Turned off, the same notification goes out again.
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(false);
        SuggestedVideoPushBlock.notify(manager, 6, plain());
        assertEquals(4, posted());
    }

    @Test
    public void thePushHandlerDropsEveryPushWhileOn() {
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        assertTrue(NotificationControls.shouldDropPush(new Object()));
        assertTrue(NotificationControls.shouldDropPush(new NotificationControlsTest.Push("im_push")));
        // A missing message is still posted as TikTok asked, and the drawer filter decides.
        assertFalse(NotificationControls.shouldDropPush(null));
    }

    @Test
    public void aBuildWithoutNotificationControlsIgnoresTheSavedValue() {
        // The notify filter also comes with Block suggested video notifications alone, and a
        // restored backup can carry this switch into such a build.
        SettingsStatus.notificationControlsEnabled = false;
        SettingsStatus.suggestedVideoPushBlockEnabled = true;
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        assertFalse(PushShutoff.skipPushSetup());
        SuggestedVideoPushBlock.notify(manager, 1, plain());
        assertEquals(1, posted());
        PowerManager.WakeLock lock = PushShutoff.newWakeLock(power, PowerManager.PARTIAL_WAKE_LOCK, FCM_TAG);
        PushShutoff.acquire(lock);
        assertTrue(lock.isHeld());
        PushShutoff.release(lock);
    }

    @Test
    public void theSwitchHasItsRowUnderInbox() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("turn_off_push_notifications"));
        }
    }

    private Notification.Builder builder() {
        return new Notification.Builder(context, MESSAGES).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("New message");
    }

    private Notification plain() {
        return builder().build();
    }

    private int posted() {
        return Shadows.shadowOf(manager).getAllNotifications().size();
    }
}
