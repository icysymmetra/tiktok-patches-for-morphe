/*
 * SPDX-FileCopyrightText: 2026 hxreborn
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.extension.tiktok.telemetry;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.bytedance.applog.priority.PriorityHttpResponse;

public final class DisableTelemetryPatch {
    private DisableTelemetryPatch() {}

    /** The reply the AppLog priority uploader's callers take as delivered: TikTok's own success data. */
    static final String DELIVERED_PRIORITY_DATA = "{\"message\":\"success\",\"magic_tag\":\"ss_app_log\"}";

    /**
     * What the AppLog priority uploader answers with the switch on, in place of the request: a
     * 200 whose data says success and carries the SDK's magic tag, which is what its callers
     * check before dropping the events they sent, so nothing is kept to send again.
     */
    public static Object deliveredPriorityResponse() {
        return new PriorityHttpResponse(200, "", DELIVERED_PRIORITY_DATA);
    }

    public static boolean isTelemetryDisabled() {
        // Keep analytics enabled if the context isn't initialized yet
        return Utils.getContext() != null && Settings.DISABLE_ANALYTICS.get();
    }
}
