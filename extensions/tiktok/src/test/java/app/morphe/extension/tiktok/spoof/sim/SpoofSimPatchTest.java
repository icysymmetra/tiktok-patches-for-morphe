/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.spoof.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The SIM fields are free text. The dialog checks the country code but nothing checked the
 * network code, and a settings backup goes nowhere near the dialog. TikTok reads both values
 * back through the platform's telephony getters and parses the first three digits as a
 * number, so a value not shaped like a network code has to stop here rather than be handed
 * on. The real value is the fallback: the reader gets their own network, not a crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpoofSimPatchTest {
    private static final String REAL_MCC_MNC = "23415";
    private static final String REAL_ISO = "gb";

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SIM_SPOOF.save(true);
    }

    @After public void reset() {
        Settings.SIM_SPOOF.save(false);
        Settings.SIMSPOOF_MCCMNC.save(Settings.SIMSPOOF_MCCMNC.defaultValue);
        Settings.SIM_SPOOF_ISO.save(Settings.SIM_SPOOF_ISO.defaultValue);
    }

    @Test public void aNetworkCodeThatIsNotDigitsLeavesTheRealOneAlone() {
        for (String nonsense : new String[]{"abc", "31026a", "3102", "3102601", "", "310 26"}) {
            Settings.SIMSPOOF_MCCMNC.save(nonsense);
            assertEquals("MCC/MNC " + nonsense + " should not reach TikTok",
                    REAL_MCC_MNC, SpoofSimPatch.getOperator(REAL_MCC_MNC));
            // The split halves read the same field, so they have to refuse it as well.
            assertEquals("MCC from " + nonsense, "234", SpoofSimPatch.getMcc("234"));
            assertEquals("MNC from " + nonsense, "15", SpoofSimPatch.getMnc("15"));
        }
    }

    @Test public void aNetworkCodeShapedLikeOneIsUsed() {
        Settings.SIMSPOOF_MCCMNC.save("310260");
        assertEquals("310260", SpoofSimPatch.getOperator(REAL_MCC_MNC));
        assertEquals("310", SpoofSimPatch.getMcc("234"));
        assertEquals("260", SpoofSimPatch.getMnc("15"));

        // Five digits is a two-digit network code, which is just as real.
        Settings.SIMSPOOF_MCCMNC.save("23410");
        assertEquals("23410", SpoofSimPatch.getOperator(REAL_MCC_MNC));
        assertEquals("234", SpoofSimPatch.getMcc("234"));
        assertEquals("10", SpoofSimPatch.getMnc("15"));
    }

    @Test public void aCountryThatIsNotACountryLeavesTheRealOneAlone() {
        for (String nonsense : new String[]{"zz", "united kingdom", "u", "", "12"}) {
            Settings.SIM_SPOOF_ISO.save(nonsense);
            assertEquals("country " + nonsense + " should not reach TikTok",
                    REAL_ISO, SpoofSimPatch.getCountryIso(REAL_ISO));
        }
    }

    @Test public void aCountryThatIsOneIsUsed() {
        Settings.SIM_SPOOF_ISO.save("jp");
        assertEquals("jp", SpoofSimPatch.getCountryIso(REAL_ISO));
    }

    @Test public void theSimChangeReportIsSkippedOnlyWhileThePresetIsOn() {
        // The report fires when the subscription id changes, which the preset does not touch, so
        // it is a real SIM swap that sends it. The country and carrier in it read as the preset;
        // the phone's SIM count does not, because that comes from SubscriptionManager, and the
        // event still says the hardware moved.
        assertTrue(SpoofSimPatch.shouldSkipSimChangeReport());

        Settings.SIM_SPOOF.save(false);
        assertFalse(SpoofSimPatch.shouldSkipSimChangeReport());
    }

    @Test public void nothingIsSpoofedWhileTheSwitchIsOff() {
        Settings.SIM_SPOOF.save(false);
        Settings.SIMSPOOF_MCCMNC.save("310260");
        Settings.SIM_SPOOF_ISO.save("us");
        assertEquals(REAL_MCC_MNC, SpoofSimPatch.getOperator(REAL_MCC_MNC));
        assertEquals(REAL_ISO, SpoofSimPatch.getCountryIso(REAL_ISO));
        assertEquals("234", SpoofSimPatch.getMcc("234"));
    }
}
