package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/** The installed avatar action must explain a consumed press that cannot save anything. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ProfileAvatarPermissionTest {
    private final CountDownLatch release = new CountDownLatch(1);
    private ActivityController<Activity> owner;
    private View avatar;

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_PROFILE_PICTURE.save(true);
        owner = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = owner.get();
        LinearLayout header = new LinearLayout(activity);
        TextView handle = new TextView(activity);
        handle.setText("@dancer");
        header.addView(handle);
        avatar = new View(activity);
        header.addView(avatar);
        activity.setContentView(header);
        ProfileAvatarSaver.recordProfileResponse(new ProfileResponse());
        ProfileAvatarSaver.attachAvatar(avatar);

        // Occupy the real workers so a wrongly submitted download stays observable in the
        // queue instead of failing quickly on the network and making the assertion pass.
        assertEquals(0, MediaJobScheduler.queuedJobs());
        assertEquals(0, MediaJobScheduler.runningJobs());
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            boolean submitted = MediaJobScheduler.submit("permission test hold", () -> {
                started.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(submitted);
        }
        assertTrue("the media workers never started", started.await(5, TimeUnit.SECONDS));
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        release.countDown();
        for (int wait = 0; wait < 500
                && (MediaJobScheduler.runningJobs() != 0 || MediaJobScheduler.queuedJobs() != 0);
             wait++) {
            Thread.sleep(20);
        }
        Settings.SAVE_PROFILE_PICTURE.save(false);
        SettingsStatus.advancedDownloadsEnabled = false;
        if (owner != null) owner.close();
        assertEquals("a test media worker was left running", 0, MediaJobScheduler.runningJobs());
        assertEquals("a test download was left queued", 0, MediaJobScheduler.queuedJobs());
    }

    @Test public void deniedStorageExplainsThePressAndGrantQueuesTheSameAvatar() {
        assertDeniedThenGranted();
    }

    @Test @Config(sdk = 23)
    public void androidSixAlsoExplainsTheDeniedAvatarPress() {
        assertDeniedThenGranted();
    }

    @Test @Config(sdk = 29)
    public void scopedStorageDoesNotRequireTheLegacyPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertTrue(avatar.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertAcknowledgedOnly();
        assertEquals("the scoped-storage save was not submitted", 1, MediaJobScheduler.queuedJobs());
    }

    /**
     * Every accepted save is said at once, with the wait it faces; a waiting save is only taken
     * out of line, never cut off; a second press names the waiting one; and once the waiting
     * save is cancelled its hold on the avatar is let go exactly once, so the same press works.
     */
    @Test @Config(sdk = 29)
    public void aWaitingPictureCanBeCancelledAndAskedForAgain() throws Exception {
        assertTrue(avatar.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertAcknowledgedOnly();
        java.util.concurrent.atomic.AtomicBoolean running =
                org.robolectric.util.ReflectionHelpers.getStaticField(ProfileAvatarSaver.class, "RUNNING");
        assertTrue(running.get());

        ShadowToast.reset();
        assertTrue(avatar.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("The last one is still waiting to start", ShadowToast.getTextOfLatestToast());
        assertEquals("a second press queued a second picture", 1, MediaJobScheduler.queuedJobs());

        MediaJobScheduler.Job waiting = MediaJobScheduler.job("profile picture");
        assertNotNull(waiting);
        assertTrue(waiting.cancel());
        assertFalse("cancelling kept the avatar held", running.get());
        assertEquals(0, MediaJobScheduler.queuedJobs());
        assertFalse(waiting.cancel());
        assertFalse(running.get());

        ShadowToast.reset();
        assertTrue(avatar.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertAcknowledgedOnly();
        assertEquals("the same picture could not be asked for again", 1, MediaJobScheduler.queuedJobs());
        assertTrue(running.get());
    }

    /** Behind the three held workers, the one toast is the acknowledgement with its wait. */
    private static void assertAcknowledgedOnly() {
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals("Saving the profile picture\nStarts after one other save", ShadowToast.getTextOfLatestToast());
    }

    private void assertDeniedThenGranted() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertEquals(PackageManager.PERMISSION_DENIED,
                avatar.getContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
        assertTrue("the installed avatar listener did not consume the press", avatar.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Allow storage for TikTok in Android settings to save profile pictures", ShadowToast.getTextOfLatestToast());
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals("denied storage still submitted media work", 0, MediaJobScheduler.queuedJobs());
        assertEquals(MediaJobScheduler.MAX_RUNNING_JOBS, MediaJobScheduler.runningJobs());

        ShadowToast.reset();
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertTrue(avatar.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // No error now; the one word is the save being taken.
        assertAcknowledgedOnly();
        assertEquals("the same avatar did not reach scheduling after permission was granted",
                1, MediaJobScheduler.queuedJobs());
    }

    public static final class ProfileResponse {
        public final Account user = new Account();
    }

    public static final class Account {
        public final String uniqueId = "dancer";
        public final Address avatar300 = new Address();
    }

    public static final class Address {
        public final List<String> urlList = List.of("https://127.0.0.1:1/avatar.jpg");
    }
}
