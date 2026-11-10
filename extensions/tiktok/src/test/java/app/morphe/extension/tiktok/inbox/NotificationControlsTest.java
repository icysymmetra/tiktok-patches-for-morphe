package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Which notifications are dropped, and which are none of our business. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NotificationControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    /** The push message's extra, which is where the channel is named. */
    public static final class Extra {
        public String notificationChannelId;
        Extra(String channel) { notificationChannelId = channel; }
    }

    public static final class Push {
        public Extra extra;
        Push(String channel) { extra = channel == null ? null : new Extra(channel); }
    }

    @Test public void onlyTheFollowerChannelIsDroppedAndOnlyWhenAskedTo() {
        try {
            Push follower = new Push("follow_push");
            Push message = new Push("im_push");
            Push comment = new Push("comment_push");

            // Off: every notification goes out, the follower one included.
            Settings.HIDE_FOLLOWER_NOTIFICATIONS.save(false);
            for (Push push : new Push[]{follower, message, comment}) {
                assertFalse(NotificationControls.shouldDropPush(push));
            }

            Settings.HIDE_FOLLOWER_NOTIFICATIONS.save(true);
            assertTrue(NotificationControls.shouldDropPush(follower));
            // Everything else in the drawer is untouched.
            assertFalse(NotificationControls.shouldDropPush(message));
            assertFalse(NotificationControls.shouldDropPush(comment));

            // A push shaped in a way we do not recognise is posted rather than swallowed.
            assertFalse(NotificationControls.shouldDropPush(new Push(null)));
            assertFalse(NotificationControls.shouldDropPush(new Push("")));
            assertFalse(NotificationControls.shouldDropPush(new Object()));
            assertFalse(NotificationControls.shouldDropPush(null));
        } finally {
            Settings.HIDE_FOLLOWER_NOTIFICATIONS.save(false);
        }
    }

    @Test public void theStreakSwitchIsWhatTheGettersAsk() {
        try {
            Settings.HIDE_MESSAGE_STREAKS.save(false);
            assertFalse(NotificationControls.hideMessageStreaks());
            Settings.HIDE_MESSAGE_STREAKS.save(true);
            assertTrue(NotificationControls.hideMessageStreaks());
        } finally {
            Settings.HIDE_MESSAGE_STREAKS.save(false);
        }
    }

    @Test public void bothSwitchesAreReachableOnTheirOwn() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            // The patch on its own, with no other Inbox patch installed.
            SettingsStatus.notificationControlsEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("hide_follower_notifications"));
            assertNotNull(screen.findPreference("hide_message_streaks"));
        } finally {
            SettingsStatus.notificationControlsEnabled = false;
        }
    }
}
