/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/** The public doors into the ad skipper: the share sheet button and the settings rows. */
public final class AdSkipActions {
    private AdSkipActions() {}

    public static void mark(Activity activity, Object aweme) {
        if (activity == null || aweme == null) {
            Utils.showToastShort(L10n.t("Open a video first"));
            return;
        }
        AdSegmentEditor.open(activity, aweme);
    }

    /** Marks the video that is playing, from the settings page. */
    public static boolean markPlaying(Activity activity) {
        Object aweme = AdSkip.playingAweme();
        if (aweme == null) return false;
        mark(activity, aweme);
        return true;
    }

    public static void openList(Activity activity) {
        AdSegmentList.open(activity);
    }

    public static int count() {
        return AdSegmentStore.size();
    }

    public static long skips() {
        return AdSkipStats.skips();
    }
}
