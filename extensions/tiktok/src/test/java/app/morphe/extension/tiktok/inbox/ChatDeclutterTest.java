package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The chat switches: the answers the hooks read, the call buttons, the gestures and the settings rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChatDeclutterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void restore() {
        PausedProcess.set(false);
        Settings.HIDE_CHAT_CALL_BUTTONS.save(false);
        Settings.HIDE_CHAT_STICKER_BANNER.save(false);
        Settings.HIDE_CHAT_AI_REPLIES.save(false);
        Settings.TURN_OFF_CHAT_DOUBLE_TAP.resetToDefault();
        Settings.TURN_OFF_CHAT_SWIPE_REPLY.resetToDefault();
        SettingsStatus.chatDeclutterEnabled = false;
    }

    @Test public void everySwitchIsOffByDefault() {
        assertFalse(Settings.HIDE_CHAT_CALL_BUTTONS.defaultValue);
        assertFalse(Settings.HIDE_CHAT_STICKER_BANNER.defaultValue);
        assertFalse(Settings.HIDE_CHAT_AI_REPLIES.defaultValue);
        assertTrue(InboxControls.shouldShowChatStickerBanner());
        assertTrue(InboxControls.shouldShowChatAiReplies());
    }

    @Test public void theStickerBannerAnswerFollowsItsSwitchOnly() {
        Settings.HIDE_CHAT_STICKER_BANNER.save(true);
        assertFalse(InboxControls.shouldShowChatStickerBanner());
        // The other switches are not this one.
        assertTrue(InboxControls.shouldShowChatAiReplies());
        Settings.HIDE_CHAT_STICKER_BANNER.save(false);
        assertTrue(InboxControls.shouldShowChatStickerBanner());
    }

    @Test public void theSuggestedReplyAnswerFollowsItsSwitchOnly() {
        Settings.HIDE_CHAT_AI_REPLIES.save(true);
        assertFalse(InboxControls.shouldShowChatAiReplies());
        assertTrue(InboxControls.shouldShowChatStickerBanner());
        Settings.HIDE_CHAT_AI_REPLIES.save(false);
        assertTrue(InboxControls.shouldShowChatAiReplies());
    }

    @Test public void pausedEveryChatAnswerIsTikToks() {
        Settings.HIDE_CHAT_STICKER_BANNER.save(true);
        Settings.HIDE_CHAT_AI_REPLIES.save(true);
        Settings.HIDE_CHAT_CALL_BUTTONS.save(true);
        PausedProcess.set(true);
        assertTrue(InboxControls.shouldShowChatStickerBanner());
        assertTrue(InboxControls.shouldShowChatAiReplies());
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            View button = new View(owner.get());
            ChatTitleBar.hideCallButton(button);
            assertEquals(View.VISIBLE, button.getVisibility());
        }
    }

    @Test public void aCallButtonIsHiddenOnlyWhileItsSwitchIsOn() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            View button = new View(owner.get());

            Settings.HIDE_CHAT_CALL_BUTTONS.save(false);
            ChatTitleBar.hideCallButton(button);
            assertEquals("a button was hidden with the switch off", View.VISIBLE, button.getVisibility());

            Settings.HIDE_CHAT_CALL_BUTTONS.save(true);
            ChatTitleBar.hideCallButton(button);
            assertEquals(View.GONE, button.getVisibility());
        }
    }

    @Test public void aButtonTikTokShowsAgainIsPutBackOutOfSightBeforeItDraws() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            FrameLayout root = new FrameLayout(activity);
            View button = new View(activity);
            root.addView(button);
            activity.setContentView(root);

            Settings.HIDE_CHAT_CALL_BUTTONS.save(true);
            ChatTitleBar.hideCallButton(button);
            assertEquals(View.GONE, button.getVisibility());

            // TikTok's state callback shows the button again.
            button.setVisibility(View.VISIBLE);
            assertFalse("the draw went ahead with the button showing",
                    ChatTitleBar.enforce(button));
            assertEquals(View.GONE, button.getVisibility());
            assertTrue(ChatTitleBar.enforce(button));

            // With the switch off the button is left however TikTok has it.
            Settings.HIDE_CHAT_CALL_BUTTONS.save(false);
            button.setVisibility(View.VISIBLE);
            assertTrue(ChatTitleBar.enforce(button));
            assertEquals(View.VISIBLE, button.getVisibility());
        }
    }

    /** The dispatcher's gesture enum, by the constant names it keeps on every declared build. */
    private enum Gesture { SINGLE_TAP, DOUBLE_TAP, LONG_PRESS, SWIPE }

    @Test public void everyGestureGoesThroughWithBothSwitchesOff() {
        assertFalse(Settings.TURN_OFF_CHAT_DOUBLE_TAP.defaultValue);
        assertFalse(Settings.TURN_OFF_CHAT_SWIPE_REPLY.defaultValue);
        for (Gesture gesture : Gesture.values()) assertFalse(gesture.name(), ChatGestures.skip(gesture));
        assertFalse(ChatGestures.skip(null));
    }

    @Test public void eachGestureSwitchStopsItsOwnGestureOnly() {
        Settings.TURN_OFF_CHAT_DOUBLE_TAP.save(true);
        assertTrue(ChatGestures.skip(Gesture.DOUBLE_TAP));
        assertFalse(ChatGestures.skip(Gesture.SWIPE));
        assertFalse(ChatGestures.skip(Gesture.SINGLE_TAP));
        assertFalse(ChatGestures.skip(Gesture.LONG_PRESS));

        Settings.TURN_OFF_CHAT_DOUBLE_TAP.save(false);
        Settings.TURN_OFF_CHAT_SWIPE_REPLY.save(true);
        assertTrue(ChatGestures.skip(Gesture.SWIPE));
        assertFalse(ChatGestures.skip(Gesture.DOUBLE_TAP));
        assertFalse(ChatGestures.skip(Gesture.SINGLE_TAP));
        assertFalse(ChatGestures.skip(Gesture.LONG_PRESS));
    }

    @Test public void pausedEveryGestureGoesThrough() {
        Settings.TURN_OFF_CHAT_DOUBLE_TAP.save(true);
        Settings.TURN_OFF_CHAT_SWIPE_REPLY.save(true);
        PausedProcess.set(true);
        assertFalse(ChatGestures.skip(Gesture.DOUBLE_TAP));
        assertFalse(ChatGestures.skip(Gesture.SWIPE));
    }

    @Test public void aMissingButtonIsSkipped() {
        Settings.HIDE_CHAT_CALL_BUTTONS.save(true);
        ChatTitleBar.hideCallButton(null);
    }

    @Test public void theRowsAreOnThePageOnlyWhenThePatchReportsIn() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.chatDeclutterEnabled = false;
            PreferenceScreen absent = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, absent);
            assertNull(absent.findPreference("hide_chat_call_buttons"));
            assertNull(absent.findPreference("hide_chat_sticker_banner"));
            assertNull(absent.findPreference("hide_chat_ai_replies"));
            assertNull(absent.findPreference("turn_off_chat_double_tap"));
            assertNull(absent.findPreference("turn_off_chat_swipe_reply"));

            SettingsStatus.chatDeclutterEnabled = true;
            PreferenceScreen present = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, present);
            assertNotNull(present.findPreference("hide_chat_call_buttons"));
            assertNotNull(present.findPreference("hide_chat_sticker_banner"));
            assertNotNull(present.findPreference("hide_chat_ai_replies"));
            assertNotNull(present.findPreference("turn_off_chat_double_tap"));
            assertNotNull(present.findPreference("turn_off_chat_swipe_reply"));
            assertTrue(InboxPreferenceCategory.isAvailable());
        }
    }
}
