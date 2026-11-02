/*
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared.settings;

import static app.morphe.extension.shared.StringRef.str;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

public abstract class Setting<T> {

    /**
     * Indicates if a {@link Setting} is available to edit and use.
     * Typically, this is dependent upon other BooleanSetting(s) set to 'true',
     * but this can be used to call into extension code and check other conditions.
     */
    public interface Availability {
        boolean isAvailable();

        /**
         * @return parent settings (dependencies) of this availability.
         */
        default List<Setting<?>> getParentSettings() {
            return Collections.emptyList();
        }
    }

    /**
     * Availability based on a single parent setting being enabled.
     */
    public static Availability parent(BooleanSetting parent) {
        return new Availability() {
            @Override
            public boolean isAvailable() {
                return parent.savedValue();
            }

            @Override
            public List<Setting<?>> getParentSettings() {
                return Collections.singletonList(parent);
            }
        };
    }

    /**
     * Availability based on a single parent setting being disabled.
     */
    public static Availability parentNot(BooleanSetting parent) {
        return new Availability() {
            @Override
            public boolean isAvailable() {
                return !parent.savedValue();
            }

            @Override
            public List<Setting<?>> getParentSettings() {
                return Collections.singletonList(parent);
            }
        };
    }

    /**
     * Availability based on all parents being enabled.
     */
    public static Availability parentsAll(BooleanSetting... parents) {
        return new Availability() {
            @Override
            public boolean isAvailable() {
                for (BooleanSetting parent : parents) {
                    if (!parent.savedValue()) return false;
                }
                return true;
            }

            @Override
            public List<Setting<?>> getParentSettings() {
                return Collections.unmodifiableList(Arrays.asList(parents));
            }
        };
    }

    /**
     * Availability based on any parent being enabled.
     */
    public static Availability parentsAny(BooleanSetting... parents) {
        return new Availability() {
            @Override
            public boolean isAvailable() {
                for (BooleanSetting parent : parents) {
                    if (parent.savedValue()) return true;
                }
                return false;
            }

            @Override
            public List<Setting<?>> getParentSettings() {
                return Collections.unmodifiableList(Arrays.asList(parents));
            }
        };
    }

    /**
     * All settings that were instantiated.
     * When a new setting is created, it is automatically added to this list.
     */
    private static final List<Setting<?>> SETTINGS = new ArrayList<>();

    /**
     * Map of setting path to setting object.
     */
    private static final Map<String, Setting<?>> PATH_TO_SETTINGS = new HashMap<>();

    /**
     * The preferences file every setting is saved to. A constant, so code that runs before
     * Hushfeed has a context can name the file without loading this class.
     */
    public static final String PREFERENCES_NAME = "morphe_prefs";

    /**
     * Preference all instances are saved to.
     */
    public static final SharedPrefCategory preferences = new SharedPrefCategory(PREFERENCES_NAME);

    @Nullable
    public static Setting<?> getSettingFromPath(String str) {
        return PATH_TO_SETTINGS.get(str);
    }

    /**
     * @return All settings that have been created.
     */
    public static List<Setting<?>> allLoadedSettings() {
        return Collections.unmodifiableList(SETTINGS);
    }

    /** Reload only keys changed by an already committed upgrade, including settings loaded early. */
    public static void reloadAfterMigration(java.util.Collection<String> keys) {
        for (String key : keys) {
            Setting<?> setting = PATH_TO_SETTINGS.get(key);
            if (setting != null) setting.load();
        }
    }

    /**
     * The key used to store the value in the shared preferences.
     */
    public final String key;

    /**
     * The default value of the setting.
     */
    public final T defaultValue;

    /**
     * If the app should be rebooted, if this setting is changed
     */
    public final boolean rebootApp;

    /**
     * If this setting should be included when importing/exporting settings.
     */
    public final boolean includeWithImportExport;

    /**
     * If this setting is available to edit and use.
     * Not to be confused with its status returned from {@link #get()}.
     */
    @Nullable
    private final Availability availability;

    /**
     * Confirmation message to display, if the user tries to change the setting from the default value.
     */
    @Nullable
    public final StringRef userDialogMessage;

    // Must be volatile, as some settings are read/write from different threads.
    // Of note, the object value is persistently stored using SharedPreferences (which is thread safe).
    /**
     * The value of the setting.
     */
    protected volatile T value;

    /**
     * Pause Hushfeed, decided once when the process starts (see {@link HushfeedPause}). While it
     * is on, every setting that changes TikTok answers the value that leaves TikTok as it ships.
     */
    private static volatile boolean pausedForProcess;

    /** Hushfeed's own state rather than a change to TikTok: it keeps its value while paused. */
    private volatile boolean keptWhenPaused;

    /** Whether this process runs with Hushfeed paused. */
    public static boolean isPaused() {
        return pausedForProcess;
    }

    static void setPausedForProcess(boolean paused) {
        pausedForProcess = paused;
    }

    /**
     * Marks settings that hold Hushfeed's own state (a remembered position, an observed list, a
     * counter, a diagnostics option) so they keep answering their value while Hushfeed is paused.
     * Every other setting answers {@link #pausedValue()} then.
     */
    public static void keepWhenPaused(Setting<?>... settings) {
        for (Setting<?> setting : settings) setting.keptWhenPaused = true;
    }

    public boolean isKeptWhenPaused() {
        return keptWhenPaused;
    }

    public Setting(String key, T defaultValue) {
        this(key, defaultValue, false, true, null, null);
    }
    public Setting(String key, T defaultValue, boolean rebootApp) {
        this(key, defaultValue, rebootApp, true, null, null);
    }
    public Setting(String key, T defaultValue, boolean rebootApp, boolean includeWithImportExport) {
        this(key, defaultValue, rebootApp, includeWithImportExport, null, null);
    }
    public Setting(String key, T defaultValue, String userDialogMessage) {
        this(key, defaultValue, false, true, userDialogMessage, null);
    }
    public Setting(String key, T defaultValue, Availability availability) {
        this(key, defaultValue, false, true, null, availability);
    }
    public Setting(String key, T defaultValue, boolean rebootApp, String userDialogMessage) {
        this(key, defaultValue, rebootApp, true, userDialogMessage, null);
    }
    public Setting(String key, T defaultValue, boolean rebootApp, Availability availability) {
        this(key, defaultValue, rebootApp, true, null, availability);
    }
    public Setting(String key, T defaultValue, boolean rebootApp, String userDialogMessage, Availability availability) {
        this(key, defaultValue, rebootApp, true, userDialogMessage, availability);
    }

    /**
     * A setting backed by a shared preference.
     *
     * @param key                     The key used to store the value in the shared preferences.
     * @param defaultValue            The default value of the setting.
     * @param rebootApp               If the app should be rebooted, if this setting is changed.
     * @param includeWithImportExport If this setting should be shown in the import/export dialog.
     * @param userDialogMessage       Confirmation message to display, if the user tries to change the setting from the default value.
     * @param availability            Condition that must be true, for this setting to be available to configure.
     */
    public Setting(String key,
                   T defaultValue,
                   boolean rebootApp,
                   boolean includeWithImportExport,
                   @Nullable String userDialogMessage,
                   @Nullable Availability availability
    ) {
        this.key = Objects.requireNonNull(key);
        this.value = this.defaultValue = Objects.requireNonNull(defaultValue);
        this.rebootApp = rebootApp;
        this.includeWithImportExport = includeWithImportExport;
        this.userDialogMessage = (userDialogMessage == null) ? null : new StringRef(userDialogMessage);
        this.availability = availability;

        SETTINGS.add(this);
        if (PATH_TO_SETTINGS.put(key, this) != null) {
            Logger.printException(() -> this.getClass().getSimpleName()
                    + " error: Duplicate Setting key found: " + key);
        }

        load();
    }

    /**
     * Migrate an old Setting value previously stored in a different SharedPreference.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void migrateFromOldPreferences(SharedPrefCategory oldPrefs, Setting setting) {
        if (!Utils.isMainProcess()) {
            Logger.printInfo(() -> "Ignored settings migration from a secondary process: " + setting.key);
            return;
        }
        String settingKey = setting.key;
        if (!oldPrefs.preferences.contains(settingKey)) {
            return; // Nothing to do.
        }

        Object newValue = setting.savedValue();
        final Object migratedValue;
        if (setting instanceof BooleanSetting) {
            migratedValue = oldPrefs.getBoolean(settingKey, (Boolean) newValue);
        } else if (setting instanceof IntegerSetting) {
            migratedValue = oldPrefs.getIntegerString(settingKey, (Integer) newValue);
        } else if (setting instanceof LongSetting) {
            migratedValue = oldPrefs.getLongString(settingKey, (Long) newValue);
        } else if (setting instanceof FloatSetting) {
            migratedValue = oldPrefs.getFloatString(settingKey, (Float) newValue);
        } else if (setting instanceof StringSetting) {
            migratedValue = oldPrefs.getString(settingKey, (String) newValue);
        } else {
            Logger.printException(() -> "Unknown setting: " + setting);
            // Remove otherwise it'll show a toast on every launch.
            oldPrefs.preferences.edit().remove(settingKey).apply();
            return;
        }

        if (migratedValue.equals(newValue)) {
            Logger.printDebug(() -> "Value does not need migrating: " + settingKey);
            oldPrefs.removeKey(settingKey);
            return; // Old value is already equal to the new setting value.
        }

        Logger.printDebug(() -> "Migrating old preference value into current preference: " + settingKey);
        if (setting.save(migratedValue)) {
            oldPrefs.removeKey(settingKey);
        } else {
            Logger.printException(() -> "Kept old preference after migration failed: " + settingKey);
        }
    }

    /**
     * Sets, but does _not_ persistently save the value.
     * This method is only to be used by the Settings preference code.
     * <p>
     * This intentionally is a static method to deter
     * accidental usage when {@link #save(Object)} was intended.
     */
    public static void privateSetValueFromString(Setting<?> setting, String newValue) {
        setting.setValueFromString(newValue);

        // Clear the preference value since default is used, to allow changing
        // the default for a future release.  Without this after upgrading
        // the saved value will be whatever was the default when the app was first installed.
        if (setting.isSetToDefault()) {
            try {
                setting.removeFromPreferences();
            } catch (RuntimeException failure) {
                // The framework already stored the selected default. Leaving that explicit value
                // is semantically correct, even if the cleanup that normally removes it failed.
                Logger.printException(() -> "Could not clear explicit default: " + setting.key, failure);
            }
        }
    }

    /**
     * Sets the value of {@link #value}, but do not save to {@link #preferences}.
     */
    protected abstract void setValueFromString(String newValue);

    /**
     * The value this setting will really hold, given one it has been asked to hold. A
     * setting with a range gives back the nearest value inside it; every other setting gives
     * back what it was handed.
     *
     * <p>Every path that assigns {@link #value} goes through this, because a value arrives
     * from a restored backup file as readily as from a dialog, and only the dialog asks
     * questions about it.
     */
    @NonNull
    protected T coerce(@NonNull T newValue) {
        return newValue;
    }

    /**
     * The value as saving it would store it, for a caller that compares what it recorded with
     * what was saved: a restore writes what {@link #coerce} returns, not the file's own number.
     * A value this setting would refuse comes back as it was.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public final Object savedFormOf(@Nullable Object value) {
        if (value == null) return null;
        try {
            return coerce((T) value);
        } catch (RuntimeException refused) {
            return value;
        }
    }

    /**
     * Load and set the value of {@link #value}.
     */
    protected abstract void load();

    /**
     * Persistently saves the value.
     */
    public final boolean save(T newValue) {
        // Every Setting shares one preference file. Keep the live-value swap, disk commit and
        // possible rollback in the same class lock as saveAll(), so a failed write cannot roll a
        // newer successful write back after the newer caller has already returned.
        synchronized (Setting.class) {
            if (!Utils.isMainProcess()) {
                Logger.printInfo(() -> "Ignored persistent setting write from a secondary process: " + key);
                return false;
            }
            newValue = coerce(Objects.requireNonNull(newValue));
            if (value.equals(newValue)) {
                return true;
            }

            // Must set before saving to preferences (otherwise importing fails to update UI correctly).
            T previousValue = value;
            value = newValue;
            try {
                persistCurrentValue();
                return true;
            } catch (RuntimeException failure) {
                // A failed commit means the value that survives a restart is still the old one.
                // Keep the live process on that same value, then make a best effort to restore
                // storage in case a platform implementation reports failure after touching it.
                value = previousValue;
                try {
                    persistCurrentValue();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                Logger.printException(() -> "Could not save setting: " + key, failure);
                return false;
            }
        }
    }

    private void persistCurrentValue() {
        if (defaultValue.equals(value)) removeFromPreferences();
        else saveToPreferences();
    }

    /**
     * Save {@link #value} to {@link #preferences}.
     */
    protected abstract void saveToPreferences();

    /**
     * Remove {@link #value} from {@link #preferences}.
     */
    protected final void removeFromPreferences() {
        Logger.printDebug(() -> "Clearing stored preference value (reset to default): " + key);
        preferences.removeKey(key);
    }

    /**
     * What the setting says now, which is what every hook reads. While Hushfeed is paused, a
     * setting that changes TikTok answers {@link #pausedValue()}; the saved value is untouched.
     */
    @NonNull
    public final T get() {
        return pausedForProcess && !keptWhenPaused ? pausedValue() : currentValue();
    }

    /**
     * What {@link #get()} answers when the setting isn't paused. That's the saved value, unless
     * a setting adds something the reader turned on for a while without saving it, as a timed
     * diagnostic capture does for {@link BaseSettings#DEBUG}.
     */
    @NonNull
    protected T currentValue() {
        return value;
    }

    /**
     * The value this setting holds, paused or not. The settings screen, backups and undo read
     * this: they show and keep what the user chose, not what a paused TikTok is answered.
     */
    @NonNull
    public final T savedValue() {
        return value;
    }

    /**
     * The answer while paused. For a value setting the default is the unpatched behaviour: an
     * empty list, a zero limit, "auto", "default". A switch answers false instead.
     */
    @NonNull
    protected T pausedValue() {
        return defaultValue;
    }

    /**
     * Identical to calling {@link #save(Object)} using {@link #defaultValue}.
     *
     * @return The newly saved default value.
     */
    public T resetToDefault() {
        save(defaultValue);
        return defaultValue;
    }

    /** Apply a validated batch in one preference transaction. Call on a worker thread. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized void saveAll(Map<Setting<?>, Object> updates) throws java.io.IOException {
        saveAll(updates, false);
    }

    /**
     * @param deviceState the values are this device's own earlier state: an undo copy, a rollback,
     *                    a change put back after an interruption. A bound that refuses one of
     *                    those is newer than the value, which was already in effect, so it goes
     *                    back as it was rather than failing the way back. A value equal to the one
     *                    already stored is kept the same way whoever writes it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized void saveAll(Map<Setting<?>, Object> updates, boolean deviceState)
            throws java.io.IOException {
        if (!Utils.isMainProcess()) {
            throw new java.io.IOException("Persistent settings are writable only from the main process");
        }
        Map<Setting<?>, Object> previous = new HashMap<>();
        Map<Setting<?>, Object> bounded = new HashMap<>();
        for (var entry : updates.entrySet()) {
            Setting setting = entry.getKey();
            Object next = entry.getValue();
            Class<?> type = setting.defaultValue instanceof Enum
                    ? ((Enum) setting.defaultValue).getDeclaringClass() : setting.defaultValue.getClass();
            if (next == null || !type.isInstance(next)) {
                throw new IllegalArgumentException("Invalid value for " + setting.key);
            }
            // A backup file is not a dialog and was never asked to stay in range. Into a copy
            // rather than back into the caller's map, which need not accept being written to.
            Object value;
            try {
                value = setting.coerce(next);
            } catch (IllegalArgumentException refused) {
                if (!deviceState && !next.equals(setting.savedValue())) throw refused;
                value = next;
            }
            bounded.put(setting, value);
            previous.put(setting, setting.savedValue());
        }
        java.io.IOException failure;
        try {
            if (writeBatch(bounded)) return;
            failure = new java.io.IOException("Could not save settings");
        } catch (RuntimeException refused) {
            failure = new java.io.IOException("Could not save settings", refused);
        }
        // Restore all live values before touching storage again: even creating an editor or
        // adding its first key can throw, leaving a later value from the attempted batch live.
        for (var entry : previous.entrySet()) ((Setting) entry.getKey()).value = entry.getValue();
        try {
            if (!writeBatch(previous)) {
                failure.addSuppressed(new java.io.IOException("Could not roll back settings; use Undo"));
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        throw failure;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean writeBatch(Map<Setting<?>, Object> values) {
        var editor = preferences.preferences.edit();
        for (var entry : values.entrySet()) {
            Setting setting = entry.getKey();
            Object next = entry.getValue();
            setting.value = next;
            if (setting.defaultValue.equals(next)) editor.remove(setting.key);
            else if (next instanceof Boolean) editor.putBoolean(setting.key, (Boolean) next);
            else editor.putString(setting.key, next instanceof Enum ? ((Enum) next).name() : next.toString());
        }
        return editor.commit();
    }

    /**
     * @return if this setting can be configured and used.
     */
    public boolean isAvailable() {
        return availability == null || availability.isAvailable();
    }

    /**
     * Get the parent Settings that this setting depends on.
     * @return List of parent Settings, or empty list if no dependencies exist.
     *         Defensive: handles null availability or missing getParentSettings() override.
     */
    public List<Setting<?>> getParentSettings() {
        return availability == null
                ? Collections.emptyList()
                : Objects.requireNonNullElse(availability.getParentSettings(), Collections.emptyList());
    }

    /**
     * @return if the currently set value is the same as {@link #defaultValue}.
     */
    public boolean isSetToDefault() {
        return value.equals(defaultValue);
    }

    @NonNull
    @Override
    public String toString() {
        return key + "=" + savedValue();
    }

    // region Import / export

}
