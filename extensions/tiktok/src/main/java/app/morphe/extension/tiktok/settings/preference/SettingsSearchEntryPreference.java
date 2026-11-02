/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.settings.L10n;

/** A compact entry to the searchable settings index without opening the keyboard on home. */
@SuppressWarnings("deprecation")
public final class SettingsSearchEntryPreference extends Preference {
    public static final String KEY = "hushfeed_search_entry";
    public static final String ROW_TAG = "hushfeed_search_entry_row";

    public SettingsSearchEntryPreference(Context context, OnPreferenceClickListener listener) {
        super(context);
        setKey(KEY);
        setPersistent(false);
        setTitle(L10n.t(context, "Search settings"));
        setOnPreferenceClickListener(listener);
        setOrder(-800);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setTag(ROW_TAG);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(SettingsUi.dp(context, 48));
        row.setBackground(new android.graphics.drawable.InsetDrawable(
                SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_SETTINGS_FIELD,
                        SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_FIELD, false)),
                SettingsUi.dp(context, 8), 0, SettingsUi.dp(context, 8), 0));
        row.setPaddingRelative(SettingsUi.dp(context, 22), SettingsUi.dp(context, 8),
                SettingsUi.dp(context, 22), SettingsUi.dp(context, 8));
        SettingsUi.markAsButton(row);

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(SettingsMenuPreference.lineIconDrawable(context, SettingsMenuPreference.Icon.SEARCH));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(SettingsUi.dp(context, 24), SettingsUi.dp(context, 24)));
        TextView title = SettingsUi.text(context, getTitle().toString(), 15,
                SettingsUi.textSecondary(), 0);
        title.setId(android.R.id.title);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(SettingsUi.dp(context, 14));
        row.addView(title, labelParams);
        return row;
    }
}
