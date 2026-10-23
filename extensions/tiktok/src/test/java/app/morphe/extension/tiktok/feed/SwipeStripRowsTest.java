package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The two rows that pick what each edge strip of Swipe for brightness and volume changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SwipeStripRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        SettingsStatus.videoOverlaysEnabled = false;
        SettingsStatus.liveSpeedEnabled = false;
        Settings.SWIPE_LEVELS_LEFT.resetToDefault();
        Settings.SWIPE_LEVELS_RIGHT.resetToDefault();
    }

    private PreferenceScreen gesturesPage(TestActivity activity) {
        Utils.setContext(activity);
        Utils.setIsDarkModeEnabled(true);
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new InterfacePreferenceCategory(activity, screen);
        return screen;
    }

    @Test
    public void withTheSpeedPatchEachStripCanChooseBrightnessVolumeOrSpeed() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            SettingsStatus.videoOverlaysEnabled = true;
            SettingsStatus.liveSpeedEnabled = true;
            PreferenceScreen screen = gesturesPage(controller.get());

            for (String key : new String[]{"swipe_levels_left", "swipe_levels_right"}) {
                ChoicePreference row = (ChoicePreference) screen.findPreference(key);
                assertNotNull(key, row);
                assertArrayEquals(key, new String[]{"brightness", "volume", "speed"}, row.getEntryValues());
            }
        }
    }

    @Test
    public void withoutTheSpeedPatchTheRowsOfferOnlyWhatTheyAlwaysDid() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            SettingsStatus.videoOverlaysEnabled = true;
            SettingsStatus.liveSpeedEnabled = false;
            PreferenceScreen screen = gesturesPage(controller.get());

            for (String key : new String[]{"swipe_levels_left", "swipe_levels_right"}) {
                ChoicePreference row = (ChoicePreference) screen.findPreference(key);
                assertNotNull(key, row);
                assertArrayEquals(key, new String[]{"brightness", "volume"}, row.getEntryValues());
            }
        }
    }

    @Test
    public void withoutTheOverlayPatchThereAreNoStripRows() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            SettingsStatus.videoOverlaysEnabled = false;
            SettingsStatus.liveSpeedEnabled = true;
            PreferenceScreen screen = gesturesPage(controller.get());

            assertNull(screen.findPreference("swipe_levels_left"));
            assertNull(screen.findPreference("swipe_levels_right"));
        }
    }

    @Test
    public void theStripsStartAsBrightnessOnTheLeftAndVolumeOnTheRight() {
        Settings.SWIPE_LEVELS_LEFT.resetToDefault();
        Settings.SWIPE_LEVELS_RIGHT.resetToDefault();
        assertEquals("brightness", Settings.SWIPE_LEVELS_LEFT.get());
        assertEquals("volume", Settings.SWIPE_LEVELS_RIGHT.get());
    }
}
