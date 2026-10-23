/*
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.parent;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DebugCapture;

/**
 * Settings shared across multiple apps.
 * <p>
 * To ensure this class is loaded when the UI is created, app specific setting bundles should extend
 * or reference this class.
 */
public class BaseSettings {
    /**
     * Log diagnostics. A timed capture ({@link DebugCapture}) answers on as well, without saving
     * anything here: the switch keeps what the reader set, a backup carries that, and the
     * capture ends on its own.
     */
    public static final BooleanSetting DEBUG = new BooleanSetting("morphe_debug", FALSE) {
        @NonNull
        @Override
        protected Boolean currentValue() {
            return savedValue() || DebugCapture.isRunning() ? TRUE : FALSE;
        }
    };
    /**
     * The running timed capture, written by {@link DebugCapture} only. Left out of backups, so
     * restoring one can't start logging again.
     */
    public static final StringSetting DEBUG_CAPTURE = new StringSetting("hushfeed_debug_capture", "", false, false);
    public static final StringSetting DEBUG_LOG_FILTERS = new StringSetting("morphe_debug_log_filters", "all", false, false);
    public static final BooleanSetting CAPTURE_JAVA_CRASHES =
            new BooleanSetting("morphe_capture_java_crashes", FALSE, true);
    public static final BooleanSetting DEBUG_STACKTRACE = new BooleanSetting("morphe_debug_stacktrace", FALSE, parent(DEBUG));
    /**
     * Whether a failure inside the bundle is also put on screen while {@link #DEBUG} is on.
     * No confirmation dialog: the message it carried named a string resource this bundle does
     * not ship, and the row that now exposes the switch would have raised an empty dialog.
     */
    public static final BooleanSetting DEBUG_TOAST_ON_ERROR = new BooleanSetting("morphe_debug_toast_on_error", TRUE);
    public static final BooleanSetting SANITIZE_SHARING_LINKS = new BooleanSetting("morphe_sanitize_sharing_links", TRUE);

    public static final IntegerSetting CHECK_ENVIRONMENT_WARNINGS_ISSUED = new IntegerSetting("morphe_check_environment_warnings_issued", 0, true, false);

    public static final EnumSetting<AppLanguage> MORPHE_LANGUAGE = new EnumSetting<>("morphe_language", AppLanguage.DEFAULT, true, "morphe_language_user_dialog_message");

    /**
     * Use the icons declared in the preferences created during patching. If no icons or styles are declared then this setting does nothing.
     */
    public static final BooleanSetting SHOW_MENU_ICONS = new BooleanSetting("morphe_show_menu_icons", TRUE, true);

    /**
     * The first time the app was launched with no previous app data (either a clean install, or after wiping app data).
     */
    public static final LongSetting FIRST_TIME_APP_LAUNCHED = new LongSetting("morphe_last_time_app_was_launched", -1L, false, false);

    public static final StringSetting EXPERIMENTAL_APP_CONFIRMED = new StringSetting("morphe_experimental_app_target_confirmed", "", false, false);

    /**
     * Pause Hushfeed: from the next start TikTok runs as if it were not patched for every hook a
     * setting controls, and every saved value stays as it is. Left out of backups, so restoring
     * one never pauses a phone by surprise.
     */
    public static final BooleanSetting PAUSED = new BooleanSetting("hushfeed_paused", FALSE, true, false, null, null);

    /** Set by {@link HushfeedPause} after three starts in a row crashed within a minute. */
    public static final BooleanSetting SAFE_MODE = new BooleanSetting("hushfeed_safe_mode", FALSE, true, false, null, null);

    /**
     * Whether the Quick setups card is off the settings home page, after a long press on it. No
     * restart, and left out of backups and of every Quick setups choice.
     */
    public static final BooleanSetting HIDE_QUICK_SETUPS =
            new BooleanSetting("hushfeed_hide_quick_setups", FALSE, false, false, null, null);

    static {
        // Hushfeed's own state and its diagnostics, which keep working while it is paused.
        Setting.keepWhenPaused(DEBUG, DEBUG_CAPTURE, DEBUG_LOG_FILTERS, CAPTURE_JAVA_CRASHES, DEBUG_STACKTRACE,
                DEBUG_TOAST_ON_ERROR, CHECK_ENVIRONMENT_WARNINGS_ISSUED, MORPHE_LANGUAGE, SHOW_MENU_ICONS,
                FIRST_TIME_APP_LAUNCHED, EXPERIMENTAL_APP_CONFIRMED, PAUSED, SAFE_MODE, HIDE_QUICK_SETUPS);

        final long now = System.currentTimeMillis();

        if (FIRST_TIME_APP_LAUNCHED.savedValue() < 0) {
            Logger.printInfo(() -> "First launch of installation with no prior app data");
            FIRST_TIME_APP_LAUNCHED.save(now);
        }
    }
}
