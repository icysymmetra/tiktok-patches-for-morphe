/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.content.Context;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public final class SectionHeadingPreference extends Preference {
    private final boolean homeGroup;
    private boolean compact;

    public SectionHeadingPreference(Context context, String title) {
        this(context, title, false);
    }

    public SectionHeadingPreference(Context context, String title, boolean homeGroup) {
        super(context);
        this.homeGroup = homeGroup;
        setTitle(L10n.t(context, title));
        setSelectable(false);
    }

    public static SectionHeadingPreference compact(Context context, String title) {
        SectionHeadingPreference heading = new SectionHeadingPreference(context, title, true);
        heading.compact = true;
        return heading;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int side = SettingsUi.dp(context, 22);
        layout.setPadding(side, SettingsUi.dp(context, compact ? 20 : 16),
                side, SettingsUi.dp(context, 8));

        TextView title = new TextView(context);
        title.setId(android.R.id.title);
        title.setTextColor(SettingsUi.accent());
        title.setTextSize(13);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setAllCaps(true);
        title.setLetterSpacing(0);
        title.setText(getTitle());
        SettingsUi.markHeading(title);
        layout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            title.setText(getTitle());
            title.setTextColor(SettingsUi.accent());
        }
    }
}
