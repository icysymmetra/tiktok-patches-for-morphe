/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/** Entry points for share-sheet and settings actions. */
public final class AdSkipActions {
    private AdSkipActions() {}

    public static void mark(Activity activity, Object aweme) {
        if (activity == null || aweme == null) {
            Utils.showToastShort(L10n.t("Open a video first"));
            return;
        }
        AdSegmentEditor.open(activity, aweme);
    }

    /** Opens the editor for the currently playing video. */
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
