/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A control marked busy and back, on both sides of Android 11. Below it the busy word goes in
 * the content description, and keeping the old one in a view tag under a framework id threw
 * on the first busy press, which took down the sticker Save and the block button on Android 6
 * to 10. Nothing ran the busy side below Android 11 until the sticker queue test did.
 */
@RunWith(RobolectricTestRunner.class)
public class BusyControlTest {
    @Test @Config(sdk = 28)
    public void belowAndroidElevenTheDescriptionCarriesTheBusyWordAndComesBack() {
        View described = new View(RuntimeEnvironment.getApplication());
        described.setContentDescription("Save");
        SettingsUi.setBusy(described, true, "Saving");
        assertFalse(described.isEnabled());
        assertEquals("Saving", described.getContentDescription().toString());
        // Pressed busy again: the first description is still the one to go back to.
        SettingsUi.setBusy(described, true, "Saving");
        SettingsUi.setBusy(described, false, null);
        assertTrue(described.isEnabled());
        assertEquals("Save", described.getContentDescription().toString());

        View bare = new View(RuntimeEnvironment.getApplication());
        SettingsUi.setBusy(bare, true, "Blocking");
        assertEquals("Blocking", bare.getContentDescription().toString());
        SettingsUi.setBusy(bare, false, null);
        assertNull("a control with no description kept the busy word", bare.getContentDescription());

        // Handing back a control that was never busy leaves its words alone.
        View idle = new View(RuntimeEnvironment.getApplication());
        idle.setContentDescription("Block");
        SettingsUi.setBusy(idle, false, null);
        assertEquals("Block", idle.getContentDescription().toString());
    }

    @Test @Config(sdk = 30)
    public void fromAndroidElevenTheStateDescriptionCarriesIt() {
        View control = new View(RuntimeEnvironment.getApplication());
        control.setContentDescription("Save");
        SettingsUi.setBusy(control, true, "Saving");
        assertFalse(control.isEnabled());
        assertEquals("Saving", control.getStateDescription().toString());
        assertEquals("Save", control.getContentDescription().toString());
        SettingsUi.setBusy(control, false, null);
        assertTrue(control.isEnabled());
        assertNull(control.getStateDescription());
    }
}
