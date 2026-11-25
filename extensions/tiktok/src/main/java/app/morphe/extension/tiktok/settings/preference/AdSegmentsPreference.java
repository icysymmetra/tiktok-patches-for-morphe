/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.tiktok.adskip.AdSkipActions;
import app.morphe.extension.tiktok.settings.L10n;

/** "Marked ads": how many there are and how often they were skipped, opens the list. */
@SuppressWarnings("deprecation")
public final class AdSegmentsPreference extends Preference {
    public AdSegmentsPreference(Context context) {
        super(context);
        setKey("action_ad_skip_segments");
        setTitle("Marked ads");
        refresh();
        setOnPreferenceClickListener(p -> {
            Activity activity = activity(getContext());
            if (activity != null) AdSkipActions.openList(activity);
            return true;
        });
    }

    private void refresh() {
        int count = AdSkipActions.count();
        super.setSummary(count == 0
                ? L10n.t(getContext(), "None yet. In a video, tap Share, then Mark ad, and choose where the ad starts and ends.")
                : L10n.f(getContext(), "%1$s ads, skipped %2$s times. Tap to manage them.",
                        String.valueOf(count), String.valueOf(AdSkipActions.skips())));
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    @Override
    protected void onBindView(View view) {
        refresh();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }
}
