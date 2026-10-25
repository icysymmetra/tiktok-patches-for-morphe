/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.File;

import app.morphe.extension.shared.Logger;

/**
 * A switch read straight from Hushfeed's preferences file, for a hook that runs before the
 * settings context exists.
 *
 * <p>Reading a setting then would build the settings registry without a context, and the class
 * that failed to load stays broken for the rest of the process. So this touches no setting. It
 * takes the application the process already has from {@code ActivityThread}, opens the same file
 * every setting is saved to, and reads the key. The names it uses are compile-time constants, so
 * naming them loads none of the classes they come from.
 *
 * <p>It answers off when there is no application yet, when the file can't be read, and whenever
 * the start would run paused (the marker file, safe mode or the Pause switch), which is the same
 * answer the setting gives once the context is up.
 */
public final class EarlySwitch {
    /** {@link BaseSettings#PAUSED}'s key. */
    static final String PAUSED_KEY = "hushfeed_paused";
    /** {@link BaseSettings#SAFE_MODE}'s key. */
    static final String SAFE_MODE_KEY = "hushfeed_safe_mode";

    /** Where the application comes from. Tests put their own here. */
    interface ApplicationSource {
        @Nullable
        Context application();
    }

    static ApplicationSource source = EarlySwitch::fromActivityThread;

    private EarlySwitch() {
    }

    /**
     * Whether the switch saved under {@code key} is on, for a read made before the settings
     * context exists. Off when nothing can be read or the start runs paused.
     */
    public static boolean isOn(String key) {
        return isOn(null, key);
    }

    /**
     * {@link #isOn(String)}, read through [context] when there is one. A hook inside the
     * application's attachBaseContext needs this, since ActivityThread has no application to hand
     * out until that returns.
     */
    public static boolean isOn(@Nullable Context context, String key) {
        Context application = context != null ? context : source.application();
        if (application == null) {
            Logger.printInfo(() -> "Early switch: no application yet, " + key + " answers off");
            return false;
        }
        try {
            SharedPreferences preferences =
                    application.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (!preferences.getBoolean(key, false)) return false;
            if (preferences.getBoolean(PAUSED_KEY, false) || preferences.getBoolean(SAFE_MODE_KEY, false)) {
                return false;
            }
            File marker = HushfeedPause.markerFile(application);
            if (marker != null && marker.exists()) return false;
            Logger.printInfo(() -> "Early switch: " + key + " is on before the settings context");
            return true;
        } catch (RuntimeException unreadable) {
            Logger.printException(() -> "Early switch: could not read " + key, unreadable);
            return false;
        }
    }

    /** The process's application, or null before Android has made it. */
    @Nullable
    static Context fromActivityThread() {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return application instanceof Context context ? context : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static void resetForTests() {
        source = EarlySwitch::fromActivityThread;
    }
}
