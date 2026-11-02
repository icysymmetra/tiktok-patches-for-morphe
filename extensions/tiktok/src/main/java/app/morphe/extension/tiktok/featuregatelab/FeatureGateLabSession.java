/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.app.Activity;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.shared.Utils;

public final class FeatureGateLabSession {
    private static volatile boolean restartNeeded;
    private static boolean noticeShown;

    private FeatureGateLabSession() {}

    public static void begin() {
        noticeShown = false;
    }

    static void resetForTests() {
        restartNeeded = false;
        noticeShown = false;
    }

    public static void markRestartNeeded() {
        restartNeeded = true;
    }

    public static boolean restartNeeded() {
        return restartNeeded;
    }

    /**
     * Retained entry point for callers leaving the Lab. Every action already toasts
     * "Restart TikTok to apply this." so the exit toast was a duplicate.
     */
    public static void showRestartDialog(Activity activity) {
    }

    public static void showAfterHostExit(Activity host) {
        showRestartDialog(host);
    }
}
