/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.upload;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * TikTok's own HD upload choice, read as on while Always upload in HD is.
 *
 * <p>TikTok keeps the post page's HD switch as an int in its publish Keva repository: 0 until you
 * touch it, 1 after you turn it on, 2 after you turn it off. Its upload decision reads that int
 * and, at 1, takes the HD path whatever the upload speed or the server's default says; the
 * clip still has to pass TikTok's own high quality check (larger than the standard size or a
 * bit rate over its recode threshold) before anything is encoded differently. The same int
 * feeds the post's analytics fields, so all three reads agree.
 */
@SuppressWarnings("unused")
public final class HdUpload {
    /** The value TikTok stores after you turn its HD switch on yourself. */
    static final int USER_CHOSE_HD = 1;

    private HdUpload() {
    }

    /**
     * The HD choice TikTok should act on, given the one it stored. Paused, before the extension
     * has a context, or with the switch off, TikTok's own value goes back untouched.
     */
    public static int userChoice(int stored) {
        return isOn() ? USER_CHOSE_HD : stored;
    }

    static boolean isOn() {
        return SettingsStatus.hdUploadEnabled
                && Utils.getContext() != null
                && Settings.ALWAYS_UPLOAD_HD.get();
    }
}
