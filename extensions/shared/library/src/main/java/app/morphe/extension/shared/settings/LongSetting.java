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
public class LongSetting extends Setting<Long> {

    public LongSetting(String key, Long defaultValue) {
        super(key, defaultValue);
    }
    public LongSetting(String key, Long defaultValue, boolean rebootApp) {
        super(key, defaultValue, rebootApp);
    }
    public LongSetting(String key, Long defaultValue, boolean rebootApp, boolean includeWithImportExport) {
        super(key, defaultValue, rebootApp, includeWithImportExport);
    }
    public LongSetting(String key, Long defaultValue, String userDialogMessage) {
        super(key, defaultValue, userDialogMessage);
    }
    public LongSetting(String key, Long defaultValue, Availability availability) {
        super(key, defaultValue, availability);
    }
    public LongSetting(String key, Long defaultValue, boolean rebootApp, String userDialogMessage) {
        super(key, defaultValue, rebootApp, userDialogMessage);
    }
    public LongSetting(String key, Long defaultValue, boolean rebootApp, Availability availability) {
        super(key, defaultValue, rebootApp, availability);
    }
    public LongSetting(String key, Long defaultValue, boolean rebootApp, String userDialogMessage, Availability availability) {
        super(key, defaultValue, rebootApp, userDialogMessage, availability);
    }
    public LongSetting(@NonNull String key, @NonNull Long defaultValue, boolean rebootApp, boolean includeWithImportExport, @Nullable String userDialogMessage, @Nullable Availability availability) {
        super(key, defaultValue, rebootApp, includeWithImportExport, userDialogMessage, availability);
    }

    @Override
    protected void load() {
        value = preferences.getLongString(key, defaultValue);
    }

    @Override
    protected void setValueFromString(@NonNull String newValue) {
        value = Long.valueOf(Objects.requireNonNull(newValue));
    }

    @Override
    public void saveToPreferences() {
        preferences.saveLongString(key, value);
    }
}
