package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** TikTok's "Videos you might like" pushes are dropped by default, and nothing else is. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SuggestedVideoPushBlockTest {
    private static final String SUGGESTED = "recommend_video_push_associated_4";
    private static final String MESSAGES = "im_push_associated_4";
    private static final String FOLLOWED_VIDEOS = "follow_new_video_push_associated_4";

    private Context context;
    private NotificationManager manager;
    private boolean patchedBefore;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG.save(false);
        // The filter is shared with Notification controls, so this check counts only in a build
        // carrying this patch, which these tests are.
        patchedBefore = SettingsStatus.suggestedVideoPushBlockEnabled;
        SettingsStatus.suggestedVideoPushBlockEnabled = true;
        manager = context.getSystemService(NotificationManager.class);
        for (String id : new String[]{SUGGESTED, MESSAGES, FOLLOWED_VIDEOS}) {
            manager.createNotificationChannel(new NotificationChannel(id, id, NotificationManager.IMPORTANCE_HIGH));
        }
    }

    @After
    public void tearDown() {
        Settings.BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS.resetToDefault();
        SettingsStatus.suggestedVideoPushBlockEnabled = patchedBefore;
    }

    @Test
    public void suggestedVideosAreBlockedFromTheStart() {
        assertEquals(Boolean.TRUE, Settings.BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS.defaultValue);
        SuggestedVideoPushBlock.notify(manager, "app_notify_ame", 1, on(SUGGESTED));
        SuggestedVideoPushBlock.notify(manager, 2, on(SUGGESTED));
        assertEquals("a suggested video reached the drawer", 0, posted());
    }

    @Test
    public void everythingElseIsPostedAsAsked() {
        SuggestedVideoPushBlock.notify(manager, "app_notify_ame", 1, on(MESSAGES));
        SuggestedVideoPushBlock.notify(manager, 2, on(FOLLOWED_VIDEOS));
        SuggestedVideoPushBlock.notify(manager, "app_notify_ame", 3, new Notification.Builder(context, "")
                .setSmallIcon(android.R.drawable.ic_dialog_info).build());
        assertEquals(3, posted());
    }

    @Test
    public void turningTheSwitchOffLetsThemThrough() {
        Settings.BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS.save(false);
        SuggestedVideoPushBlock.notify(manager, "app_notify_ame", 1, on(SUGGESTED));
        assertEquals(1, posted());
    }

    @Test
    public void withoutThisPatchItsDefaultDoesNothing() {
        // Notification controls brings the same filter in on its own, and this switch starts on.
        SettingsStatus.suggestedVideoPushBlockEnabled = false;
        SuggestedVideoPushBlock.notify(manager, "app_notify_ame", 1, on(SUGGESTED));
        assertEquals(1, posted());
    }

    @Test
    public void onlyTheChannelsStartIsCompared() {
        // TikTok bumps the suffix now and then; a channel that merely mentions the name isn't it.
        assertTrue(SuggestedVideoPushBlock.shouldBlock(on("recommend_video_push_associated_9")));
        assertFalse(SuggestedVideoPushBlock.shouldBlock(on("not_recommend_video_push")));
        assertFalse(SuggestedVideoPushBlock.shouldBlock(null));
    }

    private Notification on(String channel) {
        return new Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Good afternoon").build();
    }

    private int posted() {
        return Shadows.shadowOf(manager).getAllNotifications().size();
    }
}
