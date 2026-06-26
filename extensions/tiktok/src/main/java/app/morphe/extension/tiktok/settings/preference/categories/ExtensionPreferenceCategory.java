/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/ExtensionPreferenceCategory.java
 */

package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.preference.MorpheTikTokAboutPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class ExtensionPreferenceCategory extends ConditionalPreferenceCategory {
    public ExtensionPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Miscellaneous");
    }

    @Override
    public boolean getSettingsStatus() {
        return true;
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new MorpheTikTokAboutPreference(context));

        addPreference(new TogglePreference(
                context,
                "Sanitize sharing links",
                "Remove tracking parameters from shared links.",
                BaseSettings.SANITIZE_SHARING_LINKS
        ));

        addPreference(new TogglePreference(
                context,
                "Hide suggested accounts",
                "Remove suggested accounts from profile and inbox.",
                BaseSettings.HIDE_SUGGESTED_ACCOUNTS
        ));
    }
}

