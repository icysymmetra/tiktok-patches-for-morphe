/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.ScreenLayout;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Starts a {@link ScreenLayout} capture: the reader goes to the screen with the thing they want
 * hidden, and its layout goes into the next diagnostic export. The capture runs after the reader
 * has left settings, so what it says comes as a toast over that screen rather than a banner here.
 */
@SuppressWarnings("deprecation")
public final class ScreenLayoutPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    static final String TITLE = "Record a screen's layout";

    public ScreenLayoutPreference(Context context) {
        super(context);
        setKey("action_record_screen_layout");
        setTitle(TITLE);
        applyState();
        setOnPreferenceClickListener(preference -> {
            Activity from = context instanceof Activity ? (Activity) context : Utils.getVisibleActivity();
            ScreenLayout.start(from, result -> {
                String message;
                if (result == ScreenLayout.Result.RECORDED) {
                    message = L10n.t(Utils.getContext(),
                            "Screen layout recorded. Export the diagnostic report to send it.");
                } else if (result == ScreenLayout.Result.STILL_HERE) {
                    message = L10n.t(Utils.getContext(),
                            "Nothing was recorded because Metra settings were still open. Start again, then leave settings.");
                } else {
                    message = L10n.t(Utils.getContext(),
                            "Couldn't record the screen's layout. Start again from Diagnostics.");
                }
                Utils.showToastLong(message);
                applyState();
            });
            Utils.showToastLong(L10n.f(Utils.getContext(),
                    "Go to the screen now. Metra records its layout in %1$d seconds.", seconds()));
            applyState();
            return true;
        });
    }

    private static int seconds() {
        return (int) (ScreenLayout.DELAY_MS / 1000L);
    }

    private void applyState() {
        if (ScreenLayout.isPending()) {
            setSummary(L10n.t(getContext(),
                    "Waiting to record. Go to the screen you want recorded."));
        } else if (!ScreenLayout.lines().isEmpty()) {
            setSummary(L10n.t(getContext(),
                    "A screen's layout is recorded. Export the diagnostic report to send it, or tap to record another."));
        } else {
            setSummary(L10n.f(getContext(),
                    "Tap, then go to the screen with what you want hidden. After %1$d seconds Metra records how that screen is built, without any of its text, for the diagnostic report.",
                    seconds()));
        }
    }

    @Override
    protected void onBindView(View view) {
        applyState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }
}
