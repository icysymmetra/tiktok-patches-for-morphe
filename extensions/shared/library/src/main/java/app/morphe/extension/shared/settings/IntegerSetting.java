/*
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import java.util.Objects;

@SuppressWarnings("unused")
public class IntegerSetting extends Setting<Integer> {

    public IntegerSetting(String key, Integer defaultValue) {
        super(key, defaultValue);
    }
    public IntegerSetting(String key, Integer defaultValue, boolean rebootApp) {
        super(key, defaultValue, rebootApp);
    }
    public IntegerSetting(String key, Integer defaultValue, boolean rebootApp, boolean includeWithImportExport) {
        super(key, defaultValue, rebootApp, includeWithImportExport);
    }
    public IntegerSetting(String key, Integer defaultValue, String userDialogMessage) {
        super(key, defaultValue, userDialogMessage);
    }
    public IntegerSetting(String key, Integer defaultValue, Availability availability) {
        super(key, defaultValue, availability);
    }
    public IntegerSetting(String key, Integer defaultValue, boolean rebootApp, String userDialogMessage) {
        super(key, defaultValue, rebootApp, userDialogMessage);
    }
    public IntegerSetting(String key, Integer defaultValue, boolean rebootApp, Availability availability) {
        super(key, defaultValue, rebootApp, availability);
    }
    public IntegerSetting(String key, Integer defaultValue, boolean rebootApp, String userDialogMessage, Availability availability) {
        super(key, defaultValue, rebootApp, userDialogMessage, availability);
    }
    public IntegerSetting(@NonNull String key, @NonNull Integer defaultValue, boolean rebootApp, boolean includeWithImportExport, @Nullable String userDialogMessage, @Nullable Availability availability) {
        super(key, defaultValue, rebootApp, includeWithImportExport, userDialogMessage, availability);
    }

    private int minimum = Integer.MIN_VALUE;
    private int maximum = Integer.MAX_VALUE;

    /**
     * Declares the range this setting is allowed to hold, and returns the setting so it can
     * be written on the end of the declaration. The number preference reads the range from
     * here rather than being told it a second time at the call site.
     */
    public IntegerSetting withRange(int min, int max) {
        if (min > max) throw new IllegalArgumentException(key + ": " + min + " is above " + max);
        // A default outside its own range would mean the setting could never rest at it.
        if (defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException(key + ": default " + defaultValue
                    + " is outside " + min + " to " + max);
        }
        minimum = min;
        maximum = max;
        // The value on disk was read during construction, before this range existed, so
        // this is the first and only chance to bring it inside. It could have been written
        // by a backup file, or by a build from before the range was declared.
        value = coerce(value);
        return this;
    }

    public boolean hasRange() {
        return minimum != Integer.MIN_VALUE || maximum != Integer.MAX_VALUE;
    }

    public int minimum() {
        return minimum;
    }

    public int maximum() {
        return maximum;
    }

    @NonNull
    @Override
    protected Integer coerce(@NonNull Integer newValue) {
        return Math.max(minimum, Math.min(maximum, newValue));
    }

    @Override
    protected void load() {
        // Not coerced here. This runs from Setting's constructor, which is before this
        // class's own fields are assigned, so minimum and maximum are still zero and a
        // clamp would turn every value into zero. withRange does it instead.
        value = preferences.getIntegerString(key, defaultValue);
    }

    @Override
    protected void setValueFromString(@NonNull String newValue) {
        value = coerce(Integer.valueOf(Objects.requireNonNull(newValue)));
    }

    @Override
    public void saveToPreferences() {
        preferences.saveIntegerString(key, value);
    }
}
