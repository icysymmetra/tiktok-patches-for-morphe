package app.morphe.extension.tiktok.speed;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.preference.SwitchPreference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.List;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlaybackSpeedTest {
    private static Aweme video(String id) { return new Aweme() { @Override public String getAid() { return id; } }; }
    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The restart debt is process-wide, and the capture below owes only what this test
        // changes through the screen, not what an earlier class left behind.
        app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                .restartPending.clear();
        Settings.DEFAULT_SPEED_ENABLED.save(true);
        Settings.DEFAULT_SPEED.save("1.5");
        Settings.CUSTOM_SPEEDS.save("");
        Settings.HOLD_SPEED.save("2");
        Settings.REMEMBERED_SPEED.save(1f);
        PlaybackSpeedPatch.beginVideo(null);
    }
    @After public void tearDown() {
        SettingsStatus.playbackSpeedEnabled = false;
        SettingsStatus.playbackQualityEnabled = false;
        // Process-wide, and the Playback page other classes capture shows it.
        Settings.HOLD_SPEED.resetToDefault();
    }
    @Test public void manualChoiceSurvivesRepeatedFramesUntilTheVideoChanges() {
        Aweme a = video("a"), b = video("b");
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(a), 0);
        PlaybackSpeedPatch.onSelection(2.5f, a, "feed", "long_press");
        assertEquals(2.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("a")), 0);
        assertEquals(2.5f, PlaybackSpeedPatch.preserveTransitionSpeed(1f), 0);
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(b), 0);
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(a), 0);
    }
    @Test public void temporarySpeedAndInvalidInputsDoNotBecomeManualChoices() {
        Aweme a = video("a");
        PlaybackSpeedPatch.getPlaybackSpeedForVideo(a);
        PlaybackSpeedPatch.onSelection(2f, a, "feed", "gesture_hold");
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeed(), 0);
        for (float speed : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0, -1}) {
            PlaybackSpeedPatch.onSelection(speed, a, "feed", "long_press");
            assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeed(), 0);
        }
        PlaybackSpeedPatch.onSelection(2f, a, "feed", "swipe_up_lock_persist");
        assertEquals(2f, PlaybackSpeedPatch.getPlaybackSpeed(), 0);
        PlaybackSpeedPatch.beginVideo(null);
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeed(), 0);
    }
    @Test public void disabledDefaultRetainsRememberedSpeedAndNativeTemporaryRequests() {
        Settings.DEFAULT_SPEED_ENABLED.save(false);
        PlaybackSpeedPatch.onSelection(2.5f, video("a"), "feed", "click_share_button");
        assertEquals(2.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("b")), 0);
        assertEquals(2.5f, PlaybackSpeedPatch.preserveTransitionSpeed(1f), 0);
        assertEquals(2f, PlaybackSpeedPatch.preserveTransitionSpeed(2f), 0);
    }
    @Test public void turningTheDefaultOffDoesNotCarryItIntoTheNextVideo() {
        assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeedForVideo(video("a")), 0);
        Settings.DEFAULT_SPEED_ENABLED.save(false);
        // The swipe hands the next video what TikTok's state holds: the default "a" was given.
        PlaybackSpeedPatch.beginVideo(video("b"));
        assertEquals(1f, PlaybackSpeedPatch.preserveTransitionSpeed(1.5f), 0);
        assertEquals("a speed TikTok carries itself still stands",
                2f, PlaybackSpeedPatch.preserveTransitionSpeed(2f), 0);
    }
    @Test public void malformedDefaultUsesSafeDefaultAndMenuFailuresKeepNativeList() {
        var nativeList = List.of(0.5f, 1f, 1.5f, 2f);
        assertSame(nativeList, PlaybackSpeedPatch.menuSpeeds(nativeList));
        for (String value : new String[]{"NaN", "Infinity", "0", "-1", "4", "invalid"}) {
            Settings.DEFAULT_SPEED.save(value);
            assertEquals(1.5f, PlaybackSpeedPatch.getPlaybackSpeed(), 0);
            Settings.CUSTOM_SPEEDS.save(value);
            assertSame(nativeList, PlaybackSpeedPatch.menuSpeeds(nativeList));
        }
        Settings.CUSTOM_SPEEDS.save("0.5, 1, 1.5, 2, 2.5, 3, 2.5");
        assertEquals(List.of(0.5f, 1f, 1.5f, 2f, 2.5f, 3f), PlaybackSpeedPatch.menuSpeeds(nativeList));
        assertEquals(4, nativeList.size());
        for (String value : new String[]{"1,", ",1", "1,2,3,1,2,3,1,2,3"}) {
            assertThrows(IllegalArgumentException.class, () -> PlaybackSpeedPatch.parseMenuSpeeds(value));
        }
    }
    @Test public void speedOnlyPatchExposesControlsAndSavesFromActualSettingsScreen() throws Exception {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.playbackSpeedEnabled = true;
            SettingsStatus.playbackQualityEnabled = false;
            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "PLAYBACK");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();
            var toggle = (SwitchPreference) fragment.findPreference("default_speed_enabled");
            toggle.setChecked(false);
            toggle.setChecked(true);
            var choices = fragment.findPreference("custom_speeds");
            assertFalse(choices.getOnPreferenceChangeListener().onPreferenceChange(choices, "NaN"));
            assertTrue(choices.getOnPreferenceChangeListener().onPreferenceChange(choices, "1, 1.5, 2.5, 3"));
            ((android.preference.EditTextPreference) choices).setText("1, 1.5, 2.5, 3");
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(Settings.DEFAULT_SPEED_ENABLED.get());
            assertEquals("1, 1.5, 2.5, 3", Settings.CUSTOM_SPEEDS.get());
            assertNotNull(fragment.findPreference("default_speed"));
            assertNull(fragment.findPreference("playback_quality"));
            app.morphe.extension.tiktok.UiCapture.save(activity.getWindow().getDecorView(), "playback-speed-settings.png");
        }
    }
    @Test public void theHoldSpeedRowReplacesTikToksTwoTimesAndBadValuesLeaveIt() {
        Settings.HOLD_SPEED.save("3");
        assertEquals(3f, PlaybackSpeedPatch.holdSpeed(2f), 0);
        Settings.HOLD_SPEED.save("1.5");
        assertEquals(1.5f, PlaybackSpeedPatch.holdSpeed(2f), 0);
        // Above the player's ceiling, not a number, and zero all leave TikTok's own value.
        Settings.HOLD_SPEED.save("4");
        assertEquals(2f, PlaybackSpeedPatch.holdSpeed(2f), 0);
        Settings.HOLD_SPEED.save("fast");
        assertEquals(2f, PlaybackSpeedPatch.holdSpeed(2f), 0);
        Settings.HOLD_SPEED.save("0");
        assertEquals(2f, PlaybackSpeedPatch.holdSpeed(2f), 0);
    }
    @Test public void tiktoksWordsForTheHoldFollowTheChosenSpeed() {
        Settings.HOLD_SPEED.save("3");
        assertEquals("Speed: 3x", PlaybackSpeedPatch.holdSpeedText("Speed: 2x"));
        assertEquals("Pull down to lock 3x speed", PlaybackSpeedPatch.holdSpeedText("Pull down to lock 2x speed"));
        assertEquals("Locked at 3× speed", PlaybackSpeedPatch.holdSpeedText("Locked at 2× speed"));
        // A language that writes the multiplier first.
        assertEquals("Vitesse x3", PlaybackSpeedPatch.holdSpeedText("Vitesse x2"));
        Settings.HOLD_SPEED.save("1.25");
        assertEquals("Locked at 1.25× speed", PlaybackSpeedPatch.holdSpeedText("Locked at 2× speed"));
        // No 2, a 2 inside another number, or two of them: TikTok's own words stand.
        for (String kept : new String[]{"Back to normal speed", "Speed: 1x", "Speed: 12x", "Speed: 2.5x", "2x or 2x"}) {
            assertEquals(kept, PlaybackSpeedPatch.holdSpeedText(kept));
        }
        assertNull(PlaybackSpeedPatch.holdSpeedText(null));
        Settings.HOLD_SPEED.save("2");
        assertEquals("Speed: 2x", PlaybackSpeedPatch.holdSpeedText("Speed: 2x"));
        Settings.HOLD_SPEED.save("9");
        assertEquals("a speed the player refuses keeps TikTok's words",
                "Speed: 2x", PlaybackSpeedPatch.holdSpeedText("Speed: 2x"));
    }
    @Test public void theMenuToastNamesTheChosenSpeedNotTikToksNearest() {
        // TikTok labels a custom speed with its nearest built-in ("2x") and formats it in; the
        // reword turns that label into the speed actually chosen.
        assertEquals("2.5x", PlaybackSpeedPatch.menuSpeedText("2x", 2.5f));
        assertEquals("0.75x", PlaybackSpeedPatch.menuSpeedText("2x", 0.75f));
        assertEquals("1.25x", PlaybackSpeedPatch.menuSpeedText("2x", 1.25f));
        // A menu speed off the 0.25 grid: the label must read the float's shortest decimal, not
        // the widened double ("1.100000023841858"). Menu speeds are free text, so this is reachable.
        assertEquals("1.1x", PlaybackSpeedPatch.menuSpeedText("2x", 1.1f));
        assertEquals("0.7x", PlaybackSpeedPatch.menuSpeedText("2x", 0.7f));
        assertEquals("2.9x", PlaybackSpeedPatch.menuSpeedText("2x", 2.9f));
        // A language that writes the multiplier first, same "2x" label.
        assertEquals("x2.5", PlaybackSpeedPatch.menuSpeedText("x2", 2.5f));
        // TikTok's own values already read right: 2x is left alone, and 0.5, 1.5 and 3 carry a
        // different number the standalone-2 reword never touches.
        assertEquals("2x", PlaybackSpeedPatch.menuSpeedText("2x", 2f));
        assertEquals("0.5x", PlaybackSpeedPatch.menuSpeedText("0.5x", 0.5f));
        assertEquals("1.5x", PlaybackSpeedPatch.menuSpeedText("1.5x", 1.5f));
        assertEquals("3x", PlaybackSpeedPatch.menuSpeedText("3x", 3f));
        assertNull(PlaybackSpeedPatch.menuSpeedText(null, 2.5f));
        // NaN speed and a label with no lone 2 both keep TikTok's label.
        assertEquals("2x", PlaybackSpeedPatch.menuSpeedText("2x", Float.NaN));
        assertEquals("3x", PlaybackSpeedPatch.menuSpeedText("3x", 2.5f));
    }
    @Test public void pausedTiktoksWordsForTheHoldStand() {
        Settings.HOLD_SPEED.save("3");
        PausedProcess.set(true);
        try { assertEquals("Speed: 2x", PlaybackSpeedPatch.holdSpeedText("Speed: 2x")); }
        finally { PausedProcess.set(false); }
    }
    @Test public void thePlainBannerIsRewordedFromTikToksOwnTextEachTime() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.widget.LinearLayout banner = new android.widget.LinearLayout(context);
        android.widget.TextView label = new android.widget.TextView(context);
        label.setText("Speed: 2x");
        banner.addView(label);
        banner.addView(new android.widget.ImageView(context));

        Settings.HOLD_SPEED.save("3");
        PlaybackSpeedPatch.holdSpeedBanner(banner);
        assertEquals("Speed: 3x", label.getText().toString());
        // The next hold starts from TikTok's text, not from the last rewording.
        Settings.HOLD_SPEED.save("1.5");
        PlaybackSpeedPatch.holdSpeedBanner(banner);
        assertEquals("Speed: 1.5x", label.getText().toString());
        Settings.HOLD_SPEED.save("2");
        PlaybackSpeedPatch.holdSpeedBanner(banner);
        assertEquals("Speed: 2x", label.getText().toString());
        Settings.HOLD_SPEED.save("3");
        PausedProcess.set(true);
        try {
            PlaybackSpeedPatch.holdSpeedBanner(banner);
            assertEquals("Speed: 2x", label.getText().toString());
        } finally {
            PausedProcess.set(false);
        }
    }
    @Test public void pausedTheHoldSpeedIsTikToks() {
        Settings.HOLD_SPEED.save("3");
        PausedProcess.set(true);
        try { assertEquals(2f, PlaybackSpeedPatch.holdSpeed(2f), 0); }
        finally { PausedProcess.set(false); }
    }
    @Test public void theHoldSpeedRowSitsUnderSpeedWithTikToksTwoTimesChosen() throws Exception {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.playbackSpeedEnabled = true;
            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "PLAYBACK");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();
            var row = (android.preference.ListPreference) fragment.findPreference("hold_speed");
            assertEquals("2", row.getValue());
            assertEquals("2x", row.getEntry());
            row.setValue("3");
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("3", Settings.HOLD_SPEED.get());
        }
    }
}
