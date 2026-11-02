/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.capture;

import android.view.Window;
import android.view.WindowManager;
import app.morphe.extension.tiktok.settings.Settings;

public final class ScreenCapture {
    private ScreenCapture() {}
    private static final int SECURE = WindowManager.LayoutParams.FLAG_SECURE;

    public static void addFlags(Window window, int flags) {
        if (Settings.ALLOW_SCREEN_CAPTURE.get()) {
            window.clearFlags(SECURE);
            flags &= ~SECURE;
        }
        window.addFlags(flags);
    }

    public static void setFlags(Window window, int flags, int mask) {
        if (Settings.ALLOW_SCREEN_CAPTURE.get()) {
            flags &= ~SECURE;
            mask |= SECURE;
        }
        window.setFlags(flags, mask);
    }

    public static void setAttributes(Window window, WindowManager.LayoutParams attributes) {
        if (Settings.ALLOW_SCREEN_CAPTURE.get() && attributes != null) {
            WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
            copy.copyFrom(attributes);
            copy.flags &= ~SECURE;
            attributes = copy;
        }
        window.setAttributes(attributes);
    }

    /**
     * Takes the place of TikTok's writes of a layout parameters' flags. Popups and floating windows
     * built from their own parameters, Compose's among them, reach the window manager without a
     * Window call above. Only a secure flag reads the setting.
     */
    public static void setLayoutFlags(WindowManager.LayoutParams attributes, int flags) {
        if ((flags & SECURE) != 0 && Settings.ALLOW_SCREEN_CAPTURE.get()) flags &= ~SECURE;
        attributes.flags = flags;
    }

    public static Object circleBlock(Object original) {
        return Settings.ALLOW_SCREEN_CAPTURE.get() ? Integer.valueOf(0) : original;
    }
}
