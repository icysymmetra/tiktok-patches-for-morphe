/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.bytedance.applog.priority.PriorityHttpResponse;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DisableTelemetryPatchTest {
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.DISABLE_ANALYTICS.save(false);
    }

    @After
    public void tearDown() {
        Settings.DISABLE_ANALYTICS.save(false);
    }

    @Test
    public void telemetryIsDisabledWhenTheSettingIsOn() {
        Settings.DISABLE_ANALYTICS.save(true);
        assertTrue(DisableTelemetryPatch.isTelemetryDisabled());
    }

    @Test
    public void telemetryIsEnabledWhenTheSettingIsOff() {
        assertFalse(DisableTelemetryPatch.isTelemetryDisabled());
    }

    @Test
    public void telemetryStaysEnabledBeforeTheContextIsSet() {
        Utils.setContext(null);
        Settings.DISABLE_ANALYTICS.save(true);
        assertFalse(DisableTelemetryPatch.isTelemetryDisabled());
    }

    /**
     * The priority uploader's checker takes a reply as delivered only for a 2xx code whose data
     * says message success and carries the SDK's magic tag (TelemetrySendAnchorsTest pins that
     * checker on 47.0.3), so the guarded send has to hand back exactly that.
     */
    @Test
    public void thePriorityReplyIsTheOneItsCheckerTakesAsDelivered() throws Exception {
        PriorityHttpResponse reply = (PriorityHttpResponse) DisableTelemetryPatch.deliveredPriorityResponse();
        assertEquals(200, reply.getCode());
        JSONObject data = new JSONObject(reply.getData());
        assertEquals("success", data.optString("message"));
        assertEquals("ss_app_log", data.optString("magic_tag"));
    }
}
