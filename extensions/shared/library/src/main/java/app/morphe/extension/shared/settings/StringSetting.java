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
public class StringSetting extends Setting<String> {

    public StringSetting(String key, String defaultValue) {
        super(key, defaultValue);
    }
    public StringSetting(String key, String defaultValue, boolean rebootApp) {
        super(key, defaultValue, rebootApp);
    }
    public StringSetting(String key, String defaultValue, boolean rebootApp, boolean includeWithImportExport) {
        super(key, defaultValue, rebootApp, includeWithImportExport);
    }
    public StringSetting(String key, String defaultValue, String userDialogMessage) {
        super(key, defaultValue, userDialogMessage);
    }
    public StringSetting(String key, String defaultValue, Availability availability) {
        super(key, defaultValue, availability);
    }
    public StringSetting(String key, String defaultValue, boolean rebootApp, String userDialogMessage) {
        super(key, defaultValue, rebootApp, userDialogMessage);
    }
    public StringSetting(String key, String defaultValue, boolean rebootApp, Availability availability) {
        super(key, defaultValue, rebootApp, availability);
    }
    public StringSetting(String key, String defaultValue, boolean rebootApp, String userDialogMessage, Availability availability) {
        super(key, defaultValue, rebootApp, userDialogMessage, availability);
    }
    public StringSetting(@NonNull String key, @NonNull String defaultValue, boolean rebootApp, boolean includeWithImportExport, @Nullable String userDialogMessage, @Nullable Availability availability) {
        super(key, defaultValue, rebootApp, includeWithImportExport, userDialogMessage, availability);
    }

    @Override
    protected void load() {
        value = preferences.getString(key, defaultValue);
    }

    @Override
    protected void setValueFromString(@NonNull String newValue) {
        value = Objects.requireNonNull(newValue);
    }

    @Override
    public void saveToPreferences() {
        preferences.saveString(key, value);
    }
}
