package app.morphe.extension.tiktok.seekbar;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public final class SeekbarPatch {
    private static final String LOG_PREFIX = "[Morphe TikTok Seekbar]";

    private SeekbarPatch() {}

    public static boolean isEnabled() {
        return Settings.SHOW_SEEKBAR.get();
    }

    public static int overrideSeekbarShowType(int seekbarType) {
        if (!Settings.SHOW_SEEKBAR.get()) return seekbarType;
        if (seekbarType != 3 && seekbarType != 4) return seekbarType;

        if (Settings.DEBUG.get()) {
            Logger.printDebug(() -> LOG_PREFIX + " Overrode seekbar show type " + seekbarType + " -> 0");
        }
        return 0;
    }
}
