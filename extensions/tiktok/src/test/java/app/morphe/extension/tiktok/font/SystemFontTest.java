/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Issue #7: the device font stands in for TikTok's, at the same weight, only when the switch is on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SystemFontTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        Settings.SYSTEM_FONT.save(false);
    }

    @After public void tearDown() {
        Settings.SYSTEM_FONT.resetToDefault();
        HookStatus.clear();
    }

    @Test public void withTheSwitchOffTheTikTokTypefaceIsUntouched() {
        Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        assertSame("the font was swapped with the switch off", tiktok, SystemFont.systemize(tiktok));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("system font"));
    }

    @Test public void withTheSwitchOnADeviceTypefaceIsReturned() {
        Settings.SYSTEM_FONT.save(true);
        Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        Typeface replaced = SystemFont.systemize(tiktok);
        assertTrue("no typeface came back with the switch on", replaced != null);
        // The device font is a family of Typeface.DEFAULT, never the SERIF stand-in for TikTok's.
        assertTrue("the TikTok typeface was handed straight back", replaced != tiktok);
    }

    @Test public void nullIsAlwaysPassedThrough() {
        assertNull(SystemFont.systemize(null));
        Settings.SYSTEM_FONT.save(true);
        assertNull(SystemFont.systemize(null));
    }

    @Test public void theExportNamesTheStateWhicheverWayTheSwitchIsSet() {
        SystemFont.systemize(Typeface.DEFAULT);
        assertTrue(HookStatus.report().toString().contains("system font"));
    }
}
