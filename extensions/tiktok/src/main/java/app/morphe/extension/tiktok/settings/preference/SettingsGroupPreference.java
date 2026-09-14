/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

@SuppressWarnings("deprecation")
public final class SettingsGroupPreference extends Preference {
    private final String label;

    public SettingsGroupPreference(Context context, String label) {
        super(context);
        this.label = label;
        setTitle(label);
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        TextView heading = SettingsUi.text(
                context,
                label.toUpperCase(Locale.ROOT),
                12.5f,
                SettingsUi.ACCENT,
                Typeface.BOLD
        );
        heading.setPadding(
                SettingsUi.dp(context, 22),
                SettingsUi.dp(context, 20),
                SettingsUi.dp(context, 22),
                SettingsUi.dp(context, 7)
        );
        heading.setBackgroundColor(SettingsUi.background());
        heading.setContentDescription(label + " section");
        return heading;
    }
}
