package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.*;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MetraAutoScrollTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    @After public void reset() {
        SettingsStatus.forceShowAutoScrollEnabled = false;
        SettingsStatus.autoAdvanceEnabled = false;
        Settings.AUTO_ADVANCE.resetToDefault();
        Settings.FORCE_SHOW_AUTO_SCROLL.resetToDefault();
        Settings.AUTO_ADVANCE_HIDE_PANEL_ACTION.resetToDefault();
    }

    @Test public void exposingTheActionDoesNotStartAutomaticAdvance() {
        SettingsStatus.forceShowAutoScrollEnabled = true;
        SettingsStatus.autoAdvanceEnabled = false;
        Settings.FORCE_SHOW_AUTO_SCROLL.save(true);
        Settings.AUTO_ADVANCE.save(false);
        assertTrue(AutoAdvance.available(false));
        assertTrue(AutoAdvance.panelAvailable(false));
        Object lazy = new Object();
        assertSame(lazy, AutoAdvance.loadStrategy(lazy));
        assertFalse(Settings.AUTO_ADVANCE.get());
    }

    @Test public void settingsFromAnOmittedPatchCannotChangeTheNativeAction() {
        Settings.FORCE_SHOW_AUTO_SCROLL.save(true);
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_HIDE_PANEL_ACTION.save(true);
        SettingsStatus.forceShowAutoScrollEnabled = false;
        SettingsStatus.autoAdvanceEnabled = false;
        assertFalse(AutoAdvance.available(false));
        assertTrue(AutoAdvance.panelAvailable(true));
        SettingsStatus.forceShowAutoScrollEnabled = true;
        assertTrue(AutoAdvance.panelAvailable(false));
        SettingsStatus.autoAdvanceEnabled = true;
        assertFalse(AutoAdvance.panelAvailable(true));
    }
}
