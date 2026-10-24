package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The full-screen viewer's gate is answered by the switch, and TikTok decides when it is off. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FullScreenHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void tearDown() {
        Settings.FULL_SCREEN_HOLD.resetToDefault();
    }

    @Test public void theViewerMovesOnUnlessTheSwitchIsOn() {
        assertFalse("off by default, so TikTok's own auto-next stays", FullScreenHold.hold());
        Settings.FULL_SCREEN_HOLD.save(true);
        assertTrue(FullScreenHold.hold());
        Settings.FULL_SCREEN_HOLD.save(false);
        assertFalse(FullScreenHold.hold());
    }
}
