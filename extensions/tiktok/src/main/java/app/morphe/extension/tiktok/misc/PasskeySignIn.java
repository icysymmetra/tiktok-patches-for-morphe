/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The switch in front of Skip passkey sign-in (#102).
 *
 * <p>A password manager hands a passkey only to an app signed with the key the site's asset
 * links name, which a patched TikTok can't have, so TikTok's passkey step can't finish in it.
 * The hook is TikTok's check of whether this phone can use passkeys at all. With the switch on
 * it answers no, which is what TikTok hears on a phone without a recent Google Play services,
 * and its sign-in screens take the routes they already have for such a phone. With it off,
 * and while Hushfeed is paused, the check runs as TikTok ships it.
 *
 * <p>TikTok's saved-account list asks once, when its service is built, and that may be before
 * the settings context exists, so a read then comes from the saved file.
 */
@SuppressWarnings("unused")
public final class PasskeySignIn {
    /** {@link Settings#SKIP_PASSKEY_SIGN_IN}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "skip_passkey_sign_in";

    private PasskeySignIn() {
    }

    /** True while TikTok's passkey support check should answer no. */
    public static boolean hidePasskeys() {
        if (!SettingsStatus.passkeySignInEnabled) return false;
        return Utils.getContext() != null ? Settings.SKIP_PASSKEY_SIGN_IN.get() : EarlySwitch.isOn(SWITCH_KEY);
    }
}
