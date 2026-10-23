/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.SwitchPreference;
import android.view.View;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.Utils;

/**
 * A switch found by the settings search, flipped where it stands. It saves under the setting's
 * own key, so the page's change handling treats it exactly like the row on its page.
 *
 * <p>Not a {@link TogglePreference}: the title and summary arrive translated from the search
 * index, with the restart sentence and the page name already in them, and that class would look
 * them up again and add the sentence a second time in every language but English.
 */
@SuppressWarnings("deprecation")
final class SearchTogglePreference extends SwitchPreference {
    SearchTogglePreference(Context context, String title, String summary, BooleanSetting setting) {
        super(context);
        setTitle(title);
        setSummary(summary);
        setKey(setting.key);
        setChecked(setting.savedValue());
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Utils.setTitleAndSummaryColor(view);
    }
}
