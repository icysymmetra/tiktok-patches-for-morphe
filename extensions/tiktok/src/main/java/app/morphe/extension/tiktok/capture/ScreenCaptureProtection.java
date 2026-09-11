package app.morphe.extension.tiktok.capture;

import android.view.Window;
import android.view.WindowManager;

/** Android window boundary shared by TikTok's capture-protection controllers. */
public final class ScreenCaptureProtection {
    private static final int SECURE = WindowManager.LayoutParams.FLAG_SECURE;

    private ScreenCaptureProtection() {}

    public static void addFlags(Window window, int flags) {
        // Include SECURE in the mask to also clear a flag inherited from another screen.
        window.setFlags(flags & ~SECURE, flags | SECURE);
    }

    public static void setFlags(Window window, int flags, int mask) {
        window.setFlags(flags & ~SECURE, mask | SECURE);
    }

    public static void setAttributes(Window window, WindowManager.LayoutParams attributes) {
        if ((attributes.flags & SECURE) != 0) {
            // Preserve the caller's object and all non-security layout attributes.
            WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
            copy.copyFrom(attributes);
            copy.flags &= ~SECURE;
            window.setAttributes(copy);
        } else {
            window.setAttributes(attributes);
        }
    }

    public static void setLayoutParamsFlags(WindowManager.LayoutParams attributes, int flags) {
        attributes.flags = flags & ~SECURE;
    }
}
