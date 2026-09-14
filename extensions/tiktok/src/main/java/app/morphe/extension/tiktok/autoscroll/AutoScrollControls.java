package app.morphe.extension.tiktok.autoscroll;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/** Controls TikTok's existing Auto scroll action without constructing a replacement action. */
public final class AutoScrollControls {

    private AutoScrollControls() {
    }

    public static boolean shouldForceAutoScroll() {
        boolean force = Settings.FORCE_SHOW_AUTO_SCROLL.get();
        if (force) {
            Logger.printDebug(() -> "Auto scroll: overriding fyp_auto_scroll rollout gate");
        }
        return force;
    }

    public static boolean forceAutoScrollPanelAvailability(boolean available) {
        if (available || !Settings.FORCE_SHOW_AUTO_SCROLL.get()) {
            return available;
        }

        Logger.printDebug(() -> "Auto scroll: overriding panel_auto_scroll rollout gate");
        return true;
    }
}
