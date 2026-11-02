/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/ConditionalPreferenceCategory.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/ConditionalPreferenceCategory.java
 */

package app.morphe.extension.tiktok.settings.preference.categories;

import app.morphe.extension.tiktok.settings.L10n;
import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.view.View;

import app.morphe.extension.tiktok.settings.preference.SettingsUi;

@SuppressWarnings("deprecation")
public abstract class ConditionalPreferenceCategory extends PreferenceCategory {
    public ConditionalPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context);

        if (getSettingsStatus()) {
            screen.addPreference(this);
            addPreferences(context);
        }
    }

    public abstract boolean getSettingsStatus();

    public abstract void addPreferences(Context context);

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        SettingsUi.styleCategory(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

}
