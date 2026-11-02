package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.SharePreferenceCategory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Whether a video's own Duet and Stitch setting is the one the app reads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DuetStitchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void onlyTheCreatorsOwnRefusalIsAnswered() {
        try {
            // Off: every number reaches the app exactly as the video sent it.
            Settings.ALLOW_DUET_AND_STITCH.save(false);
            for (int setting : new int[]{0, 1, 2, 3, -1}) {
                assertEquals(setting, DuetStitch.setting(setting));
            }

            Settings.ALLOW_DUET_AND_STITCH.save(true);
            // 1 is the creator saying friends only, which is the one this switch is for.
            assertEquals(0, DuetStitch.setting(1));
            // 2 is the commercial refusal, music or a partnership, and is left standing.
            assertEquals(2, DuetStitch.setting(2));
            // Anything already permissive, or a number we do not recognise, is passed on.
            assertEquals(0, DuetStitch.setting(0));
            assertEquals(3, DuetStitch.setting(3));
            assertEquals(-1, DuetStitch.setting(-1));
        } finally {
            Settings.ALLOW_DUET_AND_STITCH.save(false);
        }
    }

    /**
     * The creator's account-wide choice is theirs whatever it says, so the switch answers every
     * value of it. On the S22 a friends-only video kept its Duet entry hidden with the switch on,
     * because its creator's account said 3.
     */
    @Test public void theCreatorsAccountWideChoiceIsAnsweredToo() {
        try {
            Settings.ALLOW_DUET_AND_STITCH.save(false);
            for (int setting : new int[]{0, 1, 2, 3, -1}) {
                assertEquals(setting, DuetStitch.authorSetting(setting));
            }

            Settings.ALLOW_DUET_AND_STITCH.save(true);
            for (int setting : new int[]{0, 1, 2, 3, -1}) {
                assertEquals("the account's " + setting + " was left standing", 0, DuetStitch.authorSetting(setting));
            }
        } finally {
            Settings.ALLOW_DUET_AND_STITCH.save(false);
        }
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.duetStitchEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new SharePreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("allow_duet_and_stitch"));
        } finally {
            SettingsStatus.duetStitchEnabled = false;
        }
    }
}
